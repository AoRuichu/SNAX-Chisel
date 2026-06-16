package mx

import chisel3._
import mx.mac.{MXFormats, ScaleFormats, ScaleAddConfig, TreeArch}
import mx.array.{ArchOverride, PEArrayConfig, PEArrayINT8Config, PEArrayWrapper, PEArrayWrapperINT8}
import mx.requant.{RequantConfig, RequantFP8, RequantINT8, RequantINT8Config}
import java.io.File

/** Emit the 6 representative configs as integrated PE Array + Requant
 *  wrappers, with M_acc-aligned input width for the requant block (no
 *  zero-padding from PE output to requant input → narrow datapath).
 *
 *  All configs:
 *    blockSize=16, tileRows=4, tileCols=16, vectorSize=4
 *
 *  Output layout:
 *    generated/key_configs/<label>/PE_Array.sv  (PE + integrated requant)
 *
 *  Run:
 *    sbt "runMain mx.EmitKeyConfigs"
 */
object EmitKeyConfigs extends App {
  val BLOCK_SIZE = 16
  val TILE_ROWS  = 4
  val TILE_COLS  = 16
  val VEC        = 4

  // 6 representative configs covering the energy-breakdown story.
  // M_acc values from macc_final_selection.csv (sweep-aligned) plus two
  // extrapolated entries for E2M1²+UE5M3 (A4W4 optimal) and E2M1²+UE4M3 (NVFP4).
  case class Config(label: String, act: String, weight: String,
                    scale: String, m_acc: Int, role: String)

  val CONFIGS = Seq(
    Config("E5M2_E5M2_UE6M2_M11", "E5M2", "E5M2", "UE6M2", 11, "Worst case (FP8, mantissa-bearing scale)"),
    Config("E2M1_E2M1_UE8M0_M8",  "E2M1", "E2M1", "UE8M0", 8,  "Smallest (FP4, power-of-2 scale)"),
    Config("E2M1_E2M1_UE5M3_M10", "E2M1", "E2M1", "UE5M3", 10, "A4W4 optimal (algo sweep)"),
    Config("E2M1_E2M1_UE4M3_M9",  "E2M1", "E2M1", "UE4M3", 9,  "NVFP4 reference"),
    Config("INT8_INT8_UE8M0_M13", "INT8", "INT8", "UE8M0", 13, "Cuyckens MXINT8 reference"),
    Config("E2M3_E2M3_UE6M2_M12", "E2M3", "E2M3", "UE6M2", 12, "MX FP6 reference"),
  )

  val FMT = Map[String, mx.mac.ElementType](
    "INT8" -> MXFormats.INT8, "E5M2" -> MXFormats.E5M2,
    "E4M3" -> MXFormats.E4M3, "E3M2" -> MXFormats.E3M2,
    "E2M3" -> MXFormats.E2M3, "E2M1" -> MXFormats.E2M1)
  val SCL = Map[String, mx.mac.ScaleType](
    "UE8M0" -> ScaleFormats.UE8M0, "UE7M1" -> ScaleFormats.UE7M1,
    "UE6M2" -> ScaleFormats.UE6M2, "UE5M3" -> ScaleFormats.UE5M3,
    "UE4M4" -> ScaleFormats.UE4M4, "UE4M3" -> ScaleFormats.UE4M3)

  val ROOT = "generated/key_configs"
  new File(ROOT).mkdirs()

  for (cfg <- CONFIGS) {
    val outDir = s"$ROOT/${cfg.label}"
    val act   = FMT(cfg.act)
    val wt    = FMT(cfg.weight)
    val scale = SCL(cfg.scale)
    val mac   = ScaleAddConfig(act, wt, scale)

    // Force Generic tree arch — SmallFixedShift / TwoStageBarrel omit G
    // slack at the alignment stage and fail the (outMantW + G ≤ absMagW)
    // require check when M_acc is set above the auto-recommended value.
    val arch = Some(ArchOverride(TreeArch.Generic, BLOCK_SIZE / VEC))

    if (cfg.act == "INT8") {
      // INT8 output → PEArrayWrapperINT8
      val rqCfg = RequantINT8Config(
        blockSize      = BLOCK_SIZE,
        tileRows       = TILE_ROWS,
        tileCols       = TILE_COLS,
        scaleType      = scale,
        inputMantWidth = cfg.m_acc)
      val arrayCfg = PEArrayINT8Config(
        macCfg              = mac,
        vectorSize          = VEC,
        tileRows            = TILE_ROWS,
        tileCols            = TILE_COLS,
        requantCfg          = rqCfg,
        archOverride        = arch,
        accMantBitsOverride = cfg.m_acc)
      // 1) Integrated PE Array + Requant
      emitVerilog(new PEArrayWrapperINT8(arrayCfg), Array("--target-dir", outDir))
      // 2) Standalone Requant (for separate DC synth)
      emitVerilog(new RequantINT8(rqCfg),
        Array("--target-dir", s"$outDir/requant_standalone"))
    } else {
      val rqCfg = RequantConfig(
        blockSize      = BLOCK_SIZE,
        tileRows       = TILE_ROWS,
        tileCols       = TILE_COLS,
        outputType     = act,             // requant output = activation format
        scaleType      = scale,
        inputMantWidth = cfg.m_acc)
      val arrayCfg = PEArrayConfig(
        macCfg              = mac,
        vectorSize          = VEC,
        tileRows            = TILE_ROWS,
        tileCols            = TILE_COLS,
        requantCfg          = rqCfg,
        archOverride        = arch,
        accMantBitsOverride = cfg.m_acc)
      emitVerilog(new PEArrayWrapper(arrayCfg), Array("--target-dir", outDir))
      emitVerilog(new RequantFP8(rqCfg),
        Array("--target-dir", s"$outDir/requant_standalone"))
    }

    val inW = 1 + 8 + cfg.m_acc
    println(f"  ✓ ${cfg.label}%-32s  M_acc=${cfg.m_acc}%2d  FP$inW%-2d  ${cfg.role}")
  }

  println()
  println(s"Done. ${CONFIGS.size} integrated PE Array + Requant variants emitted under $ROOT/")
}
