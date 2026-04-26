package mx.array

import chisel3._
import chiseltest._
import org.scalatest.funsuite.AnyFunSuite
import java.lang.Float.{floatToRawIntBits, intBitsToFloat}
import scala.util.Random
import mx.mac.{ElementType, ScaleType, MXFormats, ScaleFormats, ScaleAddConfig}
import mx.requant.RequantConfig

class PEArrayTest extends AnyFunSuite with ChiselScalatestTester {

  // =========================================================================
  // Log file
  // =========================================================================
  private val _log: java.io.PrintWriter = {
    val fw = new java.io.FileWriter("pe_array_test.log", false)
    new java.io.PrintWriter(fw, true)
  }
  private def log(msg: String): Unit    = _log.println(msg)
  private def logErr(msg: String): Unit = { _log.println(msg); println(msg) }

  // =========================================================================
  // SW golden model — PE (single-element, vectorSize=1)
  // =========================================================================

  def decodeElem(raw: Int, t: ElementType): Double = {
    if (t.name == "INT8") {
      val s = if ((raw & 0x80) != 0) raw - 256 else raw
      s.toDouble * math.pow(2, t.implicitScaleExp)
    } else {
      val sign = if (((raw >> (t.totalWidth - 1)) & 1) == 1) -1.0 else 1.0
      val exp  = (raw >> t.elementWidthMant) & ((1 << t.elementWidthExp) - 1)
      val mant = raw & ((1 << t.elementWidthMant) - 1)
      if (exp == 0)
        sign * (mant.toDouble / (1 << t.elementWidthMant)) * math.pow(2, 1 - t.bias)
      else
        sign * (1.0 + mant.toDouble / (1 << t.elementWidthMant)) * math.pow(2, exp - t.bias)
    }
  }

  def decodeScale(raw: Int, s: ScaleType): Double = {
    val expBits = (raw >> s.mantScaleWidth) & ((1 << s.expScaleWidth) - 1)
    if (s.mantScaleWidth == 0) {
      math.pow(2, expBits - s.bias)
    } else {
      val mantBits  = raw & ((1 << s.mantScaleWidth) - 1)
      val implicit1 = if (expBits > 0) 1.0 else 0.0
      val effExp    = if (expBits > 0) expBits - s.bias else 1 - s.bias
      (implicit1 + mantBits.toDouble / (1 << s.mantScaleWidth)) * math.pow(2, effExp)
    }
  }

  def encodeElem(v: Double, t: ElementType): Int = {
    if (v == 0.0) return 0
    val sign   = if (v < 0) 1 else 0
    val absVal = math.abs(v)
    if (t.name == "INT8") {
      val mag = math.round(absVal / math.pow(2, t.implicitScaleExp)).toInt.min(127)
      if (sign == 0) mag else (-mag) & 0xFF
    } else {
      val expU   = math.floor(math.log(absVal) / math.log(2)).toInt
      val expB   = expU + t.bias
      if (expB <= 0) {
        val m = math.round(absVal / math.pow(2, 1 - t.bias) * (1 << t.elementWidthMant))
                    .toInt.min((1 << t.elementWidthMant) - 1)
        (sign << (t.totalWidth - 1)) | m
      } else {
        val ec = expB.min((1 << t.elementWidthExp) - 1)
        val m  = math.round((absVal / math.pow(2, expU) - 1.0) * (1 << t.elementWidthMant))
                     .toInt.min((1 << t.elementWidthMant) - 1).max(0)
        (sign << (t.totalWidth - 1)) | (ec << t.elementWidthMant) | m
      }
    }
  }

  def encodeScale(v: Double, s: ScaleType): Int = {
    val expU  = math.floor(math.log(v.max(1e-38)) / math.log(2)).toInt
    val expB  = (expU + s.bias).max(0).min((1 << s.expScaleWidth) - 1)
    if (s.mantScaleWidth == 0) expB
    else {
      val m = math.round((v / math.pow(2, expU) - 1.0) * (1 << s.mantScaleWidth))
                  .toInt.min((1 << s.mantScaleWidth) - 1).max(0)
      (expB << s.mantScaleWidth) | m
    }
  }

