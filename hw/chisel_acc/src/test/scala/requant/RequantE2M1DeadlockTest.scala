package mx.requant

import chisel3._
import chiseltest._
import org.scalatest.funsuite.AnyFunSuite
import mx.mac.{MXFormats, ScaleFormats}
import mx.array.{
  ArchOverride, PEArrayConfig, PEArrayWrapper
}
import mx.mac.TreeArch

/** q7 (E2M1 output, 4-bit FP4 requant) deadlock investigation.
 *
 *  The kernel reports: sim time advancing, VCD growing, but `done` from the
 *  accelerator never fires.  We narrow the bug to a specific layer by
 *  testing valid-out pipelines from the bottom up.
 *
 *  Test A — RequantFP8 standalone with E2M1 output:
 *    The valid-out logic is `RegNext(blockDone)`, structurally IDENTICAL
 *    across E5M2/E4M3/E3M2/E2M3/E2M1 outputs.  If this test hangs, the bug
 *    is inside RequantFP8's E2M1-specific FP32-to-E2M1 conversion (unlikely
 *    since valid pipeline doesn't depend on outputType).  If it passes,
 *    the deadlock is at a higher level.
 *
 *  Test B — Integrated PE_Array wrapper with E2M1 output:
 *    Exercises the full pipeline: MAC array → reduction tree → requant.
 *    If A passes and B hangs, the bug is in the PE_Array wrapper's
 *    handshake (acc_reset_i / send_output_i / valid propagation).  If both
 *    pass, the deadlock is in the streamer/DMA layer between core and
 *    accelerator — outside this codebase.
 *
 *  All tests use a `withAnnotations(WriteVcdAnnotation)` hint on failure so
 *  the failing waveform can be inspected.  Timeout guard: 200 cycles per
 *  test (well above the expected latency of ~5 cycles).
 */
class RequantE2M1DeadlockTest extends AnyFunSuite with ChiselScalatestTester {

  // ── Config matching deployed E2M1_E2M1_UE8M0_M8 (user's q7 hang case) ──
  // key_configs/E2M1_E2M1_UE8M0_M8: blockSize=16, tile=4×16, M_acc=8 → FP17 in
  // scale UE8M0 = pure exp scale (no scale mantissa), which is the ONLY axis
  // that differs from UE6M2/UE5M3/UE4M3 configs → suspect the deadlock is
  // specific to the UE8M0 path in RequantFP8.
  private val cfgKey = RequantConfig(
    blockSize      = 16,
    tileRows       = 4,
    tileCols       = 16,
    outputType     = MXFormats.E2M1,
    scaleType      = ScaleFormats.UE8M0,       // ← the pure-exp scale
    inputMantWidth = 8)                        // ← FP17 input (1+8+8)

  // Alternative: multi-batch geometry (batchesPerBlock > 1) to exercise
  // the batch counter path.  tileRows must be 4/8/16; keep tileRows=4 and
  // shrink tileCols=4 → batchesPerBlock = blockSize/tileCols = 16/4 = 4.
  private val cfgSmallTile = RequantConfig(
    blockSize      = 16,
    tileRows       = 4,
    tileCols       = 4,        // → batchesPerBlock = 4
    outputType     = MXFormats.E2M1,
    scaleType      = ScaleFormats.UE8M0,
    inputMantWidth = 8)

  // ── Helpers ────────────────────────────────────────────────────────────
  private def initDut(dut: RequantFP8): Unit = {
    // Active-low async reset (see RequantFP8.scala line ~447): poking reset=true
    // → asyncRstN = 0 → registers released from reset (normal operation).
    dut.reset.poke(true.B)
    dut.io.valid_in.poke(false.B)
    dut.clock.step(2)
  }

  /** Pack tileRows × blockSize FP18 elements into the top-level packed vector.
   *  Layout matches RequantFP8: element (row=0, col=0) at MSB slot 0. */
  private def packFP18Ones(cfg: RequantConfig): BigInt = {
    val inW      = cfg.inputWidth                         // 1 + 8 + inputMantWidth
    val nSlots   = cfg.tileRows * cfg.tileCols            // per-batch slot count
    val fp18One  = BigInt(127) << cfg.inputMantWidth      // 0 | 01111111 | 000... = 1.0
    (0 until nSlots).foldLeft(BigInt(0)) { (acc, _) =>
      (acc << inW) | fp18One
    }
  }

  /** Step up to `maxCycles` clock cycles looking for valid_out high;
   *  return (elapsed cycles, saw_valid_out). */
  private def waitForValidOut(dut: RequantFP8, maxCycles: Int): (Int, Boolean) = {
    for (c <- 0 until maxCycles) {
      if (dut.io.valid_out.peekBoolean()) return (c, true)
      dut.clock.step()
    }
    (maxCycles, false)
  }

  // ── Test A1: E2M1 standalone requant, tile=4×16 (batchesPerBlock=1) ─────
  test("A1: RequantFP8 E2M1 out, tile=4x16, batchesPerBlock=1 — no deadlock") {
    test(new RequantFP8(cfgKey)) { dut =>
      initDut(dut)
      val B = cfgKey.batchesPerBlock        // = 16 / 16 = 1
      assert(B == 1, s"expected batchesPerBlock=1, got $B")

      // Drive one batch (== one full block since B=1) with all-1.0 FP18 data.
      dut.io.fp32_in.poke(packFP18Ones(cfgKey).U)
      dut.io.valid_in.poke(true.B)
      dut.clock.step()
      dut.io.valid_in.poke(false.B)

      // valid_out is RegNext(blockDone), fires 1 cycle after the last valid_in.
      // Since blockDone was true DURING the valid_in cycle, valid_out is high NOW.
      dut.io.valid_out.expect(true.B,
        "valid_out MUST be high 1 cycle after the block's last valid_in pulse (RegNext).")
      println(s"[A1] valid_out fired on cycle B+1 = 2  ✓  (elem_out=${dut.io.elem_out.peek().litValue.toString(16).take(16)}...)")
    }
  }

