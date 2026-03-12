package mx.mac

import chisel3._
import chisel3.util._

class ScaleAccumulatorFP32(val scfg: ScaleAddConfig) extends Module {
  val io = IO(new Bundle {
    // Control
    val start     = Input(Bool())
    val resetAcc  = Input(Bool())
    val done      = Output(Bool())
    // Data from ScaleAddition
    val inSign    = Input(UInt(1.W))
    val inExp     = Input(SInt(scfg.resScaleAddExpWidth.W))
    val inMant    = Input(UInt(scfg.resScaleAddMantWidth.W))
    // Result
    val accOut    = Output(UInt(32.W))
  })

  // --- State Machine ---
  val sIdle :: sCompute :: sDone :: Nil = Enum(3)
  val state = RegInit(sIdle)

  // --- Registers ---
  val accReg = RegInit(0.U(32.W))

  // --- 1. Normalization (Find leading one) ---
  // PriorityEncoder finds the lowest index of a '1'. 
  // We reverse the bits to find the Highest Significant Bit.
  val lzc = PriorityEncoder(io.inMant.asBools.reverse)
  val shiftedMant = (io.inMant << lzc)
  
  // --- 2. Round-to-Nearest-Even (RNE) Logic ---
  // FP32 has 23 bits of explicit mantissa.
  // We look at the bits immediately following the 23rd bit to decide rounding.
  val mantWidth = scfg.resScaleAddMantWidth

  // Fractional bits in the mantissa product:
  //   Each non-INT8 element factor contributes elementWidthMant fractional bits.
  //   Each scale factor contributes mantScaleWidth fractional bits.
  // The exponent bias must be increased by (mantWidth - 1 - fracBits) to align
  // the implicit-1 position with the FP32 exponent convention.
  private def elemFrac(t: ElementType): Int = if (t.name == "INT8") 0 else t.elementWidthMant
  val fracBits = elemFrac(scfg.elementTypeA) + elemFrac(scfg.elementTypeB) +
                 2 * scfg.stype.mantScaleWidth
  val expBias  = 127 + mantWidth - 1 - fracBits  // corrected FP32 bias

  // Minimum zero-padding needed so all extraction indices stay non-negative.
  // Constraint: (EXTRA + mantWidth - 2) - 25 >= 0  =>  EXTRA >= 27 - mantWidth
  val EXTRA = (27 - mantWidth) max 0
  val paddedShift = if (EXTRA > 0) Cat(shiftedMant(mantWidth - 1, 0), 0.U(EXTRA.W))
                   else shiftedMant(mantWidth - 1, 0)
  val safeExtractPos = EXTRA + mantWidth - 2  // first fractional bit below implicit leading-1

  val mant23   = paddedShift(safeExtractPos, safeExtractPos - 22)
  val guardBit = paddedShift(safeExtractPos - 23)
  val roundBit = paddedShift(safeExtractPos - 24)
  val stickyBit= paddedShift(safeExtractPos - 25, 0).orR
  
  val roundUp = guardBit && (mant23(0) || roundBit || stickyBit)
  val finalMant = Mux(roundUp, mant23 + 1.U, mant23)

  // --- 3. Exponent Adjustment ---
  // Adjust based on LZC and the corrected bias (accounts for fracBits in the product)
  val adjustedExp = io.inExp - lzc.asSInt + expBias.S
  
  // Basic Overflow/Underflow Handling
  val isOverflow  = adjustedExp >= 255.S
  val isUnderflow = adjustedExp <= 0.S
  
  val finalExp = Mux(isOverflow, 255.U(8.W), 
                 Mux(isUnderflow, 0.U(8.W), adjustedExp.asUInt(7,0)))

  // --- 4. FSM & Accumulation ---
  val currentResult = Cat(io.inSign, finalExp, finalMant)

  // Placeholder for FP32 Addition logic
  // Note: In real HW, FP32 addition requires its own alignment shifter.
  // Here we show the control flow.
  io.done := false.B
  
  switch(state) {
    is(sIdle) {
      when(io.start) {
        state := sCompute
      }
    }
    is(sCompute) {
        // --- 1. Decomposition ---
        val valA_S = accReg(31)
        val valA_E = accReg(30, 23)
        val valA_M = Cat(valA_E.orR, accReg(22, 0)) // Add implicit bit

        val valB_S = currentResult(31)
        val valB_E = currentResult(30, 23)
        val valB_M = Cat(valB_E.orR, currentResult(22, 0))

        // --- 2. Alignment (Exponents Matching) ---
        val expDiff = valA_E.asSInt - valB_E.asSInt
        val aGreater = expDiff >= 0.S

        val farExp  = Mux(aGreater, valA_E, valB_E)
        val nearM   = Mux(aGreater, valB_M, valA_M)
        val farM    = Mux(aGreater, valA_M, valB_M)
        
        // Shift the smaller number's mantissa to match the larger exponent
        val absExpDiff = Mux(aGreater, expDiff.asUInt, (-expDiff).asUInt)
        val alignedNearM = (nearM << 3) >> absExpDiff // Use extra precision bits for rounding

        // --- 3. Addition / Subtraction ---
        // farM is shifted left by 3 to match the ×8 scale of alignedNearM,
        // so both operands carry the same 3 extra precision bits.
        val isSub = valA_S ^ valB_S
        val resM = Mux(isSub, (farM << 3).asSInt - alignedNearM.asSInt, ((farM << 3) +& alignedNearM).asSInt)

        // --- 4. Renormalization ---
        // After <<resLZC the implicit-1 lands at bit 27; explicit mantissa bits are 26..4.
        val resLZC = PriorityEncoder(resM.abs.asUInt.asBools.reverse)
        val finalM = (resM.abs.asUInt << resLZC)(26, 4) // Re-align to 23 bits
        val finalE = farExp.asSInt - resLZC.asSInt + 1.S

        // Update Accumulator
        accReg := Cat(Mux(aGreater, valA_S, valB_S), finalE(7,0), finalM(22,0))
        
        state := sDone

    }
    is(sDone) {
      io.done := true.B
      state  := sIdle
    }
  }

  when(io.resetAcc) { accReg := 0.U }
  io.accOut := accReg
}

object AccumulatorMain extends App {
  val scfg = ScaleAddConfig(MXFormats.E4M3, MXFormats.E2M1, ScaleFormats.UE5M3)
  
  emitVerilog(
    new ScaleAccumulatorFP32(scfg), 
    Array("--target-dir", "generated/accumulator")
  )
}