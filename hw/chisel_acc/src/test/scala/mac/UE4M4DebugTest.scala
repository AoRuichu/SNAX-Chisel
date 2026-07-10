package mx.mac

import chisel3._
import chiseltest._
import mx.mac.deferred_archs.FDPUBlockDeferred
import org.scalatest.funsuite.AnyFunSuite
import java.lang.Float.intBitsToFloat
import scala.io.Source

/** Sanity-check several UE4M4 element combinations against FP64 reference.
 *  After the FP² fix, both FP² and INT8 cases should give HW/SW ≈ 1.0. */
class UE4M4DebugTest extends AnyFunSuite with ChiselScalatestTester {
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
  case class C(t: Int, c: Int, sA: Int, sB: Int, a: Seq[Int], b: Seq[Int])
  private def loadVecs(path: String, V: Int): Vector[C] = {
    val src = Source.fromFile(path)
    try src.getLines().drop(1).toVector.map { line =>
      val p = line.split("\t")
      C(p(0).toInt, p(1).toInt, p(2).toInt, p(3).toInt,
        (0 until V).map(i => p(4 + i).toInt),
        (0 until V).map(i => p(4 + V + i).toInt))
    } finally src.close()
  }

  private def runOne(typeA: ElementType, typeB: ElementType,
                     sType: ScaleType, K: Int, label: String): Double = {
    val scfg = ScaleAddConfig(typeA, typeB, sType)
    val path = s"acc_trunc_vectors/${typeA.name}_${typeB.name}_${sType.name}_K$K.tsv"
    val vec  = 4; val cpb = 8
    val allCycles = loadVecs(path, vec).filter(_.t == 0)

    var swRef = 0.0
    for (c <- allCycles) {
      val sAv = decodeScale(c.sA, sType); val sBv = decodeScale(c.sB, sType)
      swRef += c.a.zip(c.b).map { case (a, b) =>
        decodeElement(a, typeA) * decodeElement(b, typeB) * sAv * sBv
      }.sum
    }
    var ratio = 0.0
    test(new FDPUBlockDeferred(scfg, vec, K = K,
            treeArch = TreeArch.Generic, cyclesPerBlock = cpb, istest = false)) { dut =>
      dut.reset.poke(true.B); dut.io.validIn.poke(false.B)
      dut.io.resetAcc.poke(true.B); dut.clock.step(); dut.io.resetAcc.poke(false.B)
      for (c <- allCycles) {
        dut.io.op_a_i.poke(packElems(c.a, typeA.totalWidth).U)
        dut.io.op_b_i.poke(packElems(c.b, typeB.totalWidth).U)
        dut.io.share_exp_A_i.poke(c.sA.U); dut.io.share_exp_B_i.poke(c.sB.U)
        dut.io.validIn.poke(true.B); dut.clock.step(); dut.io.validIn.poke(false.B)
      }
      dut.clock.step(1)
      val rawAcc = dut.io.accOut.peek().litValue
      val hwAcc  = intBitsToFloat((rawAcc << (23 - dut.actualAccMantBits)).toInt)
      ratio = hwAcc / swRef
      println(f"$label%-22s K=$K%-5d  HW=$hwAcc%14.4e  SW=$swRef%14.4e  HW/SW=$ratio%.4f")
    }
    ratio
  }

  test("UE4M4 sanity across element types") {
    println("Verifying conditional expBias fix across element combos:")
    val r1 = runOne(MXFormats.INT8, MXFormats.INT8, ScaleFormats.UE4M4, 512,  "INT8 × INT8")
    val r2 = runOne(MXFormats.E4M3, MXFormats.INT8, ScaleFormats.UE4M4, 512,  "E4M3 × INT8")
    val r3 = runOne(MXFormats.E3M2, MXFormats.E3M2, ScaleFormats.UE4M4, 512,  "E3M2 × E3M2")
    val r4 = runOne(MXFormats.E4M3, MXFormats.E4M3, ScaleFormats.UE4M4, 512,  "E4M3 × E4M3")
    val r5 = runOne(MXFormats.E5M2, MXFormats.E5M2, ScaleFormats.UE4M4, 512,  "E5M2 × E5M2")
    val r6 = runOne(MXFormats.E5M2, MXFormats.E4M3, ScaleFormats.UE4M4, 512,  "E5M2 × E4M3")
    for ((label, r) <- Seq("INT8²" -> r1, "E4M3×INT8" -> r2, "E3M2²" -> r3,
                            "E4M3²" -> r4, "E5M2²" -> r5, "E5M2×E4M3" -> r6)) {
      val pass = math.abs(r - 1.0) < 0.05
      println(f"  $label%-15s HW/SW = $r%.4f  ${if (pass) "✓" else "✗"}")
    }
  }
}
