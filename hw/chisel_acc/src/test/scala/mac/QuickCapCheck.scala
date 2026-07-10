package mx.mac

import chisel3._
import chiseltest._
import mx.mac.deferred_archs.FDPUBlockDeferred
import org.scalatest.funsuite.AnyFunSuite

class QuickCapCheck extends AnyFunSuite with ChiselScalatestTester {
  test("Check cap on E5M2² UE4M4 K=8192") {
    val scfg = ScaleAddConfig(MXFormats.E5M2, MXFormats.E5M2, ScaleFormats.UE4M4)
    val K = 8192
    println(s"=== E5M2² UE4M4 K=$K ===")
    println(s"  resScaleAddMantWidth   = ${scfg.resScaleAddMantWidth}")
    println(s"  recommended (no cap)   = ${AccPrecision.recommended(scfg, K)}")
    test(new FDPUBlockDeferred(scfg, 4, K = K, cyclesPerBlock = 8)) { dut =>
      println(s"  dut.actualAccMantBits  = ${dut.actualAccMantBits}")
      println(s"  dut.desiredName        = ${dut.desiredName}")
    }
  }
}
