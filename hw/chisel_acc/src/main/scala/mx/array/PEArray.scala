package mx.array

import chisel3._
import chisel3.util._
import mx.mac.FDPUPostScaleReductionTree
import mx.requant.{RequantFP8, RequantINT8, RequantBF16}

// ============================================================
// FP8 / FP6 output wrapper
// ============================================================
/**
 * Top-level PE array wrapper using FDPUWithCustomReductionTree PEs and
 * FP32→MXFP8/FP6 (RequantFP8) requantization.
 *
 * Data flow:
 *   op_a_i / op_b_i / shared_exp_*_i
 *       → FDPUWithCustomReductionTree (tileRows × tileCols)
 *       → results_o (FP32, tileRows × tileCols)
 *       → RequantFP8
 *       → elem_out / shared_scale_out / valid_out
 */
class PEArrayWrapper(cfg: PEArrayConfig) extends Module {
  override def desiredName = "PE_Array"

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

    // ── Requantized FP8/FP6 Output ────────────────────────────────────────
    // One 8-bit shared scale per tile row
    val shared_scale_out = Output(UInt((cfg.tileRows * 8).W))
    // Flat packed output: tileRows × blockSize elements
    val elem_out         = Output(UInt((cfg.tileRows * cfg.requantCfg.blockSize * cfg.fp8Width).W))
    val valid_out        = Output(Bool())
  })

  // ── Handshake logic ──────────────────────────────────────────────────────
  io.A_ready_o := !io.send_output_i
  io.B_ready_o := !io.send_output_i
  val internal_valid = io.A_valid_i && io.B_valid_i

  // ── PE Array: tileRows × tileCols FDPUPostScaleReductionTree units ───────
  // All PEs share internal_valid / acc_reset_i, so all validOut are identical;
  // tap a single one for the requant block.
  val peValidOut = Wire(Bool())

  for (r <- 0 until cfg.tileRows) {
    for (c <- 0 until cfg.tileCols) {
      val pe = Module(new FDPUPostScaleReductionTree(
        cfg.macCfg, cfg.vectorSize, K = cfg.K, istest = false))

      pe.io.op_a_i        := io.op_a_i(r)
      pe.io.op_b_i        := io.op_b_i(c)
      pe.io.share_exp_A_i := io.shared_exp_A_i(r)
      pe.io.share_exp_B_i := io.shared_exp_B_i(c)
      pe.io.validIn       := internal_valid
      pe.io.resetAcc      := io.acc_reset_i

      io.results_o(r)(c) := pe.io.accOut
      if (r == 0 && c == 0) peValidOut := pe.io.validOut
    }
  }

  // ── RequantFP8: FP32 → MXFP8/FP6 ────────────────────────────────────────
  val rq = Module(new RequantFP8(cfg.requantCfg))

  // Pack FP32 outputs into a flat UInt, row-major, big-endian:
  //   (row=0, col=0) occupies the most-significant 32 bits.
  rq.io.fp32_in := Cat(
    for (r <- 0 until cfg.tileRows; c <- 0 until cfg.tileCols)
      yield io.results_o(r)(c)
  )
  rq.io.valid_in := peValidOut

  io.shared_scale_out := rq.io.shared_scale_out
  io.elem_out         := rq.io.elem_out
  io.valid_out        := rq.io.valid_out
}

// ============================================================
// INT8 output wrapper
// ============================================================
/**
 * PE array with FDPUWithCustomReductionTree PEs and FP32→INT8 requantization.
 *
 * Data flow:
 *   op_a_i / op_b_i / shared_exp_*_i
 *       → FDPUWithCustomReductionTree (tileRows × tileCols)
 *       → results_o (FP32)
 *       → RequantINT8
 *       → int8_out / shared_scale_out / valid_out
 */
class PEArrayWrapperINT8(cfg: PEArrayINT8Config) extends Module {
  override def desiredName = "PE_Array"

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
    val op_a_i           = Input(Vec(cfg.tileRows, UInt(cfg.srcWidthA.W)))
    val op_b_i           = Input(Vec(cfg.tileCols, UInt(cfg.srcWidthB.W)))
    val shared_exp_A_i   = Input(Vec(cfg.tileRows, UInt(cfg.scaleWidth.W)))
    val shared_exp_B_i   = Input(Vec(cfg.tileCols, UInt(cfg.scaleWidth.W)))

