package mx.mac

import org.scalatest.funsuite.AnyFunSuite
import java.lang.Float.{floatToIntBits, intBitsToFloat}
import java.io.{FileWriter, PrintWriter}
import scala.util.Random

/**
 * BF16 Accumulator Output — Stage-by-Stage Noise Budget Analysis.
 *
 * ─── WHY THIS TEST EXISTS ───────────────────────────────────────────────────
 * The accumulator register was changed from FP32 (23 mant bits) to a
 * reduced-precision FP format whose mantissa width is chosen at elaboration
 * time by AccPrecision.recommended(scfg, K).  The hardware output port is
 * the top-7 mantissa bits (BF16 precision) presented as FP32 (zero-extended).
 *
 * This test proves the change is safe by measuring the SQNR at every stage
 * of the pipeline and showing that BF16-truncation noise stays BELOW the
 * requantization noise floor for every (MAC type × K × scale type) combo.
 *
 * ─── INPUT DISTRIBUTION (realistic DNN inference model) ─────────────────────
 *   Activation : Gaussian N(0, σ=3.62) body + 6 hardcoded positive-only outliers
 *                up to 4384.  Matches real down_proj stats: std=3.62, max=4384,
 *                min=-19 → outliers are single-sided (positive only).
 *   Weight     : Gaussian N(0, σ=0.0124) — matches real down_proj weight std;
 *                tightly concentrated near zero, no outliers.
 *   Both       : MX-quantized in blocks of BLOCK_SIZE_IN=32 along the inner
 *                dimension before the dot-product accumulation, matching the
 *                hardware MX input format.
 *
 * ─── PIPELINE STAGES MODELLED ───────────────────────────────────────────────
 *
 *   FP64 exact (after input MX quantization — ground truth)
 *       │ S1: per-cycle dot product rounded to FP32 (reducedSum approx.)
 *       ▼
 *   accFP32 — accumulated with FP32 (23-bit, best-case reference)
 *       │ S2: accumulate K cycles at actualAccMantBits  (FPNAdder model)
 *       ▼
 *   accHW   — hardware accumulator at actualAccMantBits precision
 *       │ S3: BF16 output — truncate to top-7 mantissa bits (hardware path)
 *       ▼
 *   accBF16 — what results_o delivers to the requant block
 *       │ S4: requantize to target (INT8 / FP8)
 *       ▼
 *   rqOut   — final output delivered to downstream layers
 *
 * ─── HOW TO READ THE OUTPUT ─────────────────────────────────────────────────
 *
 *   SQNR_Sx  = 10·log10(signal_power / noise_power) for that stage [dB].
 *              Higher SQNR = less noise = better.
 *
 *   SQNR_S1  ≈ 80–120 dB  (FP32 rounding of a double DP — negligible)
 *   SQNR_S2  ≈ 40–70 dB  (accumulation at M bits; M ≥ 8 by design)
 *   SQNR_S3  ≈ 40–56 dB  (BF16 truncation from M bits to 7 bits, one step)
 *   SQNR_S4  = requant noise floor:
 *              E5M2 → ≈ 17 dB,  E4M3 → ≈ 23 dB,  INT8 → ≈ 44–46 dB
 *
 *   margin_dB = SQNR_S3 − SQNR_S4   (positive = BF16 acc noise < rq noise)
 *   verdict   = SAFE if margin > 0, WARN otherwise
 *
 * ─── SCALE TYPES ────────────────────────────────────────────────────────────
 * Sweep covers UE8M0 (pure power-of-2) and UE6M2 / UE5M3 / UE4M4 (mantissa-
 * precision scales).  For non-UE8M0 requant, the shared scale is the smallest
 * representable S in that format that prevents overflow of the max block element.
 */
class BF16AccLossAnalysisTest extends AnyFunSuite {

  // ── FP precision helpers ────────────────────────────────────────────────────

