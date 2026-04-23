package mx.mac

import chisel3._
import chiseltest._
import org.scalatest.funsuite.AnyFunSuite
import java.lang.Float.{floatToIntBits, intBitsToFloat}
import java.io.{FileWriter, PrintWriter}
import scala.util.Random

/** Comprehensive Accumulation-Precision vs. Requantisation-Noise Analysis.
 *
 *  Outputs three reports to stdout and CSV:
 *    1. K-sweep × precision table  — minimum sufficient accMantBits as function of K
 *    2. Requant noise audit        — per-config normErr distribution (mean/std/p95/max)
 *    3. Recommendation table       — per format-pair, the Chisel accReg width to use
 *
 *  RTL is run ONCE per config with K_max cycles; software sweeps K and precision
 *  from the stored per-cycle reducedSum traces (no re-simulation needed).
 */
class AccErrorRequantAnalysisTest extends AnyFunSuite with ChiselScalatestTester {

  // ── Precision-reduction utility ──────────────────────────────────────────

  def roundToMantBits(f: Float, mantBits: Int): Float = {
    if (mantBits >= 23) return f
    val bits = floatToIntBits(f)
    val exp  = (bits >>> 23) & 0xFF
    if (exp == 0 || exp == 255) return f
    val drop     = 23 - mantBits
    val lsb      = (bits >>> drop) & 1
    val guard    = (bits >>> (drop - 1)) & 1
    val sticky   = (bits & ((1 << (drop - 1)) - 1)) != 0
    val roundUp  = guard == 1 && (lsb == 1 || sticky)
    val truncated = bits & ~((1 << drop) - 1)
    intBitsToFloat(if (roundUp) truncated + (1 << drop) else truncated)
  }

  def addP(a: Float, b: Float, mantBits: Int): Float =
    roundToMantBits(a + b, mantBits)

  // ── Software requantisation models ───────────────────────────────────────

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
    val outMant     = (mantRaw + (if (roundUp) 1 else 0)) & ((1 << outMantBits) - 1)
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

  // ── Element / scale decode helpers ───────────────────────────────────────

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

  def packElems(elems: Seq[Int], width: Int): BigInt =
    elems.zipWithIndex.foldLeft(BigInt(0)) { case (acc, (v, i)) =>
      acc | (BigInt(v & ((1 << width) - 1)) << (i * width))
    }

  def swDotProd(cfg: ScaleAddConfig)(as: Seq[Int], bs: Seq[Int], sA: Int, sB: Int): Double = {
    val dA = decodeScale(sA, cfg.stype)
    val dB = decodeScale(sB, cfg.stype)
    as.zip(bs).map { case (a, b) =>
      decodeElement(a, cfg.elementTypeA) * decodeElement(b, cfg.elementTypeB) * dA * dB
    }.sum
  }

  // ── Metrics ──────────────────────────────────────────────────────────────

  def sqnrDB(signals: Seq[Double], noises: Seq[Double]): Double = {
    val sp = signals.map(s => s * s).sum
    val np = noises.map(n => n * n).sum
    if (np < 1e-60 || sp < 1e-60) 99.9
    else 10.0 * Math.log10(sp / np)
  }

  /** Per-element mean normalised error (paper's primary metric). */
  def meanNormErr(refs: Seq[Double], results: Seq[Double]): Double = {
    val pairs = refs.zip(results).filter { case (r, _) => math.abs(r) > 1e-10 }
    if (pairs.isEmpty) return 0.0
    pairs.map { case (r, v) => math.abs(v - r) / math.abs(r) }.sum / pairs.size
  }

  /** Per-element normalised errors (for distribution stats). */
  def normErrVec(refs: Seq[Double], results: Seq[Double]): Seq[Double] = {
    refs.zip(results)
      .filter { case (r, _) => math.abs(r) > 1e-10 }
      .map    { case (r, v) => math.abs(v - r) / math.abs(r) }
  }

  def percentile(sorted: Seq[Double], pct: Double): Double = {
    if (sorted.isEmpty) return 0.0
    val idx = ((sorted.size - 1) * pct).toInt
    sorted(idx.min(sorted.size - 1))
  }

