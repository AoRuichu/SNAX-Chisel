package mx.mac

import chisel3._
import chiseltest._
import mx.mac.deferred_archs.FDPUBlockDeferred
import org.scalatest.funsuite.AnyFunSuite
import java.lang.Float.{floatToIntBits, intBitsToFloat}
import java.io.{File, FileWriter, PrintWriter}
import scala.util.Random
import scala.io.Source

/** One cycle of pre-generated MX-quantised input vectors loaded from the
 *  Python-side gen_acc_truncation_vectors.py.  Trial = run index, cycle =
 *  position within the K-cycle dot product. */
case class MXVecCycle(trial: Int, cycle: Int, sA: Int, sB: Int, a: Seq[Int], b: Seq[Int])

/** Accumulator-truncation × architecture sweep.
 *
 *  Demonstrates that the K-derived accumulator mantissa width (truncated from
 *  the full FP32 23 bits) does NOT degrade end-to-end accuracy, because the
 *  downstream requantisation rounds to a much coarser grid (3-bit / 2-bit
 *  output mantissa).  The same property holds across all three accumulator
 *  back-bones: baseline (per-cycle scale apply), block-deferred, and Kulisch.
 *
 *  Output: `acc_truncation_sweep.csv` with one row per (arch, A, B, scale, K).
 *  Columns:
 *    arch, typeA, typeB, scaleType, K, vec, cpb, treeArch,
 *    accMantBitsRecommended,           — from AccPrecision.recommended
 *    relErrFP32_vs_FP64_mean,         — pre-requant error
 *    relErrPostRq_vs_FP64Rq_mean,     — quantised HW vs quantised FP64 ref
 *    outputUlpAtPeak,                 — relative ulp of the requant output
 *    trialsRun
 *
 *  The thesis claim — "truncation is free" — is supported by
 *  `relErrPostRq_vs_FP64Rq_mean ≈ 0` (post-requant), even when
 *  `relErrFP32_vs_FP64_mean` is non-zero (i.e., the accumulator truncates).
 */
class AccTruncationSweepTest extends AnyFunSuite with ChiselScalatestTester {

  // ── Re-use SW models from AccErrorRequantAnalysisTest verbatim ───────────
  // (decode helpers, swRequantINT8/FP8, swDotProd, packing).  Duplicated here
  // because they are private to that test class and we want a standalone test.

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