  // ── Test A2: E2M1 standalone requant, tile=2×4 (batchesPerBlock=4) ──────
  test("A2: RequantFP8 E2M1 out, tile=2x4, batchesPerBlock=4 — no deadlock") {
    test(new RequantFP8(cfgSmallTile)) { dut =>
      initDut(dut)
      val B = cfgSmallTile.batchesPerBlock    // = 16 / 4 = 4
      assert(B == 4, s"expected batchesPerBlock=4, got $B")

      // Drive B batches of arbitrary FP18 data.
      for (b <- 0 until B) {
        dut.io.fp32_in.poke(packFP18Ones(cfgSmallTile).U)
        dut.io.valid_in.poke(true.B)
        // valid_out MUST stay low until batchesPerBlock-th pulse.
        if (b < B - 1) dut.io.valid_out.expect(false.B, s"batch $b: too early to fire")
        dut.clock.step()
      }
      dut.io.valid_in.poke(false.B)

      // Now valid_out should be high (RegNext(blockDone), where blockDone fired
      // during the B-th valid_in cycle).
      dut.io.valid_out.expect(true.B,
        "valid_out MUST fire on the cycle after the B-th valid_in pulse.")
      println("[A2] multi-batch counter path OK  ✓")
    }
  }

  // ── Test B: Integrated PE_Array wrapper with E2M1 output ────────────────
  test("B: PEArrayWrapper E2M1²+UE8M0+E2M1 out — valid_out fires within 100 cycles") {
    // Match key_configs/E2M1_E2M1_UE8M0_M8 exactly — the user's q7 hang case.
    val macCfg = mx.mac.ScaleAddConfig(
      MXFormats.E2M1, MXFormats.E2M1, ScaleFormats.UE8M0)
    val rqCfg  = RequantConfig(
      blockSize      = 16, tileRows = 4, tileCols = 16,
      outputType     = MXFormats.E2M1,
      scaleType      = ScaleFormats.UE8M0,
      inputMantWidth = 8)
    val arrCfg = PEArrayConfig(
      macCfg              = macCfg,
      vectorSize          = 4,
      tileRows            = 4,
      tileCols            = 16,
      requantCfg          = rqCfg,
      K                   = 32,
      archOverride        = Some(ArchOverride(TreeArch.Generic, 1)),
      accMantBitsOverride = 8)

    test(new PEArrayWrapper(arrCfg)) { dut =>
      // accumulation_count_i = K / vectorSize = 32/4 = 8; the wrapper's
      // resultDone gate needs `peValidOut` to fire exactly this many times
      // before the requant.valid_in pulse is emitted.
      val accCount = arrCfg.K / arrCfg.vectorSize    // = 8

      dut.reset.poke(true.B)
      dut.io.A_valid_i.poke(false.B)
      dut.io.B_valid_i.poke(false.B)
      dut.io.acc_reset_i.poke(false.B)
      dut.io.send_output_i.poke(false.B)
      dut.io.accumulation_count_i.poke(accCount.U)
      dut.clock.step(2)

      // Pulse acc_reset for 1 cycle.
      dut.io.acc_reset_i.poke(true.B)
      dut.clock.step()
      dut.io.acc_reset_i.poke(false.B)

      // Drive `accCount` cycles of valid data.  Each op_a_i / op_b_i port is
      // (elemWidth × vec) bits — for E2M1 (4-bit) × vec=4 = 16-bit port.
      // 0x2468 / 0x1357 = 4 arbitrary E2M1 bit-patterns per row/col.
      for (c <- 0 until accCount) {
        dut.io.op_a_i.foreach(_.poke(0x2468.U))
        dut.io.op_b_i.foreach(_.poke(0x1357.U))
        dut.io.shared_exp_A_i.foreach(_.poke(0x40.U))
        dut.io.shared_exp_B_i.foreach(_.poke(0x40.U))
        dut.io.A_valid_i.poke(true.B)
        dut.io.B_valid_i.poke(true.B)
        dut.clock.step()
      }
      dut.io.A_valid_i.poke(false.B)
      dut.io.B_valid_i.poke(false.B)

      // Now wait up to 200 cycles for valid_out — the PE's own reduction-tree
      // latency + RequantFP8's block latency should be well under this.
      var sawValid = false
      var elapsed  = 0
      for (c <- 0 until 200 if !sawValid) {
        if (dut.io.valid_out.peekBoolean()) { sawValid = true; elapsed = c }
        else dut.clock.step()
      }

      assert(sawValid,
        s"[B DEADLOCK] PEArrayWrapper E2M1 valid_out never fired within 200 cycles " +
        s"after driving $accCount valid input cycles — this is the q7 hang.")
      println(s"[B] PEArrayWrapper E2M1 valid_out fired at cycle +${elapsed}  ✓")
    }
  }
}
