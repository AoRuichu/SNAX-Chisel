package mx.mac

import chisel3._
import chisel3.util.Cat

// ============================================================
// Fused Dot-Product Unit — Post-Scale Reduction Tree (BASELINE)
// ============================================================
/** Fused dot-product unit: MAC → FixedFP Reduction Tree → ScaleAddition → FP Accumulator.
 *
 *  Baseline cycleFP architecture used by the thesis.  Two earlier
 *  architecture explorations (blockdef, kulisch) lived in this file and
 *  have been moved to [[mx.mac.deferred_archs]]; they are reachable from
 *  the DSE-compare emit but are not on the deployed PE path.
 *
 *  Pipeline (fully combinational within each cycle):
 *
 *    ┌─ lane 0 ─┐
 *    │ CustomOp ├──►┐
 *    └──────────┘   │   ┌───────────────────┐   ┌────────────────────┐   ┌──────────  ─┐
 *        ...        ├──►│  FixedFP          ├──►│ ScaleAddition +    ├──►│ FP Accum    │
 *    ┌─ lane N-1─┐  │   │  Reduction Tree   │   │ ScaleToFPn         │   │ (M_acc bits)├──► accOut
 *    │ CustomOp  ├──►   └───────────────────┘   └────────────────────┘   └────────────  ┘
 *    └───────────┘
 *
 *  Reduction tree design:
 *    - One maxExp comparator tree across N lanes
 *    - N bounded alignment right-shifts (≤ productExpRange positions)
 *    - (N−1) plain 2's-complement integer adders
 *    - One LZC + normalisation shift + RNE round at the output
 *
 *  Correctness: sa/sb are shared across all lanes, so by the distributive property:
 *    Σᵢ(macᵢ × sa × sb) = (Σᵢ macᵢ) × sa × sb
 *
 *  Accumulator precision:
 *    The accumulator register stores a reduced-precision FP value with `accMantBits`
 *    mantissa bits (default: computed by AccPrecision.recommended from K and scfg).
 *    This is sufficient because accumulation noise stays below the requant noise floor
 *    when accMantBits ≥ rqFloor + ½·log₂(K).
 *    accOut is (1+8+accMantBits) bits wide — the native register width with no
 *    zero-padding.  Downstream consumers that need IEEE-754 FP32 must right-pad
 *    the mantissa to 23 bits.
 *
 *  @param scfg        ScaleAddConfig describing element and scale types.
 *  @param vectorSize  Number of parallel MACs per cycle (>= 1).
 *  @param K           Accumulation depth.  Used to derive the default accMantBits.
 *  @param accMantBits Accumulator mantissa bits.  `-1` = auto (AccPrecision.recommended).
 *                     Override to 23 for full FP32 accumulation.
 *  @param treeArch    Tree architecture (Generic / IntOnlySigned / SmallFixedShift /
 *                     TwoStageBarrel).  KulischInner is not valid here — use
 *                     [[mx.mac.deferred_archs.FDPUKulischDeferred]].
 *  @param istest      Enable debug ports for simulation visibility.
 *  @param noEarlyRNE  Counterfactual: skip tree-exit + scale-comp RNE; tree emits
 *                     its full absMagW mantissa unrounded; only the FPNAdder-internal
 *                     RNE remains; forces M_acc = 23 (FP32).  Used by the chapter's
 *                     area-saved comparison vs. our 3-RNE design.
 *  @param widenUE8M0  UE8M0-only optimisation: widen tree-exit mantissa to M_acc
 *                     bits so the per-cycle tree-exit RNE becomes M_acc-dependent.
 *                     Default true.  Strict area-iso, accuracy-gain change.
 */
