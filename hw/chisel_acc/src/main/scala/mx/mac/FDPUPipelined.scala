package mx.mac

import chisel3._
import chisel3.util.{Cat, log2Ceil, PriorityEncoder, RegEnable, Reverse, ShiftRegister}

// ============================================================
// Fused Dot-Product Unit — Pipelined variant
// ============================================================
//
// Parallel copy of FDPUPostScaleReductionTree that adds a `pipelineStages`
// parameter (0..3) controlling how many feed-forward register stages are
// inserted before the accumulator.  The non-piped class in
// FDPUPostScaleReductionTree.scala stays intact as the reference; this file
// is where pipeline-DSE development happens.
//
// Pipeline insertion points (cumulative; pipelineStages=N enables boundaries 1..N):
//
//   buildSingleAcc:
//     pipe1: after lane MAC      (registers laneOp Vec[CustomFP])
//     pipe2: after FixedFPReductionTree out (registers treeOut)
//     pipe3: after scale apply  (registers reducedSum FP word)
//
//   buildBlockDeferred:
//     pipe1: after lane MAC      (laneOp)
//     pipe2: after tree out      (treeOut)
//     pipe3: after no-scale apply(cycleFPnoscale)
//
//   buildKulischDeferred:
//     pipe1: after lane MAC      (laneOp)
//     pipe2: after per-lane align(laneAligned Vec[SInt])
//     pipe3: after V-lane signed adder tree (laneSum)
//
// Stage 5 (accumulator) is always 1-cycle reg with feedback — not piped here
// to avoid RAW hazards.  Sub-accumulator interleave is a separate knob.
// ============================================================
/** Fused dot-product unit: MAC → FixedFP Reduction Tree → single ScaleAddition → FP Accumulator.
 *
 *  Pipeline (fully combinational within each cycle):
 *
 *    ┌─ lane 0 ─┐
 *    │ CustomOp ├──►┐
 *    └──────────┘   │   ┌───────────────────┐   ┌────────────────────┐   ┌────────────┐
 *        ...        ├──►│  FixedFP Reduction ├──►│ Single ScaleAdd +  ├──►│ FP Accum   │
 *    ┌─ lane N-1 ─┐ │   │  Tree              │   │  ScaleToFP32       │   │ (accMantBits)├──► accOut
 *    │ CustomOp  ├──►┘   └───────────────────┘   └────────────────────┘   └────────────┘
 *    └───────────┘
 *
 *  Reduction tree design (replaces per-node CustomFPAdder tree):
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
 *    when accMantBits ≥ rqFloor + ½·log₂(K) (see AccPrecision for derivation).
 *    accOut is (1+8+accMantBits) bits wide — the native register width with
 *    no zero-padding. Downstream consumers that require IEEE-754 FP32 must
 *    right-pad the mantissa to 23 bits.
 *
 *  @param scfg        ScaleAddConfig describing element and scale types.
 *  @param vectorSize  Number of parallel MACs per cycle (>= 1).
 *  @param K           Accumulation depth (number of cycles per output element).
 *                     Used to derive the default accMantBits at elaboration time.
 *  @param accMantBits Accumulator mantissa bits. -1 = auto (AccPrecision.recommended).
 *                     Override to 23 for full FP32 accumulation.
 *  @param istest      Enable debug ports for simulation visibility.
 */
