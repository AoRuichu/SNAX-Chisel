// Basic sanity tests for SimpleDPU — checks compile + reset + clearAcc.
// Numerical vs-FP64 verification lives in SimpleDPUNumericSpec.

package mx_simple

import chisel3._
import chiseltest._
import chiseltest.simulator.VerilatorBackendAnnotation
import org.scalatest.flatspec.AnyFlatSpec

class SimpleDPUSpec extends AnyFlatSpec with ChiselScalatestTester {

  behavior of "SimpleDPU"

  private def cfgOf(a: String, w: String, s: String) =
    DPUConfig(Elem.byName(a), Elem.byName(w), Scale.byName(s))

  // ── Elaborate + basic reset behavior ─────────────────────
  it should "elaborate E4M3/E4M3/UE8M0 and reset accreg to zero" in {
    test(new SimpleDPU(cfgOf("E4M3", "E4M3", "UE8M0")))
      .withAnnotations(Seq(VerilatorBackendAnnotation)) { dut =>
        dut.reset.poke(true.B)
        dut.clock.step(2)
        dut.reset.poke(false.B)
        dut.io.enable.poke(false.B)
        dut.io.clearAcc.poke(false.B)
        dut.clock.step(1)
        dut.io.accOut.sign.expect(false.B)
        dut.io.accOut.exp.expect(0.U)
        dut.io.accOut.mant.expect(0.U)
      }
  }

  // ── clearAcc high should zero out accreg ─────────────────
  it should "zero accreg when clearAcc is asserted" in {
    test(new SimpleDPU(cfgOf("E5M2", "E5M2", "UE6M2")))
      .withAnnotations(Seq(VerilatorBackendAnnotation)) { dut =>
        // Any inputs; enable off; clearAcc high.
        dut.io.enable.poke(false.B)
        dut.io.clearAcc.poke(true.B)
        dut.clock.step(1)
        dut.io.accOut.exp.expect(0.U)
        dut.io.accOut.mant.expect(0.U)
      }
  }

  // ── INT8 config elaborates ──────────────────────────────
  it should "elaborate INT8/INT8/UE8M0" in {
    test(new SimpleDPU(cfgOf("INT8", "INT8", "UE8M0")))
      .withAnnotations(Seq(VerilatorBackendAnnotation)) { dut =>
        dut.io.enable.poke(false.B)
        dut.io.clearAcc.poke(true.B)
        dut.clock.step(1)
        dut.io.accOut.exp.expect(0.U)
      }
  }

  // ── Mixed asymmetric config elaborates ──────────────────
  it should "elaborate E5M2/INT8/UE4M4 (asymmetric)" in {
    test(new SimpleDPU(cfgOf("E5M2", "INT8", "UE4M4")))
      .withAnnotations(Seq(VerilatorBackendAnnotation)) { dut =>
        dut.io.clearAcc.poke(true.B)
        dut.clock.step(1)
        dut.io.accOut.exp.expect(0.U)
      }
  }
}
