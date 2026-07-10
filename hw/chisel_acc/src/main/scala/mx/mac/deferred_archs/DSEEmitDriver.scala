package mx.mac.deferred_archs

import chisel3._
import java.io.{File, PrintWriter}
import scala.io.Source
import mx.mac._

/** DSE comparison emitter — moved out of FDPUPostScaleReductionTree.scala
 *  into the deferred_archs/ folder because the comparison is between the
 *  baseline (cycleFP) and the two dropped architectures (blockdef, kulisch).
 *
 *  Each arch's representative configs are emitted side-by-side with a Generic
 *  baseline of the same config.  All split .sv files in each output folder
 *  are concatenated into a single PE.sv for easy synthesis input.
 *
 *  Output layout:
 *    generated/dse_compare/<archLabel>_<cfgLabel>/PE.sv          (arch's tree)
 *    generated/dse_compare/baseline_<archLabel>_<cfgLabel>/PE.sv (Generic ref)
 */
object DSECompareEmitMain extends App {
  private val Root     = "generated/dse_compare"
  private val Kdefault = 16384

  /** Concatenate all .sv files in `dir` into `dir/PE.sv`, then remove the
   *  original split files.  Preserves per-module visibility for synthesis. */
  private def consolidatePE(dir: String): Unit = {
    val dirFile = new File(dir)
    val svFiles = Option(dirFile.listFiles())
      .map(_.filter(_.getName.endsWith(".sv")))
      .getOrElse(Array.empty[File])
      .sortBy(_.getName)
    if (svFiles.nonEmpty) {
      val combined = svFiles.map { f =>
        s"// ─── ${f.getName} ───\n${Source.fromFile(f).mkString}"
      }.mkString("\n")
      val out = new PrintWriter(new File(s"$dir/PE.sv"))
      try out.write(combined) finally out.close()
      svFiles.foreach(_.delete())
    }
  }

  /** Emit one PE variant.  cpb==1 → baseline FDPUPostScaleReductionTree;
   *  cpb>=2 → FDPUBlockDeferred or FDPUKulischDeferred per archForce. */
  private def emitOne(label: String, scfg: ScaleAddConfig, archForce: TreeArch,
                      vsize: Int, cpb: Int): Unit = {
    val dir = s"$Root/$label"
    new File(dir).mkdirs()
    Option(new File(dir).listFiles()).foreach(_.foreach(_.delete()))

    val mod: () => Module = (cpb, archForce) match {
      case (1, _) =>
        () => new FDPUPostScaleReductionTree(
          scfg          = scfg,
          vectorSize    = vsize,
          K             = Kdefault,
          treeArch      = archForce,
          istest        = false)
      case (cpbV, TreeArch.KulischInner) =>
        () => new FDPUKulischDeferred(
          scfg          = scfg,
          vectorSize    = vsize,
          K             = Kdefault,
          cyclesPerBlock = cpbV,
          istest        = false)
      case (cpbV, _) =>
        () => new FDPUBlockDeferred(
          scfg          = scfg,
          vectorSize    = vsize,
          K             = Kdefault,
          treeArch      = archForce,
          cyclesPerBlock = cpbV,
          istest        = false)
    }
    emitVerilog(mod(), Array("--target-dir", dir))
    consolidatePE(dir)
    println(s"[DSE] $label → $dir/PE.sv  (R=${scfg.productExpRange})")
  }

  // (typeA, typeB, scale, vectorSize, label)
  private case class Cfg(typeA: ElementType, typeB: ElementType, scale: ScaleType,
                         vsize: Int, label: String)

  // ── Arch-I IntOnlySigned (productExpRange == 0; only INT8×INT8 qualifies) ──
  private val archIConfigs = Seq(
    Cfg(MXFormats.INT8, MXFormats.INT8, ScaleFormats.UE8M0, 4, "INT8xINT8_UE8M0_V4"),
    Cfg(MXFormats.INT8, MXFormats.INT8, ScaleFormats.UE4M4, 4, "INT8xINT8_UE4M4_V4"),
    Cfg(MXFormats.INT8, MXFormats.INT8, ScaleFormats.UE6M2, 4, "INT8xINT8_UE6M2_V4"),
    Cfg(MXFormats.INT8, MXFormats.INT8, ScaleFormats.UE7M1, 4, "INT8xINT8_UE7M1_V4"),
    Cfg(MXFormats.INT8, MXFormats.INT8, ScaleFormats.UE5M3, 4, "INT8xINT8_UE5M3_V4"),
  )

