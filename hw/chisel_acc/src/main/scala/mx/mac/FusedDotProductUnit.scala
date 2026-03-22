package mx.mac

import chisel3._
import chisel3.util._

// ============================================================
// Combinational FP32 adder (extracted from ScaleAccumulatorFP32)
// ============================================================
/** Purely-combinational IEEE-754 single-precision adder.
 *
 *  Supports both addition and subtraction (sign-magnitude).
 *  Uses 3 extra guard/round/sticky bits for RNE rounding.
 *  No special-case handling for NaN/Inf (consistent with the
 *  rest of the mx.mac pipeline).
 */
class FP32Adder extends Module {
  val io = IO(new Bundle {
    val a   = Input(UInt(32.W))
    val b   = Input(UInt(32.W))
    val out = Output(UInt(32.W))
  })

  val valA_S = io.a(31)
  val valA_E = io.a(30, 23)
  val valA_M = Cat(valA_E.orR, io.a(22, 0)) // 24-bit with implicit-1

  val valB_S = io.b(31)
  val valB_E = io.b(30, 23)
  val valB_M = Cat(valB_E.orR, io.b(22, 0))

  // Determine which operand has larger magnitude
  val expDiff  = valA_E.zext - valB_E.zext
  val aGreater = expDiff > 0.S || (expDiff === 0.S && valA_M >= valB_M)

  val farExp = Mux(aGreater, valA_E, valB_E)
  val nearM  = Mux(aGreater, valB_M, valA_M)
  val farM   = Mux(aGreater, valA_M, valB_M)

  // Align smaller operand; keep 3 extra precision bits for rounding
  val absExpDiff   = Mux(aGreater, expDiff.asUInt, (-expDiff).asUInt)
  val alignedNearM = (nearM << 3) >> absExpDiff

  // Add / subtract (28-bit result)
  val isSub  = valA_S ^ valB_S
  val farM27 = farM << 3
  val resMag: UInt = Mux(
    isSub,
    Cat(0.U(1.W), farM27) - Cat(0.U(1.W), alignedNearM),
    farM27 +& alignedNearM
  )

  val resSign = Mux(aGreater, valA_S, valB_S)

  // Normalize
  val resLZC = PriorityEncoder(resMag.asBools.reverse)
  val finalM = (resMag << resLZC)(26, 4) // 23 explicit mantissa bits
  val finalE = farExp.zext - resLZC.asSInt + 1.S

  // Exact cancellation → zero
  val isZero = resMag === 0.U
  io.out := Mux(isZero, 0.U(32.W), Cat(resSign, finalE(7, 0), finalM(22, 0)))
}

// ============================================================
// ScaleAddition output → IEEE FP32 conversion
// ============================================================
/** Converts the (sign, exp, mant) output of ScaleAddition to a
 *  32-bit IEEE-754 float using LZC normalization and RNE rounding.
 *
 *  Replicates the normalization stage that was previously
 *  baked into ScaleAccumulatorFP32.
 */
class ScaleToFP32(val scfg: ScaleAddConfig) extends Module {
  override def desiredName =
    s"ScaleToFP32_${scfg.elementTypeA.name}_x_${scfg.elementTypeB.name}_${scfg.stype.name}"

  val io = IO(new Bundle {
    val inSign = Input(UInt(1.W))
    val inExp  = Input(SInt(scfg.resScaleAddExpWidth.W))
    val inMant = Input(UInt(scfg.resScaleAddMantWidth.W))
    val out    = Output(UInt(32.W))
  })

  private def elemFrac(t: ElementType): Int =
    if (t.name == "INT8") 0 else t.elementWidthMant

  val fracBits  = elemFrac(scfg.elementTypeA) + elemFrac(scfg.elementTypeB) +
                  2 * scfg.stype.mantScaleWidth
  val mantWidth = scfg.resScaleAddMantWidth
  val expBias   = 127 + mantWidth - 1 - fracBits

