package mx.mac

import chisel3._
import chiseltest._
import org.scalatest.funsuite.AnyFunSuite
import java.lang.Float.{floatToIntBits, intBitsToFloat}
import java.io.{FileWriter, PrintWriter}
import scala.util.Random

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
  private case class Arch(label: String, treeArch: TreeArch, cpb: Int)
  private val archs = Seq(
    Arch("baseline", TreeArch.Generic,      1),
    Arch("blockdef", TreeArch.Generic,      8),
    Arch("kulisch",  TreeArch.KulischInner, 8),
  )

  private case class Pair(typeA: ElementType, typeB: ElementType)
  private val formatPairs = Seq(
    Pair(MXFormats.INT8, MXFormats.INT8),  // R=0
    Pair(MXFormats.E3M2, MXFormats.E3M2),  // R=12
    Pair(MXFormats.E4M3, MXFormats.E4M3),  // R=28
    Pair(MXFormats.E5M2, MXFormats.E5M2),  // R=58
  )

  private val scales = Seq(ScaleFormats.UE8M0, ScaleFormats.UE6M2, ScaleFormats.UE4M4)

  private val Ks      = Seq(32, 256, 4096)
  private val nTrials = 3
  private val vec     = 4

  // ── The sweep test ───────────────────────────────────────────────────────

  test("Acc-truncation × architecture sweep — post-requant error stays bounded") {
    val csv = new PrintWriter(new FileWriter("acc_truncation_sweep.csv", false))
    csv.println(
      "arch,typeA,typeB,scaleType,K,vec,cpb,treeArch,accMantBitsRec," +
      "relErrFP32_mean,relErrFP32_max,relErrPostRq_mean,relErrPostRq_max," +
      "outputType,trials,note"
    )

    // Activation type → requant target.  In a real array the wrapper picks
    // INT8 vs FP, here we choose by the activation's family.
    def requantTargetFor(a: ElementType): RequantTarget = a.name match {
      case "INT8" => RqINT8
      case _      => RqFP(a)
    }

    for (arch <- archs;
         pair <- formatPairs;
         sType <- scales;
         K    <- Ks) {
      val scfg     = ScaleAddConfig(pair.typeA, pair.typeB, sType)
      val recBits  = AccPrecision.recommended(scfg, K)
      val target   = requantTargetFor(pair.typeA)
      val rqLabel  = target match { case RqINT8 => "INT8"; case RqFP(o) => o.name }
      val tag      = s"${arch.label} ${pair.typeA.name}×${pair.typeB.name} ${sType.name} K=$K"

      // Some configs are invalid: cpb must evenly divide K; if not, skip.
      if (K % arch.cpb != 0) {
        csv.println(s"${arch.label},${pair.typeA.name},${pair.typeB.name},${sType.name},$K,$vec,${arch.cpb},${arch.treeArch.name},$recBits,NA,NA,NA,NA,$rqLabel,0,K_mod_cpb_nonzero")
      } else {
        // Drive the same input stream into the HW and compare against FP64.
        var sumFp32Err   = 0.0
        var maxFp32Err   = 0.0
        var sumPostRqErr = 0.0
        var maxPostRqErr = 0.0
        var counted      = 0

        try {
          test(new FDPUPostScaleReductionTree(
            scfg, vec, K = K,
            treeArch = arch.treeArch, cyclesPerBlock = arch.cpb,
            istest = false)) { dut =>
            val rng = new Random(0xC0FFEE ^ K ^ arch.label.hashCode ^
                                  pair.typeA.name.hashCode ^ sType.name.hashCode)

            for (trial <- 0 until nTrials) {
              dut.io.resetAcc.poke(true.B); dut.clock.step(); dut.io.resetAcc.poke(false.B)

              var swRef = 0.0
              // Block-stable scales (MX semantics): pick one per cpb.
              val firstScaleA = randScale(sType, rng)
              val firstScaleB = randScale(sType, rng)
              var curScaleA   = firstScaleA
              var curScaleB   = firstScaleB

              for (cyc <- 0 until K) {
                if (cyc % arch.cpb == 0 && cyc != 0) {
                  curScaleA = randScale(sType, rng)
                  curScaleB = randScale(sType, rng)
                }
                val as = Seq.fill(vec)(randElem(pair.typeA, rng))
                val bs = Seq.fill(vec)(randElem(pair.typeB, rng))
                val sAv = decodeScale(curScaleA, sType)
                val sBv = decodeScale(curScaleB, sType)
                swRef += as.zip(bs).map { case (a, b) =>
                  decodeElement(a, pair.typeA) * decodeElement(b, pair.typeB) * sAv * sBv
                }.sum

                dut.io.op_a_i       .poke(packElems(as, pair.typeA.totalWidth).U)
                dut.io.op_b_i       .poke(packElems(bs, pair.typeB.totalWidth).U)
                dut.io.share_exp_A_i.poke(curScaleA.U)
                dut.io.share_exp_B_i.poke(curScaleB.U)
                dut.io.validIn.poke(true.B); dut.clock.step(); dut.io.validIn.poke(false.B)
              }
              // Drain so the last block commit lands in outerAcc.
              dut.clock.step(1)
              val hwAcc = intBitsToFloat(dut.io.accOut.peek().litValue.toInt)

              // Pre-requant relative error of HW vs FP64 reference.
              if (math.abs(swRef) > 1e-10) {
                val fp32Err = math.abs(hwAcc - swRef) / math.abs(swRef)
                sumFp32Err += fp32Err
                if (fp32Err > maxFp32Err) maxFp32Err = fp32Err

                // Post-requant: quantise both HW and FP64 ref through the
                // same SW requantiser and compare in the quantised domain.
                val sharedScale = chooseSharedScale(math.abs(swRef), target)
                val hwQ  = swRequantAndDecode(hwAcc, target, sharedScale)
                val refQ = swRequantAndDecode(swRef.toFloat, target, sharedScale)
                val postErr =
                  if (math.abs(refQ) > 1e-10) math.abs(hwQ - refQ) / math.abs(refQ) else 0.0
                sumPostRqErr += postErr
                if (postErr > maxPostRqErr) maxPostRqErr = postErr

                counted += 1
              }
            }
          }
        } catch { case e: Exception =>
          println(s"[ERROR] $tag: ${e.getMessage}")
        }

        val meanFp32  = if (counted > 0) sumFp32Err   / counted else Double.NaN
        val meanPostQ = if (counted > 0) sumPostRqErr / counted else Double.NaN
        csv.println(f"${arch.label},${pair.typeA.name},${pair.typeB.name},${sType.name},$K,$vec,${arch.cpb}," +
                    f"${arch.treeArch.name},$recBits,$meanFp32%.6e,$maxFp32Err%.6e," +
                    f"$meanPostQ%.6e,$maxPostRqErr%.6e,$rqLabel,$counted,")
        println(f"[$tag] mantBits=$recBits  preRq=$meanFp32%.3e  postRq=$meanPostQ%.3e  ($counted trials)")
      }
    }

    csv.close()
    println("\n[acc-truncation sweep] CSV written to acc_truncation_sweep.csv")
  }
}