    // ── FP32 Accumulator Output ──────────────────────────────────────────
    val results_o        = Output(Vec(cfg.tileRows, Vec(cfg.tileCols, UInt(cfg.dstWidth.W))))

    // ── Requantized INT8 Output ───────────────────────────────────────────
    // One 8-bit UE8M0 shared scale per tile row
    val shared_scale_out = Output(UInt((cfg.tileRows * 8).W))
    // Flat packed INT8: tileRows × blockSize elements (two's complement)
    val int8_out         = Output(UInt((cfg.tileRows * cfg.requantCfg.blockSize * 8).W))
    val valid_out        = Output(Bool())
  })

  // ── Handshake logic ──────────────────────────────────────────────────────
  io.A_ready_o := !io.send_output_i
  io.B_ready_o := !io.send_output_i
  val internal_valid = io.A_valid_i && io.B_valid_i

  // ── PE Array ──────────────────────────────────────────────────────────────
  // All PEs share internal_valid / acc_reset_i, so all validOut are identical;
  // tap a single one for the requant block.
  val peValidOut = Wire(Bool())

  for (r <- 0 until cfg.tileRows) {
    for (c <- 0 until cfg.tileCols) {
      val pe = Module(new FDPUPostScaleReductionTree(
        cfg.macCfg, cfg.vectorSize, K = cfg.K, istest = false))

      pe.io.op_a_i        := io.op_a_i(r)
      pe.io.op_b_i        := io.op_b_i(c)
      pe.io.share_exp_A_i := io.shared_exp_A_i(r)
      pe.io.share_exp_B_i := io.shared_exp_B_i(c)
      pe.io.validIn       := internal_valid
      pe.io.resetAcc      := io.acc_reset_i

      io.results_o(r)(c) := pe.io.accOut
      if (r == 0 && c == 0) peValidOut := pe.io.validOut
    }
  }

  // ── RequantINT8: FP32 → INT8 ──────────────────────────────────────────────
  val rq = Module(new RequantINT8(cfg.requantCfg))

  rq.io.fp32_in := Cat(
    for (r <- 0 until cfg.tileRows; c <- 0 until cfg.tileCols)
      yield io.results_o(r)(c)
  )
  rq.io.valid_in := peValidOut

  io.shared_scale_out := rq.io.shared_scale_out
  io.int8_out         := rq.io.int8_out
  io.valid_out        := rq.io.valid_out
}

// ============================================================
// BF16 output wrapper
// ============================================================
/**
 * PE array with FDPUWithCustomReductionTree PEs and FP32→BF16 requantization.
 *
 * BF16 is a purely combinational per-element pass-through (top 16 bits of FP32
 * with RNE rounding). No block buffering, no shared scale.
 *
 * Data flow:
 *   op_a_i / op_b_i / shared_exp_*_i
 *       → FDPUWithCustomReductionTree (tileRows × tileCols)
 *       → results_o (FP32)
 *       → RequantBF16
 *       → bf16_out / valid_out
 */
class PEArrayWrapperBF16(cfg: PEArrayBF16Config) extends Module {
  override def desiredName = "PE_Array"

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
    val op_a_i           = Input(Vec(cfg.tileRows, UInt(cfg.srcWidthA.W)))
    val op_b_i           = Input(Vec(cfg.tileCols, UInt(cfg.srcWidthB.W)))
    val shared_exp_A_i   = Input(Vec(cfg.tileRows, UInt(cfg.scaleWidth.W)))
    val shared_exp_B_i   = Input(Vec(cfg.tileCols, UInt(cfg.scaleWidth.W)))

    // ── FP32 Accumulator Output ──────────────────────────────────────────
    val results_o        = Output(Vec(cfg.tileRows, Vec(cfg.tileCols, UInt(cfg.dstWidth.W))))

