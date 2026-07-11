// mx_like_simple_core — 4-way dot-product unit with BF16 accumulator.
//
// Architecture: Lutz et al. ARITH 2024 "Fused FP8 4-Way Dot Product With
// Scaling and FP32 Accumulation", Section II.B (Early Accumulation).
// Extensions over Lutz: (1) per-config tailored widths (not FP32 worst-case),
// (2) fractional scale mantissa via post-tree multiplier, (3) BF16 output.
//
// Datapath — all combinational within one cycle, only the accreg is a register:
//
//   S1  Per-lane multiply:  product mantissa + exponent + sign per lane
//   S2  Long-integer align + sum:  each product left-shifted into the SoP
//       field by (|P_min| + prodExp[i]), then signed accumulate
//   S3  Post-tree scale mant multiply (skipped when UE8M0)
//   S4  Bidirectional accreg align to term's anchor via exp compare
//   S5  Signed CPA sum (final adder width)
//   S6  Sign-magnitude conversion + LSC-based normalize
//   S7  RNE to 7-bit BF16 mantissa, write back accreg
//
// Simplifications (deliberately minimal — grow as needed):
//   - Subnormal INPUTS accepted but treated as normal (no hidden bit gating)
//   - No NaN / Inf propagation
//   - Underflow to zero at output; overflow saturates via exp field wrap

package mx_simple

import chisel3._
import chisel3.util._

/** BF16 accumulator record: 1 sign + 8 exp + 7 mant. */
class BF16Reg extends Bundle {
  val sign = Bool()
  val exp  = UInt(BF16.expBits.W)
  val mant = UInt(BF16.mantBits.W)
}

/** One element operand: 1 sign + eE exp + eM mant. */
class ElemOperand(val el: Elem) extends Bundle {
  val sign = Bool()
  val exp  = UInt(el.e.W)
  val mant = UInt(el.m.W)
}

/** One scale operand: eE exp + eM mant (unsigned). */
class ScaleOperand(val sc: Scale) extends Bundle {
  val exp  = UInt(sc.e.W)
  val mant = UInt(sc.m.W)
}

/** IO bundle for SimpleDPU. All N lanes, both scale halves, and current
  * accreg come in each cycle. The accreg is updated (or cleared) at the
  * rising edge based on `enable` / `clearAcc`.
  */
class SimpleDPUIO(cfg: DPUConfig) extends Bundle {
  val enable   = Input(Bool())
  val clearAcc = Input(Bool())
  val a        = Input(Vec(cfg.N, new ElemOperand(cfg.A)))
  val w        = Input(Vec(cfg.N, new ElemOperand(cfg.W)))
  val scaleA   = Input(new ScaleOperand(cfg.S))
  val scaleW   = Input(new ScaleOperand(cfg.S))
  val accOut   = Output(new BF16Reg)
}

class SimpleDPU(val cfg: DPUConfig) extends Module {
  val io = IO(new SimpleDPUIO(cfg))
  val w  = Widths(cfg)
  private val A = cfg.A; private val W = cfg.W; private val S = cfg.S
  private val N = cfg.N

  // ── State: the sole register in this design ────────────────
  val accreg = RegInit({
    val z = Wire(new BF16Reg)
    z.sign := false.B
    z.exp  := 0.U
    z.mant := 0.U
    z
  })

  // ── S1  Per-lane multiply ──────────────────────────────────
  // Compose full significand:
  //   FP:   {hidden = (exp!=0), mant}   — width = A.m + 1
  //   INT8: {0, mant}                    — width = A.m + 1 (MSB padded)
  // For INT, the significand is just the magnitude bits; no hidden bit.
  private val opAsig = VecInit(io.a.map { a =>
    val hidden: Bool = if (A.hasHiddenBit) (a.exp =/= 0.U) else false.B
    Cat(hidden, a.mant)
  })                                            // (A.m + 1) bits
  private val opWsig = VecInit(io.w.map { b =>
    val hidden: Bool = if (W.hasHiddenBit) (b.exp =/= 0.U) else false.B
    Cat(hidden, b.mant)
  })                                            // (W.m + 1) bits

