package mx.mac

import chisel3._

/** Dot-product unit that chains:
 *    CustomOperator  →  ScaleAddition  →  ScaleAccumulatorFP32
 *
 *  One call to start/done drives one multiply-scale-accumulate step.
 *  resetAcc resets the FP32 accumulator to zero.
 *
 *  @param scfg  ScaleAddConfig that fully describes element types and scale format.
 *               The embedded OperatorConfig is derived automatically.
 */
class DotProductUnit(val scfg: ScaleAddConfig) extends Module {
  override def desiredName =
    s"DotProductUnit_${scfg.elementTypeA.name}_x_${scfg.elementTypeB.name}_scale_${scfg.stype.name}"

  val io = IO(new Bundle {
    // --- Data inputs ---
    val inA      = Input(UInt(scfg.elementTypeA.totalWidth.W))
    val inB      = Input(UInt(scfg.elementTypeB.totalWidth.W))
    // --- Shared scale factors ---
    val inScaleA = Input(UInt(scfg.stype.totalScaleWidth.W))
    val inScaleB = Input(UInt(scfg.stype.totalScaleWidth.W))
    // --- Control ---
    val start    = Input(Bool())
    val resetAcc = Input(Bool())
    // --- Output ---
    val done     = Output(Bool())
    val accOut   = Output(UInt(32.W))
  })

  // ------------------------------------------------------------------
  // Stage 1: CustomOperator — element-wise multiply (A × B)
  // ------------------------------------------------------------------
  val operator = Module(new CustomOperator(OperatorConfig(scfg.elementTypeA, scfg.elementTypeB)))
  operator.io.inA := io.inA
  operator.io.inB := io.inB

  // ------------------------------------------------------------------
  // Stage 2: ScaleAddition — apply shared scale factors
  // ------------------------------------------------------------------
  val scaleAdd = Module(new ScaleAddition(scfg))
  scaleAdd.io.inOpSign      := operator.io.outSign
  scaleAdd.io.inOpExp       := operator.io.outExp
  scaleAdd.io.inOpMant      := operator.io.outMant
  scaleAdd.io.inShareScaleA := io.inScaleA
  scaleAdd.io.inShareScaleB := io.inScaleB

  // ------------------------------------------------------------------
  // Stage 3: ScaleAccumulatorFP32 — accumulate scaled result into FP32
  // ------------------------------------------------------------------
  val accumulator = Module(new ScaleAccumulatorFP32(scfg))
  accumulator.io.inSign   := scaleAdd.io.outSign
  accumulator.io.inExp    := scaleAdd.io.outExp
  accumulator.io.inMant   := scaleAdd.io.outMant
  accumulator.io.start    := io.start
  accumulator.io.resetAcc := io.resetAcc

  io.done   := accumulator.io.done
  io.accOut := accumulator.io.accOut
}

/** Emit Verilog for the default E4M3 × E2M1 / UE5M3 configuration. */
object DotProductMain extends App {
  val scfg = ScaleAddConfig(MXFormats.E4M3, MXFormats.E2M1, ScaleFormats.UE5M3)
  emitVerilog(
    new DotProductUnit(scfg),
    Array("--target-dir", "generated/dot_product")
  )
}

/** Emit Verilog for every element-type × scale-type combination. */
object AllDotProductMain extends App {
  for {
    typeA  <- MXFormats.allElementTypes
    typeB  <- MXFormats.allElementTypes
    stype  <- ScaleFormats.allScaleTypes
  } {
    val scfg = ScaleAddConfig(typeA, typeB, stype)
    println(s"Generating DotProductUnit: ${typeA.name} x ${typeB.name}, scale ${stype.name}")
    emitVerilog(
      new DotProductUnit(scfg),
      Array("--target-dir", s"generated/dot_product/${typeA.name}_${typeB.name}_${stype.name}")
    )
  }
}
