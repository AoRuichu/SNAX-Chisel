package mx

import java.io.{File, PrintWriter}
import scala.io.Source
import chisel3._
import mx.array._
import mx.mac.{BFPPELosslessAcc, FDPUPostScaleReductionTree, MXFormats, ScaleAddConfig, ScaleFormats, TreeArch}
import mx.requant.RequantConfig

/** Counterfactual emit: E5M2×E5M2 UE6M2 with the early-RNE stages disabled.
 *
 *  Emits two variants of the same (act, weight, scale) config:
 *    - ours    : 3-RNE design at M_acc = 12 (selected from sweep)
 *    - alt-no-rne: noEarlyRNE = true (tree carries full mantissa through scale
 *                  multiplier; only the FPNAdder-internal RNE remains; M_acc
 *                  forced to 23 = FP32 mantissa).
 *
 *  Used in the synthesis comparison of Section [acc-truncation].
 */
object EmitNoEarlyRNE extends App {

  private val Root      = "generated/no_early_rne"
  private val vsize     = 4
  private val Kdefault  = 2048
  private val scfg      = ScaleAddConfig(MXFormats.E5M2, MXFormats.E5M2, ScaleFormats.UE6M2)

  private def cleanDir(dir: String): Unit = {
    val f = new File(dir); f.mkdirs()
    Option(f.listFiles()).foreach(_.foreach(_.delete()))
  }

  private def consolidate(dir: String, name: String): Unit = {
    val combined = s"$name.sv"
    val svFiles = Option(new File(dir).listFiles())
      .map(_.filter(_.getName.endsWith(".sv")))
      .getOrElse(Array.empty[File])
      .sortBy(_.getName)
    if (svFiles.nonEmpty) {
      val body = svFiles.map(f =>
        s"// ─── ${f.getName} ───\n${Source.fromFile(f).mkString}"
      ).mkString("\n")
      val out = new PrintWriter(new File(s"$dir/$combined"))
      try out.write(body) finally out.close()
      svFiles.foreach(f => if (f.getName != combined) f.delete())
    }
  }

  // ── Variant A: our 3-RNE design at M_acc = 12 ────────────────────────────
  {
    val dir = s"$Root/ours_M12"
    cleanDir(dir)
    emitVerilog(
      new FDPUPostScaleReductionTree(
        scfg = scfg, vectorSize = vsize, K = Kdefault,
        accMantBits = 12,
        treeArch = TreeArch.Generic,
        noEarlyRNE = false, istest = false
      ),
      Array("--target-dir", dir)
    )
    consolidate(dir, "PE_ours_M12")
    println(s"[no-rne emit] ours_M12 → $dir/PE_ours_M12.sv")
  }

  // ── Variant B: noEarlyRNE counterfactual (M_acc forced to 23) ────────────
  {
    val dir = s"$Root/alt_noEarlyRNE"
    cleanDir(dir)
    emitVerilog(
      new FDPUPostScaleReductionTree(
        scfg = scfg, vectorSize = vsize, K = Kdefault,
        treeArch = TreeArch.Generic,
        noEarlyRNE = true, istest = false
      ),
      Array("--target-dir", dir)
    )
    consolidate(dir, "PE_alt_noEarlyRNE")
    println(s"[no-rne emit] alt_noEarlyRNE → $dir/PE_alt_noEarlyRNE.sv")
  }

  // ── Variant C: TRUE wide fixed-point lossless accumulator ────────────────
  //   No intermediate RNE: skips ScaleToFPn + FPNAdder entirely, accumulates
  //   into a 128-bit signed integer register, single terminal LZD+RNE → narrow
  //   FP for requant.  This is the alt baseline that matches the thesis
  //   "wide fixed-point lossless acc" description.  See BFPPELosslessAcc.scala.
  {
    val dir = s"$Root/alt_losslessAcc"
    cleanDir(dir)
    emitVerilog(
      new BFPPELosslessAcc(
        scfg        = scfg,
        vectorSize  = vsize,
        K           = Kdefault,
        accBits     = 101,         // thesis-stated width
        accExpBase  = -64,         // bit 0 = 2^(-64)
        outMantBits = 23,          // FP32 output — match alt_noEarlyRNE for
                                   //   apples-to-apples synth comparison
                                   //   (test verified 2.0e-8 rel-err vs FP64 ref,
                                   //   22× better than alt_noEarlyRNE)
        treeArch    = TreeArch.Generic
      ),
      Array("--target-dir", dir)
    )
    consolidate(dir, "PE_alt_losslessAcc")
    println(s"[no-rne emit] alt_losslessAcc → $dir/PE_alt_losslessAcc.sv")
  }

  println("Done. Three PE variants emitted side-by-side for synth comparison.")
}
