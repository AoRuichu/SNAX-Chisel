package mx

import java.io.{File, PrintWriter}
import scala.io.Source
import chisel3._
import mx.array._
import mx.mac.{FDPUPostScaleReductionTree, MXFormats, ScaleAddConfig, ScaleFormats, TreeArch}
import mx.mac.deferred_archs.FDPUFactory

/** Emit two PE variants of E5M2² × UE6M2 at the same M_acc=12 for area
 *  comparison via Yosys:
 *
 *    - cycleFP:    cyclesPerBlock=1, scale applied every cycle (current baseline)
 *    - blockdef:   cyclesPerBlock=4, scale applied at block boundary
 *                  (uses inner FP accumulator; tree adder + scale multiplier
 *                   active only once per block).
 *
 *  Both share: tree arch = Generic, vsize=4, K=2048, widenUE8M0=true (default).
 */
object EmitArchCompare extends App {
  private val Root  = "generated/arch_compare"
  private val vsize = 4
  private val K     = 2048
  private val M_acc = 12   // E5M2² UE6M2 deployable M from sweep
  private val scfg  = ScaleAddConfig(MXFormats.E5M2, MXFormats.E5M2, ScaleFormats.UE6M2)

  private def cleanDir(dir: String): Unit = {
    val f = new File(dir); f.mkdirs()
    Option(f.listFiles()).foreach(_.foreach(_.delete()))
  }

  private def consolidate(dir: String, name: String): Unit = {
    val combined = s"$name.sv"
    val svFiles = Option(new File(dir).listFiles())
      .map(_.filter(_.getName.endsWith(".sv")))
      .getOrElse(Array.empty[File]).sortBy(_.getName)
    if (svFiles.nonEmpty) {
      val body = svFiles.map(f =>
        s"// ─── ${f.getName} ───\n${Source.fromFile(f).mkString}"
      ).mkString("\n")
      val out = new PrintWriter(new File(s"$dir/$combined"))
      try out.write(body) finally out.close()
      svFiles.foreach(f => if (f.getName != combined) f.delete())
    }
  }

  // Variant A: cycleFP (cpb=1)
  {
    val dir = s"$Root/cycleFP_M$M_acc"
    cleanDir(dir)
    emitVerilog(FDPUFactory(
      scfg = scfg, vectorSize = vsize, K = K,
      accMantBits = M_acc,
      treeArch = TreeArch.Generic, cyclesPerBlock = 1,
      istest = false
    ), Array("--target-dir", dir))
    consolidate(dir, s"PE_cycleFP_M${M_acc}")
    println(s"[arch-compare] cycleFP/M${M_acc} → $dir")
  }

  // Variant B: blockdef (cpb=4)
  {
    val dir = s"$Root/blockdef_M$M_acc"
    cleanDir(dir)
    emitVerilog(FDPUFactory(
      scfg = scfg, vectorSize = vsize, K = K,
      accMantBits = M_acc,
      treeArch = TreeArch.Generic, cyclesPerBlock = 4,
      istest = false
    ), Array("--target-dir", dir))
    consolidate(dir, s"PE_blockdef_M${M_acc}")
    println(s"[arch-compare] blockdef/M${M_acc} → $dir")
  }
  println("Done.")
}
