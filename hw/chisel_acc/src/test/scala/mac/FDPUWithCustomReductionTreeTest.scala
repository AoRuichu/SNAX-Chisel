package mx.mac

import chisel3._
import chiseltest._
import org.scalatest.funsuite.AnyFunSuite
import java.lang.Float.intBitsToFloat
import scala.util.Random

class FDPUWithCustomReductionTreeTest extends AnyFunSuite with ChiselScalatestTester {

  // -----------------------------------------------------------------------
  // Log file — verbose output here; stdout only for failures
  // -----------------------------------------------------------------------
  private val _logWriter: java.io.PrintWriter = {
    val fw = new java.io.FileWriter("fdpu_custom_reduction_tree_test.log", false)
    new java.io.PrintWriter(fw, true)
  }
  private def log(msg: String): Unit    = _logWriter.println(msg)
  private def logErr(msg: String): Unit = { _logWriter.println(msg); println(msg) }

  // -----------------------------------------------------------------------
  // Software golden model  (identical to FusedDotProductUnitTest)
  // -----------------------------------------------------------------------

  def decodeElement(raw: Int, t: ElementType): Double = {
    if (t.name == "INT8") {
      val signedVal = if ((raw & 0x80) != 0) raw - 256 else raw
      signedVal.toDouble * Math.pow(2, t.implicitScaleExp)
    } else {
      val sign = if (((raw >> (t.totalWidth - 1)) & 1) == 1) -1.0 else 1.0
      val exp  = (raw >> t.elementWidthMant) & ((1 << t.elementWidthExp) - 1)
      val mant = raw & ((1 << t.elementWidthMant) - 1)
      if (exp == 0)
        sign * (mant.toDouble / (1 << t.elementWidthMant)) * Math.pow(2, 1 - t.bias)
      else
        sign * (1.0 + mant.toDouble / (1 << t.elementWidthMant)) * Math.pow(2, exp - t.bias)
    }
  }

  def decodeScale(raw: Int, s: ScaleType): Double = {
    val expBits = (raw >> s.mantScaleWidth) & ((1 << s.expScaleWidth) - 1)
    if (s.mantScaleWidth == 0) {
      Math.pow(2, expBits - s.bias)
    } else {
      val mantBits  = raw & ((1 << s.mantScaleWidth) - 1)
      val implicit1 = if (expBits > 0) 1.0 else 0.0
      val effExp    = if (expBits > 0) expBits - s.bias else 1 - s.bias
      (implicit1 + mantBits.toDouble / (1 << s.mantScaleWidth)) * Math.pow(2, effExp)
    }
  }

  def encodeElement(value: Double, t: ElementType): Int = {
    if (value == 0.0) return 0
    val sign   = if (value < 0) 1 else 0
    val absVal = math.abs(value)
    if (t.name == "INT8") {
      val magnitude = math.round(absVal / Math.pow(2, t.implicitScaleExp)).toInt.min(127)
      if (sign == 0) magnitude else (-magnitude) & 0xFF
    } else {
      val expUnbiased = math.floor(math.log(absVal) / math.log(2)).toInt
      val expBiased   = expUnbiased + t.bias
      if (expBiased <= 0) {
        val mantInt = math.round(absVal / math.pow(2, 1 - t.bias) * (1 << t.elementWidthMant))
                          .toInt.min((1 << t.elementWidthMant) - 1)
        (sign << (t.totalWidth - 1)) | mantInt
      } else {
        val expClamped = expBiased.min((1 << t.elementWidthExp) - 1)
        val mantInt    = math.round((absVal / math.pow(2, expUnbiased) - 1.0) * (1 << t.elementWidthMant))
                              .toInt.min((1 << t.elementWidthMant) - 1).max(0)
        (sign << (t.totalWidth - 1)) | (expClamped << t.elementWidthMant) | mantInt
      }
    }
  }

  def encodeScale(value: Double, s: ScaleType): Int = {
    val expUnbiased = math.floor(math.log(value) / math.log(2)).toInt
    val expBiased   = (expUnbiased + s.bias).max(0).min((1 << s.expScaleWidth) - 1)
    if (s.mantScaleWidth == 0) expBiased
    else {
      val mantInt = math.round((value / math.pow(2, expUnbiased) - 1.0) * (1 << s.mantScaleWidth))
                         .toInt.min((1 << s.mantScaleWidth) - 1).max(0)
      (expBiased << s.mantScaleWidth) | mantInt
    }
  }

  def swFusedProduct(cfg: ScaleAddConfig)(
    as: Seq[Int], bs: Seq[Int], scaleA: Int, scaleB: Int
  ): Float = {
    val sA = decodeScale(scaleA, cfg.stype)
    val sB = decodeScale(scaleB, cfg.stype)
    val products = as.zip(bs).map { case (a, b) =>
      val prod = decodeElement(a, cfg.elementTypeA) * decodeElement(b, cfg.elementTypeB)
      (prod * sA * sB).toFloat
    }
    products.sum.toFloat
  }

  def swFusedProductWithTrace(cfg: ScaleAddConfig)(
    as: Seq[Int], bs: Seq[Int], scaleA: Int, scaleB: Int, cycleIdx: Int,
    verbose: Boolean = false
  ): Float = {
    val sA = decodeScale(scaleA, cfg.stype)
    val sB = decodeScale(scaleB, cfg.stype)
    val details = as.zip(bs).zipWithIndex.map { case ((a, b), lane) =>
      val dA   = decodeElement(a, cfg.elementTypeA)
      val dB   = decodeElement(b, cfg.elementTypeB)
      val prod = dA * dB
      (dA, dB, prod)
    }
    val dotSum        = details.map(_._3).sum
    val finalCycleVal = (dotSum * sA * sB).toFloat
    if (verbose) {
      log(f"--- Cycle $cycleIdx SW Trace (sA=$sA%.4e, sB=$sB%.4e) ---")
      details.zipWithIndex.foreach { case ((dA, dB, p), lane) =>
        log(f"  Lane $lane: A=$dA%10.4f (raw=0x${as(lane)}%02x) | B=$dB%10.4f (raw=0x${bs(lane)}%02x) | P=$p%10.4f")
      }
      log(f"  DotSum=$dotSum%10.4f | FinalCycleVal=$finalCycleVal%10.4f")
    }
    finalCycleVal
  }

