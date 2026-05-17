package mx.requant

import chisel3._
import chiseltest._
import org.scalatest.funsuite.AnyFunSuite
import scala.util.Random
import java.lang.Float.{floatToRawIntBits, intBitsToFloat}
import mx.mac.{ScaleType, ScaleFormats}

class RequantINT8Test extends AnyFunSuite with ChiselScalatestTester {

  // =========================================================================
  // Software golden model — UE8M0 (legacy MXINT8)
  // =========================================================================

  def swSharedScaleINT8_UE8M0(fp32s: Seq[Float]): Int =
    fp32s.map(f => (floatToRawIntBits(f) >>> 23) & 0xFF).max

  /**
   * UE8M0 FP32 → MXINT8 (matches current barrel-shift hardware).
   *   k = fp32_exp − shared_scale + 6   (≤ 6)
   *   mag = RNE-round(fp32_full_mant × 2^(k − 23)),
   *         clipped to 127 on overflow; result is 8-bit two's complement.
   */
  def swFP32toInt8_UE8M0(f: Float, sharedScale: Int): Int = {
    val bits    = floatToRawIntBits(f)
    val sign    = (bits >>> 31) & 1
    val fp32Exp = (bits >>> 23) & 0xFF
    val fp32Man = bits & 0x7FFFFF

    if (fp32Exp == 0) return 0

    val k = fp32Exp - sharedScale + 6
    val FRAC = 24
    val fullMant = (1 << 23) | fp32Man                       // 24 bits
    val mantExt  = fullMant.toLong << FRAC                   // 48 bits
    val shiftAmt = 23 - k
    val shifted  = if (shiftAmt >= 64) 0L else mantExt >>> shiftAmt

    val mag7      = ((shifted >>> FRAC) & 0x7F).toInt
    val guardBit  = ((shifted >>> (FRAC - 1)) & 1L).toInt
    val stickyBit = (shifted & ((1L << (FRAC - 1)) - 1)) != 0L

    val roundUp     = guardBit == 1 && ((mag7 & 1) == 1 || stickyBit)
    val mag8        = mag7 + (if (roundUp) 1 else 0)
    val magOverflow = (mag8 >>> 7) & 1
    val mag         = if (magOverflow == 1) 127 else mag8 & 0x7F

    if (sign == 1) (256 - mag) & 0xFF else mag
  }

  // =========================================================================
  // Software golden model — ExMy (NVFP4-style)
  // =========================================================================

  // MXINT8 in 1.M × 2^E form: 1.984 = 1.111111 × 2^0 → M=6, E=0, signifInt=127.
  private val mxint8MantBits         = 6
  private val mxint8Emax             = 0
  private val mxint8MaxNormSignifInt = 127

  /** ExMy shared scale = ceil-encode(max_abs / (127/64)). */
  def swSharedScaleINT8_ExMy(fp32s: Seq[Float], st: ScaleType): Int = {
    val M            = st.mantScaleWidth
    val E            = st.expScaleWidth
    val scaleBias    = st.bias
    val maxScaleExpV = (1 << E) - 1

    val maxBits      = fp32s.map(f => floatToRawIntBits(f) & 0x7FFFFFFF).max
    val maxBiasedExp = (maxBits >>> 23) & 0xFF
    val maxMant23    = maxBits & 0x7FFFFF
    val maxIsZero    = maxBiasedExp == 0

    val EXTRA = M + 3
    val IMPL  = 23 - mxint8MantBits + EXTRA
    val fullMant = if (maxBiasedExp != 0) (1 << 23) | maxMant23 else 0
    val qNum     = fullMant.toLong << EXTRA
    val qInt     = qNum / mxint8MaxNormSignifInt
    val qRem     = qNum % mxint8MaxNormSignifInt

    val qGeq1 = ((qInt >>> IMPL) & 1L) == 1L

    val mantRaw =
      if (qGeq1) ((qInt >>> (IMPL - M)) & ((1L << M) - 1)).toInt
      else       ((qInt >>> (IMPL - 1 - M)) & ((1L << M) - 1)).toInt
    val residMask =
      if (qGeq1) (1L << (IMPL - M))     - 1L
      else       (1L << (IMPL - 1 - M)) - 1L
    val ceilInc = if (qRem != 0L || (qInt & residMask) != 0L) 1 else 0

    val mantSum      = mantRaw + ceilInc
    val mantOverflow = mantSum >>> M
    val mantNormal   = mantSum & ((1 << M) - 1)
    val normAdj      = if (qGeq1) 0 else -1
    val scaleBiasedExp = (maxBiasedExp - 127 - mxint8Emax) + normAdj + mantOverflow + scaleBias

    if (maxIsZero) 0
    else if (scaleBiasedExp >= maxScaleExpV) (maxScaleExpV << M) | ((1 << M) - 1)
    else if (scaleBiasedExp <= 0)            (0 << M) | 1
    else                                     ((scaleBiasedExp & maxScaleExpV) << M) | mantNormal
  }

