package mx.mac

import chisel3._
import chiseltest._
import org.scalatest.funsuite.AnyFunSuite
import java.lang.Float.intBitsToFloat
import java.io.File
import scala.io.Source

/** Tree shrink experiment for E5M2 × E5M2 + UE6M2.
 *
 *  The reduction tree's alignment-shift width is sized by productExpRange
 *  (= 58 for E5M2², dominating absMagW = 66 bits).  Capping it shrinks the
 *  per-lane shifter + integer adder tree, but any lane whose exp differs
 *  from maxExp by more than the cap is CLAMPED → its mantissa contributes
 *  an over-magnified term to the sum.
 *
 *  This test answers: for realistic + heavy-tail NN workloads, how small
 *  can productExpRange go before the clamp damage exceeds the requant
 *  noise floor?
 *
 *  Workloads:
 *    q_proj_l0       — clean attention proj, σ_act=0.372 (low-spread)
 *    gate_proj_l1    — heavy MLP gate,        σ_act=9.420 (heavy tail)
 *
 *  Per (workload, range): 64 trials × K=2048 cycles each, compare per-trial
 *  HW vs FP64 ref relative error.  Report median + p95 + p99 + max.
 *
 *  Safety bound: median(ε_acc) < ½·ε_req  (matches the M_acc-selection rule
 *  in macc_final_selection.csv).  ε_req comes from the same CSV.
 */
class TreeShrinkE5M2Test extends AnyFunSuite with ChiselScalatestTester {

