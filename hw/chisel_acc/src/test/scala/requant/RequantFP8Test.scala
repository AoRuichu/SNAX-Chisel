package mx.requant

import chisel3._
import chiseltest._
import org.scalatest.funsuite.AnyFunSuite
import scala.util.Random
import java.lang.Float.{floatToRawIntBits, intBitsToFloat}
import mx.mac.{ElementType, ScaleType, MXFormats, ScaleFormats}

class RequantFP8Test extends AnyFunSuite with ChiselScalatestTester {

  // =========================================================================
  // Software golden model — UE8M0
  // =========================================================================

  /**
   * UE8M0 shared scale (OCP MX semantics):
   *   X = clamp(max biased FP32 exp − emax_element, 0, 255)
   * with emax_element = (1 << outExpBits) − 2 − outBias.
   */
  def swSharedScale(fp32s: Seq[Float], t: ElementType): Int = {
    val maxBiasedExp = fp32s.map(f => (floatToRawIntBits(f) >>> 23) & 0xFF).max
    val emaxElem     = ((1 << t.elementWidthExp) - 2) - t.bias
    (maxBiasedExp - emaxElem).max(0).min(255)
  }

  /**
   * FP32 → MXFP8 for a single element given the UE8M0 shared exponent.
   * Matches the hardware implementation exactly:
   *   - Subnormal / zero FP32 → 0
   *   - Re-biased exp <= 0    → underflow → 0
   *   - Re-biased exp > max   → saturate to ±max-normal
   *   - Otherwise             → truncate mantissa with RNE rounding
   */
  def swFP32toFP8(f: Float, sharedExp: Int, t: ElementType): Int = {
    val bits    = floatToRawIntBits(f)
    val sign    = (bits >>> 31) & 1
    val fp32Exp = (bits >>> 23) & 0xFF
    val fp32Man = bits & 0x7FFFFF

    val fp8ExpBits      = t.elementWidthExp
    val fp8MantBits     = t.elementWidthMant
    val fp8Bias         = t.bias
    val fp8MaxNormalExp = (1 << fp8ExpBits) - 2   // 30 for E5M2, 14 for E4M3

    if (fp32Exp == 0) return 0                     // zero / subnormal → 0

    val fp8ExpFull = fp32Exp - sharedExp + fp8Bias

    if (fp8ExpFull <= 0) return 0                  // underflow → 0

    if (fp8ExpFull > fp8MaxNormalExp) {            // saturate to ±max-normal
      val maxMant = (1 << fp8MantBits) - 1
      return (sign << 7) | (fp8MaxNormalExp << fp8MantBits) | maxMant
    }

    // Truncate mantissa with RNE rounding.
    // fp32_man[22 : 23-fp8MantBits]  — fp8 mantissa bits
    // fp32_man[22-fp8MantBits]        — guard bit
    // fp32_man[21-fp8MantBits : 0]    — sticky bits
    val fp8MantRaw = fp32Man >>> (23 - fp8MantBits)
    val guardBit   = (fp32Man >>> (22 - fp8MantBits)) & 1
    val stickyBits =
      if (22 - fp8MantBits > 0) (fp32Man & ((1 << (22 - fp8MantBits)) - 1)) != 0
      else false
    val roundUp = guardBit == 1 && ((fp8MantRaw & 1) == 1 || stickyBits)
    val fp8Mant = (fp8MantRaw + (if (roundUp) 1 else 0)) & ((1 << fp8MantBits) - 1)
    val fp8Exp  = fp8ExpFull & ((1 << fp8ExpBits) - 1)

    (sign << 7) | (fp8Exp << fp8MantBits) | fp8Mant
  }

  /**
   * Full SW model for a complete block (UE8M0 scale).
   * @param block  Indexed as block(row)(col), dimensions tileRows × blockSize.
   * @return  (shared scale per row,  fp8 element per row per col)
   */
  def swRequantBlock(
    block: Seq[Seq[Float]],
    t: ElementType
  ): (Seq[Int], Seq[Seq[Int]]) = {
    val scales = block.map(row => swSharedScale(row, t))
    val fp8s   = block.zip(scales).map { case (row, sc) =>
      row.map(f => swFP32toFP8(f, sc, t))
    }
    (scales, fp8s)
  }

  // =========================================================================
  // Software golden model — ExMy scale (NVFP4-style)
  // =========================================================================

  /** Real OCP-MX max-normal (biased_exp, mant) per element format. */
  private def nvfp4Limits(t: ElementType): (Int, Int) = t.name match {
    case "E5M2" => (30, (1 << t.elementWidthMant) - 1)
    case "E4M3" => (15, (1 << t.elementWidthMant) - 2)
    case "E3M2" => (7,  (1 << t.elementWidthMant) - 1)
    case "E2M3" => (3,  (1 << t.elementWidthMant) - 1)
    case _      => ((1 << t.elementWidthExp) - 2, (1 << t.elementWidthMant) - 1)
  }

