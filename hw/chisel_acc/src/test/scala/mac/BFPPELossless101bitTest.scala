package mx.mac

import chisel3._
import chiseltest._
import org.scalatest.funsuite.AnyFunSuite
import java.lang.Float.intBitsToFloat
import java.io.{File}
import scala.io.Source

/** Validate that the 101-bit lossless wide-fixed accumulator does not drift
 *  from the FP64 software reference, and compare its error against the two
 *  existing baselines (ours_M12 + alt_noEarlyRNE).
 *
 *  Workload: q_proj_l0 E5M2×E5M2 + UE6M2, K=2048, first 16 trials of the
 *  pre-generated vectors in acc_trunc_vectors/.  Each trial = one output
 *  channel's dot product over 2048 K-cycles.
 *
 *  Stats per variant: mean / max / p95 relative error of HW vs FP64 ref.
 *  101-bit lossless should match ours_M12 closely (both single-cycle adds,
 *  similar rounding behaviour); the goal is NO catastrophic dynamic-range
 *  saturation at 101 bits for the q_proj_l0 workload.
 */
class BFPPELossless101bitTest extends AnyFunSuite with ChiselScalatestTester {

  // ── Helpers (verbatim from AccTruncationSweepTest) ────────────────────────
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

  // ── Workload / config ─────────────────────────────────────────────────────
  private val workload = "q_proj_l0"
  private val scfg     = ScaleAddConfig(MXFormats.E5M2, MXFormats.E5M2, ScaleFormats.UE6M2)
  private val K        = 2048
  private val vec      = 4
  private val nTrials  = 16              // small slice for tractable test wall time
  private val outMantBits = 12           // match ours_M12 + lossless out for fair compare

  // Load vector file (must exist; otherwise skip the whole class).
  private val vectorFile = {
    val path = s"acc_trunc_vectors/$workload/${scfg.elementTypeA.name}_${scfg.elementTypeB.name}_${scfg.stype.name}_K$K.tsv"
    val f = new File(path)
    require(f.exists, s"Vector file missing: $path. Run gen_acc_truncation_vectors.py first.")
    val src = Source.fromFile(f)
    try src.getLines().drop(1).toVector finally src.close()
  }

  // Reshape into per-trial / per-cycle: Vector[Vector[ (a,b,sA,sB) ]]
  private case class Cyc(a: Seq[Int], b: Seq[Int], sA: Int, sB: Int)
  private val perTrial: Vector[Vector[Cyc]] = {
    vectorFile.map { line =>
      val parts = line.split("\t")
      val trial = parts(0).toInt
      val cyc   = parts(1).toInt
      val sA    = parts(2).toInt
      val sB    = parts(3).toInt
      val a     = (0 until vec).map(i => parts(4 + i).toInt)
      val b     = (0 until vec).map(i => parts(4 + vec + i).toInt)
      (trial, cyc, Cyc(a, b, sA, sB))
    }.groupBy(_._1).toVector.sortBy(_._1)
      .map { case (_, rows) => rows.sortBy(_._2).map(_._3).toVector }
      .take(nTrials)
  }

  // Pre-compute FP64 software reference (one value per trial)
  private val swRef: Vector[Double] = perTrial.map { trial =>
    trial.foldLeft(0.0) { case (acc, c) =>
      val sAv = decodeScale(c.sA, scfg.stype)
      val sBv = decodeScale(c.sB, scfg.stype)
      acc + c.a.zip(c.b).map { case (a, b) =>
        decodeElement(a, scfg.elementTypeA) * decodeElement(b, scfg.elementTypeB) * sAv * sBv
      }.sum
    }
  }

  // ── Result aggregator ─────────────────────────────────────────────────────
  private case class Stats(label: String, perTrialErr: Vector[Double]) {
    private val errs = perTrialErr.filter(!_.isNaN).sorted
    val mean = errs.sum / errs.length.max(1)
    val max  = if (errs.isEmpty) 0.0 else errs.last
    val p95  = if (errs.isEmpty) 0.0 else errs((errs.length * 95) / 100)
    def print(): Unit =
      println(f"  $label%-32s  mean=$mean%.3e  p95=$p95%.3e  max=$max%.3e   n=${errs.length}")
  }

