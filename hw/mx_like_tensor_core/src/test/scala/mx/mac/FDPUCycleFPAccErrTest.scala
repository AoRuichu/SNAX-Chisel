package mx.mac

import chisel3._
import chiseltest._
import org.scalatest.funsuite.AnyFunSuite

import java.io.{File, FileWriter, PrintWriter}
import java.lang.Float.intBitsToFloat
import scala.io.Source

/** Direct acc-error validation for the deployed cycleFP-fused FDPU arch.
 *
 *  Question this test answers:
 *    The macc sweep (`macc_sweep_all_<workload>_final.csv`) was run against
 *    `FDPUBlockDeferred` in chisel_acc (3-RNE, block-deferred scale apply).
 *    The DEPLOYED arch in mx_like_tensor_core is `FDPU` (2-RNE, per-cycle
 *    scale apply via FusedScaleAccumulator).  These arches have DIFFERENT
 *    per-cycle alignment-shift budgets, so blockdef sweep numbers do not
 *    directly transfer.  This test replays the SAME per-cycle stimulus
 *    against the deployed FDPU and reports `addErr = |HW − FP64| / |FP64|`
 *    aggregated across trials, compared to the same `floor_emp_median` used
 *    by the selection rule.  Pass condition: median(addErr) < 0.5 × floor.
 *
 *  Inputs:
 *    - Stimulus TSV: ../chisel_acc/acc_trunc_vectors/<workload>/<label>.tsv
 *    - M_acc:        data/macc_final_selection.csv (mx_like_tensor_core copy)
 *    - Floor ref:    ../chisel_acc/macc_sweep_all_<workload>_final.csv
 *
 *  Env vars (mirrors MaccDesignRuleValidationTest):
 *    - WORKLOAD          default "q_proj_l0"
 *    - CONFIG_FILTER     regex, default ".*"
 *    - VECTORS_DIR       default "../chisel_acc/acc_trunc_vectors"
 *    - FLOOR_CSV_DIR     default "../chisel_acc"
 *    - MACC_CSV          default "data/macc_final_selection.csv"
 *    - M_ACC_OFFSET      integer, added to CSV M_acc (default 0); use to
 *                        stress-test at smaller M values.
 */
class FDPUCycleFPAccErrTest extends AnyFunSuite with ChiselScalatestTester {

  // ── FP element / scale decode (identical to MaccDesignRuleValidationTest) ──
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

  // ── Stimulus TSV loader ────────────────────────────────────────────────────
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

  // ── Config table (same 30 configs the chisel_acc sweep covered) ────────────
  private case class VCfg(label: String, tA: ElementType, tB: ElementType, sT: ScaleType)
  private val pairs: Seq[(String, ElementType, ElementType)] = Seq(
    ("INT8_INT8",  MXFormats.INT8, MXFormats.INT8),
    ("E3M2_E3M2",  MXFormats.E3M2, MXFormats.E3M2),
    ("E4M3_E4M3",  MXFormats.E4M3, MXFormats.E4M3),
    ("E5M2_E5M2",  MXFormats.E5M2, MXFormats.E5M2),
    ("E5M2_E4M3",  MXFormats.E5M2, MXFormats.E4M3),
    ("E4M3_INT8",  MXFormats.E4M3, MXFormats.INT8),
    ("E5M2_INT8",  MXFormats.E5M2, MXFormats.INT8),
    ("E4M3_E2M1",  MXFormats.E4M3, MXFormats.E2M1),
    ("E2M1_E2M1",  MXFormats.E2M1, MXFormats.E2M1),
    ("E2M3_E2M3",  MXFormats.E2M3, MXFormats.E2M3),
  )
  private val scaleTypes: Seq[(String, ScaleType)] = Seq(
    ("UE8M0", ScaleFormats.UE8M0),
    ("UE6M2", ScaleFormats.UE6M2),
    ("UE4M4", ScaleFormats.UE4M4),
  )
  private val allConfigs: Seq[VCfg] = for {
    (pl, tA, tB) <- pairs
    (sl, sT)     <- scaleTypes
  } yield VCfg(s"${pl}_${sl}", tA, tB, sT)