  /** SW model for one PE cycle (vectorSize=1): elem_A × elem_B × scaleA × scaleB */
  def swPECycle(macCfg: ScaleAddConfig)(
    aRaw: Int, bRaw: Int, scaleARaw: Int, scaleBRaw: Int
  ): Float =
    (decodeElem(aRaw, macCfg.elementTypeA) *
     decodeElem(bRaw, macCfg.elementTypeB) *
     decodeScale(scaleARaw, macCfg.stype) *
     decodeScale(scaleBRaw, macCfg.stype)).toFloat

  // =========================================================================
  // SW golden model — RequantFP8 (UE8M0 only; matches RequantFP8Test)
  // =========================================================================

  /** UE8M0: max biased FP32 exponent across the row = shared scale. */
  def swSharedScale(fp32s: Seq[Float]): Int =
    fp32s.map(f => (floatToRawIntBits(f) >>> 23) & 0xFF).max

  /** FP32 → MXFP8 given UE8M0 shared exponent. RNE rounding, saturate, FTZ. */
  def swFP32toFP8(f: Float, sharedExp: Int, t: ElementType): Int = {
    val bits = floatToRawIntBits(f)
    val sign = (bits >>> 31) & 1
    val fp32Exp = (bits >>> 23) & 0xFF
    val fp32Man = bits & 0x7FFFFF

    val fp8ExpBits      = t.elementWidthExp
    val fp8MantBits     = t.elementWidthMant
    val fp8Bias         = t.bias
    val fp8MaxNormalExp = (1 << fp8ExpBits) - 2

    if (fp32Exp == 0) return 0
    val fp8ExpFull = fp32Exp - sharedExp + fp8Bias
    if (fp8ExpFull <= 0) return 0
    if (fp8ExpFull > fp8MaxNormalExp) {
      val maxMant = (1 << fp8MantBits) - 1
      return (sign << 7) | (fp8MaxNormalExp << fp8MantBits) | maxMant
    }
    val fp8MantRaw = fp32Man >>> (23 - fp8MantBits)
    val guardBit   = (fp32Man >>> (22 - fp8MantBits)) & 1
    val stickyBits = if (22 - fp8MantBits > 0) (fp32Man & ((1 << (22 - fp8MantBits)) - 1)) != 0 else false
    val roundUp    = guardBit == 1 && ((fp8MantRaw & 1) == 1 || stickyBits)
    val fp8Mant    = (fp8MantRaw + (if (roundUp) 1 else 0)) & ((1 << fp8MantBits) - 1)
    val fp8Exp     = fp8ExpFull & ((1 << fp8ExpBits) - 1)
    (sign << 7) | (fp8Exp << fp8MantBits) | fp8Mant
  }

  /**
   * Full SW requant model for a tileRows × blockSize FP32 block.
   * Returns (shared scale per row, fp8 per row per col).
   */
  def swRequant(block: Seq[Seq[Float]], t: ElementType): (Seq[Int], Seq[Seq[Int]]) = {
    val scales = block.map(swSharedScale)
    val fp8s   = block.zip(scales).map { case (row, sc) => row.map(swFP32toFP8(_, sc, t)) }
    (scales, fp8s)
  }

  // =========================================================================
  // DUT helpers
  // =========================================================================

  /** Active-low async reset: drive reset=1 for "not in reset" (matches FusedDotProductUnit). */
  def initDut(dut: PEArrayWrapper): Unit = {
    dut.reset.poke(true.B)
    dut.io.A_valid_i.poke(false.B)
    dut.io.B_valid_i.poke(false.B)
    dut.io.acc_reset_i.poke(false.B)
    dut.io.send_output_i.poke(false.B)
    dut.io.A_mode.poke(0.U)
    dut.io.B_mode.poke(0.U)
    dut.io.result_mode_quan.poke(0.U)
    dut.io.group_size.poke(0.U)
    dut.io.shared_format_i.poke(0.U)
  }

