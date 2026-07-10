package mx.mac.deferred_archs

import chisel3._
import chisel3.util.{Cat, log2Ceil, RegEnable}
import mx.mac._

/** Block-deferred FDPU variant — Arch-IV.a (DROPPED FROM THESIS).
 *
 *  Two-level FP accumulator: scale apply is deferred to the cyclesPerBlock-th
 *  cycle (block boundary) instead of running every cycle.
 *
 *  Per cycle (intra-block):
 *    lane products → tree → "convert CustomFP → FP without scale"
 *      → cycleFPnoscale (FP, no scale applied)
 *    innerAccReg ← FPNAdder(innerAccReg, cycleFPnoscale)
 *
 *  On the cyclesPerBlock-th cycle (block boundary):
 *    blockPartialFP = innerNext × scaleA × scaleB     (one bridge per block)
 *    outerAccReg    ← FPNAdder(outerAccReg, blockPartialFP)
 *    innerAccReg    ← 0
 *
 *  Correctness precondition: share_exp_A_i / share_exp_B_i must be held
 *  constant across the cyclesPerBlock cycles of one block (MX block semantics).
 *
 *  ── Status ──
 *  This architecture was Pareto-dominated by cycleFP (+10 % power, −30 %
 *  fmax) in the area/timing sweep and was DROPPED from the thesis.  This file
 *  is kept under `deferred_archs/` for reviewer reference and as input to the
 *  arch-comparison emit (EmitArchCompare).  No production caller depends on
 *  it for the deployed PE.
 *
 *  IO bundle is identical to FDPUPostScaleReductionTree (baseline) so callers
 *  can swap between the two without touching connections.
 */