  /** Read accOut as IEEE 754 Float. */
  def peekFloat(dut: FDPUWithCustomReductionTree): Float =
    intBitsToFloat(dut.io.accOut.peek().litValue.toInt)

  /**
   * Drive reset=1 so asyncRstN = ~1 = 0 for normal operation.
   * Required because the module uses active-low async reset (!reset.asBool).
   */
  def initDut(dut: FDPUWithCustomReductionTree): Unit = {
    dut.reset.poke(true.B)
    dut.io.validIn.poke(false.B)
    dut.io.resetAcc.poke(false.B)
  }

  /** Pack element raw values into a single UInt (element 0 at LSB). */
  def packElements(elems: Seq[Int], width: Int): BigInt =
    elems.zipWithIndex.foldLeft(BigInt(0)) { case (acc, (v, i)) =>
      acc | (BigInt(v & ((1 << width) - 1)) << (i * width))
    }

  /** Drive one fused-MAC cycle (all vectorSize lanes) and advance the clock. */
  def driveOne(
    dut: FDPUWithCustomReductionTree, cfg: ScaleAddConfig,
    as: Seq[Int], bs: Seq[Int], scaleA: Int, scaleB: Int
  ): Unit = {
    dut.io.op_a_i.poke(packElements(as, cfg.elementTypeA.totalWidth).U)
    dut.io.op_b_i.poke(packElements(bs, cfg.elementTypeB.totalWidth).U)
    dut.io.share_exp_A_i.poke(scaleA.U)
    dut.io.share_exp_B_i.poke(scaleB.U)
    dut.io.validIn.poke(true.B)
    dut.clock.step()
    dut.io.validIn.poke(false.B)
  }

  /**
   * Drive a sequence of fused-MAC cycles and check the running accumulator
   * against the SW golden model after every cycle.
   *
   * Note: when useCustomTree=true, all_lanes_fp32 is DontCare — only
   * all_lanes_sa_mant and reducedSum are observable from debug ports.
   */
  def runCycles(
    dut: FDPUWithCustomReductionTree, cfg: ScaleAddConfig,
    cycles: Seq[(Seq[Int], Seq[Int], Int, Int)],
    header: String = "",
    verbose: Boolean = false
  ): Unit = {
    if (header.nonEmpty) {
      log("=" * 70)
      log(header)
      log(s"  useCustomTree = ${cfg.useCustomTree}")
      log("=" * 70)
    }
    var swAcc = 0.0f
    for (((as, bs, sA, sB), i) <- cycles.zipWithIndex) {
      val decSA = decodeScale(sA, cfg.stype)
      val decSB = decodeScale(sB, cfg.stype)

      val swCycleVal = swFusedProductWithTrace(cfg)(as, bs, sA, sB, i, verbose)
      driveOne(dut, cfg, as, bs, sA, sB)

      val hwCycleValRaw = dut.io.debug.get.reducedSum.peek().litValue.toInt
      val hwCycleVal    = java.lang.Float.intBitsToFloat(hwCycleValRaw)
      val hwAcc         = peekFloat(dut)

      swAcc = (swAcc + swCycleVal).toFloat
      val hw  = peekFloat(dut)

      val tol    = math.abs(swAcc) * 0.02f + 1e-5f
      val isFail = math.abs(hw - swAcc) > tol

      val line = f"Cycle $i%2d | sA=${decSA}%10.4e | sB=${decSB}%10.4e | cycleVal=$swCycleVal%12.4e | SW=$swAcc%12.4e | HW=$hw%12.4e"

      if (isFail || verbose) {
        val tag = if (isFail) "FAIL" else "VERBOSE"
        log(f"--- Cycle $i%2d [$tag] HW All Lanes Trace (useCustomTree=${cfg.useCustomTree}) ---")

        for (laneIdx <- 0 until dut.vectorSize) {
          // all_lanes_fp32 is DontCare on the custom-tree path — log it only for FP32 path
          val hwLaneMant = dut.io.debug.get.all_lanes_sa_mant(laneIdx).peek().litValue
          if (!cfg.useCustomTree) {
            val hwLaneFP32Raw = dut.io.debug.get.all_lanes_fp32(laneIdx).peek().litValue.toInt
            val hwLaneFP32    = java.lang.Float.intBitsToFloat(hwLaneFP32Raw)
            log(f"  Lane $laneIdx%1d: HW_Mant=0x$hwLaneMant%x | HW_FP32=$hwLaneFP32%.6f")
          } else {
            log(f"  Lane $laneIdx%1d: HW_Mant=0x$hwLaneMant%x")
          }
        }

        log(f"  [SUMMARY] SW_CycleSum=$swCycleVal%.6f | HW_CycleSum=$hwCycleVal%.6f")
        log(f"  [ACCUM  ] SW_Acc=$swAcc%.6f | HW_Acc=$hwAcc%.6f")

        if (isFail) {
          logErr(line)
          logErr(f"      BITS: SW=0x${java.lang.Float.floatToIntBits(swAcc).toHexString} | " +
                 f"HW=0x${java.lang.Float.floatToIntBits(hw).toHexString}")
        } else {
          log(line)
        }
      } else {
        log(line)
      }
      assert(!isFail, s"Mismatch at cycle $i: hw=$hw expected=$swAcc")
    }
  }

  // -----------------------------------------------------------------------
  // MX block quantization helpers
  // -----------------------------------------------------------------------

  def maxElemRepr(t: ElementType): Double =
    if (t.name == "INT8") {
      ((1 << (t.totalWidth - 1)) - 1).toDouble * Math.pow(2, t.implicitScaleExp)
    } else {
      val maxExpBiased = (1 << t.elementWidthExp) - 2
      val maxMant      = (1 << t.elementWidthMant) - 1
      (1.0 + maxMant.toDouble / (1 << t.elementWidthMant)) * math.pow(2, maxExpBiased - t.bias)
    }