class FDPUPostScaleReductionTreePiped(
  val scfg: ScaleAddConfig,
  val vectorSize: Int,
  val K: Int = 32,
  val accMantBits: Int = -1,
  val treeArch: TreeArch = TreeArch.Generic,
  val cyclesPerBlock: Int = 1,
  val pipeAfterTree: Boolean = false,   // Stage A: register at the reduction-tree output
  val pipeInScale:   Boolean = false,   // Stage B: register inside FPxScale (after mant-mult)
  val istest: Boolean = false
) extends Module {
  require(cyclesPerBlock >= 1, s"cyclesPerBlock must be >= 1, got $cyclesPerBlock")
  require(vectorSize >= 1, "vectorSize must be >= 1")
  require(K >= 1, "K must be >= 1")

  // Feed-forward pipeline depth in the per-cycle datapath.  pipeAfterTree adds
  // one cycle between the (anchored) reduction tree and the downstream
  // converter/adder, which is the only outer register position that actually
  // breaks the per-cycle critical path; the post-multiply and post-conversion
  // positions tried earlier proved to be dominated (no f_max gain, extra area).
  private val feedForwardDepth: Int = if (pipeAfterTree) 1 else 0

  // pipeInScale only takes effect when the scale type carries a mantissa
  // (non-UE8M0); UE8M0's block-end scale apply is pure exp arithmetic and
  // does not benefit from internal pipelining.
  private val effectiveScalePipe: Boolean =
    pipeInScale && scfg.stype.mantScaleWidth > 0

  /** Insert a RegNext on a Chisel Data signal iff `enable` is true. */
  private def maybePipe[T <: chisel3.Data](enable: Boolean, sig: T): T =
    if (enable) RegNext(sig) else sig

  /** Delay a control signal (validIn / resetAcc) so it aligns with the in-flight
   *  data at the accumulator stage.  Without this, accReg would update on the
   *  wrong cycle's data when pipeAfterTree=true.
   */
  private def alignedCtrl(sig: Bool): Bool =
    if (feedForwardDepth == 0) sig
    else ShiftRegister(sig, feedForwardDepth)

  val actualAccMantBits: Int =
    if (accMantBits == -1) AccPrecision.recommended(scfg, K)
    else { require(accMantBits >= 1 && accMantBits <= 23); accMantBits }

  // Internal FP word width used throughout the post-tree pipeline
  private val fpNW = 1 + 8 + actualAccMantBits

  private val wA = scfg.elementTypeA.totalWidth
  private val wB = scfg.elementTypeB.totalWidth

  override def desiredName = {
    val pipeTag = (pipeAfterTree, pipeInScale) match {
      case (false, false) => ""
      case (true,  false) => "_pAT"
      case (false, true)  => "_pIS"
      case (true,  true)  => "_pAT_pIS"
    }
    if (!istest)
      s"BFP_PE${pipeTag}"
    else
      s"FDPUPostScale_${scfg.elementTypeA.name}_x_${scfg.elementTypeB.name}" +
      s"_scale_${scfg.stype.name}_vec${vectorSize}_K${K}_acc${actualAccMantBits}b${pipeTag}"
  }

  val io = IO(new Bundle {
    val op_a_i        = Input(UInt((vectorSize * wA).W))
    val op_b_i        = Input(UInt((vectorSize * wB).W))
    val share_exp_A_i = Input(UInt(scfg.stype.totalScaleWidth.W))
    val share_exp_B_i = Input(UInt(scfg.stype.totalScaleWidth.W))
    val validIn  = Input(Bool())
    val resetAcc = Input(Bool())
    val validOut = Output(Bool())
    // Narrow FP output: {sign[1], exp[8], mant[actualAccMantBits]}.
    // Matches FDPUPostScaleReductionTree.io.accOut — downstream consumers
    // that need IEEE-754 FP32 must right-pad the mantissa to 23 bits.
    val accOut   = Output(UInt((1 + 8 + actualAccMantBits).W))

    // ── Debug ports (test mode only) ────────────────────────────────────────
    val debug = if (istest) Some(new Bundle {
      // Per-lane CustomOperator outputs (tree inputs)
      val all_lanes_op_sign = Output(Vec(vectorSize, UInt(1.W)))
      val all_lanes_op_exp  = Output(Vec(vectorSize, SInt(scfg.resOperatorExpWidth.W)))
      val all_lanes_op_mant = Output(Vec(vectorSize, UInt(scfg.resOperatorMantWidth.W)))
      // FixedFP reduction tree output (single CustomFP at resOperatorMantWidth precision)
      val tree_out_sign     = Output(UInt(1.W))
      val tree_out_exp      = Output(SInt(scfg.resOperatorExpWidth.W))
      val tree_out_mant     = Output(UInt(scfg.resOperatorMantWidth.W))
      // ScaleAddition output
      val sa_out_sign       = Output(UInt(1.W))
      val sa_out_exp        = Output(SInt(scfg.resScaleAddExpWidth.W))
      val sa_out_mant       = Output(UInt(scfg.resScaleAddMantWidth.W))
      // FP32 result after ScaleToFP32
      val reducedSum        = Output(UInt(32.W))
    }) else None
  })

  // ── Elaboration-time dispatch on (cyclesPerBlock, scale type) ────────────
  // - cyclesPerBlock == 1 OR non-UE8M0:  buildSingleAcc()  (current baseline,
  //   scale apply every cycle, single FP accumulator).
  // - cyclesPerBlock >= 2 AND UE8M0:     buildBlockDeferred()  (new baseline
  //   fix: scale apply only at block boundary; two-level FP accumulator).
  //
  // The non-UE8M0 case is intentionally NOT specialised here yet — its scale
  // apply involves a mantissa × mantissa multiplication that doesn't reduce
  // cleanly to an exp-only post-shift, so block deferral needs extra work
  // and is deferred to a later iteration.
  // ── 3-way dispatch ───────────────────────────────────────────────────────
  //   useKulischDeferred : Arch-IV.b — wide fixed-point inner acc + FP outer.
  //                        Selected when treeArch == KulischInner AND UE8M0
  //                        AND cyclesPerBlock ≥ 2.  Tree is bypassed.
  //   useBlockDeferred   : Arch-IV.a — two-level FP inner+outer acc, scale
  //                        deferred to block boundary.  Used otherwise when
  //                        cyclesPerBlock ≥ 2.  Tree is still active.
  //   buildSingleAcc     : baseline single FP accumulator, scale per cycle.
  val useKulischDeferred =
    cyclesPerBlock >= 2 && (treeArch == TreeArch.KulischInner)
  val useBlockDeferred = cyclesPerBlock >= 2 && !useKulischDeferred

  if      (useKulischDeferred) buildKulischDeferred()
  else if (useBlockDeferred)   buildBlockDeferred()
  else                         buildSingleAcc()

  // ── buildSingleAcc: legacy per-cycle scale-apply + single FP accumulator ─
  private def buildSingleAcc(): Unit = {
    // share_exp aligned to scale-apply consumption point.  Scale apply occurs
    // AFTER the tree, so it sits behind the optional pipeAfterTree register.
    val shareExpA = if (feedForwardDepth == 0) io.share_exp_A_i else ShiftRegister(io.share_exp_A_i, feedForwardDepth)
    val shareExpB = if (feedForwardDepth == 0) io.share_exp_B_i else ShiftRegister(io.share_exp_B_i, feedForwardDepth)

    // ── Per-lane MAC ─────────────────────────────────────────────────────────
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

    // ── FixedFP reduction tree ────────────────────────────────────────────────
    val tree = Module(new FixedFPReductionTree(
      expW            = scfg.resOperatorExpWidth,
      inMantW         = scfg.resOperatorMantWidth,
      outMantW        = scfg.resOperatorMantWidth,
      vectorSize      = vectorSize,
      productExpRange = scfg.productExpRange,
      arch            = treeArch
    ))
    tree.io.inputs := laneOp
    // ── pipeAfterTree: register tree output before scale apply ───────────────
    val treeOut = maybePipe(pipeAfterTree, tree.io.out)

    io.debug.foreach { d =>
      d.tree_out_sign := treeOut.sign
      d.tree_out_exp  := treeOut.exp
      d.tree_out_mant := treeOut.mant
    }

    // ── ScaleAdd + FPn conversion: path selected at elaboration time ─────────
    // All intermediate values use fpNW = 1+8+actualAccMantBits bits throughout.
    // UE8M0: DirectToFPn — pure exponent arithmetic, no multipliers.
    // Non-UE8M0: ScaleAddition + ScaleToFPn — LZC normalise + RNE round.
    val reducedSum = Wire(UInt(fpNW.W))

    if (scfg.stype.mantScaleWidth == 0) {
      // ── Path A: UE8M0 — DirectToFPn ──────────────────────────────────────
      val d2fpn = Module(new DirectToFPn(scfg, actualAccMantBits))
      d2fpn.io.inOpSign      := treeOut.sign
      d2fpn.io.inOpExp       := treeOut.exp
      d2fpn.io.inOpMant      := treeOut.mant
      d2fpn.io.inShareScaleA := shareExpA
      d2fpn.io.inShareScaleB := shareExpB
      reducedSum             := d2fpn.io.out

      io.debug.foreach { d =>
        val adjA = shareExpA.zext - scfg.stype.bias.S
        val adjB = shareExpB.zext - scfg.stype.bias.S
        val expDebug = Wire(SInt(scfg.resScaleAddExpWidth.W))
        expDebug := treeOut.exp + adjA + adjB
        d.sa_out_sign := treeOut.sign
        d.sa_out_exp  := expDebug
        d.sa_out_mant := Cat(0.U(2.W), treeOut.mant)
      }

    } else {
      // ── Path B: non-UE8M0 — ScaleAddition + ScaleToFPn ───────────────────
      val sa = Module(new ScaleAddition(scfg))
      sa.io.inOpSign      := treeOut.sign
      sa.io.inOpExp       := treeOut.exp
      sa.io.inOpMant      := treeOut.mant
      sa.io.inShareScaleA := shareExpA
      sa.io.inShareScaleB := shareExpB

      io.debug.foreach { d =>
        d.sa_out_sign := sa.io.outSign
        d.sa_out_exp  := sa.io.outExp
        d.sa_out_mant := sa.io.outMant
      }

      val conv = Module(new ScaleToFPn(scfg, actualAccMantBits))
      conv.io.inSign := sa.io.outSign
      conv.io.inExp  := sa.io.outExp
      conv.io.inMant := sa.io.outMant
      reducedSum     := conv.io.out
    }

    // Debug reducedSum: zero-extend to 32 bits for log continuity
    io.debug.foreach { d =>
      d.reducedSum := (if (actualAccMantBits < 23) Cat(reducedSum, 0.U((23 - actualAccMantBits).W))
                       else reducedSum)
    }

    // ── Reduced-precision FP accumulator register ────────────────────────────
    // Register stores fpNW = (1 + 8 + actualAccMantBits) bits.
    // FPNAdder natively operates at fpNW precision — no post-add truncation needed.
    // accOut is FP32: accReg's mantissa is zero-extended to 23 bits (no rounding).
    val accRegW   = fpNW
    val asyncRstN = (!reset.asBool).asAsyncReset
    val accReg    = withReset(asyncRstN)(RegInit(0.U(accRegW.W)))
    val validReg  = withReset(asyncRstN)(RegInit(false.B))

    val accAdder = Module(new FPNAdder(actualAccMantBits))
    accAdder.io.a := accReg
    accAdder.io.b := reducedSum

    // ── Control signals delayed to align with piped data path ────────────────
    val validInAligned  = alignedCtrl(io.validIn)
    val resetAccAligned = alignedCtrl(io.resetAcc)

    when(resetAccAligned) {
      accReg   := 0.U
      validReg := false.B
    }.elsewhen(validInAligned) {
      accReg   := accAdder.io.out
      validReg := true.B
    }.otherwise {
      validReg := false.B
    }

    io.validOut := validReg
    // Narrow output: accReg holds {sign[1], exp[8], mant[actualAccMantBits]}
    // exactly — no zero-pad. Downstream must zero-extend if FP32 is required.
    io.accOut := accReg
  }

  // ── buildBlockDeferred: cyclesPerBlock >= 2 (all scale types) ────────────
  // Two-level FP accumulator that defers scale-apply to the block boundary.
  //   per cycle (intra-block):
  //     lane products → tree → "convert CustomFP → FP without scale"
  //       → cycleFPnoscale (FP, no scale applied)
  //     innerAccReg ← FPNAdder(innerAccReg, cycleFPnoscale)
  //   on the cyclesPerBlock-th cycle (block boundary):
  //     blockPartialFP = innerNext × scaleA × scaleB (one bridge per block)
  //     outerAccReg    ← FPNAdder(outerAccReg, blockPartialFP)
  //     innerAccReg    ← 0  (start fresh for next block)
  //
  // Correctness precondition: share_exp_A_i / share_exp_B_i must be held
  // constant across the cyclesPerBlock cycles of one block.  The upstream
  // (PEArray wrapper feeding MX block data) enforces this naturally.
  //
  //   UE8M0      → per-cycle uses DirectToFPn(scale=bias) (pure exp arith).
  //                Block bridge inline: just biased-exp adjust.
  //   non-UE8M0  → per-cycle uses ScaleAddition+ScaleToFPn(scale=1.0_rep);
  //                the constant unityScale lets synthesis fold the mant
  //                multiplier into a hardwired shift.  Block bridge uses
  //                FPxScale for the real scaleA × scaleB mant multiply.
  //
  // For non-UE8M0, savings = (cyclesPerBlock − 1) eliminated mant multipliers
  // per output element.  UE8M0 only saves the per-cycle 8-bit exp arithmetic.
  private def buildBlockDeferred(): Unit = {
    require(cyclesPerBlock >= 2)

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

    // ── FixedFP reduction tree ─────────────────────────────────────────────
    val tree = Module(new FixedFPReductionTree(
      expW            = scfg.resOperatorExpWidth,
      inMantW         = scfg.resOperatorMantWidth,
      outMantW        = scfg.resOperatorMantWidth,
      vectorSize      = vectorSize,
      productExpRange = scfg.productExpRange,
      arch            = treeArch
    ))
    tree.io.inputs := laneOp
    // ── pipeAfterTree: register tree output before no-scale converter ───────
    val treeOut = maybePipe(pipeAfterTree, tree.io.out)

    io.debug.foreach { d =>
      d.tree_out_sign := treeOut.sign
      d.tree_out_exp  := treeOut.exp
      d.tree_out_mant := treeOut.mant
    }

    // ── Tree CustomFP → FP (NO scale) ────────────────────────────────────
    // Synthesis is expected to fold the constant scale inputs (bias / 1.0
    // representation) through the module boundary into a hardwired shift,
    // eliminating the scale-mant multiplier from the per-cycle datapath.
    val cycleFPnoscale = Wire(UInt(fpNW.W))
    if (scfg.stype.mantScaleWidth == 0) {
      // UE8M0: scaleA = scaleB = bias  ⇒  scale value = 1.0
      val biasU = scfg.stype.bias.U(scfg.stype.totalScaleWidth.W)
      val d2fpnInner = Module(new DirectToFPn(scfg, actualAccMantBits))
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
      // non-UE8M0: 1.0_rep = (exp = bias) || (mant = 0)
      // bit pattern = bias << mantScaleW
      val unityScale =
        (scfg.stype.bias << scfg.stype.mantScaleWidth).U(scfg.stype.totalScaleWidth.W)
      val sa = Module(new ScaleAddition(scfg))
      sa.io.inOpSign      := treeOut.sign
      sa.io.inOpExp       := treeOut.exp
      sa.io.inOpMant      := treeOut.mant
      sa.io.inShareScaleA := unityScale
      sa.io.inShareScaleB := unityScale

      io.debug.foreach { d =>
        d.sa_out_sign := sa.io.outSign
        d.sa_out_exp  := sa.io.outExp
        d.sa_out_mant := sa.io.outMant
      }

      val s2fpn = Module(new ScaleToFPn(scfg, actualAccMantBits))
      s2fpn.io.inSign := sa.io.outSign
      s2fpn.io.inExp  := sa.io.outExp
      s2fpn.io.inMant := sa.io.outMant
      cycleFPnoscale := s2fpn.io.out
    }

    // ── Inner FP accumulator (no scale applied yet) ────────────────────────
    val asyncRstN   = (!reset.asBool).asAsyncReset
    val innerAccReg = withReset(asyncRstN)(RegInit(0.U(fpNW.W)))

    val innerAdder = Module(new FPNAdder(actualAccMantBits))
    innerAdder.io.a := innerAccReg
    innerAdder.io.b := cycleFPnoscale
    val innerNext = innerAdder.io.out

    // ── Control signals delayed to align with piped data path ────────────────
    // Precondition for pipeAfterTree=true: share_exp_{A,B}_i must be held
    // stable for at least one extra cycle around the block boundary so the
    // bridge samples the correct block's scale.  MX block semantics already
    // enforce share_exp constancy across cyclesPerBlock cycles.
    val validInAligned  = alignedCtrl(io.validIn)
    val resetAccAligned = alignedCtrl(io.resetAcc)

    // ── Block-cycle counter ────────────────────────────────────────────────
    val blockCnt = withReset(asyncRstN)(RegInit(0.U(log2Ceil(cyclesPerBlock).W)))
    val isLastCycleOfBlock = blockCnt === (cyclesPerBlock - 1).U
    val blockDone = validInAligned && isLastCycleOfBlock

    // ── share_exp aligned to block-end bridge consumption ──────────────────
    val shareExpA = if (feedForwardDepth == 0) io.share_exp_A_i else ShiftRegister(io.share_exp_A_i, feedForwardDepth)
    val shareExpB = if (feedForwardDepth == 0) io.share_exp_B_i else ShiftRegister(io.share_exp_B_i, feedForwardDepth)

    // ── Block-boundary bridge: apply scale to innerNext → blockPartialFP ───
    val blockPartialFP = Wire(UInt(fpNW.W))
    if (scfg.stype.mantScaleWidth == 0) {
      // UE8M0: scale apply is just biased-exp addition by (scaleA−bias)+(scaleB−bias).
      val innerSign = innerNext(fpNW - 1)
      val innerExpU = innerNext(fpNW - 2, fpNW - 1 - 8)
      val innerMant = innerNext(actualAccMantBits - 1, 0)

      val adjScaleA = shareExpA.zext - scfg.stype.bias.S
      val adjScaleB = shareExpB.zext - scfg.stype.bias.S
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
      // pipeInScale opts in to an internal register after the mant multiplier,
      // turning the module into a 2-cycle pipeline.  blockPartialFP then trails
      // the corresponding blockDone by one cycle, and the outer accumulator
      // update is gated by `blockDoneOuter` below.
      // FPxScale's internal pipeline registers (when pipeInScale=true) must
      // use the same inverted reset (asyncRstN) as the rest of FDPU so they
      // operate while the codebase's "reset high = normal operation"
      // convention is in effect.
      //
      // Operand isolation: hold FPxScale inputs stable on idle cycles —
      // its output is consumed only at the block boundary, so toggling
      // its mantissa multiplier / LZC / normalize stages on the 7 idle
      // cycles wastes ~7/8 of the datapath's switching power.
      val innerHeld  = withReset(asyncRstN)(RegEnable(innerNext,
                                                      isLastCycleOfBlock))
      val scaleAHeld = withReset(asyncRstN)(RegEnable(shareExpA,
                                                      isLastCycleOfBlock))
      val scaleBHeld = withReset(asyncRstN)(RegEnable(shareExpB,
                                                      isLastCycleOfBlock))
      val fpScale = withReset(asyncRstN) {
        Module(new FPxScale(scfg, actualAccMantBits, pipeInternal = effectiveScalePipe))
      }
      fpScale.io.fpIn   := Mux(isLastCycleOfBlock, innerNext, innerHeld)
      fpScale.io.scaleA := Mux(isLastCycleOfBlock, shareExpA, scaleAHeld)
      fpScale.io.scaleB := Mux(isLastCycleOfBlock, shareExpB, scaleBHeld)
      blockPartialFP := fpScale.io.fpOut
    }

    // ── Outer FP accumulator (block partials) ─────────────────────────────
    val outerAccReg = withReset(asyncRstN)(RegInit(0.U(fpNW.W)))

    // Operand isolation on outerAdder.b: blockPartialFP is consumed only
    // on the last cycle of each block; for UE8M0 it is recomputed
    // combinationally from innerNext every cycle and would otherwise toggle
    // outerAdder needlessly on the 7 idle cycles per block.  Use the
    // captured value on idle cycles so outerAdder's mantissa adder /
    // normalize stages do not switch.
    val blockPartialHeld = withReset(asyncRstN)(RegEnable(blockPartialFP,
                                                          isLastCycleOfBlock))
    val outerAdder = Module(new FPNAdder(actualAccMantBits))
    outerAdder.io.a := outerAccReg
    outerAdder.io.b := Mux(isLastCycleOfBlock, blockPartialFP, blockPartialHeld)

    val validReg = withReset(asyncRstN)(RegInit(false.B))

    // When the scale pipe is engaged, the FPxScale output appears one cycle
    // after blockDone, so the outer-accumulator update has to wait that cycle.
    // innerAcc reset, blockCnt reset and per-cycle accumulation are unaffected.
    val blockDoneOuter =
      if (effectiveScalePipe) withReset(asyncRstN)(RegNext(blockDone, init = false.B))
      else blockDone

    // ── State updates ─────────────────────────────────────────────────────
    when(resetAccAligned) {
      innerAccReg := 0.U
      outerAccReg := 0.U
      blockCnt    := 0.U
      validReg    := false.B
    }.otherwise {
      when(blockDoneOuter) {
        outerAccReg := outerAdder.io.out     // commit scaled block partial
      }
      when(validInAligned) {
        when(blockDone) {
          innerAccReg := 0.U                 // reset inner for next block
          blockCnt    := 0.U
        }.otherwise {
          innerAccReg := innerAdder.io.out   // intra-block FP accumulate
          blockCnt    := blockCnt + 1.U
        }
        validReg := true.B                   // mirror buildSingleAcc semantics
      }.otherwise {
        validReg := false.B
      }
    }

    io.validOut := validReg
    io.accOut := outerAccReg

    io.debug.foreach { d =>
      d.reducedSum := (
        if (actualAccMantBits < 23) Cat(cycleFPnoscale, 0.U((23 - actualAccMantBits).W))
        else cycleFPnoscale
      )
    }
  }

  // ── buildKulischDeferred — Arch-IV.b: wide fixed-point Kulisch inner + FP outer ──
  //
  // Per cycle (intra-block):
  //   lane products (sign, exp, mant) from CustomOperator
  //     → align to GLOBAL anchor (compile-time minProductExp) via LEFT-shift
  //     → sign-magnitude → 2's complement
  //     → signed integer adder tree (V → 1)
  //     → innerAccReg += laneSum   (wide fixed-point register)
  //   NO LZC, NO normalize, NO FP add inside the block.
  //
  // On the cyclesPerBlock-th cycle (block boundary):
  //   innerAccReg → LZC + normalize + RNE → mant + biased_exp
  //                                          + scaleA × scaleB (UE8M0: exp arith)
  //                → blockPartialFP
  //   outerAccReg ← FPNAdder(outerAccReg, blockPartialFP)
  //   innerAccReg ← 0
  //
  // Anchor and width math:
  //   b      = scfg.minProductExp                 (compile-time)
  //   R      = scfg.productExpRange = maxE − b    (compile-time)
  //   m      = scfg.resOperatorMantWidth          (product mantissa bits)
  //   log2CV = log2(cyclesPerBlock × V)           (accumulation growth)
  //   magW   = R + m + log2CV                     (inner magnitude bits)
  //   accInnerW = 1 + magW                        (sign + magnitude)
  //
  // Block-end biased exp formula (UE8M0):
  //   biased_exp = magW − 1 − lzc + b + 127 + (scaleA − bias) + (scaleB − bias)
  //                                          + (mCarry ? 1 : 0)
  //
  // Precondition: share_exp_A_i / share_exp_B_i constant across the block
  // (MX block semantics — wrapper enforces this).
  // Supports UE8M0 (pure biased-exp scale apply) and non-UE8M0 (mant-multiply
  // via FPxScale at block boundary).  Inner accumulator is identical in both
  // paths — scale type only affects the block-end bridge.
  private def buildKulischDeferred(): Unit = {
    require(cyclesPerBlock >= 2)

    // Compile-time anchor + widths.
    //
    // CustomOperator emits (sign, exp, mant) where the true product value is:
    //   product = (-1)^sign × mant × 2^(exp − fracBitsElem)
    // with mant being the integer product of (1.fracA)·(1.fracB) × 2^(mantA+mantB),
    // and fracBitsElem = mantA + mantB (for FP) or 0 (for INT).
    //
    // Two separate anchors:
    //   bShift = minProductExp                        — shift amount uses this
    //   bValue = minProductExp − fracBitsElem         — value anchor for biased
    //                                                   exp; bit 0 = 2^bValue
    //
    // The shift `shiftAmt = exp − minProductExp` places mant's bit 0 at
    // innerAcc bit `shiftAmt`, whose VALUE is 2^(shiftAmt + bValue) =
    // 2^(exp − fracBitsElem), matching the product's exp convention.
    val bShift    = scfg.minProductExp
    val fracBitsElem =
      (if (scfg.elementTypeA.elementWidthExp == 0) 0 else scfg.elementTypeA.elementWidthMant) +
      (if (scfg.elementTypeB.elementWidthExp == 0) 0 else scfg.elementTypeB.elementWidthMant)
    val bValue    = bShift - fracBitsElem
    val R         = scfg.productExpRange
    val mProd     = scfg.resOperatorMantWidth
    val log2CV    = log2Ceil(cyclesPerBlock * vectorSize)
    val magW      = R + mProd + log2CV
    val accInnerW = 1 + magW
    val shiftAmtW = log2Ceil(R + 1)
    val M         = actualAccMantBits

    // ── Per-lane MAC ─────────────────────────────────────────────────────
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

    // ── Per-lane align to global anchor + sign-mag → 2's complement ────────
    val laneAligned = Wire(Vec(vectorSize, SInt(accInnerW.W)))
    for (i <- 0 until vectorSize) {
      val expDiff = laneOp(i).exp - bShift.S
      // expDiff is always ≥ 0 in valid inputs. Defensive clamp to [0, R].
      val shiftAmt = Mux(expDiff < 0.S,                       0.U(shiftAmtW.W),
                       Mux(expDiff.asUInt > R.U,              R.U(shiftAmtW.W),
                                                               expDiff.asUInt(shiftAmtW - 1, 0)))
      // mant occupies low mProd bits; top (R + log2CV) bits zero-padded.
      val extended = Cat(0.U((R + log2CV).W), laneOp(i).mant)
      val shifted  = (extended << shiftAmt)(magW - 1, 0)
      val posSigned = Cat(0.U(1.W), shifted).asSInt
      laneAligned(i) := Mux(laneOp(i).sign.asBool, -posSigned, posSigned)
    }

    // ── pipeAfterTree: register aligned lane outputs before adder tree ─────
    // For Kulisch this position breaks the chain between the (per-lane parallel)
    // alignment shifts and the (sequential) signed adder tree — analogous to
    // BD-FP's post-FP-tree register in that it splits the cross-lane reduction
    // from the per-lane preprocessing.
    val laneAlignedP = maybePipe(pipeAfterTree, laneAligned)

    // ── V-lane signed integer adder tree (width-preserving) ────────────────
    def signedAddTree(vals: Seq[SInt]): SInt =
      if (vals.length == 1) vals.head
      else signedAddTree(vals.grouped(2).map(g => if (g.length == 2) g(0) + g(1) else g(0)).toSeq)
    val laneSum = signedAddTree(laneAlignedP.toSeq)

    // ── Wide fixed-point Kulisch accumulator ──────────────────────────────
    val asyncRstN   = (!reset.asBool).asAsyncReset
    val innerAccReg = withReset(asyncRstN)(RegInit(0.S(accInnerW.W)))
    val innerNext   = innerAccReg + laneSum   // width-preserving SInt add

    // ── Block-cycle counter ───────────────────────────────────────────────
    val blockCnt = withReset(asyncRstN)(RegInit(0.U(log2Ceil(cyclesPerBlock).W)))
    val isLastCycleOfBlock = blockCnt === (cyclesPerBlock - 1).U
    // Pipeline-aligned ctrl: validInAligned / resetAccAligned added below.
    // (We can't define them earlier because pipelineStages affects shift depth
    // computed from FDPU-level params, but the helper alignedCtrl handles 0.)
    val validInAlignedK  = alignedCtrl(io.validIn)
    val resetAccAlignedK = alignedCtrl(io.resetAcc)
    val blockDone = validInAlignedK && isLastCycleOfBlock

    // ── Block-end bridge: Kulisch → FP (UE8M0 exp arith) ───────────────────
    val kSign       = innerNext(accInnerW - 1)
    val kMagFull    = Mux(kSign.asBool, (-innerNext).asUInt(magW - 1, 0),
                                         innerNext.asUInt(magW - 1, 0))
    val isMagZero   = kMagFull === 0.U

    val lzc        = PriorityEncoder(Reverse(kMagFull))
    val normalized = (kMagFull << lzc)(magW - 1, 0)

    // Implicit 1 at bit (magW-1); explicit mantissa in next M bits below.
    val mantRaw    = normalized(magW - 2, magW - 1 - M)
    val gPos       = magW - 2 - M
    val rPos       = magW - 3 - M
    val sTop       = magW - 4 - M
    val guardBit   = if (gPos >= 0) normalized(gPos).asBool else false.B
    val roundBit   = if (rPos >= 0) normalized(rPos).asBool else false.B
    val stkyBits   = if (sTop >= 0) normalized(sTop, 0).orR  else false.B
    val roundUp    = guardBit && (mantRaw(0).asBool || roundBit || stkyBits)
    val roundedM   = mantRaw +& roundUp.asUInt
    val mCarry     = roundedM(M).asBool
    val finalMantExp = Mux(mCarry, 0.U(M.W), roundedM(M - 1, 0))

    // share_exp aligned to block-end bridge consumption.
    val shareExpA = if (feedForwardDepth == 0) io.share_exp_A_i else ShiftRegister(io.share_exp_A_i, feedForwardDepth)
    val shareExpB = if (feedForwardDepth == 0) io.share_exp_B_i else ShiftRegister(io.share_exp_B_i, feedForwardDepth)

    val blockPartialFP = Wire(UInt(fpNW.W))

    if (scfg.stype.mantScaleWidth == 0) {
      // ── UE8M0 fast path: scale apply = pure biased-exp arithmetic ─────────
      // biased_exp = (magW − 1 − lzc) + b + 127 + scaleExpSum + (mCarry ? 1 : 0)
      val adjScaleA = shareExpA.zext - scfg.stype.bias.S
      val adjScaleB = shareExpB.zext - scfg.stype.bias.S
      val scaleAdj  = adjScaleA +& adjScaleB
      val constOff  = magW - 1 + bValue + 127
      val lzcS      = Cat(false.B, lzc).asSInt
      val newExpBase = constOff.S - lzcS +& scaleAdj
      val newExpRaw  = Mux(mCarry, newExpBase + 1.S, newExpBase)

      val isOverflow  = newExpRaw >= 255.S
      val isUnderflow = newExpRaw <= 0.S

      val finalBiasedExp = Mux(isOverflow,                       255.U(8.W),
                           Mux(isUnderflow || isMagZero,         0.U(8.W),
                                                                  newExpRaw.asUInt(7, 0)))
      blockPartialFP :=
        Mux(isMagZero || (isUnderflow && !isOverflow), 0.U(fpNW.W),
        Mux(isOverflow, Cat(kSign, 255.U(8.W), 0.U(M.W)),
                        Cat(kSign, finalBiasedExp, finalMantExp)))
    } else {
      // ── non-UE8M0 path: assemble pre-scale FP word, apply scale via FPxScale
      // Pre-scale exp = (magW − 1 − lzc) + bValue + 127 (+1 if mCarry); no scale
      // exp/mant applied yet — FPxScale does the full scaleA×scaleB application.
      val constOff   = magW - 1 + bValue + 127
      val lzcS       = Cat(false.B, lzc).asSInt
      val preExpBase = constOff.S - lzcS
      val preExpRaw  = Mux(mCarry, preExpBase + 1.S, preExpBase)

      // For Kulisch widths (constOff ≤ ~170, lzc ∈ [0, magW-1]), preExpRaw
      // is always within 8-bit unsigned range. Mag-zero still must give 0.
      val preBiasedExp = Mux(isMagZero, 0.U(8.W), preExpRaw.asUInt(7, 0))
      val preFP        = Mux(isMagZero, 0.U(fpNW.W),
                                        Cat(kSign, preBiasedExp, finalMantExp))

      // Wrap FPxScale with the inverted reset so its internal stage register
      // operates while dut.reset is held HIGH (this codebase's normal state).
      //
      // Operand isolation (same rationale as the buildBlockDeferred and
      // buildKulischDeferred bridges in FDPUPostScaleReductionTree.scala):
      // FPxScale output is consumed only at block boundaries; holding inputs
      // stable on idle cycles cuts switching power on its mantissa multiplier
      // / LZC / normalize stages by ~7/8.
      val preFPHeld  = withReset(asyncRstN)(RegEnable(preFP,
                                                      isLastCycleOfBlock))
      val scaleAHeld = withReset(asyncRstN)(RegEnable(shareExpA,
                                                      isLastCycleOfBlock))
      val scaleBHeld = withReset(asyncRstN)(RegEnable(shareExpB,
                                                      isLastCycleOfBlock))
      val fpScale = withReset(asyncRstN) {
        Module(new FPxScale(scfg, actualAccMantBits, pipeInternal = effectiveScalePipe))
      }
      fpScale.io.fpIn   := Mux(isLastCycleOfBlock, preFP,     preFPHeld)
      fpScale.io.scaleA := Mux(isLastCycleOfBlock, shareExpA, scaleAHeld)
      fpScale.io.scaleB := Mux(isLastCycleOfBlock, shareExpB, scaleBHeld)
      blockPartialFP := fpScale.io.fpOut
    }

    // ── Outer FP accumulator (block partials) ─────────────────────────────
    val outerAccReg = withReset(asyncRstN)(RegInit(0.U(fpNW.W)))

    // Operand isolation on outerAdder.b: same rationale as buildBlockDeferred.
    val blockPartialHeld = withReset(asyncRstN)(RegEnable(blockPartialFP,
                                                          isLastCycleOfBlock))
    val outerAdder  = Module(new FPNAdder(actualAccMantBits))
    outerAdder.io.a := outerAccReg
    outerAdder.io.b := Mux(isLastCycleOfBlock, blockPartialFP, blockPartialHeld)

    val validReg = withReset(asyncRstN)(RegInit(false.B))

    // Engaging pipeInScale (non-UE8M0 only) delays blockPartialFP by 1 cycle,
    // so outerAcc updates one cycle after blockDone.  Inner state advances on
    // its own (validInAligned).  Must use asyncRstN to match the rest of FDPU.
    val blockDoneOuter =
      if (effectiveScalePipe) withReset(asyncRstN)(RegNext(blockDone, init = false.B))
      else blockDone

    when(resetAccAlignedK) {
      innerAccReg := 0.S
      outerAccReg := 0.U
      blockCnt    := 0.U
      validReg    := false.B
    }.otherwise {
      when(blockDoneOuter) {
        outerAccReg := outerAdder.io.out
      }
      when(validInAlignedK) {
        when(blockDone) {
          innerAccReg := 0.S
          blockCnt    := 0.U
        }.otherwise {
          innerAccReg := innerNext
          blockCnt    := blockCnt + 1.U
        }
        validReg := true.B
      }.otherwise {
        validReg := false.B
      }
    }

    io.validOut := validReg
    io.accOut := outerAccReg

    io.debug.foreach { d =>
      d.tree_out_sign := kSign
      d.tree_out_exp  := 0.S
      d.tree_out_mant := 0.U
      d.sa_out_sign   := kSign
      d.sa_out_exp    := 0.S
      d.sa_out_mant   := 0.U
      d.reducedSum    := Cat(0.U((32 - fpNW).W), blockPartialFP)
    }
  }
}