  /**
   * Compute the ExMy shared scale for a row of FP32 values (NVFP4 ceil).
   *
   * Mirrors MaxScaleFinder ExMy hardware:
   *   1. Find the FP32 with the largest 31-bit magnitude.
   *   2. q_int = (1.maxMant) × 2^EXTRA / (1.elemMaxMant).
   *   3. Extract top M scale-mant bits, ceil-round on any residual.
   *   4. Compute scale_biased_exp with normalization shift and mant carry.
   *   5. Clamp: zero block → {0,0}; saturate → {maxExp, all-ones};
   *      subnormal range → {0, 1}.
   */
  def swSharedScaleExMy(fp32s: Seq[Float], t: ElementType, st: ScaleType): Int = {
    val M            = st.mantScaleWidth
    val E            = st.expScaleWidth
    val scaleBias    = st.bias
    val maxScaleExpV = (1 << E) - 1

    val outMantBits  = t.elementWidthMant
    val outBias      = t.bias
    val (elemMaxExp, elemMaxMant) = nvfp4Limits(t)
    val E_elem            = elemMaxExp - outBias
    val maxNormSignifInt  = (1 << outMantBits) | elemMaxMant

    val maxBits      = fp32s.map(f => floatToRawIntBits(f) & 0x7FFFFFFF).max
    val maxBiasedExp = (maxBits >>> 23) & 0xFF
    val maxMant23    = maxBits & 0x7FFFFF
    val maxIsZero    = maxBiasedExp == 0  // FP32 zero or subnormal

    val EXTRA = M + 3
    val IMPL  = 23 - outMantBits + EXTRA
    val fp32FullMant = if (maxBiasedExp != 0) (1 << 23) | maxMant23 else 0
    val qNum         = fp32FullMant.toLong << EXTRA
    val qInt         = qNum / maxNormSignifInt
    val qRem         = qNum % maxNormSignifInt

    val qGeq1 = ((qInt >>> IMPL) & 1L) == 1L

    val mantRaw =
      if (qGeq1) ((qInt >>> (IMPL - M)) & ((1L << M) - 1)).toInt
      else       ((qInt >>> (IMPL - 1 - M)) & ((1L << M) - 1)).toInt
    val residMask =
      if (qGeq1) (1L << (IMPL - M))     - 1L
      else       (1L << (IMPL - 1 - M)) - 1L
    val residNonZero = (qInt & residMask) != 0L
    val ceilInc      = if (qRem != 0L || residNonZero) 1 else 0

    val mantSum      = mantRaw + ceilInc
    val mantOverflow = mantSum >>> M
    val mantNormal   = mantSum & ((1 << M) - 1)

    val normAdj = if (qGeq1) 0 else -1
    val scaleBiasedExp = (maxBiasedExp - 127 - E_elem) + normAdj + mantOverflow + scaleBias

    if (maxIsZero) 0
    else if (scaleBiasedExp >= maxScaleExpV) (maxScaleExpV << M) | ((1 << M) - 1)
    else if (scaleBiasedExp <= 0)            (0 << M) | 1               // smallest subnormal
    else                                     ((scaleBiasedExp & maxScaleExpV) << M) | mantNormal
  }

  /**
   * FP32 → MXFP8 for a single element given an NVFP4-style ExMy shared scale.
   *
   * Mirrors FP32ToMXFP8 ExMy hardware:
   *   - Subnormal scale {0, mant} is renormalized to (1.mant_norm × 2^eff_exp).
   *   - Saturation uses the element format's real OCP-MX max-normal
   *     (exMyMaxNormalExp / exMyMaxNormalMant), with E4M3's NaN-borderline
   *     (15, 7) clamped to (15, 6).
   */
  def swFP32toFP8ExMy(f: Float, sharedScale: Int, t: ElementType, st: ScaleType): Int = {
    val bits    = floatToRawIntBits(f)
    val sign    = (bits >>> 31) & 1
    val fp32Exp = (bits >>> 23) & 0xFF
    val fp32Man = bits & 0x7FFFFF

    val fp8ExpBits      = t.elementWidthExp
    val fp8MantBits     = t.elementWidthMant
    val fp8Bias         = t.bias
    val (exMyMaxNormalExp, exMyMaxNormalMant) = nvfp4Limits(t)

    if (fp32Exp == 0) return 0  // zero / subnormal → 0

    val M          = st.mantScaleWidth
    val E          = st.expScaleWidth
    val scaleBias  = st.bias
    val correction = scaleBias - 127 + fp8Bias

    val scaleBiasedExpRaw = (sharedScale >>> M) & ((1 << E) - 1)
    val scaleMantRaw      = sharedScale & ((1 << M) - 1)
    val isZeroScale       = scaleBiasedExpRaw == 0 && scaleMantRaw == 0
    val isSubnormScale    = scaleBiasedExpRaw == 0 && scaleMantRaw != 0

    if (isZeroScale) return 0

    // Subnormal renormalization: shift leading 1 to the implicit-1 position.
    val (effBiasedExp, scaleFullMant) =
      if (isSubnormScale) {
        // Find MSB position of scaleMantRaw (0..M-1).
        var msbPos = 0
        var i = M - 1
        while (i >= 0 && msbPos == 0) {
          if (((scaleMantRaw >>> i) & 1) == 1) msbPos = i
          i -= 1
        }
        val leftShift = M - msbPos
        val mantSubNorm = (scaleMantRaw << leftShift) & ((1 << M) - 1)
        (1 - leftShift, (1 << M) | mantSubNorm)
      } else {
        (scaleBiasedExpRaw, (if (scaleBiasedExpRaw != 0) 1 << M else 0) | scaleMantRaw)
      }

    val fp8ExpRaw = fp32Exp - effBiasedExp + correction

    // Integer mantissa division: q_int = fp32FullMant × 2^EXTRA / scaleFullMant
    val EXTRA = fp8MantBits + 3
    val IMPL  = 23 - M + EXTRA

    val fp32FullMant = (1 << 23) | fp32Man         // 24 bits
    val qNum         = fp32FullMant.toLong << EXTRA
    val qInt         = qNum / scaleFullMant
    val qRem         = qNum % scaleFullMant

    val qGeq1 = ((qInt >>> IMPL) & 1L) == 1L

    val fp8MantRaw =
      if (qGeq1) ((qInt >>> (IMPL - fp8MantBits)) & ((1L << fp8MantBits) - 1)).toInt
      else       ((qInt >>> (IMPL - 1 - fp8MantBits)) & ((1L << fp8MantBits) - 1)).toInt
    val guardBit =
      if (qGeq1) ((qInt >>> (IMPL - fp8MantBits - 1)) & 1L).toInt
      else       ((qInt >>> (IMPL - fp8MantBits - 2)) & 1L).toInt
    val roundBit =
      if (qGeq1) ((qInt >>> (IMPL - fp8MantBits - 2)) & 1L).toInt
      else       ((qInt >>> (IMPL - fp8MantBits - 3)) & 1L).toInt
    val stickyMask =
      if (qGeq1) (1L << (IMPL - fp8MantBits - 2)) - 1L
      else       (1L << (IMPL - fp8MantBits - 3)) - 1L
    val stickyBit = qRem != 0L || (qInt & stickyMask) != 0L

    val roundUp       = guardBit == 1 && ((fp8MantRaw & 1) == 1 || roundBit == 1 || stickyBit)
    val fp8MantCarry  = fp8MantRaw + (if (roundUp) 1 else 0)
    val mantOverflow  = fp8MantCarry >>> fp8MantBits
    val fp8Mant       = fp8MantCarry & ((1 << fp8MantBits) - 1)

    val normAdj     = if (qGeq1) 0 else -1
    val fp8ExpFull  = fp8ExpRaw + normAdj + mantOverflow

    // Saturate at the element format's real OCP-MX max-normal, including
    // E4M3's NaN-borderline (15, 7) → (15, 6).
    val borderNaN = fp8ExpFull == exMyMaxNormalExp && fp8Mant > exMyMaxNormalMant
    if (fp8ExpFull > exMyMaxNormalExp || borderNaN) {
      return (sign << 7) | (exMyMaxNormalExp << fp8MantBits) | exMyMaxNormalMant
    }

    if (fp8ExpFull > 0) {
      val fp8Exp = fp8ExpFull & ((1 << fp8ExpBits) - 1)
      return (sign << 7) | (fp8Exp << fp8MantBits) | fp8Mant
    }

    // Subnormal element output: shift (1.fp8Mant) right by (1 - fp8ExpFull),
    // then RNE-round the shifted-out bits.  If rounding overflows to the
    // smallest normal, encode as biased_exp=1, mant=0.
    val subnMaxShift = fp8MantBits + 2
    val subnShift    = math.min(1 - fp8ExpFull, subnMaxShift)
    val sigInt       = (1 << fp8MantBits) | fp8Mant
    val padded       = sigInt.toLong << subnMaxShift                    // sigInt at upper bits
    val shiftedPad   = padded >>> subnShift
    val subnTrunc    = ((shiftedPad >>> subnMaxShift) & ((1L << fp8MantBits) - 1)).toInt
    val subnGuard    = ((shiftedPad >>> (subnMaxShift - 1)) & 1L).toInt
    val subnSticky   = if (subnMaxShift >= 2) (shiftedPad & ((1L << (subnMaxShift - 1)) - 1)) != 0
                       else false
    val subnRoundUp  = subnGuard == 1 && ((subnTrunc & 1) == 1 || subnSticky)
    val subnSum      = subnTrunc + (if (subnRoundUp) 1 else 0)
    val subnCarry    = (subnSum >>> fp8MantBits) & 1
    val subnMant     = subnSum & ((1 << fp8MantBits) - 1)

    if (subnCarry == 1) {
      // Rounded up into smallest normal: biased_exp=1, mant=0.
      (sign << 7) | (1 << fp8MantBits) | 0
    } else if (subnMant != 0) {
      // Subnormal: biased_exp=0, mant=subnMant.
      (sign << 7) | (0 << fp8MantBits) | subnMant
    } else {
      0
    }
  }

