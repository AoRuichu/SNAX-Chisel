package mx

import java.io.{File, PrintWriter}
import scala.io.Source
import chisel3._
import mx.array._
import mx.mac.{FDPUPostScaleReductionTree, MXFormats, ScaleAddConfig, ScaleFormats, TreeArch}

/** Quick emit + diff to verify widenUE8M0=true changes the RTL widths
 *  on the UE8M0 path of E5M2² for the running thesis case.
 */
object EmitWidenUE8M0Check extends App {
  private val Root  = "generated/widen_check"
  private val vsize = 4
  private val K     = 2048
  private val scfg  = ScaleAddConfig(MXFormats.E5M2, MXFormats.E5M2, ScaleFormats.UE8M0)

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

  // Emit two variants of E5M2² UE8M0 PE: widenUE8M0 = false vs true.
  for ((tag, w) <- Seq("legacy" -> false, "widen" -> true);
       M <- Seq(12, 15)) {
    val dir = s"$Root/${tag}_M$M"
    cleanDir(dir)
    emitVerilog(new FDPUPostScaleReductionTree(
      scfg = scfg, vectorSize = vsize, K = K,
      accMantBits = M, treeArch = TreeArch.Generic,
      widenUE8M0 = w, istest = false
    ), Array("--target-dir", dir))
    consolidate(dir, s"PE_${tag}_M${M}")
    println(s"[widen-check] ${tag}_M${M} → $dir")
  }
  println("Done.")
}
