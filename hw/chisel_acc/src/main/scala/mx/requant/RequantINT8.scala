package mx.requant

import chisel3._
import chisel3.util._

// ============================================================
// Config
// ============================================================
/**
 * Configuration for the FP32 → MX INT8 requantization block.
 * INT8 always uses UE8M0 shared scale (max biased FP32 exponent in the block).
 */
case class RequantINT8Config(
  blockSize: Int,
  tileRows:  Int,
  tileCols:  Int
) {
  require(Seq(16, 32, 64).contains(blockSize),
    s"blockSize must be 16, 32, or 64; got $blockSize")
  require(Seq(4, 8).contains(tileRows),
    s"tileRows must be 4 or 8; got $tileRows")
  require(Seq(4, 8).contains(tileCols),
    s"tileCols must be 4 or 8; got $tileCols")
  require(blockSize % tileCols == 0,
    s"blockSize ($blockSize) must be divisible by tileCols ($tileCols)")

  val batchesPerBlock: Int = blockSize / tileCols
}

// ============================================================
// FP32 → MX INT8 single-element converter
// ============================================================
/**
 * Converts one IEEE-754 FP32 to MX INT8 (8-bit two's complement) given the
 * block's UE8M0 shared scale.
 *
 * Scale semantics:
 *   shared_scale = max biased FP32 exponent S in the block.
 *   Element value = int8_out × 2^(S − 133)
 *                 = int8_out × 2^(S − 127) × 2^(−6)
 *
 * Quantisation:
 *   int8 = round(fp32 / 2^(S − 133))
 *         = round((1.mant) × 2^(fp32_exp − S + 6))
 *         = round((1.mant) × 2^k),  k = fp32_exp − S + 6
 *
 *   Since S = max(fp32_exp), k ≤ 6 for all elements in the block.
 *
 * Implementation (barrel-shift approach):
 *   mantExt = {fp32_full_mant[23:0], 24'b0}  (48 bits)
 *   shifted = mantExt >> (23 − k)
 *   → bits [30:24] = 7-bit integer magnitude
 *   → bit  [23]   = guard bit
 *   → bits [22:0] = sticky bits
 *   No mantissa bits are lost for k ∈ [−24, 6].
 *
 * Rounding  : round-to-nearest-even (RNE).
 * Saturation: clamp to ±127 (0x80 = −128 is never generated).
 * Subnormals: flush to 0.
 */
class FP32ToMXINT8(val cfg: RequantINT8Config) extends Module {
  override def desiredName = s"FP32ToMXINT8_blk${cfg.blockSize}"

  val io = IO(new Bundle {
    val fp32_in      = Input(UInt(32.W))
    val shared_scale = Input(UInt(8.W))   // UE8M0: max biased FP32 exponent in block
    val int8_out     = Output(UInt(8.W))  // two's complement signed INT8
  })

  // ── Unpack FP32 ──────────────────────────────────────────
  val sign     = io.fp32_in(31)
  val fp32_exp = io.fp32_in(30, 23)
  val fp32_man = io.fp32_in(22, 0)
  val isZeroOrSubnormal = fp32_exp === 0.U

  // ── Compute shift exponent k ──────────────────────────────
  // k ∈ (−∞, 6]; guaranteed by shared_scale = max(fp32_exp).
  val k = fp32_exp.zext - io.shared_scale.zext + 6.S   // SInt

  // ── Barrel-shift mantissa ─────────────────────────────────
  private val FRAC = 24
  val fp32FullMant = Cat(fp32_exp.orR, fp32_man)           // 24 bits
  val mantExt      = Cat(fp32FullMant, 0.U(FRAC.W))        // 48 bits

  // shiftAmt = 23 − k ≥ 17 (since k ≤ 6).
  // For very negative k, shiftAmt >> 48, Chisel shifts beyond width → 0.
  val shiftAmt = (23.S - k).asUInt
  val shifted  = mantExt >> shiftAmt                        // 48 bits

  // ── Extract integer + rounding bits (all Scala-time constants) ──
  val mag7      = shifted(FRAC + 6, FRAC)     // bits [30:24] — 7-bit magnitude (0..127)
  val guardBit  = shifted(FRAC - 1)           // bit  [23]
  val stickyBit = shifted(FRAC - 2, 0).orR   // bits [22:0]

  // ── RNE rounding ─────────────────────────────────────────
  val roundUp     = guardBit && (mag7(0) || stickyBit)
  val mag8        = mag7 +& roundUp            // 8 bits with carry
  val magOverflow = mag8(7)                    // 0x7F + 1 = 0x80: saturate

  val mag = Mux(magOverflow, 127.U(7.W), mag8(6, 0))

  // ── Sign and output ───────────────────────────────────────
  val posResult = Cat(0.U(1.W), mag)           // 0x00..0x7F
  val negResult = (~posResult + 1.U)(7, 0)     // two's complement (0xFF..0x81, 0x00 for zero)

  io.int8_out := Mux(isZeroOrSubnormal, 0.U,
                 Mux(sign, negResult, posResult))
}

// ============================================================
// Block INT8 requantizer (combinational)
// ============================================================
/**
 * Requantizes blockSize FP32 values for one tile row to INT8.
 * Purely combinational: MaxScale (UE8M0) → blockSize × FP32ToMXINT8.
 */
class RequantBlockINT8(val cfg: RequantINT8Config) extends Module {
  override def desiredName = s"RequantBlockINT8_blk${cfg.blockSize}"