// ============================================================
// Pipeline DSE sweep emitter — V=4 only.
//
// Pipeline knobs (replacing the old 0..3 stages):
//   pAT (pipeAfterTree) — Stage A, register at reduction-tree output
//   pIS (pipeInScale)   — Stage B, register inside FPxScale (after mant-mult)
// pIS has no effect for UE8M0 (scale apply is pure exp arithmetic).
//
// Two scale-type tracks:
//
//   UE8M0 (Kulisch-relevant):
//     modes  = {blockdef, kulisch}
//     combos = {(pAT=F,pIS=F), (pAT=T,pIS=F)}  (pIS dominated)
//     5 pairs × 2 modes × 2 combos = 20
//
//   UE4M4 (Kulisch now supports non-UE8M0):
//     modes  = {single, blockdef, kulisch}
//     combos = full 2×2 = 4
//     3 pairs × 3 modes × 4 combos = 36
//
// Total = 56 PE.sv under generated/dse_pipeline_sweep/.
// ============================================================
object DSEPipelineSweepMain extends App {
  import java.io.{File, PrintWriter}
  import scala.io.Source

  private val Root  = "generated/dse_pipeline_sweep"
  private val vsize = 4
  private val K     = 16384

  private case class Pair(label: String, typeA: ElementType, typeB: ElementType)
  private case class Mode(label: String, treeArch: TreeArch, cpb: Int)
  private case class PipeCombo(label: String, pAT: Boolean, pIS: Boolean)

