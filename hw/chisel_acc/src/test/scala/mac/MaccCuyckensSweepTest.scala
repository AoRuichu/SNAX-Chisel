package mx.mac

import chisel3._
import chiseltest._
import mx.mac.deferred_archs.FDPUBlockDeferred
import org.scalatest.funsuite.AnyFunSuite
import java.lang.Float.{floatToIntBits, intBitsToFloat}
import java.io.{FileWriter, PrintWriter}
import scala.io.Source

/** Cuyckens-style addition-error vs requant-error sweep.
 *
 *  For each (pair, scale) at K=8192, sweep M_acc ∈ {7,9,11,13,14,15} to
 *  produce the noise-vs-saturation curve used in the Cuyckens-style plot
 *  (pre-rq addition error vs post-rq bin distance).
 *
 *  Sharding: env vars SHARD_ID (0..SHARD_COUNT-1) and SHARD_COUNT select a
 *  disjoint subset of (pair, scale) combos so multiple sbt JVMs can run in
 *  parallel without contending. Each shard writes to its own CSV
 *  (macc_cuyckens_shard${SHARD_ID}.csv); merge externally after all shards
 *  complete.
 *
 *  Configs covered (8 missing combos @ K=8192, drop INT8²):
 *    E3M2×E3M2  × {UE4M4, UE8M0}
 *    E4M3×E4M3  × {UE8M0}              (UE4M4 done in main sweep)
 *    E4M3×INT8  × {UE4M4}              (UE8M0 done in main sweep)
 *    E5M2×E4M3  × {UE4M4, UE8M0}
 *    E5M2×E5M2  × {UE4M4, UE8M0}
 */
class MaccCuyckensSweepTest extends AnyFunSuite with ChiselScalatestTester {

  // ── helpers (mirror MaccCalibrationTest) ─────────────────────────────────
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
          val shiftAmt = 23 - k
          val shifted: Long  = if (shiftAmt >= 48) 0L
                               else if (shiftAmt < 0) mantExt << (-shiftAmt)
                               else mantExt >>> shiftAmt
          val mag7 = ((shifted >>> 24) & 0x7FL).toInt
          val guardBit = ((shifted >>> 23) & 1L).toInt
          val sticky = (shifted & 0x7FFFFFL) != 0L
          val roundUp = guardBit == 1 && ((mag7 & 1) == 1 || sticky)
          val mag = (mag7 + (if (roundUp) 1 else 0)).min(127)
          val int8 = if (sign == 0) mag else -mag
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
            val mantRaw  = fp32Man >>> (23 - outMantBits)
            val guardIdx = 22 - outMantBits
            val guardBit = (fp32Man >>> guardIdx) & 1
            val stickyBit = if (guardIdx > 0) (fp32Man & ((1 << guardIdx) - 1)) != 0 else false
            val roundUp = guardBit == 1 && ((mantRaw & 1) == 1 || stickyBit)
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

  // ── Cuyckens-style sweep configurations ──────────────────────────────────
  private case class PCfg(label: String, tA: ElementType, tB: ElementType,
                          sT: ScaleType, K: Int)

  private val maccSamples = Seq(7, 9, 11, 13, 14, 15)

  // 8 missing (pair, scale) combos @ K=8192 (drop INT8², skip already-covered).
  private val allConfigs = Seq(
    PCfg("E3M2xE3M2_UE4M4_K8192", MXFormats.E3M2, MXFormats.E3M2, ScaleFormats.UE4M4, 8192),
    PCfg("E3M2xE3M2_UE8M0_K8192", MXFormats.E3M2, MXFormats.E3M2, ScaleFormats.UE8M0, 8192),
    PCfg("E4M3xE4M3_UE8M0_K8192", MXFormats.E4M3, MXFormats.E4M3, ScaleFormats.UE8M0, 8192),
    PCfg("E4M3xINT8_UE4M4_K8192", MXFormats.E4M3, MXFormats.INT8, ScaleFormats.UE4M4, 8192),
    PCfg("E5M2xE4M3_UE4M4_K8192", MXFormats.E5M2, MXFormats.E4M3, ScaleFormats.UE4M4, 8192),
    PCfg("E5M2xE4M3_UE8M0_K8192", MXFormats.E5M2, MXFormats.E4M3, ScaleFormats.UE8M0, 8192),
    PCfg("E5M2xE5M2_UE4M4_K8192", MXFormats.E5M2, MXFormats.E5M2, ScaleFormats.UE4M4, 8192),
    PCfg("E5M2xE5M2_UE8M0_K8192", MXFormats.E5M2, MXFormats.E5M2, ScaleFormats.UE8M0, 8192),
  )

  // Sharding via env vars. Default: shard 0 of 1 → run all configs.
  private val shardId    = sys.env.getOrElse("SHARD_ID",    "0").toInt
  private val shardCount = sys.env.getOrElse("SHARD_COUNT", "1").toInt
  private val configs    = allConfigs.zipWithIndex.collect {
    case (c, i) if i % shardCount == shardId => c
  }

  private val V = 4
  private val cpb = 8
  private val blockSize = 32