  /**
   * ExMy FP32 → MXINT8 (mirrors FP32ToMXINT8 hardware ExMy path).
   *   q_int = (1.fp32_mant) × 2^EXTRA / (1.scale_mant)
   *   shift_amt = IMPL − k,  k = fp32_unbiased − scale_unbiased + 6
   *   mag = q_int >> shift_amt, low 7 bits, RNE-rounded; saturate to 127.
   * Subnormal scales are renormalized so the (1.scale_mant) divisor stays
   * in its standard form.
   */
  def swFP32toInt8_ExMy(f: Float, sharedScale: Int, st: ScaleType): Int = {
    val bits    = floatToRawIntBits(f)
    val sign    = (bits >>> 31) & 1
    val fp32Exp = (bits >>> 23) & 0xFF
    val fp32Man = bits & 0x7FFFFF
    if (fp32Exp == 0) return 0

    val M         = st.mantScaleWidth
    val E         = st.expScaleWidth
    val scaleBias = st.bias
    val correction = scaleBias - 127 + 6

    val scaleBiasedExpRaw = (sharedScale >>> M) & ((1 << E) - 1)
    val scaleMantRaw      = sharedScale & ((1 << M) - 1)
    val isZeroScale       = scaleBiasedExpRaw == 0 && scaleMantRaw == 0
    val isSubnormScale    = scaleBiasedExpRaw == 0 && scaleMantRaw != 0
    if (isZeroScale) return 0

    // Subnormal renormalization.
    val (effBiasedExp, scaleFullMant) =
      if (isSubnormScale) {
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

    val M_elem = mxint8MantBits           // 6
    val EXTRA  = M_elem + 3               // 9
    val IMPL   = 23 - M + EXTRA

    val fullMant = (1 << 23) | fp32Man
    val qNum     = fullMant.toLong << EXTRA
    val qInt     = qNum / scaleFullMant
    val qRem     = qNum % scaleFullMant

    val k         = fp32Exp - effBiasedExp + correction
    val shiftAmt  = IMPL - k

    // See RequantINT8.scala — MAX_SHIFT_INT8 = IMPL + 2 keeps the guard
    // bit above q_int's MSB at the clamp boundary so very small values
    // don't spuriously round up to 1.
    val MAX_SHIFT_INT8 = IMPL + 2
    val saturateNeg = shiftAmt < 0
    val shiftClamp  =
      if (saturateNeg) 0
      else if (shiftAmt > MAX_SHIFT_INT8) MAX_SHIFT_INT8
      else shiftAmt

    val mag7Pre   = ((qInt >>> shiftClamp) & 0x7FL).toInt
    // High bits above the 7-bit magnitude → overflow.
    val highBits  = (qInt >>> (shiftClamp + 7)) != 0L
    val guardInt8 = if (shiftClamp == 0) 0 else ((qInt >>> (shiftClamp - 1)) & 1L).toInt
    val stickyMask =
      if (shiftClamp <= 1) 0L else (1L << (shiftClamp - 1)) - 1L
    val stickyLow = (qInt & stickyMask) != 0L
    val stickyInt8 = stickyLow || qRem != 0L

    val roundUpInt8 = guardInt8 == 1 && ((mag7Pre & 1) == 1 || stickyInt8)
    val magSum  = mag7Pre + (if (roundUpInt8) 1 else 0)
    val magOverflow = (magSum >>> 7) & 1
    val magUnsat = magSum & 0x7F

    val saturate = saturateNeg || highBits || magOverflow == 1
    val mag      = if (saturate) 127 else magUnsat

    if (sign == 1) (256 - mag) & 0xFF else mag
  }

  /** Compose a full block worth of expected (scale, int8) tuples. */
  def swRequantBlockINT8(
    block: Seq[Seq[Float]],
    st: ScaleType
  ): (Seq[Int], Seq[Seq[Int]]) = {
    val scales =
      if (st.mantScaleWidth == 0) block.map(swSharedScaleINT8_UE8M0(_))
      else                        block.map(swSharedScaleINT8_ExMy(_, st))
    val int8s = block.zip(scales).map { case (row, sc) =>
      if (st.mantScaleWidth == 0) row.map(f => swFP32toInt8_UE8M0(f, sc))
      else                        row.map(f => swFP32toInt8_ExMy(f, sc, st))
    }
    (scales, int8s)
  }

  // =========================================================================
  // DUT helpers (mirror RequantFP8Test)
  // =========================================================================

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

  // Row 0 packed at LSB so little-endian memory stores [row0, row1, …]
  // in ascending byte address (mirrors RequantFP8Test).
  def extractScale(packed: BigInt, tileRows: Int, row: Int): Int =
    ((packed >> (row * 8)) & 0xFF).toInt

  def extractInt8(
    packed: BigInt, tileRows: Int, blockSize: Int, row: Int, col: Int
  ): Int = {
    val k     = row * blockSize + col
    val shift = k * 8
    ((packed >> shift) & 0xFF).toInt
  }

  /** Drive reset=1 so active-low async reset is de-asserted. */
  def initDut(dut: RequantINT8): Unit = {
    dut.reset.poke(true.B)
    dut.io.valid_in.poke(false.B)
    dut.io.fp32_in.poke(0.U)
    dut.clock.step(2)
  }

  def driveBatch(dut: RequantINT8, batchData: Seq[Seq[Float]]): Unit = {
    val cfg = dut.cfg
    dut.io.fp32_in.poke(packFP32(cfg.tileRows, cfg.tileCols, batchData).U)
    dut.io.valid_in.poke(true.B)
    dut.clock.step()
    dut.io.valid_in.poke(false.B)
  }

  def driveBlock(dut: RequantINT8, block: Seq[Seq[Float]]): Unit = {
    val cfg = dut.cfg
    for (b <- 0 until cfg.batchesPerBlock) {
      val slice = block.map(row =>
        row.slice(b * cfg.tileCols, (b + 1) * cfg.tileCols))
      driveBatch(dut, slice)
    }
  }

  // =========================================================================
  // Regression test: existing UE8M0 behaviour (block of 1.0)
  // =========================================================================
  test("RequantINT8 UE8M0: block of 1.0 → shared_scale=127, int8=64") {
    val cfg = RequantINT8Config(blockSize = 16, tileRows = 4, tileCols = 4)
    test(new RequantINT8(cfg)) { dut =>
      initDut(dut)
      val block = Seq.fill(cfg.tileRows)(Seq.fill(cfg.blockSize)(1.0f))
      driveBlock(dut, block)
      dut.io.valid_out.expect(true.B)

      val scaleOut = dut.io.shared_scale_out.peek().litValue
      val int8Out  = dut.io.int8_out.peek().litValue
      for (row <- 0 until cfg.tileRows) {
        assert(extractScale(scaleOut, cfg.tileRows, row) == 127,
          s"row $row UE8M0 scale should be 127 for max_abs=1.0")
        for (col <- 0 until cfg.blockSize)
          assert(extractInt8(int8Out, cfg.tileRows, cfg.blockSize, row, col) == 64,
            s"row=$row col=$col int8 should be 64 (= 1.0 × 64)")
      }
    }
  }

  // =========================================================================
  // Regression: zero block → scale=0 and int8=0  (UE8M0)
  // =========================================================================
  test("RequantINT8 UE8M0: zero block → scale=0, int8=0") {
    val cfg = RequantINT8Config(blockSize = 16, tileRows = 4, tileCols = 4)
    test(new RequantINT8(cfg)) { dut =>
      initDut(dut)
      val block = Seq.fill(cfg.tileRows)(Seq.fill(cfg.blockSize)(0.0f))
      driveBlock(dut, block)
      dut.io.valid_out.expect(true.B)

      val scaleOut = dut.io.shared_scale_out.peek().litValue
      val int8Out  = dut.io.int8_out.peek().litValue
      for (row <- 0 until cfg.tileRows) {
        assert(extractScale(scaleOut, cfg.tileRows, row) == 0)
        for (col <- 0 until cfg.blockSize)
          assert(extractInt8(int8Out, cfg.tileRows, cfg.blockSize, row, col) == 0)
      }
    }
  }

  // =========================================================================
  // Randomised SW vs HW for all scale variants
  // =========================================================================
  def randomizedTestINT8(cfg: RequantINT8Config, seed: Long, nBlocks: Int): Unit = {
    val rng = new Random(seed)
    val blocks = (0 until nBlocks).map { _ =>
      val scale = math.pow(2.0, rng.nextInt(20) - 10).toFloat
      Seq.fill(cfg.tileRows)(
        Seq.fill(cfg.blockSize)(((rng.nextGaussian().toFloat) * scale))
      )
    }

    test(new RequantINT8(cfg)) { dut =>
      initDut(dut)
      for ((block, blockIdx) <- blocks.zipWithIndex) {
        val (expScales, expInt8) = swRequantBlockINT8(block, cfg.scaleType)
        driveBlock(dut, block)
        dut.io.valid_out.expect(true.B)
        val scaleOut = dut.io.shared_scale_out.peek().litValue
        val int8Out  = dut.io.int8_out.peek().litValue
        for (row <- 0 until cfg.tileRows) {
          val hwSc = extractScale(scaleOut, cfg.tileRows, row)
          assert(hwSc == expScales(row),
            s"blk$blockIdx row$row scale: hw=0x${hwSc.toHexString} sw=0x${expScales(row).toHexString}")
          for (col <- 0 until cfg.blockSize) {
            val hw = extractInt8(int8Out, cfg.tileRows, cfg.blockSize, row, col)
            val sw = expInt8(row)(col)
            assert(hw == sw,
              f"blk$blockIdx int8[$row][$col]: hw=0x$hw%02X sw=0x$sw%02X " +
              f"(input=${block(row)(col)}%.4f scale=0x${expScales(row)}%02X)")
          }
        }
      }
    }
  }

  test("RequantINT8 UE8M0: randomized 10 blocks") {
    randomizedTestINT8(
      RequantINT8Config(blockSize = 16, tileRows = 4, tileCols = 4),
      seed = 10001L, nBlocks = 10
    )
  }

  test("RequantINT8 UE7M1: randomized 10 blocks") {
    randomizedTestINT8(
      RequantINT8Config(blockSize = 16, tileRows = 4, tileCols = 4, ScaleFormats.UE7M1),
      seed = 10002L, nBlocks = 10
    )
  }

  test("RequantINT8 UE6M2: randomized 10 blocks") {
    randomizedTestINT8(
      RequantINT8Config(blockSize = 16, tileRows = 4, tileCols = 4, ScaleFormats.UE6M2),
      seed = 10003L, nBlocks = 10
    )
  }

  test("RequantINT8 UE4M4: randomized 10 blocks") {
    randomizedTestINT8(
      RequantINT8Config(blockSize = 16, tileRows = 4, tileCols = 4, ScaleFormats.UE4M4),
      seed = 10004L, nBlocks = 10
    )
  }

  // =========================================================================
  // Python quantize_mx_v6 (mxint8) ↔ Chisel ExMy bit-true cross-check.
  // Regenerate vectors with: `python3 gen_requant_v6_int8_vectors.py` in hw/chisel_acc/.
  // =========================================================================
  test("RequantINT8 ExMy: quantize_mx_v6 (mxint8) vectors bit-true vs HW") {
    val resource = getClass.getResource("/requant_v6_int8_vectors.txt")
    assert(resource != null, "missing src/test/resources/requant_v6_int8_vectors.txt — " +
      "run `python3 gen_requant_v6_int8_vectors.py` from hw/chisel_acc/")
    val src   = scala.io.Source.fromURL(resource)
    val lines = try src.getLines().toIndexedSeq finally src.close()

    val scaleTypeByName = Map(
      "UE8M0" -> ScaleFormats.UE8M0, "UE7M1" -> ScaleFormats.UE7M1,
      "UE6M2" -> ScaleFormats.UE6M2, "UE5M3" -> ScaleFormats.UE5M3,
      "UE4M4" -> ScaleFormats.UE4M4,
    )

    case class Block(inputs: Seq[Float], scales: Seq[Int], elems: Seq[Int])
    case class Group(name: String, cfg: RequantINT8Config, blocks: Seq[Block])

    var i = 0
    val groups = scala.collection.mutable.ArrayBuffer.empty[Group]
    while (i < lines.length) {
      val ln = lines(i).trim
      if (ln.isEmpty || ln.startsWith("#")) {
        i += 1
      } else if (ln.startsWith("CONFIG ")) {
        val tok = ln.split("\\s+")
        // CONFIG <name> <tileRows> <blockSize> <outType=MXINT8> <scaleType> <nBlocks>
        val name      = tok(1)
        val tileRows  = tok(2).toInt
        val blockSize = tok(3).toInt
        assert(tok(4) == "MXINT8", s"expected outType=MXINT8 in INT8 vectors, got ${tok(4)}")
        val scT       = scaleTypeByName(tok(5))
        val tileCols  = tileRows
        val cfg       = RequantINT8Config(blockSize, tileRows, tileCols, scT)
        val nBlocks   = tok(6).toInt
        i += 1
        val bs = scala.collection.mutable.ArrayBuffer.empty[Block]
        for (_ <- 0 until nBlocks) {
          while (i < lines.length && (lines(i).trim.isEmpty || lines(i).trim.startsWith("#"))) i += 1
          val inputLine = lines(i).trim; i += 1
          val scaleLine = lines(i).trim; i += 1
          val elemLine  = lines(i).trim; i += 1
          assert(inputLine.startsWith("INPUT "), s"expected INPUT, got: $inputLine")
          assert(scaleLine.startsWith("SCALE "), s"expected SCALE, got: $scaleLine")
          assert(elemLine.startsWith("ELEM "),   s"expected ELEM,  got: $elemLine")
          val inputs = inputLine.stripPrefix("INPUT ").split("\\s+").map(h =>
            intBitsToFloat(java.lang.Long.parseLong(h, 16).toInt))
          val scales = scaleLine.stripPrefix("SCALE ").split("\\s+").map(h => Integer.parseInt(h, 16))
          val elems  = elemLine.stripPrefix("ELEM").trim.split("\\s+").map(h => Integer.parseInt(h, 16))
          assert(inputs.length == tileRows * blockSize)
          assert(scales.length == tileRows)
          assert(elems.length  == tileRows * blockSize)
          bs += Block(inputs.toSeq, scales.toSeq, elems.toSeq)
        }
        groups += Group(name, cfg, bs.toSeq)
      } else {
        i += 1
      }
    }
    assert(groups.nonEmpty, "no CONFIG groups parsed from requant_v6_int8_vectors.txt")

    for (g <- groups) {
      val cfg = g.cfg
      test(new RequantINT8(cfg)) { dut =>
        initDut(dut)
        for ((blk, blkIdx) <- g.blocks.zipWithIndex) {
          val block = (0 until cfg.tileRows).map { r =>
            (0 until cfg.blockSize).map(c => blk.inputs(r * cfg.blockSize + c))
          }
          driveBlock(dut, block)
          dut.io.valid_out.expect(true.B)
          val scaleOut = dut.io.shared_scale_out.peek().litValue
          val int8Out  = dut.io.int8_out.peek().litValue
          for (row <- 0 until cfg.tileRows) {
            val hwSc = extractScale(scaleOut, cfg.tileRows, row)
            assert(hwSc == blk.scales(row),
              f"${g.name} blk$blkIdx row$row scale: hw=0x$hwSc%02X py=0x${blk.scales(row)}%02X")
            for (col <- 0 until cfg.blockSize) {
              val hw = extractInt8(int8Out, cfg.tileRows, cfg.blockSize, row, col)
              val py = blk.elems(row * cfg.blockSize + col)
              assert(hw == py,
                f"${g.name} blk$blkIdx int8[$row][$col]: hw=0x$hw%02X py=0x$py%02X " +
                f"(input=${blk.inputs(row * cfg.blockSize + col)}%.4f scale=0x${blk.scales(row)}%02X)")
            }
          }
        }
      }
    }
  }
}