  // Product mantissa: unsigned, width = prodMantW = (A.m+1)+(W.m+1).
  private val prodMant = VecInit((0 until N).map { i =>
    (opAsig(i) * opWsig(i))(w.prodMantW - 1, 0)
  })

  // Product exponent (unbiased, signed):
  //   FP:   (A.exp - A.bias) + (W.exp - W.bias) with subnormal fix
  //         (biased exp == 0 → treat as biased 1)
  //   INT8: impSc_A + impSc_W (constant, no per-lane variability)
  private val expSignedW = math.max(math.max(A.e, W.e), 6) + 3  // headroom
  private val prodExp = VecInit((0 until N).map { i =>
    val aeUn: SInt =
      if (A.isInt) A.impSc.S(expSignedW.W)
      else {
        val biasedFixed =
          Mux(io.a(i).exp === 0.U, 1.S(expSignedW.W),
                                    io.a(i).exp.zext.pad(expSignedW))
        biasedFixed - A.bias.S
      }
    val weUn: SInt =
      if (W.isInt) W.impSc.S(expSignedW.W)
      else {
        val biasedFixed =
          Mux(io.w(i).exp === 0.U, 1.S(expSignedW.W),
                                    io.w(i).exp.zext.pad(expSignedW))
        biasedFixed - W.bias.S
      }
    (aeUn + weUn).pad(expSignedW)
  })

  private val prodSign = VecInit((0 until N).map { i =>
    io.a(i).sign ^ io.w(i).sign
  })

  // ── S2  Long-integer align + signed tree sum ──────────────
  // Each product is placed at bit position `sopShift + prodExp[i]` in the
  // SoP field. sopShift = |P_min_unnorm|. Signed 2's-complement.
  //
  // shift_amount range: [0, sopShift + pMax] = [0, |P_min| + pMax]
  private val shiftAmtW = log2Ceil(w.sopShift + w.pMax + 1).max(1)

  private val signedProd = VecInit((0 until N).map { i =>
    val mag  = prodMant(i).zext                    // widen to signed
    val sgn  = Mux(prodSign(i), -mag, mag)          // 2's complement if neg
    sgn.pad(w.signedProdW).asSInt
  })

  private val alignedProd = VecInit((0 until N).map { i =>
    val shiftAmt = (prodExp(i) + w.sopShift.S).asUInt.pad(shiftAmtW)
    // Sign-extend shift so we can place into the wide sopFieldW.
    (signedProd(i).pad(w.sopFieldW) << shiftAmt)(w.sopFieldW - 1, 0).asSInt
  })

  // Tree sum: signed. Chisel's reduce fold produces a signed adder chain;
  // synthesis will optimize into a Wallace/CSA style automatically.
  private val sopField = alignedProd.reduce(_ + _)  // width = sopFieldW

  // ── S3  Post-tree scale mant multiply ──────────────────────
  // scale_mant_prod = (1.sA_mant) * (1.sW_mant), unsigned, 2*(mS+1) bits.
  // UE8M0: mS=0 → scale_mant_prod is not synthesized (empty branch).
  private val scaleAsig =
    if (S.m == 0) 1.U(1.W) else Cat(1.U(1.W), io.scaleA.mant)
  private val scaleWsig =
    if (S.m == 0) 1.U(1.W) else Cat(1.U(1.W), io.scaleW.mant)

  private val scaledTerm: SInt = if (w.scaleMantProdW == 0) {
    sopField
  } else {
    val smp = (scaleAsig * scaleWsig).pad(w.scaleMantProdW + 1)  // unsigned
    (sopField * smp.zext).pad(w.scaledTermW).asSInt
  }

  // ── Scale exp addition ────────────────────────────────────
  private val scExpW = S.e + 2  // headroom for signed sum
  private val scaleExpSum = (
    io.scaleA.exp.zext.pad(scExpW) +
    io.scaleW.exp.zext.pad(scExpW) -
    (2 * S.bias).S(scExpW.W)
  )  // signed unbiased scale exp