  private val workload = sys.env.getOrElse("WORKLOAD", "q_proj_l0")
  // Comma-separated list of K (accumulation depths) to sweep per config.
  // Verilator binary is per-config, but different K → different stimulus =
  // still per-config compile cost.  Multiple K in one invocation still saves
  // the sbt startup + Chisel elab across K values.
  // Available K per workload: 512, 2048, 8192.
  private val ksEnv = sys.env.getOrElse("KS", "2048")
  private val ks    = ksEnv.split(",").map(_.trim.toInt).filter(_ > 0).toSeq
  private val configRe  = sys.env.getOrElse("CONFIG_FILTER", ".*").r
  private val vectorsDir = sys.env.getOrElse("VECTORS_DIR",   "../chisel_acc/acc_trunc_vectors")
  private val floorDir   = sys.env.getOrElse("FLOOR_CSV_DIR", "../chisel_acc")
  private val maccCsv    = sys.env.getOrElse("MACC_CSV",      "data/macc_final_selection.csv")
  private val mAccOffset = sys.env.getOrElse("M_ACC_OFFSET",  "0").toInt

  private val configs = allConfigs.filter(c => configRe.findFirstIn(c.label).isDefined)
  private val K = 2048
  private val V = 4

  // ── Load M_acc CSV (mx_like_tensor_core schema) ────────────────────────────
  private def loadMaccMap(path: String): Map[(String, String, String), Int] = {
    val f = new File(path)
    if (!f.exists) { println(s"[warn] M_acc CSV not found: $path"); return Map.empty }
    val src = Source.fromFile(f)
    val lines = try src.getLines().toList finally src.close()
    val header = lines.head.split(",").map(_.trim)
    val (iA, iB, iS, iM) = (
      header.indexOf("act"),   header.indexOf("weight"),
      header.indexOf("scale"), header.indexOf("m_acc"))
    lines.tail.flatMap { ln =>
      val p = ln.split(",").map(_.trim)
      if (p.length > math.max(iM, math.max(iA, math.max(iB, iS))))
        Some((p(iA), p(iB), p(iS)) -> p(iM).toInt)
      else None
    }.toMap
  }

  // ── Load floor_emp_median CSV (chisel_acc sweep schema) ────────────────────
  private def loadFloorMap(path: String): Map[(String, String, String), Double] = {
    val f = new File(path)
    if (!f.exists) { println(s"[warn] floor CSV not found: $path"); return Map.empty }
    val src = Source.fromFile(f)
    val lines = try src.getLines().toList finally src.close()
    val header = lines.head.split(",").map(_.trim)
    val (iA, iB, iS, iF) = (
      header.indexOf("typeA"), header.indexOf("typeB"),
      header.indexOf("scaleType"), header.indexOf("floor_emp_median"))
    // Keep FIRST row per config (all rows share the same floor per config).
    val builder = scala.collection.mutable.LinkedHashMap.empty[(String, String, String), Double]
    lines.tail.foreach { ln =>
      val p = ln.split(",").map(_.trim)
      if (p.length > math.max(iF, math.max(iA, math.max(iB, iS)))) {
        val key = (p(iA), p(iB), p(iS))
        if (!builder.contains(key)) builder(key) = p(iF).toDouble
      }
    }
    builder.toMap
  }

  private val mAccMap = loadMaccMap(maccCsv)
  private val floorMap = loadFloorMap(s"$floorDir/macc_sweep_all_${workload}_final.csv")