class FDPUPostScaleReductionTree(
  val scfg:        ScaleAddConfig,
  val vectorSize:  Int,
  val K:           Int      = 32,
  val accMantBits: Int      = -1,
  val treeArch:    TreeArch = TreeArch.Generic,
  val istest:      Boolean  = false,
  val noEarlyRNE:  Boolean  = false,
  val widenUE8M0:  Boolean  = true,
  /** Override the productExpRange (alignment-shift cap) handed to the reduction
   *  tree.  Default −1 → use scfg.productExpRange (the strict upper bound on
   *  diffRaw).  Setting a smaller value caps alignment shifts: any lane whose
   *  exp differs from maxExp by more than this gets clamped, trading precision
   *  on outlier lanes for narrower tree internal width.  Used by the tree
   *  shrink DSE — see DSE notes in the chapter. */
  val treeProductExpRangeOverride: Int = -1,
) extends Module {
  require(vectorSize >= 1, "vectorSize must be >= 1")
  require(K >= 1, "K must be >= 1")
  require(treeArch != TreeArch.KulischInner,
    "treeArch=KulischInner not supported on baseline FDPUPostScaleReductionTree; " +
    "use mx.mac.deferred_archs.FDPUKulischDeferred (cpb >= 2).")

  // ── Shared width derivation ───────────────────────────────────────────
  private val w = FDPUWidthMath(scfg, vectorSize, K,
    accMantBits = accMantBits,
    noEarlyRNE  = noEarlyRNE,
    widenUE8M0  = widenUE8M0)
  /** Public so tests can pull the resolved M_acc without re-deriving. */
  val actualAccMantBits: Int       = w.actualAccMantBits
  val treeExtraMantBits: Int       = w.treeExtraMantBits
  val effectiveTreeOutMantW: Int   = w.effectiveTreeOutMantW
  val effectiveScaleAddMantW: Int  = w.effectiveScaleAddMantW
  val effectiveExpW: Int           = w.effectiveExpW
  private val fpNW = w.fpNW

  private val wA = scfg.elementTypeA.totalWidth
  private val wB = scfg.elementTypeB.totalWidth

  override def desiredName =
    if (!istest) "BFP_PE"
    else s"FDPUPostScale_${scfg.elementTypeA.name}_x_${scfg.elementTypeB.name}" +
         s"_scale_${scfg.stype.name}_vec${vectorSize}_K${K}_acc${actualAccMantBits}b"

  val io = IO(new Bundle {
    val op_a_i        = Input(UInt((vectorSize * wA).W))
    val op_b_i        = Input(UInt((vectorSize * wB).W))
    val share_exp_A_i = Input(UInt(scfg.stype.totalScaleWidth.W))
    val share_exp_B_i = Input(UInt(scfg.stype.totalScaleWidth.W))
    val validIn  = Input(Bool())
    val resetAcc = Input(Bool())
    val validOut = Output(Bool())
    /** Narrow FP output: {sign[1], exp[8], mant[actualAccMantBits]}.
     *  Downstream consumers that need IEEE-754 FP32 must zero-extend the
     *  mantissa to 23 bits (right-pad). */
    val accOut   = Output(UInt(fpNW.W))

    val debug = if (istest) Some(new Bundle {
      val all_lanes_op_sign = Output(Vec(vectorSize, UInt(1.W)))
      val all_lanes_op_exp  = Output(Vec(vectorSize, SInt(scfg.resOperatorExpWidth.W)))
      val all_lanes_op_mant = Output(Vec(vectorSize, UInt(scfg.resOperatorMantWidth.W)))
      val tree_out_sign     = Output(UInt(1.W))
      val tree_out_exp      = Output(SInt(scfg.resOperatorExpWidth.W))
      val tree_out_mant     = Output(UInt(scfg.resOperatorMantWidth.W))
      val sa_out_sign       = Output(UInt(1.W))
      val sa_out_exp        = Output(SInt(scfg.resScaleAddExpWidth.W))
      val sa_out_mant       = Output(UInt(scfg.resScaleAddMantWidth.W))
      val reducedSum        = Output(UInt(32.W))
      /** Widths padded to 96 bits for uniform debug ports across arch variants.
       *  Baseline: innerAcc == outerAcc == the single accReg. */
      val innerAccReg       = Output(UInt(96.W))
      val outerAccReg       = Output(UInt(96.W))
      val blockDoneFlag     = Output(Bool())
    }) else None
  })

  // ── Per-lane MAC ───────────────────────────────────────────────────────
  val laneOp = Wire(Vec(vectorSize, new CustomFP(scfg.resOperatorExpWidth, scfg.resOperatorMantWidth)))
  for (i <- 0 until vectorSize) {
    val op = Module(new CustomOperator(OperatorConfig(scfg.elementTypeA, scfg.elementTypeB)))
    op.io.inA := io.op_a_i((i + 1) * wA - 1, i * wA)
    op.io.inB := io.op_b_i((i + 1) * wB - 1, i * wB)

    laneOp(i).sign := op.io.outSign
    laneOp(i).mant := op.io.outMant
    laneOp(i).exp  := op.io.outExp

    io.debug.foreach { d =>
      d.all_lanes_op_sign(i) := op.io.outSign
      d.all_lanes_op_exp(i)  := op.io.outExp
      d.all_lanes_op_mant(i) := op.io.outMant
    }
  }

  // ── FixedFP reduction tree ────────────────────────────────────────────
  // outMantW = effectiveTreeOutMantW exposes additional bits beyond the legacy
  // resScaleAddMantWidth−3 cap; expW is widened in lockstep to absorb the
  // extra negative range from the post-LZC exp arithmetic.
  val effectiveProductExpRange =
    if (treeProductExpRangeOverride > 0) treeProductExpRangeOverride
    else scfg.productExpRange
  val tree = Module(new FixedFPReductionTree(
    expW            = effectiveExpW,
    inMantW         = scfg.resOperatorMantWidth,
    outMantW        = effectiveTreeOutMantW,
    vectorSize      = vectorSize,
    productExpRange = effectiveProductExpRange,
    arch            = treeArch,
    skipFinalRound  = noEarlyRNE   // Counterfactual: skip tree-exit RNE
  ))
  for (i <- 0 until vectorSize) {
    tree.io.inputs(i).sign := laneOp(i).sign
    tree.io.inputs(i).mant := laneOp(i).mant
    tree.io.inputs(i).exp  := laneOp(i).exp
  }
  val treeOut = tree.io.out  // CustomFP(effectiveExpW, effectiveTreeOutMantW)

  io.debug.foreach { d =>
    d.tree_out_sign := treeOut.sign
    d.tree_out_exp  := treeOut.exp
    val mantDebug = if (effectiveTreeOutMantW >= scfg.resOperatorMantWidth)
                      treeOut.mant(effectiveTreeOutMantW - 1,
                                   effectiveTreeOutMantW - scfg.resOperatorMantWidth)
                    else treeOut.mant
    d.tree_out_mant := mantDebug
  }

  // ── ScaleAdd + FPn conversion: path selected at elaboration time ──────
  // All intermediate values use fpNW = 1+8+actualAccMantBits bits throughout.
  // UE8M0: DirectToFPn — pure exponent arithmetic, no multipliers.
  // Non-UE8M0: ScaleAddition + ScaleToFPn — LZC normalise + RNE round.
  val reducedSum = Wire(UInt(fpNW.W))

  if (scfg.stype.mantScaleWidth == 0) {
    // ── Path A: UE8M0 — DirectToFPn ──────────────────────────────────────
    val d2fpn = Module(new DirectToFPn(scfg, actualAccMantBits,
      opMantWOverride = if (widenUE8M0) effectiveTreeOutMantW else -1,
      opExpWOverride  = effectiveExpW))
    d2fpn.io.inOpSign      := treeOut.sign
    d2fpn.io.inOpExp       := treeOut.exp
    d2fpn.io.inOpMant      := treeOut.mant
    d2fpn.io.inShareScaleA := io.share_exp_A_i
    d2fpn.io.inShareScaleB := io.share_exp_B_i
    reducedSum             := d2fpn.io.out

    io.debug.foreach { d =>
      val adjA = io.share_exp_A_i.zext - scfg.stype.bias.S
      val adjB = io.share_exp_B_i.zext - scfg.stype.bias.S
      val expDebug = Wire(SInt(scfg.resScaleAddExpWidth.W))
      expDebug := treeOut.exp + adjA + adjB
      d.sa_out_sign := treeOut.sign
      d.sa_out_exp  := expDebug
      d.sa_out_mant := Cat(0.U(2.W), treeOut.mant)
    }
  } else {
    // ── Path B: non-UE8M0 — ScaleAddition + ScaleToFPn ───────────────────
    val sa = Module(new ScaleAddition(scfg,
      inOpMantWOverride = effectiveTreeOutMantW,
      inOpExpWOverride  = effectiveExpW))
    sa.io.inOpSign      := treeOut.sign
    sa.io.inOpExp       := treeOut.exp
    sa.io.inOpMant      := treeOut.mant
    sa.io.inShareScaleA := io.share_exp_A_i
    sa.io.inShareScaleB := io.share_exp_B_i

    io.debug.foreach { d =>
      d.sa_out_sign := sa.io.outSign
      d.sa_out_exp  := sa.io.outExp(scfg.resScaleAddExpWidth - 1, 0).asSInt
      val saMantDebug = if (effectiveScaleAddMantW >= scfg.resScaleAddMantWidth)
                          sa.io.outMant(effectiveScaleAddMantW - 1,
                                        effectiveScaleAddMantW - scfg.resScaleAddMantWidth)
                        else sa.io.outMant
      d.sa_out_mant := saMantDebug
    }

    val conv = Module(new ScaleToFPn(scfg, actualAccMantBits,
      inMantWOverride = effectiveScaleAddMantW,
      inExpWOverride  = sa.effectiveOutExpW))
    conv.io.inSign := sa.io.outSign
    conv.io.inExp  := sa.io.outExp
    conv.io.inMant := sa.io.outMant
    reducedSum     := conv.io.out
  }

  io.debug.foreach { d =>
    d.reducedSum := (if (actualAccMantBits < 23) Cat(reducedSum, 0.U((23 - actualAccMantBits).W))
                     else reducedSum)
  }

  // ── Reduced-precision FP accumulator register ─────────────────────────
  // accReg holds fpNW = (1 + 8 + actualAccMantBits) bits.
  // FPNAdder natively operates at fpNW precision — no post-add truncation.
  val asyncRstN = (!reset.asBool).asAsyncReset
  val accReg    = withReset(asyncRstN)(RegInit(0.U(fpNW.W)))
  val validReg  = withReset(asyncRstN)(RegInit(false.B))

  val accAdder = Module(new FPNAdder(actualAccMantBits))
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
  io.accOut := accReg

  io.debug.foreach { d =>
    // Baseline: innerAcc == outerAcc == the one accReg.
    d.innerAccReg   := Cat(0.U((96 - fpNW).W), accReg)
    d.outerAccReg   := Cat(0.U((96 - fpNW).W), accReg)
    d.blockDoneFlag := io.validIn   // every valid cycle is "block-done"
  }
}