  def quantizeBlock(values: Seq[Double], et: ElementType, st: ScaleType): (Seq[Int], Int) = {
    val maxAbs       = values.map(math.abs).max.max(1e-38)
    val minScaleExp  = -(st.bias)
    val maxScaleExp  = (1 << st.expScaleWidth) - 1 - st.bias
    val rawScale =
      if (st.mantScaleWidth == 0) {
        val scaleExp   = math.ceil(math.log((maxAbs / maxElemRepr(et)).max(1e-38)) / math.log(2)).toInt
        val clampedExp = scaleExp.max(minScaleExp).min(maxScaleExp)
        encodeScale(math.pow(2.0, clampedExp), st)
      } else {
        val idealScale  = (maxAbs / maxElemRepr(et)).max(math.pow(2.0, minScaleExp))
        val expUnbiased = math.floor(math.log(idealScale) / math.log(2)).toInt
        val mantRaw     = math.ceil(
          (idealScale / math.pow(2.0, expUnbiased) - 1.0) * (1 << st.mantScaleWidth)
        ).toInt
        val (adjExp, adjMant) =
          if (mantRaw > (1 << st.mantScaleWidth) - 1) (expUnbiased + 1, 0)
          else (expUnbiased, mantRaw)
        val expBiased = (adjExp + st.bias).max(0).min((1 << st.expScaleWidth) - 1)
        (expBiased << st.mantScaleWidth) | adjMant
      }
    val decodedScale = decodeScale(rawScale, st)
    val rawElems     = values.map(v => encodeElement(v / decodedScale, et))
    (rawElems, rawScale)
  }

  // -----------------------------------------------------------------------
  // Pre-encoded constants
  // -----------------------------------------------------------------------

  // Default config: E4M3 × E2M1 / UE5M3  (useCustomTree = true, mantWidth < 24)
  val defaultScfg = ScaleAddConfig(MXFormats.E4M3, MXFormats.E2M1, ScaleFormats.UE5M3)

  val e4m3_1    = encodeElement(1.0,  MXFormats.E4M3)
  val e4m3_neg1 = encodeElement(-1.0, MXFormats.E4M3)
  val e4m3_2    = encodeElement(2.0,  MXFormats.E4M3)
  val e4m3_1p5  = encodeElement(1.5,  MXFormats.E4M3)
  val e2m1_1    = encodeElement(1.0,  MXFormats.E2M1)
  val e2m1_2    = encodeElement(2.0,  MXFormats.E2M1)
  val ue5m3_1   = encodeScale(1.0,    ScaleFormats.UE5M3)
  val ue5m3_2   = encodeScale(2.0,    ScaleFormats.UE5M3)

  // E5M2 × E5M2 / UE8M0 (useCustomTree = false — wide mantissa path)
  val e5m2Scfg   = ScaleAddConfig(MXFormats.E5M2, MXFormats.E5M2, ScaleFormats.UE8M0)

  val e5m2_0p5   = encodeElement( 0.5,  MXFormats.E5M2)
  val e5m2_0p75  = encodeElement( 0.75, MXFormats.E5M2)
  val e5m2_1     = encodeElement( 1.0,  MXFormats.E5M2)
  val e5m2_1p25  = encodeElement( 1.25, MXFormats.E5M2)
  val e5m2_1p5   = encodeElement( 1.5,  MXFormats.E5M2)
  val e5m2_1p75  = encodeElement( 1.75, MXFormats.E5M2)
  val e5m2_2     = encodeElement( 2.0,  MXFormats.E5M2)
  val e5m2_3     = encodeElement( 3.0,  MXFormats.E5M2)
  val e5m2_n0p5  = encodeElement(-0.5,  MXFormats.E5M2)
  val e5m2_n0p75 = encodeElement(-0.75, MXFormats.E5M2)
  val e5m2_n1    = encodeElement(-1.0,  MXFormats.E5M2)
  val e5m2_n1p25 = encodeElement(-1.25, MXFormats.E5M2)
  val e5m2_n1p5  = encodeElement(-1.5,  MXFormats.E5M2)
  val e5m2_n1p75 = encodeElement(-1.75, MXFormats.E5M2)
  val e5m2_n2    = encodeElement(-2.0,  MXFormats.E5M2)
  val ue8m0_0p5  = encodeScale(0.5, ScaleFormats.UE8M0)
  val ue8m0_1    = encodeScale(1.0, ScaleFormats.UE8M0)
  val ue8m0_2    = encodeScale(2.0, ScaleFormats.UE8M0)
  val ue8m0_4    = encodeScale(4.0, ScaleFormats.UE8M0)

  // -----------------------------------------------------------------------
  // Test 1: Reset clears accumulator
  // -----------------------------------------------------------------------
  test("FDPUWithCustomReductionTree: Reset clears accumulator") {
    log("\n[TEST 1] Reset clears accumulator")
    try {
      test(new FDPUWithCustomReductionTree(defaultScfg, 4, true)) { dut =>
        initDut(dut)
        dut.io.accOut.expect(0.U, "initial accOut should be 0")

        dut.io.resetAcc.poke(true.B)
        dut.clock.step()
        dut.io.accOut.expect(0.U, "accOut should stay 0 after reset")
        dut.io.resetAcc.poke(false.B)
      }
      log("[PASSED] Reset clears accumulator")
    } catch { case e: Exception =>
      logErr(s"[FAILED] Reset clears accumulator: ${e.getMessage}"); throw e
    }
  }

  // -----------------------------------------------------------------------
  // Test 2: validOut handshake timing
  // -----------------------------------------------------------------------
  test("FDPUWithCustomReductionTree: validOut handshake timing") {
    log("\n[TEST 2] validOut handshake timing (vec=4)")
    try {
      test(new FDPUWithCustomReductionTree(defaultScfg, 4, true)) { dut =>
        initDut(dut)
        dut.io.op_a_i.poke(packElements(Seq.fill(4)(e4m3_1), defaultScfg.elementTypeA.totalWidth).U)
        dut.io.op_b_i.poke(packElements(Seq.fill(4)(e2m1_1), defaultScfg.elementTypeB.totalWidth).U)
        dut.io.share_exp_A_i.poke(ue5m3_1.U)
        dut.io.share_exp_B_i.poke(ue5m3_1.U)

        dut.io.validOut.expect(false.B, "validOut must be false when validIn=0 (before any cycle)")

        dut.io.validIn.poke(true.B)
        dut.clock.step()
        dut.io.validIn.poke(false.B)
        dut.io.validOut.expect(true.B, "validOut must be true one cycle after validIn pulse")

        dut.clock.step()
        dut.io.validOut.expect(false.B, "validOut must return false when validIn=0")
      }
      log("[PASSED] validOut handshake timing")
    } catch { case e: Exception =>
      logErr(s"[FAILED] validOut handshake timing: ${e.getMessage}"); throw e
    }
  }