  // Normalization: find leading-one position
  val lzc         = PriorityEncoder(io.inMant.asBools.reverse)
  val shiftedMant = io.inMant << lzc

  // Ensure we always have enough bits below the implicit-1 for extraction
  val EXTRA = (27 - mantWidth) max 0
  val paddedShift =
    if (EXTRA > 0) Cat(shiftedMant(mantWidth - 1, 0), 0.U(EXTRA.W))
    else           shiftedMant(mantWidth - 1, 0)
  val safeExtractPos = EXTRA + mantWidth - 2  // bit index of first fractional bit

  // RNE rounding
  val mant23    = paddedShift(safeExtractPos, safeExtractPos - 22)
  val guardBit  = paddedShift(safeExtractPos - 23)
  val roundBit  = paddedShift(safeExtractPos - 24)
  val stickyBit = paddedShift(safeExtractPos - 25, 0).orR

  val roundUp   = guardBit && (mant23(0) || roundBit || stickyBit)
  val finalMant = Mux(roundUp, mant23 + 1.U, mant23)

  // Exponent with bias correction
  val adjustedExp = io.inExp - lzc.zext + expBias.S
  val isOverflow  = adjustedExp >= 255.S
  val isUnderflow = adjustedExp <= 0.S

  val finalExp = Mux(isOverflow,  255.U(8.W),
                 Mux(isUnderflow, 0.U(8.W), adjustedExp.asUInt(7, 0)))

  io.out := Cat(io.inSign, finalExp, finalMant)
}

// ============================================================
// Fused Dot-Product Unit
// ============================================================
/** Fused dot-product unit that performs vectorSize MAC operations per cycle.
 *
 *  Pipeline (fully combinational within each cycle):
 *
 *    ┌─ lane 0 ─┐   ┌──────────────┐
 *    │ CustomOp │   │              │
 *    │ ScaleAdd ├──►│              │
 *    │ ToFP32   │   │  FP32        │   ┌─────────┐
 *    └──────────┘   │  Reduction   ├──►│  FP32   │
 *        ...        │  Tree        │   │  Accum  ├──► accOut
 *    ┌─ lane N-1 ─┐ │  (balanced)  │   │  Reg    │
 *    │ CustomOp  │  │              │   └─────────┘
 *    │ ScaleAdd  ├──►│              │
 *    │ ToFP32    │  └──────────────┘
 *    └───────────┘
 *
 *  The shared scale factors (share_exp_A_i / share_exp_B_i) are
 *  broadcast to every lane, matching the MX block-floating-point
 *  convention where one scale covers an entire vector.
 *
 *  @param scfg       ScaleAddConfig describing element and scale types.
 *  @param vectorSize Number of parallel MACs per cycle (>= 1).
 */
class FusedDotProductUnit(val scfg: ScaleAddConfig, val vectorSize: Int) extends Module {
  require(vectorSize >= 1, "vectorSize must be >= 1")

  override def desiredName =
    s"FusedDotProductUnit_${scfg.elementTypeA.name}_x_${scfg.elementTypeB.name}" +
    s"_scale_${scfg.stype.name}_vec${vectorSize}"

  val io = IO(new Bundle {
    // Vectorized element inputs — one entry per lane
    val op_a_i        = Input(Vec(vectorSize, UInt(scfg.elementTypeA.totalWidth.W)))
    val op_b_i        = Input(Vec(vectorSize, UInt(scfg.elementTypeB.totalWidth.W)))
    // Shared scale factors (broadcast to all lanes)
    val share_exp_A_i = Input(UInt(scfg.stype.totalScaleWidth.W))
    val share_exp_B_i = Input(UInt(scfg.stype.totalScaleWidth.W))
    // Control
    val validIn  = Input(Bool())
    val resetAcc = Input(Bool())
    // Output
    val validOut = Output(Bool())
    val accOut   = Output(UInt(32.W))
  })