  // ── Helpers ───────────────────────────────────────────────────────────────
  private def decodeElement(raw: Int, t: ElementType): Double = {
    val sign = if (((raw >> (t.totalWidth - 1)) & 1) == 1) -1.0 else 1.0
    val exp  = (raw >> t.elementWidthMant) & ((1 << t.elementWidthExp) - 1)
    val mant = raw & ((1 << t.elementWidthMant) - 1)
    if (exp == 0) sign * (mant.toDouble / (1 << t.elementWidthMant)) * math.pow(2, 1 - t.bias)
    else          sign * (1.0 + mant.toDouble / (1 << t.elementWidthMant)) * math.pow(2, exp - t.bias)
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

  /** Decode the per-lane product exponent (unbiased, post product) for diff
   *  analysis.  Returns Int (≈ exp_A + exp_B − biases). */
  private def laneProductExp(rawA: Int, rawB: Int, t: ElementType): Int = {
    def unbiasedExp(raw: Int): Int = {
      val exp = (raw >> t.elementWidthMant) & ((1 << t.elementWidthExp) - 1)
      if (exp == 0) 1 - t.bias else exp - t.bias
    }
    unbiasedExp(rawA) + unbiasedExp(rawB)
  }

  private def packElems(elems: Seq[Int], width: Int): BigInt =
    elems.zipWithIndex.foldLeft(BigInt(0)) { case (acc, (v, i)) =>
      acc | (BigInt(v & ((1 << width) - 1)) << (i * width))
    }

  // ── Config ────────────────────────────────────────────────────────────────
  private val scfg      = ScaleAddConfig(MXFormats.E5M2, MXFormats.E5M2, ScaleFormats.UE6M2)
  private val K         = 2048
  private val vec       = 4
  private val nTrials   = 64
  private val mAcc      = 11               // sweep-selected M_acc

  // ε_req per workload — sweep-measured medians from macc_final_selection.csv
  // (the floor is empirically very close across workloads for the same
  // output format + scale type — both are ~4e-2 for E5M2²+UE6M2).
  private val epsReq    = 4.366e-2
  private val epsReqHalf = epsReq * 0.5

  private case class Cyc(a: Seq[Int], b: Seq[Int], sA: Int, sB: Int)

  private def loadWorkload(name: String): Vector[Vector[Cyc]] = {
    val path = s"acc_trunc_vectors/$name/${scfg.elementTypeA.name}_${scfg.elementTypeB.name}_${scfg.stype.name}_K$K.tsv"
    val f = new File(path)
    require(f.exists, s"Vector file missing: $path. Run gen_acc_truncation_vectors.py.")
    val src = Source.fromFile(f)
    val raw = try src.getLines().drop(1).toVector finally src.close()
    raw.map { line =>
      val parts = line.split("\t")
      (parts(0).toInt, parts(1).toInt,
       Cyc(a  = (0 until vec).map(i => parts(4 + i).toInt),
           b  = (0 until vec).map(i => parts(4 + vec + i).toInt),
           sA = parts(2).toInt,
           sB = parts(3).toInt))
    }.groupBy(_._1).toVector.sortBy(_._1)
      .map { case (_, rows) => rows.sortBy(_._2).map(_._3).toVector }
      .take(nTrials)
  }

  /** FP64 reference per trial (sum of decoded products × scales). */
  private def computeRef(perTrial: Vector[Vector[Cyc]]): Vector[Double] =
    perTrial.map { trial =>
      trial.foldLeft(0.0) { case (acc, c) =>
        val sAv = decodeScale(c.sA, scfg.stype)
        val sBv = decodeScale(c.sB, scfg.stype)
        acc + c.a.zip(c.b).map { case (a, b) =>
          decodeElement(a, scfg.elementTypeA) *
          decodeElement(b, scfg.elementTypeB) * sAv * sBv
        }.sum
      }
    }

  /** Pre-analysis: per-cycle lane-exp-diff (maxLaneExp − minLaneExp) across
   *  the (vec) lanes.  Returns the distribution: max, p99, p95, median. */
  private def laneDiffDistribution(perTrial: Vector[Vector[Cyc]]):
      (Int, Int, Int, Int) = {
    val diffs = scala.collection.mutable.ArrayBuffer.empty[Int]
    for (trial <- perTrial; c <- trial) {
      val laneExps = c.a.zip(c.b).map { case (a, b) =>
        laneProductExp(a, b, scfg.elementTypeA)
      }
      diffs += (laneExps.max - laneExps.min)
    }
    val sorted = diffs.sorted
    val n = sorted.length
    (sorted.last,
     sorted((n.toLong * 99 / 100).toInt.min(n - 1)),
     sorted((n.toLong * 95 / 100).toInt.min(n - 1)),
     sorted(n / 2))
  }

  // ── Stats aggregator ──────────────────────────────────────────────────────
  private case class Stats(label: String, range: Int, errs: Vector[Double]) {
    private val sorted = errs.filter(!_.isNaN).sorted
    private def pct(p: Int): Double =
      if (sorted.isEmpty) 0.0
      else sorted((sorted.length.toLong * p / 100).toInt.min(sorted.length - 1))
    val median = pct(50)
    val p95    = pct(95)
    val p99    = pct(99)
    val max    = if (sorted.isEmpty) 0.0 else sorted.last
    val safe   = median < epsReqHalf
    def print(): Unit =
      println(f"    range=$range%2d  median=$median%.2e  p95=$p95%.2e  p99=$p99%.2e  " +
              f"max=$max%.2e   med/½ε_req=${median / epsReqHalf}%4.2f  " +
              f"${if (safe) "✓" else "✗"}")
  }

  private def runRange(perTrial: Vector[Vector[Cyc]], swRef: Vector[Double],
                       range: Int): Stats = {
    val errs = scala.collection.mutable.ArrayBuffer.empty[Double]
    test(new FDPUPostScaleReductionTree(
      scfg, vec, K = K, accMantBits = mAcc,
      treeArch = TreeArch.Generic, istest = false,
      treeProductExpRangeOverride = range)) { dut =>
      dut.reset.poke(true.B)
      dut.io.validIn.poke(false.B)
      val mantShift = 23 - dut.actualAccMantBits
      for (t <- 0 until perTrial.length) {
        dut.io.resetAcc.poke(true.B); dut.clock.step(); dut.io.resetAcc.poke(false.B)
        for (c <- perTrial(t)) {
          dut.io.op_a_i       .poke(packElems(c.a, scfg.elementTypeA.totalWidth).U)
          dut.io.op_b_i       .poke(packElems(c.b, scfg.elementTypeB.totalWidth).U)
          dut.io.share_exp_A_i.poke(c.sA.U)
          dut.io.share_exp_B_i.poke(c.sB.U)
          dut.io.validIn.poke(true.B); dut.clock.step(); dut.io.validIn.poke(false.B)
        }
        dut.clock.step()
        val raw = dut.io.accOut.peek().litValue
        val hw  = intBitsToFloat((raw << mantShift).toInt).toDouble
        val ref = swRef(t)
        val e   = if (math.abs(ref) > 1e-12) math.abs(hw - ref) / math.abs(ref) else 0.0
        errs += e
      }
    }
    Stats(s"productExpRange=$range", range, errs.toVector)
  }

  // ── Per-workload sweep driver ────────────────────────────────────────────
  private val ranges = Seq(58, 16, 8, 6, 4, 2)

  private def sweepWorkload(name: String): Unit = {
    val perTrial = loadWorkload(name)
    val swRef    = computeRef(perTrial)
    val (dMax, dP99, dP95, dMed) = laneDiffDistribution(perTrial)

    println(f"\n── Workload: $name%-16s  (${perTrial.length} trials × $K cycles) ──")
    println(f"   Lane-exp-diff distribution (per cycle, across $vec lanes):")
    println(f"     max=$dMax%2d  p99=$dP99%2d  p95=$dP95%2d  median=$dMed%2d")
    println( "     → if range ≥ p99, clamp fires < 1% of cycles (negligible)")
    println( "     → if range < median, clamp fires > 50% of cycles (catastrophic)\n")

    val results = ranges.map(r => runRange(perTrial, swRef, r))
    println(s"   Per-range ε_acc stats (M_acc=$mAcc, ε_req=$epsReq, ½·ε_req=$epsReqHalf):")
    results.foreach(_.print())
    val safeRanges = results.filter(_.safe).map(_.range)
    val verdict = if (safeRanges.nonEmpty)
      s"smallest safe productExpRange = ${safeRanges.min}"
    else
      "NO tested range satisfies the safety bound"
    println(s"   [$name verdict] $verdict")
  }

  test("Tree shrink sweep: E5M2² UE6M2, q_proj_l0 (clean) vs gate_proj_l1 (heavy)") {
    println("\n=== Tree-shrink sweep across NN workloads ===")
    println(s"Config: E5M2 × E5M2 + UE6M2, K=$K, M_acc=$mAcc, $nTrials trials/workload")
    println(s"Safety rule: median(ε_acc) < ½·ε_req = $epsReqHalf  " +
            "(same rule that picked M_acc=11)")

    sweepWorkload("q_proj_l0")
    sweepWorkload("gate_proj_l1")
  }
}