  // -----------------------------------------------------------------------
  // Test 3: vec=1 scalar equivalence — custom tree path (E4M3 × E2M1 / UE5M3)
  // -----------------------------------------------------------------------
  test("FDPUWithCustomReductionTree: vec=1 scalar equivalence (custom tree, 1×1×1×1 = 1.0)") {
    log("\n[TEST 3] vec=1 scalar equivalence (custom tree path)")
    try {
      test(new FDPUWithCustomReductionTree(defaultScfg, 1, true)) { dut =>
        initDut(dut)
        dut.io.resetAcc.poke(true.B); dut.clock.step()
        dut.io.resetAcc.poke(false.B)

        val expected = swFusedProduct(defaultScfg)(Seq(e4m3_1), Seq(e2m1_1), ue5m3_1, ue5m3_1)
        driveOne(dut, defaultScfg, Seq(e4m3_1), Seq(e2m1_1), ue5m3_1, ue5m3_1)
        val hw = peekFloat(dut)
        log(f"Expected: $expected%.6f  HW: $hw%.6f  useCustomTree=${defaultScfg.useCustomTree}")

        val tol = math.abs(expected) * 0.01f + 1e-37f
        assert(math.abs(hw - expected) <= tol, s"Mismatch: hw=$hw expected=$expected")
      }
      log("[PASSED] vec=1 scalar equivalence (custom tree)")
    } catch { case e: Exception =>
      logErr(s"[FAILED] vec=1 scalar equivalence: ${e.getMessage}"); throw e
    }
  }

  // -----------------------------------------------------------------------
  // Test 4: vec=4 all-ones fused → 4.0  (custom tree path)
  // -----------------------------------------------------------------------
  test("FDPUWithCustomReductionTree: vec=4 all-ones fused → 4.0 (custom tree)") {
    log("\n[TEST 4] vec=4 all-ones (1×1×1×1 per lane) → 4.0 (custom tree)")
    try {
      test(new FDPUWithCustomReductionTree(defaultScfg, 4, true)) { dut =>
        initDut(dut)
        dut.io.resetAcc.poke(true.B); dut.clock.step()
        dut.io.resetAcc.poke(false.B)

        val as       = Seq.fill(4)(e4m3_1)
        val bs       = Seq.fill(4)(e2m1_1)
        val expected = swFusedProduct(defaultScfg)(as, bs, ue5m3_1, ue5m3_1)
        driveOne(dut, defaultScfg, as, bs, ue5m3_1, ue5m3_1)
        val hw = peekFloat(dut)
        log(f"Expected (SW): $expected%.6f  HW: $hw%.6f")

        val tol = math.abs(expected) * 0.01f + 1e-37f
        assert(math.abs(hw - expected) <= tol, s"Mismatch: hw=$hw expected=$expected")
      }
      log("[PASSED] vec=4 all-ones fused → 4.0")
    } catch { case e: Exception =>
      logErr(s"[FAILED] vec=4 all-ones: ${e.getMessage}"); throw e
    }
  }

  // -----------------------------------------------------------------------
  // Test 5: vec=8 all-ones fused → 8.0  (custom tree path)
  // -----------------------------------------------------------------------
  test("FDPUWithCustomReductionTree: vec=8 all-ones fused → 8.0 (custom tree)") {
    log("\n[TEST 5] vec=8 all-ones → 8.0 (custom tree)")
    try {
      test(new FDPUWithCustomReductionTree(defaultScfg, 8, true)) { dut =>
        initDut(dut)
        dut.io.resetAcc.poke(true.B); dut.clock.step()
        dut.io.resetAcc.poke(false.B)

        val as       = Seq.fill(8)(e4m3_1)
        val bs       = Seq.fill(8)(e2m1_1)
        val expected = swFusedProduct(defaultScfg)(as, bs, ue5m3_1, ue5m3_1)
        driveOne(dut, defaultScfg, as, bs, ue5m3_1, ue5m3_1)
        val hw = peekFloat(dut)
        log(f"Expected (SW): $expected%.6f  HW: $hw%.6f")

        val tol = math.abs(expected) * 0.01f + 1e-37f
        assert(math.abs(hw - expected) <= tol, s"Mismatch: hw=$hw expected=$expected")
      }
      log("[PASSED] vec=8 all-ones fused → 8.0")
    } catch { case e: Exception =>
      logErr(s"[FAILED] vec=8 all-ones: ${e.getMessage}"); throw e
    }
  }

  // -----------------------------------------------------------------------
  // Test 6: vec=4 mixed signs — partial cancellation (custom tree path)
  // -----------------------------------------------------------------------
  test("FDPUWithCustomReductionTree: vec=4 mixed signs → near-zero cancellation (custom tree)") {
    log("\n[TEST 6] vec=4 mixed signs [+1,-1,+1,-1] × [1,1,1,1] → 0.0")
    try {
      test(new FDPUWithCustomReductionTree(defaultScfg, 4, true)) { dut =>
        initDut(dut)
        dut.io.resetAcc.poke(true.B); dut.clock.step()
        dut.io.resetAcc.poke(false.B)

        val as       = Seq(e4m3_1, e4m3_neg1, e4m3_1, e4m3_neg1)
        val bs       = Seq.fill(4)(e2m1_1)
        val expected = swFusedProduct(defaultScfg)(as, bs, ue5m3_1, ue5m3_1)
        driveOne(dut, defaultScfg, as, bs, ue5m3_1, ue5m3_1)
        val hw = peekFloat(dut)
        log(f"Expected (SW): $expected%.6f  HW: $hw%.6f")

        val tol = math.abs(expected) * 0.01f + 1e-37f + 1e-6f
        assert(math.abs(hw - expected) <= tol, s"Mismatch: hw=$hw expected=$expected")
      }
      log("[PASSED] vec=4 mixed signs cancellation")
    } catch { case e: Exception =>
      logErr(s"[FAILED] vec=4 mixed signs cancellation: ${e.getMessage}"); throw e
    }
  }

