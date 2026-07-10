package mx.mac

import chisel3._
import chisel3.util._

/** Per-PE BFP variant with a TRUE wide-fixed-point lossless accumulator,
 *  matching the description of the alt baseline in Section [acc-truncation].
 *
 *  Pipeline (one BFP_PE):
 *
 *      4× CustomOperator                 ─┐  per-lane MAC (3-bit × 3-bit mant)
 *      → FixedFPReductionTree            ─┤  tree-add, full-precision (skipFinalRound)
 *      → ScaleAddition                   ─┘  compose shared scale, no normalize
 *
 *      → WideFixedShifter                ─┐
 *      → accBits-wide signed integer add ─┤  NO RNE, NO LZD per cycle
 *      → accReg                          ─┘
 *
 *      → ONE terminal LZD + RNE          → (1+8+outMantBits)-bit narrow FP
 *                                          (only when reading the acc out)
 *
 *  Replaces the (ScaleToFPn + FPNAdder + narrow-FP accReg) tail of the
 *  early-rounded baseline with a wide fixed-point register and a single
 *  terminal LZD/RNE conversion.  The intermediate RNE rounds inside
 *  ScaleToFPn and FPNAdder are eliminated.
 *
 *  Value semantics from ScaleAddition's output (empirically matched against
 *  ScaleToFP23b's bias_adjust constant of 0xC1 = 193):
 *      value = sign · saMant · 2^(saExp − 8)
 *  i.e. mant bit 0 has weight 2^(saExp − 8).
 *
 *  @param scfg         ScaleAddConfig (same as our early-rounded design)
 *  @param vectorSize   per-cycle K width (default 4)
 *  @param K            total accumulation depth (informational; used for
 *                      naming, not for sizing here)
 *  @param accBits      fixed-point accumulator width (default 128).  Pick
 *                      large enough to span the worst-case dynamic range:
 *                      saExp range + saMantW + log₂(K) ≈ 130 for our
 *                      E5M2²×UE6M2 worked example.
 *  @param accExpBase   accReg bit 0 represents 2^(accExpBase).  Default
 *                      −64 centres the typical exp range mid-register.
 *  @param outMantBits  narrow FP output mantissa width — match the design
 *                      under test's M_acc so the requant-input geometry
 *                      stays identical across the comparison.
 *  @param treeArch     tree arch override (default Generic — matches the
 *                      noEarlyRNE / ours_M12 baselines).
 */