  val io = IO(new Bundle {
    val fp32_in      = Input(Vec(cfg.blockSize, UInt(32.W)))
    val shared_scale = Output(UInt(8.W))
    val int8_out     = Output(Vec(cfg.blockSize, UInt(8.W)))
  })

  // Balanced max-tree: finds the maximum biased FP32 exponent (UE8M0 scale)
  def maxTree(vals: Seq[UInt]): UInt =
    if (vals.length == 1) vals.head
    else maxTree(vals.grouped(2).map {
      case Seq(a, b) => Mux(a >= b, a, b)
      case Seq(a)    => a
      case g         => g.head
    }.toSeq)

  val maxExp = maxTree(io.fp32_in.map(_(30, 23)))
  io.shared_scale := maxExp

  for (i <- 0 until cfg.blockSize) {
    val conv = Module(new FP32ToMXINT8(cfg))
    conv.io.fp32_in      := io.fp32_in(i)
    conv.io.shared_scale := maxExp
    io.int8_out(i)       := conv.io.int8_out
  }
}

// ============================================================
// Top-level: buffer + controller + tileRows parallel blocks
// ============================================================
/**
 * RequantINT8: same dataflow structure as RequantFP8.
 * Accumulates batchesPerBlock = blockSize / tileCols cycles of FP32 input,
 * then fires INT8 requantization for all tileRows rows in parallel.
 *
 * Output fires one cycle after blockDone (valid_out pulse):
 *   shared_scale_out[i]  — 8-bit UE8M0 scale for row i
 *   int8_out[i][k]       — k-th INT8 element of row i (two's complement)
 */
class RequantINT8(val cfg: RequantINT8Config) extends Module {
  override def desiredName =
    s"requant"

  private val B   = cfg.batchesPerBlock
  private val nIn = cfg.tileRows * cfg.tileCols

  val io = IO(new Bundle {
    val fp32_in          = Input(UInt((nIn * 32).W))
    val valid_in         = Input(Bool())
    val shared_scale_out = Output(UInt((cfg.tileRows * 8).W))
    val int8_out         = Output(UInt((cfg.tileRows * cfg.blockSize * 8).W))
    val valid_out        = Output(Bool())
  })

  def extractFP32(row: Int, col: Int): UInt = {
    val k = row * cfg.tileCols + col
    io.fp32_in(nIn * 32 - k * 32 - 1, nIn * 32 - (k + 1) * 32)
  }

  // Active-low async reset — matches FusedDotProductUnit convention.
  val asyncRstN = (!reset.asBool).asAsyncReset

  val buffer    = withReset(asyncRstN)(Reg(Vec(cfg.tileRows, Vec(cfg.blockSize, UInt(32.W)))))
  val batchCnt  = withReset(asyncRstN)(RegInit(0.U(6.W)))
  val blockDone = io.valid_in && (batchCnt === (B - 1).U)

  when(io.valid_in) {
    for (batch <- 0 until B) {
      when(batchCnt === batch.U) {
        for (row <- 0 until cfg.tileRows; col <- 0 until cfg.tileCols)
          buffer(row)(batch * cfg.tileCols + col) := extractFP32(row, col)
      }
    }
    batchCnt := Mux(blockDone, 0.U, batchCnt + 1.U)
  }

  val sharedScaleWire = Wire(Vec(cfg.tileRows, UInt(8.W)))
  val int8Wire        = Wire(Vec(cfg.tileRows, Vec(cfg.blockSize, UInt(8.W))))

  for (row <- 0 until cfg.tileRows) {
    val rq = Module(new RequantBlockINT8(cfg))
    for (col <- 0 until cfg.blockSize) {
      val batchIdx   = col / cfg.tileCols
      val colInBatch = col % cfg.tileCols
      rq.io.fp32_in(col) := (
        if (batchIdx == B - 1)
          Mux(blockDone, extractFP32(row, colInBatch), buffer(row)(col))
        else
          buffer(row)(col)
      )
    }
    sharedScaleWire(row) := rq.io.shared_scale
    int8Wire(row)        := rq.io.int8_out
  }

  val validOutReg    = withReset(asyncRstN)(RegNext(blockDone, init = false.B))
  val sharedScaleReg = withReset(asyncRstN)(Reg(Vec(cfg.tileRows, UInt(8.W))))
  val int8OutReg     = withReset(asyncRstN)(Reg(Vec(cfg.tileRows, Vec(cfg.blockSize, UInt(8.W)))))

  when(blockDone) {
    sharedScaleReg := sharedScaleWire
    int8OutReg     := int8Wire
  }

  io.valid_out        := validOutReg
  io.shared_scale_out := Cat(sharedScaleReg.toSeq)
  io.int8_out := Cat(
    for (row <- 0 until cfg.tileRows; col <- 0 until cfg.blockSize)
      yield int8OutReg(row)(col)
  )
}

// ============================================================
// Emission helpers
// ============================================================
object RequantINT8Main extends App {
  Seq(
    RequantINT8Config(blockSize = 32, tileRows = 4, tileCols = 4),
    RequantINT8Config(blockSize = 16, tileRows = 8, tileCols = 8),
    RequantINT8Config(blockSize = 64, tileRows = 4, tileCols = 4),
  ).foreach { cfg =>
    println(s"Generating RequantINT8: blk${cfg.blockSize} ${cfg.tileRows}x${cfg.tileCols}")
    emitVerilog(
      new RequantINT8(cfg),
      Array("--target-dir",
            s"generated/requant/INT8_UE8M0_blk${cfg.blockSize}_${cfg.tileRows}x${cfg.tileCols}")
    )
  }
}
