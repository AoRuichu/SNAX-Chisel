// Focused debug spec: instantiate SimpleDPU with debug=true, feed one
// specific workload, dump every cycle's accreg vs Python-style accumulation.

package mx_simple

import chisel3._
import chiseltest._
import chiseltest.simulator.VerilatorBackendAnnotation
import org.scalatest.funsuite.AnyFunSuite

import scala.io.Source

class SimpleDPUDebugSpec extends AnyFunSuite with ChiselScalatestTester {

  private val CFG = "INT8_INT8_UE7M1"

  test(s"$CFG — per-cycle accreg trace") {
    val cfg = DPUConfig(Elem.byName("INT8"), Elem.byName("INT8"), Scale.byName("UE7M1"))
    val ww  = Widths(cfg)

    // ── Parse workload JSON ────────────────────────────────
    // Read all 16 cycles from /tmp/vectest/<CFG>_K64_bs16_vec4_seed0.json.
    val jsonPath = s"/tmp/vectest/${CFG}_K64_bs16_vec4_seed0.json"
    val jsonText = Source.fromFile(jsonPath).getLines().mkString(" ")

    val laneObj = """\{\s*"sign":\s*(\d+),\s*"exp":\s*(\d+),\s*"mant":\s*(\d+)\s*\}""".r
    val scaleObj = """"scale([AW])"\s*:\s*\{\s*"exp":\s*(\d+),\s*"mant":\s*(\d+)\s*\}""".r
    val cycleBoundary = """\{\s*"lanes_a"\s*:\s*\[""".r

    // Manually split cycles by cycle boundary patterns.
    // Instead of full JSON parsing, use fact each cycle has exactly N lanes
    // for A and N lanes for W plus two scale objects.
    val allLanes = laneObj.findAllMatchIn(jsonText).toVector.map(m =>
      (m.group(1).toInt, m.group(2).toInt, m.group(3).toInt))
    // Each cycle has cfg.N lanes_a + cfg.N lanes_w = 2*cfg.N lane objs.
    val perCycle = 2 * cfg.N
    val nCycles = allLanes.size / perCycle

    val allScales = scaleObj.findAllMatchIn(jsonText).toVector
    // First 2*nCycles scale matches; alternating A,W.
    val cyclesA = (0 until nCycles).map { c =>
      allLanes.slice(c * perCycle, c * perCycle + cfg.N)
    }
    val cyclesW = (0 until nCycles).map { c =>
      allLanes.slice(c * perCycle + cfg.N, (c + 1) * perCycle)
    }
    val scalesA = (0 until nCycles).map { c =>
      val m = allScales(2 * c);  (m.group(2).toInt, m.group(3).toInt)
    }
    val scalesW = (0 until nCycles).map { c =>
      val m = allScales(2 * c + 1);  (m.group(2).toInt, m.group(3).toInt)
    }

    // ── Golden dot (from json string) ─────────────────────
    val goldenDot = {
      val gPat = """"golden_dot"\s*:\s*(-?[0-9.eE+-]+)""".r
      gPat.findFirstMatchIn(jsonText).get.group(1).toDouble
    }

    // Compute Python-style ideal accumulator (FP64).
    val refAccPerCycle = collection.mutable.ArrayBuffer[Double]()
    var refAcc = 0.0
    for (c <- 0 until nCycles) {
      val termRef = (0 until cfg.N).map { i =>
        Reference.decodeElem(cfg.A, cyclesA(c)(i)._1 == 1,
                              cyclesA(c)(i)._2, cyclesA(c)(i)._3) *
        Reference.decodeElem(cfg.W, cyclesW(c)(i)._1 == 1,
                              cyclesW(c)(i)._2, cyclesW(c)(i)._3)
      }.sum *
        Reference.decodeScale(cfg.S, scalesA(c)._1, scalesA(c)._2) *
        Reference.decodeScale(cfg.S, scalesW(c)._1, scalesW(c)._2)
      refAcc += termRef
      refAccPerCycle.append(refAcc)
    }

    // ── Drive HW cycle-by-cycle ───────────────────────────
    println(s"\n══ Per-cycle trace ($CFG, K=${nCycles * cfg.N}) ═══════════════════════")
    println(f"  ${"cyc"}%3s  ${"refAcc"}%12s  ${"hwAcc"}%12s  ${"ratio"}%7s  ${"absDiff"}%12s")
    test(new SimpleDPU(cfg, debug = true))
      .withAnnotations(Seq(VerilatorBackendAnnotation)) { dut =>
        dut.io.clearAcc.poke(true.B)
        dut.io.enable.poke(false.B)
        dut.clock.step(1)
        dut.io.clearAcc.poke(false.B)
        dut.io.enable.poke(true.B)

        for (c <- 0 until nCycles) {
          for (i <- 0 until cfg.N) {
            dut.io.a(i).sign.poke((cyclesA(c)(i)._1 == 1).B)
            if (cfg.A.e > 0) dut.io.a(i).exp.poke(cyclesA(c)(i)._2.U)
            dut.io.a(i).mant.poke(cyclesA(c)(i)._3.U)
            dut.io.w(i).sign.poke((cyclesW(c)(i)._1 == 1).B)
            if (cfg.W.e > 0) dut.io.w(i).exp.poke(cyclesW(c)(i)._2.U)
            dut.io.w(i).mant.poke(cyclesW(c)(i)._3.U)
          }
          dut.io.scaleA.exp.poke(scalesA(c)._1.U)
          if (cfg.S.m > 0) dut.io.scaleA.mant.poke(scalesA(c)._2.U)
          dut.io.scaleW.exp.poke(scalesW(c)._1.U)
          if (cfg.S.m > 0) dut.io.scaleW.mant.poke(scalesW(c)._2.U)

          // Peek debug signals BEFORE step — these reflect combinational
          // outputs computed from the CURRENT accreg + stimulus, i.e., the
          // values that will be written on the coming posedge.
          if (c == 14 || c == 15) {
            val dbg2 = dut.io.dbg.get
            val preAccSig = dbg2.accSig.peek().litValue
            val preAccShifted = dbg2.accShiftedInt.peek().litValue
            val preSumField = dbg2.sumField.peek().litValue
            val preLz = dbg2.lz.peek().litValue
            val preResExp = dbg2.resExpUnbiased.peek().litValue
            println(f"    PRE-step cyc=$c: accSig=$preAccSig  accShifted=$preAccShifted  " +
                    f"sumField=$preSumField  lz=$preLz  resExp=$preResExp")
          }

          dut.clock.step(1)

          val hwSign = dut.io.accOut.sign.peek().litToBoolean
          val hwExp  = dut.io.accOut.exp.peek().litValue.toInt
          val hwMant = dut.io.accOut.mant.peek().litValue.toInt
          val hwVal  = Reference.decodeBF16(hwSign, hwExp, hwMant)
          val ref    = refAccPerCycle(c)
          val ratio  = if (ref != 0) hwVal / ref else Double.NaN
          val diff   = hwVal - ref
          println(f"  $c%3d  $ref%+.5e  $hwVal%+.5e  $ratio%7.4f  $diff%+.5e")

          if (c == 14 || c == 15) {
            val dbg = dut.io.dbg.get
            val sop = dbg.sopField.peek().litValue
            val scaledTerm = dbg.scaledTerm.peek().litValue
            val scaleExpSum = dbg.scaleExpSum.peek().litValue
            val accShift = dbg.accShiftSigned.peek().litValue
            val sumField = dbg.sumField.peek().litValue
            val lz = dbg.lz.peek().litValue
            val resExp = dbg.resExpUnbiased.peek().litValue
            val accSig = dbg.accSig.peek().litValue
            val accShiftedInt = dbg.accShiftedInt.peek().litValue
            val sopVal = sop.toDouble * math.pow(2.0, -ww.belowAnchor.toDouble)
            val termFinalVal = scaledTerm.toDouble *
              math.pow(2.0, -ww.termUnitPos.toDouble + scaleExpSum.toDouble)
            println(f"    dbg: sopField=$sop -> ${sopVal}%+.5e  " +
                    f"scaledTerm=$scaledTerm  " +
                    f"scaleExpSum=$scaleExpSum  termFinal=${termFinalVal}%+.5e")
            println(f"    dbg: accSig=$accSig  accShifted=$accShiftedInt  " +
                    f"accShift=$accShift  sumField=$sumField  lz=$lz  resExp=$resExp")
          }
        }
        println(f"\n  golden_dot from JSON = $goldenDot%+.6e")
      }
  }
}