  // ── Format pairs ────────────────────────────────────────────────────────
  private val ue8m0Pairs = Seq(
    Pair("INT8xINT8",   MXFormats.INT8, MXFormats.INT8),  // R=0
    Pair("E3M2xE3M2",   MXFormats.E3M2, MXFormats.E3M2),  // R=12
    Pair("E4M3xE4M3",   MXFormats.E4M3, MXFormats.E4M3),  // R=28
    Pair("E5M2xE4M3",   MXFormats.E5M2, MXFormats.E4M3),  // R=43
    Pair("E5M2xE5M2",   MXFormats.E5M2, MXFormats.E5M2),  // R=58
  )
  private val ue4m4Pairs = Seq(
    Pair("E3M2xE3M2", MXFormats.E3M2, MXFormats.E3M2),
    Pair("E4M3xE4M3", MXFormats.E4M3, MXFormats.E4M3),
    Pair("E5M2xE5M2", MXFormats.E5M2, MXFormats.E5M2),
  )

  private val ue8m0Modes = Seq(
    Mode("blockdef", TreeArch.Generic,      8),
    Mode("kulisch",  TreeArch.KulischInner, 8),
  )
  private val ue4m4Modes = Seq(
    Mode("single",   TreeArch.Generic,      1),
    Mode("blockdef", TreeArch.Generic,      8),
    Mode("kulisch",  TreeArch.KulischInner, 8),
  )

