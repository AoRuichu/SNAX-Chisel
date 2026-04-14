package mx.array

import chisel3._
import chisel3.util._
import mx.mac.FusedDotProductUnit
import mx.requant.RequantFP8

/**
 * Top-level PE array wrapper integrating:
 *   - tileRows × tileCols FusedDotProductUnit PEs
 *   - RequantFP8 requantization block
 *
 * Port names are aligned with PE_Array_wrapper.sv so that the generated
 * Verilog can directly replace it.
 *
 * Data flow:
 *   op_a_i / op_b_i / shared_exp_*_i
 *       → FusedDotProductUnit (tileRows × tileCols)
 *       → results_o (FP32, tileRows × tileCols)
 *       → RequantFP8
 *       → fp8_out / shared_scale_out / valid_out
 */
class PEArrayWrapper(cfg: PEArrayConfig) extends Module {
  override def desiredName = "PE_Array_wrapper"

  val io = IO(new Bundle {
    // ── CSR & Control ────────────────────────────────────────────────────
    val A_mode           = Input(UInt(3.W))
    val B_mode           = Input(UInt(3.W))
    val result_mode_quan = Input(UInt(2.W))
    val group_size       = Input(UInt(2.W))
    val shared_format_i  = Input(UInt(4.W))

    val acc_reset_i      = Input(Bool())
    val send_output_i    = Input(Bool())

    // ── Handshakes ────────────────────────────────────────────────────────
    val A_valid_i        = Input(Bool())
    val B_valid_i        = Input(Bool())
    val A_ready_o        = Output(Bool())
    val B_ready_o        = Output(Bool())

    // ── Data Input ───────────────────────────────────────────────────────
    // [0:TileRows-1][srcWidthA-1:0] — matches SV op_a_i
    val op_a_i           = Input(Vec(cfg.tileRows, UInt(cfg.srcWidthA.W)))
    // [0:TileCols-1][srcWidthB-1:0] — matches SV op_b_i
    val op_b_i           = Input(Vec(cfg.tileCols, UInt(cfg.srcWidthB.W)))
    // [0:TileRows-1][SCALE_WIDTH-1:0] — matches SV shared_exp_A_i
    val shared_exp_A_i   = Input(Vec(cfg.tileRows, UInt(cfg.scaleWidth.W)))
    // [0:TileCols-1][SCALE_WIDTH-1:0] — matches SV shared_exp_B_i
    val shared_exp_B_i   = Input(Vec(cfg.tileCols, UInt(cfg.scaleWidth.W)))

    // ── FP32 Accumulator Output ──────────────────────────────────────────
    // [0:TileRows-1][0:TileCols-1][DST_WIDTH-1:0] — matches SV results_o
    val results_o        = Output(Vec(cfg.tileRows, Vec(cfg.tileCols, UInt(cfg.dstWidth.W))))

    // ── Requantized MXFP8 Output ─────────────────────────────────────────
    // One 8-bit shared scale per tile row
    val shared_scale_out = Output(UInt((cfg.tileRows * 8).W))
    // Flat packed MXFP8: tileRows × blockSize elements
    val fp8_out          = Output(UInt((cfg.tileRows * cfg.requantCfg.blockSize * cfg.fp8Width).W))
    val valid_out        = Output(Bool())
  })

  // ── Handshake logic ──────────────────────────────────────────────────────
  io.A_ready_o := !io.send_output_i
  io.B_ready_o := !io.send_output_i
  val internal_valid = io.A_valid_i && io.B_valid_i

  // ── PE Array: tileRows × tileCols FusedDotProductUnits ──────────────────
  // Capture per-PE validOut to forward to RequantFP8; use PE[0][0] as clock
  // reference since all PEs receive the same validIn and resetAcc.
  val peValidOut = Wire(Vec(cfg.tileRows, Vec(cfg.tileCols, Bool())))

  for (r <- 0 until cfg.tileRows) {
    for (c <- 0 until cfg.tileCols) {
      val pe = Module(new FusedDotProductUnit(cfg.macCfg, cfg.vectorSize, istest = false))

      // Inputs: row r shares operand A and scale A; col c shares operand B and scale B
      pe.io.op_a_i        := io.op_a_i(r)
      pe.io.op_b_i        := io.op_b_i(c)
      pe.io.share_exp_A_i := io.shared_exp_A_i(r)
      pe.io.share_exp_B_i := io.shared_exp_B_i(c)
      pe.io.validIn       := internal_valid
      pe.io.resetAcc      := io.acc_reset_i

      io.results_o(r)(c) := pe.io.accOut
      peValidOut(r)(c)   := pe.io.validOut
    }
  }

  // ── RequantFP8: FP32 → MXFP8 ─────────────────────────────────────────────
  val rq = Module(new RequantFP8(cfg.requantCfg))

  // Pack FP32 outputs into a flat UInt, row-major, big-endian:
  //   (row=0, col=0) occupies the most-significant 32 bits,
  //   which matches RequantFP8.extractFP32's indexing:
  //     k = row*tileCols + col
  //     fp32_in[nIn*32-1 - k*32 : nIn*32-(k+1)*32]
  rq.io.fp32_in := Cat(
    for (r <- 0 until cfg.tileRows; c <- 0 until cfg.tileCols)
      yield io.results_o(r)(c)
  )
  // All PEs are synchronised; use PE[0][0] as the valid reference
  rq.io.valid_in := peValidOut(0)(0)

  io.shared_scale_out := rq.io.shared_scale_out
  io.fp8_out          := rq.io.fp8_out
  io.valid_out        := rq.io.valid_out
}

// ============================================================
// Emission helpers
// ============================================================

/** Emit the default 4×4 E5M2 array. */
object PEArrayMain extends App {
  import DefaultPEArrayConfigs._
  val cfg = e5m2_4x4
  emitVerilog(
    new PEArrayWrapper(cfg),
    Array("--target-dir",
      s"generated/pe_array/${cfg.tileRows}x${cfg.tileCols}" +
      s"_${cfg.macCfg.elementTypeA.name}_${cfg.macCfg.elementTypeB.name}" +
      s"_${cfg.macCfg.stype.name}_vec${cfg.vectorSize}")
  )
}

/** Emit all default configs. */
object AllPEArrayMain extends App {
  import DefaultPEArrayConfigs._
  Seq(e5m2_4x4, e4m3_4x4, e4m3_8x8).foreach { cfg =>
    println(
      s"Generating PEArray: ${cfg.macCfg.elementTypeA.name} x ${cfg.macCfg.elementTypeB.name}" +
      s" scale ${cfg.macCfg.stype.name} ${cfg.tileRows}x${cfg.tileCols} vec${cfg.vectorSize}"
    )
    emitVerilog(
      new PEArrayWrapper(cfg),
      Array("--target-dir",
        s"generated/pe_array/${cfg.tileRows}x${cfg.tileCols}" +
        s"_${cfg.macCfg.elementTypeA.name}_${cfg.macCfg.elementTypeB.name}" +
        s"_${cfg.macCfg.stype.name}_vec${cfg.vectorSize}")
    )
  }
}
