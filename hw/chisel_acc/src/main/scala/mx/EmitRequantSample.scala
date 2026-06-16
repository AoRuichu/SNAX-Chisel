package mx

import chisel3._
import mx.mac.{MXFormats, ScaleFormats}
import mx.requant.{RequantConfig, RequantFP8}

/** Emit two requant variants for yosys area comparison:
 *    1. baseline_FP32   — current design (inputMantWidth=23, FP32 buffer)
 *    2. narrow_FP20     — M_acc=11 case (inputMantWidth=11, FP20 buffer)
 *
 *  Both target the worked-example config (E5M2 output, UE6M2 scale,
 *  block 16, array 4×16) so the only structural difference is the input
 *  bit-width of the FP buffer / divider / barrel-shifter.
 *
 *  Run with:
 *    sbt "runMain mx.EmitRequantSample"
 *
 *  Outputs:
 *    generated/requant_sample/baseline_FP32/requant.sv
 *    generated/requant_sample/narrow_FP20/requant_in20.sv
 */
object EmitRequantSample extends App {
  val BLOCK_SIZE = 16
  val TILE_ROWS  = 4
  val TILE_COLS  = 16
  val OUT_TYPE   = MXFormats.E5M2
  val SCALE      = ScaleFormats.UE6M2

  // ── Baseline: FP32 input (current behaviour, inputMantWidth=23) ──
  {
    val cfg = RequantConfig(
      blockSize      = BLOCK_SIZE,
      tileRows       = TILE_ROWS,
      tileCols       = TILE_COLS,
      outputType     = OUT_TYPE,
      scaleType      = SCALE,
      inputMantWidth = 23,    // FP32
    )
    val outDir = "generated/requant_sample/baseline_FP32"
    emitVerilog(new RequantFP8(cfg), Array("--target-dir", outDir))
    println(s"[emit] baseline (FP32 input, ${cfg.inputWidth}-bit per element) → $outDir")
  }

  // ── Narrow: FP20 input (M_acc=11, matches E5M2² + UE6M2 deploy choice) ──
  {
    val cfg = RequantConfig(
      blockSize      = BLOCK_SIZE,
      tileRows       = TILE_ROWS,
      tileCols       = TILE_COLS,
      outputType     = OUT_TYPE,
      scaleType      = SCALE,
      inputMantWidth = 11,    // FP20 = 1 sign + 8 exp + 11 mant
    )
    val outDir = "generated/requant_sample/narrow_FP20"
    emitVerilog(new RequantFP8(cfg), Array("--target-dir", outDir))
    println(s"[emit] narrow   (FP20 input, ${cfg.inputWidth}-bit per element) → $outDir")
  }

  println()
  println("Per-element input savings: 32 - 20 = 12 bits")
  println("Total buffer savings:      64 elements × 12 bits = 768 bit-flops per requant")
  println()
  println("Next step (yosys):")
  println("  cd generated/requant_sample/baseline_FP32 && yosys -p 'read_verilog ...'  ")
  println("  cd generated/requant_sample/narrow_FP20   && yosys -p 'read_verilog ...'  ")
  println("  Compare area reports.")
}
