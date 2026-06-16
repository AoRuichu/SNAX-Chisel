package mx

import chisel3._
import mx.mac.{ElementType, MXFormats, ScaleType, ScaleFormats}
import mx.requant.{RequantConfig, RequantFP8, RequantINT8, RequantINT8Config}
import scala.io.Source
import java.io.File

/** Emit per-config requant variants for the full activation×weight×scale sweep.
 *
 *  Constraints (matches pe_vcd_merged_manifest.csv):
 *    - blockSize = 16, tileRows = 4, tileCols = 16  (fixed)
 *    - activation precision ≥ weight precision      → 21 (act, weight) pairs
 *    - 6 scale formats                                → 21 × 6 = 126 configs
 *
 *  Per-config inputMantWidth:
 *    - For configs present in macc_final_selection.csv, use the deployed M_acc.
 *    - For the remaining configs, fall back to a per-act heuristic that
 *      reflects the typical M_acc bracket for that activation family.
 *
 *  Output layout:
 *    generated/requant_sweep/<label>/requant.sv   (or requant_in<W>.sv)
 *  where <label> = "<act>_<weight>_<scale>_M<m_acc>".
 *
 *  Run:
 *    sbt "runMain mx.EmitRequantSweep"
 */
object EmitRequantSweep extends App {
  // ── Fixed array geometry ─────────────────────────────────
  val BLOCK_SIZE = 16
  val TILE_ROWS  = 4
  val TILE_COLS  = 16

  // ── Element + scale enumeration ──────────────────────────
  // Ordered from highest precision to lowest so (act >= weight) filter
  // produces the 21 lower-triangular pairs.
  val ACT_ORDER: Seq[ElementType] = Seq(
    MXFormats.INT8, MXFormats.E5M2, MXFormats.E4M3,
    MXFormats.E3M2, MXFormats.E2M3, MXFormats.E2M1)
  val WT_ORDER = ACT_ORDER
  val SCALE_ORDER: Seq[ScaleType] = Seq(
    ScaleFormats.UE8M0, ScaleFormats.UE7M1, ScaleFormats.UE6M2,
    ScaleFormats.UE5M3, ScaleFormats.UE4M4, ScaleFormats.UE4M3)

  // Strict precision rank for the act >= weight filter — matches the
  // 21 lower-triangular pairs in pe_vcd_merged_manifest.csv.
  // Rank 1 = highest precision, rank 6 = lowest.  Filter: act_rank <= weight_rank.
  val RANK: Map[String, Int] = Map(
    "INT8" -> 1, "E5M2" -> 2, "E4M3" -> 3,
    "E3M2" -> 4, "E2M3" -> 5, "E2M1" -> 6)

  // ── Load deployed M_acc from macc_final_selection.csv ────
  case class Key(act: String, weight: String, scale: String)
  val maccSel: Map[Key, Int] = {
    val f = new File("macc_final_selection.csv")
    if (!f.exists) {
      println(s"[warn] macc_final_selection.csv not found; using heuristics for all configs")
      Map.empty
    } else {
      val lines = Source.fromFile(f).getLines().toList
      val header = lines.head.split(",").map(_.trim)
      val iA = header.indexOf("typeA")
      val iB = header.indexOf("typeB")
      val iS = header.indexOf("scale")
      val iM = header.indexOf("M_sel")
      lines.drop(1).flatMap { ln =>
        val c = ln.split(",").map(_.trim)
        if (c.length > iM)
          Some(Key(c(iA), c(iB), c(iS)) -> c(iM).toInt)
        else None
      }.toMap
    }
  }

  // Per-activation default M_acc when the (act, weight, scale) tuple isn't
  // covered by the sweep selection.  Numbers chosen from observed bracket.
  // The output-precision floor scales with the activation's mantissa width.
  val DEFAULT_M_PER_ACT: Map[String, Int] = Map(
    "INT8" -> 14,  // INT8² tightest floor → widest M_acc
    "E5M2" -> 11,  // E5M2² worked example
    "E4M3" -> 12,  // 3-bit explicit mant → slightly wider
    "E3M2" -> 11,
    "E2M3" -> 12,
    "E2M1" -> 9)   // FP4 loosest floor → narrowest M_acc

  def pickMacc(act: String, weight: String, scale: String): Int = {
    maccSel.getOrElse(Key(act, weight, scale),
                      DEFAULT_M_PER_ACT.getOrElse(act, 12))
  }

  // ── Enumerate 21 × 6 = 126 configs, emit each ────────────
  val ROOT = "generated/requant_sweep"
  new File(ROOT).mkdirs()

  var nEmit = 0
  for (act <- ACT_ORDER; weight <- WT_ORDER if RANK(act.name) <= RANK(weight.name)
       ; scale <- SCALE_ORDER) {
    val mAcc = pickMacc(act.name, weight.name, scale.name)
    val label = s"${act.name}_${weight.name}_${scale.name}_M$mAcc"
    val outDir = s"$ROOT/$label"
    val inputW = 1 + 8 + mAcc

    if (act == MXFormats.INT8) {
      val cfg = RequantINT8Config(
        blockSize      = BLOCK_SIZE,
        tileRows       = TILE_ROWS,
        tileCols       = TILE_COLS,
        scaleType      = scale,
        inputMantWidth = mAcc)
      emitVerilog(new RequantINT8(cfg), Array("--target-dir", outDir))
    } else {
      val cfg = RequantConfig(
        blockSize      = BLOCK_SIZE,
        tileRows       = TILE_ROWS,
        tileCols       = TILE_COLS,
        outputType     = act,
        scaleType      = scale,
        inputMantWidth = mAcc)
      emitVerilog(new RequantFP8(cfg), Array("--target-dir", outDir))
    }
    nEmit += 1
    println(f"[$nEmit%3d/126] $label%-30s (FP$inputW%d input, ${inputW * TILE_ROWS * TILE_COLS}%d-bit total)")
  }

  println()
  println(s"Done. $nEmit configs emitted under $ROOT/")
  println(s"  Each subdir contains <module>.sv (Chisel + FIRRTL Verilog output).")
}