  /** Drive all rows/cols with the given element/scale values and step one clock. */
  def driveCycle(
    dut:     PEArrayWrapper,
    cfg:     PEArrayConfig,
    aElems:  Seq[Int],   // length tileRows, one element per row (vectorSize=1)
    bElems:  Seq[Int],   // length tileCols
    aScales: Seq[Int],   // length tileRows
    bScales: Seq[Int]    // length tileCols
  ): Unit = {
    for (r <- 0 until cfg.tileRows) dut.io.op_a_i(r).poke(aElems(r).U)
    for (c <- 0 until cfg.tileCols) dut.io.op_b_i(c).poke(bElems(c).U)
    for (r <- 0 until cfg.tileRows) dut.io.shared_exp_A_i(r).poke(aScales(r).U)
    for (c <- 0 until cfg.tileCols) dut.io.shared_exp_B_i(c).poke(bScales(c).U)
    dut.io.A_valid_i.poke(true.B)
    dut.io.B_valid_i.poke(true.B)
    dut.clock.step()
    dut.io.A_valid_i.poke(false.B)
    dut.io.B_valid_i.poke(false.B)
  }

  /** Read results_o(r)(c) as Float. */
  def peekAcc(dut: PEArrayWrapper, r: Int, c: Int): Float =
    intBitsToFloat(dut.io.results_o(r)(c).peek().litValue.toInt)

  /** Extract shared_scale_out[row] (row 0 = MSB, SV [0:tileRows-1][7:0]). */
  def extractScale(packed: BigInt, tileRows: Int, row: Int): Int =
    ((packed >> ((tileRows - row - 1) * 8)) & 0xFF).toInt

  /** Extract fp8_out[row][col] (row-major, row 0 / col 0 = MSB). */
  def extractFP8(packed: BigInt, tileRows: Int, blockSize: Int, row: Int, col: Int): Int = {
    val k    = row * blockSize + col
    val nOut = tileRows * blockSize
    ((packed >> ((nOut - k - 1) * 8)) & 0xFF).toInt
  }

  // =========================================================================
  // Shared test config — 4×4 tile, E5M2×E5M2/UE8M0, blockSize=32 → B=8 batches
  // =========================================================================
  val cfg = DefaultPEArrayConfigs.e5m2_4x4
  // Convenience aliases
  val macCfg    = cfg.macCfg
  val rqCfg     = cfg.requantCfg
  val B         = rqCfg.batchesPerBlock  // 8
  val tileRows  = cfg.tileRows           // 4
  val tileCols  = cfg.tileCols           // 4
  val blockSize = rqCfg.blockSize        // 32

  // Pre-encoded E5M2/UE8M0 constants
  val e_1   = encodeElem(1.0,  MXFormats.E5M2)
  val e_n1  = encodeElem(-1.0, MXFormats.E5M2)
  val e_2   = encodeElem(2.0,  MXFormats.E5M2)
  val e_0p5 = encodeElem(0.5,  MXFormats.E5M2)
  val s_1   = encodeScale(1.0, ScaleFormats.UE8M0)
  val s_2   = encodeScale(2.0, ScaleFormats.UE8M0)

  // =========================================================================
  // Test 1: valid_out timing — fires only after batchesPerBlock valid pulses
  // =========================================================================
  test("PEArray: valid_out fires exactly after batchesPerBlock PE cycles") {
    test(new PEArrayWrapper(cfg)).withAnnotations(Seq(WriteVcdAnnotation))  { dut =>
      initDut(dut)
      dut.io.valid_out.expect(false.B)

      val aE = Seq.fill(tileRows)(e_1)
      val bE = Seq.fill(tileCols)(e_1)
      val aS = Seq.fill(tileRows)(s_1)
      val bS = Seq.fill(tileCols)(s_1)

      // First B-1 cycles: valid_out stays low
      for (b <- 0 until B - 1) {
        driveCycle(dut, cfg, aE, bE, aS, bS)
        dut.io.valid_out.expect(false.B, s"valid_out must stay low after batch $b")
      }
      // B-th cycle: drive data, then one idle step so the last PE.validReg
      // pulse (combinational rq.valid_in) is sampled by RequantFP8.
      driveCycle(dut, cfg, aE, bE, aS, bS)
      dut.clock.step()  // flush: let PE.validReg=true propagate into RequantFP8
      dut.io.valid_out.expect(true.B, "valid_out must assert after batchesPerBlock cycles")

      // De-asserts on next idle cycle
      dut.clock.step()
      dut.io.valid_out.expect(false.B, "valid_out must de-assert after 1 idle cycle")
    }
  }