  // ── Common driver: drives one DUT, returns per-trial relative errors ──────
  // Returns Vector[Double] of length nTrials.
  private def runFDPU(label: String,
                      mkDut: => FDPUPostScaleReductionTree): Stats = {
    val errs = scala.collection.mutable.ArrayBuffer.empty[Double]
    test(mkDut) { dut =>
      dut.reset.poke(true.B)
      dut.io.validIn.poke(false.B)
      val mantShift = 23 - dut.actualAccMantBits
      for (t <- 0 until nTrials) {
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
    Stats(label, errs.toVector)
  }

  private def runLossless(label: String,
                          mkDut: => BFPPELosslessAcc): Stats = {
    val errs = scala.collection.mutable.ArrayBuffer.empty[Double]
    test(mkDut) { dut =>
      dut.reset.poke(true.B)
      dut.io.validIn.poke(false.B)
      val mantShift = 23 - dut.outMantBits
      for (t <- 0 until nTrials) {
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
    Stats(label, errs.toVector)
  }

  // ── The actual comparison ─────────────────────────────────────────────────
  // We compare ACCUMULATOR-LEVEL error.  To isolate the accumulator from the
  // output-format quantisation, both alt variants emit FP32 (outMantBits=23);
  // ours_M12 stays at its native FP12 output (12-bit mantissa) because that
  // is the actual deployed config.
  test("101-bit lossless wide acc — error vs FP64 ref, compared to ours_M12 + alt_noEarlyRNE") {
    println("\n=== BFP_PE accumulator validation: q_proj_l0 E5M2² UE6M2 K=2048, "
            + s"$nTrials trials ===")
    println(s"Reference: FP64 lossless sum (per-trial, after block-scale composition)\n")

    val s1 = runFDPU("ours_M12          (accReg=FP12, out=FP12)",
      new FDPUPostScaleReductionTree(
        scfg, vec, K = K, accMantBits = 12,
        treeArch = TreeArch.Generic, cyclesPerBlock = 1,
        noEarlyRNE = false, istest = false))

    val s2 = runFDPU("alt_noEarlyRNE    (accReg=FP32, out=FP32)",
      new FDPUPostScaleReductionTree(
        scfg, vec, K = K,
        treeArch = TreeArch.Generic, cyclesPerBlock = 1,
        noEarlyRNE = true, istest = false))

    // Lossless variant emitted with outMantBits=23 → FP32 output, so the
    // residual error is purely from the wide-fixed accumulator + terminal
    // LZD+RNE, not from output FP encoding.
    val s3 = runLossless("alt_losslessAcc   (accReg=101b, out=FP32)",
      new BFPPELosslessAcc(
        scfg = scfg, vectorSize = vec, K = K,
        accBits = 101, accExpBase = -64,
        outMantBits = 23,                  // ← FP32 output for fair compare
        treeArch = TreeArch.Generic))

    println()
    println("=== Per-trial relative error vs FP64 ref ===")
    s1.print(); s2.print(); s3.print()
    println()

    // Hard assertion: 101-bit lossless (FP32 out) should match alt_noEarlyRNE
    // within 10× — both are nominally accumulating without early RNE; the
    // delta tells us if 101-bit causes dynamic-range saturation.
    assert(s3.mean < s2.mean * 10.0 + 1e-9,
      s"101-bit lossless mean error (${s3.mean}) > 10× alt_noEarlyRNE (${s2.mean}); "
      + "dynamic range too small at accBits=101.")

    // Verdict for the chapter's main claim.
    if (s3.mean < s2.mean * 2.0)
      println("[VERDICT] 101-bit lossless matches FP32-acc baseline — width is "
              + "sufficient for this workload, no dynamic-range loss.")
    else
      println(f"[VERDICT] 101-bit shows ${s3.mean / s2.mean}%.1fx the error of "
              + "alt_noEarlyRNE — consider widening accBits or shifting accExpBase.")
  }
}
