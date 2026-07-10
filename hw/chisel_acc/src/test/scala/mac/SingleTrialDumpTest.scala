package mx.mac

import chisel3._
import chiseltest._
import mx.mac.deferred_archs.FDPUBlockDeferred
import org.scalatest.funsuite.AnyFunSuite
import java.io.{FileWriter, PrintWriter}
import scala.io.Source
import java.lang.Float.intBitsToFloat

/** Single-trial HW dump for HW-bug investigation.
 *
 *  Runs E5M2 × E4M3 UE4M4 K=512 trial 0 with istest=true and dumps
 *  innerAccReg / outerAccReg every cycle to a CSV.  The Python pipeline
 *  emulator (diagnose_hw_pipeline.py) produces a parallel SW trace; bisect
 *  by block to find the first divergence.
 *
 *  CSV columns:
 *    cycle, blockIdx, blockDone,
 *    innerExp, innerMant, innerSign, innerValue (as FP32 zero-pad),
 *    outerExp, outerMant, outerSign, outerValue
 *
 *  Output file: hw_dump_<config>.csv  (one row per cycle).
 */
class SingleTrialDumpTest extends AnyFunSuite with ChiselScalatestTester {

  /** Decode (1 + 8 + M) FP-shape narrow word, zero-pad mantissa to 23,
   *  return Float. */
  private def narrowFpToFloat(raw: BigInt, M: Int): Float = {
    val mask = (BigInt(1) << (1 + 8 + M)) - 1
    val v    = raw & mask
    val sign = (v >> (8 + M)).toInt & 1
    val exp  = ((v >> M) & 0xFF).toInt
    val mant = (v & ((BigInt(1) << M) - 1)).toInt
    val padded = mant << (23 - M)
    val bits = (sign << 31) | (exp << 23) | padded
    intBitsToFloat(bits)
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

  private def pack(elems: Seq[Int], width: Int): BigInt =
    elems.zipWithIndex.foldLeft(BigInt(0)) { case (acc, (v, i)) =>
      acc | (BigInt(v & ((1 << width) - 1)) << (i * width))
    }

  test("SingleTrialDumpTest — E5M2×E4M3 UE4M4 K=512 trial 0") {
    val V       = 4
    val K       = 512
    val cpb     = 8
    val typeA   = MXFormats.E5M2
    val typeB   = MXFormats.E4M3
    val sType   = ScaleFormats.UE4M4
    val scfg    = ScaleAddConfig(typeA, typeB, sType)
    val M_acc   = AccPrecision.recommended(scfg, K)
    val vecPath = s"acc_trunc_vectors/${typeA.name}_${typeB.name}_${sType.name}_K$K.tsv"
    val outPath = s"hw_dump_${typeA.name}x${typeB.name}_${sType.name}_K${K}_trial0.csv"

    println(s"Loading vectors: $vecPath  (M_acc=$M_acc)")
    val cycles = loadVec(vecPath, V).filter(_.trial == 0)
    assert(cycles.length == K, s"expected $K cycles, got ${cycles.length}")

    val out = new PrintWriter(new FileWriter(outPath))
    out.println("cycle,blockIdx,blockDone,innerHexLo64,outerHexLo64,innerFP32,outerFP32")

    test(new FDPUBlockDeferred(scfg, V, K = K,
                                         treeArch = TreeArch.Generic,
                                         cyclesPerBlock = cpb,
                                         istest = true)) { dut =>
      dut.reset.poke(true.B)
      dut.io.validIn.poke(false.B)
      dut.io.resetAcc.poke(true.B); dut.clock.step(); dut.io.resetAcc.poke(false.B)

      cycles.foreach { c =>
        dut.io.op_a_i       .poke(pack(c.a, typeA.totalWidth).U)
        dut.io.op_b_i       .poke(pack(c.b, typeB.totalWidth).U)
        dut.io.share_exp_A_i.poke(c.sA.U)
        dut.io.share_exp_B_i.poke(c.sB.U)
        dut.io.validIn.poke(true.B)
        dut.clock.step()
        dut.io.validIn.poke(false.B)

        val inner = dut.io.debug.get.innerAccReg.peek().litValue
        val outer = dut.io.debug.get.outerAccReg.peek().litValue
        val done  = dut.io.debug.get.blockDoneFlag.peek().litToBoolean
        val blkIdx = c.cyc / cpb

        // Narrow FP (1+8+M_acc) lives in low (9+M_acc) bits of the 96-bit pad
        val fpNW   = 1 + 8 + M_acc
        val fpMask = (BigInt(1) << fpNW) - 1
        val innerNarrow = inner & fpMask
        val outerNarrow = outer & fpMask
        val innerFp32 = narrowFpToFloat(innerNarrow, M_acc)
        val outerFp32 = narrowFpToFloat(outerNarrow, M_acc)
        val innerHexLo64 = (inner & ((BigInt(1) << 64) - 1)).toString(16)
        val outerHexLo64 = (outer & ((BigInt(1) << 64) - 1)).toString(16)
        out.println(f"${c.cyc},${blkIdx},${done},0x${innerHexLo64},0x${outerHexLo64},${innerFp32}%.6e,${outerFp32}%.6e")
      }
    }
    out.close()
    println(s"HW trace written to $outPath")
  }
}
