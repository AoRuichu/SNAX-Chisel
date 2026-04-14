package mx.mac

import chisel3._
import chisel3.util._

// ============================================================
// Combinational FP32 adder (shared submodule)
// ============================================================
/** Purely-combinational IEEE-754 single-precision adder.
 *
 *  Supports both addition and subtraction (sign-magnitude).
 *  Uses 3 extra guard/round/sticky bits for RNE rounding.
 *  No special-case handling for NaN/Inf (consistent with the
 *  rest of the mx.mac pipeline).
 */
class FP32Adder extends Module {
  val io = IO(new Bundle {
    val a   = Input(UInt(32.W))
    val b   = Input(UInt(32.W))
    val out = Output(UInt(32.W))
  })

  val valA_S = io.a(31)
  val valA_E = io.a(30, 23)
  val valA_M = Cat(valA_E.orR, io.a(22, 0)) // 24-bit with implicit-1

  val valB_S = io.b(31)
  val valB_E = io.b(30, 23)
  val valB_M = Cat(valB_E.orR, io.b(22, 0))

  // Determine which operand has larger magnitude
  val expDiff  = valA_E.zext - valB_E.zext
  val aGreater = expDiff > 0.S || (expDiff === 0.S && valA_M >= valB_M)

  val farExp = Mux(aGreater, valA_E, valB_E)
  val nearM  = Mux(aGreater, valB_M, valA_M)
  val farM   = Mux(aGreater, valA_M, valB_M)

  // Align smaller operand: 3 guard bits below mantissa, then right-shift
  val absExpDiff      = Mux(aGreater, expDiff.asUInt, (-expDiff).asUInt)
  val nearM27         = Cat(nearM, 0.U(3.W))            // 27 bits: 24-bit mantissa + 3 zero guard
  val alignedNearM    = nearM27 >> absExpDiff
  // Sticky: any bit of nearM27 shifted past position 0 during alignment
  val stickyFromAlign = (nearM27 & ((1.U << absExpDiff) - 1.U)(26, 0)).orR

  // Add / subtract (28-bit result)
  val isSub  = valA_S ^ valB_S
  val farM27 = Cat(farM, 0.U(3.W))                      // 27 bits: 24-bit mantissa + 3 zero guard
  val resMag: UInt = Mux(
    isSub,
    Cat(0.U(1.W), farM27) - Cat(0.U(1.W), alignedNearM),
    farM27 +& alignedNearM
  )

  val resSign = Mux(aGreater, valA_S, valB_S)

  // Normalize: left-shift until implicit-1 lands at bit 27
  val resLZC    = PriorityEncoder(resMag.asBools.reverse)
  val normShift = resMag << resLZC

  // RNE rounding: bit 27 = implicit-1, [26:4] = mantissa, bit 3 = G, bit 2 = R, [1:0] = S
  val mant23    = normShift(26, 4)
  val guardBit  = normShift(3)
  val roundBit  = normShift(2)
  val stickyBit = normShift(1, 0).orR || stickyFromAlign

  val roundUp  = guardBit && (mant23(0) || roundBit || stickyBit)
  val roundedM = mant23 +& roundUp                      // 24 bits; bit 23 = carry out on 0xFFFFFF→0

  // If rounding overflows the mantissa, increment the exponent (mantissa wraps to 0)
  val mantCarry = roundedM(23)
  val finalM    = roundedM(22, 0)
  val finalE_wide = farExp.zext - resLZC.asSInt + 1.S + mantCarry.zext

  // Underflow (denormal result) → flush to zero; overflow → clamp to Infinity
  val isZero        = resMag === 0.U || finalE_wide <= 0.S
  val isExpOverflow = finalE_wide >= 255.S

  io.out := Mux(isZero,        0.U(32.W),
             Mux(isExpOverflow, Cat(resSign, 255.U(8.W), 0.U(23.W)),
                                Cat(resSign, finalE_wide.asUInt(7, 0), finalM)))
}

// ============================================================
// ScaleAddition output → IEEE FP32 conversion (shared submodule)
// ============================================================
/** Converts the (sign, exp, mant) output of ScaleAddition to a
 *  32-bit IEEE-754 float using LZC normalization and RNE rounding.
 *
 *  Replicates the normalization stage that was previously
 *  baked into ScaleAccumulatorFP32.
 */
class ScaleToFP32(val scfg: ScaleAddConfig) extends Module {
  override def desiredName =
    s"ScaleToFP32_${scfg.elementTypeA.name}_x_${scfg.elementTypeB.name}_${scfg.stype.name}"

  val io = IO(new Bundle {
    val inSign = Input(UInt(1.W))
    val inExp  = Input(SInt(scfg.resScaleAddExpWidth.W))
    val inMant = Input(UInt(scfg.resScaleAddMantWidth.W))
    val out    = Output(UInt(32.W))
  })

  private def elemFrac(t: ElementType): Int =
    if (t.name == "INT8") 0 else t.elementWidthMant

  val fracBits  = elemFrac(scfg.elementTypeA) + elemFrac(scfg.elementTypeB) +
                  2 * scfg.stype.mantScaleWidth
  val mantWidth = scfg.resScaleAddMantWidth
  val expBias   = 127 + mantWidth - 1 - fracBits

  val isZero = io.inMant === 0.U

  // Normalization: find leading-one position
  val lzc         = PriorityEncoder(io.inMant.asBools.reverse)
  val shiftedMant = io.inMant << lzc

  // Ensure we always have enough bits below the implicit-1 for extraction
  val EXTRA = (27 - mantWidth) max 0
  val paddedShift =
    if (EXTRA > 0) Cat(shiftedMant(mantWidth - 1, 0), 0.U(EXTRA.W))
    else           shiftedMant(mantWidth - 1, 0)
  val safeExtractPos = EXTRA + mantWidth - 2  // bit index of first fractional bit

  // RNE rounding
  val mant23    = paddedShift(safeExtractPos, safeExtractPos - 22)
  val guardBit  = paddedShift(safeExtractPos - 23)
  val roundBit  = paddedShift(safeExtractPos - 24)
  val stickyBit = paddedShift(safeExtractPos - 25, 0).orR || (lzc > (mantWidth - 1).U) //补齐sticky

  val roundUp   = guardBit && (mant23(0) || roundBit || stickyBit)
  val roundedM  = mant23 +& roundUp // 使用 +& 保留进位

  val roundCarry = roundedM(23)
  val finalMant  = roundedM(22, 0)

  // Exponent with bias correction
  val adjustedExp = io.inExp - lzc.zext + expBias.S + roundCarry.zext
  val isOverflow  = adjustedExp >= 255.S
  val isUnderflow = adjustedExp <= 0.S

  val finalExp = Mux(isOverflow,  255.U(8.W),
                 Mux(isUnderflow, 0.U(8.W), adjustedExp.asUInt(7, 0)))

  // 零值强制清零；下溢（次正规结果）同样清零（FTZ）
  io.out := Mux(isZero || isUnderflow, 0.U(32.W), Cat(io.inSign, finalExp, finalMant))
}