class BFPPELosslessAcc(
  val scfg:        ScaleAddConfig,
  val vectorSize:  Int      = 4,
  val K:           Int      = 2048,
  val accBits:     Int      = 101,
  val accExpBase:  Int      = -64,
  val outMantBits: Int      = 12,
  val treeArch:    TreeArch = TreeArch.Generic,
) extends Module {
  override def desiredName =
    s"BFP_PE_LosslessAcc_${scfg.elementTypeA.name}_x_${scfg.elementTypeB.name}_${scfg.stype.name}_acc${accBits}_out${outMantBits}"

  private val wA = scfg.elementTypeA.totalWidth
  private val wB = scfg.elementTypeB.totalWidth

  // ── Width inference (mirror FDPUPostScaleReductionTree's noEarlyRNE path) ──
  private val SAFETY_G  = 3
  private val log2N     = log2Ceil(vectorSize.max(2))
  // absMagW = tree's internal magnitude width (includes guard slack)
  private val absMagW   = scfg.resOperatorMantWidth + scfg.productExpRange + SAFETY_G + log2N
  // skipFinalRound path requires outMantW == absMagW exactly (CustomReduction.scala:64)
  private val treeOutMantW = absMagW
  private val treeExpW     = scfg.resOperatorExpWidth + 2

  // ── Empirical mantissa scale offset.
  //   Derived from ScaleToFP23b's bias_adjust constant (0xC1 = 193):
  //     IEEE_exp_unbiased = saExp − lzc + 66
  //   Implies (mant_normalized at bit 74) corresponds to 2^(IEEE_exp_unbiased),
  //   so mant bit 0 has weight 2^(saExp − 8).  i.e. MANT_SCALE = −8.
  private val MANT_SCALE = -8

  val io = IO(new Bundle {
    val op_a_i        = Input(UInt((wA * vectorSize).W))
    val op_b_i        = Input(UInt((wB * vectorSize).W))
    val share_exp_A_i = Input(UInt(scfg.stype.totalScaleWidth.W))
    val share_exp_B_i = Input(UInt(scfg.stype.totalScaleWidth.W))
    val validIn       = Input(Bool())
    val resetAcc      = Input(Bool())
    val validOut      = Output(Bool())
    val accOut        = Output(UInt((1 + 8 + outMantBits).W))
  })

  // ──────────────────────────────────────────────────────────────────────────
  // 1) Per-lane CustomOperator (no LZD, just raw 3×3 mult)
  // ──────────────────────────────────────────────────────────────────────────
  val laneOp = Wire(Vec(vectorSize,
    new CustomFP(scfg.resOperatorExpWidth, scfg.resOperatorMantWidth)))
  for (i <- 0 until vectorSize) {
    val op = Module(new CustomOperator(
      OperatorConfig(scfg.elementTypeA, scfg.elementTypeB)))
    op.io.inA := io.op_a_i((i + 1) * wA - 1, i * wA)
    op.io.inB := io.op_b_i((i + 1) * wB - 1, i * wB)
    laneOp(i).sign := op.io.outSign
    laneOp(i).mant := op.io.outMant
    laneOp(i).exp  := op.io.outExp
  }

  // ──────────────────────────────────────────────────────────────────────────
  // 2) FixedFPReductionTree (full precision; skipFinalRound = true)
  // ──────────────────────────────────────────────────────────────────────────
  val tree = Module(new FixedFPReductionTree(
    expW            = treeExpW,
    inMantW         = scfg.resOperatorMantWidth,
    outMantW        = treeOutMantW,
    vectorSize      = vectorSize,
    productExpRange = scfg.productExpRange,
    arch            = treeArch,
    skipFinalRound  = true
  ))
  for (i <- 0 until vectorSize) {
    tree.io.inputs(i).sign := laneOp(i).sign
    tree.io.inputs(i).mant := laneOp(i).mant
    tree.io.inputs(i).exp  := laneOp(i).exp
  }
  val treeOut = tree.io.out

  // ──────────────────────────────────────────────────────────────────────────
  // 3) ScaleAddition — compose shared scales (no normalize, no RNE)
  // ──────────────────────────────────────────────────────────────────────────
  val sa = Module(new ScaleAddition(scfg,
    inOpMantWOverride = treeOutMantW,
    inOpExpWOverride  = treeExpW))
  sa.io.inOpSign      := treeOut.sign
  sa.io.inOpExp       := treeOut.exp
  sa.io.inOpMant      := treeOut.mant
  sa.io.inShareScaleA := io.share_exp_A_i
  sa.io.inShareScaleB := io.share_exp_B_i

  // ──────────────────────────────────────────────────────────────────────────
  // 4) WideFixedShifter + accBits-wide signed integer accumulator
  // ──────────────────────────────────────────────────────────────────────────
  // ScaleAddition output:
  //   saSign : 1-bit
  //   saExp  : SInt[effectiveOutExpW]
  //   saMant : UInt[effectiveOutMantW]
  // Value: saSign * saMant * 2^(saExp + MANT_SCALE)
  val saMantW = sa.effectiveOutMantW
  val saExpW  = sa.effectiveOutExpW
  val saSign  = sa.io.outSign.asBool
  val saExp   = sa.io.outExp                  // SInt
  val saMant  = sa.io.outMant                  // UInt

  // Position of mant bit 0 in accReg: shiftAmt = (saExp + MANT_SCALE) − accExpBase
  // Widen saExp to a comfortable signed arithmetic width before the +/− offsets.
  val widerExpW = saExpW + 4
  val shiftAmt  = saExp.pad(widerExpW) +&
                  (MANT_SCALE - accExpBase).S(widerExpW.W)

  // Saturate shift bounds so we don't index out of accReg.
  //   - shiftAmt < 0          : drop low-precision bits (acceptable lossy region)
  //   - shiftAmt > accBits-1  : value overflows accReg (lossy region; flag clip)
  val shiftPosClipped = Mux(shiftAmt >= 0.S,
                            Mux(shiftAmt > (accBits - 1).S,
                                (accBits - 1).U(log2Ceil(accBits + 1).W),
                                shiftAmt.asUInt.apply(log2Ceil(accBits + 1) - 1, 0)),
                            0.U(log2Ceil(accBits + 1).W))
  val shiftNegAbs = Mux(shiftAmt < 0.S,
                        (-shiftAmt).asUInt.apply(log2Ceil(saMantW + accBits) - 1, 0),
                        0.U(log2Ceil(saMantW + accBits).W))

  // Build a wide unsigned holder, shift mant into place, truncate to accBits.
  // padded width = accBits (so left-shift up to accBits-1 doesn't drop top bits)
  // then a right-shift for shiftAmt < 0 case.
  val mantPad     = saMant.pad(accBits + saMantW)
  val leftShifted = (mantPad << shiftPosClipped)(accBits + saMantW - 1, 0)
  val placed      = (leftShifted >> shiftNegAbs)(accBits - 1, 0)

  // Convert to signed addend.
  val addendUnsigned = placed.asSInt.pad(accBits + 1)
  val addend = Mux(saSign, -addendUnsigned, addendUnsigned).asSInt.pad(accBits)

  // Signed accumulator
  val asyncRstN = (!reset.asBool).asAsyncReset
  val accReg    = withReset(asyncRstN)(RegInit(0.S(accBits.W)))
  val validReg  = withReset(asyncRstN)(RegInit(false.B))

  when (io.resetAcc) {
    accReg   := 0.S
    validReg := false.B
  } .elsewhen (io.validIn) {
    accReg   := accReg + addend
    validReg := true.B
  } .otherwise {
    validReg := false.B
  }
  io.validOut := validReg

  // ──────────────────────────────────────────────────────────────────────────
  // 5) Terminal LZD + RNE: signed accReg → (1+8+outMantBits) narrow FP
  // ──────────────────────────────────────────────────────────────────────────
  val accSign = (accReg < 0.S)
  val accAbs  = Mux(accSign, (-accReg).asUInt, accReg.asUInt).apply(accBits - 1, 0)

  val isZero  = accAbs === 0.U
  val lzcW    = log2Ceil(accBits + 1)
  val lzc     = PriorityEncoder(Reverse(accAbs))         // 0..accBits-1
  val msbPos  = (accBits - 1).U(lzcW.W) - lzc            // bit position of MSB

  // Unbiased exponent of the MSB bit (which represents 2^(msbPos + accExpBase))
  val expUnbiased = msbPos.zext +& accExpBase.S(lzcW.W)
  val expBiased   = expUnbiased + 127.S

  // Normalize: shift accAbs left by lzc so MSB sits at bit (accBits-1)
  val normWindowW = outMantBits + 1 + 3            // implicit + outMant + G/R/S
  val normalized  = ((accAbs.pad(accBits + lzcW)) << lzc)(accBits + lzcW - 1, 0)
                      .apply(accBits - 1, accBits - normWindowW)
  // normalized bit (normWindowW-1) = implicit 1
  // normalized bits (normWindowW-2) downto (normWindowW-1-outMantBits) = mantissa
  val mantField = normalized(normWindowW - 2, normWindowW - 1 - outMantBits)
  val guardBit  = normalized(normWindowW - 2 - outMantBits)
  val roundBit  = normalized(normWindowW - 3 - outMantBits)
  val stickyBit = if (normWindowW - 4 - outMantBits >= 0)
                    normalized(normWindowW - 4 - outMantBits, 0).orR
                  else false.B

  // RNE round
  val roundUp     = guardBit & (roundBit | stickyBit | mantField(0))
  val mantRounded = mantField +& roundUp.asUInt
  val mantOvf     = mantRounded(outMantBits)            // 1 → mantissa overflowed
  val finalMant   = Mux(mantOvf, 0.U(outMantBits.W),
                                  mantRounded(outMantBits - 1, 0))
  val finalExpBiased = Mux(mantOvf, expBiased + 1.S, expBiased)

  // Clip the 8-bit biased exponent at boundaries.
  val expClipped = Mux(finalExpBiased < 0.S,    0.U(8.W),
                   Mux(finalExpBiased > 255.S, 255.U(8.W),
                       finalExpBiased(7, 0).asUInt))

  io.accOut := Mux(isZero, 0.U,
                   Cat(accSign.asUInt, expClipped, finalMant))
}