  /** Full SW model for a block with an ExMy scale format. */
  def swRequantBlockExMy(
    block: Seq[Seq[Float]],
    t: ElementType,
    st: ScaleType
  ): (Seq[Int], Seq[Seq[Int]]) = {
    val scales = block.map(row => swSharedScaleExMy(row, t, st))
    val fp8s   = block.zip(scales).map { case (row, sc) =>
      row.map(f => swFP32toFP8ExMy(f, sc, t, st))
    }
    (scales, fp8s)
  }

  // =========================================================================
  // DUT helpers
  // =========================================================================

  /**
   * Pack a tileRows × tileCols matrix of FP32 values into the flat input bus.
   *
   * SV convention [0:R-1][0:C-1][31:0]:
   *   element [i][j] occupies MSB-slot k = i*C + j.
   *   Flat bit range: hi = (nIn-k-1)*32 + 31, lo = (nIn-k-1)*32
   */
  def packFP32(tileRows: Int, tileCols: Int, data: Seq[Seq[Float]]): BigInt = {
    val nIn = tileRows * tileCols
    var result = BigInt(0)
    for (row <- 0 until tileRows; col <- 0 until tileCols) {
      val k     = row * tileCols + col
      val bits  = floatToRawIntBits(data(row)(col)).toLong & 0xFFFFFFFFL
      val shift = (nIn - k - 1) * 32
      result |= BigInt(bits) << shift
    }
    result
  }

  /**
   * Extract the shared scale for one row from the packed shared_scale_out bus.
   * Row 0 is at LSB so that little-endian memory stores [row0, row1, …]
   * in ascending byte address.
   */
  def extractScale(packed: BigInt, tileRows: Int, row: Int): Int =
    ((packed >> (row * 8)) & 0xFF).toInt

  /**
   * Extract fp8_out[row][col] from the packed fp8_out bus.
   * (row=0, col=0) at LSB; slot k = row*blockSize + col.
   */
  def extractFP8(
    packed: BigInt, tileRows: Int, blockSize: Int, row: Int, col: Int
  ): Int = {
    val k     = row * blockSize + col
    val shift = k * 8
    ((packed >> shift) & 0xFF).toInt
  }

  /**
   * Drive reset=1 so asyncRstN = ~1 = 0 (not in reset) for normal operation.
   * Mirrors FusedDotProductUnit.initDut: RequantFP8 registers use active-low
   * async reset (!reset.asBool), so ChiselTest's default reset=0 would keep
   * asyncRstN permanently high and hold all registers in their reset state.
   */
  def initDut(dut: RequantFP8): Unit = {
    dut.reset.poke(true.B)
    dut.io.valid_in.poke(false.B)
  }