  // Pipeline-combo enumerations.  pIS is dominated when the scale type has no
  // mantissa (UE8M0), so we drop the (pIS=T) half there.
  private val ue8m0Combos = Seq(
    PipeCombo("p00",     pAT = false, pIS = false),
    PipeCombo("pAT",     pAT = true,  pIS = false),
  )
  private val ue4m4Combos = Seq(
    PipeCombo("p00",     pAT = false, pIS = false),
    PipeCombo("pAT",     pAT = true,  pIS = false),
    PipeCombo("pIS",     pAT = false, pIS = true),
    PipeCombo("pAT_pIS", pAT = true,  pIS = true),
  )

  // Consolidate emitted .sv files into a single PE.sv.
  private def consolidatePE(dir: String): Unit = {
    val dirFile = new File(dir)
    val svFiles = Option(dirFile.listFiles())
      .map(_.filter(_.getName.endsWith(".sv")))
      .getOrElse(Array.empty[File])
      .sortBy(_.getName)
    if (svFiles.nonEmpty) {
      val combined = svFiles.map { f =>
        s"// ─── ${f.getName} ───\n${Source.fromFile(f).mkString}"
      }.mkString("\n")
      val out = new PrintWriter(new File(s"$dir/PE.sv"))
      try out.write(combined) finally out.close()
      svFiles.foreach(_.delete())
    }
  }