  test(s"Cuyckens-style sweep — shard $shardId of $shardCount") {
    val csvPath = s"macc_cuyckens_shard${shardId}.csv"
    val csv = new PrintWriter(new FileWriter(csvPath, /* append = */ false), true)
    csv.println(
      "config,typeA,typeB,scaleType,K,M_acc_requested,M_acc_actual,recBits," +
      "binDist_mean,binDist_max,bitExactRate," +
      "addErr_mean,addErr_median,addErr_max," +
      "n_blocks,binDist_per_block,bitExact_per_block,n_trials"
    )

    println(s"\n=== Shard $shardId/$shardCount — ${configs.size} configs: " +
            configs.map(_.label).mkString(", ") + " ===")

    for (cfg <- configs) {
      val scfg     = ScaleAddConfig(cfg.tA, cfg.tB, cfg.sT)
      val recBits  = AccPrecision.recommended(scfg, cfg.K)
      val target   = if (cfg.tA.name == "INT8") RqINT8 else RqFP(cfg.tA)
      println(s"\n=== ${cfg.label}  recBits=$recBits  mantWidth=${scfg.resScaleAddMantWidth} ===")

      val path = s"acc_trunc_vectors/${cfg.tA.name}_${cfg.tB.name}_${cfg.sT.name}_K${cfg.K}.tsv"
      val all = loadVec(path, V)
      val perTrial: Vector[Vector[Cycle]] =
        all.groupBy(_.trial).toVector.sortBy(_._1).map(_._2.sortBy(_.cyc))
      val nTrials = perTrial.size

      for (M_acc <- maccSamples) {
        val blockHw  = scala.collection.mutable.ArrayBuffer[Float]()
        val blockRef = scala.collection.mutable.ArrayBuffer[Double]()
        var actualM  = 0
        try {
          test(new FDPUBlockDeferred(scfg, V, K = cfg.K,
                                               treeArch = TreeArch.Generic,
                                               cyclesPerBlock = cpb,
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
        } catch { case e: Exception => println(s"  [ERROR M_acc=$M_acc] ${e.getMessage}") }

        // Per-block B and D
        val nBlocks = blockRef.length / blockSize
        val perBlockBin  = scala.collection.mutable.ArrayBuffer[Double]()
        val perBlockBitR = scala.collection.mutable.ArrayBuffer[Double]()
        var binDistMaxAll: Long = 0L
        for (b <- 0 until nBlocks) {
          val refs = blockRef.slice(b * blockSize, (b + 1) * blockSize)
          val hws  = blockHw .slice(b * blockSize, (b + 1) * blockSize)
          val peakAbs = refs.map(math.abs).max
          val sharedScale = chooseSharedScale(peakAbs, target)
          var blkBinSum: Long = 0L; var blkExact: Int = 0
          for ((hw, ref) <- hws.zip(refs)) {
            val hwQ  = swRequantAndDecode(hw, target, sharedScale)
            val refQ = swRequantAndDecode(ref.toFloat, target, sharedScale)
            val hwIdx  = binIndex(hwQ, target, sharedScale)
            val refIdx = binIndex(refQ, target, sharedScale)
            val dist = math.abs(hwIdx - refIdx)
            blkBinSum += dist
            if (dist > binDistMaxAll) binDistMaxAll = dist
            if (dist == 0L) blkExact += 1
          }
          perBlockBin  += blkBinSum.toDouble / blockSize
          perBlockBitR += blkExact.toDouble  / blockSize
        }
        val binMean = if (perBlockBin.nonEmpty)  perBlockBin.sum  / perBlockBin.size  else Double.NaN
        val bitR    = if (perBlockBitR.nonEmpty) perBlockBitR.sum / perBlockBitR.size else Double.NaN

        // Pre-rq direct addition error (Cuyckens primary metric)
        val perTrialAddErr = scala.collection.mutable.ArrayBuffer[Double]()
        for ((hw, ref) <- blockHw.zip(blockRef)) {
          if (math.abs(ref) > 1e-10) {
            perTrialAddErr += math.abs(hw - ref) / math.abs(ref)
          }
        }
        val addErrMean = if (perTrialAddErr.nonEmpty) perTrialAddErr.sum / perTrialAddErr.size else Double.NaN
        val addErrMedian = if (perTrialAddErr.nonEmpty) {
          val sorted = perTrialAddErr.sorted
          sorted(sorted.size / 2)
        } else Double.NaN
        val addErrMax = if (perTrialAddErr.nonEmpty) perTrialAddErr.max else Double.NaN
        val binSeries  = perBlockBin.map(b => f"$b%.4f").mkString(";")
        val bitRSeries = perBlockBitR.map(b => f"$b%.4f").mkString(";")
        csv.println(f"${cfg.label},${cfg.tA.name},${cfg.tB.name},${cfg.sT.name},${cfg.K}," +
                    f"$M_acc,$actualM,$recBits,$binMean%.4f,$binDistMaxAll,$bitR%.4f," +
                    f"$addErrMean%.6e,$addErrMedian%.6e,$addErrMax%.6e," +
                    f"$nBlocks,$binSeries,$bitRSeries,${blockRef.length}")
        csv.flush()
        println(f"  M_acc=$M_acc%2d (actual=$actualM%2d, recBits=$recBits)  " +
                f"B=$binMean%.3f D=${bitR*100}%.1f%%  addErr=$addErrMean%.3e")
      }
    }
    csv.close()
    println(s"\n[Shard $shardId] Done. Wrote $csvPath.")
  }
}