  // =========================================================================
  // Test 2: acc_reset_i clears all PE accumulators
  // =========================================================================
  test("PEArray: acc_reset_i clears all PE accumulators") {
    test(new PEArrayWrapper(cfg)) { dut =>
      initDut(dut)
      val aE = Seq.fill(tileRows)(e_1)
      val bE = Seq.fill(tileCols)(e_1)
      val aS = Seq.fill(tileRows)(s_1)
      val bS = Seq.fill(tileCols)(s_1)

      // Accumulate a few cycles
      for (_ <- 0 until 3) driveCycle(dut, cfg, aE, bE, aS, bS)
      // All accumulators should be non-zero
      for (r <- 0 until tileRows; c <- 0 until tileCols)
        assert(dut.io.results_o(r)(c).peek().litValue != 0,
          s"PE[$r][$c] expected non-zero before reset")

      // Reset
      dut.io.acc_reset_i.poke(true.B)
      dut.clock.step()
      dut.io.acc_reset_i.poke(false.B)

      // All accumulators should be cleared
      for (r <- 0 until tileRows; c <- 0 until tileCols)
        dut.io.results_o(r)(c).expect(0.U, s"PE[$r][$c] should be 0 after reset")
    }
  }

  // =========================================================================
  // Test 3: all-ones input → verify FP32 accumulators after B cycles
  //
  // Each PE(r,c): cycle contribution = 1 × 1 × 1 × 1 = 1.0
  // After b cycles: accOut(r,c) = b+1 (running sum)
  // =========================================================================
  test("PEArray: all-ones input — FP32 accumulators match SW model") {
    test(new PEArrayWrapper(cfg)){ dut =>
      initDut(dut)
      dut.io.acc_reset_i.poke(true.B); dut.clock.step()
      dut.io.acc_reset_i.poke(false.B)

      val aE = Seq.fill(tileRows)(e_1)
      val bE = Seq.fill(tileCols)(e_1)
      val aS = Seq.fill(tileRows)(s_1)
      val bS = Seq.fill(tileCols)(s_1)

      var swAcc = 0.0f
      for (b <- 0 until B) {
        val cycleContrib = swPECycle(macCfg)(e_1, e_1, s_1, s_1)
        driveCycle(dut, cfg, aE, bE, aS, bS)
        swAcc += cycleContrib
        for (r <- 0 until tileRows; c <- 0 until tileCols) {
          val hw  = peekAcc(dut, r, c)
          val tol = math.abs(swAcc) * 0.02f + 1e-6f
          assert(math.abs(hw - swAcc) <= tol,
            f"PE[$r][$c] after batch $b: hw=$hw%.4f expected=$swAcc%.4f")
        }
      }
      log(f"[Test 3] All-ones: final acc=$swAcc%.4f")
    }
  }