  // ------------------------------------------------------------------
  // Lanes: vectorSize × (CustomOperator → ScaleAddition → ScaleToFP32)
  // ------------------------------------------------------------------
  val laneResults = Wire(Vec(vectorSize, UInt(32.W)))

  for (i <- 0 until vectorSize) {
    val op = Module(new CustomOperator(OperatorConfig(scfg.elementTypeA, scfg.elementTypeB)))
    op.io.inA := io.op_a_i(i)
    op.io.inB := io.op_b_i(i)

    val sa = Module(new ScaleAddition(scfg))
    sa.io.inOpSign      := op.io.outSign
    sa.io.inOpExp       := op.io.outExp
    sa.io.inOpMant      := op.io.outMant
    sa.io.inShareScaleA := io.share_exp_A_i
    sa.io.inShareScaleB := io.share_exp_B_i

    val conv = Module(new ScaleToFP32(scfg))
    conv.io.inSign := sa.io.outSign
    conv.io.inExp  := sa.io.outExp
    conv.io.inMant := sa.io.outMant

    laneResults(i) := conv.io.out
  }

  // ------------------------------------------------------------------
  // FP32 balanced reduction tree
  // ------------------------------------------------------------------
  /** Recursively halve the list using FP32Adder instances until one value remains. */
  def fp32ReduceTree(inputs: Seq[UInt]): UInt = {
    if (inputs.length == 1) {
      inputs.head
    } else {
      val nextLevel = inputs.grouped(2).map { group =>
        if (group.length == 2) {
          val adder = Module(new FP32Adder())
          adder.io.a := group(0)
          adder.io.b := group(1)
          adder.io.out
        } else {
          group(0) // odd lane passes through without an adder
        }
      }.toSeq
      fp32ReduceTree(nextLevel)
    }
  }

  val reducedSum = fp32ReduceTree(laneResults.toSeq)

  // ------------------------------------------------------------------
  // FP32 accumulator register
  // ------------------------------------------------------------------
  val accReg   = RegInit(0.U(32.W))
  val validReg = RegInit(false.B)

  val accAdder = Module(new FP32Adder())
  accAdder.io.a := accReg
  accAdder.io.b := reducedSum

  when(io.resetAcc) {
    accReg   := 0.U
    validReg := false.B
  }.elsewhen(io.validIn) {
    accReg   := accAdder.io.out
    validReg := true.B
  }.otherwise {
    validReg := false.B
  }

  io.validOut := validReg
  io.accOut   := accReg
}

// ============================================================
// Emission helpers
// ============================================================

/** Emit a single FusedDotProductUnit for the default E4M3×E2M1/UE5M3 config
 *  with a configurable vector size. */
object FusedDotProductMain extends App {
  val scfg       = ScaleAddConfig(MXFormats.E4M3, MXFormats.E2M1, ScaleFormats.UE5M3)
  val vectorSize = 8

  emitVerilog(
    new FusedDotProductUnit(scfg, vectorSize),
    Array("--target-dir", s"generated/fused_dot_product/default_vec${vectorSize}")
  )
}

/** Emit Verilog for every element-type × scale-type × vector-size combination. */
object AllFusedDotProductMain extends App {
  val vectorSizes = Seq(1, 2, 4, 8, 16, 32)

  for {
    typeA <- MXFormats.allElementTypes
    typeB <- MXFormats.allElementTypes
    stype <- ScaleFormats.allScaleTypes
    vsize <- vectorSizes
  } {
    val scfg = ScaleAddConfig(typeA, typeB, stype)
    println(
      s"Generating FusedDotProductUnit: ${typeA.name} x ${typeB.name}, " +
      s"scale ${stype.name}, vectorSize=$vsize"
    )
    emitVerilog(
      new FusedDotProductUnit(scfg, vsize),
      Array("--target-dir",
        s"generated/fused_dot/${typeA.name}_${typeB.name}_${stype.name}_vec${vsize}")
    )
  }
}