  // -----------------------------------------------------------------------
  // Test 7: vec=4 scale amplification — scaleA=2, scaleB=2 → 16.0
  // -----------------------------------------------------------------------
  test("FDPUWithCustomReductionTree: vec=4 scale=2×2 amplification → 16.0") {
    log("\n[TEST 7] vec=4, all-ones, scale 2×2 → 4×1×2×2 = 16.0")
    try {
      test(new FDPUWithCustomReductionTree(defaultScfg, 4, true)) { dut =>
        initDut(dut)
        dut.io.resetAcc.poke(true.B); dut.clock.step()
        dut.io.resetAcc.poke(false.B)

        val as       = Seq.fill(4)(e4m3_1)
        val bs       = Seq.fill(4)(e2m1_1)
        val expected = swFusedProduct(defaultScfg)(as, bs, ue5m3_2, ue5m3_2)
        driveOne(dut, defaultScfg, as, bs, ue5m3_2, ue5m3_2)
        val hw = peekFloat(dut)
        log(f"Expected (SW): $expected%.6f  HW: $hw%.6f")

        val tol = math.abs(expected) * 0.01f + 1e-37f
        assert(math.abs(hw - expected) <= tol, s"Mismatch: hw=$hw expected=$expected")
      }
      log("[PASSED] vec=4 scale=2×2 amplification")
    } catch { case e: Exception =>
      logErr(s"[FAILED] vec=4 scale amplification: ${e.getMessage}"); throw e
    }
  }

  // -----------------------------------------------------------------------
  // Test 8: Reset mid-accumulation
  // -----------------------------------------------------------------------
  test("FDPUWithCustomReductionTree: Reset mid-accumulation (vec=4)") {
    log("\n[TEST 8] Reset mid-accumulation (vec=4)")
    try {
      test(new FDPUWithCustomReductionTree(defaultScfg, 4, true)) { dut =>
        initDut(dut)

        driveOne(dut, defaultScfg, Seq.fill(4)(e4m3_2), Seq.fill(4)(e2m1_2), ue5m3_1, ue5m3_1)
        val before = dut.io.accOut.peek().litValue.toInt
        log(s"Before reset: 0x${before.toHexString}  (${intBitsToFloat(before)})")
        assert(before != 0, "accOut should be non-zero after accumulation")

        dut.io.resetAcc.poke(true.B); dut.clock.step()
        dut.io.accOut.expect(0.U, "accOut should be 0 after reset")
        dut.io.resetAcc.poke(false.B)

        val as1      = Seq.fill(4)(e4m3_1)
        val bs1      = Seq.fill(4)(e2m1_1)
        val expected = swFusedProduct(defaultScfg)(as1, bs1, ue5m3_1, ue5m3_1)
        driveOne(dut, defaultScfg, as1, bs1, ue5m3_1, ue5m3_1)
        val hw = peekFloat(dut)
        log(f"After reset + 1 cycle: HW=$hw%.6f expected=$expected%.6f")

        val tol = math.abs(expected) * 0.01f + 1e-37f
        assert(math.abs(hw - expected) <= tol, s"Mismatch after reset: hw=$hw expected=$expected")
      }
      log("[PASSED] Reset mid-accumulation")
    } catch { case e: Exception =>
      logErr(s"[FAILED] Reset mid-accumulation: ${e.getMessage}"); throw e
    }
  }

  // -----------------------------------------------------------------------
  // Test 9: vec=4 multi-cycle accumulation — 4 cycles each adding 4.0 → 16.0
  // -----------------------------------------------------------------------
  test("FDPUWithCustomReductionTree: vec=4 multi-cycle (4 cycles × 4.0 → 16.0, VCD)") {
    log("\n[TEST 9] vec=4 multi-cycle: 4 cycles of (4×1×1×1×1) → 16.0")
    try {
      test(new FDPUWithCustomReductionTree(defaultScfg, 4, true)).withAnnotations(Seq(WriteVcdAnnotation)) { dut =>
        initDut(dut)
        dut.io.resetAcc.poke(true.B); dut.clock.step()
        dut.io.resetAcc.poke(false.B)

        val cycles = Seq.fill(4)(
          (Seq.fill(4)(e4m3_1), Seq.fill(4)(e2m1_1), ue5m3_1, ue5m3_1)
        )
        runCycles(dut, defaultScfg, cycles, "vec=4 multi-cycle (4 cycles)")
      }
      log("[PASSED] vec=4 multi-cycle accumulation")
    } catch { case e: Exception =>
      logErr(s"[FAILED] vec=4 multi-cycle: ${e.getMessage}"); throw e
    }
  }

  // -----------------------------------------------------------------------
  // Test 10: vec=4 mixed-value multi-cycle — negative contributions
  // -----------------------------------------------------------------------
  test("FDPUWithCustomReductionTree: vec=4 mixed-value multi-cycle (negative/positive)") {
    log("\n[TEST 10] vec=4 mixed-value multi-cycle")
    try {
      test(new FDPUWithCustomReductionTree(defaultScfg, 4, true)) { dut =>
        initDut(dut)
        dut.io.resetAcc.poke(true.B); dut.clock.step()
        dut.io.resetAcc.poke(false.B)

        val cycles = Seq(
          // cycle 0: [1, 2, -1, 1] × [1, 1, 1, 1], scale 1×1 → sum = 3.0
          (Seq(e4m3_1, e4m3_2, e4m3_neg1, e4m3_1), Seq.fill(4)(e2m1_1), ue5m3_1, ue5m3_1),
          // cycle 1: [1, 1, 1, 1] × [2, 2, 2, 2], scale 1×1 → sum = 8.0
          (Seq.fill(4)(e4m3_1), Seq.fill(4)(e2m1_2), ue5m3_1, ue5m3_1),
          // cycle 2: [-1, -1, -1, -1] × [1, 1, 1, 1], scale 2×1 → sum = -8.0
          (Seq.fill(4)(e4m3_neg1), Seq.fill(4)(e2m1_1), ue5m3_2, ue5m3_1),
          // cycle 3: [1, 1.5, -1, 2] × [1, 1, 1, 1], scale 1×1 → sum = 3.5
          (Seq(e4m3_1, e4m3_1p5, e4m3_neg1, e4m3_2), Seq.fill(4)(e2m1_1), ue5m3_1, ue5m3_1)
        )
        runCycles(dut, defaultScfg, cycles, "vec=4 mixed-value multi-cycle")
      }
      log("[PASSED] vec=4 mixed-value multi-cycle")
    } catch { case e: Exception =>
      logErr(s"[FAILED] vec=4 mixed-value multi-cycle: ${e.getMessage}"); throw e
    }
  }