  // ── Requant dispatcher ───────────────────────────────────────────────────

  sealed trait RequantTarget
  case object RqINT8                 extends RequantTarget
  case class  RqFP8(ot: ElementType) extends RequantTarget

  def applyRequant(fp32: Float, sharedScale: Int, rq: RequantTarget): Double = rq match {
    case RqINT8     => decodeINT8(swRequantINT8(fp32, sharedScale), sharedScale)
    case RqFP8(ot)  =>
      val raw = swRequantFP8(fp32, sharedScale, ot.elementWidthExp, ot.elementWidthMant, ot.bias)
      decodeFP8(raw, sharedScale, ot.elementWidthExp, ot.elementWidthMant, ot.bias)
  }

  // ── Configuration descriptor ─────────────────────────────────────────────

  case class Cfg(
    typeA: ElementType, typeB: ElementType, stype: ScaleType,
    vecSize: Int, rq: RequantTarget, note: String = ""
  ) {
    val scfg   = ScaleAddConfig(typeA, typeB, stype)
    val label  = s"${typeA.name}x${typeB.name}_${stype.name}_N${vecSize}"
    val rqName = rq match { case RqINT8 => "INT8"; case RqFP8(ot) => ot.name }
    val opMantW = scfg.resOperatorMantWidth
    val prodRange = scfg.productExpRange
  }

  // ── Main analysis test ───────────────────────────────────────────────────