  /**
   * Drive one tile-column batch: poke valid_in=1 + fp32_in, step, then
   * de-assert valid_in.  The batch data is a tileRows × tileCols matrix.
   */
  def driveBatch(dut: RequantFP8, batchData: Seq[Seq[Float]]): Unit = {
    val cfg    = dut.cfg
    val fp32Bus = packFP32(cfg.tileRows, cfg.tileCols, batchData)
    dut.io.fp32_in.poke(fp32Bus.U)
    dut.io.valid_in.poke(true.B)
    dut.clock.step()
    dut.io.valid_in.poke(false.B)
  }

  /**
   * Drive a complete block of blockSize columns split across
   * batchesPerBlock pulses of tileCols columns each.
   * @param block  Indexed block(row)(col), col in 0..blockSize-1.
   */
  def driveBlock(dut: RequantFP8, block: Seq[Seq[Float]]): Unit = {
    val cfg = dut.cfg
    for (b <- 0 until cfg.batchesPerBlock) {
      val slice = block.map(row =>
        row.slice(b * cfg.tileCols, (b + 1) * cfg.tileCols))
      driveBatch(dut, slice)
    }
  }

  // =========================================================================
  // Shared test configs
  // =========================================================================

  // blockSize=16, tileRows=2, tileCols=4  →  batchesPerBlock=4
  val cfgE5M2 = RequantConfig(16, 4, 4, MXFormats.E5M2)
  val cfgE4M3 = RequantConfig(16, 4, 4, MXFormats.E4M3)

  // =========================================================================
  // Test 1: valid_out fires after exactly batchesPerBlock pulses
  // =========================================================================
  test("RequantFP8: valid_out timing — fires only after full block") {
    test(new RequantFP8(cfgE5M2)) { dut =>
      initDut(dut)
      val cfg       = dut.cfg
      val zeroSlice = Seq.fill(cfg.tileRows)(Seq.fill(cfg.tileCols)(0.0f))

      dut.clock.step()
      dut.io.valid_out.expect(false.B, "valid_out must be false before any input")

      // First B-1 batches: valid_out stays low
      for (b <- 0 until cfg.batchesPerBlock - 1) {
        driveBatch(dut, zeroSlice)
        dut.io.valid_out.expect(false.B, s"valid_out must be false after batch $b")
      }

      // B-th batch: valid_out goes high the cycle after the clock edge
      driveBatch(dut, zeroSlice)
      dut.io.valid_out.expect(true.B, "valid_out must be true after batchesPerBlock pulses")

      // De-asserts after one idle cycle
      dut.clock.step()
      dut.io.valid_out.expect(false.B, "valid_out must de-assert after 1 cycle")
    }
  }

  // =========================================================================
  // Test 2: all-zeros block → all-zero outputs
  // =========================================================================
  test("RequantFP8 E5M2: all-zeros block → shared_scale=0 and fp8=0") {
    test(new RequantFP8(cfgE5M2)) { dut =>
      initDut(dut)
      val cfg       = dut.cfg
      val zeroBlock = Seq.fill(cfg.tileRows)(Seq.fill(cfg.blockSize)(0.0f))

      driveBlock(dut, zeroBlock)
      dut.io.valid_out.expect(true.B)

      val scaleOut = dut.io.shared_scale_out.peek().litValue
      val fp8Out   = dut.io.elem_out.peek().litValue

      for (row <- 0 until cfg.tileRows)
        assert(extractScale(scaleOut, cfg.tileRows, row) == 0,
          s"row $row: shared_scale should be 0")

      for (row <- 0 until cfg.tileRows; col <- 0 until cfg.blockSize)
        assert(extractFP8(fp8Out, cfg.tileRows, cfg.blockSize, row, col) == 0,
          s"fp8[$row][$col] should be 0")
    }
  }

  // =========================================================================
  // Test 3: block of 1.0 (E5M2, emax=15) → shared_scale = 127−15 = 112,
  //         max element saturates to max-normal exponent: fp8 = 0x78.
  // =========================================================================
  test("RequantFP8 E5M2: block of 1.0 → shared_scale=112, fp8=0x78") {
    test(new RequantFP8(cfgE5M2)) { dut =>
      initDut(dut)
      val cfg   = dut.cfg
      // E5M2 encoding under OCP MX shared scale:
      //   shared_scale = 127 − emax(=15) = 112
      //   fp8 exp = 127 − 112 + 15 = 30 (= max-normal exp), mant = 0
      //   → (0 << 7) | (30 << 2) | 0 = 0x78
      val block = Seq.fill(cfg.tileRows)(Seq.fill(cfg.blockSize)(1.0f))

      driveBlock(dut, block)
      dut.io.valid_out.expect(true.B)

      val scaleOut = dut.io.shared_scale_out.peek().litValue
      val fp8Out   = dut.io.elem_out.peek().litValue

      for (row <- 0 until cfg.tileRows) {
        val sc = extractScale(scaleOut, cfg.tileRows, row)
        assert(sc == 112, s"row $row: shared_scale=$sc, expected 112")
        for (col <- 0 until cfg.blockSize) {
          val fp8 = extractFP8(fp8Out, cfg.tileRows, cfg.blockSize, row, col)
          assert(fp8 == 0x78, f"fp8[$row][$col]=0x$fp8%02X, expected 0x78")
        }
      }
    }
  }

