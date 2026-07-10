package mx.mac

import chisel3._
import chiseltest._
import org.scalatest.funsuite.AnyFunSuite
import java.lang.Float.{floatToIntBits, intBitsToFloat}
import java.io.{FileWriter, PrintWriter}
import scala.io.Source

/** HW design-rule validation: verify that the K-derived M_acc formula keeps
 *  accumulator noise below the requantization noise floor (Cuyckens-style),
 *  for both Cycle-FP (cpb=1, Generic) and Block-FX (cpb=8, KulischInner).
 *
 *  Cuyckens-style metric (Cuyckens et al. 2024):
 *      ε_rel,i  =  |HW_acc,i - FP_ref,i|  /  |FP_ref,i|
 *  i.e. per-output relative addition error vs the FP64 gold reference.
 *  Aggregated across 96 trials using MEDIAN (robust to occasional
 *  near-zero references that produce outlier-dominated max).
 *
 *  Reference noise floor: requantization relative ulp of the output element
 *  format,
 *      ulp_req  =  2^-(m_out + 1)   for FP element output
 *               =  1 / 127            for INT8 element output
 *
 *  Design rule:
 *      ε_rel_median  <  ulp_req
 *  Equivalently, safety_margin = ulp_req / ε_rel_median > 1, indicating that
 *  the typical hardware accumulator output is closer to the FP64 reference
 *  than one output-format ulp --- noise is hidden by requantization, and the
 *  SW algorithmic noise budget (Chapter "DSE") is preserved.
 */
class MaccDesignRuleValidationTest extends AnyFunSuite with ChiselScalatestTester {

  // ── helpers ──────────────────────────────────────────────────────────────
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

  private sealed trait RqTarget
  private case object RqINT8                 extends RqTarget
  private case class  RqFP(ot: ElementType)  extends RqTarget

  private def swRequantAndDecode(fp32: Float, target: RqTarget, sharedScale: Int): Double = target match {
    case RqINT8 =>
      val bits = floatToIntBits(fp32); val sign = (bits >>> 31) & 1
      val fp32Exp = (bits >>> 23) & 0xFF; val fp32Man = bits & 0x7FFFFF
      if (fp32Exp == 0) 0.0
      else {
        val k = fp32Exp - sharedScale + 6
        if (k < -23) 0.0
        else {
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
          val mag      = (mag7 + (if (roundUp) 1 else 0)).min(127)
          val int8     = if (sign == 0) mag else -mag
          int8.toDouble * math.pow(2.0, sharedScale - 133)
        }
      }
    case RqFP(ot) =>
      val outExpBits = ot.elementWidthExp; val outMantBits = ot.elementWidthMant
      val outBias    = ot.bias
      val bits       = floatToIntBits(fp32); val sign = (bits >>> 31) & 1
      val fp32Exp    = (bits >>> 23) & 0xFF; val fp32Man = bits & 0x7FFFFF
      val outExpFull = fp32Exp - sharedScale + outBias
      val outMaxExp  = (1 << outExpBits) - 2
      if (fp32Exp == 0 || outExpFull <= 0) 0.0
      else {
        val (decExp, decMant) =
          if (outExpFull > outMaxExp) (outMaxExp, (1 << outMantBits) - 1)
          else {
            val mantRaw   = fp32Man >>> (23 - outMantBits)
            val guardIdx  = 22 - outMantBits
            val guardBit  = (fp32Man >>> guardIdx) & 1
            val stickyBit = if (guardIdx > 0) (fp32Man & ((1 << guardIdx) - 1)) != 0 else false
            val roundUp   = guardBit == 1 && ((mantRaw & 1) == 1 || stickyBit)
            (outExpFull min outMaxExp,
             (mantRaw + (if (roundUp) 1 else 0)) & ((1 << outMantBits) - 1))
          }
        val mantVal = 1.0 + decMant.toDouble / (1 << outMantBits)
        val v = mantVal * math.pow(2.0, decExp - outBias + sharedScale - 127)
        if (sign == 1) -v else v
      }
  }
  private def chooseSharedScale(peakAbs: Double, target: RqTarget): Int = target match {
    case RqINT8 =>
      if (peakAbs < 1e-37) 127 else math.floor(math.log(peakAbs) / math.log(2)).toInt + 126
    case RqFP(ot) =>
      val outMaxExp = (1 << ot.elementWidthExp) - 2
      val peakBits  = floatToIntBits(peakAbs.toFloat)
      val peakExp   = (peakBits >>> 23) & 0xFF
      peakExp - outMaxExp + ot.bias
  }
  private def binIndexFp(rqValue: Double, ot: ElementType, sharedScale: Int): Long = {
    if (rqValue == 0.0) return 0L
    val mantBits = ot.elementWidthMant
    val v = math.abs(rqValue).toFloat
    val sign = if (rqValue < 0) -1L else 1L
    val bits = floatToIntBits(v)
    val fp32Exp = (bits >>> 23) & 0xFF; val fp32Man = bits & 0x7FFFFF
    val outExp  = fp32Exp - sharedScale + ot.bias
    val mantRaw = fp32Man >>> (23 - mantBits)
    if (outExp <= 0) return 0L
    sign * ((outExp.toLong << mantBits) | mantRaw.toLong)
  }
  private def binIndexInt8(rqValue: Double, sharedScale: Int): Long = {
    if (rqValue == 0.0) return 0L
    math.round(rqValue * math.pow(2.0, 133 - sharedScale)).toLong
  }
  private def binIndex(rqValue: Double, target: RqTarget, sharedScale: Int): Long =
    target match {
      case RqINT8   => binIndexInt8(rqValue, sharedScale)
      case RqFP(ot) => binIndexFp(rqValue, ot, sharedScale)
    }