  // ── Main sweep ─────────────────────────────────────────────────────────────
  test(s"FDPU (cycleFP-fused) acc-error vs FP64 ref — workload=$workload, Ks=[${ks.mkString(",")}], offset=$mAccOffset") {
    val suffix = if (ks.size == 1) s"K${ks.head}" else s"K${ks.mkString("-")}"
    val outCsvPath = s"fdpu_cyclefp_accerr_${workload}_${suffix}${if (mAccOffset==0) "" else s"_off${mAccOffset}"}.csv"
    val csv = new PrintWriter(new FileWriter(outCsvPath, false), true)
    csv.println("label,typeA,typeB,scaleType,K,M_acc," +
                "addErr_median,addErr_mean,addErr_p95,addErr_p99," +
                "floor_emp_median,ratio_mean,ratio_p99,verdict,n_valid,n_trials")

    // Verdict: PASS iff  mean < 0.5 × floor  (Cuyckens Fig 4 alignment).
    println(f"\n${"config"}%-30s ${"K"}%-6s ${"M"}%-3s ${"med"}%-11s ${"mean"}%-11s ${"p95"}%-11s ${"p99"}%-11s ${"floor"}%-11s ${"r_mn"}%-6s ${"r_p99"}%-6s verdict")
    println("-" * 135)

    var pass = 0; var fail = 0; var skipped = 0
    for (cfg <- configs) {
      val key = (cfg.tA.name, cfg.tB.name, cfg.sT.name)
      val mCsv = mAccMap.get(key)
      val floor = floorMap.getOrElse(key, Double.NaN)
      if (mCsv.isEmpty) {
        println(f"${cfg.label}%-30s  [skip: not in $maccCsv]")
        skipped += 1
      } else {
        val M_acc = math.max(1, math.min(23, mCsv.get + mAccOffset))
        // Load every K's stimulus up-front so we know which exist before spinning
        // up verilator for this config.
        val loaded: Seq[(Int, Vector[Cycle])] = ks.flatMap { k =>
          val tsvPath = s"$vectorsDir/$workload/${cfg.label}_K$k.tsv"
          if (new File(tsvPath).exists) Some(k -> loadVec(tsvPath, V))
          else { println(f"${cfg.label}%-30s K=$k%-4d M=$M_acc%2d  [skip: no $tsvPath]"); None }
        }
        if (loaded.isEmpty) { skipped += 1 }
        else {
          val scfg = ScaleAddConfig(cfg.tA, cfg.tB, cfg.sT)
          // K is a compile-time param of FDPU (only used for legacy AccPrecision),
          // so we can use the LARGEST K in the FDPU constructor; runtime K just
          // controls how many cycles we drive per trial.  One verilator binary
          // per config, loop Ks inside.
          val maxK = loaded.map(_._1).max
          val perKResults = scala.collection.mutable.ArrayBuffer[(Int, Seq[(Float, Double)])]()

          test(new FDPU(scfg, vectorSize = V, K = maxK, accMantBits = M_acc))
            .withAnnotations(Seq(VerilatorBackendAnnotation)) { dut =>
            val actualM = dut.actualAccMantBits
            dut.reset.poke(true.B); dut.io.validIn.poke(false.B)
            for ((kVal, all) <- loaded) {
              val perTrial = all.groupBy(_.trial).toVector.sortBy(_._1).map(_._2.sortBy(_.cyc))
              val trialResults = scala.collection.mutable.ArrayBuffer[(Float, Double)]()
              for (trial <- perTrial.indices) {
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
                val raw = dut.io.accOut.peek().litValue
                val hw  = intBitsToFloat((raw << (23 - actualM)).toInt)
                trialResults += ((hw, swRef))
              }
              perKResults += ((kVal, trialResults.toSeq))
            }
          }

          // Post-process each K's results
          for ((kVal, results) <- perKResults) {
            val refThreshold = 1e-6
            val rel = results.flatMap { case (hw, ref) =>
              if (math.abs(ref) > refThreshold) Some(math.abs(hw - ref) / math.abs(ref))
              else None
            }.sorted
            val nTrials = results.size
            val nValid  = rel.size
            val median  = if (nValid > 0) rel(nValid / 2) else Double.NaN
            val mean    = if (nValid > 0) rel.sum / nValid else Double.NaN
            val p95     = if (nValid > 0) rel(math.min(nValid - 1, (0.95 * nValid).toInt)) else Double.NaN
            val p99     = if (nValid > 0) rel(math.min(nValid - 1, (0.99 * nValid).toInt)) else Double.NaN
            val rMean   = if (floor > 0) mean / floor else Double.NaN
            val rP99    = if (floor > 0) p99  / floor else Double.NaN
            val verdict =
              if (rMean.isNaN || floor.isNaN)      "no-floor"
              else if (rMean < 0.5)                { pass += 1; "PASS" }
              else                                 { fail += 1; "FAIL" }

            println(f"${cfg.label}%-30s $kVal%-6d $M_acc%3d $median%.4e $mean%.4e $p95%.4e $p99%.4e $floor%.4e $rMean%6.3f $rP99%6.3f $verdict")
            csv.println(f"${cfg.label},${cfg.tA.name},${cfg.tB.name},${cfg.sT.name},$kVal,$M_acc," +
                        f"$median%.6e,$mean%.6e,$p95%.6e,$p99%.6e," +
                        f"$floor%.6e,$rMean%.4f,$rP99%.4f,$verdict,$nValid,$nTrials")
          }  // per-K post-processing loop
        }  // else (loaded.nonEmpty)
      }  // else (mCsv exists)
    }  // config loop
    csv.close()
    println("-" * 110)
    println(s"Summary: $pass PASS, $fail FAIL, $skipped skipped.  CSV → $outCsvPath")
    // Do NOT fail the ScalaTest here — per-config verdict is in the CSV; the
    // test itself succeeds regardless so we can inspect the full sweep.
  }
}