  // =========================================================================
  // Test 4: max-exponent selection — row with larger value sets the scale
  // =========================================================================
  test("RequantFP8 E5M2: max-exponent selection with [1.0, 2.0] mix") {
    test(new RequantFP8(cfgE5M2)) { dut =>
      initDut(dut)
      val cfg = dut.cfg
      // Row 0: alternating 1.0 / 2.0 → max biased exp = 128, shared_scale = 113
      // Row 1: all 1.0              → max biased exp = 127, shared_scale = 112
      val block = Seq(
        Seq.tabulate(cfg.blockSize)(i => if (i % 2 == 0) 1.0f else 2.0f),
        Seq.fill(cfg.blockSize)(1.0f),
        Seq.fill(cfg.blockSize)(1.0f),
        Seq.fill(cfg.blockSize)(1.0f)
      )
      val (expScales, expFP8) = swRequantBlock(block, cfg.outputType)

      driveBlock(dut, block)
      dut.io.valid_out.expect(true.B)

      val scaleOut = dut.io.shared_scale_out.peek().litValue
      val fp8Out   = dut.io.elem_out.peek().litValue

      for (row <- 0 until cfg.tileRows) {
        val hwScale = extractScale(scaleOut, cfg.tileRows, row)
        assert(hwScale == expScales(row),
          s"row $row scale: hw=$hwScale sw=${expScales(row)}")
        for (col <- 0 until cfg.blockSize) {
          val hw = extractFP8(fp8Out, cfg.tileRows, cfg.blockSize, row, col)
          val sw = expFP8(row)(col)
          assert(hw == sw, f"fp8[$row][$col]: hw=0x$hw%02X sw=0x$sw%02X")
        }
      }
    }
  }

  // =========================================================================
  // Test 5: negative values preserve sign bit, magnitude matches positive
  // =========================================================================
  test("RequantFP8 E5M2: negative values preserve sign bit") {
    test(new RequantFP8(cfgE5M2)) { dut =>
      initDut(dut)
      val cfg = dut.cfg
      // Row 0: all -1.0  →  sign bit should be 1
      // Row 1: all +1.0  →  sign bit should be 0
      val block = Seq(
        Seq.fill(cfg.blockSize)(-1.0f),
        Seq.fill(cfg.blockSize)(1.0f),
        Seq.fill(cfg.blockSize)(-1.0f),
        Seq.fill(cfg.blockSize)(1.0f)
      )

      driveBlock(dut, block)
      dut.io.valid_out.expect(true.B)

      val fp8Out = dut.io.elem_out.peek().litValue

      for (col <- 0 until cfg.blockSize) {
        val hwNeg = extractFP8(fp8Out, cfg.tileRows, cfg.blockSize, 0, col)
        val hwPos = extractFP8(fp8Out, cfg.tileRows, cfg.blockSize, 1, col)

        assert((hwNeg & 0x80) != 0,
          f"row0 col$col: sign bit should be 1, got 0x$hwNeg%02X")
        assert((hwPos & 0x80) == 0,
          f"row1 col$col: sign bit should be 0, got 0x$hwPos%02X")
        assert((hwNeg & 0x7F) == (hwPos & 0x7F),
          f"magnitude mismatch: neg=0x$hwNeg%02X pos=0x$hwPos%02X")
      }
    }
  }

  // =========================================================================
  // Test 6: underflow and saturation
  // =========================================================================
  test("RequantFP8 E5M2: underflow → 0, overflow → max-normal") {
    test(new RequantFP8(cfgE5M2)) { dut =>
      initDut(dut)
      val cfg = dut.cfg
      // Huge: 2^125 (fp32 biased exp=252), very near E5M2 representable maximum.
      // Tiny: 2^-126 (minimum normal FP32 — tiny relative to huge).
      val huge = intBitsToFloat(0x7E800000)   // fp32 biased exp = 253
      val tiny = intBitsToFloat(0x00800000)   // fp32 biased exp = 1
      val half = cfg.blockSize / 2
      // Rows: first half huge, second half tiny
      val block = Seq.fill(cfg.tileRows)(
        Seq.fill(half)(huge) ++ Seq.fill(half)(tiny)
      )
      val (expScales, expFP8) = swRequantBlock(block, cfg.outputType)

      driveBlock(dut, block)
      dut.io.valid_out.expect(true.B)

      val fp8Out = dut.io.elem_out.peek().litValue

      for (row <- 0 until cfg.tileRows) {
        // Huge values: compare against SW model (may saturate to max-normal)
        for (col <- 0 until half) {
          val hw = extractFP8(fp8Out, cfg.tileRows, cfg.blockSize, row, col)
          val sw = expFP8(row)(col)
          assert(hw == sw,
            f"huge[$row][$col]: hw=0x$hw%02X sw=0x$sw%02X")
        }
        // Tiny values: must underflow to 0
        for (col <- half until cfg.blockSize) {
          val hw = extractFP8(fp8Out, cfg.tileRows, cfg.blockSize, row, col)
          assert(hw == 0x00,
            f"tiny[$row][$col]=0x$hw%02X, expected 0x00 (underflow)")
        }
      }
    }
  }

  // =========================================================================
  // Test 7: second consecutive block is fully independent (counter wraps)
  // =========================================================================
  test("RequantFP8 E5M2: consecutive blocks are independent") {
    test(new RequantFP8(cfgE5M2)) { dut =>
      initDut(dut)
      val cfg    = dut.cfg
      val block1 = Seq.fill(cfg.tileRows)(Seq.fill(cfg.blockSize)(1.0f))
      val block2 = Seq.fill(cfg.tileRows)(Seq.fill(cfg.blockSize)(2.0f))

      // Block 1: 1.0  → shared_scale = 127 − emax(15) = 112
      driveBlock(dut, block1)
      dut.io.valid_out.expect(true.B)
      val sc1 = extractScale(dut.io.shared_scale_out.peek().litValue, cfg.tileRows, 0)
      assert(sc1 == 112, s"block1 scale=$sc1, expected 112")

      // Block 2: 2.0  → shared_scale = 128 − emax(15) = 113
      driveBlock(dut, block2)
      dut.io.valid_out.expect(true.B)
      val sc2 = extractScale(dut.io.shared_scale_out.peek().litValue, cfg.tileRows, 0)
      assert(sc2 == 113, s"block2 scale=$sc2, expected 113")
    }
  }