  case class Cycle(trial: Int, cyc: Int, sA: Int, sB: Int, a: Seq[Int], b: Seq[Int])
  private def loadVec(path: String, V: Int): Vector[Cycle] = {
    val src = Source.fromFile(path)
    try {
      src.getLines().drop(1).toVector.map { line =>
        val p = line.split("\t")
        Cycle(p(0).toInt, p(1).toInt, p(2).toInt, p(3).toInt,
              (0 until V).map(i => p(4+i).toInt),
              (0 until V).map(i => p(4+V+i).toInt))
      }
    } finally src.close()
  }

  // ── Validation configurations ────────────────────────────────────────────
  private case class VCfg(label: String, tA: ElementType, tB: ElementType, sT: ScaleType, K: Int)
  // 11 pairs × 3 scales = 33 configs. Pairs span the full element-mantissa
  // product range from M_ext(A)+M_ext(B) = 4 (E2M1²) to 16 (INT8²), and
  // cover every MX element format (INT8, E5M2, E4M3, E3M2, E2M3, E2M1) at
  // least once.  Scales cover the SW-Pareto-relevant UE8M0 / UE6M2 / UE4M4
  // family.
  private val pairs: Seq[(String, ElementType, ElementType)] = Seq(
    ("INT8xINT8",  MXFormats.INT8, MXFormats.INT8),
    ("E3M2xE3M2",  MXFormats.E3M2, MXFormats.E3M2),
    ("E4M3xE4M3",  MXFormats.E4M3, MXFormats.E4M3),
    ("E5M2xE5M2",  MXFormats.E5M2, MXFormats.E5M2),
    ("E5M2xE4M3",  MXFormats.E5M2, MXFormats.E4M3),
    ("E4M3xINT8",  MXFormats.E4M3, MXFormats.INT8),
    ("E5M2xINT8",  MXFormats.E5M2, MXFormats.INT8),
    ("E4M3xE2M1",  MXFormats.E4M3, MXFormats.E2M1),   // A8W4 LLM combo
    // ── Added: full-mantissa-range coverage ──────────────────────────────
    ("E2M1xE2M1",  MXFormats.E2M1, MXFormats.E2M1),   // smallest M_ext = 4
    ("E2M3xE2M3",  MXFormats.E2M3, MXFormats.E2M3),   // covers E2M3 element
    // E5M2×E2M1 disabled: no precomputed test vectors in acc_trunc_vectors/
    // ("E5M2xE2M1",  MXFormats.E5M2, MXFormats.E2M1),
  )
  private val scaleTypes: Seq[(String, ScaleType)] = Seq(
    ("UE8M0", ScaleFormats.UE8M0),
    ("UE6M2", ScaleFormats.UE6M2),
    ("UE4M4", ScaleFormats.UE4M4),
  )
  private val K = 8192
  private val allConfigs: Seq[VCfg] = for {
    (pl, tA, tB) <- pairs
    (sl, sT)     <- scaleTypes
  } yield VCfg(s"${pl}_${sl}", tA, tB, sT, K)

