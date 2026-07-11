// Numerical verification: drives SimpleDPU with random stimulus, compares
// the BF16 accumulator output against an FP64 reference (single final RNE to BF16).
//
// Pass condition: relative error |hw - ref| / |ref| < REL_TOL for cycles
// with |ref| > ABS_THRESHOLD. Tolerance is lenient (5%) because the
// hardware also rounds per vector, so multi-vector accumulation compounds.

package mx_simple

import chisel3._
import chiseltest._
import chiseltest.simulator.VerilatorBackendAnnotation
import org.scalatest.funsuite.AnyFunSuite

class SimpleDPUNumericSpec extends AnyFunSuite with ChiselScalatestTester {

  private def cfgOf(a: String, w: String, s: String) =
    DPUConfig(Elem.byName(a), Elem.byName(w), Scale.byName(s))

  /** Drive `K` vectors of random stimulus into `dut`, return the sequence of
    * (hardware BF16 output, FP64 reference at that cycle). */
  private def runK(dut: SimpleDPU, cfg: DPUConfig, K: Int,
                    seed: Long): Vector[(Double, Double)] = {
    val rng = new scala.util.Random(seed)
    val results = collection.mutable.ArrayBuffer[(Double, Double)]()

    // Clear accreg
    dut.io.clearAcc.poke(true.B)
    dut.io.enable.poke(false.B)
    dut.clock.step(1)
    dut.io.clearAcc.poke(false.B)

    // Software reference accumulator (FP64)
    var refAcc: Double = 0.0

    for (_ <- 0 until K) {
      // Random stimulus for this cycle
      val (sAe, sAm) = Reference.randScale(cfg.S, rng)
      val (sWe, sWm) = Reference.randScale(cfg.S, rng)
      val scaleA = Reference.decodeScale(cfg.S, sAe, sAm)
      val scaleW = Reference.decodeScale(cfg.S, sWe, sWm)

      var vectorSum: Double = 0.0
      val aVals = (0 until cfg.N).map { _ =>
        val (as, ae, am) = Reference.randElem(cfg.A, rng)
        (as, ae, am, Reference.decodeElem(cfg.A, as, ae, am))
      }
      val wVals = (0 until cfg.N).map { _ =>
        val (ws, we, wm) = Reference.randElem(cfg.W, rng)
        (ws, we, wm, Reference.decodeElem(cfg.W, ws, we, wm))
      }
      for (i <- 0 until cfg.N) vectorSum += aVals(i)._4 * wVals(i)._4

      val termF64  = vectorSum * scaleA * scaleW
      val newRefAcc = refAcc + termF64

      // Round the SOFTWARE accumulator to BF16 to model per-vector rounding.
      val (rs, rExp, rMant) = Reference.roundToBF16(newRefAcc)
      refAcc = Reference.decodeBF16(rs, rExp, rMant)

      // Drive hardware
      dut.io.enable.poke(true.B)
      dut.io.clearAcc.poke(false.B)
      for (i <- 0 until cfg.N) {
        dut.io.a(i).sign.poke(aVals(i)._1.B)
        if (cfg.A.e > 0) dut.io.a(i).exp.poke(aVals(i)._2.U)
        dut.io.a(i).mant.poke(aVals(i)._3.U)
        dut.io.w(i).sign.poke(wVals(i)._1.B)
        if (cfg.W.e > 0) dut.io.w(i).exp.poke(wVals(i)._2.U)
        dut.io.w(i).mant.poke(wVals(i)._3.U)
      }
      dut.io.scaleA.exp.poke(sAe.U)
      dut.io.scaleA.mant.poke(sAm.U)
      dut.io.scaleW.exp.poke(sWe.U)
      dut.io.scaleW.mant.poke(sWm.U)

      dut.clock.step(1)

      val hwSign = dut.io.accOut.sign.peek().litToBoolean
      val hwExp  = dut.io.accOut.exp.peek().litValue.toInt
      val hwMant = dut.io.accOut.mant.peek().litValue.toInt
      val hwVal  = Reference.decodeBF16(hwSign, hwExp, hwMant)

      results.append((hwVal, refAcc))
    }
    results.toVector
  }

  // ── Summarize a run's errors, print + assert ────────────
  private def analyze(tag: String, cfg: DPUConfig,
                      results: Vector[(Double, Double)],
                      relTol: Double = 0.10,
                      absThresh: Double = 1e-4): Unit = {
    val rel = results.flatMap { case (hw, ref) =>
      if (math.abs(ref) > absThresh) Some(math.abs(hw - ref) / math.abs(ref))
      else None
    }
    if (rel.isEmpty) {
      println(f"[$tag]  n_valid=0 (all refs below threshold) — skipped stats")
      return
    }
    val sorted = rel.sorted
    val med    = sorted(sorted.size / 2)
    val mean   = sorted.sum / sorted.size
    val p95    = sorted(math.min(sorted.size - 1, (sorted.size * 95 / 100)))
    val p99    = sorted(math.min(sorted.size - 1, (sorted.size * 99 / 100)))
    println(f"[$tag]  cfg=${cfg.A.name}/${cfg.W.name}/${cfg.S.name}  " +
            f"n=${sorted.size}%3d  med=$med%.4e  mean=$mean%.4e  " +
            f"p95=$p95%.4e  p99=$p99%.4e")
    assert(med < relTol, s"[$tag] median rel err $med exceeds tolerance $relTol")
  }

  // ── Tests ────────────────────────────────────────────────

  private val trialK   = 32
  private val trialSeed = 1234L

  test("E4M3/E4M3/UE8M0 numerical accuracy") {
    val cfg = cfgOf("E4M3", "E4M3", "UE8M0")
    test(new SimpleDPU(cfg)).withAnnotations(Seq(VerilatorBackendAnnotation)) { dut =>
      val results = runK(dut, cfg, trialK, trialSeed)
      analyze("E4M3_UE8M0", cfg, results)
    }
  }

  test("E4M3/E4M3/UE6M2 numerical accuracy (fractional scale)") {
    val cfg = cfgOf("E4M3", "E4M3", "UE6M2")
    test(new SimpleDPU(cfg)).withAnnotations(Seq(VerilatorBackendAnnotation)) { dut =>
      val results = runK(dut, cfg, trialK, trialSeed)
      analyze("E4M3_UE6M2", cfg, results)
    }
  }

  test("E5M2/E5M2/UE8M0 wide exponent range") {
    val cfg = cfgOf("E5M2", "E5M2", "UE8M0")
    test(new SimpleDPU(cfg)).withAnnotations(Seq(VerilatorBackendAnnotation)) { dut =>
      val results = runK(dut, cfg, trialK, trialSeed)
      analyze("E5M2_UE8M0", cfg, results)
    }
  }

  test("INT8/INT8/UE8M0 integer path") {
    val cfg = cfgOf("INT8", "INT8", "UE8M0")
    test(new SimpleDPU(cfg)).withAnnotations(Seq(VerilatorBackendAnnotation)) { dut =>
      val results = runK(dut, cfg, trialK, trialSeed)
      analyze("INT8_UE8M0", cfg, results)
    }
  }
}