  // =========================================================================
  // Test 8: valid_in gaps — block fills correctly with non-contiguous pulses
  // =========================================================================
  test("RequantFP8 E5M2: gaps between valid_in pulses do not corrupt buffer") {
    test(new RequantFP8(cfgE5M2)) { dut =>
      initDut(dut)
      val cfg   = dut.cfg
      val block = Seq.fill(cfg.tileRows)(Seq.fill(cfg.blockSize)(1.0f))
      val (expScales, expFP8) = swRequantBlock(block, cfg.outputType)

      // Drive each batch with 2 idle cycles between pulses
      for (b <- 0 until cfg.batchesPerBlock) {
        val slice = block.map(row =>
          row.slice(b * cfg.tileCols, (b + 1) * cfg.tileCols))
        dut.io.fp32_in.poke(packFP32(cfg.tileRows, cfg.tileCols, slice).U)
        dut.io.valid_in.poke(true.B)
        dut.clock.step()
        dut.io.valid_in.poke(false.B)
        // valid_out is a one-cycle pulse: RegNext(blockDone) fires on the
        // same edge as the last valid_in, so check it immediately after.
        if (b == cfg.batchesPerBlock - 1) dut.io.valid_out.expect(true.B)
        // Two idle cycles between batches
        dut.clock.step()
        dut.clock.step()
      }

      val scaleOut = dut.io.shared_scale_out.peek().litValue
      val fp8Out   = dut.io.elem_out.peek().litValue

      for (row <- 0 until cfg.tileRows) {
        val hwSc = extractScale(scaleOut, cfg.tileRows, row)
        assert(hwSc == expScales(row), s"row $row scale: hw=$hwSc sw=${expScales(row)}")
        for (col <- 0 until cfg.blockSize) {
          val hw = extractFP8(fp8Out, cfg.tileRows, cfg.blockSize, row, col)
          val sw = expFP8(row)(col)
          assert(hw == sw, f"fp8[$row][$col]: hw=0x$hw%02X sw=0x$sw%02X")
        }
      }
    }
  }

  // =========================================================================
  // Test 9 & 10: randomized SW vs HW — E5M2 and E4M3
  // =========================================================================
  def randomizedTest(cfg: RequantConfig, seed: Long, nBlocks: Int): Unit = {
    val rng = new Random(seed)
    // Random normal FP32 in [-16, 16] — avoids denormals and extremes
    def randFP32(): Float = (rng.nextFloat() * 32.0f - 16.0f)

    test(new RequantFP8(cfg)) { dut =>
      initDut(dut)
      for (blockIdx <- 0 until nBlocks) {
        val block = Seq.fill(cfg.tileRows)(Seq.fill(cfg.blockSize)(randFP32()))
        val (expScales, expFP8) = swRequantBlock(block, cfg.outputType)

        driveBlock(dut, block)
        dut.io.valid_out.expect(true.B, s"block $blockIdx: valid_out not asserted")

        val scaleOut = dut.io.shared_scale_out.peek().litValue
        val fp8Out   = dut.io.elem_out.peek().litValue

        for (row <- 0 until cfg.tileRows) {
          val hwSc = extractScale(scaleOut, cfg.tileRows, row)
          assert(hwSc == expScales(row),
            s"block$blockIdx row$row scale: hw=$hwSc sw=${expScales(row)}")

          for (col <- 0 until cfg.blockSize) {
            val hw = extractFP8(fp8Out, cfg.tileRows, cfg.blockSize, row, col)
            val sw = expFP8(row)(col)
            assert(hw == sw,
              f"block$blockIdx fp8[$row][$col]: hw=0x$hw%02X sw=0x$sw%02X " +
              f"(input=${block(row)(col)}%.4f scale=${expScales(row)})")
          }
        }
      }
    }
  }

  test("RequantFP8 E5M2: randomized 20-block SW vs HW") {
    randomizedTest(cfgE5M2, seed = 1234L, nBlocks = 20)
  }

  test("RequantFP8 E4M3: randomized 20-block SW vs HW") {
    randomizedTest(cfgE4M3, seed = 5678L, nBlocks = 20)
  }

  // =========================================================================
  // Test 11: blockSize=32 with 8x8 tile, E4M3
  // =========================================================================
  test("RequantFP8 E4M3: blockSize=32, 8x8 tile, 4 random blocks") {
    val cfg = RequantConfig(32, 4, 8, MXFormats.E4M3)  // B = 4 batches
    randomizedTest(cfg, seed = 9999L, nBlocks = 4)
  }

  // =========================================================================
  // ExMy scale tests — shared randomized driver
  // =========================================================================

  /**
   * Randomized SW-vs-HW test for an ExMy scale config.
   * Uses swRequantBlockExMy as the golden model.
   */
  def randomizedTestExMy(cfg: RequantConfig, seed: Long, nBlocks: Int): Unit = {
    require(cfg.scaleType.mantScaleWidth > 0, "use randomizedTest for UE8M0")
    val rng = new Random(seed)
    def randFP32(): Float = rng.nextFloat() * 32.0f - 16.0f

    test(new RequantFP8(cfg)) { dut =>
      initDut(dut)
      for (blockIdx <- 0 until nBlocks) {
        val block = Seq.fill(cfg.tileRows)(Seq.fill(cfg.blockSize)(randFP32()))
        val (expScales, expFP8) = swRequantBlockExMy(block, cfg.outputType, cfg.scaleType)

        driveBlock(dut, block)
        dut.io.valid_out.expect(true.B, s"block $blockIdx: valid_out not asserted")

        val scaleOut = dut.io.shared_scale_out.peek().litValue
        val fp8Out   = dut.io.elem_out.peek().litValue

        for (row <- 0 until cfg.tileRows) {
          val hwSc = extractScale(scaleOut, cfg.tileRows, row)
          assert(hwSc == expScales(row),
            s"block$blockIdx row$row scale: hw=0x${hwSc.toHexString} sw=0x${expScales(row).toHexString}")

          for (col <- 0 until cfg.blockSize) {
            val hw = extractFP8(fp8Out, cfg.tileRows, cfg.blockSize, row, col)
            val sw = expFP8(row)(col)
            assert(hw == sw,
              f"block$blockIdx fp8[$row][$col]: hw=0x$hw%02X sw=0x$sw%02X " +
              f"(input=${block(row)(col)}%.4f scale=0x${expScales(row).toHexString})")
          }
        }
      }
    }
  }

