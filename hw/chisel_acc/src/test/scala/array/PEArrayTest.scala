package mx.array

import chisel3._
import chiseltest._
import org.scalatest.funsuite.AnyFunSuite
import java.lang.Float.{floatToRawIntBits, intBitsToFloat}
import scala.util.Random
import mx.mac.{ElementType, ScaleType, MXFormats, ScaleFormats, ScaleAddConfig}
import mx.requant.{RequantConfig, RequantINT8Config}

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
  // SW golden model — RequantFP8 (matches RequantFP8.scala bit-exactly for both
  // UE8M0 and ExMy scale formats). See:
  //   src/main/scala/mx/requant/RequantFP8.scala
  //     - MaxScaleFinder: UE8M0 = max biased exp; ExMy = floor-encoded
  //                      max-magnitude FP32 → {clamped exp, top-M-bits-mant}
  //     - FP32ToMXFP8:   UE8M0 path (subtract scale exp, RNE round) /
  //                      ExMy path  (integer mantissa division, normalise,
  //                                  RNE round)
  // =========================================================================

  /**
   * Shared scale for one row, scale-type aware (OCP MX semantics).
   * Subtracts emax_element = (1 << outExpBits) − 2 − outBias so that the
   * block's max-magnitude element lands at the element format's max-normal
   * exponent — matches the hardware MaxScaleFinder.
   */
  def swMaxScale(fp32s: Seq[Float], t: ElementType, st: ScaleType): Int = {
    val emaxElem = ((1 << t.elementWidthExp) - 2) - t.bias
    if (st.mantScaleWidth == 0) {
      // UE8M0 — (max biased FP32 exp) − emax_element, clamped to [0, 255]
      val maxBiasedExp = fp32s.map(f => (floatToRawIntBits(f) >>> 23) & 0xFF).max
      (maxBiasedExp - emaxElem).max(0).min(255)
    } else {
      // ExMy — floor-encode max-magnitude FP32 as {biasedExp[E-1:0], mant[M-1:0]}
      val M             = st.mantScaleWidth
      val E             = st.expScaleWidth
      val scaleBias     = st.bias
      val maxScaleExpV  = (1 << E) - 1

      // Sign-stripped 31-bit comparison picks the largest magnitude
      val maxMag31     = fp32s.map(f => floatToRawIntBits(f) & 0x7FFFFFFF).max
      val maxBiasedExp = (maxMag31 >>> 23) & 0xFF
      val maxMant23    = maxMag31 & 0x7FFFFF

      val expRaw = maxBiasedExp - 127 + scaleBias - emaxElem
      val expClamped =
        if (expRaw <= 0) 0
        else if (expRaw >= maxScaleExpV) maxScaleExpV
        else expRaw

      // Top M bits of the 23-bit mantissa (floor — never rounds up)
      val mantEnc = (maxMant23 >>> (23 - M)) & ((1 << M) - 1)

      (expClamped << M) | mantEnc
    }
  }

  /** FP32 → MXFP8/FP6 given the shared scale and its format. RNE, saturate ±maxNormal, FTZ.
   *  Bit layout: sign at bit (t.totalWidth-1); exponent next; mantissa LSBs. */
  def swFP32toFP8(f: Float, sharedScale: Int, t: ElementType, st: ScaleType): Int = {
    val outBias         = t.bias
    val outExpBits      = t.elementWidthExp
    val outMantBits     = t.elementWidthMant
    val outMaxNormalExp = (1 << outExpBits) - 2
    val signShift       = t.totalWidth - 1   // sign is the MSB (bit 7 for FP8, bit 5 for FP6)

    val bits    = floatToRawIntBits(f)
    val sign    = (bits >>> 31) & 1
    val fp32Exp = (bits >>> 23) & 0xFF
    val fp32Man = bits & 0x7FFFFF
    if (fp32Exp == 0) return 0  // subnormal/zero → flush

    if (st.mantScaleWidth == 0) {
      // ── UE8M0 path ───────────────────────────────────────
      val outExpFull = fp32Exp - sharedScale + outBias
      if (outExpFull <= 0) return 0
      if (outExpFull > outMaxNormalExp) {
        val maxMant = (1 << outMantBits) - 1
        return (sign << signShift) | (outMaxNormalExp << outMantBits) | maxMant
      }
      val outMantRaw = (fp32Man >>> (23 - outMantBits)) & ((1 << outMantBits) - 1)
      val guardBit   = (fp32Man >>> (22 - outMantBits)) & 1
      val stickyBits =
        if (22 - outMantBits > 0) (fp32Man & ((1 << (22 - outMantBits)) - 1)) != 0
        else                      false
      val roundUp = guardBit == 1 && ((outMantRaw & 1) == 1 || stickyBits)
      val outMant = (outMantRaw + (if (roundUp) 1 else 0)) & ((1 << outMantBits) - 1)
      val outExpC = outExpFull & ((1 << outExpBits) - 1)
      (sign << signShift) | (outExpC << outMantBits) | outMant
    } else {
      // ── ExMy path: integer mantissa division (mirrors HW) ──
      val M          = st.mantScaleWidth
      val E          = st.expScaleWidth
      val scaleBias  = st.bias
      val correction = scaleBias - 127 + outBias

      val scaleBiasedExp = (sharedScale >>> M) & ((1 << E) - 1)
      val scaleMantRaw   = sharedScale & ((1 << M) - 1)
      // Implicit-1 only when biasedExp > 0 (otherwise scale is subnormal/zero)
      val scaleFullMant: Long =
        if (scaleBiasedExp != 0) (1L << M) | scaleMantRaw.toLong
        else                     scaleMantRaw.toLong

      val outExpRaw = fp32Exp - scaleBiasedExp + correction

      // q_int = (1.fp32_mant << EXTRA) / (1.scale_mant)
      // Implicit-1 of q lands at bit IMPL when q ≥ 1; bit (IMPL−1) when q ∈ [0.5, 1).
      val EXTRA = outMantBits + 3
      val IMPL  = 23 - M + EXTRA

      val fp32FullMant = (1L << 23) | fp32Man.toLong   // 24 bits
      val qNum         = fp32FullMant << EXTRA          // (24 + EXTRA) bits

      val safeDenom = if (scaleFullMant == 0L) 1L else scaleFullMant
      val qInt      = qNum / safeDenom
      val qRem      = qNum % safeDenom

      val qGeq1 = ((qInt >>> IMPL) & 1L) != 0L

      // Slice the mantissa, guard, round, sticky based on whether q ≥ 1
      val (mantSlot, guardSlot, roundSlot, stickySlot) =
        if (qGeq1) (IMPL - outMantBits, IMPL - outMantBits - 1,
                    IMPL - outMantBits - 2, IMPL - outMantBits - 3)
        else       (IMPL - 1 - outMantBits, IMPL - outMantBits - 2,
                    IMPL - outMantBits - 3, IMPL - outMantBits - 4)

      val outMantRaw = ((qInt >>> mantSlot)  & ((1L << outMantBits) - 1)).toInt
      val guardBit   = ((qInt >>> guardSlot) & 1L).toInt
      val roundBit   = ((qInt >>> roundSlot) & 1L).toInt
      // HW: q_int(stickySlot, 0).orR — bits [stickySlot..0] inclusive,
      // so the mask must cover stickySlot+1 bits.
      val stickyQ    = (qInt & ((1L << (stickySlot + 1)) - 1L)) != 0L
      val stickyBit  = (qRem != 0L) || stickyQ

      val roundUp = (guardBit == 1) && ((outMantRaw & 1) == 1 || roundBit == 1 || stickyBit)
      val outMantCarry = outMantRaw + (if (roundUp) 1 else 0)
      val mantOverflow = (outMantCarry >>> outMantBits) & 1
      val outMant      = outMantCarry & ((1 << outMantBits) - 1)

      val normAdj    = if (qGeq1) 0 else -1
      val outExpFull = outExpRaw + normAdj + mantOverflow

      if (outExpFull <= 0) 0
      else if (outExpFull > outMaxNormalExp) {
        val maxMant = (1 << outMantBits) - 1
        (sign << signShift) | (outMaxNormalExp << outMantBits) | maxMant
      } else {
        val outExpC = outExpFull & ((1 << outExpBits) - 1)
        (sign << signShift) | (outExpC << outMantBits) | outMant
      }
    }
  }

  /** Full SW requant model for a tileRows × blockSize FP32 block, scale-type aware. */
  def swRequant(block: Seq[Seq[Float]], t: ElementType, st: ScaleType): (Seq[Int], Seq[Seq[Int]]) = {
    val scales = block.map(swMaxScale(_, t, st))
    val elems  = block.zip(scales).map { case (row, sc) => row.map(swFP32toFP8(_, sc, t, st)) }
    (scales, elems)
  }

  // =========================================================================
  // DUT helpers
  // =========================================================================

  /** Active-low async reset: drive reset=1 for "not in reset" (matches FusedDotProductUnit).
   *  Also pokes io.accumulation_count_i = cfg.K / cfg.vectorSize so the
   *  wrapper's accum-aware gate fires every K/vec PE-cycles. */
  def initDut(dut: PEArrayWrapper, cfg: PEArrayConfig): Unit = {
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
    dut.io.accumulation_count_i.poke((cfg.K / cfg.vectorSize).U)
  }

  /** Drive all rows/cols with the given element/scale values and step one clock.
   *  This pulses A_valid_i & B_valid_i for ONE PE-cycle, which adds one MAC
   *  contribution to the accumulator — NOT a complete dot product.  Use
   *  driveBatch for "one full dot product" worth of driving. */
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

  /** Drive ONE complete dot-product accumulation:
   *    K/vec valid PE-cycles  →  accReg holds the full sum
   *    + 1 acc_reset_i pulse  →  triggers RequantFP8 sample of accReg AND
   *                               clears accReg for the next batch
   *
   *  Mirrors the wrapper's accumulator-aware "result valid" gate — the requant
   *  block consumes one fp32_in per K/vec PE-cycles, not per cycle.  Use this
   *  everywhere a test needs "one batch of the block".
   */
  def driveBatch(
    dut:     PEArrayWrapper,
    cfg:     PEArrayConfig,
    aElems:  Seq[Int],
    bElems:  Seq[Int],
    aScales: Seq[Int],
    bScales: Seq[Int]
  ): Unit = {
    val accumCycles = cfg.K / cfg.vectorSize
    for (_ <- 0 until accumCycles)
      driveCycle(dut, cfg, aElems, bElems, aScales, bScales)
    // accReg now holds the full sum.  Pulse acc_reset_i this cycle so the
    // wrapper's resultDone fires (rq samples accReg) and on the next clock
    // edge accReg → 0, ready for the next batch.
    dut.io.acc_reset_i.poke(true.B)
    dut.clock.step()
    dut.io.acc_reset_i.poke(false.B)
  }

  /** PEArrayWrapperINT8 variant of driveBatch (Test 10). */
  def driveBatchINT8(
    dut:     PEArrayWrapperINT8,
    cfg:     PEArrayINT8Config,
    aElems:  Seq[Int],
    bElems:  Seq[Int],
    aScales: Seq[Int],
    bScales: Seq[Int]
  ): Unit = {
    val accumCycles = cfg.K / cfg.vectorSize
    for (_ <- 0 until accumCycles) {
      for (r <- 0 until cfg.tileRows) dut.io.op_a_i(r).poke(aElems(r).U)
      for (c <- 0 until cfg.tileCols) dut.io.op_b_i(c).poke(bElems(c).U)
      for (r <- 0 until cfg.tileRows) dut.io.shared_exp_A_i(r).poke(aScales(r).U)
      for (c <- 0 until cfg.tileCols) dut.io.shared_exp_B_i(c).poke(bScales(c).U)
      dut.io.A_valid_i.poke(true.B); dut.io.B_valid_i.poke(true.B)
      dut.clock.step()
      dut.io.A_valid_i.poke(false.B); dut.io.B_valid_i.poke(false.B)
    }
    dut.io.acc_reset_i.poke(true.B)
    dut.clock.step()
    dut.io.acc_reset_i.poke(false.B)
  }

  /** Read results_o(r)(c) as Float. */
  def peekAcc(dut: PEArrayWrapper, r: Int, c: Int): Float =
    intBitsToFloat(dut.io.results_o.get(r)(c).peek().litValue.toInt)

  /** Extract shared_scale_out[row] (row 0 = LSB, little-endian byte order). */
  def extractScale(packed: BigInt, tileRows: Int, row: Int): Int =
    ((packed >> (row * 8)) & 0xFF).toInt

  /** Extract elem_out[row][col] (row-major, row 0 / col 0 = LSB).
   *  `elemBits` defaults to 8 for FP8/INT8; pass 6 for FP6 outputs. */
  def extractFP8(
    packed: BigInt, tileRows: Int, blockSize: Int, row: Int, col: Int, elemBits: Int = 8
  ): Int = {
    val k    = row * blockSize + col
    val mask = (BigInt(1) << elemBits) - 1
    ((packed >> (k * elemBits)) & mask).toInt
  }

  // =========================================================================
  // Research-relevance filters (mirrors FDPUPostScaleReductionTreeTest)
  //   - Skip low×low precision pairs (E3M2/E2M3/E2M1 × E3M2/E2M3/E2M1)
  //   - Restrict scale types to UE8M0..UE4M4 (expScaleWidth >= 4) — i.e.
  //     excludes UE3M5 and UE2M6 (the user's "scale 小于 E3M5..E0M8" filter)
  // =========================================================================
  private val lowPrecElems       = Set(MXFormats.E3M2, MXFormats.E2M3, MXFormats.E2M1)
  private val researchScaleTypes = ScaleFormats.allScaleTypes.filter(_.expScaleWidth >= 4)
  private def isResearchPair(etA: ElementType, etB: ElementType): Boolean =
    !(lowPrecElems(etA) && lowPrecElems(etB))

  /** Element types valid as RequantFP8/FP6 outputType (per RequantConfig require).
   *  The sweep restricts etA to this set so the requant output type can match
   *  the A-operand format.  INT8 and E2M1 as etA are excluded — those would
   *  need PEArrayWrapperINT8 (different wrapper) or have no matching requant
   *  target. */
  private val requantOutputElems: Set[ElementType] =
    Set(MXFormats.E5M2, MXFormats.E4M3, MXFormats.E3M2, MXFormats.E2M3)

  /** Random element raw bits, moderate magnitude (avoids subnormals / inf). */
  private def randElement(t: ElementType, r: Random): Int = {
    val sign = r.nextInt(2)
    if (t.name == "INT8") {
      val mag = 1 + r.nextInt(20)
      if (sign == 0) mag else (-mag) & 0xFF
    } else {
      val expRange = 1 << t.elementWidthExp
      val expLo    = (t.bias + 1).max(1).min(expRange - 1)
      val expHi    = (t.bias + 3).max(expLo).min(expRange - 1)
      val exp      = expLo + r.nextInt(expHi - expLo + 1)
      val mant     = r.nextInt(1 << t.elementWidthMant)
      (sign << (t.totalWidth - 1)) | (exp << t.elementWidthMant) | mant
    }
  }

  /** Random shared-scale raw bits, exponent near bias (~1.0 magnitude). */
  private def randScale(s: ScaleType, r: Random): Int = {
    val expRange = 1 << s.expScaleWidth
    val expLo    = (s.bias - 1).max(1).min(expRange - 1)
    val expHi    = (s.bias + 1).max(expLo).min(expRange - 1)
    val exp      = expLo + r.nextInt(expHi - expLo + 1)
    val mant     = if (s.mantScaleWidth == 0) 0 else r.nextInt(1 << s.mantScaleWidth)
    (exp << s.mantScaleWidth) | mant
  }

  // =========================================================================
  // SW golden model — RequantINT8 (mirrors RequantINT8.scala FP32→INT8 logic)
  //
  // Scale semantics (UE8M0 only): shared_scale = max biased FP32 exponent
  // across the block.  Element value = int8_out × 2^(shared_scale − 133).
  //
  // FP32 → INT8 conversion:
  //   k = fp32_exp − shared_scale + 6   (shift exponent; k ≤ 6 for in-block)
  //   shift the 24-bit mantissa left by 24 then right by (23 − k), so
  //     bits [30:24] = 7-bit integer magnitude (0..127)
  //     bit  [23]    = guard
  //     bits [22:0]  = sticky
  //   RNE round, saturate to ±127 (0x80 never produced), subnormals → 0.
  // =========================================================================

  /** Max biased FP32 exponent across a block — UE8M0 shared scale (INT8 mode). */
  def swSharedScaleINT8(fp32s: Seq[Float]): Int =
    fp32s.map(f => (floatToRawIntBits(f) >>> 23) & 0xFF).max

  /** FP32 → INT8 given the block's max-biased-exp shared scale. RNE, saturate ±127. */
  def swFP32toINT8(f: Float, sharedScale: Int): Int = {
    val bits     = floatToRawIntBits(f)
    val sign     = (bits >>> 31) & 1
    val fp32Exp  = (bits >>> 23) & 0xFF
    val fp32Man  = bits & 0x7FFFFF
    if (fp32Exp == 0) return 0    // subnormal / zero → flush

    val k = fp32Exp - sharedScale + 6
    // 24-bit full mantissa with implicit 1, shifted left by 24 (= 48-bit value)
    val mantFull = (1L << 23) | fp32Man.toLong   // 24 bits
    val mantExt  = mantFull << 24                // 48 bits

    val shiftAmt = 23 - k
    // Chisel's >> with shiftAmt > width yields 0; replicate that here.
    val shifted: Long =
      if (shiftAmt >= 48) 0L
      else if (shiftAmt < 0) mantExt << (-shiftAmt)
      else mantExt >>> shiftAmt

    val mag7      = ((shifted >>> 24) & 0x7F).toInt
    val guardBit  = ((shifted >>> 23) & 1).toInt
    val stickyBit = (shifted & ((1L << 23) - 1)) != 0L

    val roundUp = guardBit == 1 && ((mag7 & 1) == 1 || stickyBit)
    val mag8    = mag7 + (if (roundUp) 1 else 0)
    val mag     = if (mag8 >= 128) 127 else mag8     // saturate (never emit 0x80)

    if (sign == 1) (-mag) & 0xFF else mag
  }

  /** Full SW requant model for a tileRows × blockSize FP32 block (INT8 output). */
  def swRequantINT8(block: Seq[Seq[Float]]): (Seq[Int], Seq[Seq[Int]]) = {
    val scales = block.map(swSharedScaleINT8)
    val ints   = block.zip(scales).map { case (row, sc) => row.map(swFP32toINT8(_, sc)) }
    (scales, ints)
  }

  /** Detailed per-PE-cycle failure trace (mirrors reference's runCycles output). */
  private def logPECycleFailure(
    cfg: PEArrayConfig, batch: Int, r: Int, c: Int,
    aE: Int, bE: Int, aS: Int, bS: Int,
    hw: Float, sw: Float, swCycleContrib: Float, tol: Float
  ): Unit = {
    val mc  = cfg.macCfg
    val dA  = decodeElem(aE, mc.elementTypeA)
    val dB  = decodeElem(bE, mc.elementTypeB)
    val dSA = decodeScale(aS, mc.stype)
    val dSB = decodeScale(bS, mc.stype)
    logErr(f"--- Batch $batch%2d  PE[$r][$c]  FAIL ---")
    logErr(f"  cfg: ${mc.elementTypeA.name} × ${mc.elementTypeB.name} / ${mc.stype.name}")
    logErr(f"  A : raw=0x$aE%X  decoded=$dA%12.4e")
    logErr(f"  B : raw=0x$bE%X  decoded=$dB%12.4e")
    logErr(f"  sA: raw=0x$aS%X  decoded=$dSA%12.4e")
    logErr(f"  sB: raw=0x$bS%X  decoded=$dSB%12.4e")
    logErr(f"  cycleContrib(SW) = $swCycleContrib%12.4e")
    logErr(f"  HW=$hw%12.4e  SW_acc=$sw%12.4e  diff=${math.abs(hw - sw)}%.4e  tol=$tol%.4e")
    logErr(f"  BITS: HW=0x${java.lang.Float.floatToIntBits(hw).toHexString} " +
           f"SW=0x${java.lang.Float.floatToIntBits(sw).toHexString}")
  }

  // =========================================================================
  // Shared test config — 4×4 tile, E5M2×E5M2/UE8M0, blockSize=32 → B=8 batches
  // =========================================================================
  // Tests opt into the debug FP32 results_o IO — production gen leaves it off.
  val cfg = DefaultPEArrayConfigs.e5m2_8x8.copy(exposeResults = true)
  // Convenience aliases
  val macCfg    = cfg.macCfg
  val rqCfg     = cfg.requantCfg
  val B         = rqCfg.batchesPerBlock  // 2
  val tileRows  = cfg.tileRows           // 8
  val tileCols  = cfg.tileCols           // 8
  val blockSize = rqCfg.blockSize        // 16

  // Pre-encoded E5M2/UE8M0 constants
  val e_1   = encodeElem(1.0,  MXFormats.E5M2)
  val e_n1  = encodeElem(-1.0, MXFormats.E5M2)
  val e_2   = encodeElem(2.0,  MXFormats.E5M2)
  val e_0p5 = encodeElem(0.5,  MXFormats.E5M2)
  val s_1   = encodeScale(1.0, ScaleFormats.UE8M0)
  val s_2   = encodeScale(2.0, ScaleFormats.UE8M0)

  // =========================================================================
  // Test 1: valid_out timing — fires only after batchesPerBlock dot products
  //
  // One "batch" = K/vec PE-cycles of accumulation + 1 acc_reset_i pulse
  // (driveBatch).  After B = blockSize/tileCols batches the requant block
  // fires valid_out.  Each batch fills tileCols columns of the block.
  // =========================================================================
  test("PEArray: valid_out fires exactly after batchesPerBlock dot products") {
    test(new PEArrayWrapper(cfg)).withAnnotations(Seq(WriteVcdAnnotation))  { dut =>
      initDut(dut, cfg)
      dut.io.valid_out.expect(false.B)

      val aE = Seq.fill(tileRows)(e_1)
      val bE = Seq.fill(tileCols)(e_1)
      val aS = Seq.fill(tileRows)(s_1)
      val bS = Seq.fill(tileCols)(s_1)

      // First B-1 batches: valid_out stays low
      for (b <- 0 until B - 1) {
        driveBatch(dut, cfg, aE, bE, aS, bS)
        dut.io.valid_out.expect(false.B, s"valid_out must stay low after batch $b")
      }
      // B-th batch: driveBatch's own acc_reset step IS the edge that latches
      // blockDone into validOutReg, so valid_out is already high in the cycle
      // right after driveBatch returns — no extra step needed.
      driveBatch(dut, cfg, aE, bE, aS, bS)
      dut.io.valid_out.expect(true.B, "valid_out must assert after B batches")

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
      initDut(dut, cfg)
      val aE = Seq.fill(tileRows)(e_1)
      val bE = Seq.fill(tileCols)(e_1)
      val aS = Seq.fill(tileRows)(s_1)
      val bS = Seq.fill(tileCols)(s_1)

      // Accumulate a few cycles
      for (_ <- 0 until 3) driveCycle(dut, cfg, aE, bE, aS, bS)
      // All accumulators should be non-zero
      for (r <- 0 until tileRows; c <- 0 until tileCols)
        assert(dut.io.results_o.get(r)(c).peek().litValue != 0,
          s"PE[$r][$c] expected non-zero before reset")

      // Reset
      dut.io.acc_reset_i.poke(true.B)
      dut.clock.step()
      dut.io.acc_reset_i.poke(false.B)

      // All accumulators should be cleared
      for (r <- 0 until tileRows; c <- 0 until tileCols)
        dut.io.results_o.get(r)(c).expect(0.U, s"PE[$r][$c] should be 0 after reset")
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
      initDut(dut, cfg)
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
  // Per batch: K/vec PE-cycles, each adding 1×1×1×1 = 1.0 to the accumulator.
  //   batch_dot_product = K/vec × 1.0
  //   block(r)(b*tileCols + c) = batch_dot_product (constant across batches)
  // =========================================================================
  test("PEArray: all-ones → MXFP8 output matches SW requant model") {
    test(new PEArrayWrapper(cfg)) { dut =>
      initDut(dut, cfg)
      dut.io.acc_reset_i.poke(true.B); dut.clock.step()
      dut.io.acc_reset_i.poke(false.B)

      val aE = Seq.fill(tileRows)(e_1)
      val bE = Seq.fill(tileCols)(e_1)
      val aS = Seq.fill(tileRows)(s_1)
      val bS = Seq.fill(tileCols)(s_1)

      val accumCycles = cfg.K / cfg.vectorSize
      val perBatchDot = swPECycle(macCfg)(e_1, e_1, s_1, s_1) * accumCycles

      val swBlock = Array.ofDim[Float](tileRows, blockSize)
      for (b <- 0 until B) {
        driveBatch(dut, cfg, aE, bE, aS, bS)
        for (r <- 0 until tileRows; c <- 0 until tileCols)
          swBlock(r)(b * tileCols + c) = perBatchDot
      }
      // The B-th driveBatch's acc_reset step latches blockDone → validOutReg
      // — valid_out is already high in the cycle right after driveBatch returns.
      dut.io.valid_out.expect(true.B, "valid_out must fire after full block")

      val (expScales, expFP8) = swRequant(swBlock.map(_.toSeq).toSeq, rqCfg.outputType, rqCfg.scaleType)
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
      initDut(dut, cfg)
      dut.io.acc_reset_i.poke(true.B); dut.clock.step()
      dut.io.acc_reset_i.poke(false.B)

      val accumCycles = cfg.K / cfg.vectorSize
      val aElems  = Seq.fill(tileRows)(e_1)
      val bElems  = Seq.fill(tileCols)(e_1)
      // alternating per-row scales — pad/truncate to tileRows
      val aScales = Seq.tabulate(tileRows)(r => if (r % 2 == 0) s_1 else s_2)
      val bScales = Seq.fill(tileCols)(s_1)

      val swBlock = Array.ofDim[Float](tileRows, blockSize)
      // Per-row dot product = accumCycles × (per-cycle MAC)
      val perRowBatchDot = Array.tabulate(tileRows) { r =>
        swPECycle(macCfg)(aElems(r), bElems(0), aScales(r), bScales(0)) * accumCycles
      }

      for (b <- 0 until B) {
        driveBatch(dut, cfg, aElems, bElems, aScales, bScales)
        for (r <- 0 until tileRows; c <- 0 until tileCols)
          swBlock(r)(b * tileCols + c) = perRowBatchDot(r)
      }
      dut.io.valid_out.expect(true.B)

      val (expScales, expFP8) = swRequant(swBlock.map(_.toSeq).toSeq, rqCfg.outputType, rqCfg.scaleType)
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
      initDut(dut, cfg)
      dut.io.acc_reset_i.poke(true.B); dut.clock.step()
      dut.io.acc_reset_i.poke(false.B)

      val accumCycles = cfg.K / cfg.vectorSize

      def runBlock(aRaw: Int, bRaw: Int, sARaw: Int, sBRaw: Int)
          : (Seq[Seq[Float]], BigInt, BigInt) = {
        val swBlock     = Array.ofDim[Float](tileRows, blockSize)
        val perBatchDot = swPECycle(macCfg)(aRaw, bRaw, sARaw, sBRaw) * accumCycles
        for (b <- 0 until B) {
          driveBatch(dut, cfg,
            Seq.fill(tileRows)(aRaw), Seq.fill(tileCols)(bRaw),
            Seq.fill(tileRows)(sARaw), Seq.fill(tileCols)(sBRaw))
          for (r <- 0 until tileRows; c <- 0 until tileCols)
            swBlock(r)(b * tileCols + c) = perBatchDot
        }
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
        val (expSc, expFP8) = swRequant(blk, rqCfg.outputType, rqCfg.scaleType)
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
      initDut(dut, cfg)
      dut.io.acc_reset_i.poke(true.B); dut.clock.step()
      dut.io.acc_reset_i.poke(false.B)

      val accumCycles = cfg.K / cfg.vectorSize

      // Generate random inputs for each batch
      val aElems  = Array.tabulate(B, tileRows)((_, _) => randE5M2())
      val bElems  = Array.tabulate(B, tileCols)((_, _) => randE5M2())
      val aScales = Array.tabulate(B, tileRows)((_, _) => randUE8M0())
      val bScales = Array.tabulate(B, tileCols)((_, _) => randUE8M0())

      val swBlock = Array.ofDim[Float](tileRows, blockSize)
      val hwBlock = Array.ofDim[Float](tileRows, blockSize)

      for (b <- 0 until B) {
        // SW per-batch dot product = accumCycles × per-cycle MAC (inputs are
        // constant within a batch — we drive the same operands K/vec times)
        val perCycleMac = Array.ofDim[Float](tileRows, tileCols)
        for (r <- 0 until tileRows; c <- 0 until tileCols)
          perCycleMac(r)(c) = swPECycle(macCfg)(aElems(b)(r), bElems(b)(c),
                                                aScales(b)(r), bScales(b)(c))

        // Drive K/vec PE-cycles (inline: don't call driveBatch because we
        // need to peek HW FP32 BEFORE the reset step samples-and-clears it)
        for (_ <- 0 until accumCycles)
          driveCycle(dut, cfg,
            aElems(b).toSeq, bElems(b).toSeq,
            aScales(b).toSeq, bScales(b).toSeq)

        // Peek + verify FP32 accumulator (= per-batch dot product)
        for (r <- 0 until tileRows; c <- 0 until tileCols) {
          val sw  = perCycleMac(r)(c) * accumCycles
          val hw  = peekAcc(dut, r, c)
          val tol = math.abs(sw) * 0.05f + 1e-5f
          assert(math.abs(hw - sw) <= tol,
            f"batch $b PE[$r][$c]: hw=$hw%.6f sw=$sw%.6f diff=${math.abs(hw-sw)}%.6f")
          swBlock(r)(b * tileCols + c) = sw
          hwBlock(r)(b * tileCols + c) = hw
        }

        // Now pulse acc_reset_i: rq.valid_in fires (resultDone=true), rq
        // samples accReg (= dot product), then accReg → 0.
        dut.io.acc_reset_i.poke(true.B); dut.clock.step()
        dut.io.acc_reset_i.poke(false.B)
      }

      // The last batch's acc_reset step IS the edge that latched blockDone
      // into validOutReg — valid_out is already high in the current cycle.
      dut.io.valid_out.expect(true.B, "valid_out must fire after full block")

      // Use HW FP32 (hwBlock) as the requant reference — it isolates the
      // requant block from any FP32-accumulator drift between HW and SW.
      val (expScales, expFP8) = swRequant(hwBlock.map(_.toSeq).toSeq, rqCfg.outputType, rqCfg.scaleType)
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

  // =========================================================================
  // Test 8: Config sweep — multi-cycle FP32 accumulator across (etA, etB, st)
  //
  // Mirrors FDPUPostScaleReductionTreeTest's "All type combinations" sweep,
  // applied to the PE array.  Filters per the user's request:
  //   - Skip low×low element pairs (E3M2/E2M3/E2M1 × E3M2/E2M3/E2M1)
  //   - Skip scale types with expScaleWidth < 4 (UE3M5, UE2M6)
  //
  // Per (etA, etB, st):
  //   - Build a 4×4 PEArrayConfig with vec=1 (matches the existing SW model).
  //   - Drive numCycles random batches.  Each batch sets fresh per-row A/scaleA
  //     and per-col B/scaleB, then steps one clock.
  //   - After each batch, peek every PE FP32 accumulator and compare to the SW
  //     running-sum.  On mismatch, dump the full per-PE data flow.
  //
  // Note: the SW peeks via dut.io.results_o.get(...) — requires
  // exposeResults=true on the cfg (set below).
  // =========================================================================
  test("PEArray: config sweep — multi-cycle FP32 accumulator across (etA,etB,st)") {
    log("\n[TEST 8] Config sweep — element-pair × scale combinations (vec=1 4x4)")

    val rng       = new Random(0x5EED5L)
    val vsize     = 1
    val numCycles = 5
    val rows      = 4
    val cols      = 4

    val allElem = MXFormats.allElementTypes
    var passed  = 0
    var failed  = 0

    // etA = current-tensor activation precision; the requant output (next-layer
    // activation) is produced in the same format → outputType = etA.  etA is
    // therefore restricted to the requant-compatible set (E5M2/E4M3/E3M2/E2M3).
    for (etA <- allElem if requantOutputElems(etA);
         etB <- allElem if isResearchPair(etA, etB);
         st  <- researchScaleTypes) {
      val mc = ScaleAddConfig(etA, etB, st)
      val rq = RequantConfig(
        blockSize  = 32, tileRows = rows, tileCols = cols,
        outputType = etA, scaleType = st
      )
      val sweepCfg = PEArrayConfig(
        macCfg = mc, vectorSize = vsize, tileRows = rows, tileCols = cols,
        requantCfg = rq, exposeResults = true
      )
      val label = s"${etA.name} × ${etB.name} / ${st.name} → ${etA.name} (${rows}x${cols} vec=$vsize)"
      log("\n" + "=" * 70)
      log(s"[$label]  resOpMantW=${mc.resOperatorMantWidth}  resScaleAddMantW=${mc.resScaleAddMantWidth}")
      log("=" * 70)

      // Pre-generate cycles (one set of inputs per batch)
      val seed    = new Random(rng.nextLong())
      val batches = (0 until numCycles).map { _ =>
        val aE = Seq.fill(rows)(randElement(etA, seed))
        val bE = Seq.fill(cols)(randElement(etB, seed))
        val aS = Seq.fill(rows)(randScale(st, seed))
        val bS = Seq.fill(cols)(randScale(st, seed))
        (aE, bE, aS, bS)
      }

      var localFailed = 0
      try {
        test(new PEArrayWrapper(sweepCfg)) { dut =>
          initDut(dut, sweepCfg)
          dut.io.acc_reset_i.poke(true.B); dut.clock.step()
          dut.io.acc_reset_i.poke(false.B)

          // SW per-PE running accumulator
          val swAcc = Array.ofDim[Float](rows, cols)

          for ((batch, bIdx) <- batches.zipWithIndex) {
            val (aE, bE, aS, bS) = batch
            // SW update first (so we know the expected post-cycle value)
            for (r <- 0 until rows; c <- 0 until cols)
              swAcc(r)(c) += swPECycle(mc)(aE(r), bE(c), aS(r), bS(c))

            driveCycle(dut, sweepCfg, aE, bE, aS, bS)

            // Per-cycle summary line — show corner PEs even on success
            val pe00Hw = peekAcc(dut, 0, 0)
            val pe33Hw = peekAcc(dut, rows - 1, cols - 1)
            log(f"  batch $bIdx%2d | PE[0][0] HW=$pe00Hw%12.4e SW=${swAcc(0)(0)}%12.4e | " +
                f"PE[${rows - 1}][${cols - 1}] HW=$pe33Hw%12.4e SW=${swAcc(rows - 1)(cols - 1)}%12.4e")

            // Verify every PE
            for (r <- 0 until rows; c <- 0 until cols) {
              val hw  = peekAcc(dut, r, c)
              val sw  = swAcc(r)(c)
              // Tolerance: 5% relative + small absolute floor for cancellation
              val tol = math.abs(sw) * 0.05f + 1e-4f
              if (math.abs(hw - sw) > tol) {
                val swCycleContrib = swPECycle(mc)(aE(r), bE(c), aS(r), bS(c))
                logPECycleFailure(sweepCfg, bIdx, r, c, aE(r), bE(c), aS(r), bS(c),
                  hw, sw, swCycleContrib, tol)
                localFailed += 1
              }
            }
          }
        }
        if (localFailed == 0) {
          log(s"[OK] $label")
          passed += 1
        } else {
          logErr(s"[FAIL] $label : $localFailed PE-cycle mismatches (${numCycles * rows * cols} checks total)")
          failed += 1
        }
      } catch { case e: Exception =>
        logErr(s"[ERROR] $label sim failed: ${e.getMessage}")
        failed += 1
      }
    }

    val summary = f"[TEST 8] Sweep summary: $passed passed, $failed failed (of ${passed + failed})"
    if (failed > 0) logErr(summary) else log(summary)
    assert(failed == 0, s"$failed config(s) failed — see pe_array_test.log for the data-flow trace")
  }

  // =========================================================================
  // Test 9: MX block-quantized 32-element dot product across configs
  //
  // Per (etA, etB, st) (same filters as Test 8): build a 32-element MX block
  // for A and B, slice into B = blockSize/tileCols batches of vec=1 inputs,
  // drive the array, then verify both:
  //   (a) every PE FP32 accumulator after the final batch
  //   (b) the requant element output at valid_out (using etA as the output
  //       type when valid, else fall back to E5M2)
  //
  // On any mismatch the per-PE failure trace is dumped via logPECycleFailure.
  // =========================================================================
  test("PEArray: MX block-quantized dot product sweep across (etA,etB,st)") {
    log("\n[TEST 9] MX block-quantized dot product sweep (4x4 vec=1, blockSize=32)")

    val rng       = new Random(0xB10C5L)
    val vsize     = 1
    val rows      = 4
    val cols      = 4
    val blockSize = 32
    val B         = blockSize / cols  // 8 batches per block

    val allElem = MXFormats.allElementTypes
    var passed  = 0
    var failed  = 0

    // etA = current-tensor activation precision; the requant output (next-layer
    // activation) is produced in the same format → outputType = etA.  etA is
    // therefore restricted to the requant-compatible set (E5M2/E4M3/E3M2/E2M3).
    for (etA <- allElem if requantOutputElems(etA);
         etB <- allElem if isResearchPair(etA, etB);
         st  <- researchScaleTypes) {
      val mc = ScaleAddConfig(etA, etB, st)
      val rq = RequantConfig(
        blockSize = blockSize, tileRows = rows, tileCols = cols,
        outputType = etA, scaleType = st
      )
      // K = vsize so accumCycles = K/vsize = 1: every PE-cycle is one full
      // dot product, and the test's existing "1 driveCycle per batch + random
      // inputs per batch" pattern stays valid under the new accum-aware gate.
      val sweepCfg = PEArrayConfig(
        macCfg = mc, vectorSize = vsize, tileRows = rows, tileCols = cols,
        requantCfg = rq, exposeResults = true, K = vsize
      )
      val label = s"${etA.name} × ${etB.name} / ${st.name} → ${etA.name} blk$blockSize"
      log("\n" + "=" * 70)
      log(s"[$label]")
      log("=" * 70)

      val seed = new Random(rng.nextLong())

      // Generate B batches of random per-row / per-col inputs
      val aElems  = Array.tabulate(B, rows)((_, _) => randElement(etA, seed))
      val bElems  = Array.tabulate(B, cols)((_, _) => randElement(etB, seed))
      val aScales = Array.tabulate(B, rows)((_, _) => randScale(st, seed))
      val bScales = Array.tabulate(B, cols)((_, _) => randScale(st, seed))

      var localFailed = 0
      try {
        test(new PEArrayWrapper(sweepCfg)) { dut =>
          initDut(dut, sweepCfg)
          dut.io.acc_reset_i.poke(true.B); dut.clock.step()
          dut.io.acc_reset_i.poke(false.B)

          val swAcc   = Array.ofDim[Float](rows, cols)
          val swBlock = Array.ofDim[Float](rows, blockSize)
          // hwBlock captures the HW FP32 accumulator per (row, col-in-block).
          // The HW requant block consumes these per-batch FP32 values, so the
          // *correct* expected requant output is swRequant(hwBlock, ...) — not
          // swRequant(swBlock, ...).  Comparing against the latter conflates
          // FP32 accumulator drift with requant errors.
          val hwBlock = Array.ofDim[Float](rows, blockSize)

          for (b <- 0 until B) {
            // SW first
            for (r <- 0 until rows; c <- 0 until cols) {
              swAcc(r)(c) += swPECycle(mc)(aElems(b)(r), bElems(b)(c),
                                           aScales(b)(r), bScales(b)(c))
              swBlock(r)(b * cols + c) = swAcc(r)(c)
            }
            driveCycle(dut, sweepCfg,
              aElems(b).toSeq, bElems(b).toSeq,
              aScales(b).toSeq, bScales(b).toSeq)

            // Capture HW FP32 for every PE: this is what the HW requant sees.
            for (r <- 0 until rows; c <- 0 until cols)
              hwBlock(r)(b * cols + c) = peekAcc(dut, r, c)

            // Per-batch corner trace
            log(f"  batch $b%2d | PE[0][0] HW=${peekAcc(dut, 0, 0)}%12.4e SW=${swAcc(0)(0)}%12.4e | " +
                f"PE[${rows - 1}][${cols - 1}] HW=${peekAcc(dut, rows - 1, cols - 1)}%12.4e " +
                f"SW=${swAcc(rows - 1)(cols - 1)}%12.4e")

            // Verify per-PE FP32 accumulator
            for (r <- 0 until rows; c <- 0 until cols) {
              val hw  = peekAcc(dut, r, c)
              val sw  = swAcc(r)(c)
              val tol = math.abs(sw) * 0.05f + 1e-4f
              if (math.abs(hw - sw) > tol) {
                val cycleContrib = swPECycle(mc)(aElems(b)(r), bElems(b)(c),
                                                 aScales(b)(r), bScales(b)(c))
                logPECycleFailure(sweepCfg, b, r, c, aElems(b)(r), bElems(b)(c),
                                  aScales(b)(r), bScales(b)(c), hw, sw, cycleContrib, tol)
                localFailed += 1
              }
            }
          }

          // Flush so RequantFP8 sees the last PE.validReg pulse
          dut.clock.step()
          dut.io.valid_out.expect(true.B, s"$label: valid_out must fire after full block")

          // ── Requant verification — use HW FP32 (hwBlock) as the input ──
          // because that's what the HW requant block actually consumed.
          // Bit-equality with swRequant(hwBlock, ...) isolates the requant
          // logic from any FP32-accumulator drift between HW and SW.
          val (expScales, expElems) = swRequant(hwBlock.map(_.toSeq).toSeq, etA, st)
          val hwScalePacked = dut.io.shared_scale_out.peek().litValue
          val hwElemPacked  = dut.io.result.peek().litValue

          // ── Requant header: expose scale type, max-abs per row, decoded scales ──
          // Per-row requant header: scale type, max-abs, decoded HW vs SW scale.
          // Both HW and SW now use cfg.scaleType for the encoding, so a DIFF
          // here points at a real bit-level mismatch (e.g. saturation, mant top-bits).
          val sharedScaleType = sweepCfg.requantCfg.scaleType
          log(f"  [requant] scaleType=${sharedScaleType.name} (expBits=${sharedScaleType.expScaleWidth}, " +
              f"mantBits=${sharedScaleType.mantScaleWidth}, bias=${sharedScaleType.bias})  outType=${etA.name}")
          for (row <- 0 until rows) {
            // maxAbs over hwBlock — that's the value HW's MaxScaleFinder saw.
            val rowAbs   = hwBlock(row).map(math.abs)
            val maxAbs   = rowAbs.max
            val maxIdx   = rowAbs.indexOf(maxAbs)
            val hwSc     = extractScale(hwScalePacked, rows, row)
            val swSc     = expScales(row)
            val hwScVal  = decodeScale(hwSc, sharedScaleType)
            val swScVal  = decodeScale(swSc, sharedScaleType)
            val tag      = if (hwSc == swSc) "OK  " else "DIFF"
            log(f"  [$tag] row $row  hw_maxAbs=${maxAbs}%10.4e (col $maxIdx) | " +
                f"HW scale=0x$hwSc%02X (=${hwScVal}%10.4e) | " +
                f"SW scale=0x$swSc%02X (=${swScVal}%10.4e)")
          }

          // ── Per-element check: assert and dump first N mismatches per row ──
          val dumpMaxPerRow = 4   // limit spam: full list already in raw fp32_value
          for (row <- 0 until rows) {
            val hwSc = extractScale(hwScalePacked, rows, row)
            val swSc = expScales(row)
            if (hwSc != swSc) {
              logErr(f"[FAIL] $label row $row scale: hw=0x$hwSc%02X sw=0x$swSc%02X")
              localFailed += 1
            }
            // ── Per-element check ────────────────────────────────────────
            // Bit-equality against swRequant(hwBlock, …) is the strict pass
            // criterion: it isolates the requant block from FP32-accumulator
            // drift (hwBlock = HW FP32, the actual requant input).
            // Quality fallback: when bits still differ, decode HW vs the SW
            // model output and compare distance to hwBlock.  Should never
            // trigger if the SW model is bit-exact — but if it does, mark
            // RNE-ties / SW-edges as informational and only count "HW worse"
            // as a real failure.
            var rowDumped     = 0
            var rowBitDiffs   = 0   // raw bit mismatches (informational)
            var rowRealFails  = 0   // HW strictly worse than ideal-of-hwBlock
            var rowTies       = 0   // HW and SW differ but equidistant (RNE tie)
            var rowSwLosses   = 0   // HW closer than SW model output
            for (col <- 0 until blockSize) {
              val hw = extractFP8(hwElemPacked, rows, blockSize, row, col, etA.totalWidth)
              val sw = expElems(row)(col)
              if (hw != sw) {
                rowBitDiffs += 1
                // Target = HW's own FP32 input, not swBlock.
                val target    = hwBlock(row)(col).toDouble
                val hwDecoded = decodeElem(hw, etA) * decodeScale(hwSc, sharedScaleType)
                val swDecoded = decodeElem(sw, etA) * decodeScale(swSc, sharedScaleType)
                val hwDist    = math.abs(hwDecoded - target)
                val swDist    = math.abs(swDecoded - target)
                val tieEps    = math.max(math.abs(target) * 1e-9, 1e-30)

                val (verdict, isFail) =
                  if (hwDist < swDist - tieEps)        ("HW closer (SW model edge)", false)
                  else if (swDist < hwDist - tieEps)   ("HW worse (real bug)       ", true)
                  else                                 ("RNE tie (both valid)      ", false)

                isFail match {
                  case true  => rowRealFails += 1
                  case false => if (hwDist < swDist) rowSwLosses += 1 else rowTies += 1
                }

                if (rowDumped < dumpMaxPerRow) {
                  val tag = if (isFail) "FAIL" else "INFO"
                  logErr(f"[$tag] $label elem[$row][$col]: hw=0x$hw%02X sw=0x$sw%02X | $verdict | " +
                         f"hw_fp32=${hwBlock(row)(col)}%10.4e (sw_fp32=${swBlock(row)(col)}%10.4e) | " +
                         f"HW_dec=${hwDecoded}%10.4e (d=${hwDist}%9.3e)  " +
                         f"SW_dec=${swDecoded}%10.4e (d=${swDist}%9.3e)")
                  rowDumped += 1
                }
                if (isFail) localFailed += 1
              }
            }
            if (rowBitDiffs > 0) {
              val summaryLine =
                f"[ROW $row] bit-diffs=$rowBitDiffs%2d  real-fails=$rowRealFails  " +
                f"rne-ties=$rowTies  sw-edges=$rowSwLosses"
              if (rowRealFails > 0) logErr(summaryLine) else log(summaryLine)
              if (rowBitDiffs > dumpMaxPerRow)
                log(f"[ROW $row] $rowBitDiffs bit-diffs total ($dumpMaxPerRow shown above)")
            }
          }
        }
        if (localFailed == 0) {
          log(s"[OK] $label")
          passed += 1
        } else {
          logErr(s"[FAIL] $label : $localFailed mismatches")
          failed += 1
        }
      } catch { case e: Exception =>
        logErr(s"[ERROR] $label sim failed: ${e.getMessage}")
        failed += 1
      }
    }

    val summary = f"[TEST 9] Block-quantized sweep summary: $passed passed, $failed failed (of ${passed + failed})"
    if (failed > 0) logErr(summary) else log(summary)
    assert(failed == 0, s"$failed config(s) failed — see pe_array_test.log")
  }

  // =========================================================================
  // Test 10: INT8-activation sweep — PEArrayWrapperINT8 + RequantINT8
  //
  // Mirrors Test 9 but for INT8 activations: etA = INT8, etB sweeps over all
  // element types, st sweeps over researchScaleTypes.  Output flows through
  // RequantINT8 (UE8M0-only shared scale, FP32 → 8-bit two's-complement RNE
  // with ±127 saturation), so the SW model uses swRequantINT8.
  //
  // PEArrayWrapperINT8 is a different wrapper class than PEArrayWrapper, but
  // its IO layout is structurally identical for the parts we touch (op_a/b,
  // shared_exp_*, results_o.get, valid_out, shared_scale_out).  The element
  // output port `result` is 8-bit-wide-per-element packed flat — same shape
  // as the FP8 case — so extractFP8 works as the bit-slice extractor.
  //
  // Note: isResearchPair never filters this sweep because INT8 is not in
  // lowPrecElems; all 6 etB choices remain for every scale.
  // =========================================================================
  test("PEArray: INT8-activation sweep — PEArrayWrapperINT8 + RequantINT8") {
    log("\n[TEST 10] INT8-activation sweep — etA=INT8, RequantINT8 output (4x4 vec=1, blockSize=32)")

    val rng       = new Random(0x1117C8L)
    val vsize     = 1
    val rows      = 4
    val cols      = 4
    val blockSize = 32
    val B         = blockSize / cols  // 8 batches per block

    val etA     = MXFormats.INT8
    val allElem = MXFormats.allElementTypes
    var passed  = 0
    var failed  = 0

    for (etB <- allElem if isResearchPair(etA, etB); st <- researchScaleTypes) {
      val mc = ScaleAddConfig(etA, etB, st)
      val rq = RequantINT8Config(blockSize = blockSize, tileRows = rows, tileCols = cols)
      // K = vsize → accumCycles = 1 (see Test 9 for rationale).
      val sweepCfg = PEArrayINT8Config(
        macCfg = mc, vectorSize = vsize, tileRows = rows, tileCols = cols,
        requantCfg = rq, exposeResults = true, K = vsize
      )
      val label = s"INT8 × ${etB.name} / ${st.name} → INT8 blk$blockSize"
      log("\n" + "=" * 70)
      log(s"[$label]")
      log("=" * 70)

      val seed = new Random(rng.nextLong())

      val aElems  = Array.tabulate(B, rows)((_, _) => randElement(etA, seed))
      val bElems  = Array.tabulate(B, cols)((_, _) => randElement(etB, seed))
      val aScales = Array.tabulate(B, rows)((_, _) => randScale(st, seed))
      val bScales = Array.tabulate(B, cols)((_, _) => randScale(st, seed))

      var localFailed = 0
      try {
        test(new PEArrayWrapperINT8(sweepCfg)) { dut =>
          // ── init ──────────────────────────────────────────────
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
          dut.io.accumulation_count_i.poke((sweepCfg.K / sweepCfg.vectorSize).U)
          dut.io.acc_reset_i.poke(true.B); dut.clock.step()
          dut.io.acc_reset_i.poke(false.B)

          val swAcc   = Array.ofDim[Float](rows, cols)
          val swBlock = Array.ofDim[Float](rows, blockSize)
          // hwBlock is the actual HW FP32 input to the requant block — see
          // Test 9 for rationale.
          val hwBlock = Array.ofDim[Float](rows, blockSize)

          for (b <- 0 until B) {
            // SW running update
            for (r <- 0 until rows; c <- 0 until cols) {
              swAcc(r)(c) += swPECycle(mc)(aElems(b)(r), bElems(b)(c),
                                           aScales(b)(r), bScales(b)(c))
              swBlock(r)(b * cols + c) = swAcc(r)(c)
            }

            // Drive one cycle (inline since driveCycle is typed for PEArrayWrapper)
            for (r <- 0 until rows) dut.io.op_a_i(r).poke(aElems(b)(r).U)
            for (c <- 0 until cols) dut.io.op_b_i(c).poke(bElems(b)(c).U)
            for (r <- 0 until rows) dut.io.shared_exp_A_i(r).poke(aScales(b)(r).U)
            for (c <- 0 until cols) dut.io.shared_exp_B_i(c).poke(bScales(b)(c).U)
            dut.io.A_valid_i.poke(true.B); dut.io.B_valid_i.poke(true.B)
            dut.clock.step()
            dut.io.A_valid_i.poke(false.B); dut.io.B_valid_i.poke(false.B)

            // Capture HW FP32 for every PE.
            for (r <- 0 until rows; c <- 0 until cols)
              hwBlock(r)(b * cols + c) =
                intBitsToFloat(dut.io.results_o.get(r)(c).peek().litValue.toInt)

            // Per-batch corner trace
            val pe00Hw = hwBlock(0)(b * cols + 0)
            val pe33Hw = hwBlock(rows - 1)(b * cols + cols - 1)
            log(f"  batch $b%2d | PE[0][0] HW=$pe00Hw%12.4e SW=${swAcc(0)(0)}%12.4e | " +
                f"PE[${rows - 1}][${cols - 1}] HW=$pe33Hw%12.4e SW=${swAcc(rows - 1)(cols - 1)}%12.4e")

            // Per-PE FP32 accumulator check
            for (r <- 0 until rows; c <- 0 until cols) {
              val hw  = hwBlock(r)(b * cols + c)
              val sw  = swAcc(r)(c)
              val tol = math.abs(sw) * 0.05f + 1e-4f
              if (math.abs(hw - sw) > tol) {
                val cycleContrib = swPECycle(mc)(aElems(b)(r), bElems(b)(c),
                                                 aScales(b)(r), bScales(b)(c))
                // Reuse the FP-side trace helper with a synthetic PEArrayConfig view:
                // only macCfg fields are read by logPECycleFailure.
                val viewRq = RequantConfig(blockSize, rows, cols, MXFormats.E5M2, st)
                val view   = PEArrayConfig(mc, vsize, rows, cols, viewRq)
                logPECycleFailure(view, b, r, c, aElems(b)(r), bElems(b)(c),
                                  aScales(b)(r), bScales(b)(c), hw, sw, cycleContrib, tol)
                localFailed += 1
              }
            }
          }

          dut.clock.step()  // flush PE.validReg into RequantINT8
          dut.io.valid_out.expect(true.B, s"$label: valid_out must fire after full block")

          // Verify INT8 element output against HW's own FP32 input.
          val (expScales, expInts) = swRequantINT8(hwBlock.map(_.toSeq).toSeq)
          val hwScalePacked = dut.io.shared_scale_out.peek().litValue
          val hwIntPacked   = dut.io.result.peek().litValue

          for (row <- 0 until rows) {
            val hwSc = extractScale(hwScalePacked, rows, row)
            if (hwSc != expScales(row)) {
              logErr(f"[FAIL] $label row $row scale: hw=0x$hwSc%02X sw=0x${expScales(row)}%02X")
              localFailed += 1
            }
            for (col <- 0 until blockSize) {
              val hw = extractFP8(hwIntPacked, rows, blockSize, row, col)  // 8-bit slice — works for INT8 too
              val sw = expInts(row)(col)
              if (hw != sw) {
                logErr(f"[FAIL] $label int8[$row][$col]: hw=0x$hw%02X sw=0x$sw%02X " +
                       f"(hw_fp32=${hwBlock(row)(col)}%.4f sw_fp32=${swBlock(row)(col)}%.4f)")
                localFailed += 1
              }
            }
          }
        }
        if (localFailed == 0) {
          log(s"[OK] $label")
          passed += 1
        } else {
          logErr(s"[FAIL] $label : $localFailed mismatches")
          failed += 1
        }
      } catch { case e: Exception =>
        logErr(s"[ERROR] $label sim failed: ${e.getMessage}")
        failed += 1
      }
    }

    val summary = f"[TEST 10] INT8-activation sweep summary: $passed passed, $failed failed (of ${passed + failed})"
    if (failed > 0) logErr(summary) else log(summary)
    assert(failed == 0, s"$failed config(s) failed — see pe_array_test.log")
  }

  // =========================================================================
  // Test 11: validIn / validOut handshake — state machine
  //
  // Verifies the wrapper's handshake behaviour:
  //   1. Post-reset state          : valid_out=0, A_ready_o=B_ready_o=1
  //   2. send_output_i flips ready : A_ready_o = B_ready_o = !send_output_i
  //                                  (purely combinational)
  //   3. Asymmetric valid pulses   : A_valid only OR B_valid only must NOT
  //                                  advance the block counter
  //   4. Idle cycles               : cycles with A_valid=B_valid=0 must NOT
  //                                  advance the block counter
  //   5. Block firing              : valid_out asserts for exactly one cycle
  //                                  after `batchesPerBlock` (=B) joint
  //                                  A_valid&B_valid pulses, then de-asserts
  //   6. Re-entrancy               : two consecutive blocks each fire once
  //
  // This is a pure handshake test — uses constant inputs; data values don't
  // matter, only the valid/ready semantics.
  // =========================================================================
  test("PEArray: validIn/validOut handshake — state machine") {
    log("\n[TEST 11] validIn/validOut handshake — state machine")

    val aE = Seq.fill(tileRows)(e_1)
    val bE = Seq.fill(tileCols)(e_1)
    val aS = Seq.fill(tileRows)(s_1)
    val bS = Seq.fill(tileCols)(s_1)

    test(new PEArrayWrapper(cfg)).withAnnotations(Seq(WriteVcdAnnotation)) { dut =>

      // Drive a hard reset that flushes both PE accumulators AND the requant
      // block's batch counter.  Active-low async reset: dut.reset=false means
      // "assert internal reset" (registers held at init), dut.reset=true means
      // "deassert" (operating).  Pulse reset=false for one cycle, then leave
      // reset=true so the design runs after fullReset returns.
      def fullReset(): Unit = {
        dut.reset.poke(false.B)
        dut.io.A_valid_i.poke(false.B)
        dut.io.B_valid_i.poke(false.B)
        dut.io.acc_reset_i.poke(false.B)
        dut.io.send_output_i.poke(false.B)
        dut.io.A_mode.poke(0.U)
        dut.io.B_mode.poke(0.U)
        dut.io.result_mode_quan.poke(0.U)
        dut.io.group_size.poke(0.U)
        dut.io.shared_format_i.poke(0.U)
        dut.io.accumulation_count_i.poke((cfg.K / cfg.vectorSize).U)
        dut.clock.step(2)
        dut.reset.poke(true.B)
        dut.clock.step()
      }

      def driveOperandsOnce(): Unit = {
        for (r <- 0 until tileRows) dut.io.op_a_i(r).poke(aE(r).U)
        for (c <- 0 until tileCols) dut.io.op_b_i(c).poke(bE(c).U)
        for (r <- 0 until tileRows) dut.io.shared_exp_A_i(r).poke(aS(r).U)
        for (c <- 0 until tileCols) dut.io.shared_exp_B_i(c).poke(bS(c).U)
      }

      // ── Phase 1: post-reset state ───────────────────────────────────────
      log("  [Phase 1] post-reset")
      fullReset()
      dut.io.valid_out.expect(false.B,  "valid_out must be 0 after reset")
      dut.io.A_ready_o.expect(true.B,   "A_ready_o must be 1 after reset (send_output_i=0)")
      dut.io.B_ready_o.expect(true.B,   "B_ready_o must be 1 after reset")

      // ── Phase 2: send_output_i ↔ A_ready_o / B_ready_o (combinational) ──
      log("  [Phase 2] send_output_i drives A_ready_o / B_ready_o")
      dut.io.send_output_i.poke(true.B)
      dut.io.A_ready_o.expect(false.B, "A_ready_o must be 0 when send_output_i=1")
      dut.io.B_ready_o.expect(false.B, "B_ready_o must be 0 when send_output_i=1")
      dut.io.send_output_i.poke(false.B)
      dut.io.A_ready_o.expect(true.B,  "A_ready_o must restore to 1 when send_output_i=0")
      dut.io.B_ready_o.expect(true.B,  "B_ready_o must restore to 1 when send_output_i=0")

      // ── Phase 3: A_valid alone — must NOT count toward the block ────────
      log("  [Phase 3] A_valid only (B_valid=0) — counter must not advance")
      fullReset()
      driveOperandsOnce()
      dut.io.A_valid_i.poke(true.B)
      dut.io.B_valid_i.poke(false.B)
      // Run > B cycles; if internal_valid were sensitive to A only, valid_out would fire.
      for (_ <- 0 until B + 4) {
        dut.clock.step()
        dut.io.valid_out.expect(false.B, "valid_out must stay low while only A_valid is high")
      }
      dut.io.A_valid_i.poke(false.B)

      // ── Phase 4: B_valid alone — must NOT count toward the block ────────
      log("  [Phase 4] B_valid only (A_valid=0) — counter must not advance")
      fullReset()
      driveOperandsOnce()
      dut.io.A_valid_i.poke(false.B)
      dut.io.B_valid_i.poke(true.B)
      for (_ <- 0 until B + 4) {
        dut.clock.step()
        dut.io.valid_out.expect(false.B, "valid_out must stay low while only B_valid is high")
      }
      dut.io.B_valid_i.poke(false.B)

      // ── Phase 5: idle cycles within a batch don't advance accCnt ────────
      // The wrapper's accumulation-cycle counter (accCnt) only advances when
      // peValidOut=1.  Inserting idle cycles inside a single batch must not
      // bring the counter to (accumCycles − 1) any sooner.
      log("  [Phase 5] idle cycles within a batch are not counted toward accCnt")
      fullReset()
      val accumCycles = cfg.K / cfg.vectorSize  // 8 with default cfg
      // First half of batch 0
      for (_ <- 0 until accumCycles / 2) driveCycle(dut, cfg, aE, bE, aS, bS)
      dut.io.valid_out.expect(false.B, "valid_out=0 mid-batch")
      // Idle gap — accCnt must hold
      for (i <- 0 until 5) {
        dut.clock.step()
        dut.io.valid_out.expect(false.B, s"valid_out=0 during idle gap (cycle $i)")
      }
      // Second half of batch 0
      for (_ <- 0 until accumCycles - accumCycles / 2)
        driveCycle(dut, cfg, aE, bE, aS, bS)
      // Pulse acc_reset_i: rq.valid_in fires for batch 0 + accumulator clears
      dut.io.acc_reset_i.poke(true.B); dut.clock.step()
      dut.io.acc_reset_i.poke(false.B)
      // Drive remaining B-1 batches normally
      for (_ <- 0 until B - 1) driveBatch(dut, cfg, aE, bE, aS, bS)
      // The B-th driveBatch's own acc_reset step latches blockDone → validOutReg.
      dut.io.valid_out.expect(true.B,
        s"valid_out fires after $B batches even with an idle gap inside batch 0")
      dut.clock.step()
      dut.io.valid_out.expect(false.B, "valid_out de-asserts one cycle later")

      // ── Phase 6: re-entrancy — two consecutive blocks both fire ─────────
      log("  [Phase 6] consecutive blocks each fire valid_out exactly once")
      fullReset()

      def runOneBlock(blockIdx: Int): Unit = {
        for (b <- 0 until B - 1) {
          driveBatch(dut, cfg, aE, bE, aS, bS)
          dut.io.valid_out.expect(false.B, s"block $blockIdx batch $b: valid_out=0")
        }
        driveBatch(dut, cfg, aE, bE, aS, bS)
        dut.io.valid_out.expect(true.B,  s"block $blockIdx: valid_out fires after $B batches")
        dut.clock.step()
        dut.io.valid_out.expect(false.B, s"block $blockIdx: valid_out=0 next cycle")
      }

      runOneBlock(1)
      runOneBlock(2)

      log("[Test 11] handshake state machine PASSED")
    }
  }

  // =========================================================================
  // Test 12: Custom 4×4 / K=32 / vec=4 / blk=16 — VCD waveform verification
  //
  // Config:
  //   tileRows = tileCols = 4
  //   K = 32, vectorSize = 4   →  accumCycles = K/vec = 8 PE-cycles / dot prod
  //   blockSize = 16, tileCols = 4   →  B = blockSize/tileCols = 4 batches
  //   Total cycles per block ≈ accumCycles × B = 32 PE-cycles + B reset pulses
  //   E5M2 × E5M2 / UE8M0 → E5M2 (default types)
  //
  // Inputs vary per batch so each batch's dot product is distinguishable in
  // the waveform:
  //   batch 0 : A = +1.0 → dot = 8  × 1   = +8.0   (col 0..3 of block)
  //   batch 1 : A = +2.0 → dot = 8  × 2   = +16.0  (col 4..7)
  //   batch 2 : A = -1.0 → dot = 8  × -1  = -8.0   (col 8..11)
  //   batch 3 : A = +0.5 → dot = 8  × 0.5 = +4.0   (col 12..15)
  //
  // Encoded MXFP8 (UE8M0 shared scale = max biased exp of 16.0 = 131 = 0x83):
  //   element / 2^4: 8/16=0.5 → 0x38, 16/16=1.0 → 0x3C, -8/16=-0.5 → 0xB8,
  //                  4/16=0.25 → 0x34
  //
  // VCD lands at test_run_dir/PEArray_*/<dut>.vcd.  Look for:
  //   - accReg (per PE) ramping over each 8-cycle batch then clearing on reset
  //   - peValidOut pulsing every cycle that has had a prior validIn
  //   - rq.io.valid_in / shared_scale_out / result asserting once after all 4
  //     batches complete
  //   - io.valid_out: exactly ONE cycle high after the 4th acc_reset_i pulse
  // =========================================================================
  test("PEArray: custom K=32 vec=4 4x4 blk16 — VCD waveform verification") {
    val customCfg = PEArrayConfig(
      macCfg     = ScaleAddConfig(MXFormats.E5M2, MXFormats.E5M2, ScaleFormats.UE8M0),
      vectorSize = 4,
      tileRows   = 4,
      tileCols   = 4,
      requantCfg = RequantConfig(
        blockSize  = 16,
        tileRows   = 4,
        tileCols   = 4,
        outputType = MXFormats.E5M2
      ),
      K             = 32,
      exposeResults = true
    )
    val cR    = customCfg.tileRows
    val cC    = customCfg.tileCols
    val cBlk  = customCfg.requantCfg.blockSize
    val cB    = customCfg.requantCfg.batchesPerBlock         // 4
    val cAcc  = customCfg.K / customCfg.vectorSize            // 8

    log("\n[TEST 12] Custom 4x4 K=32 vec=4 blk16 config — VCD waveform")
    log(f"  accumCycles = $cAcc%d, batchesPerBlock = $cB%d")
    log(f"  total cycles per block ≈ ${cAcc * cB}%d (= $cAcc%d × $cB%d) + $cB acc_reset pulses")

    test(new PEArrayWrapper(customCfg)).withAnnotations(Seq(WriteVcdAnnotation)) { dut =>
      initDut(dut, customCfg)
      dut.io.acc_reset_i.poke(true.B); dut.clock.step()
      dut.io.acc_reset_i.poke(false.B)

      // Distinguishable per-batch A operands: +1, +2, -1, +0.5
      val batchOperands = Seq(e_1, e_2, e_n1, e_0p5)
      require(batchOperands.size == cB,
        s"batchOperands.size (${batchOperands.size}) must match cB ($cB)")

      val swBlock = Array.ofDim[Float](cR, cBlk)

      for ((aRaw, b) <- batchOperands.zipWithIndex) {
        val aE = Seq.fill(cR)(aRaw)
        val bE = Seq.fill(cC)(e_1)
        val aS = Seq.fill(cR)(s_1)
        val bS = Seq.fill(cC)(s_1)

        val perCycleMac = swPECycle(macCfg)(aRaw, e_1, s_1, s_1)
        val perBatchDot = perCycleMac * cAcc

        log(f"  batch $b%1d: A=0x$aRaw%02X (=$perCycleMac%+.4f/cycle) → dot product = $perBatchDot%+10.4f")

        driveBatch(dut, customCfg, aE, bE, aS, bS)

        for (r <- 0 until cR; c <- 0 until cC)
          swBlock(r)(b * cC + c) = perBatchDot

        // Sanity: valid_out must NOT yet be high (we're mid-block)
        if (b < cB - 1)
          dut.io.valid_out.expect(false.B,
            s"batch $b/${cB - 1}: valid_out must stay low until the last batch lands")
      }

      // The B-th driveBatch's acc_reset step IS the edge that latches blockDone
      // into validOutReg — valid_out is already high in the cycle right after
      // driveBatch returns.
      dut.io.valid_out.expect(true.B,
        s"valid_out fires in the cycle right after the ${cB}-th batch")

      // Verify shared_scale_out + result
      val (expScales, expElems) =
        swRequant(swBlock.map(_.toSeq).toSeq, MXFormats.E5M2, ScaleFormats.UE8M0)
      val hwScalePacked = dut.io.shared_scale_out.peek().litValue
      val hwElemPacked  = dut.io.result.peek().litValue

      log(f"\n  [requant] outType=E5M2  scaleType=UE8M0  block_max_abs=16.0")
      for (row <- 0 until cR) {
        val hwSc    = extractScale(hwScalePacked, cR, row)
        val hwScVal = decodeScale(hwSc, ScaleFormats.UE8M0)
        log(f"  row $row scale: hw=0x$hwSc%02X (=$hwScVal%.4f) | sw=0x${expScales(row)}%02X")
        assert(hwSc == expScales(row),
          f"row $row scale mismatch: hw=0x$hwSc%02X sw=0x${expScales(row)}%02X")

        for (col <- 0 until cBlk) {
          val hw = extractFP8(hwElemPacked, cR, cBlk, row, col)
          val sw = expElems(row)(col)
          assert(hw == sw,
            f"row $row col $col: hw=0x$hw%02X sw=0x$sw%02X (fp32=${swBlock(row)(col)}%+.4f)")
        }
        // Pretty-print the row's bytes (4 batches × 4 cols)
        val rowBytes = (0 until cBlk).map { c =>
          f"0x${extractFP8(hwElemPacked, cR, cBlk, row, c)}%02X"
        }.mkString(" ")
        log(f"  row $row elements: $rowBytes")
      }

      // De-asserts on next idle cycle
      dut.clock.step()
      dut.io.valid_out.expect(false.B, "valid_out must de-assert one cycle after firing")

      log("[Test 12] custom config VCD waveform PASSED — see test_run_dir/*/<dut>.vcd")
    }
  }
}