// ============================================================
// Emission helpers (baseline only)
// ============================================================
// Single-config + full-sweep emit objects.  The DSE-compare emit
// (DSECompareEmitMain) moved to mx.mac.deferred_archs.DSEEmitDriver
// since its 4-way comparison includes the dropped blockdef/kulisch archs.

object FDPUPostScaleMain extends App {
  val scfg       = ScaleAddConfig(MXFormats.E4M3, MXFormats.E2M1, ScaleFormats.UE5M3)
  val vectorSize = 8
  emitVerilog(
    new FDPUPostScaleReductionTree(scfg, vectorSize, istest = false),
    Array("--target-dir", s"generated/fdpu_post_scale/default_vec${vectorSize}")
  )
}

object AllFDPUPostScaleMain extends App {
  val vectorSizes  = Seq(4, 16)
  val elementTypes = MXFormats.allElementTypes
  val scaleTypes   = Seq(ScaleFormats.UE8M0, ScaleFormats.UE6M2, ScaleFormats.UE4M4)
  val lowPrecision = Set(MXFormats.E3M2, MXFormats.E2M3, MXFormats.E2M1)

  for {
    (typeA, i) <- elementTypes.zipWithIndex
    typeB      <- elementTypes.drop(i)
    if !(lowPrecision(typeA) && lowPrecision(typeB))
    stype      <- scaleTypes
    vsize      <- vectorSizes
  } {
    val scfg = ScaleAddConfig(typeA, typeB, stype)
    println(
      s"Generating FDPUPostScale: ${typeA.name} x ${typeB.name}, " +
      s"scale ${stype.name}, vectorSize=$vsize"
    )
    emitVerilog(
      new FDPUPostScaleReductionTree(scfg, vsize, istest = false),
      Array("--target-dir",
        s"generated/post_scale/${typeA.name}_${typeB.name}_${stype.name}_vec${vsize}")
    )
  }
}
