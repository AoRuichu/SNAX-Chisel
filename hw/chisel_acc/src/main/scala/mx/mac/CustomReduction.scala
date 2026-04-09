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

  // ── 1. Select far (larger exp) and near operands ────────────────────────
  // SInt subtraction gives expW+1 bits; a tie in exp is broken by mantissa mag.
  val expDiff = io.a.exp - io.b.exp          // SInt(expW+1)
  val aLarger = (expDiff > 0.S) || (expDiff === 0.S && io.a.mant >= io.b.mant)

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
  // Subtraction: far >= near by construction → no borrow, top bit is always 0
  val subResult = Cat(0.U(1.W), farExt) - Cat(0.U(1.W), aligned)  // EXT+1 bits

  val resMag    = Mux(isSub, subResult, addResult)   // EXT+1 bits

  // ── 4. Absorb addition carry ────────────────────────────────────────────
  val carry      = resMag(EXT).asBool
  val shifted    = Mux(carry, (resMag >> 1)(EXT - 1, 0), resMag(EXT - 1, 0))  // EXT bits
  val expAddCarry = Mux(carry, farExp + 1.S, farExp)                           // SInt(expW+1)

  // ── 5. RNE rounding from EXT bits down to mantW bits ───────────────────
  // Layout of 'shifted': [EXT-1 : G] = mantW-bit mantissa, [2] = G, [1] = R, [0] = S-low
  val mantRaw     = shifted(EXT - 1, G)   // mantW bits
  val guardBit    = shifted(2).asBool
  val roundBit    = shifted(1).asBool
  val stickyFinal = shifted(0).asBool || sticky0

  val roundUp    = guardBit && (mantRaw(0).asBool || roundBit || stickyFinal)
  val roundedM   = mantRaw +& roundUp.asUInt   // mantW+1 bits (keeps carry)
  val mantCarry  = roundedM(mantW).asBool

  // Rounding overflow: 2^mantW * 2^exp → represent as (1<<(mantW-1)) * 2^(exp+1)
  val finalMant  = Mux(mantCarry, (1 << (mantW - 1)).U(mantW.W), roundedM(mantW - 1, 0))
  val finalExpW  = Mux(mantCarry, expAddCarry + 1.S, expAddCarry)  // SInt(expW+2)

  // ── 6. Output (truncate exp back to expW; extreme overflow is saturated by ScaleToFP32) ──
  val isZero = resMag === 0.U

  io.out.sign := Mux(isZero, 0.U, farSign)
  io.out.exp  := finalExpW(expW - 1, 0).asSInt
  io.out.mant := Mux(isZero, 0.U, finalMant)
}