  // =========================================================================
  // Test 4: all-ones → verify MXFP8 output after one full block (UE8M0)
  //
  // After B cycles with 1×1×1×1 per lane:
  //   accOut(r,c) at batch b = (b+1).toFloat
  //   block(r) = [1.0, 1.0, 1.0, 1.0, 2.0, 2.0, ..., 8.0, 8.0, 8.0, 8.0]
  //   (each batch contributes tileCols copies of the running sum)
  // =========================================================================
  test("PEArray: all-ones → MXFP8 output matches SW requant model") {
    test(new PEArrayWrapper(cfg)) { dut =>
      initDut(dut)
      dut.io.acc_reset_i.poke(true.B); dut.clock.step()
      dut.io.acc_reset_i.poke(false.B)

      val aE = Seq.fill(tileRows)(e_1)
      val bE = Seq.fill(tileCols)(e_1)
      val aS = Seq.fill(tileRows)(s_1)
      val bS = Seq.fill(tileCols)(s_1)

      // Build SW block: block(r)(b*tileCols + c) = running FP32 acc at batch b
      val swBlock = Array.ofDim[Float](tileRows, blockSize)
      var swAcc   = 0.0f

      for (b <- 0 until B) {
        swAcc += swPECycle(macCfg)(e_1, e_1, s_1, s_1)
        driveCycle(dut, cfg, aE, bE, aS, bS)
        for (r <- 0 until tileRows; c <- 0 until tileCols)
          swBlock(r)(b * tileCols + c) = swAcc
      }
      dut.clock.step()  // flush: let last PE.validReg propagate into RequantFP8

      dut.io.valid_out.expect(true.B, "valid_out must fire after full block")

      val (expScales, expFP8) = swRequant(swBlock.map(_.toSeq).toSeq, rqCfg.outputType)
      val hwScalePacked = dut.io.shared_scale_out.peek().litValue
      val hwFP8Packed   = dut.io.result.peek().litValue

      for (row <- 0 until tileRows) {
        val hwScale = extractScale(hwScalePacked, tileRows, row)
        assert(hwScale == expScales(row),
          f"row $row shared_scale: hw=0x$hwScale%02X sw=0x${expScales(row)}%02X")
        for (col <- 0 until blockSize) {
          val hw = extractFP8(hwFP8Packed, tileRows, blockSize, row, col)
          val sw = expFP8(row)(col)
          assert(hw == sw,
            f"fp8[$row][$col]: hw=0x$hw%02X sw=0x$sw%02X " +
            f"(fp32=${swBlock(row)(col)}%.4f, scale=${expScales(row)})")
        }
      }
      log("[Test 4] all-ones MXFP8 PASSED")
    }
  }

  // =========================================================================
  // Test 5: heterogeneous rows — each row gets a different A scale,
  //         shared B scale = 1.0.  Verifies per-row scale selection.
  //
  //   row 0: scaleA=1, row 1: scaleA=2, row 2: scaleA=1, row 3: scaleA=2
  //   All elements = 1.0, scaleB = 1.0
  //   row 0 acc per cycle = 1, row 1 acc per cycle = 2
  // =========================================================================
  test("PEArray: heterogeneous row scales — per-row MXFP8 scale selection") {
    test(new PEArrayWrapper(cfg)) { dut =>
      initDut(dut)
      dut.io.acc_reset_i.poke(true.B); dut.clock.step()
      dut.io.acc_reset_i.poke(false.B)

      val aElems  = Seq.fill(tileRows)(e_1)
      val bElems  = Seq.fill(tileCols)(e_1)
      val aScales = Seq(s_1, s_2, s_1, s_2)   // alternating
      val bScales = Seq.fill(tileCols)(s_1)

      val swBlock = Array.ofDim[Float](tileRows, blockSize)
      val swAccR  = Array.fill(tileRows)(0.0f)

      for (b <- 0 until B) {
        for (r <- 0 until tileRows) {
          // With vectorSize=1: each PE(r,c) gets same A/scaleA, same B/scaleB
          swAccR(r) += swPECycle(macCfg)(aElems(r), bElems(0), aScales(r), bScales(0))
        }
        driveCycle(dut, cfg, aElems, bElems, aScales, bScales)
        for (r <- 0 until tileRows; c <- 0 until tileCols)
          swBlock(r)(b * tileCols + c) = swAccR(r)
      }
      dut.clock.step()  // flush: let last PE.validReg propagate into RequantFP8

      dut.io.valid_out.expect(true.B)

      val (expScales, expFP8) = swRequant(swBlock.map(_.toSeq).toSeq, rqCfg.outputType)
      val hwScalePacked = dut.io.shared_scale_out.peek().litValue
      val hwFP8Packed   = dut.io.result.peek().litValue

      for (row <- 0 until tileRows) {
        val hwScale = extractScale(hwScalePacked, tileRows, row)
        assert(hwScale == expScales(row),
          f"row $row scale: hw=0x$hwScale%02X sw=0x${expScales(row)}%02X")
        for (col <- 0 until blockSize) {
          val hw = extractFP8(hwFP8Packed, tileRows, blockSize, row, col)
          val sw = expFP8(row)(col)
          assert(hw == sw, f"fp8[$row][$col]: hw=0x$hw%02X sw=0x$sw%02X")
        }
      }
      log("[Test 5] heterogeneous row scales PASSED")
    }
  }