    // ── Requantized BF16 Output ───────────────────────────────────────────
    // Flat packed BF16: tileRows × tileCols elements, big-endian
    val bf16_out         = Output(UInt((cfg.tileRows * cfg.tileCols * 16).W))
    val valid_out        = Output(Bool())
  })

  // ── Handshake logic ──────────────────────────────────────────────────────
  io.A_ready_o := !io.send_output_i
  io.B_ready_o := !io.send_output_i
  val internal_valid = io.A_valid_i && io.B_valid_i

  // ── PE Array ──────────────────────────────────────────────────────────────
  // All PEs share internal_valid / acc_reset_i, so all validOut are identical;
  // tap a single one for the requant block.
  val peValidOut = Wire(Bool())

  for (r <- 0 until cfg.tileRows) {
    for (c <- 0 until cfg.tileCols) {
      val pe = Module(new FDPUPostScaleReductionTree(
        cfg.macCfg, cfg.vectorSize, K = cfg.K, istest = false))

      pe.io.op_a_i        := io.op_a_i(r)
      pe.io.op_b_i        := io.op_b_i(c)
      pe.io.share_exp_A_i := io.shared_exp_A_i(r)
      pe.io.share_exp_B_i := io.shared_exp_B_i(c)
      pe.io.validIn       := internal_valid
      pe.io.resetAcc      := io.acc_reset_i

      io.results_o(r)(c) := pe.io.accOut
      if (r == 0 && c == 0) peValidOut := pe.io.validOut
    }
  }

  // ── RequantBF16: FP32 → BF16 ─────────────────────────────────────────────
  val rq = Module(new RequantBF16(cfg.tileRows, cfg.tileCols))

  rq.io.fp32_in := Cat(
    for (r <- 0 until cfg.tileRows; c <- 0 until cfg.tileCols)
      yield io.results_o(r)(c)
  )
  rq.io.valid_in := peValidOut

  io.bf16_out  := rq.io.bf16_out
  io.valid_out := rq.io.valid_out
}

// ============================================================
// Emission helpers — shared directory-name utility
// ============================================================

private object EmitDir {
  /** Common target-dir pattern: <tileRows>x<tileCols>_<typeA>_<typeB>_<scale>_vec<v>_<outTag> */
  def fp8(cfg: PEArrayConfig): String =
    s"generated/pe_array/${cfg.tileRows}x${cfg.tileCols}" +
    s"_${cfg.macCfg.elementTypeA.name}_${cfg.macCfg.elementTypeB.name}" +
    s"_${cfg.macCfg.stype.name}_vec${cfg.vectorSize}" +
    s"_${cfg.requantCfg.outputType.name}_blk${cfg.requantCfg.blockSize}"

  def int8(cfg: PEArrayINT8Config): String =
    s"generated/pe_array/${cfg.tileRows}x${cfg.tileCols}" +
    s"_${cfg.macCfg.elementTypeA.name}_${cfg.macCfg.elementTypeB.name}" +
    s"_${cfg.macCfg.stype.name}_vec${cfg.vectorSize}" +
    s"_INT8_blk${cfg.requantCfg.blockSize}"

  def bf16(cfg: PEArrayBF16Config): String =
    s"generated/pe_array/${cfg.tileRows}x${cfg.tileCols}" +
    s"_${cfg.macCfg.elementTypeA.name}_${cfg.macCfg.elementTypeB.name}" +
    s"_${cfg.macCfg.stype.name}_vec${cfg.vectorSize}_BF16"
}

// ============================================================
// Emission helpers — generation App objects
// ============================================================

/** Emit the default 4×4 E5M2 array with FP8 output. */
object PEArrayMain extends App {
  import DefaultPEArrayConfigs._
  val cfg = e5m2_4x4
  emitVerilog(new PEArrayWrapper(cfg), Array("--target-dir", EmitDir.fp8(cfg)))
}

/** Emit all FP8/FP6 output configs: symmetric and asymmetric MAC pairs. */
object AllPEArrayMain extends App {
  import mx.mac.{MXFormats, ScaleFormats, ScaleAddConfig}
  import mx.requant.RequantConfig

  // ── Tile sizes + block sizes ────────────────────────────────────────────
  val tileConfigs = Seq(
    (4, 16, 32)
  )

  // ── MAC input pairs (symmetric + asymmetric) ────────────────────────────
  val macPairs = Seq(
    (MXFormats.E5M2, MXFormats.E5M2),
    (MXFormats.E4M3, MXFormats.E4M3),
    (MXFormats.E3M2, MXFormats.E3M2),
    (MXFormats.INT8, MXFormats.E5M2),
    (MXFormats.INT8, MXFormats.E4M3),
    (MXFormats.E5M2, MXFormats.E4M3)
  )