  // ── S4  Accreg alignment ─────────────────────────────────
  // Compute shift for accreg significand:
  //   acc_shift = termUnitPos - (BF16.sigBits - 1)
  //             + accreg_unbiased_exp - scaleExpSum
  //
  // Positive acc_shift = shift accreg LEFT into higher bits of the field.
  // Negative = shift RIGHT (accreg goes below term's anchor).
  //
  // accreg significand: 8 bits (1 hidden + 7 fraction). When accreg is zero
  // or subnormal, hidden = 0.
  private val accHidden = accreg.exp =/= 0.U
  private val accSig    = Cat(accHidden, accreg.mant)         // BF16.sigBits
  private val accUnbiasedExp = accreg.exp.zext - BF16.bias.S

  // Total shift math needs to accommodate:
  //   +|termUnitPos - (sigBits-1)| for the constant offset
  //   +max BF16 exp (about 127) for accreg upshift
  //   -max scale exp (~256 for wide UE8M0) for downshift
  private val accShiftW = log2Ceil(
    w.termUnitPos + BF16.bias + (1 << S.e)
  ).max(4) + 2

  private val constOffset = w.termUnitPos - (BF16.sigBits - 1)
  private val accShiftSigned = (
    constOffset.S(accShiftW.W) + accUnbiasedExp.pad(accShiftW) -
      scaleExpSum.pad(accShiftW)
  )

  // Bidirectional shifter: shift left if positive, right (with sticky) if
  // negative. Clamp both directions to prevent negative-width shifters.
  private val accShiftLimit = w.finalAdderW - 1
  private val accShiftIsRight = accShiftSigned < 0.S
  private val accShiftMag = Mux(accShiftIsRight,
                                (-accShiftSigned).asUInt,
                                accShiftSigned.asUInt)
  private val accShiftClamped = Mux(accShiftMag > accShiftLimit.U,
                                    accShiftLimit.U,
                                    accShiftMag)

  // 2's complement of accreg significand if negative sign. accSig is 8-bit
  // unsigned; zext gives us 9-bit SInt; the negation may need one more bit.
  private val accSigMag: SInt = accSig.zext                          // 9-bit SInt
  private val accSignedSig: SInt = Mux(accreg.sign, -accSigMag, accSigMag)

  // Place accreg into a wide field aligned to term. Start position: same as
  // term's "1x2^0" line (termUnitPos). Left shift raises accreg; right shift
  // brings it below termUnitPos.
  private val accWide: SInt = accSignedSig.pad(w.finalAdderW)

  // Left-shift path — SInt << UInt grows the width; reinterpret truncated
  // bits as SInt to keep 2's-complement semantics.
  private val accShiftedLeftGrown: SInt = accWide << accShiftClamped
  private val accShiftedLeftS: SInt =
    accShiftedLeftGrown.asUInt.apply(w.finalAdderW - 1, 0).asSInt

  // Right-shift path — arithmetic right shift preserves sign; may need pad.
  private val accRightShift = Mux(accShiftIsRight, accShiftClamped, 0.U)
  private val accLostMask = ((1.U << accRightShift) - 1.U)
  private val accSticky = (accSignedSig.asUInt.pad(w.finalAdderW) & accLostMask).orR
  private val accShiftedRightS: SInt = (accWide >> accRightShift).pad(w.finalAdderW)

  private val accAligned: SInt = Mux(accShiftIsRight, accShiftedRightS, accShiftedLeftS)

  // ── S5  Signed CPA sum ───────────────────────────────────
  // Place scaled_term at LSB region of the final adder field (its own sign
  // extended), then add accreg (already aligned).
  private val termInField: SInt = scaledTerm.pad(w.finalAdderW)
  private val stickyLSB: SInt = Mux(accShiftIsRight, accSticky.asSInt.pad(1), 0.S(1.W))
  private val sumField: SInt = termInField + accAligned + stickyLSB

  // ── S6  Normalize (sign-magnitude + LSC) ────────────────
  // Sign of the sum: sign bit of sumField.
  private val sumSign = sumField(w.finalAdderW - 1)
  private val sumMag = Mux(sumSign, (-sumField).asUInt, sumField.asUInt)(
    w.finalAdderW - 1, 0)