class FDPUBlockDeferred(
  val scfg:          ScaleAddConfig,
  val vectorSize:    Int,
  val K:             Int        = 32,
  val accMantBits:   Int        = -1,
  val treeArch:      TreeArch   = TreeArch.Generic,
  val cyclesPerBlock: Int,
  val istest:        Boolean    = false,
  val widenUE8M0:    Boolean    = true,
) extends Module {
  require(cyclesPerBlock >= 2,
    s"FDPUBlockDeferred requires cyclesPerBlock >= 2, got $cyclesPerBlock; " +
    "use FDPUPostScaleReductionTree for the single-acc baseline (cpb == 1).")
  require(vectorSize >= 1, "vectorSize must be >= 1")
  require(K >= 1, "K must be >= 1")

  // ── Shared width math (mirrors FDPUPostScaleReductionTree) ──
  private val w = FDPUWidthMath(scfg, vectorSize, K,
    accMantBits = accMantBits, widenUE8M0 = widenUE8M0)
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
    else s"FDPUBlockDeferred_${scfg.elementTypeA.name}_x_${scfg.elementTypeB.name}" +
         s"_scale_${scfg.stype.name}_vec${vectorSize}_K${K}" +
         s"_cpb${cyclesPerBlock}_acc${actualAccMantBits}b"

  val io = IO(new Bundle {
    val op_a_i        = Input(UInt((vectorSize * wA).W))
    val op_b_i        = Input(UInt((vectorSize * wB).W))
    val share_exp_A_i = Input(UInt(scfg.stype.totalScaleWidth.W))
    val share_exp_B_i = Input(UInt(scfg.stype.totalScaleWidth.W))
    val validIn  = Input(Bool())
    val resetAcc = Input(Bool())
    val validOut = Output(Bool())
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

  // ── FixedFP reduction tree (widened outMantW + expW) ──
  val tree = Module(new FixedFPReductionTree(
    expW            = effectiveExpW,
    inMantW         = scfg.resOperatorMantWidth,
    outMantW        = effectiveTreeOutMantW,
    vectorSize      = vectorSize,
    productExpRange = scfg.productExpRange,
    arch            = treeArch
  ))
  for (i <- 0 until vectorSize) {
    tree.io.inputs(i).sign := laneOp(i).sign
    tree.io.inputs(i).mant := laneOp(i).mant
    tree.io.inputs(i).exp  := laneOp(i).exp
  }
  val treeOut = tree.io.out

  io.debug.foreach { d =>
    d.tree_out_sign := treeOut.sign
    d.tree_out_exp  := treeOut.exp
    val mantDebug = if (effectiveTreeOutMantW >= scfg.resOperatorMantWidth)
                      treeOut.mant(effectiveTreeOutMantW - 1,
                                   effectiveTreeOutMantW - scfg.resOperatorMantWidth)
                    else treeOut.mant
    d.tree_out_mant := mantDebug
  }

  // ── Tree CustomFP → FP (NO scale applied yet) ────────────────────────
  val cycleFPnoscale = Wire(UInt(fpNW.W))
  if (scfg.stype.mantScaleWidth == 0) {
    // UE8M0: scale value = 1.0 (no widening). DirectToFPn with bias inputs
    // is expected to collapse to identity through synthesis const-folding.
    val biasU = scfg.stype.bias.U(scfg.stype.totalScaleWidth.W)
    val d2fpnInner = Module(new DirectToFPn(scfg, actualAccMantBits,
      opExpWOverride = effectiveExpW))
    d2fpnInner.io.inOpSign      := treeOut.sign
    d2fpnInner.io.inOpExp       := treeOut.exp
    d2fpnInner.io.inOpMant      := treeOut.mant
    d2fpnInner.io.inShareScaleA := biasU
    d2fpnInner.io.inShareScaleB := biasU
    cycleFPnoscale := d2fpnInner.io.out

    io.debug.foreach { d =>
      val expDebug = Wire(SInt(scfg.resScaleAddExpWidth.W))
      expDebug := treeOut.exp
      d.sa_out_sign := treeOut.sign
      d.sa_out_exp  := expDebug
      d.sa_out_mant := Cat(0.U(2.W), treeOut.mant)
    }
  } else {
    // non-UE8M0 per-cycle: direct CustomFP → IEEE FP bit-concat (no mant mult).
    // Rationale: the per-cycle inner accumulation must NOT apply the real
    // scale (that happens only at the block boundary).  Composing the FP
    // word directly avoids leaving an unused LZC/barrel-shift/RNE in the
    // post-synth netlist.
    require(effectiveTreeOutMantW <= actualAccMantBits,
      s"per-cycle no-scale path requires effectiveTreeOutMantW " +
      s"($effectiveTreeOutMantW) <= actualAccMantBits ($actualAccMantBits)")
    val padBits = actualAccMantBits - effectiveTreeOutMantW
    val biasedE = (treeOut.exp.asSInt + 127.S).asUInt
    val encExp  = biasedE(7, 0)
    val padded  = if (padBits > 0) Cat(treeOut.mant, 0.U(padBits.W))
                  else if (padBits == 0) treeOut.mant
                  else treeOut.mant(effectiveTreeOutMantW - 1,
                                    effectiveTreeOutMantW - actualAccMantBits)
    cycleFPnoscale := Cat(treeOut.sign, encExp, padded)

    io.debug.foreach { d =>
      val expDebug = Wire(SInt(scfg.resScaleAddExpWidth.W))
      expDebug := treeOut.exp
      d.sa_out_sign := treeOut.sign
      d.sa_out_exp  := expDebug
      val mantDebug = if (effectiveScaleAddMantW >= scfg.resScaleAddMantWidth)
                        Cat(treeOut.mant, 0.U((effectiveScaleAddMantW
                            - effectiveTreeOutMantW).W))(
                            effectiveScaleAddMantW - 1,
                            effectiveScaleAddMantW - scfg.resScaleAddMantWidth)
                      else treeOut.mant
      d.sa_out_mant := mantDebug
    }
  }

  // ── Inner FP accumulator (no scale applied yet) ────────────────────────
  val asyncRstN   = (!reset.asBool).asAsyncReset
  val innerAccReg = withReset(asyncRstN)(RegInit(0.U(fpNW.W)))

  val innerAdder = Module(new FPNAdder(actualAccMantBits))
  innerAdder.io.a := innerAccReg
  innerAdder.io.b := cycleFPnoscale
  val innerNext = innerAdder.io.out

  // ── Block-cycle counter ────────────────────────────────────────────────
  val blockCnt = withReset(asyncRstN)(RegInit(0.U(log2Ceil(cyclesPerBlock).W)))
  val isLastCycleOfBlock = blockCnt === (cyclesPerBlock - 1).U
  val blockDone = io.validIn && isLastCycleOfBlock

  // ── Block-boundary bridge: apply scale to innerNext → blockPartialFP ───
  val blockPartialFP = Wire(UInt(fpNW.W))
  if (scfg.stype.mantScaleWidth == 0) {
    // UE8M0: scale apply is biased-exp addition by (scaleA−bias)+(scaleB−bias).
    val innerSign = innerNext(fpNW - 1)
    val innerExpU = innerNext(fpNW - 2, fpNW - 1 - 8)
    val innerMant = innerNext(actualAccMantBits - 1, 0)

    val adjScaleA = io.share_exp_A_i.zext - scfg.stype.bias.S
    val adjScaleB = io.share_exp_B_i.zext - scfg.stype.bias.S
    val scaleAdj  = adjScaleA +& adjScaleB

    val isInnerZero       = innerExpU === 0.U && innerMant === 0.U
    val blockExpRaw       = innerExpU.zext +& scaleAdj
    val blockExpOverflow  = blockExpRaw >= 255.S
    val blockExpUnderflow = blockExpRaw <= 0.S

    blockPartialFP :=
      Mux(isInnerZero || (blockExpUnderflow && !blockExpOverflow), 0.U(fpNW.W),
      Mux(blockExpOverflow, Cat(innerSign, 255.U(8.W), 0.U(actualAccMantBits.W)),
          Cat(innerSign, blockExpRaw.asUInt(7, 0), innerMant)))
  } else {
    // non-UE8M0: real scale_mant × scale_mant × inner_mant via FPxScale.
    // Operand isolation: FPxScale's mantissa multiplier / LZC / normalize
    // are combinational; holding inputs stable on the cyclesPerBlock-1
    // idle cycles cuts switching power by ~7/8.
    val innerHeld  = withReset(asyncRstN)(RegEnable(innerNext,  isLastCycleOfBlock))
    val scaleAHeld = withReset(asyncRstN)(RegEnable(io.share_exp_A_i, isLastCycleOfBlock))
    val scaleBHeld = withReset(asyncRstN)(RegEnable(io.share_exp_B_i, isLastCycleOfBlock))
    val fpScale = Module(new FPxScale(scfg, actualAccMantBits))
    fpScale.io.fpIn   := Mux(isLastCycleOfBlock, innerNext,        innerHeld)
    fpScale.io.scaleA := Mux(isLastCycleOfBlock, io.share_exp_A_i, scaleAHeld)
    fpScale.io.scaleB := Mux(isLastCycleOfBlock, io.share_exp_B_i, scaleBHeld)
    blockPartialFP := fpScale.io.fpOut
  }

  // ── Outer FP accumulator (block partials) ─────────────────────────────
  val outerAccReg = withReset(asyncRstN)(RegInit(0.U(fpNW.W)))

  val blockPartialHeld = withReset(asyncRstN)(RegEnable(blockPartialFP, isLastCycleOfBlock))
  val outerAdder = Module(new FPNAdder(actualAccMantBits))
  outerAdder.io.a := outerAccReg
  outerAdder.io.b := Mux(isLastCycleOfBlock, blockPartialFP, blockPartialHeld)

  val validReg = withReset(asyncRstN)(RegInit(false.B))

  // ── State updates ─────────────────────────────────────────────────────
  when(io.resetAcc) {
    innerAccReg := 0.U
    outerAccReg := 0.U
    blockCnt    := 0.U
    validReg    := false.B
  }.elsewhen(io.validIn) {
    when(blockDone) {
      innerAccReg := 0.U
      outerAccReg := outerAdder.io.out
      blockCnt    := 0.U
    }.otherwise {
      innerAccReg := innerAdder.io.out
      blockCnt    := blockCnt + 1.U
    }
    validReg := true.B
  }.otherwise {
    validReg := false.B
  }

  io.validOut := validReg
  io.accOut := outerAccReg

  io.debug.foreach { d =>
    d.reducedSum := (
      if (actualAccMantBits < 23) Cat(cycleFPnoscale, 0.U((23 - actualAccMantBits).W))
      else cycleFPnoscale
    )
    d.innerAccReg   := Cat(0.U((96 - fpNW).W), innerAccReg)
    d.outerAccReg   := Cat(0.U((96 - fpNW).W), outerAccReg)
    d.blockDoneFlag := blockDone
  }
}