  // =========================================================================
  // Test 6: two consecutive blocks — valid_out fires, then clear & repeat
  // =========================================================================
  test("PEArray: two consecutive blocks — valid_out fires twice correctly") {
    test(new PEArrayWrapper(cfg)) { dut =>
      initDut(dut)
      dut.io.acc_reset_i.poke(true.B); dut.clock.step()
      dut.io.acc_reset_i.poke(false.B)

      def runBlock(aRaw: Int, bRaw: Int, sARaw: Int, sBRaw: Int)
          : (Seq[Seq[Float]], BigInt, BigInt) = {
        val swBlock = Array.ofDim[Float](tileRows, blockSize)
        val swAcc   = Array.fill(tileRows, tileCols)(0.0f)
        for (b <- 0 until B) {
          driveCycle(dut, cfg,
            Seq.fill(tileRows)(aRaw), Seq.fill(tileCols)(bRaw),
            Seq.fill(tileRows)(sARaw), Seq.fill(tileCols)(sBRaw))
          for (r <- 0 until tileRows; c <- 0 until tileCols) {
            swAcc(r)(c) += swPECycle(macCfg)(aRaw, bRaw, sARaw, sBRaw)
            swBlock(r)(b * tileCols + c) = swAcc(r)(c)
          }
        }
        dut.clock.step()  // flush: let last PE.validReg propagate into RequantFP8
        dut.io.valid_out.expect(true.B, "valid_out must fire at end of block")
        val hwScale = dut.io.shared_scale_out.peek().litValue
        val hwFP8   = dut.io.result.peek().litValue
        (swBlock.map(_.toSeq).toSeq, hwScale, hwFP8)
      }

      // Block 1: all 1×1, scale 1×1
      val (blk1, hwSc1, hwFP8_1) = runBlock(e_1, e_1, s_1, s_1)

      // Reset accumulator before block 2
      dut.io.acc_reset_i.poke(true.B); dut.clock.step()
      dut.io.acc_reset_i.poke(false.B)

      // Block 2: 2×1, scale 1×1 — each cycle contributes 2.0
      val (blk2, hwSc2, hwFP8_2) = runBlock(e_2, e_1, s_1, s_1)

      // Verify both blocks against SW model
      for ((blk, hwScPacked, hwFP8Packed, tag) <-
           Seq((blk1, hwSc1, hwFP8_1, "block1"), (blk2, hwSc2, hwFP8_2, "block2"))) {
        val (expSc, expFP8) = swRequant(blk, rqCfg.outputType)
        for (row <- 0 until tileRows) {
          val hwSc = extractScale(hwScPacked, tileRows, row)
          assert(hwSc == expSc(row),
            f"$tag row$row scale: hw=0x$hwSc%02X sw=0x${expSc(row)}%02X")
          for (col <- 0 until blockSize) {
            val hw = extractFP8(hwFP8Packed, tileRows, blockSize, row, col)
            val sw = expFP8(row)(col)
            assert(hw == sw, f"$tag fp8[$row][$col]: hw=0x$hw%02X sw=0x$sw%02X")
          }
        }
      }
      log("[Test 6] consecutive blocks PASSED")
    }
  }