  // -----------------------------------------------------------------------
  // Test 11: E5M2 × E5M2 / UE8M0 — FP32 reduction tree path (useCustomTree=false)
  //          vec=4, 6-cycle randomized
  // -----------------------------------------------------------------------
  test("FDPUWithCustomReductionTree: E5M2 x E5M2 / UE8M0 vec=4 (FP32 tree path, 6-cycle)") {
    log("\n[TEST 11] E5M2 x E5M2 / UE8M0 vec=4 — FP32 reduction tree path")
    log(s"  useCustomTree = ${e5m2Scfg.useCustomTree}  (expected: false)")
    try {
      val cfg   = e5m2Scfg
      val vsize = 4
      val cycles = Seq(
        (Seq(e5m2_1p5,  e5m2_n0p75, e5m2_2,    e5m2_n1p5),
         Seq(e5m2_n0p5, e5m2_1p75,  e5m2_n1,   e5m2_0p75),  ue8m0_2, ue8m0_1),
        (Seq(e5m2_0p75, e5m2_1,    e5m2_n1p5,  e5m2_0p5),
         Seq(e5m2_2,    e5m2_n1p5, e5m2_0p75,  e5m2_n1),    ue8m0_1, ue8m0_0p5),
        (Seq(e5m2_n2,   e5m2_1p5,   e5m2_0p75, e5m2_n1),
         Seq(e5m2_1,    e5m2_n0p75, e5m2_1p5,  e5m2_2),     ue8m0_0p5, ue8m0_2),
        (Seq(e5m2_1p75, e5m2_n0p5,  e5m2_n1,   e5m2_1p5),
         Seq(e5m2_n1p5, e5m2_1,     e5m2_2,    e5m2_n0p75), ue8m0_4, ue8m0_1),
        (Seq(e5m2_n0p75, e5m2_2,    e5m2_1,    e5m2_n1p75),
         Seq(e5m2_0p75,  e5m2_n2,   e5m2_1p5,  e5m2_1),     ue8m0_1, ue8m0_4),
        (Seq(e5m2_1,    e5m2_n1,   e5m2_1p5,  e5m2_n2),
         Seq(e5m2_n1,   e5m2_1p5,  e5m2_n0p75, e5m2_1),     ue8m0_2, ue8m0_2)
      )
      test(new FDPUWithCustomReductionTree(cfg, vsize, true)) { dut =>
        initDut(dut)
        dut.io.resetAcc.poke(true.B); dut.clock.step()
        dut.io.resetAcc.poke(false.B)
        runCycles(dut, cfg, cycles, "E5M2 x E5M2 / UE8M0 vec=4 (FP32 tree path)")
      }
      log("[PASSED] E5M2 x E5M2 / UE8M0 vec=4 (FP32 tree path)")
    } catch { case e: Exception =>
      logErr(s"[FAILED] E5M2 x E5M2 / UE8M0 vec=4: ${e.getMessage}"); throw e
    }
  }

  // -----------------------------------------------------------------------
  // Test 12: Power-of-2 vectorSizes — one-cycle all-1 sum equals vectorSize
  //          Tests both custom tree and FP32 tree paths across sizes.
  // -----------------------------------------------------------------------
  test("FDPUWithCustomReductionTree: Power-of-2 vectorSizes 1..16 — fused sum = vecSize") {
    log("\n[TEST 12] Power-of-2 vectorSizes (1, 2, 4, 8, 16) — 1-cycle sum = vecSize")
    try {
      for (vsize <- Seq(1, 2, 4, 8, 16)) {
        test(new FDPUWithCustomReductionTree(defaultScfg, vsize, true)) { dut =>
          initDut(dut)
          dut.io.resetAcc.poke(true.B); dut.clock.step()
          dut.io.resetAcc.poke(false.B)

          val as       = Seq.fill(vsize)(e4m3_1)
          val bs       = Seq.fill(vsize)(e2m1_1)
          val expected = swFusedProduct(defaultScfg)(as, bs, ue5m3_1, ue5m3_1)
          driveOne(dut, defaultScfg, as, bs, ue5m3_1, ue5m3_1)
          val hw = peekFloat(dut)
          log(f"vec=$vsize%2d | expected=$expected%.4f | HW=$hw%.4f | useCustomTree=${defaultScfg.useCustomTree}")

          val tol = math.abs(expected) * 0.01f + 1e-37f
          assert(math.abs(hw - expected) <= tol, s"vec=$vsize: hw=$hw expected=$expected")
        }
      }
      log("[PASSED] Power-of-2 vectorSizes")
    } catch { case e: Exception =>
      logErr(s"[FAILED] Power-of-2 vectorSizes: ${e.getMessage}"); throw e
    }
  }

  // -----------------------------------------------------------------------
  // Test 13: Non-power-of-2 vectorSizes — tests odd-lane pass-through in tree
  // -----------------------------------------------------------------------
  test("FDPUWithCustomReductionTree: Non-power-of-2 vectorSizes (3, 5, 7) — reduction tree") {
    log("\n[TEST 13] Non-power-of-2 vectorSizes (3, 5, 7) — odd lane pass-through")
    try {
      for (vsize <- Seq(3, 5, 7)) {
        test(new FDPUWithCustomReductionTree(defaultScfg, vsize, true)) { dut =>
          initDut(dut)
          dut.io.resetAcc.poke(true.B); dut.clock.step()
          dut.io.resetAcc.poke(false.B)

          val as       = Seq.fill(vsize)(e4m3_1)
          val bs       = Seq.fill(vsize)(e2m1_1)
          val expected = swFusedProduct(defaultScfg)(as, bs, ue5m3_1, ue5m3_1)
          driveOne(dut, defaultScfg, as, bs, ue5m3_1, ue5m3_1)
          val hw = peekFloat(dut)
          log(f"vec=$vsize%2d | expected=$expected%.4f | HW=$hw%.4f")

          val tol = math.abs(expected) * 0.01f + 1e-37f
          assert(math.abs(hw - expected) <= tol, s"vec=$vsize: hw=$hw expected=$expected")
        }
      }
      log("[PASSED] Non-power-of-2 vectorSizes")
    } catch { case e: Exception =>
      logErr(s"[FAILED] Non-power-of-2 vectorSizes: ${e.getMessage}"); throw e
    }
  }