  val vecSize = 4
  val scale   = Seq(
    ScaleFormats.UE8M0,   // shared exponent with no bias (unsigned 8-bit integer)
    ScaleFormats.UE4M4,   // shared exponent with 4-bit signed mantissa (E5M2-style)
    ScaleFormats.UE6M2    // shared exponent with 6-bit signed mantissa (E4M3-style)
  )

  for {
    (typeA, typeB) <- macPairs
    (rows, cols, blk) <- tileConfigs
    scale <- scale
  } {
    val cfg = PEArrayConfig(
      macCfg     = ScaleAddConfig(typeA, typeB, scale),
      vectorSize = vecSize,
      tileRows   = rows,
      tileCols   = cols,
      requantCfg = RequantConfig(blk, rows, cols, typeA, scale)
    )
    println(
      s"[FP8/FP6] ${typeA.name}×${typeB.name} scale ${scale.name} " +
      s"${rows}x${cols} vec${vecSize} → ${typeA.name} blk${blk}"
    )
    emitVerilog(new PEArrayWrapper(cfg), Array("--target-dir", EmitDir.fp8(cfg)))
  }
}

/** Emit all INT8 output configs: symmetric and asymmetric MAC pairs. */
object AllPEArrayINT8Main extends App {
  import mx.mac.{MXFormats, ScaleFormats, ScaleAddConfig}
  import mx.requant.RequantINT8Config

  val tileConfigs = Seq(
    (4, 16, 32)
  )

  val macPairs = Seq(
    (MXFormats.E5M2, MXFormats.E5M2),
    (MXFormats.E4M3, MXFormats.E4M3),
    (MXFormats.INT8, MXFormats.E5M2),
    (MXFormats.INT8, MXFormats.E4M3),
    (MXFormats.E5M2, MXFormats.E4M3)
  )

  val vecSize = 4
  val scale   = Seq(
    ScaleFormats.UE8M0,   // shared exponent with no bias (unsigned 8-bit integer)
    ScaleFormats.UE4M4,   // shared exponent with 4-bit signed mantissa (E5M2-style)
    ScaleFormats.UE6M2    // shared exponent with 6-bit signed mantissa (E4M3-style)
  )

  for {
    (typeA, typeB) <- macPairs
    (rows, cols, blk) <- tileConfigs
    scale <- scale
  } {
    val cfg = PEArrayINT8Config(
      macCfg     = ScaleAddConfig(typeA, typeB, scale),
      vectorSize = vecSize,
      tileRows   = rows,
      tileCols   = cols,
      requantCfg = RequantINT8Config(blk, rows, cols)
    )
    println(
      s"[INT8] ${typeA.name}×${typeB.name} scale ${scale.name} " +
      s"${rows}x${cols} vec${vecSize} → INT8 blk${blk}"
    )
    emitVerilog(new PEArrayWrapperINT8(cfg), Array("--target-dir", EmitDir.int8(cfg)))
  }
}

/** Emit all BF16 output configs: symmetric and asymmetric MAC pairs. */
object AllPEArrayBF16Main extends App {
  import mx.mac.{MXFormats, ScaleFormats, ScaleAddConfig}

  val tileConfigs = Seq(
    (4, 16)
  )

  val macPairs = Seq(
    (MXFormats.E5M2, MXFormats.E5M2),
    (MXFormats.E4M3, MXFormats.E4M3),
    (MXFormats.INT8, MXFormats.E5M2),
    (MXFormats.INT8, MXFormats.E4M3),
    (MXFormats.E5M2, MXFormats.E4M3)
  )

  val vecSize = 4
  val scale   = ScaleFormats.UE8M0

  for {
    (typeA, typeB) <- macPairs
    (rows, cols)   <- tileConfigs
  } {
    val cfg = PEArrayBF16Config(
      macCfg     = ScaleAddConfig(typeA, typeB, scale),
      vectorSize = vecSize,
      tileRows   = rows,
      tileCols   = cols
    )
    println(
      s"[BF16] ${typeA.name}×${typeB.name} scale ${scale.name} " +
      s"${rows}x${cols} vec${vecSize} → BF16"
    )
    emitVerilog(new PEArrayWrapperBF16(cfg), Array("--target-dir", EmitDir.bf16(cfg)))
  }
}