  // Leading zero count of the magnitude (0..finalAdderW-1).
  // PriorityEncoder(Reverse) gives leading zeros for a non-zero value.
  private val lzcW = log2Ceil(w.finalAdderW).max(1) + 1
  private val sumMagRev = Reverse(sumMag)
  private val lz = PriorityEncoder(sumMagRev).pad(lzcW)
  private val sumIsZero = sumMag === 0.U

  // Shift left by lz to bring MSB to bit finalAdderW-1.
  private val normalized = (sumMag << lz)(w.finalAdderW - 1, 0)

  // ── S7  RNE to BF16 ─────────────────────────────────────
  // After normalization, the MSB is at bit finalAdderW-1. That MSB
  // represents the value 2^k where k depends on where the "1x2^0" line
  // sat in the pre-shift field. Compute the final exponent from:
  //   result_unbiased_exp = scaleExpSum
  //                       + (finalAdderW - 1 - termUnitPos_in_finalAdder)
  //                       - lz
  //
  // "termUnitPos_in_finalAdder" — the term was placed at position
  // termUnitPos within its scaledTermW-field. In the finalAdderW field
  // (which is scaledTermW-1 + acc_sig + 2), the term's 2^0 bit sits at
  // the same absolute position (bit termUnitPos from LSB).
  private val termUnitPosInAdder = w.termUnitPos
  // Result exp = scaleExp + (position of MSB - position of 2^0)
  //           = scaleExp + ((finalAdderW - 1 - lz) - termUnitPosInAdder)
  private val resExpUnbiased = scaleExpSum.pad(lzcW + S.e + 4) +
    (w.finalAdderW - 1 - termUnitPosInAdder).S -
    lz.zext

  // RNE: extract top (sigBits + 3) bits — hidden + 7 mant + G + R + S
  private val gField = normalized(w.finalAdderW - 1, w.finalAdderW - BF16.sigBits)
    .pad(BF16.sigBits)  // 8 bits: hidden + mant
  private val guardBit = normalized(w.finalAdderW - BF16.sigBits - 1)
  private val roundBit = normalized(w.finalAdderW - BF16.sigBits - 2)
  private val stickyBits = normalized(w.finalAdderW - BF16.sigBits - 3, 0).orR
  private val stickyPlus = stickyBits | (stickyLSB =/= 0.S)

  // RNE: round up when guard is 1 AND (round | sticky | keep_LSB) is 1.
  private val roundUp = guardBit && (roundBit || stickyPlus || gField(0))
  private val gFieldPlusRnd = (gField + roundUp.asUInt).pad(BF16.sigBits + 1)

  // If rounding caused a carry-out (hidden bit went from 1.x -> 10.x), shift
  // right by one and bump exp.
  private val roundCarry = gFieldPlusRnd(BF16.sigBits)
  private val mantAfterRnd = Mux(roundCarry,
                                 gFieldPlusRnd(BF16.sigBits, 1),
                                 gFieldPlusRnd(BF16.sigBits - 1, 0)
                                 )(BF16.mantBits - 1, 0)
  private val expAfterRnd = resExpUnbiased + Mux(roundCarry, 1.S, 0.S)

  // ── S8  Pack BF16 ──────────────────────────────────────
  private val finalBiased = expAfterRnd + BF16.bias.S
  private val expUnderflow = finalBiased <= 0.S
  private val expOverflow  = finalBiased >= ((1 << BF16.expBits) - 1).S

  private val newAcc = Wire(new BF16Reg)
  newAcc.sign := sumSign && !sumIsZero
  newAcc.exp  := Mux(sumIsZero || expUnderflow, 0.U,
                 Mux(expOverflow, ((1 << BF16.expBits) - 2).U,   // saturate
                     finalBiased.asUInt.pad(BF16.expBits)))
  newAcc.mant := Mux(sumIsZero || expUnderflow, 0.U, mantAfterRnd)

  // ── State update ───────────────────────────────────────
  when(io.clearAcc) {
    accreg.sign := false.B
    accreg.exp  := 0.U
    accreg.mant := 0.U
  }.elsewhen(io.enable) {
    accreg := newAcc
  }

  io.accOut := accreg
}