  // =========================================================================
  // Test 12: E4M3 / UE7M1 — block of 1.0; NVFP4 ceil scale = 1.5·2^−9 (0x6D),
  // each fp8 element lands at ≈ 341 → E4M3 (15, 3) = 0x7B.
  // Verified against the SW NVFP4 golden model.
  // =========================================================================
  test("RequantFP8 E4M3/UE7M1: block of 1.0 — NVFP4 ceil scale → 0x7B element") {
    val cfg   = RequantConfig(16, 4, 4, MXFormats.E4M3, ScaleFormats.UE7M1)
    val block = Seq.fill(cfg.tileRows)(Seq.fill(cfg.blockSize)(1.0f))
    val (expScales, expFP8) = swRequantBlockExMy(block, cfg.outputType, cfg.scaleType)

    test(new RequantFP8(cfg)) { dut =>
      initDut(dut)
      driveBlock(dut, block)
      dut.io.valid_out.expect(true.B)

      val scaleOut = dut.io.shared_scale_out.peek().litValue
      val fp8Out   = dut.io.elem_out.peek().litValue

      for (row <- 0 until cfg.tileRows) {
        val hwSc = extractScale(scaleOut, cfg.tileRows, row)
        assert(hwSc == expScales(row),
          s"row $row scale: hw=0x${hwSc.toHexString} sw=0x${expScales(row).toHexString}")
        for (col <- 0 until cfg.blockSize) {
          val hw = extractFP8(fp8Out, cfg.tileRows, cfg.blockSize, row, col)
          val sw = expFP8(row)(col)
          assert(hw == sw, f"fp8[$row][$col]: hw=0x$hw%02X sw=0x$sw%02X")
        }
      }
    }
  }

  // =========================================================================
  // Test 13: E4M3 / UE7M1 — mixed values [1.0, 1.5, −1.5, 0.5]
  // =========================================================================
  test("RequantFP8 E4M3/UE7M1: mixed [1.0, 1.5, -1.5, 0.5] block") {
    val cfg = RequantConfig(16, 4, 4, MXFormats.E4M3, ScaleFormats.UE7M1)
    val vals = Seq(1.0f, 1.5f, -1.5f, 0.5f, 1.0f, -0.5f, 0.75f, -0.75f,
                   1.25f, -1.25f, 0.25f, -0.25f, 1.0f, -1.0f, 0.5f, -0.5f)
    val block = Seq.fill(cfg.tileRows)(vals)
    val (expScales, expFP8) = swRequantBlockExMy(block, cfg.outputType, cfg.scaleType)

    test(new RequantFP8(cfg)) { dut =>
      initDut(dut)
      driveBlock(dut, block)
      dut.io.valid_out.expect(true.B)

      val scaleOut = dut.io.shared_scale_out.peek().litValue
      val fp8Out   = dut.io.elem_out.peek().litValue

      for (row <- 0 until cfg.tileRows) {
        val hwSc = extractScale(scaleOut, cfg.tileRows, row)
        assert(hwSc == expScales(row),
          s"row $row scale: hw=0x${hwSc.toHexString} sw=0x${expScales(row).toHexString}")
        for (col <- 0 until cfg.blockSize) {
          val hw = extractFP8(fp8Out, cfg.tileRows, cfg.blockSize, row, col)
          val sw = expFP8(row)(col)
          assert(hw == sw, f"fp8[$row][$col]: hw=0x$hw%02X sw=0x$sw%02X")
        }
      }
    }
  }

  // =========================================================================
  // Test 14: randomized E4M3 / UE7M1
  // =========================================================================
  test("RequantFP8 E4M3/UE7M1: randomized 20-block SW vs HW") {
    randomizedTestExMy(
      RequantConfig(16, 4, 4, MXFormats.E4M3, ScaleFormats.UE7M1),
      seed = 1111L, nBlocks = 20
    )
  }

  // =========================================================================
  // Test 15: randomized E5M2 / UE6M2
  // =========================================================================
  test("RequantFP8 E5M2/UE6M2: randomized 20-block SW vs HW") {
    randomizedTestExMy(
      RequantConfig(16, 4, 4, MXFormats.E5M2, ScaleFormats.UE6M2),
      seed = 2222L, nBlocks = 20
    )
  }

  // =========================================================================
  // Test 16: randomized E4M3 / UE4M4
  // =========================================================================
  test("RequantFP8 E4M3/UE4M4: randomized 20-block SW vs HW") {
    randomizedTestExMy(
      RequantConfig(16, 4, 4, MXFormats.E4M3, ScaleFormats.UE4M4),
      seed = 3333L, nBlocks = 20
    )
  }

  // =========================================================================
  // Test 17: E5M2 / UE7M1, blockSize=32
  // =========================================================================
  test("RequantFP8 E5M2/UE7M1: blockSize=32 randomized 10 blocks") {
    randomizedTestExMy(
      RequantConfig(32, 4, 4, MXFormats.E5M2, ScaleFormats.UE7M1),
      seed = 4444L, nBlocks = 10
    )
  }

