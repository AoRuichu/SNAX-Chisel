package mx

import java.io.{File, PrintWriter}
import scala.io.Source

import chisel3._
import mx.array._
import mx.mac.{FDPUPostScaleReductionTree, MXFormats, ScaleAddConfig, ScaleFormats, TreeArch, ElementType}
import mx.mac.deferred_archs.FDPUFactory
import mx.requant.{RequantConfig, RequantINT8Config}

// ============================================================
//  Full DSE sweep: 3 accumulator architectures
//                × 5 activation formats
//                × 6 weight formats
//                × 3 shared-scale formats
//                = 270 configurations.
//
// For each configuration we emit two RTL artefacts:
//   generated/dse_full_sweep/pe/<label>/PE.sv         — single FDPU
//   generated/dse_full_sweep/array/<label>/PE_Array.sv — 4 × 16 wrapper with
//                                                       requant (output type
//                                                       follows activation)
//
// Pipeline knobs are intentionally OFF for this sweep (timing-consistent
// across the three architectures, see PEArray wrapper's accCnt gate).
//
// Activation formats (5): INT8, E5M2, E4M3, E3M2, E2M3
// Weight     formats (6): INT8, E5M2, E4M3, E3M2, E2M3, E2M1
// Shared scale       (6): UE8M0, UE7M1, UE6M2, UE5M3, UE4M4, UE4M3 (NVFP4)
// Architecture       (1): baseline   — Generic tree, cpb=1 (buildSingleAcc),
//                                      blockSize=16 (NVFP4-style micro-block)
// ============================================================
object DSEFullSweepMain extends App {

  // ─── Sweep domain ───────────────────────────────────────────────────────
  private val Root      = "generated/dse_full_sweep"
  private val vsize     = 4
  private val tileRows  = 4
  private val tileCols  = 16
  private val blockSize = 16                // NVFP4-style 16-element micro-block
  private val Kdefault  = 16384

  private val activations: Seq[ElementType] =
    Seq(MXFormats.INT8, MXFormats.E5M2, MXFormats.E4M3, MXFormats.E3M2, MXFormats.E2M3, MXFormats.E2M1)
  private val weights: Seq[ElementType] =
    Seq(MXFormats.INT8, MXFormats.E5M2, MXFormats.E4M3, MXFormats.E3M2, MXFormats.E2M3, MXFormats.E2M1)
  private val scales = Seq(
    ScaleFormats.UE8M0, ScaleFormats.UE7M1, ScaleFormats.UE6M2,
    ScaleFormats.UE5M3, ScaleFormats.UE4M4, ScaleFormats.UE4M3
  )

  private case class Arch(label: String, treeArch: TreeArch, cpb: Int)
  private val archs = Seq(
    Arch("baseline", TreeArch.Generic,      1),
    // Block-FX / blockdef removed — thesis adopts Cycle-FP (baseline) only.
    // Arch("blockdef", TreeArch.Generic,      blockSize / vsize),
    // Arch("kulisch",  TreeArch.KulischInner, blockSize / vsize),
  )

  // ─── Helpers ────────────────────────────────────────────────────────────
  private def cleanDir(dir: String): Unit = {
    val f = new File(dir); f.mkdirs()
    Option(f.listFiles()).foreach(_.foreach(_.delete()))
  }

  /** Concatenate every .sv file in `dir` into a single `<combinedName>.sv`,
   *  preserving the per-module header for readability.  If Chisel itself
   *  produced a file named `<combinedName>.sv` (e.g. when desiredName matches),
   *  it is included in the body BEFORE being overwritten — but never deleted
   *  afterwards (we only delete the *other* fragment files). */
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

  private def label(arch: Arch, a: ElementType, b: ElementType, s: mx.mac.ScaleType): String =
    s"${arch.label}_act-${a.name}_w-${b.name}_${s.name}"

  // ─── Emit one (arch, A, B, scale) — both PE and array variants ─────────
  private def emitOne(arch: Arch, a: ElementType, b: ElementType, s: mx.mac.ScaleType): Unit = {
    val lbl     = label(arch, a, b, s)
    val scfg    = ScaleAddConfig(a, b, s)
    val ovr     = ArchOverride(arch.treeArch, arch.cpb)

    // 1) Single PE — emit FDPUPostScaleReductionTree directly.
    val peDir = s"$Root/pe/$lbl"
    cleanDir(peDir)
    emitVerilog(
      FDPUFactory(
        scfg, vsize, K = Kdefault,
        treeArch = arch.treeArch, cyclesPerBlock = arch.cpb, istest = false),
      Array("--target-dir", peDir)
    )
    consolidate(peDir, "PE")

    // 2) Array — wrapper choice follows activation type.
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

    println(f"[FULL-SWEEP] $lbl%-50s  R=${scfg.productExpRange}%-3d  cpb=${arch.cpb}%-2d")
  }

  // ─── Run the sweep ──────────────────────────────────────────────────────
  val total = archs.size * activations.size * weights.size * scales.size
  println(s"\n=== DSE full sweep: $total configurations ===")
  for {
    arch <- archs
    a    <- activations
    b    <- weights
    s    <- scales
  } emitOne(arch, a, b, s)

  println(s"\n[FULL-SWEEP] Done. $total variants under $Root/{pe,array}/")
}