  // ── Arch-II SmallFixedShift (R ∈ [1, 4]) ──
  private val archIIConfigs = Seq(
    Cfg(MXFormats.E2M1, MXFormats.E2M1, ScaleFormats.UE8M0, 4, "E2M1xE2M1_UE8M0_V4"),
    Cfg(MXFormats.E2M3, MXFormats.E2M3, ScaleFormats.UE8M0, 4, "E2M3xE2M3_UE8M0_V4"),
    Cfg(MXFormats.E2M1, MXFormats.E2M3, ScaleFormats.UE8M0, 4, "E2M1xE2M3_UE8M0_V4"),
    Cfg(MXFormats.INT8, MXFormats.E2M1, ScaleFormats.UE8M0, 4, "INT8xE2M1_UE8M0_V4"),
    Cfg(MXFormats.INT8, MXFormats.E2M3, ScaleFormats.UE8M0, 4, "INT8xE2M3_UE8M0_V4"),
  )

  // ── Arch-III TwoStageBarrel (R ∈ [5, 32]) ──
  private val archIIIConfigs = Seq(
    Cfg(MXFormats.E3M2, MXFormats.E3M2, ScaleFormats.UE8M0, 4, "E3M2xE3M2_UE8M0_V4"),
    Cfg(MXFormats.E4M3, MXFormats.E4M3, ScaleFormats.UE8M0, 4, "E4M3xE4M3_UE8M0_V4"),
    Cfg(MXFormats.INT8, MXFormats.E4M3, ScaleFormats.UE8M0, 4, "INT8xE4M3_UE8M0_V4"),
    Cfg(MXFormats.INT8, MXFormats.E5M2, ScaleFormats.UE8M0, 4, "INT8xE5M2_UE8M0_V4"),
    Cfg(MXFormats.E3M2, MXFormats.E4M3, ScaleFormats.UE8M0, 4, "E3M2xE4M3_UE8M0_V4"),
  )

  // ── Arch-IV.b KulischInner (R > 32 + UE8M0 only) ──
  private val archIVConfigs = Seq(
    Cfg(MXFormats.E5M2, MXFormats.E5M2, ScaleFormats.UE8M0, 4, "E5M2xE5M2_UE8M0_V4"),
    Cfg(MXFormats.E5M2, MXFormats.E5M2, ScaleFormats.UE8M0, 8, "E5M2xE5M2_UE8M0_V8"),
    Cfg(MXFormats.E5M2, MXFormats.E4M3, ScaleFormats.UE8M0, 4, "E5M2xE4M3_UE8M0_V4"),
    Cfg(MXFormats.E5M2, MXFormats.E4M3, ScaleFormats.UE8M0, 8, "E5M2xE4M3_UE8M0_V8"),
    Cfg(MXFormats.E5M2, MXFormats.E3M2, ScaleFormats.UE8M0, 4, "E5M2xE3M2_UE8M0_V4"),
  )

  private def doArch(archLabel: String, archForce: TreeArch,
                     configs: Seq[Cfg], cpb: Int): Unit = {
    println(s"\n── ${archLabel} sweep (cpb=$cpb) ──")
    configs.foreach { c =>
      val scfg = ScaleAddConfig(c.typeA, c.typeB, c.scale)
      emitOne(s"${archLabel}_${c.label}",            scfg, archForce,         c.vsize, cpb)
      emitOne(s"baseline_${archLabel}_${c.label}",   scfg, TreeArch.Generic,  c.vsize, cpb)
    }
  }

  doArch("archI",   TreeArch.IntOnlySigned,   archIConfigs,   cpb = 1)
  doArch("archII",  TreeArch.SmallFixedShift, archIIConfigs,  cpb = 1)
  doArch("archIII", TreeArch.TwoStageBarrel,  archIIIConfigs, cpb = 1)
  doArch("archIV",  TreeArch.KulischInner,    archIVConfigs,  cpb = 8)

  println(s"\n[DSE] All emissions complete under $Root/")
}
