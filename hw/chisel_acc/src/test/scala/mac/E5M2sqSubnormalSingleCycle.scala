package mx.mac

import chisel3._
import chiseltest._
import mx.mac.deferred_archs.FDPUBlockDeferred
import org.scalatest.funsuite.AnyFunSuite
import java.lang.Float.intBitsToFloat

/** Single-cycle hand-crafted diagnostic: E5M2 × E5M2, UE4M4, both subnormal
 *  scales.  Drives one block (8 cycles) of identical inputs, peeks the FP
 *  accumulator at block boundary, compares to FP64 reference.  Goal: isolate
 *  the per-cycle bias that drives the NN-sweep K=8192 catastrophe.
 */
class E5M2sqSubnormalSingleCycle extends AnyFunSuite with ChiselScalatestTester {

  // Manual decode for E5M2 (1+5+2): bias=15, format-specific.
  private def decE5M2(raw: Int): Double = {
    val sign = if ((raw & 0x80) != 0) -1.0 else 1.0
    val exp  = (raw >> 2) & 0x1F
    val mant = raw & 0x3
    if (exp == 0) sign * (mant / 4.0) * math.pow(2, 1 - 15)
    else          sign * (1.0 + mant / 4.0) * math.pow(2, exp - 15)
  }
  // UE4M4 scale: 1.M4 × 2^(exp - 7) for exp > 0, or m/16 × 2^(1-7) for exp=0
  private def decUE4M4(raw: Int): Double = {
    val exp  = (raw >> 4) & 0xF
    val mant = raw & 0xF
    if (exp == 0) (mant / 16.0) * math.pow(2, 1 - 7)
    else          (1.0 + mant / 16.0) * math.pow(2, exp - 7)
  }

  private def packE5M2(elems: Seq[Int]): BigInt =
    elems.zipWithIndex.foldLeft(BigInt(0)) { case (acc, (v, i)) =>
      acc | (BigInt(v & 0xFF) << (i * 8))
    }

  test("E5M2² UE4M4 subnormal-scale single-block diagnostic") {
    val V = 4; val K = 8; val cpb = 8
    val scfg = ScaleAddConfig(MXFormats.E5M2, MXFormats.E5M2, ScaleFormats.UE4M4)

    // Hand-pick non-zero E5M2 values: 1.0 each (exp=15, mant=0, sign=0 → bits=0x3C).
    // 4 lanes × cpb cycles, all identical.
    val elem = 0x3C   // value 1.0 in E5M2
    // Use scenario selector — A: both subnormal, B: both normal=1.0, C: one normal+one subnormal
    val scenario = sys.env.getOrElse("SCN", "A")
    val (sA, sB) = scenario match {
      case "A" => (0x08, 0x04)   // both subnormal
      case "B" => (0x70, 0x70)   // both normal value=1.0 (exp=7, mant=0)
      case "C" => (0x70, 0x04)   // sA normal=1.0, sB subnormal
      case _   => (0x08, 0x04)
    }
    println(s"\n  scenario = $scenario  sA=0x${sA.toHexString} sB=0x${sB.toHexString}")

    val vA = decE5M2(elem)
    val vB = decE5M2(elem)
    val sAv = decUE4M4(sA)
    val sBv = decUE4M4(sB)

    // Per-cycle product: V lanes × vA × vB = 4 × 1.0 × 1.0 = 4.0
    // Per-cycle scaled product: 4.0 × sAv × sBv = 4 × 2^-7 × 2^-8 = 4 × 2^-15 = 2^-13
    // 8 cycles → block sum = 8 × 2^-13 = 2^-10 = 9.765625e-4
    val expectedPerCycle = V * vA * vB
    val expectedScaled   = expectedPerCycle * sAv * sBv
    val expectedBlock    = expectedScaled * cpb

    println(s"\n=== E5M2² UE4M4 subnormal-scale single-block ===")
    println(s"  elem (E5M2)        = $elem (0x${elem.toHexString}) value=$vA")
    println(s"  sA (UE4M4)         = $sA  (0x${sA.toHexString})  value=$sAv")
    println(s"  sB (UE4M4)         = $sB  (0x${sB.toHexString})  value=$sBv")
    println(s"  per-cycle product  = $expectedPerCycle")
    println(s"  per-cycle scaled   = $expectedScaled")
    println(s"  block sum (8 cyc)  = $expectedBlock")

    test(new FDPUBlockDeferred(scfg, V, K = K,
                                         treeArch = TreeArch.Generic,
                                         cyclesPerBlock = cpb,
                                         istest = false)) { dut =>
      dut.reset.poke(true.B); dut.io.validIn.poke(false.B)
      dut.io.resetAcc.poke(true.B); dut.clock.step(); dut.io.resetAcc.poke(false.B)

      for (_ <- 0 until K) {
        dut.io.op_a_i       .poke(packE5M2(Seq.fill(V)(elem)).U)
        dut.io.op_b_i       .poke(packE5M2(Seq.fill(V)(elem)).U)
        dut.io.share_exp_A_i.poke(sA.U)
        dut.io.share_exp_B_i.poke(sB.U)
        dut.io.validIn.poke(true.B); dut.clock.step(); dut.io.validIn.poke(false.B)
      }
      dut.clock.step(1)
      val rawAcc = dut.io.accOut.peek().litValue
      val M = dut.actualAccMantBits
      val signBit = ((rawAcc >> (8 + M)).toInt) & 1
      val expBits = ((rawAcc >> M) & 0xFF).toInt
      val mantBits = (rawAcc & ((BigInt(1) << M) - 1)).toInt
      val padded = mantBits << (23 - M)
      val hwBits = (signBit << 31) | (expBits << 23) | padded
      val hwAcc  = intBitsToFloat(hwBits)
      val ratio  = if (expectedBlock != 0) hwAcc / expectedBlock else 0.0
      println(f"  HW raw bits        = 0x${rawAcc.toString(16)}")
      println(f"  HW sign/exp/mant   = $signBit / $expBits / $mantBits  (M_acc=$M)")
      println(f"  HW decoded         = $hwAcc%+.6e")
      println(f"  HW / expected      = $ratio%.6f")
      println(f"  abs error          = ${(hwAcc - expectedBlock).abs}%.6e")
    }
  }
}
