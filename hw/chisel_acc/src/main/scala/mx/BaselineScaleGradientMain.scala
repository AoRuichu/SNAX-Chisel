package mx

import java.io.{File, PrintWriter}
import scala.io.Source

import chisel3._
import mx.array._
import mx.mac.{FDPUPostScaleReductionTree, MXFormats, ScaleAddConfig, ScaleFormats, TreeArch, ElementType}
import mx.mac.deferred_archs.FDPUFactory
import mx.requant.{RequantConfig, RequantINT8Config}

// ============================================================
//  Baseline (Cycle-FP) scale-format gradient sweep.
//
//  Fills the gap between the existing UE8M0 / UE6M2 / UE4M4 baseline
//  synthesis results so the HW-efficiency-vs-scale-format Pareto front
//  is sampled at every 1-bit scale-mantissa step (0, 1, 2, 3, 4):
//      UE8M0  (done in full DSE sweep)
//      UE7M1  ← this sweep
//      UE6M2  (done)
//      UE5M3  ← this sweep
//      UE4M4  (done)
//
//  Block-FX is intentionally NOT swept here; the SW chapter is the
//  primary deliverable and per-arch differences are a secondary
//  observation that the existing 3-scale data is enough to capture.
//
//  Emits 5 acts × 6 weights × 2 scales = 60 baseline PE + 60 PE_Array
//  RTL variants into generated/baseline_scale_gradient/{pe,array}/<label>/.
// ============================================================
object BaselineScaleGradientMain extends App {

  private val Root      = "generated/baseline_scale_gradient"
  private val vsize     = 4
  private val tileRows  = 4
  private val tileCols  = 16
  private val blockSize = 32
  private val Kdefault  = 16384

  private val activations: Seq[ElementType] =
    Seq(MXFormats.INT8, MXFormats.E5M2, MXFormats.E4M3, MXFormats.E3M2, MXFormats.E2M3)
  private val weights: Seq[ElementType] =
    Seq(MXFormats.INT8, MXFormats.E5M2, MXFormats.E4M3, MXFormats.E3M2, MXFormats.E2M3, MXFormats.E2M1)
  private val scales = Seq(ScaleFormats.UE7M1, ScaleFormats.UE5M3)  // ← only new ones

  private val archLabel    = "baseline"
  private val archTreeArch = TreeArch.Generic
  private val archCpb      = 1

  // ─── Helpers ────────────────────────────────────────────────────────────
  private def cleanDir(dir: String): Unit = {
    val f = new File(dir); f.mkdirs()
    Option(f.listFiles()).foreach(_.foreach(_.delete()))
  }

  private def consolidate(dir: String, combinedName: String): Unit = {
    val combinedFileName = s"$combinedName.sv"
    val svFiles = Option(new File(dir).listFiles())
      .map(_.filter(_.getName.endsWith(".sv")))
      .getOrElse(Array.empty[File])
      .sortBy(_.getName)
    if (svFiles.nonEmpty) {
      val body = svFiles.map { f =>
        s"// ─── ${f.getName} ───\n${Source.fromFile(f).mkString}"
      }.mkString("\n")
      val out = new PrintWriter(new File(s"$dir/$combinedFileName"))
      try out.write(body) finally out.close()
      svFiles.foreach { f =>
        if (f.getName != combinedFileName) f.delete()
      }
    }
  }

  private def label(a: ElementType, b: ElementType, s: mx.mac.ScaleType): String =
    s"${archLabel}_act-${a.name}_w-${b.name}_${s.name}"

  // ─── Emit one (A, B, scale) — both PE and array variants ────────────────
  private def emitOne(a: ElementType, b: ElementType, s: mx.mac.ScaleType): Unit = {
    val lbl     = label(a, b, s)
    val scfg    = ScaleAddConfig(a, b, s)
    val ovr     = ArchOverride(archTreeArch, archCpb)

    // 1) Single PE.
    val peDir = s"$Root/pe/$lbl"
    cleanDir(peDir)
    emitVerilog(
      FDPUFactory(
        scfg, vsize, K = Kdefault,
        treeArch = archTreeArch, cyclesPerBlock = archCpb, istest = false),
      Array("--target-dir", peDir)
    )
    consolidate(peDir, "PE")

    // 2) Array (output element type = activation type).
    val arrDir = s"$Root/array/$lbl"
    cleanDir(arrDir)
    if (a.name == "INT8") {
      val cfg = PEArrayINT8Config(
        macCfg     = scfg,
        vectorSize = vsize, tileRows = tileRows, tileCols = tileCols,
        requantCfg = RequantINT8Config(blockSize, tileRows, tileCols, s),
        K          = Kdefault,
        archOverride = Some(ovr)
      )
      emitVerilog(new PEArrayWrapperINT8(cfg), Array("--target-dir", arrDir))
    } else {
      val cfg = PEArrayConfig(
        macCfg     = scfg,
        vectorSize = vsize, tileRows = tileRows, tileCols = tileCols,
        requantCfg = RequantConfig(blockSize, tileRows, tileCols, a, s),
        K          = Kdefault,
        archOverride = Some(ovr)
      )
      emitVerilog(new PEArrayWrapper(cfg), Array("--target-dir", arrDir))
    }
    consolidate(arrDir, "PE_Array")

    println(f"[BASELINE-SCALE-GRADIENT] $lbl%-45s R=${scfg.productExpRange}%-3d  scaleMant=${s.mantScaleWidth}")
  }

  // ─── Run the sweep ──────────────────────────────────────────────────────
  val total = activations.size * weights.size * scales.size
  println(s"\n=== Baseline-only scale-gradient sweep: $total configurations ===")
  println(s"=== Filling UE7M1 + UE5M3 to complete the (UE8M0→UE7M1→UE6M2→UE5M3→UE4M4) sequence ===\n")
  for {
    a <- activations
    b <- weights
    s <- scales
  } emitOne(a, b, s)

  println(s"\n[BASELINE-SCALE-GRADIENT] Done. $total variants under $Root/{pe,array}/")
}