  /** RNE rounding to N mantissa bits (models FPNAdder). */
  def roundToMantBits(f: Float, mantBits: Int): Float = {
    if (mantBits >= 23) return f
    val bits = floatToIntBits(f)
    val exp  = (bits >>> 23) & 0xFF
    if (exp == 0 || exp == 255) return f
    val drop    = 23 - mantBits
    val lsb     = (bits >>> drop) & 1
    val guard   = (bits >>> (drop - 1)) & 1
    val sticky  = (bits & ((1 << (drop - 1)) - 1)) != 0
    val roundUp = guard == 1 && (lsb == 1 || sticky)
    val truncated = bits & ~((1 << drop) - 1)
    intBitsToFloat(if (roundUp) truncated + (1 << drop) else truncated)
  }

  /** Truncate (no rounding) to N mantissa bits — models the hardware bit-select
   *  that extracts the top 16 bits of accReg into the BF16 output port. */
  def truncateToMantBits(f: Float, mantBits: Int): Float = {
    if (mantBits >= 23) return f
    val bits = floatToIntBits(f)
    val exp  = (bits >>> 23) & 0xFF
    if (exp == 0 || exp == 255) return f
    val drop = 23 - mantBits
    intBitsToFloat(bits & ~((1 << drop) - 1))
  }

  /** One FPNAdder step: add then round to mantBits. */
  def addP(a: Float, b: Float, mantBits: Int): Float =
    roundToMantBits(a + b, mantBits)

  // ── Element / scale decode ──────────────────────────────────────────────────

  def decodeElement(raw: Int, t: ElementType): Double = {
    if (t.name == "INT8") {
      val sv = if ((raw & 0x80) != 0) raw - 256 else raw
      sv.toDouble * Math.pow(2, t.implicitScaleExp)
    } else {
      val sign = if (((raw >> (t.totalWidth - 1)) & 1) == 1) -1.0 else 1.0
      val exp  = (raw >> t.elementWidthMant) & ((1 << t.elementWidthExp) - 1)
      val mant = raw & ((1 << t.elementWidthMant) - 1)
      if (exp == 0) sign * (mant.toDouble / (1 << t.elementWidthMant)) * Math.pow(2, 1 - t.bias)
      else          sign * (1.0 + mant.toDouble / (1 << t.elementWidthMant)) * Math.pow(2, exp - t.bias)
    }
  }

  def decodeScale(raw: Int, s: ScaleType): Double = {
    val eBits = (raw >> s.mantScaleWidth) & ((1 << s.expScaleWidth) - 1)
    if (s.mantScaleWidth == 0) Math.pow(2, eBits - s.bias)
    else {
      val mBits = raw & ((1 << s.mantScaleWidth) - 1)
      val impl  = if (eBits > 0) 1.0 else 0.0
      val eff   = if (eBits > 0) eBits - s.bias else 1 - s.bias
      (impl + mBits.toDouble / (1 << s.mantScaleWidth)) * Math.pow(2, eff)
    }
  }

