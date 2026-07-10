package mx.mac

import chisel3._
import chiseltest._
import mx.mac.deferred_archs.FDPUBlockDeferred
import org.scalatest.funsuite.AnyFunSuite
import java.lang.Float.intBitsToFloat
import java.io.{FileWriter, PrintWriter}
import scala.io.Source

/** Dump 32 trials' (swRef, hwAcc) magnitudes for the worst shared-scale
 *  cases.  Goal: verify that post-rq >> pre-rq comes from a few outlier
 *  trials pulling shared_scale wide enough to coarsen the grid for the
 *  rest of the block.
 *
 *  Output: per_trial_magnitudes_<config>.tsv
 *    trial  swRef  hwAcc  abs_swRef  abs_hwAcc
 */
class PerTrialMagnitudeDump extends AnyFunSuite with ChiselScalatestTester {

  case class Cfg(tA: ElementType, tB: ElementType, sT: ScaleType, K: Int, label: String)

  // Worst post-rq case (post=0.88), interesting mid case (post=0.50), and
  // a "well-behaved" case for comparison (post≈0).
  private val cases = Seq(
    Cfg(MXFormats.E4M3, MXFormats.INT8, ScaleFormats.UE8M0, 8192, "E4M3xINT8_UE8M0_K8192"),
    Cfg(MXFormats.E4M3, MXFormats.E4M3, ScaleFormats.UE4M4, 8192, "E4M3xE4M3_UE4M4_K8192"),
    Cfg(MXFormats.E3M2, MXFormats.E3M2, ScaleFormats.UE4M4, 8192, "E3M2xE3M2_UE4M4_K8192"),
  )

  private def decElem(raw: Int, t: ElementType): Double = {
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
  private def decScale(raw: Int, s: ScaleType): Double = {
    val e = (raw >> s.mantScaleWidth) & ((1 << s.expScaleWidth) - 1)
    if (s.mantScaleWidth == 0) math.pow(2, e - s.bias)
    else {
      val m = raw & ((1 << s.mantScaleWidth) - 1)
      val impl = if (e > 0) 1.0 else 0.0
      val eff  = if (e > 0) e - s.bias else 1 - s.bias
      (impl + m.toDouble / (1 << s.mantScaleWidth)) * math.pow(2, eff)
    }
  }
  private def packE(elems: Seq[Int], w: Int): BigInt =
    elems.zipWithIndex.foldLeft(BigInt(0)) { case (a, (v, i)) =>
      a | (BigInt(v & ((1 << w) - 1)) << (i * w))
    }

  for (cfg <- cases) {
    test(s"per-trial magnitudes ${cfg.label}") {
      val V = 4; val cpb = 8
      val scfg = ScaleAddConfig(cfg.tA, cfg.tB, cfg.sT)
      val path = s"acc_trunc_vectors/${cfg.tA.name}_${cfg.tB.name}_${cfg.sT.name}_K${cfg.K}.tsv"
      val src = Source.fromFile(path)
      val rows = try src.getLines().drop(1).toVector finally src.close()
      case class R(trial: Int, cyc: Int, sA: Int, sB: Int, a: Seq[Int], b: Seq[Int])
      val parsed = rows.map { ln =>
        val p = ln.split("\t")
        R(p(0).toInt, p(1).toInt, p(2).toInt, p(3).toInt,
          (0 until V).map(i => p(4 + i).toInt),
          (0 until V).map(i => p(4 + V + i).toInt))
      }
      val byTrial = parsed.groupBy(_.trial).toVector.sortBy(_._1).map(_._2.sortBy(_.cyc))
      val out = new PrintWriter(new FileWriter(s"per_trial_magnitudes_${cfg.label}.tsv"))
      out.println("trial\tswRef\thwAcc\tabs_swRef\tabs_hwAcc")
      test(new FDPUBlockDeferred(scfg, V, K = cfg.K,
                                           treeArch = TreeArch.Generic,
                                           cyclesPerBlock = cpb, istest = false)) { dut =>
        dut.reset.poke(true.B); dut.io.validIn.poke(false.B)
        for (trial <- 0 until byTrial.size) {
          dut.io.resetAcc.poke(true.B); dut.clock.step(); dut.io.resetAcc.poke(false.B)
          var swRef = 0.0
          for (r <- byTrial(trial)) {
            val sAv = decScale(r.sA, cfg.sT); val sBv = decScale(r.sB, cfg.sT)
            swRef += r.a.zip(r.b).map { case (a, b) =>
              decElem(a, cfg.tA) * decElem(b, cfg.tB) * sAv * sBv
            }.sum
            dut.io.op_a_i.poke(packE(r.a, cfg.tA.totalWidth).U)
            dut.io.op_b_i.poke(packE(r.b, cfg.tB.totalWidth).U)
            dut.io.share_exp_A_i.poke(r.sA.U)
            dut.io.share_exp_B_i.poke(r.sB.U)
            dut.io.validIn.poke(true.B); dut.clock.step(); dut.io.validIn.poke(false.B)
          }
          dut.clock.step(1)
          val rawAcc = dut.io.accOut.peek().litValue
          val hwAcc = intBitsToFloat((rawAcc << (23 - dut.actualAccMantBits)).toInt)
          out.println(f"$trial\t$swRef%+.6e\t$hwAcc%+.6e\t${math.abs(swRef)}%.6e\t${math.abs(hwAcc)}%.6e")
        }
      }
      out.close()
      println(s"  → per_trial_magnitudes_${cfg.label}.tsv")
    }
  }
}
