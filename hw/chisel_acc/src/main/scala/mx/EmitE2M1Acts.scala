package mx

import java.io.{File, PrintWriter}
import scala.io.Source

import chisel3._
import mx.mac.{FDPUPostScaleReductionTree, MXFormats, ScaleAddConfig, ScaleFormats, TreeArch, ElementType}

// ============================================================
//  Extension of DSEFullSweepMain: emit the missing E2M1-activation
//  diagonal (E2M1 × E2M1) across all 6 shared-scale formats.
//
//      1 act (E2M1) × 1 weight (E2M1) × 6 scales = 6 configurations
//
//  Outputs go under the same `generated/dse_full_sweep/{pe,array}/`
//  tree, so they merge cleanly with the existing 180 variants.
// ============================================================
object EmitE2M1Acts extends App {

  private val Root     = "generated/dse_full_sweep"
  private val vsize    = 4
  private val Kdefault = 16384

  private val activations: Seq[ElementType] = Seq(MXFormats.E2M1)
  private val weights: Seq[ElementType]     = Seq(MXFormats.E2M1)
  private val scales = Seq(
    ScaleFormats.UE8M0, ScaleFormats.UE7M1, ScaleFormats.UE6M2,
    ScaleFormats.UE5M3, ScaleFormats.UE4M4, ScaleFormats.UE4M3
  )

  private case class Arch(label: String, treeArch: TreeArch, cpb: Int)
  private val arch = Arch("baseline", TreeArch.Generic, 1)

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
    s"${arch.label}_act-${a.name}_w-${b.name}_${s.name}"

  private def emitOne(a: ElementType, b: ElementType, s: mx.mac.ScaleType): Unit = {
    val lbl  = label(a, b, s)
    val scfg = ScaleAddConfig(a, b, s)

    val peDir = s"$Root/pe/$lbl"
    cleanDir(peDir)
    emitVerilog(
      new FDPUPostScaleReductionTree(
        scfg, vsize, K = Kdefault,
        treeArch = arch.treeArch, cyclesPerBlock = arch.cpb, istest = false),
      Array("--target-dir", peDir)
    )
    consolidate(peDir, "PE")

    // PE-only: requant module does not support E2M1 output, so the array
    // wrapper cannot be assembled. Synthesis of the PE alone is sufficient
    // for the per-bit-area cost comparison this sweep feeds.

    println(f"[E2M1-ACT] $lbl%-50s  R=${scfg.productExpRange}%-3d  cpb=${arch.cpb}%-2d  (PE only)")
  }

  val total = activations.size * weights.size * scales.size
  println(s"\n=== E2M1-activation extension: $total configurations ===")
  for {
    a <- activations
    b <- weights
    s <- scales
  } emitOne(a, b, s)

  println(s"\n[E2M1-ACT] Done. $total variants under $Root/{pe,array}/")
}