  // Sharding via env vars (default: shard 0 of 1 = all configs).
  private val shardId    = sys.env.getOrElse("SHARD_ID",    "0").toInt
  private val shardCount = sys.env.getOrElse("SHARD_COUNT", "1").toInt
  // Bit-trim sweep: M_ACC_OFFSET is added to AccPrecision.recommended.
  // CONFIG_FILTER is a regex; only configs whose label matches are run.
  private val mAccOffset     = sys.env.getOrElse("M_ACC_OFFSET", "0").toInt
  private val configFilterRe = sys.env.getOrElse("CONFIG_FILTER", ".*").r
  private val configs = allConfigs
    .filter(c => configFilterRe.findFirstIn(c.label).isDefined)
    .zipWithIndex.collect { case (c, i) if i % shardCount == shardId => c }

  private val V = 4
  private val blockSize = 16     // NVFP4-style 16-element block (shared scale per 16 elements)

  private case class Arch(label: String, treeArch: TreeArch, cpb: Int)
  private val archs = Seq(
    Arch("CycleFP", TreeArch.Generic,      1),
    // Block-FX removed from this sweep — thesis adopts Cycle-FP only.
    // Arch("BlockFX", TreeArch.KulischInner, blockSize / V),
  )

  // Cuyckens-style relative ulp of the output element format.
  private def requantRelativeUlp(outType: ElementType): Double = {
    if (outType.name == "INT8") 1.0 / 127.0
    else math.pow(2.0, -(outType.elementWidthMant + 1))
  }