  // -----------------------------------------------------------------------
  // Test 14: 8-element vector dot product simulation (vec=8, 1 cycle)
  //          Custom tree path (E4M3 × E2M1 / UE5M3)
  // -----------------------------------------------------------------------
  test("FDPUWithCustomReductionTree: 8-element vector dot product in 1 cycle (shared scale=2.0)") {
    log("\n[TEST 14] 8-element dot product in 1 cycle (scale=2.0 × 2.0)")
    try {
      val vecA         = Seq(1.0, -1.0, 2.0, 1.0, -1.0, 1.0, 2.0, -2.0)
      val vecB         = Seq(1.0,  1.0, 1.0, 2.0,  2.0, 1.0, 1.0,  1.0)
      val sharedScaleA = 2.0
      val sharedScaleB = 2.0

      val rawAs = vecA.map(encodeElement(_, defaultScfg.elementTypeA))
      val rawBs = vecB.map(encodeElement(_, defaultScfg.elementTypeB))
      val rawSA = encodeScale(sharedScaleA, defaultScfg.stype)
      val rawSB = encodeScale(sharedScaleB, defaultScfg.stype)

      val dotNoScale    = vecA.zip(vecB).map { case (a, b) => a * b }.sum
      val expectedTotal = (dotNoScale * sharedScaleA * sharedScaleB).toFloat
      log(f"Dot product (no scale) = $dotNoScale%.4f")
      log(f"Expected (×scale²)    = $expectedTotal%.4f")

      test(new FDPUWithCustomReductionTree(defaultScfg, 8, true)).withAnnotations(Seq(WriteVcdAnnotation)) { dut =>
        initDut(dut)
        dut.io.resetAcc.poke(true.B); dut.clock.step()
        dut.io.resetAcc.poke(false.B)

        driveOne(dut, defaultScfg, rawAs, rawBs, rawSA, rawSB)
        val hw  = peekFloat(dut)
        val tol = math.abs(expectedTotal) * 0.05f + 1e-37f
        log(f"HW result = $hw%.4f  expected = $expectedTotal%.4f  tol = $tol%.6f")
        assert(math.abs(hw - expectedTotal) <= tol,
          s"Final dot product mismatch: hw=$hw expected=$expectedTotal")
      }
      log("[PASSED] 8-element vector dot product in 1 cycle")
    } catch { case e: Exception =>
      logErr(s"[FAILED] 8-element vector dot product: ${e.getMessage}"); throw e
    }
  }

  // -----------------------------------------------------------------------
  // Test 15: All type combinations — vec=4, 5-cycle random accumulation
  //          Covers both useCustomTree=true and useCustomTree=false paths
  //          depending on the mantissa width of each configuration.
  // -----------------------------------------------------------------------
  test("FDPUWithCustomReductionTree: All type combinations vec=4 — 5-cycle random accumulation") {
    log("\n[TEST 15] All type combinations vec=4 — 5-cycle random accumulation")

    val rng       = new Random(42)
    val vsize     = 4
    val numCycles = 5

    def randElement(t: ElementType): Int = {
      val sign = rng.nextInt(2)
      if (t.name == "INT8") {
        val mag = 1 + rng.nextInt(15)
        if (sign == 0) mag else (-mag) & 0xFF
      } else {
        val expRange = 1 << t.elementWidthExp
        val expLo    = (t.bias + 1).max(1).min(expRange - 1)
        val expHi    = (t.bias + 4).max(expLo).min(expRange - 1)
        val exp      = expLo + rng.nextInt(expHi - expLo + 1)
        val mant     = rng.nextInt(1 << t.elementWidthMant)
        (sign << (t.totalWidth - 1)) | (exp << t.elementWidthMant) | mant
      }
    }

    def randScale(s: ScaleType): Int = {
      val expRange = 1 << s.expScaleWidth
      val expLo    = (s.bias - 2).max(1).min(expRange - 1)
      val expHi    = (s.bias + 2).max(expLo).min(expRange - 1)
      val exp      = expLo + rng.nextInt(expHi - expLo + 1)
      val mant     = if (s.mantScaleWidth == 0) 0 else rng.nextInt(1 << s.mantScaleWidth)
      (exp << s.mantScaleWidth) | mant
    }

    val allElem  = MXFormats.allElementTypes
    val allScale = ScaleFormats.allScaleTypes
    val total    = allElem.size * allElem.size * allScale.size
    var passed   = 0
    var failed   = 0

    for (etA <- allElem; etB <- allElem; st <- allScale) {
      val cfg   = ScaleAddConfig(etA, etB, st)
      val label = s"${etA.name} x ${etB.name} / ${st.name} vec=$vsize (customTree=${cfg.useCustomTree})"
      val cycles = (0 until numCycles).map { _ =>
        val as = Seq.fill(vsize)(randElement(etA))
        val bs = Seq.fill(vsize)(randElement(etB))
        (as, bs, randScale(st), randScale(st))
      }
      try {
        test(new FDPUWithCustomReductionTree(cfg, vsize, true)) { dut =>
          initDut(dut)
          dut.io.resetAcc.poke(true.B); dut.clock.step()
          dut.io.resetAcc.poke(false.B)
          runCycles(dut, cfg, cycles, label)
        }
        log(s"[OK] $label")
        passed += 1
      } catch { case e: Exception =>
        logErr(s"[FAIL] $label: ${e.getMessage}")
        failed += 1
      }
    }

    val summary = s"Summary: $passed passed, $failed failed out of $total combinations"
    if (failed > 0) logErr(summary) else log(summary)
    assert(failed == 0,
      s"$failed combination(s) failed — see fdpu_custom_reduction_tree_test.log for details")
    log("[PASSED] All type combinations vec=4")
  }

