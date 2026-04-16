package mx.mac

import chisel3._
import chisel3.util._

/** Sign-magnitude floating-point bundle used in the custom reduction tree.
 *  Represents the value:  (-1)^sign * mant * 2^exp
 *  where mant is an unnormalized unsigned integer and exp is a biased SInt.
 *  This matches the output format of ScaleAddition exactly.
 */
class CustomFP(val expW: Int, val mantW: Int) extends Bundle {
  val sign = UInt(1.W)
  val exp  = SInt(expW.W)
  val mant = UInt(mantW.W)
}

/** Combinational adder for the CustomFP format.
 *
 *  Algorithm:
 *   1. Select far (larger |exp|) and near operands.
 *   2. Append 3 guard bits to each mantissa, right-shift near to align with far.
 *   3. Add or subtract mantissas based on signs.
 *   4. Absorb a possible addition carry by right-shifting and incrementing exp.
 *   5. Round to mantW bits using RNE (round-to-nearest-even).
 *   6. Handle rounding overflow: store as (1<<(mantW-1)) * 2^(exp+1).
 *
 *  Does NOT normalize: leading zeros in mant are preserved so the tree can
 *  defer normalization to the single ScaleToFP32 at the end.
 *
 *  @param expW  SInt exponent field width (bits)
 *  @param mantW UInt mantissa field width (bits, unnormalized)
 */
class CustomFPAdder(val expW: Int, val mantW: Int) extends Module {
  override def desiredName = s"CustomFPAdder_exp${expW}_mant${mantW}"

  val io = IO(new Bundle {
    val a   = Input(new CustomFP(expW, mantW))
    val b   = Input(new CustomFP(expW, mantW))
    val out = Output(new CustomFP(expW, mantW))
  })

  private val G   = 3           // guard bits
  private val EXT = mantW + G   // extended mantissa width

  // ── 1. Select far (larger |value|) and near operands ───────────────────
  // A zero mantissa means the value is 0 regardless of exp. A zero operand
  // must never be selected as "far" over a non-zero operand; doing so would
  // place the non-zero value in the near slot and compute (0 − near) as an
  // unsigned subtraction, which wraps and corrupts the result.
  val expDiff = io.a.exp - io.b.exp          // SInt(expW+1)
  val aIsZero = io.a.mant === 0.U
  val bIsZero = io.b.mant === 0.U
  // a has larger magnitude when:
  //   (a non-zero AND b zero), OR
  //   (both non-zero AND a has larger exp, or same exp with larger mant)
  val aLarger = (!aIsZero && bIsZero) ||
                (!aIsZero && !bIsZero &&
                  ((expDiff > 0.S) || (expDiff === 0.S && io.a.mant >= io.b.mant)))

  val farSign  = Mux(aLarger, io.a.sign, io.b.sign)
  val farExp   = Mux(aLarger, io.a.exp,  io.b.exp)   // SInt(expW)
  val farMant  = Mux(aLarger, io.a.mant, io.b.mant)
  val nearSign = Mux(aLarger, io.b.sign, io.a.sign)
  val nearMant = Mux(aLarger, io.b.mant, io.a.mant)

  // ── 2. Align near mantissa ──────────────────────────────────────────────
  val nearExt = Cat(nearMant, 0.U(G.W))   // EXT bits: mantissa ++ 3 guard zeros
  val farExt  = Cat(farMant,  0.U(G.W))   // EXT bits

  // |expDiff|, clamped to EXT so the shift can't exceed the extended width
  val absShift   = Mux(expDiff > 0.S, expDiff.asUInt, (-expDiff).asUInt)
  val shiftCap   = EXT.U
  val clampedSh  = Mux(absShift > shiftCap, shiftCap, absShift)
  // Truncate to log2Ceil(EXT+1) bits to bound the width of downstream expressions
  private val LOG_EXT = log2Ceil(EXT + 1)
  val shiftBits  = clampedSh(LOG_EXT - 1, 0)

  val aligned    = nearExt >> shiftBits   // EXT bits

  // Sticky: OR of every bit shifted off the right edge of nearExt
  val stickyMask = ((1.U((EXT + 1).W) << shiftBits) - 1.U)(EXT - 1, 0)
  val stickyRaw  = (nearExt & stickyMask).orR
  val stickyOver = absShift > shiftCap    // entire value shifted out
  val sticky0    = stickyRaw || stickyOver