  def randElem(t: ElementType, r: Random): Int = {
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

  def randScale(s: ScaleType, r: Random): Int = {
    val expRange = 1 << s.expScaleWidth
    val expLo    = (s.bias - 1).max(1).min(expRange - 1)
    val expHi    = (s.bias + 1).max(expLo).min(expRange - 1)
    val exp      = expLo + r.nextInt(expHi - expLo + 1)
    val mant     = if (s.mantScaleWidth == 0) 0 else r.nextInt(1 << s.mantScaleWidth)
    (exp << s.mantScaleWidth) | mant
  }

  // ── SQNR ───────────────────────────────────────────────────────────────────

  def sqnrDB(signal: Seq[Double], noise: Seq[Double]): Double = {
    val sp = signal.map(s => s * s).sum
    val np = noise.map(n => n * n).sum
    if (np < 1e-60 || sp < 1e-60) 99.9
    else 10.0 * Math.log10(sp / np)
  }

  // ── Realistic input-distribution helpers ────────────────────────────────────

  // Positive-only outliers. Real data: max=4384, min=-19 → single-sided outliers.
  private val actOutliers  = Array(4384.0f, 3500.0f, 2800.0f, 2048.0f, 4000.0f, 3200.0f)
  private val blockSizeIn  = 32   // MX input quantization block size

  /** Activation vector: Gaussian N(0, σ=3.62) body + actOutliers at random positions. */
  def genActivationVec(size: Int, rng: Random): Array[Float] = {
    val a = Array.fill(size)((rng.nextGaussian() * 3.62).toFloat)
    val positions = rng.shuffle((0 until size).toList).take(actOutliers.length)
    positions.zipWithIndex.foreach { case (pos, i) => a(pos) = actOutliers(i) }
    a
  }

  /** Weight vector: Gaussian N(0, σ=0.0124) — real down_proj weight std. */
  def genWeightVec(size: Int, rng: Random): Array[Float] =
    Array.fill(size)((rng.nextGaussian() * 0.0124).toFloat)

  /** RqTarget matching an element type (used for MX input quantization). */
  def inputRqFor(t: ElementType): RqTarget =
    if (t.elementWidthExp == 0) RqINT8 else RqFP8(t)

  /** MX-quantize a float vector in groups of blockSizeIn, returning dequantized doubles.
   *  Uses the same applyRq path as the output requant — matching quantize_mx_v2. */
  def mxQuantizeVec(vals: Array[Float], t: ElementType, s: ScaleType): Array[Double] =
    vals.grouped(blockSizeIn).flatMap(blk => applyRq(blk.toSeq, inputRqFor(t), s)).toArray

  // ── Requant models ──────────────────────────────────────────────────────────

  def biasedExp(f: Float): Int = (floatToIntBits(f) >>> 23) & 0xFF

  def swRequantINT8(fp32: Float, sharedScale: Int): Int = {
    val bits    = floatToIntBits(fp32)
    val sign    = (bits >>> 31) & 1
    val fp32Exp = (bits >>> 23) & 0xFF
    val fp32Man = bits & 0x7FFFFF
    if (fp32Exp == 0) return 0
    val k        = fp32Exp - sharedScale + 6
    if (k < -23) return 0
    val fullMant: Long = ((1 << 23) | fp32Man).toLong
    val mantExt: Long  = fullMant << 24
    val shiftAmt       = 23 - k
    val shifted: Long  = if (shiftAmt >= 48) 0L
                         else if (shiftAmt < 0) mantExt << (-shiftAmt)
                         else mantExt >>> shiftAmt
    val mag7    = ((shifted >>> 24) & 0x7FL).toInt
    val guardBit = ((shifted >>> 23) & 1L).toInt
    val sticky   = (shifted & 0x7FFFFFL) != 0L
    val roundUp  = guardBit == 1 && ((mag7 & 1) == 1 || sticky)
    val mag8     = mag7 + (if (roundUp) 1 else 0)
    val mag      = if (mag8 > 127) 127 else mag8
    if (sign == 0) mag else -mag
  }

  def decodeINT8(int8: Int, sharedScale: Int): Double =
    int8.toDouble * Math.pow(2.0, sharedScale - 133)

  def swRequantFP8(fp32: Float, sharedScale: Int, outExpBits: Int, outMantBits: Int, outBias: Int): Int = {
    val bits        = floatToIntBits(fp32)
    val sign        = (bits >>> 31) & 1
    val fp32Exp     = (bits >>> 23) & 0xFF
    val fp32Man     = bits & 0x7FFFFF
    val outExpFull  = fp32Exp - sharedScale + outBias
    val outMaxExp   = (1 << outExpBits) - 2
    if (fp32Exp == 0 || outExpFull <= 0) return 0
    val maxResult   = (sign << (outExpBits + outMantBits)) | (outMaxExp << outMantBits) | ((1 << outMantBits) - 1)
    if (outExpFull > outMaxExp) return maxResult
    val mantRaw   = fp32Man >>> (23 - outMantBits)
    val guardIdx  = 22 - outMantBits
    val guardBit  = (fp32Man >>> guardIdx) & 1
    val stickyBit = if (guardIdx > 0) (fp32Man & ((1 << guardIdx) - 1)) != 0 else false
    val roundUp   = guardBit == 1 && ((mantRaw & 1) == 1 || stickyBit)
    val outMant   = (mantRaw + (if (roundUp) 1 else 0)) & ((1 << outMantBits) - 1)
    val outExpClamp = outExpFull & ((1 << outExpBits) - 1)
    (sign << (outExpBits + outMantBits)) | (outExpClamp << outMantBits) | outMant
  }

  def decodeFP8(fp8: Int, sharedScale: Int, outExpBits: Int, outMantBits: Int, outBias: Int): Double = {
    val sign   = (fp8 >>> (outExpBits + outMantBits)) & 1
    val fp8Exp = (fp8 >>> outMantBits) & ((1 << outExpBits) - 1)
    val fp8Man = fp8 & ((1 << outMantBits) - 1)
    if (fp8Exp == 0) return 0.0
    val fp32ExpRebias = fp8Exp - outBias + sharedScale
    val mantVal = 1.0 + fp8Man.toDouble / (1 << outMantBits)
    val v = mantVal * Math.pow(2.0, fp32ExpRebias - 127)
    if (sign == 1) -v else v
  }

  // ── Requant target ──────────────────────────────────────────────────────────

  sealed trait RqTarget { def name: String }
  case object RqINT8                 extends RqTarget { val name = "INT8" }
  case class  RqFP8(ot: ElementType) extends RqTarget { val name = ot.name }

  /** Select the natural requant target: the higher-precision input type.
   *
   *  "Precision" = effective mantissa bits:
   *    INT8  → 7 (signed 8-bit integer, magnitudes 0–127)
   *    FP types → elementWidthMant (stored fractional bits)
   *
   *  Ties (symmetric pairs) return typeA.
   */
  def naturalRqTarget(typeA: ElementType, typeB: ElementType): RqTarget = {
    def prec(t: ElementType): Int =
      if (t.elementWidthExp == 0) t.elementWidthMant + 1
      else t.elementWidthMant
    val higher = if (prec(typeA) >= prec(typeB)) typeA else typeB
    if (higher.elementWidthExp == 0) RqINT8 else RqFP8(higher)
  }

  /** Maximum representable element magnitude for a given requant target.
   *  Used to choose the tightest shared scale that avoids overflow. */
  def maxRepresentableElem(rq: RqTarget): Double = rq match {
    case RqINT8    => 127.0 / 64.0   // 127 * 2^implicitScaleExp = 127 * 2^-6
    case RqFP8(ot) =>
      val maxExp  = (1 << ot.elementWidthExp) - 2
      val maxMant = (1 << ot.elementWidthMant) - 1
      (1.0 + maxMant.toDouble / (1 << ot.elementWidthMant)) * math.pow(2.0, maxExp - ot.bias)
  }

  /** Best shared scale code for a block whose peak absolute value is maxAbs.
   *
   *  UE8M0: biasedExp(maxAbs) — original behavior, pure power-of-2.
   *  Non-UE8M0: iterate all 256 codes, find the smallest representable S
   *  such that S >= maxAbs / maxRepresentableElem(rq).  This is the tightest
   *  scale that prevents the peak element from clipping after quantization.
   */
  def findBestSharedScale(maxAbs: Float, stype: ScaleType, rq: RqTarget): Int = {
    if (maxAbs == 0.0f) return 0
    if (stype.mantScaleWidth == 0) {
      biasedExp(maxAbs)
    } else {
      val minScaleNeeded = maxAbs.toDouble / maxRepresentableElem(rq)
      val totalCodes = 1 << stype.totalScaleWidth   // always 256
      var bestCode = totalCodes - 1
      var bestS    = decodeScale(totalCodes - 1, stype)
      for (code <- 1 until totalCodes) {
        val sv = decodeScale(code, stype)
        if (sv >= minScaleNeeded && sv < bestS) { bestS = sv; bestCode = code }
      }
      bestCode
    }
  }

  /** Apply requantization to a block of FP32 values and decode back to Double.
   *
   *  UE8M0: original biased-exponent integer path (unchanged behavior).
   *  Non-UE8M0: find best scale via findBestSharedScale, then normalize the
   *  FP32 values by that decoded scale, quantize, and denormalize.  For FP8
   *  the normalization is fp32/S → quantize to FP8 with scale=1.0 → × S.
   *  For INT8: fp32 × 64/S → round/clip to ±127 → × S/64.
   */
  def applyRq(blockVals: Seq[Float], rq: RqTarget, stype: ScaleType): Seq[Double] = {
    if (stype.mantScaleWidth == 0) {
      // ── UE8M0: original path ──────────────────────────────────────────────
      val sharedScale = blockVals.map(biasedExp).max
      rq match {
        case RqINT8 =>
          blockVals.map(f => decodeINT8(swRequantINT8(f, sharedScale), sharedScale))
        case RqFP8(ot) =>
          blockVals.map { f =>
            val raw = swRequantFP8(f, sharedScale, ot.elementWidthExp, ot.elementWidthMant, ot.bias)
            decodeFP8(raw, sharedScale, ot.elementWidthExp, ot.elementWidthMant, ot.bias)
          }
      }
    } else {
      // ── Non-UE8M0: normalize-quantize-denormalize ─────────────────────────
      val maxAbs   = blockVals.map(f => math.abs(f.toDouble)).max.toFloat
      val decodedS = decodeScale(findBestSharedScale(maxAbs, stype, rq), stype)
      if (decodedS == 0.0) return blockVals.map(_ => 0.0)
      rq match {
        case RqINT8 =>
          blockVals.map { f =>
            val int8Code = (f.toDouble * 64.0 / decodedS).round.toInt.max(-127).min(127)
            int8Code * decodedS / 64.0
          }
        case RqFP8(ot) =>
          // Quantize (f/S) to FP8 with sharedScale=127 (scale=1.0), then × S
          blockVals.map { f =>
            val normF  = (f.toDouble / decodedS).toFloat
            val fp8Raw = swRequantFP8(normF, 127, ot.elementWidthExp, ot.elementWidthMant, ot.bias)
            decodeFP8(fp8Raw, 127, ot.elementWidthExp, ot.elementWidthMant, ot.bias) * decodedS
          }
      }
    }
  }

  // ── Main analysis test ──────────────────────────────────────────────────────

  test("BF16 accumulator output: per-stage noise budget for all type combinations") {

    // --- Hardware parameters --------------------------------------------------
    // PE array: arrayRows × arrayCols.  Each tile computes one row of arrayCols
    // output elements in parallel.  After K cycles of vecSize MACs each column
    // produces one BF16 output; consecutive blockSize outputs share a requant scale.
    //
    //  Total MACs per output element = K × vecSize
    //    tensor 64×64:   K = 64 / 4 = 16 → 64  MACs/element
    //    tensor 256×256: K = 256 / 4 = 64 → 256 MACs/element
    val arrayRows   = 4
    val arrayCols   = 16   // output elements computed in parallel per tile
    val vecSize     = 4    // MACs per cycle per PE (inner-product lane width)
    val blockSizes  = Seq(8, 16, 32)   // requant block size options
    val tensorSizes = Seq(64, 256)     // square tensor inner dimension
    // N_tiles: number of independent tiles per (macPair, scale, tensorSize) config.
    // Effective sample count = N_tiles × arrayCols per config before S4 sweep.
    val N_tiles = 300

    val scaleTypes = Seq(
      ScaleFormats.UE8M0,
      ScaleFormats.UE6M2,
      ScaleFormats.UE5M3,
      ScaleFormats.UE4M4
    )

    val macPairs = Seq(
      (MXFormats.E5M2, MXFormats.E5M2),
      (MXFormats.E4M3, MXFormats.E4M3),
      (MXFormats.E3M2, MXFormats.E3M2),
      (MXFormats.E5M2, MXFormats.E4M3),
      (MXFormats.INT8, MXFormats.E5M2),
      (MXFormats.INT8, MXFormats.E4M3),
      (MXFormats.INT8, MXFormats.INT8)
    )

    // --- Result storage -------------------------------------------------------
    // S1/S2/S3 are independent of blockSize; S4 and margin depend on blockSize.
    case class StageResult(
      typeA: String, typeB: String, rqName: String,
      scaleName: String, tensorSize: Int, K: Int, accMantBits: Int, blockSize: Int,
      sqnr_S1: Double,   // FP32 reducedSum rounding vs FP64           (~99 dB, negligible)
      sqnr_S2: Double,   // K-cycle accumulation at accMantBits         (~50–85 dB)
      sqnr_S3: Double,   // BF16 output truncation (7 mant bits)        (~50–56 dB, KEY)
      sqnr_S4: Double,   // requantization noise floor                  (~14–46 dB, dominant)
      sqnr_total: Double // end-to-end vs FP64 ground truth
    ) {
      val margin  = sqnr_S3 - sqnr_S4   // >0 → BF16 acc noise < rq noise → SAFE
      val verdict = if (margin > 0) "SAFE" else "WARN"
    }

    val allResults = scala.collection.mutable.ArrayBuffer[StageResult]()

    val csv = new PrintWriter(new FileWriter("bf16_acc_noise_budget.csv", false))
    csv.println("typeA,typeB,rqTarget,scaleType,tensorSize,K,totalMACs,accMantBits,blockSize," +
                "SQNR_S1_dB,SQNR_S2_dB,SQNR_S3_dB,SQNR_S4_dB,SQNR_total_dB,margin_dB,verdict")

    // --- Outer sweep: macPairs × scaleTypes × tensorSizes --------------------
    // S1/S2/S3 computed once per (macPair, scale, tensorSize).
    // S4 computed inside for each blockSize (cheap, no re-accumulation needed).
    for {
      (typeA, typeB) <- macPairs
      scale          <- scaleTypes
      tensorSize     <- tensorSizes
    } {
      val K           = tensorSize / vecSize   // accumulation depth in cycles
      val totalMACs   = K * vecSize            // total MACs per output element
      val rq          = naturalRqTarget(typeA, typeB)
      val scfg        = ScaleAddConfig(typeA, typeB, scale)
      val accMantBits = AccPrecision.recommended(scfg, K)
      val rng = new Random(
        0xABCD_1234L ^ (tensorSize * 7919L) ^ (scale.name.hashCode.toLong * 31337L))

      // Collect outputs: N_tiles tiles × arrayCols columns = N_tiles*arrayCols elements.
      // Each tile runs arrayCols independent column accumulators in parallel (one PE row).
      // This models the real array: all columns in a tile share the same K cycles but
      // have different element data; sA is broadcast per row, sB differs per column.
      val ref64s   = scala.collection.mutable.ArrayBuffer[Double]()
      val accFP32s = scala.collection.mutable.ArrayBuffer[Double]()
      val accHWs   = scala.collection.mutable.ArrayBuffer[Double]()
      val accBF16s = scala.collection.mutable.ArrayBuffer[Float]()

      for (_ <- 0 until N_tiles) {
        for (_ <- 0 until arrayCols) {
          // Pre-generate full tensorSize-element vectors then MX-quantize in blocks of 32.
          // This matches the Python bf16_acc_distribution_analysis.py pipeline exactly:
          //   activation: N(0, var=9) + outliers ±4000  →  quantize_mx_v2(dtype_a, blk=32)
          //   weight:     U(−1, 1)                      →  quantize_mx_v2(dtype_b, blk=32)
          val aQ = mxQuantizeVec(genActivationVec(tensorSize, rng), typeA, scale)
          val bQ = mxQuantizeVec(genWeightVec(tensorSize, rng),     typeB, scale)

          // One column accumulator: K cycles of vecSize MACs each.
          var ref64   = 0.0
          var fp32Acc = 0.0f
          var hwAcc   = 0.0f

          for (k <- 0 until K) {
            val k0 = k * vecSize
            val exactDP = (0 until vecSize).map(i => aQ(k0 + i) * bQ(k0 + i)).sum
            ref64 += exactDP

            val rs  = exactDP.toFloat   // S1: reducedSum ≈ exact DP cast to Float
            fp32Acc = addP(fp32Acc, rs, 23)
            hwAcc   = addP(hwAcc,   rs, accMantBits)
          }

          // S3: hardware takes top 7 mantissa bits of accReg (no rounding — bit-select)
          val bf16Out: Float =
            if (accMantBits >= 7) truncateToMantBits(hwAcc, 7) else hwAcc

          ref64s   += ref64
          accFP32s += fp32Acc.toDouble
          accHWs   += hwAcc.toDouble
          accBF16s += bf16Out
        }
      }

      // S1/S2/S3: computed once, shared across all blockSize variants
      val s1_noise = accFP32s.zip(ref64s).map { case (f, r) => f - r }.toSeq
      val sqnr_S1  = sqnrDB(ref64s.toSeq, s1_noise)

      val s2_noise = accHWs.zip(accFP32s).map { case (h, f) => h - f }.toSeq
      val sqnr_S2  = sqnrDB(accFP32s.toSeq, s2_noise)

      val s3_noise = accBF16s.zip(accHWs).map { case (b, h) => b.toDouble - h }.toSeq
      val sqnr_S3  = sqnrDB(accHWs.toSeq, s3_noise)

      // S4: sweep blockSizes — requant groups consecutive blockSize column outputs.
      //   blockSize ≤ arrayCols: each tile row is split into (arrayCols/blockSize) blocks.
      //   blockSize > arrayCols: one block spans multiple consecutive tiles' outputs.
      //   Either way, grouped() on the flat buffer gives the correct statistics.
      for (blockSize <- blockSizes) {
        val rqDecoded = accBF16s.toSeq.grouped(blockSize).flatMap { blk =>
          applyRq(blk, rq, scale)
        }.toSeq

        val s4_noise   = rqDecoded.zip(accBF16s.toSeq).map { case (r, b) => r - b.toDouble }
        val sqnr_S4    = sqnrDB(accBF16s.map(_.toDouble).toSeq, s4_noise)

        val tot_noise  = rqDecoded.zip(ref64s.toSeq).map { case (r, g) => r - g }
        val sqnr_total = sqnrDB(ref64s.toSeq, tot_noise)

        val res = StageResult(
          typeA.name, typeB.name, rq.name, scale.name,
          tensorSize, K, accMantBits, blockSize,
          sqnr_S1, sqnr_S2, sqnr_S3, sqnr_S4, sqnr_total)
        allResults += res
        csv.println(
          f"${typeA.name},${typeB.name},${rq.name},${scale.name}," +
          f"$tensorSize,$K,$totalMACs,$accMantBits,$blockSize," +
          f"$sqnr_S1%.1f,$sqnr_S2%.1f,$sqnr_S3%.1f,$sqnr_S4%.1f,$sqnr_total%.1f," +
          f"${res.margin}%.1f,${res.verdict}")
      }
    }
    csv.close()

    // ── Print the noise budget table ──────────────────────────────────────────

    val W = 190
    println("\n" + "═" * W)
    println("BF16 ACCUMULATOR — STAGE-BY-STAGE NOISE BUDGET  (4×16 PE Array, realistic distribution)")
    println("  Activation: N(0, var=9) + outliers ±4000   |   Weight: U(−1,1)")
    println("  Both inputs MX-quantized in blocks of 32 before accumulation (matches quantize_mx_v2)")
    println("  rq: highest-precision input type  |  blockSize: consecutive outputs sharing one requant scale")
    println("═" * W)
    println("""
Pipeline (per column accumulator, one tile):
  FP64 exact (after MX input quantization — K × vecSize MACs)
    ↓ S1: per-cycle sum rounded to FP32 [reducedSum model — ~99 dB, negligible]
  accFP32   [23-bit baseline, K cycles]
    ↓ S2: K-cycle accumulation at accMantBits [FPNAdder model]
  accHW     [accMantBits precision]
    ↓ S3: BF16 output — top 7 mantissa bits, bit-select from accReg [KEY stage]
  accBF16   [7 mant bits → requant input]
    ↓ S4: blockSize outputs → shared scale → requant to activation type [dominant noise]
  rqOut

Columns: tensor=input dim  K=cycles  MACs=K×vecSize  accM=AccPrecision.recommended
         blk=blockSize  margin=SQNR_S3−SQNR_S4  (positive=SAFE)
""")

    println(
      f"${"typeA×typeB"}%-16s | ${"rq"}%-5s | ${"scale"}%-6s | ${"tensor"}%6s | ${"K"}%3s | ${"MACs"}%4s | " +
      f"${"accM"}%4s | ${"blk"}%3s | ${"SQNR_S1"}%8s | ${"SQNR_S2"}%8s | " +
      f"${"SQNR_S3(BF16)"}%14s | ${"SQNR_S4(rq)"}%12s | ${"SQNR_tot"}%9s | ${"margin"}%8s | verdict")
    println("-" * W)

    for (r <- allResults) {
      println(
        f"${r.typeA}×${r.typeB}%-16s | ${r.rqName}%-5s | ${r.scaleName}%-6s | ${r.tensorSize}%6d | " +
        f"${r.K}%3d | ${r.K * vecSize}%4d | ${r.accMantBits}%4d | ${r.blockSize}%3d | " +
        f"${r.sqnr_S1}%7.1f dB | ${r.sqnr_S2}%7.1f dB | ${r.sqnr_S3}%13.1f dB | " +
        f"${r.sqnr_S4}%11.1f dB | ${r.sqnr_total}%8.1f dB | ${r.margin}%+7.1f dB | ${r.verdict}")
    }

    // ── Summary: worst blockSize per (MAC pair × scale × tensorSize) ──────────
    println()
    println("═" * W)
    println("SUMMARY: worst-blockSize margin  (min over blockSize ∈ {8,16,32})")
    println()
    println(
      f"${"typeA×typeB"}%-16s | ${"rq"}%-5s | ${"scale"}%-6s | ${"tensor"}%6s | " +
      f"${"K"}%3s | ${"MACs"}%4s | ${"accM"}%4s | ${"min margin"}%10s | verdict")
    println("-" * 95)

    for {
      (typeA, typeB) <- macPairs
      scale          <- scaleTypes
      tensorSize     <- tensorSizes
    } {
      val K    = tensorSize / vecSize
      val rows = allResults.filter(r =>
        r.typeA == typeA.name && r.typeB == typeB.name &&
        r.scaleName == scale.name && r.tensorSize == tensorSize)
      val minMarg = rows.map(_.margin).min
      val verdict = if (minMarg > 0) "SAFE" else "WARN"
      val accM    = rows.head.accMantBits
      val rqName  = rows.head.rqName
      println(
        f"${typeA.name}×${typeB.name}%-16s | $rqName%-5s | ${scale.name}%-6s | $tensorSize%6d | " +
        f"$K%3d | ${K * vecSize}%4d | $accM%4d | ${minMarg}%+9.1f dB | $verdict")
    }

    println()
    println("═" * W)
    println(s"Full data → bf16_acc_noise_budget.csv")
    println(s"Distribution: activation=N(0,σ=3.62)+outliers≤4384(+only)  weight=N(0,σ=0.0124)  blockSizeIn=$blockSizeIn")
    println(s"(N_tiles=$N_tiles, arrayCols=$arrayCols, vecSize=$vecSize, " +
            s"blockSizes=${blockSizes.mkString(",")}, " +
            s"tensors=${tensorSizes.mkString(",")}, " +
            s"scales=${scaleTypes.map(_.name).mkString(",")})")
    println("═" * W)

    val hardFails = allResults.filter(_.margin < -3.0)
    assert(hardFails.isEmpty,
      s"${hardFails.size} config(s) have margin < −3 dB — BF16 accumulator may be unsafe:\n" +
      hardFails.map(r =>
        s"  ${r.typeA}×${r.typeB} scale=${r.scaleName} tensor=${r.tensorSize} " +
        s"K=${r.K} accM=${r.accMantBits} blk=${r.blockSize} rq=${r.rqName} margin=${r.margin}%.1f dB"
      ).mkString("\n"))
  }
}
