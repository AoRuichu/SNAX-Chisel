package mx

import chisel3._
import mx.mac.{MXFormats, ScaleFormats, ScaleAddConfig, TreeArch}
import mx.array.{ArchOverride, PEArrayConfig, PEArrayINT8Config, PEArrayWrapper, PEArrayWrapperINT8}
import mx.requant.{RequantConfig, RequantFP8, RequantINT8, RequantINT8Config}
import java.io.File
import scala.io.Source

/** Emit the 6 representative configs as integrated PE Array + Requant
 *  wrappers, with M_acc-aligned input width for the requant block (no
 *  zero-padding from PE output to requant input → narrow datapath).
 *
 *  All configs:
 *    blockSize=16, tileRows=4, tileCols=16, vectorSize=4
 *
 *  M_acc source of truth: macc_final_selection.csv (sweep output).
 *  Configs not in the CSV fall back to the OVERRIDES map below — kept
 *  explicit so the divergence between sweep-aligned and hand-picked
 *  values stays visible in code review.
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

  // ── M_acc source of truth: macc_final_selection.csv ─────────────────────
  // Mirrors EmitRequantSweep.scala's loader.  Read at elaboration time so
  // any future sweep refresh propagates automatically into the next emit.
  case class Key(act: String, weight: String, scale: String)
  val maccSel: Map[Key, Int] = {
    val f = new File("macc_final_selection.csv")
    require(f.exists,
      "macc_final_selection.csv not found in cwd; run the sweep first " +
      "(plot_macc_sweep_band.py) or invoke sbt from hw/chisel_acc/.")
    val src    = Source.fromFile(f)
    val lines  = try src.getLines().toList finally src.close()
    val header = lines.head.split(",").map(_.trim)
    val iA = header.indexOf("typeA")
    val iB = header.indexOf("typeB")
    val iS = header.indexOf("scale")
    val iM = header.indexOf("M_sel")
    lines.drop(1).flatMap { ln =>
      val c = ln.split(",").map(_.trim)
      if (c.length > iM) Some(Key(c(iA), c(iB), c(iS)) -> c(iM).toInt) else None
    }.toMap
  }

  // ── Per-pair overrides for configs not covered by the sweep CSV ──────────
  // Keep this list short and explicit — every entry here is a hand-picked
  // value that bypassed the safety-margin sweep, so it must be justified.
  val OVERRIDES: Map[Key, Int] = Map(
    Key("E2M1", "E2M1", "UE5M3") -> 10,  // A4W4 optimal — extrapolated from UE6M2 row
    Key("E2M1", "E2M1", "UE4M3") ->  9,  // NVFP4 reference — matches E2M1²+UE6M2 budget
    Key("INT8", "INT8", "UE8M0") -> 13,  // Cuyckens MXINT8 reference — INT8² has tightest
                                          //   output floor; AccPrecision recommends 14, but
                                          //   13 enables the FP22 vs Cuyckens FP24 area win
                                          //   while still satisfying ε_acc < ½·ε_req.
  )

  def lookupMacc(act: String, weight: String, scale: String): (Int, String) = {
    val k = Key(act, weight, scale)
    maccSel.get(k).map(_ -> "sweep CSV").orElse(
      OVERRIDES.get(k).map(_ -> "hand-picked override")).getOrElse(
      throw new RuntimeException(
        s"No M_acc found for ($act, $weight, $scale) — add to CSV or OVERRIDES."))
  }

  // 6 representative configs covering the energy-breakdown story.
  // (act, weight, scale, role) — M_acc is looked up; label is derived.
  case class Config(act: String, weight: String, scale: String, role: String) {
    val (m_acc, source) = lookupMacc(act, weight, scale)
    val label: String   = s"${act}_${weight}_${scale}_M${m_acc}"
  }

  val CONFIGS = Seq(
    Config("E5M2", "E5M2", "UE6M2", "Worst case (FP8, mantissa-bearing scale)"),
    Config("E2M1", "E2M1", "UE8M0", "Smallest (FP4, power-of-2 scale)"),
    Config("E2M1", "E2M1", "UE5M3", "A4W4 optimal (algo sweep)"),
    Config("E2M1", "E2M1", "UE4M3", "NVFP4 reference"),
    Config("INT8", "INT8", "UE8M0", "Cuyckens MXINT8 reference"),
    Config("E2M3", "E2M3", "UE6M2", "MX FP6 reference"),
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

    // Force Generic tree arch — SmallFixedShift / TwoStageBarrel omit the
    // SAFETY_G slack at the alignment stage and fail the
    // (outMantW + G ≤ absMagW) require when M_acc is set above the
    // auto-recommended value.  The cyclesPerBlock field of ArchOverride
    // is now vestigial (baseline always cycleFP); pass 1 for clarity.
    val arch = Some(ArchOverride(TreeArch.Generic, 1))

    if (cfg.act == "INT8") {
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
      emitVerilog(new PEArrayWrapperINT8(arrayCfg), Array("--target-dir", outDir))
      emitVerilog(new RequantINT8(rqCfg),
        Array("--target-dir", s"$outDir/requant_standalone"))
    } else {
      val rqCfg = RequantConfig(
        blockSize      = BLOCK_SIZE,
        tileRows       = TILE_ROWS,
        tileCols       = TILE_COLS,
        outputType     = act,
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
    println(f"  ✓ ${cfg.label}%-32s  M_acc=${cfg.m_acc}%2d  FP$inW%-2d  " +
            f"[${cfg.source}%-22s]  ${cfg.role}")
  }

  println()
  println(s"Done. ${CONFIGS.size} integrated PE Array + Requant variants emitted under $ROOT/")
}