  private def emitOne(scaleTag: String, p: Pair, mode: Mode, combo: PipeCombo,
                       scfg: ScaleAddConfig): Unit = {
    val label = s"${mode.label}_${p.label}_${scaleTag}_V${vsize}_cpb${mode.cpb}_${combo.label}"
    val dir   = s"$Root/$label"
    new File(dir).mkdirs()
    Option(new File(dir).listFiles()).foreach(_.foreach(_.delete()))
    emitVerilog(
      new FDPUPostScaleReductionTreePiped(
        scfg            = scfg,
        vectorSize      = vsize,
        K               = K,
        treeArch        = mode.treeArch,
        cyclesPerBlock  = mode.cpb,
        pipeAfterTree   = combo.pAT,
        pipeInScale     = combo.pIS,
        istest          = false),
      Array("--target-dir", dir)
    )
    consolidatePE(dir)
    println(f"[PIPE-SWEEP] $label%-58s  R=${scfg.productExpRange}%-3d")
  }

  // ── UE8M0 sweep ─────────────────────────────────────────────────────────
  val ue8m0Count = ue8m0Pairs.size * ue8m0Modes.size * ue8m0Combos.size
  println(s"\n=== UE8M0 track: $ue8m0Count variants ===")
  for {
    p     <- ue8m0Pairs
    mode  <- ue8m0Modes
    combo <- ue8m0Combos
  } emitOne("UE8M0", p, mode, combo,
            ScaleAddConfig(p.typeA, p.typeB, ScaleFormats.UE8M0))

  // ── UE4M4 sweep ─────────────────────────────────────────────────────────
  val ue4m4Count = ue4m4Pairs.size * ue4m4Modes.size * ue4m4Combos.size
  println(s"\n=== UE4M4 track: $ue4m4Count variants ===")
  for {
    p     <- ue4m4Pairs
    mode  <- ue4m4Modes
    combo <- ue4m4Combos
  } emitOne("UE4M4", p, mode, combo,
            ScaleAddConfig(p.typeA, p.typeB, ScaleFormats.UE4M4))

  println(s"\n[PIPE-SWEEP] Done. ${ue8m0Count + ue4m4Count} variants under $Root/")
}