  private def randElem(t: ElementType, r: Random): Int = {
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

  private def randScale(s: ScaleType, r: Random): Int = {
    val expRange = 1 << s.expScaleWidth
    val expLo    = (s.bias - 1).max(1).min(expRange - 1)
    val expHi    = (s.bias + 1).max(expLo).min(expRange - 1)
    val exp      = expLo + r.nextInt(expHi - expLo + 1)
    val mant     = if (s.mantScaleWidth == 0) 0 else r.nextInt(1 << s.mantScaleWidth)
    (exp << s.mantScaleWidth) | mant
  }

  private def packElems(elems: Seq[Int], width: Int): BigInt =
    elems.zipWithIndex.foldLeft(BigInt(0)) { case (acc, (v, i)) =>
      acc | (BigInt(v & ((1 << width) - 1)) << (i * width))
    }

  /** Load pre-generated MX-quantised input vectors for one (workload, A, B,
   *  scale, K) configuration produced by gen_acc_truncation_vectors.py.  Path:
   *    acc_trunc_vectors/<workload>/<A>_<B>_<scale>_K<K>.tsv
   *  Each row = one cycle; columns = trial, cycle, sA, sB, a0..a{V-1}, b0..b{V-1}.
   *  Returns Some(vec) on success, None if the file is absent (caller falls
   *  back to in-Scala random generation for backward compatibility). */
  private def loadVectorFile(workload: String,
                             typeA: ElementType, typeB: ElementType,
                             sType: ScaleType, K: Int, V: Int)
  : Option[Vector[MXVecCycle]] = {
    val path = s"acc_trunc_vectors/$workload/${typeA.name}_${typeB.name}_${sType.name}_K$K.tsv"
    val f = new File(path)
    if (!f.exists) None
    else {
      val src = Source.fromFile(f)
      try {
        val lines = src.getLines().drop(1).toVector
        Some(lines.map { line =>
          val parts = line.split("\t")
          val trial = parts(0).toInt
          val cyc   = parts(1).toInt
          val sA    = parts(2).toInt
          val sB    = parts(3).toInt
          val a     = (0 until V).map(i => parts(4 + i).toInt)
          val b     = (0 until V).map(i => parts(4 + V + i).toInt)
          MXVecCycle(trial, cyc, sA, sB, a, b)
        })
      } finally src.close()
    }
  }

  // Software requant: choose INT8 or FP8/FP6 based on activation type
  private sealed trait RequantTarget
  private case object RqINT8                 extends RequantTarget
  private case class  RqFP(ot: ElementType)  extends RequantTarget

  /** Quantise an FP32 value through a software MX requantiser to the chosen
   *  target format and return the decoded value as Double for comparison.
   *  Uses a per-block sharedScale chosen as floor(log2(maxAbs)) - (outMaxExp - bias).
   */
  private def swRequantAndDecode(fp32: Float, target: RequantTarget, sharedScale: Int): Double = target match {
    case RqINT8 =>
      val bits    = floatToIntBits(fp32)
      val sign    = (bits >>> 31) & 1
      val fp32Exp = (bits >>> 23) & 0xFF
      val fp32Man = bits & 0x7FFFFF
      if (fp32Exp == 0) 0.0
      else {
        val k        = fp32Exp - sharedScale + 6
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
          val mag8     = mag7 + (if (roundUp) 1 else 0)
          val mag      = if (mag8 > 127) 127 else mag8
          val int8     = if (sign == 0) mag else -mag
          int8.toDouble * math.pow(2.0, sharedScale - 133)
        }
      }
    case RqFP(ot) =>
      val outExpBits  = ot.elementWidthExp
      val outMantBits = ot.elementWidthMant
      val outBias     = ot.bias
      val bits        = floatToIntBits(fp32)
      val sign        = (bits >>> 31) & 1
      val fp32Exp     = (bits >>> 23) & 0xFF
      val fp32Man     = bits & 0x7FFFFF
      val outExpFull  = fp32Exp - sharedScale + outBias
      val outMaxExp   = (1 << outExpBits) - 2
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
            ((outExpFull + (if ((mantRaw + 1) == (1 << outMantBits)) 0 else 0)) min outMaxExp,
             (mantRaw + (if (roundUp) 1 else 0)) & ((1 << outMantBits) - 1))
          }
        val mantVal = 1.0 + decMant.toDouble / (1 << outMantBits)
        val v = mantVal * math.pow(2.0, decExp - outBias + sharedScale - 127)
        if (sign == 1) -v else v
      }
  }

  /** Map a requantized output value back to a monotonic integer "bin index",
   *  so that |idx(a) − idx(b)| equals the number of grid points between two
   *  values on the same MX/INT8 output grid (under the same sharedScale).
   *
   *  This is the bridge from continuous error metrics to the IEEE 754
   *  "correctly rounded" criterion: bit-exact means |idx(hwQ) − idx(refQ)| = 0.
   */
  private def binIndexFp(rqValue: Double, ot: ElementType, sharedScale: Int): Long = {
    if (rqValue == 0.0) return 0L
    val mantBits = ot.elementWidthMant
    val v = math.abs(rqValue).toFloat
    val sign = if (rqValue < 0) -1L else 1L
    val bits    = floatToIntBits(v)
    val fp32Exp = (bits >>> 23) & 0xFF
    val fp32Man = bits & 0x7FFFFF
    val outExp  = fp32Exp - sharedScale + ot.bias
    val mantRaw = fp32Man >>> (23 - mantBits)
    if (outExp <= 0) return 0L
    sign * ((outExp.toLong << mantBits) | mantRaw.toLong)
  }

  private def binIndexInt8(rqValue: Double, sharedScale: Int): Long = {
    // INT8 output is a fixed-point grid: value = int × 2^(sharedScale - 133).
    // So bin index = round(value × 2^(133 - sharedScale)).
    if (rqValue == 0.0) return 0L
    math.round(rqValue * math.pow(2.0, 133 - sharedScale)).toLong
  }

  private def binIndex(rqValue: Double, target: RequantTarget, sharedScale: Int): Long =
    target match {
      case RqINT8   => binIndexInt8(rqValue, sharedScale)
      case RqFP(ot) => binIndexFp(rqValue, ot, sharedScale)
    }

  /** Pick a per-block sharedScale that places the peak value near the format's max. */
  private def chooseSharedScale(peakAbs: Double, target: RequantTarget): Int = target match {
    case RqINT8 =>
      // INT8: peak ≈ 127 × 2^(sharedScale-133)  ⇒  sharedScale = floor(log2(peakAbs)) + 126
      if (peakAbs < 1e-37) 127 else math.floor(math.log(peakAbs) / math.log(2)).toInt + 126
    case RqFP(ot) =>
      val outMaxExp = (1 << ot.elementWidthExp) - 2
      // FP: choose sharedScale so peak's fp32 exp lands at outMaxExp
      val peakBits = floatToIntBits(peakAbs.toFloat)
      val peakExp  = (peakBits >>> 23) & 0xFF
      peakExp - outMaxExp + ot.bias
  }

  // ── DSE knob ─────────────────────────────────────────────────────────────
  // Block-deferred is the focus architecture for the K-truncation safety study:
  // its inner FP accumulator is sized by M_acc, so the K-derived truncation
  // formula actually changes the design.  Long-integer-addition has a wide
  // fixed-point inner accumulator whose width is set by R (independent of M_acc),
  // making it a separate architectural axis covered in §arch-choice.
  private case class Arch(label: String, treeArch: TreeArch, cpb: Int)
  private val archs = Seq(
    Arch("blockdef", TreeArch.Generic, 8),
  )

  // Seven format pairs spanning the practical narrow-precision DSE
  // (drop INT8×INT8 — output-INT8 boundary, not a K-truncation effect).
  // Includes FP4 (E2M1²) and W4A8 (E4M3×E2M1) for LLM-quant relevance.
  private case class Pair(typeA: ElementType, typeB: ElementType)
  private val formatPairs = Seq(
    Pair(MXFormats.E4M3, MXFormats.INT8),  // asymmetric FP8 act × INT8 weight
    Pair(MXFormats.E2M3, MXFormats.E2M3),  // matched FP6 mantissa-heavy
    Pair(MXFormats.E4M3, MXFormats.E4M3),  // matched FP8 baseline
    Pair(MXFormats.E5M2, MXFormats.E4M3),  // asymmetric FP8 mixed precision
    Pair(MXFormats.E5M2, MXFormats.E5M2),  // matched FP8 high-range
    Pair(MXFormats.E2M1, MXFormats.E2M1),  // matched FP4 (NVFP4-style)
    Pair(MXFormats.E4M3, MXFormats.E2M1),  // W4A8 mixed precision
  )

  // Two scales cover {no scale-mant, with scale-mant} modes.  Lower exp widths
  // (UE3M5 and below) cannot represent the dynamic range of NN-realistic
  // activations (down_proj outliers up to 4384), so they are excluded by design.
  private val scales = Seq(ScaleFormats.UE8M0, ScaleFormats.UE4M4)

  // Workloads matched to measured LLaMA-3 layer statistics; see
  // gen_acc_truncation_vectors.py WORKLOADS dict.
  private val workloads = Seq("attn_qk", "down_proj", "mlp_gate")

  // K values: 512 / 2048 / 8192 bracket typical LLM-inference accumulation
  // depths.  Phase A (calibration) sweeps full K under down_proj; Phase B
  // (workload robustness) runs only K=8192 under the other workloads.
  private val Ks      = Seq(512, 2048, 8192)
  private def keepConfig(workload: String, K: Int): Boolean =
    workload == "down_proj" || K == 8192
  // nTrials = blockSize × nOutputBlocks.  blockSize matches the MX output-side
  // requant block (RequantFP8.buffer is sized to blockSize PE outputs sharing
  // one MaxScaleFinder pick).  nOutputBlocks gives multiple block-level samples
  // for statistical confidence on the post-rq mean.
  private val blockSize      = 32
  private val nOutputBlocks  = 3
  private val nTrials        = blockSize * nOutputBlocks
  private val vec     = 4

  // ── The sweep test ───────────────────────────────────────────────────────

  test("Acc-truncation × architecture sweep — post-requant error stays bounded") {
    // autoFlush=true so each row appears in the CSV as soon as it is printed,
    // letting the user watch progress without waiting for the JVM to exit.
    val csv = new PrintWriter(new FileWriter("acc_truncation_sweep.csv", false), true)
    csv.println(
      "workload,arch,typeA,typeB,scaleType,K,vec,cpb,treeArch,accMantBitsRec," +
      "relErrFP32_mean,relErrFP32_max,relErrPostRq_mean,relErrPostRq_max," +
      "binDist_mean,binDist_max,bitExactRate," +
      "outputType,trials,note"
    )

    // Activation type → requant target.  In a real array the wrapper picks
    // INT8 vs FP, here we choose by the activation's family.
    def requantTargetFor(a: ElementType): RequantTarget = a.name match {
      case "INT8" => RqINT8
      case _      => RqFP(a)
    }

    for (workload <- workloads;
         arch     <- archs;
         pair     <- formatPairs;
         sType    <- scales;
         K        <- Ks
         if keepConfig(workload, K)) {
      val scfg     = ScaleAddConfig(pair.typeA, pair.typeB, sType)
      val recBits  = AccPrecision.recommended(scfg, K)
      val target   = requantTargetFor(pair.typeA)
      val rqLabel  = target match { case RqINT8 => "INT8"; case RqFP(o) => o.name }
      val tag      = s"$workload ${arch.label} ${pair.typeA.name}×${pair.typeB.name} ${sType.name} K=$K"

      // Some configs are invalid: cpb must evenly divide K; if not, skip.
      if (K % arch.cpb != 0) {
        csv.println(s"$workload,${arch.label},${pair.typeA.name},${pair.typeB.name},${sType.name},$K,$vec,${arch.cpb},${arch.treeArch.name},$recBits,NA,NA,NA,NA,NA,NA,NA,$rqLabel,0,K_mod_cpb_nonzero")
      } else {
        // Drive the same input stream into the HW and compare against FP64.
        var sumFp32Err   = 0.0
        var maxFp32Err   = 0.0
        var sumPostRqErr = 0.0
        var maxPostRqErr = 0.0
        var counted      = 0
        // Buffer per-trial (hwAcc, swRef) so we can apply ONE shared scale
        // across the whole output-block (matches real HW: RequantFP8/INT8
        // pulls one max-abs over blockSize=nTrials PE outputs and reuses it
        // for every requant in the block — see PEArray.scala line ~145 +
        // RequantFP8.MaxScaleFinder).
        val blockHw  = scala.collection.mutable.ArrayBuffer[Float]()
        val blockRef = scala.collection.mutable.ArrayBuffer[Double]()

        // Try loading NN-realistic vectors generated by
        // gen_acc_truncation_vectors.py.  When present, the K cycles for every
        // trial are read from a TSV file; otherwise fall back to in-Scala
        // pseudo-random generation (legacy compact-sweep behaviour).
        val vectorFile = loadVectorFile(workload, pair.typeA, pair.typeB, sType, K, vec)
        val perTrialVectors: Option[Vector[Vector[MXVecCycle]]] = vectorFile.map { all =>
          // Group by trial index, preserving cycle order.
          all.groupBy(_.trial).toVector.sortBy(_._1).map(_._2.sortBy(_.cycle))
        }
        try {
          test(new FDPUBlockDeferred(
            scfg, vec, K = K,
            treeArch = arch.treeArch, cyclesPerBlock = arch.cpb,
            istest = false)) { dut =>
            // This codebase uses inverted reset polarity (asyncRstN = !reset),
            // so the implicit reset must be POKED HIGH for the design to leave
            // the reset state.  Never deassert it in the loop.
            dut.reset.poke(true.B)
            dut.io.validIn.poke(false.B)
            val rng = new Random(0xC0FFEE ^ K ^ arch.label.hashCode ^
                                  pair.typeA.name.hashCode ^ sType.name.hashCode)

            for (trial <- 0 until nTrials) {
              dut.io.resetAcc.poke(true.B); dut.clock.step(); dut.io.resetAcc.poke(false.B)

              var swRef = 0.0
              // Block-stable scales (MX semantics): pick one per cpb.
              var curScaleA = 0
              var curScaleB = 0

              for (cyc <- 0 until K) {
                val (as, bs, sA, sB) = perTrialVectors match {
                  case Some(perTrial) =>
                    val v = perTrial(trial)(cyc)
                    (v.a, v.b, v.sA, v.sB)
                  case None =>
                    if (cyc == 0 || cyc % arch.cpb == 0) {
                      curScaleA = randScale(sType, rng)
                      curScaleB = randScale(sType, rng)
                    }
                    val a = Seq.fill(vec)(randElem(pair.typeA, rng))
                    val b = Seq.fill(vec)(randElem(pair.typeB, rng))
                    (a, b, curScaleA, curScaleB)
                }
                val sAv = decodeScale(sA, sType)
                val sBv = decodeScale(sB, sType)
                swRef += as.zip(bs).map { case (a, b) =>
                  decodeElement(a, pair.typeA) * decodeElement(b, pair.typeB) * sAv * sBv
                }.sum

                dut.io.op_a_i       .poke(packElems(as, pair.typeA.totalWidth).U)
                dut.io.op_b_i       .poke(packElems(bs, pair.typeB.totalWidth).U)
                dut.io.share_exp_A_i.poke(sA.U)
                dut.io.share_exp_B_i.poke(sB.U)
                dut.io.validIn.poke(true.B); dut.clock.step(); dut.io.validIn.poke(false.B)
              }
              // Drain so the last block commit lands in outerAcc.
              dut.clock.step(1)
              // io.accOut is narrow ({sign[1], exp[8], mant[actualAccMantBits]});
              // left-shift the mantissa into FP32's 23-bit field for intBitsToFloat.
              val rawAcc = dut.io.accOut.peek().litValue
              val hwAcc  = intBitsToFloat((rawAcc << (23 - dut.actualAccMantBits)).toInt)

              // Pre-requant relative error of HW vs FP64 reference (per-trial).
              if (math.abs(swRef) > 1e-10) {
                val fp32Err = math.abs(hwAcc - swRef) / math.abs(swRef)
                sumFp32Err += fp32Err
                if (fp32Err > maxFp32Err) maxFp32Err = fp32Err
                counted += 1
              }
              // Buffer for the output-block shared-scale post-requant pass.
              blockHw  += hwAcc
              blockRef += swRef
            }
          }
        } catch { case e: Exception =>
          println(s"[ERROR] $tag: ${e.getMessage}")
        }

        // ── Post-requant pass: partition trials into output-blocks of size
        //    `blockSize`, each block sharing ONE requant scale (= chooseShared-
        //    Scale over the block's peak |swRef|).  Real HW: RequantFP8/INT8's
        //    `buffer` collects blockSize PE outputs from successive K-cycle
        //    accumulations, MaxScaleFinder picks one scale, and every element
        //    gets requantised with it — an outlier in any one element
        //    compresses the LSB precision of the others.
        var binDistSum:  Long = 0L
        var binDistMax:  Long = 0L
        var bitExactCnt: Int  = 0
        var bdSamples:   Int  = 0
        val nBlocks = blockRef.length / blockSize
        for (b <- 0 until nBlocks) {
          val refs = blockRef.slice(b * blockSize, (b + 1) * blockSize)
          val hws  = blockHw .slice(b * blockSize, (b + 1) * blockSize)
          val peakAbs = refs.map(math.abs).max
          val sharedScale = chooseSharedScale(peakAbs, target)
          for ((hw, ref) <- hws.zip(refs)) {
            // Relative-error metric (legacy, kept for backward compat).
            if (math.abs(ref) > 1e-10) {
              val hwQ  = swRequantAndDecode(hw,          target, sharedScale)
              val refQ = swRequantAndDecode(ref.toFloat, target, sharedScale)
              val postErr =
                if (math.abs(refQ) > 1e-10) math.abs(hwQ - refQ) / math.abs(refQ) else 0.0
              sumPostRqErr += postErr
              if (postErr > maxPostRqErr) maxPostRqErr = postErr
            }
            // Bin-agreement metrics B + D (primary metric for safety claim):
            //   B = mean |bin_idx(hwQ) - bin_idx(refQ)|  (grid-step distance)
            //   D = fraction of trials where bin_idx equals (bit-exact)
            val hwQ  = swRequantAndDecode(hw,          target, sharedScale)
            val refQ = swRequantAndDecode(ref.toFloat, target, sharedScale)
            val hwIdx  = binIndex(hwQ,  target, sharedScale)
            val refIdx = binIndex(refQ, target, sharedScale)
            val dist = math.abs(hwIdx - refIdx)
            binDistSum += dist
            if (dist > binDistMax) binDistMax = dist
            if (dist == 0L) bitExactCnt += 1
            bdSamples += 1
          }
        }

        val meanFp32   = if (counted > 0) sumFp32Err   / counted else Double.NaN
        val meanPostQ  = if (counted > 0) sumPostRqErr / counted else Double.NaN
        val binDistMean = if (bdSamples > 0) binDistSum.toDouble / bdSamples else Double.NaN
        val bitExactR   = if (bdSamples > 0) bitExactCnt.toDouble / bdSamples else Double.NaN
        csv.println(f"$workload,${arch.label},${pair.typeA.name},${pair.typeB.name},${sType.name},$K,$vec,${arch.cpb}," +
                    f"${arch.treeArch.name},$recBits,$meanFp32%.6e,$maxFp32Err%.6e," +
                    f"$meanPostQ%.6e,$maxPostRqErr%.6e," +
                    f"$binDistMean%.4f,$binDistMax,$bitExactR%.4f," +
                    f"$rqLabel,$counted,")
        println(f"[$tag] mantBits=$recBits  preRq=$meanFp32%.3e  postRq=$meanPostQ%.3e  " +
                f"B=$binDistMean%.3f (max=$binDistMax) D=${bitExactR*100}%.1f%% ($counted trials)")
      }
    }

    csv.close()
    println("\n[acc-truncation sweep] CSV written to acc_truncation_sweep.csv")
  }
}