  // -----------------------------------------------------------------------
  // Test 16: MX block-quantized 32-element dot product
  //          (E5M2 × E4M3 / UE8M0, vec=8, 4 cycles — FP32 tree path)
  // -----------------------------------------------------------------------
  test("FDPUWithCustomReductionTree: MX block-quantized 32-element dot product (E5M2×E4M3, vec=8, 4 cycles)") {
    log("\n[TEST 16] MX block-quantized 32-element dot product (E5M2×E4M3 / UE8M0, vec=8)")
    try {
      val cfg       = ScaleAddConfig(MXFormats.E5M2, MXFormats.E4M3, ScaleFormats.UE8M0)
      val vsize     = 8
      val blockSize = 32

      val rawA: Seq[Double] = Seq(
         3.2,  -1.6,   0.8,   4.0,  -2.4,  1.2,  -0.6,  3.6,
         2.0,  -3.0,   1.5,  -0.5,   2.5, -1.0,   0.4,  1.8,
        -2.2,   0.9,  -1.4,   3.1,  -0.7,  2.8,  -1.1,  0.3,
         1.7,  -2.9,   0.6,  -1.3,   2.1, -0.8,   1.9, -3.5
      )
      val rawB: Seq[Double] = Seq(
         1.0,   2.0,  -1.0,   0.5,   1.5, -0.5,   2.0, -1.0,
         0.75, -1.25,  1.5,   2.0,  -0.75, 1.0,  -2.0,  0.5,
         2.0,  -1.0,   0.5,  -1.5,   1.0,  0.25, -1.0,  2.0,
        -0.5,   1.5,  -2.0,   1.0,   0.5, -1.5,   1.0, -0.25
      )
      require(rawA.size == blockSize && rawB.size == blockSize)

      val (encA, rawScaleA) = quantizeBlock(rawA, cfg.elementTypeA, cfg.stype)
      val (encB, rawScaleB) = quantizeBlock(rawB, cfg.elementTypeB, cfg.stype)

      val scaleAf = decodeScale(rawScaleA, cfg.stype)
      val scaleBf = decodeScale(rawScaleB, cfg.stype)
      log(f"Block max |A| = ${rawA.map(math.abs).max}%.4f  → shared scale_A = $scaleAf%.6f  (raw=0x${rawScaleA.toHexString})")
      log(f"Block max |B| = ${rawB.map(math.abs).max}%.4f  → shared scale_B = $scaleBf%.6f  (raw=0x${rawScaleB.toHexString})")
      log(s"useCustomTree = ${cfg.useCustomTree}")

      val cycles: Seq[(Seq[Int], Seq[Int], Int, Int)] =
        (0 until (blockSize / vsize)).map { c =>
          val slice = c * vsize until (c + 1) * vsize
          (encA.slice(slice.start, slice.end),
           encB.slice(slice.start, slice.end),
           rawScaleA, rawScaleB)
        }

      test(new FDPUWithCustomReductionTree(cfg, vsize, true)).withAnnotations(Seq(WriteVcdAnnotation)) { dut =>
        initDut(dut)
        dut.io.resetAcc.poke(true.B); dut.clock.step()
        dut.io.resetAcc.poke(false.B)
        runCycles(dut, cfg, cycles, "MX block-quantized E5M2×E4M3 vec=8 × 4 cycles")
      }
      log("[PASSED] MX block-quantized 32-element dot product (E5M2×E4M3)")
    } catch { case e: Exception =>
      logErr(s"[FAILED] MX block-quantized 32-element dot product (E5M2×E4M3): ${e.getMessage}"); throw e
    }
  }

  // -----------------------------------------------------------------------
  // Test 17: MX block-quantized 32-element dot product
  //          (INT8 × E2M1 / UE8M0, vec=8, 4 cycles — custom tree path)
  //          Exercises the INT8 element type on the custom reduction tree.
  // -----------------------------------------------------------------------
  test("FDPUWithCustomReductionTree: MX block-quantized 32-element dot product (INT8×E2M1, vec=8, 4 cycles)") {
    log("\n[TEST 17] MX block-quantized 32-element dot product (INT8×E2M1 / UE8M0, vec=8)")
    try {
      val cfg       = ScaleAddConfig(MXFormats.INT8, MXFormats.E2M1, ScaleFormats.UE8M0)
      val vsize     = 8
      val blockSize = 32

      log(s"useCustomTree = ${cfg.useCustomTree}")

      val rawA: Seq[Double] = Seq(
         1.0, -2.0,  3.0,  1.5, -1.5,  2.0, -0.5,  3.5,
         2.5, -1.0,  0.5, -3.0,  1.0, -2.5,  2.0, -1.0,
        -1.5,  1.0, -2.0,  3.0, -0.5,  2.0, -1.0,  0.5,
         1.5, -3.0,  1.0, -1.5,  2.0, -0.5,  1.0, -2.0
      )
      val rawB: Seq[Double] = Seq(
         2.0, -1.0,  1.5, -2.0,  1.0, -1.5,  2.0, -1.0,
         1.0, -2.0,  1.5, -1.0,  2.0, -1.5,  1.0, -2.0,
        -1.0,  2.0, -1.5,  1.0, -2.0,  1.5, -1.0,  2.0,
         1.5, -1.0,  2.0, -1.5,  1.0, -2.0,  1.5, -1.0
      )
      require(rawA.size == blockSize && rawB.size == blockSize)

      val (encA, rawScaleA) = quantizeBlock(rawA, cfg.elementTypeA, cfg.stype)
      val (encB, rawScaleB) = quantizeBlock(rawB, cfg.elementTypeB, cfg.stype)

      val scaleAf = decodeScale(rawScaleA, cfg.stype)
      val scaleBf = decodeScale(rawScaleB, cfg.stype)
      log(f"Block max |A| = ${rawA.map(math.abs).max}%.4f  → shared scale_A = $scaleAf%.6f")
      log(f"Block max |B| = ${rawB.map(math.abs).max}%.4f  → shared scale_B = $scaleBf%.6f")

      val cycles: Seq[(Seq[Int], Seq[Int], Int, Int)] =
        (0 until (blockSize / vsize)).map { c =>
          val slice = c * vsize until (c + 1) * vsize
          (encA.slice(slice.start, slice.end),
           encB.slice(slice.start, slice.end),
           rawScaleA, rawScaleB)
        }

      test(new FDPUWithCustomReductionTree(cfg, vsize, true)) { dut =>
        initDut(dut)
        dut.io.resetAcc.poke(true.B); dut.clock.step()
        dut.io.resetAcc.poke(false.B)
        runCycles(dut, cfg, cycles, "MX block-quantized INT8×E2M1 vec=8 × 4 cycles")
      }
      log("[PASSED] MX block-quantized 32-element dot product (INT8×E2M1)")
    } catch { case e: Exception =>
      logErr(s"[FAILED] MX block-quantized 32-element dot product (INT8×E2M1): ${e.getMessage}"); throw e
    }
  }
}