  // ── 3. Add / subtract in sign-magnitude ────────────────────────────────
  val isSub     = (farSign ^ nearSign).asBool
  // Addition: keep carry bit → EXT+1 result
  val addResult = farExt +& aligned
  // Subtraction: mantissas are UNNORMALIZED, so farExt may be < aligned even
  // when farExp >= nearExp (e.g. mant_far=1,exp=5 vs mant_near=3,exp=4).
  // Detect borrow via the MSB of the EXT+1-bit result; when it is set,
  // negate via 2's-complement (~x + 1) so resMag is always non-negative.
  val subFwd    = Cat(0.U(1.W), farExt) - Cat(0.U(1.W), aligned)  // EXT+1 bits
  val subBorrow = subFwd(EXT).asBool
  val subResult = Mux(subBorrow, (~subFwd + 1.U)(EXT, 0), subFwd) // EXT+1 bits, always >= 0

  val resMag    = Mux(isSub, subResult, addResult)   // EXT+1 bits

  // ── 4. Absorb addition carry ────────────────────────────────────────────
  val carry       = resMag(EXT).asBool
  val shifted     = Mux(carry, (resMag >> 1)(EXT - 1, 0), resMag(EXT - 1, 0))  // EXT bits
  // When carry=1, resMag[0] is shifted out — fold it into sticky for correct RNE.
  val stickyCarry = carry && resMag(0).asBool
  val expAddCarry = Mux(carry, farExp + 1.S, farExp)                            // SInt(expW+1)

  // ── 4b. Post-subtraction normalization ──────────────────────────────────
  // Subtracting near-equal operands leaves leading zeros at farExp, wasting
  // mantissa bits and causing large relative errors (catastrophic cancellation).
  // Fix: count the leading zeros (LZC), left-shift the result to move the MSB
  // to the top, and decrement the exponent by the same amount.
  // This step is skipped for the addition path (carry absorption already
  // normalized it) and for the zero result (handled by isZero below).
  val isNonzero   = shifted.orR
  // PriorityEncoder(Reverse(x)) gives the number of leading zeros in x.
  // The output is log2Ceil(EXT) bits, sufficient to represent 0..EXT-1.
  // We do NOT truncate further here because (LOG_EXT-1) can exceed that width
  // when EXT is a power-of-two (log2Ceil(EXT+1) = log2Ceil(EXT) + 1).
  val lzc         = PriorityEncoder(Reverse(shifted))  // # leading zeros from MSB
  val normShift   = Mux(isSub && !carry && isNonzero, lzc, 0.U)
  val shiftedNorm = (shifted << normShift)(EXT - 1, 0)   // EXT bits, MSB-aligned
  val expNorm     = expAddCarry - normShift.zext           // SInt(expW+2)

  // ── 5. RNE rounding from EXT bits down to mantW bits ───────────────────
  // Layout of 'shiftedNorm': [EXT-1 : G] = mantW-bit mantissa, [2] = G, [1] = R, [0] = S-low
  val mantRaw     = shiftedNorm(EXT - 1, G)   // mantW bits
  val guardBit    = shiftedNorm(2).asBool
  val roundBit    = shiftedNorm(1).asBool
  val stickyFinal = shiftedNorm(0).asBool || sticky0 || stickyCarry

  val roundUp    = guardBit && (mantRaw(0).asBool || roundBit || stickyFinal)
  val roundedM   = mantRaw +& roundUp.asUInt   // mantW+1 bits (keeps carry)
  val mantCarry  = roundedM(mantW).asBool

  // Rounding overflow: 2^mantW * 2^exp → represent as (1<<(mantW-1)) * 2^(exp+1)
  val finalMant  = Mux(mantCarry, (1 << (mantW - 1)).U(mantW.W), roundedM(mantW - 1, 0))
  val finalExpW  = Mux(mantCarry, expNorm + 1.S, expNorm)  // SInt(expW+3)

  // ── 6. Output (truncate exp back to expW; extreme over/underflow saturated by ScaleToFP32) ──
  val isZero  = resMag === 0.U
  // When subtraction borrows, the near operand was actually larger, so the
  // result carries near's sign rather than far's.
  val outSign = Mux(isSub && subBorrow, nearSign, farSign)

  io.out.sign := Mux(isZero, 0.U, outSign)
  io.out.exp  := finalExpW(expW - 1, 0).asSInt
  io.out.mant := Mux(isZero, 0.U, finalMant)
}