  // =========================================================================
  // Test 7: random inputs — full end-to-end with SW golden model
  // =========================================================================
  test("PEArray: random E5M2×E5M2/UE8M0 inputs — FP32 + MXFP8 match SW") {
    val rng = new Random(0xC0FFEE)

    // All E5M2 non-zero raw values: exponent != 0 and not max (avoids NaN/Inf)
    def randE5M2(): Int = {
      val exp  = 1 + rng.nextInt(29)   // biased exp 1..29, keeps value well inside range
      val mant = rng.nextInt(4)        // 2-bit mantissa for E5M2
      val sign = rng.nextInt(2)
      (sign << 7) | (exp << 2) | mant
    }
    def randUE8M0(): Int = 120 + rng.nextInt(8)  // exponent ~1.0, avoids scale extremes

    test(new PEArrayWrapper(cfg)) { dut =>
      initDut(dut)
      dut.io.acc_reset_i.poke(true.B); dut.clock.step()
      dut.io.acc_reset_i.poke(false.B)

      // Generate random inputs for each batch
      val aElems  = Array.tabulate(B, tileRows)((_, _) => randE5M2())
      val bElems  = Array.tabulate(B, tileCols)((_, _) => randE5M2())
      val aScales = Array.tabulate(B, tileRows)((_, _) => randUE8M0())
      val bScales = Array.tabulate(B, tileCols)((_, _) => randUE8M0())

      // SW accumulation tracking: swAcc(r)(c) = running sum
      val swAcc   = Array.ofDim[Float](tileRows, tileCols)
      val swBlock = Array.ofDim[Float](tileRows, blockSize)

      for (b <- 0 until B) {
        // SW: each PE(r,c) accumulates one cycle
        for (r <- 0 until tileRows; c <- 0 until tileCols) {
          swAcc(r)(c) += swPECycle(macCfg)(aElems(b)(r), bElems(b)(c),
                                           aScales(b)(r), bScales(b)(c))
          swBlock(r)(b * tileCols + c) = swAcc(r)(c)
        }

        driveCycle(dut, cfg,
          aElems(b).toSeq, bElems(b).toSeq,
          aScales(b).toSeq, bScales(b).toSeq)

        // Check FP32 accumulators at each batch
        for (r <- 0 until tileRows; c <- 0 until tileCols) {
          val hw  = peekAcc(dut, r, c)
          val sw  = swAcc(r)(c)
          val tol = math.abs(sw) * 0.05f + 1e-5f
          assert(math.abs(hw - sw) <= tol,
            f"batch $b PE[$r][$c]: hw=$hw%.6f sw=$sw%.6f diff=${math.abs(hw-sw)}%.6f")
        }
      }

      dut.clock.step()  // flush: let last PE.validReg propagate into RequantFP8
      dut.io.valid_out.expect(true.B, "valid_out must fire after full block")

      // Check MXFP8 outputs
      val (expScales, expFP8) = swRequant(swBlock.map(_.toSeq).toSeq, rqCfg.outputType)
      val hwScalePacked = dut.io.shared_scale_out.peek().litValue
      val hwFP8Packed   = dut.io.result.peek().litValue

      var mismatchCount = 0
      for (row <- 0 until tileRows) {
        val hwSc = extractScale(hwScalePacked, tileRows, row)
        if (hwSc != expScales(row)) {
          logErr(f"[FAIL] row $row scale: hw=0x$hwSc%02X sw=0x${expScales(row)}%02X")
          mismatchCount += 1
        }
        for (col <- 0 until blockSize) {
          val hw = extractFP8(hwFP8Packed, tileRows, blockSize, row, col)
          val sw = expFP8(row)(col)
          if (hw != sw) {
            logErr(f"[FAIL] fp8[$row][$col]: hw=0x$hw%02X sw=0x$sw%02X " +
                   f"(fp32=${swBlock(row)(col)}%.4f)")
            mismatchCount += 1
          }
        }
      }
      assert(mismatchCount == 0, s"$mismatchCount MXFP8 element mismatches — see pe_array_test.log")
      log("[Test 7] random E5M2 PASSED")
    }
  }
}