  // =========================================================================
  // Test 18: Python quantize_mx_v6 ↔ Chisel ExMy bit-true cross-check.
  //
  // Loads `src/test/resources/requant_v6_vectors.txt` (generated by
  // hw/chisel_acc/gen_requant_v6_vectors.py) and feeds each block to the
  // hardware, asserting that the encoded scale_raw and elem_raw match
  // Python's `quantize_mx_v6` (NVFP4-style ceil) byte-for-byte.
  //
  // Regenerate the vectors with: `python3 gen_requant_v6_vectors.py` from
  // hw/chisel_acc/.
  // =========================================================================
  test("RequantFP8 ExMy: quantize_mx_v6 vectors bit-true vs HW") {
    val resource = getClass.getResource("/requant_v6_vectors.txt")
    assert(resource != null, "missing src/test/resources/requant_v6_vectors.txt — " +
      "run `python3 gen_requant_v6_vectors.py` from hw/chisel_acc/")
    val src   = scala.io.Source.fromURL(resource)
    val lines = try src.getLines().toIndexedSeq finally src.close()

    val outputTypeByName = Map(
      "E5M2" -> MXFormats.E5M2, "E4M3" -> MXFormats.E4M3,
      "E3M2" -> MXFormats.E3M2, "E2M3" -> MXFormats.E2M3,
    )
    val scaleTypeByName = Map(
      "UE8M0" -> ScaleFormats.UE8M0, "UE7M1" -> ScaleFormats.UE7M1,
      "UE6M2" -> ScaleFormats.UE6M2, "UE5M3" -> ScaleFormats.UE5M3,
      "UE4M4" -> ScaleFormats.UE4M4,
    )

    case class Block(inputs: Seq[Float], scales: Seq[Int], elems: Seq[Int])
    case class Group(name: String, cfg: RequantConfig, blocks: Seq[Block])

    // Parse: skip blanks/comments. Each CONFIG header begins a group; INPUT/
    // SCALE/ELEM triples follow until the next CONFIG or EOF.
    var i = 0
    val groups = scala.collection.mutable.ArrayBuffer.empty[Group]
    while (i < lines.length) {
      val ln = lines(i).trim
      if (ln.isEmpty || ln.startsWith("#")) {
        i += 1
      } else if (ln.startsWith("CONFIG ")) {
        val tok = ln.split("\\s+")
        // CONFIG <name> <tileRows> <blockSize> <outType> <scaleType> <nBlocks>
        val name      = tok(1)
        val tileRows  = tok(2).toInt
        val blockSize = tok(3).toInt
        val outT      = outputTypeByName(tok(4))
        val scT       = scaleTypeByName(tok(5))
        // tileCols pinned to tileRows for these vectors (4x4).
        val tileCols  = tileRows
        val cfg = RequantConfig(blockSize, tileRows, tileCols, outT, scT)
        val nBlocks   = tok(6).toInt
        i += 1
        val bs = scala.collection.mutable.ArrayBuffer.empty[Block]
        for (_ <- 0 until nBlocks) {
          while (i < lines.length && (lines(i).trim.isEmpty || lines(i).trim.startsWith("#"))) i += 1
          val inputLine = lines(i).trim; i += 1
          val scaleLine = lines(i).trim; i += 1
          val elemLine  = lines(i).trim; i += 1
          assert(inputLine.startsWith("INPUT "), s"expected INPUT line, got: $inputLine")
          assert(scaleLine.startsWith("SCALE "), s"expected SCALE line, got: $scaleLine")
          assert(elemLine.startsWith("ELEM "),   s"expected ELEM line, got: $elemLine")
          val inputs = inputLine.stripPrefix("INPUT ").split("\\s+").map(h =>
            intBitsToFloat(java.lang.Long.parseLong(h, 16).toInt))
          val scales = scaleLine.stripPrefix("SCALE ").split("\\s+").map(h => Integer.parseInt(h, 16))
          val elems  = elemLine.stripPrefix("ELEM").trim.split("\\s+").map(h => Integer.parseInt(h, 16))
          assert(inputs.length == tileRows * blockSize, s"$name: input count")
          assert(scales.length == tileRows,             s"$name: scale count")
          assert(elems.length  == tileRows * blockSize, s"$name: elem count")
          bs += Block(inputs.toSeq, scales.toSeq, elems.toSeq)
        }
        groups += Group(name, cfg, bs.toSeq)
      } else {
        i += 1   // skip unrecognized lines
      }
    }

    assert(groups.nonEmpty, "no CONFIG groups parsed from requant_v6_vectors.txt")

    for (g <- groups) {
      val cfg = g.cfg
      test(new RequantFP8(cfg)) { dut =>
        initDut(dut)
        for ((blk, blkIdx) <- g.blocks.zipWithIndex) {
          val block = (0 until cfg.tileRows).map { r =>
            (0 until cfg.blockSize).map(c => blk.inputs(r * cfg.blockSize + c))
          }
          driveBlock(dut, block)
          dut.io.valid_out.expect(true.B)
          val scaleOut = dut.io.shared_scale_out.peek().litValue
          val fp8Out   = dut.io.elem_out.peek().litValue
          for (row <- 0 until cfg.tileRows) {
            val hwSc = extractScale(scaleOut, cfg.tileRows, row)
            assert(hwSc == blk.scales(row),
              f"${g.name} blk$blkIdx row$row scale: hw=0x$hwSc%02X py=0x${blk.scales(row)}%02X")
            for (col <- 0 until cfg.blockSize) {
              val hw = extractFP8(fp8Out, cfg.tileRows, cfg.blockSize, row, col)
              val py = blk.elems(row * cfg.blockSize + col)
              assert(hw == py,
                f"${g.name} blk$blkIdx fp8[$row][$col]: hw=0x$hw%02X py=0x$py%02X " +
                f"(input=${blk.inputs(row * cfg.blockSize + col)}%.4f scale=0x${blk.scales(row)}%02X)")
            }
          }
        }
      }
    }
  }
}