  test("Accumulation precision vs. requantization noise floor (SQNR analysis)") {

    val accPrecisions = Seq(23, 19, 15, 12, 9, 7)
    val K_values  = Seq(4, 8, 16, 32, 64)
    val K_max     = K_values.max          // RTL runs this many cycles; SW uses prefixes
    val blockSize = 16
    val N_trials  = 20                    // total elements = N_trials × blockSize

    val configs = Seq(
      // ── Class A: INT8 × INT8 (productExpRange = 0, pure integer acc) ──
      Cfg(MXFormats.INT8,  MXFormats.INT8,  ScaleFormats.UE8M0, 4, RqINT8,          "ClassA"),
      Cfg(MXFormats.INT8,  MXFormats.INT8,  ScaleFormats.UE8M0, 8, RqINT8,          "ClassA"),
      // ── Class B: INT8 × FP8 ──
      Cfg(MXFormats.INT8,  MXFormats.E4M3,  ScaleFormats.UE8M0, 4, RqFP8(MXFormats.E4M3), "ClassB"),
      Cfg(MXFormats.INT8,  MXFormats.E4M3,  ScaleFormats.UE8M0, 8, RqFP8(MXFormats.E4M3), "ClassB"),
      // ── Class C: FP8 × FP8 ──
      Cfg(MXFormats.E4M3,  MXFormats.E4M3,  ScaleFormats.UE8M0, 4, RqFP8(MXFormats.E4M3), "ClassC"),
      Cfg(MXFormats.E4M3,  MXFormats.E4M3,  ScaleFormats.UE8M0, 8, RqFP8(MXFormats.E4M3), "ClassC"),
      Cfg(MXFormats.E5M2,  MXFormats.E5M2,  ScaleFormats.UE8M0, 4, RqFP8(MXFormats.E5M2), "ClassC"),
      Cfg(MXFormats.E5M2,  MXFormats.E4M3,  ScaleFormats.UE8M0, 4, RqFP8(MXFormats.E4M3), "ClassC"),
      // ── FP4 (E2M1) configs — paper's MXFP4 analogue ──────────────────
      Cfg(MXFormats.INT8,  MXFormats.E2M1,  ScaleFormats.UE8M0, 4, RqINT8,          "INT8xFP4"),
      Cfg(MXFormats.INT8,  MXFormats.E2M1,  ScaleFormats.UE8M0, 8, RqINT8,          "INT8xFP4"),
      Cfg(MXFormats.E4M3,  MXFormats.E2M1,  ScaleFormats.UE8M0, 4, RqFP8(MXFormats.E4M3), "FP8xFP4"),
      Cfg(MXFormats.E4M3,  MXFormats.E2M1,  ScaleFormats.UE8M0, 8, RqFP8(MXFormats.E4M3), "FP8xFP4"),
      Cfg(MXFormats.E5M2,  MXFormats.E2M1,  ScaleFormats.UE8M0, 4, RqFP8(MXFormats.E4M3), "FP8xFP4"),
    )

    val csvFull = new PrintWriter(new FileWriter("acc_error_requant_full.csv", false))
    csvFull.println("config,class,rqOutput,vecSize,K,accMantBits,SQNR_acc_dB,SQNR_full_dB,SQNR_floor_dB,normErr_pct,normFloor_pct,within1dB,within10pct")

    // ── Per-config results storage (for summary tables) ──────────────────
    case class KPResult(
      normFloor: Double,              // normErr at mantBits=23 (FP32 baseline)
      sqnrFloor: Double,
      normStats: (Double,Double,Double,Double), // mean,std,p95,max at mantBits=23 (FP32 requant only)
      sufficientP_sqnr: Map[Int,Int], // K → min mantBits (SQNR criterion)
      sufficientP_norm: Map[Int,Int]  // K → min mantBits (normErr criterion)
    )

    val allResults = scala.collection.mutable.ArrayBuffer[(Cfg, KPResult)]()

    // ═══════════════════════════════════════════════════════════════════════
    // Phase 1 + 2: RTL simulation → software K × precision sweep
    // ═══════════════════════════════════════════════════════════════════════
    for (cfg <- configs) {
      println(s"\n[${cfg.label}] running RTL (K_max=$K_max, N=${N_trials * blockSize} elements)...")

      case class RunResult(ref: Double, cycleRS: Seq[Float])
      val allRuns = scala.collection.mutable.ArrayBuffer[RunResult]()

      test(new FDPUPostScaleReductionTree(cfg.scfg, cfg.vecSize, istest = true)) { dut =>
        val rng = new Random(0xDEAD_BEEF)

        for (_ <- 0 until N_trials * blockSize) {
          dut.io.resetAcc.poke(true.B); dut.clock.step(); dut.io.resetAcc.poke(false.B)

          var ref = 0.0
          val cycleRS = scala.collection.mutable.ArrayBuffer[Float]()

          for (_ <- 0 until K_max) {
            val as = Seq.fill(cfg.vecSize)(randElem(cfg.typeA, rng))
            val bs = Seq.fill(cfg.vecSize)(randElem(cfg.typeB, rng))
            val sA = randScale(cfg.stype, rng)
            val sB = randScale(cfg.stype, rng)
            ref += swDotProd(cfg.scfg)(as, bs, sA, sB)

            dut.io.op_a_i       .poke(packElems(as, cfg.typeA.totalWidth).U)
            dut.io.op_b_i       .poke(packElems(bs, cfg.typeB.totalWidth).U)
            dut.io.share_exp_A_i.poke(sA.U)
            dut.io.share_exp_B_i.poke(sB.U)
            dut.io.validIn.poke(true.B); dut.clock.step(); dut.io.validIn.poke(false.B)

            cycleRS += intBitsToFloat(dut.io.debug.get.reducedSum.peek().litValue.toInt)
          }
          allRuns += RunResult(ref, cycleRS.toSeq)
        }
      }

      // ── Phase 2: software K × precision sweep ────────────────────────
      // sufP_sqnr(K)(P) and sufP_norm(K)(P) track per-K sufficient precision
      val sufP_sqnr = scala.collection.mutable.Map[Int,Int]().withDefaultValue(23)
      val sufP_norm = scala.collection.mutable.Map[Int,Int]().withDefaultValue(23)
      var normFloor = 0.0
      var sqnrFloor = 0.0
      var normStatsAtFloor: (Double,Double,Double,Double) = (0,0,0,0)

      for (K <- K_values) {
        var sqnrFloorK = 0.0
        var normFloorK = 0.0

        for (mantBits <- accPrecisions) {
          // Accumulate using first K cycleRS values
          val accErrors   = scala.collection.mutable.ArrayBuffer[Double]()
          val fullErrors  = scala.collection.mutable.ArrayBuffer[Double]()
          val refVals     = scala.collection.mutable.ArrayBuffer[Double]()
          val fullResults = scala.collection.mutable.ArrayBuffer[Double]()
          val blockNormErrs = scala.collection.mutable.ArrayBuffer[Double]()  // per-block normErr

          // Compute partial reference for first K cycles
          // (run.ref is for all K_max cycles; we need partial ref)
          // We recompute via double-precision from cycleRS approximation:
          // Note: cycleRS is the RTL FP32 reducedSum; we use it as an approximation
          // of the partial dot product for each cycle. The "partial reference" is
          // sum of first K cycleRS values cast to double (FP32-exact partial sum).
          // This separates accumulator error from dot-product error cleanly.
          for (blockRuns <- allRuns.toSeq.grouped(blockSize)) {
            val reducedAccs: Seq[Float] = blockRuns.map { run =>
              var acc = 0.0f
              for (rs <- run.cycleRS.take(K)) acc = addP(acc, rs, mantBits)
              acc
            }
            // Partial reference: exact FP32 sum of first K reducedSums (per element)
            val refs: Seq[Double] = blockRuns.map { run =>
              run.cycleRS.take(K).map(_.toDouble).sum
            }
            val sharedScale = reducedAccs.map(biasedExp).max

            val blockRefVals  = scala.collection.mutable.ArrayBuffer[Double]()
            val blockResVals  = scala.collection.mutable.ArrayBuffer[Double]()

            for ((rAcc, ref) <- reducedAccs.zip(refs)) {
              val rqVal = applyRequant(rAcc, sharedScale, cfg.rq)
              refVals     += ref
              accErrors   += rAcc.toDouble - ref
              fullErrors  += rqVal - ref
              fullResults += rqVal
              blockRefVals  += ref
              blockResVals  += rqVal
            }
            // Per-block normErr (captures outlier blocks)
            blockNormErrs += meanNormErr(blockRefVals.toSeq, blockResVals.toSeq)
          }

          val sqnrAcc  = sqnrDB(refVals.toSeq, accErrors.toSeq)
          val sqnrFull = sqnrDB(refVals.toSeq, fullErrors.toSeq)
          val normErr  = meanNormErr(refVals.toSeq, fullResults.toSeq)

          if (mantBits == 23) {
            sqnrFloorK = sqnrFull
            normFloorK = normErr
            if (K == K_values.max) {
              normFloor = normErr; sqnrFloor = sqnrFull
              // Compute normErr distribution stats at FP32 baseline
              val sorted = blockNormErrs.toSeq.sorted
              val mean = sorted.sum / sorted.size
              val std  = math.sqrt(sorted.map(x => (x - mean)*(x - mean)).sum / sorted.size)
              val p95  = percentile(sorted, 0.95)
              val max  = sorted.last
              normStatsAtFloor = (mean, std, p95, max)
            }
          }

          if (sqnrFull >= sqnrFloorK - 1.0)  sufP_sqnr(K) = sufP_sqnr(K).min(mantBits)
          if (normErr  <= normFloorK * 1.10)  sufP_norm(K) = sufP_norm(K).min(mantBits)

          csvFull.println(f"${cfg.label},${cfg.note},${cfg.rqName},${cfg.vecSize},$K,$mantBits," +
            f"$sqnrAcc%.2f,$sqnrFull%.2f,$sqnrFloorK%.2f,${normErr*100}%.4f,${normFloorK*100}%.4f," +
            f"${sqnrFull >= sqnrFloorK - 1.0},${normErr <= normFloorK * 1.10}")
        }
      }

      allResults += ((cfg, KPResult(normFloor, sqnrFloor, normStatsAtFloor,
                                    sufP_sqnr.toMap, sufP_norm.toMap)))
    }

    csvFull.close()

    // ═══════════════════════════════════════════════════════════════════════
    // Report 1: K-sweep × precision summary (normErr criterion)
    // ═══════════════════════════════════════════════════════════════════════
    val Ks = K_values
    println("\n\n" + "═" * 110)
    println("REPORT 1: Minimum sufficient accMantBits  (normErr criterion: within 10% of FP32 baseline)")
    println("═" * 110)
    val hdr = f"${"Config"}%-34s | ${"class"}%-9s | ${"rq"}%-5s | ${"opW"}%3s | ${"prodR"}%5s" +
              Ks.map(k => f"  K=$k%2d").mkString + " | recommendation"
    println(hdr)
    println("-" * 110)

    for ((cfg, res) <- allResults) {
      val kCols = Ks.map(k => f"${res.sufficientP_norm(k)}%6d").mkString
      val recP  = Ks.map(k => res.sufficientP_norm(k)).max
      val saves = 23 - recP
      val recStr = s"FP${1+8+recP} (saves $saves vs FP32)"
      println(f"${cfg.label}%-34s | ${cfg.note}%-9s | ${cfg.rqName}%-5s | ${cfg.opMantW}%3d | ${cfg.prodRange}%5d$kCols | $recStr")
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Report 2: Requant noise audit (FP32 accumulator — pure requant noise)
    // ═══════════════════════════════════════════════════════════════════════
    println("\n\n" + "═" * 110)
    println("REPORT 2: Requant noise audit  (accMantBits=23, pure requant noise)")
    println("  normErr distribution across blocks — identifies abnormal saturation/outlier blocks")
    println("═" * 110)
    println(f"${"Config"}%-34s | ${"rq"}%-5s | ${"opW"}%3s | ${"normFloor%"}%10s | ${"SQNR_floor"}%10s | ${"mean%"}%7s | ${"std%"}%6s | ${"p95%"}%6s | ${"max%"}%7s | ${"max/mean"}%8s | verdict")
    println("-" * 110)

    for ((cfg, res) <- allResults) {
      val (mean, std, p95, max) = res.normStats
      val ratio = if (mean > 1e-6) max / mean else 0.0
      val verdict =
        if      (ratio > 5.0)  "WARN: extreme outlier blocks (saturation?)"
        else if (ratio > 2.5)  "NOTE: moderate block-to-block variance"
        else                   "OK"
      println(f"${cfg.label}%-34s | ${cfg.rqName}%-5s | ${cfg.opMantW}%3d | ${res.normFloor*100}%9.3f%% | ${res.sqnrFloor}%9.1f dB | ${mean*100}%6.3f%% | ${std*100}%5.3f%% | ${p95*100}%5.3f%% | ${max*100}%6.3f%% | $ratio%8.2f | $verdict")
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Report 3: Consolidated recommendation table
    // ═══════════════════════════════════════════════════════════════════════
    println("\n\n" + "═" * 110)
    println("REPORT 3: accReg width recommendation  (worst K over K∈{4,8,16,32,64})")
    println("═" * 110)
    println(f"${"Config"}%-34s | ${"rq"}%-5s | ${"worstK_P"}%8s | ${"accReg"}%10s | ${"saves vs FP32"}%14s | ${"normFloor%"}%10s | K sensitivity")
    println("-" * 110)

    for ((cfg, res) <- allResults) {
      val pByK  = Ks.map(k => res.sufficientP_norm(k))
      val worstP = pByK.max
      val saves  = 23 - worstP
      val sensitivity =
        if (pByK.distinct.size == 1) "flat (K-insensitive)"
        else s"K=${Ks(pByK.indexOf(pByK.min))}→${pByK.min}b  K=${Ks(pByK.indexOf(pByK.max))}→${pByK.max}b"
      println(f"${cfg.label}%-34s | ${cfg.rqName}%-5s | $worstP%8d | FP${1+8+worstP}%9s | ${saves}%13d bits | ${res.normFloor*100}%9.3f%% | $sensitivity")
    }

    println(s"\nResults written to acc_error_requant_full.csv")
    println(s"(K_max=$K_max, blockSize=$blockSize, N_trials=$N_trials)")
  }
}
