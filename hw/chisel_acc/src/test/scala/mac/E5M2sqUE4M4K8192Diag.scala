package mx.mac

import chisel3._
import chiseltest._
import mx.mac.deferred_archs.FDPUBlockDeferred
import org.scalatest.funsuite.AnyFunSuite
import java.lang.Float.{floatToIntBits, intBitsToFloat}
import scala.io.Source

/** Per-trial diagnostic for E5M2 × E5M2 UE4M4 K=8192 — the one remaining
 *  outlier in the NN-realistic acc-truncation sweep (preR=57%).
 *
 *  Prints, for each of the 10 pre-generated trials:
 *    - swRef (FP64 reference)
 *    - hwAcc (HW output, decoded via dut.actualAccMantBits shift)
 *    - relative error
 *    - sign agreement
 *
 *  Aim: see if error is uniform (suggests systematic bias) or trial-dependent
 *  (suggests pathological input).
 */
class E5M2sqUE4M4K8192Diag extends AnyFunSuite with ChiselScalatestTester {

  private def decodeElement(raw: Int, t: ElementType): Double = {
    if (t.name == "INT8") {
      val sv = if ((raw & 0x80) != 0) raw - 256 else raw
      sv.toDouble * math.pow(2, t.implicitScaleExp)
    } else {
      val sign = if (((raw >> (t.totalWidth - 1)) & 1) == 1) -1.0 else 1.0
      val exp  = (raw >> t.elementWidthMant) & ((1 << t.elementWidthExp) - 1)
      val mant = raw & ((1 << t.elementWidthMant) - 1)
      if (exp == 0) sign * (mant.toDouble / (1 << t.elementWidthMant)) * math.pow(2, 1 - t.bias)
      else          sign * (1.0 + mant.toDouble / (1 << t.elementWidthMant)) * math.pow(2, exp - t.bias)
    }
  }

  private def decodeScale(raw: Int, s: ScaleType): Double = {
    val eBits = (raw >> s.mantScaleWidth) & ((1 << s.expScaleWidth) - 1)
    if (s.mantScaleWidth == 0) math.pow(2, eBits - s.bias)
    else {
      val mBits = raw & ((1 << s.mantScaleWidth) - 1)
      val impl  = if (eBits > 0) 1.0 else 0.0
      val eff   = if (eBits > 0) eBits - s.bias else 1 - s.bias
      (impl + mBits.toDouble / (1 << s.mantScaleWidth)) * math.pow(2, eff)
    }
  }

  private def packElems(elems: Seq[Int], width: Int): BigInt =
    elems.zipWithIndex.foldLeft(BigInt(0)) { case (acc, (v, i)) =>
      acc | (BigInt(v & ((1 << width) - 1)) << (i * width))
    }

  test("Per-trial dump for E5M2² UE4M4 K=8192") {
    val tA = MXFormats.E5M2
    val tB = MXFormats.E5M2
    val sT = ScaleFormats.UE4M4
    val K  = 8192
    val V  = 4
    val cpb = 8
    val scfg = ScaleAddConfig(tA, tB, sT)

    val path = s"acc_trunc_vectors/${tA.name}_${tB.name}_${sT.name}_K$K.tsv"
    val src = Source.fromFile(path)
    val lines = try src.getLines().drop(1).toVector finally src.close()
    case class Row(trial: Int, cyc: Int, sA: Int, sB: Int, a: Seq[Int], b: Seq[Int])
    val rows = lines.map { ln =>
      val p = ln.split("\t")
      Row(p(0).toInt, p(1).toInt, p(2).toInt, p(3).toInt,
          (0 until V).map(i => p(4 + i).toInt),
          (0 until V).map(i => p(4 + V + i).toInt))
    }
    val byTrial = rows.groupBy(_.trial).toVector.sortBy(_._1).map(_._2.sortBy(_.cyc))

    test(new FDPUBlockDeferred(scfg, V, K = K, treeArch = TreeArch.Generic,
                                         cyclesPerBlock = cpb, istest = false)) { dut =>
      println(s"\n=== ${tA.name}×${tB.name} ${sT.name} K=$K ===")
      println(s"  mantWidth (resScaleAddMantWidth) = ${scfg.resScaleAddMantWidth}")
      println(s"  AccPrecision.recommended          = ${AccPrecision.recommended(scfg, K)}")
      println(s"  dut.actualAccMantBits             = ${dut.actualAccMantBits}")
      println()
      println(f"${"tr"}%2s  ${"swRef"}%14s  ${"hwAcc"}%14s  ${"rel"}%10s  ${"sgnOK"}%5s")
      println("─" * 60)

      dut.reset.poke(true.B)
      dut.io.validIn.poke(false.B)
      for (trial <- 0 until math.min(10, byTrial.size)) {
        dut.io.resetAcc.poke(true.B); dut.clock.step(); dut.io.resetAcc.poke(false.B)
        var swRef = 0.0
        for (r <- byTrial(trial)) {
          val sAv = decodeScale(r.sA, sT)
          val sBv = decodeScale(r.sB, sT)
          swRef += r.a.zip(r.b).map { case (a, b) =>
            decodeElement(a, tA) * decodeElement(b, tB) * sAv * sBv
          }.sum
          dut.io.op_a_i       .poke(packElems(r.a, tA.totalWidth).U)
          dut.io.op_b_i       .poke(packElems(r.b, tB.totalWidth).U)
          dut.io.share_exp_A_i.poke(r.sA.U)
          dut.io.share_exp_B_i.poke(r.sB.U)
          dut.io.validIn.poke(true.B); dut.clock.step(); dut.io.validIn.poke(false.B)
        }
        dut.clock.step(1)
        val raw = dut.io.accOut.peek().litValue
        val hw  = intBitsToFloat((raw << (23 - dut.actualAccMantBits)).toInt)
        val rel = if (math.abs(swRef) > 1e-10) math.abs(hw - swRef) / math.abs(swRef) else 0.0
        val sgnOK = math.signum(hw.toDouble) == math.signum(swRef)
        println(f"$trial%2d  $swRef%+14.5e  $hw%+14.5e  $rel%10.3e  $sgnOK%5s")
      }
    }
  }
}