  test(s"HW design-rule validation Cuyckens-style: shard $shardId of $shardCount (offset=$mAccOffset)") {
    val offsetTag = if (mAccOffset == 0) "" else s"_off${mAccOffset}"
    val csvPath = s"macc_designrule_validation_shard${shardId}${offsetTag}.csv"
    val csv = new PrintWriter(new FileWriter(csvPath, /* append = */ false), true)
    println(s"\n=== Shard $shardId/$shardCount, M_ACC_OFFSET=$mAccOffset, " +
            s"${configs.size} configs: " +
            configs.map(_.label).mkString(", ") + " ===")
    csv.println(
      "config,arch,typeA,typeB,scaleType,K,M_acc_recommended,M_acc_actual," +
      "addErr_rel_median,addErr_rel_p95,addErr_rel_mean," +
      "requant_relative_ulp,safety_margin_median,safety_margin_p95," +
      "n_valid,n_trials"
    )

    for (cfg <- configs) {
      val scfg     = ScaleAddConfig(cfg.tA, cfg.tB, cfg.sT)
      val recM     = AccPrecision.recommended(scfg, cfg.K)
      val M_acc    = math.max(1, math.min(23, recM + mAccOffset))
      val target   = if (cfg.tA.name == "INT8") RqINT8 else RqFP(cfg.tA)
      println(f"\n=== ${cfg.label}  M_acc=$M_acc%2d (rec=$recM%2d, offset=$mAccOffset%+d)" +
              f"  outElem=${cfg.tA.name} ===")

      val path = s"acc_trunc_vectors/down_proj/${cfg.tA.name}_${cfg.tB.name}_${cfg.sT.name}_K${cfg.K}.tsv"
      val all = loadVec(path, V)
      val perTrial: Vector[Vector[Cycle]] =
        all.groupBy(_.trial).toVector.sortBy(_._1).map(_._2.sortBy(_.cyc))
      val nTrials = perTrial.size

      for (arch <- archs) {
        val blockHw  = scala.collection.mutable.ArrayBuffer[Float]()
        val blockRef = scala.collection.mutable.ArrayBuffer[Double]()
        var actualM  = 0
        try {
          test(new FDPUPostScaleReductionTree(
                 scfg, V, K = cfg.K,
                 treeArch = arch.treeArch,
                 cyclesPerBlock = arch.cpb,
                 accMantBits = M_acc,
                 istest = false)) { dut =>
            actualM = dut.actualAccMantBits
            dut.reset.poke(true.B); dut.io.validIn.poke(false.B)
            for (trial <- 0 until nTrials) {
              dut.io.resetAcc.poke(true.B); dut.clock.step(); dut.io.resetAcc.poke(false.B)
              var swRef = 0.0
              for (r <- perTrial(trial)) {
                val sAv = decodeScale(r.sA, cfg.sT); val sBv = decodeScale(r.sB, cfg.sT)
                swRef += r.a.zip(r.b).map { case (a, b) =>
                  decodeElement(a, cfg.tA) * decodeElement(b, cfg.tB) * sAv * sBv
                }.sum
                dut.io.op_a_i       .poke(packElems(r.a, cfg.tA.totalWidth).U)
                dut.io.op_b_i       .poke(packElems(r.b, cfg.tB.totalWidth).U)
                dut.io.share_exp_A_i.poke(r.sA.U)
                dut.io.share_exp_B_i.poke(r.sB.U)
                dut.io.validIn.poke(true.B); dut.clock.step(); dut.io.validIn.poke(false.B)
              }
              dut.clock.step(1)
              val rawAcc = dut.io.accOut.peek().litValue
              val hwAcc  = intBitsToFloat((rawAcc << (23 - dut.actualAccMantBits)).toInt)
              blockHw  += hwAcc
              blockRef += swRef
            }
          }
        } catch { case e: Exception => println(s"  [ERROR ${arch.label}] ${e.getMessage}") }

        // Cuyckens-literal metric (Cuyckens et al. 2024):
        //   ε_rel,i = |HW_acc,i − FP64_ref,i| / |FP64_ref,i|
        // filter |FP64_ref,i| > refThreshold (skip near-zero outliers),
        // then aggregate as median (primary), p95, mean.
        val refThreshold = 1e-6
        val ulpRel       = requantRelativeUlp(cfg.tA)
        val perTrialRel  = scala.collection.mutable.ArrayBuffer[Double]()
        for ((hw, ref) <- blockHw.zip(blockRef)) {
          if (math.abs(ref) > refThreshold) {
            perTrialRel += math.abs(hw - ref) / math.abs(ref)
          }
        }
        val sorted = perTrialRel.sorted
        val nValid = sorted.size
        val median = if (nValid > 0) sorted(nValid / 2) else Double.NaN
        val p95    = if (nValid > 0) sorted(math.min(nValid - 1, (0.95 * nValid).toInt)) else Double.NaN
        val mean   = if (nValid > 0) perTrialRel.sum / nValid else Double.NaN
        val safetyMargMedian = if (median > 0) ulpRel / median else Double.NaN
        val safetyMargP95    = if (p95    > 0) ulpRel / p95    else Double.NaN

        csv.println(f"${cfg.label},${arch.label},${cfg.tA.name},${cfg.tB.name},${cfg.sT.name},${cfg.K}," +
                    f"$M_acc,$actualM," +
                    f"$median%.6e,$p95%.6e,$mean%.6e," +
                    f"$ulpRel%.6e,$safetyMargMedian%.3f,$safetyMargP95%.3f," +
                    f"$nValid,$nTrials")
        csv.flush()
        println(f"  ${arch.label}%-8s M_acc=$actualM%2d" +
                f"  ε_med=$median%.3e  ε_p95=$p95%.3e  ulp=$ulpRel%.3e" +
                f"  μ_med=$safetyMargMedian%.1fx  μ_p95=$safetyMargP95%.1fx")
      }
    }
    csv.close()
    println(s"\nDone. Wrote $csvPath.")
  }
}
