package mx.mac

import chisel3._
import chisel3.util.{Cat, log2Ceil, PriorityEncoder, RegEnable, Reverse}

// ============================================================
// Fused Dot-Product Unit — Post-Scale Reduction Tree
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
class FDPUPostScaleReductionTree(
  val scfg: ScaleAddConfig,
  val vectorSize: Int,
  val K: Int = 32,
  val accMantBits: Int = -1,
  val treeArch: TreeArch = TreeArch.Generic,
  val cyclesPerBlock: Int = 1,
  val istest: Boolean = false,
  /** If true, disable the early RNE stages (tree exit + scale composition):
   *  tree emits its full absMagW mantissa unrounded, scale multiplier operates
   *  on this wide mantissa, and only the FPNAdder-internal RNE remains.
   *  This is the "Cuyckens-extended-to-ExMy" counterfactual baseline used
   *  in the chapter to quantify the area saved by our 3-RNE design. */
  val noEarlyRNE: Boolean = false,
  /** When using UE8M0 scale, widen the tree-exit mantissa to M_acc bits
   *  (instead of the legacy resOperatorMantWidth = lane-product width).
   *  This makes the per-cycle tree-exit RNE noise M_acc-dependent on
   *  UE8M0 configs, eliminating the fixed-width truncation that
   *  otherwise creates a noise-floor saturation on narrow-mantissa
   *  symmetric pairs (E5M2² / E2M1²).  Empirically verified to drop
   *  the accumulator-relative error by 9–63× at M_acc ∈ [13, 15]
   *  with no change in any other block's logic.  Default = true
   *  since the change is strict area-iso, accuracy-gain. */
  val widenUE8M0: Boolean = true
) extends Module {
  require(cyclesPerBlock >= 1, s"cyclesPerBlock must be >= 1, got $cyclesPerBlock")
  require(vectorSize >= 1, "vectorSize must be >= 1")
  require(K >= 1, "K must be >= 1")

  // ── K-derived M_acc (output-floor aware via AccPrecision.recommended) ──
  // The legacy structural cap M_acc ≤ resScaleAddMantWidth − 3 is now
  // eliminated by widening the tree's output mantissa: instead of capping
  // M_acc downward, we widen the tree's outMantW upward by exactly enough
  // bits so the downstream ScaleAddition + ScaleToFPn RNE window keeps
  // (M_acc + 3) bits below it.  See `treeExtraMantBits` below.
  val actualAccMantBits: Int = {
    if (noEarlyRNE) {
      // Counterfactual variant: M_acc forced to 23 (FP32 mantissa) because
      // a single RNE at the FPNAdder output cannot meaningfully commit to a
      // narrower accumulator without the early-RNE truncation stages.
      23
    } else if (accMantBits == -1) AccPrecision.recommended(scfg, K)
    else { require(accMantBits >= 1 && accMantBits <= 23); accMantBits }
  }

  // Tree output mantissa widening (extra bits beyond resOperatorMantWidth 6):
  //   UE8M0 path uses DirectToFPn (no scale-mant multiply, no RNE at the
  //   scale step) → no widening needed.
  //   Non-UE8M0 path uses ScaleAddition + ScaleToFPn with an RNE that
  //   needs G=3 guard/round/sticky bits below the M_acc window.  We
  //   widen the tree's outMantW so resScaleAddMantWidth_effective grows
  //   from the legacy `resOperatorMantWidth 6 + resScaleMantWidth` to at
  //   least `actualAccMantBits + 3`, eliminating the cap.
  //
  //   noEarlyRNE counterfactual: widen all the way to absMagW so the tree
  //   emits its unrounded internal precision, feeding the full mantissa
  //   into the scale-composition multiplier (no RNE compresses the
  //   tree-side operand).
  private val SAFETY_G = 3
  private val _log2N    = chisel3.util.log2Ceil(vectorSize.max(2))
  private val _absMagW  = scfg.resOperatorMantWidth + (scfg.productExpRange + SAFETY_G) + _log2N
  val treeExtraMantBits: Int =
    if (noEarlyRNE)                                _absMagW - scfg.resOperatorMantWidth
    else if (scfg.stype.mantScaleWidth == 0 && widenUE8M0)
      // Widen UE8M0 tree exit to M_acc bits so the per-cycle tree-exit RNE
      // becomes M_acc-dependent (mirrors the ExMy path's behaviour).
      math.max(0, actualAccMantBits - scfg.resOperatorMantWidth)
    else if (scfg.stype.mantScaleWidth == 0) 0
    else math.max(0, actualAccMantBits + SAFETY_G - scfg.resScaleAddMantWidth)

  /** Effective tree output mantissa width fed to ScaleAddition / DirectToFPn. */
  val effectiveTreeOutMantW: Int = scfg.resOperatorMantWidth + treeExtraMantBits
  /** Effective post-scale mantissa width fed into ScaleToFPn's LZC + RNE. */
  val effectiveScaleAddMantW: Int = effectiveTreeOutMantW + scfg.resScaleMantWidth

  /** Effective tree output exponent width.  When outMantW > inMantW, the
   *  tree's LZC normalisation can drive its outExp below the legacy
   *  resOperatorExpWidth's representable range: with the wider mantissa
   *  window, expBase = maxExp + (inMantW + log2N − outMantW) − lzc, where
   *  the (inMantW − outMantW) term contributes an extra negative offset
   *  that the original expW cannot hold without wrap-around.  We widen
   *  the tree's expW per config so the natural expBase range fits. */
  private def sIntBitsForNeg(v: Int): Int =
    if (v >= 0) 1 else BigInt(-v).bitLength + 2
  val effectiveExpW: Int = {
    val log2N    = chisel3.util.log2Ceil(vectorSize.max(2))
    // Use the conservative Generic-arch fracBits (productExpRange + 3); this
    // bound is correct or pessimistic for every other tree arch we dispatch
    // to from this module.
    val fracBits = scfg.productExpRange + 3
    val absMagW  = scfg.resOperatorMantWidth + fracBits + log2N
    val minExp   = scfg.maxProductExp - (absMagW - 1) - treeExtraMantBits
    math.max(scfg.resOperatorExpWidth, sIntBitsForNeg(minExp))
  }

  // Internal FP word width used throughout the post-tree pipeline
  private val fpNW = 1 + 8 + actualAccMantBits

  private val wA = scfg.elementTypeA.totalWidth
  private val wB = scfg.elementTypeB.totalWidth

  override def desiredName =
    if (!istest)
      s"BFP_PE"
    else
      s"FDPUPostScale_${scfg.elementTypeA.name}_x_${scfg.elementTypeB.name}" +
      s"_scale_${scfg.stype.name}_vec${vectorSize}_K${K}_acc${actualAccMantBits}b"

  val io = IO(new Bundle {
    val op_a_i        = Input(UInt((vectorSize * wA).W))
    val op_b_i        = Input(UInt((vectorSize * wB).W))
    val share_exp_A_i = Input(UInt(scfg.stype.totalScaleWidth.W))
    val share_exp_B_i = Input(UInt(scfg.stype.totalScaleWidth.W))
    val validIn  = Input(Bool())
    val resetAcc = Input(Bool())
    val validOut = Output(Bool())
    // Narrow FP output: {sign[1], exp[8], mant[actualAccMantBits]}.
    // Downstream consumers that need IEEE-754 FP32 must zero-extend the
    // mantissa to 23 bits (right-pad). Exposing the native register width
    // saves (23 − M) zero-padding bits per element through the buffer fabric.
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
      // FP32 result after ScaleToFP32 (or DirectToFPn for UE8M0)
      val reducedSum        = Output(UInt(32.W))
      // ── Accumulator state per cycle (block-deferred / Kulisch paths) ──────
      // For buildSingleAcc, both reflect the single accReg; innerAcc==outerAcc.
      // Widths sized for the widest possible register: 1+8+actualAccMantBits
      // for FP accumulators; pad up to 96 bits to also fit Kulisch's wide
      // fixed-point inner (accInnerW ≤ ~75 bit) — actual bit width per arch
      // is documented in the comment where the signal is connected.
      val innerAccReg       = Output(UInt(96.W))
      val outerAccReg       = Output(UInt(96.W))
      val blockDoneFlag     = Output(Bool())
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
  // Kulisch path now supports both UE8M0 (pure exp scale apply) and non-UE8M0
  // (FPxScale at block boundary) — see buildKulischDeferred() below.
  val useKulischDeferred =
    cyclesPerBlock >= 2 && (treeArch == TreeArch.KulischInner)
  val useBlockDeferred = cyclesPerBlock >= 2 && !useKulischDeferred

  if      (useKulischDeferred) buildKulischDeferred()
  else if (useBlockDeferred)   buildBlockDeferred()
  else                         buildSingleAcc()

  // ── buildSingleAcc: legacy per-cycle scale-apply + single FP accumulator ─
  private def buildSingleAcc(): Unit = {
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
    // outMantW = effectiveTreeOutMantW (= resOperatorMantWidth + treeExtraMantBits)
    // exposes additional bits from the tree's internal absMagW when M_acc would
    // otherwise exceed the legacy resScaleAddMantWidth − 3 cap.  expW is
    // simultaneously widened to absorb the extra negative range introduced
    // by the (inMantW − outMantW) term in the post-LZC exp arithmetic.
    val tree = Module(new FixedFPReductionTree(
      expW            = effectiveExpW,
      inMantW         = scfg.resOperatorMantWidth,//6
      outMantW        = effectiveTreeOutMantW,//6 + treeExtraMantBits,
      vectorSize      = vectorSize,
      productExpRange = scfg.productExpRange,
      arch            = treeArch,
      skipFinalRound  = noEarlyRNE   // Counterfactual: skip tree-exit RNE
    ))
    // Lane inputs are still at the legacy operator widths; sign-extend the
    // per-lane exp to the wider tree-input format.
    for (i <- 0 until vectorSize) {
      tree.io.inputs(i).sign := laneOp(i).sign
      tree.io.inputs(i).mant := laneOp(i).mant
      tree.io.inputs(i).exp  := laneOp(i).exp     // SInt widening preserves sign
    }
    val treeOut = tree.io.out  // CustomFP(effectiveExpW, effectiveTreeOutMantW)

    io.debug.foreach { d =>
      d.tree_out_sign := treeOut.sign
      d.tree_out_exp  := treeOut.exp
      // Debug port has fixed resOperatorMantWidth width; zero-pad / truncate
      // as needed to surface a comparable signal.
      val mantDebug = if (effectiveTreeOutMantW >= scfg.resOperatorMantWidth)
                        treeOut.mant(effectiveTreeOutMantW - 1,
                                     effectiveTreeOutMantW - scfg.resOperatorMantWidth)
                      else treeOut.mant
      d.tree_out_mant := mantDebug
    }

    // ── ScaleAdd + FPn conversion: path selected at elaboration time ─────────
    // All intermediate values use fpNW = 1+8+actualAccMantBits bits throughout.
    // UE8M0: DirectToFPn — pure exponent arithmetic, no multipliers.
    // Non-UE8M0: ScaleAddition + ScaleToFPn — LZC normalise + RNE round.
    val reducedSum = Wire(UInt(fpNW.W))

    if (scfg.stype.mantScaleWidth == 0) {
      // ── Path A: UE8M0 — DirectToFPn ──────────────────────────────────────
      // Legacy path: no widening (treeExtraMantBits == 0) so DirectToFPn's
      // default operand width = resOperatorMantWidth.
      // widenUE8M0 path: tree-exit widened to M_acc, so we must forward the
      // wider mantissa width via opMantWOverride.
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
      // Override input mantissa/exp widths so the widened tree output
      // propagates through ScaleAddition's multiplier and ScaleToFPn's
      // LZC + RNE without bit truncation or sign wrap-around.
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
        // sa.io.outExp may be wider than the debug port; truncate (lossy
        // for diagnostic only — the real signal path is intact).
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
    // Narrow output: accReg holds {sign[1], exp[8], mant[actualAccMantBits]}
    // exactly — no zero-pad. Downstream must zero-extend if FP32 is required.
    io.accOut := accReg

    io.debug.foreach { d =>
      // Single-acc path: innerAcc == outerAcc == the one accReg.
      d.innerAccReg   := Cat(0.U((96 - fpNW).W), accReg)
      d.outerAccReg   := Cat(0.U((96 - fpNW).W), accReg)
      d.blockDoneFlag := io.validIn   // every valid cycle is "block-done"
    }
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

    // ── FixedFP reduction tree (widened outMantW + expW; see buildSingleAcc)
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

    // ── Tree CustomFP → FP (NO scale) ────────────────────────────────────
    // Synthesis is expected to fold the constant scale inputs (bias / 1.0
    // representation) through the module boundary into a hardwired shift,
    // eliminating the scale-mant multiplier from the per-cycle datapath.
    val cycleFPnoscale = Wire(UInt(fpNW.W))
    if (scfg.stype.mantScaleWidth == 0) {
      // UE8M0: scaleA = scaleB = bias  ⇒  scale value = 1.0  (no widening)
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
      // non-UE8M0: 1.0_rep = (exp = bias) || (mant = 0); widen ScaleAddition
      // mantissa AND exp inputs to propagate the wider tree output.
      val unityScale =
        (scfg.stype.bias << scfg.stype.mantScaleWidth).U(scfg.stype.totalScaleWidth.W)
      val sa = Module(new ScaleAddition(scfg,
        inOpMantWOverride = effectiveTreeOutMantW,
        inOpExpWOverride  = effectiveExpW))
      sa.io.inOpSign      := treeOut.sign
      sa.io.inOpExp       := treeOut.exp
      sa.io.inOpMant      := treeOut.mant
      sa.io.inShareScaleA := unityScale
      sa.io.inShareScaleB := unityScale

      io.debug.foreach { d =>
        d.sa_out_sign := sa.io.outSign
        d.sa_out_exp  := sa.io.outExp(scfg.resScaleAddExpWidth - 1, 0).asSInt
        val saMantDebug = if (effectiveScaleAddMantW >= scfg.resScaleAddMantWidth)
                            sa.io.outMant(effectiveScaleAddMantW - 1,
                                          effectiveScaleAddMantW - scfg.resScaleAddMantWidth)
                          else sa.io.outMant
        d.sa_out_mant := saMantDebug
      }

      val s2fpn = Module(new ScaleToFPn(scfg, actualAccMantBits,
        inMantWOverride = effectiveScaleAddMantW,
        inExpWOverride  = sa.effectiveOutExpW))
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

    // ── Block-cycle counter ────────────────────────────────────────────────
    val blockCnt = withReset(asyncRstN)(RegInit(0.U(log2Ceil(cyclesPerBlock).W)))
    val isLastCycleOfBlock = blockCnt === (cyclesPerBlock - 1).U
    val blockDone = io.validIn && isLastCycleOfBlock

    // ── Block-boundary bridge: apply scale to innerNext → blockPartialFP ───
    val blockPartialFP = Wire(UInt(fpNW.W))
    if (scfg.stype.mantScaleWidth == 0) {
      // UE8M0: scale apply is just biased-exp addition by (scaleA−bias)+(scaleB−bias).
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
      //
      // Operand isolation: FPxScale's output is consumed only on the last
      // cycle of every cyclesPerBlock window, but its mantissa multiplier,
      // LZC and normalize stages are combinational.  Letting innerNext /
      // scale inputs change every cycle therefore wastes ~7/8 of the
      // datapath's switching power on values that are never latched into
      // outerAccReg.  We hold the inputs stable on idle cycles via
      // RegEnable, and bypass directly through innerNext on the active
      // cycle so no extra pipeline stage is introduced (the
      // last-cycle critical path is unchanged apart from a 2:1 Mux).
      val innerHeld  = withReset(asyncRstN)(RegEnable(innerNext,
                                                      isLastCycleOfBlock))
      val scaleAHeld = withReset(asyncRstN)(RegEnable(io.share_exp_A_i,
                                                      isLastCycleOfBlock))
      val scaleBHeld = withReset(asyncRstN)(RegEnable(io.share_exp_B_i,
                                                      isLastCycleOfBlock))
      val fpScale = Module(new FPxScale(scfg, actualAccMantBits))
      fpScale.io.fpIn   := Mux(isLastCycleOfBlock, innerNext,
                                                   innerHeld)
      fpScale.io.scaleA := Mux(isLastCycleOfBlock, io.share_exp_A_i,
                                                   scaleAHeld)
      fpScale.io.scaleB := Mux(isLastCycleOfBlock, io.share_exp_B_i,
                                                   scaleBHeld)
      blockPartialFP := fpScale.io.fpOut
    }

    // ── Outer FP accumulator (block partials) ─────────────────────────────
    val outerAccReg = withReset(asyncRstN)(RegInit(0.U(fpNW.W)))

    // Operand isolation on outerAdder.b: blockPartialFP is consumed only on
    // the last cycle of each block; for UE8M0 it is recomputed combinationally
    // from innerNext every cycle and would otherwise toggle outerAdder needlessly
    // on the 7 idle cycles per block.  Hold the value stable on idle cycles so
    // outerAdder's mantissa adder / normalize stages do not switch.
    val blockPartialHeld = withReset(asyncRstN)(RegEnable(blockPartialFP,
                                                          isLastCycleOfBlock))
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
        innerAccReg := 0.U                  // reset inner for next block
        outerAccReg := outerAdder.io.out    // commit scaled block partial
        blockCnt    := 0.U
      }.otherwise {
        innerAccReg := innerAdder.io.out    // intra-block FP accumulate
        blockCnt    := blockCnt + 1.U
      }
      validReg := true.B                    // mirror buildSingleAcc semantics
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
      // Inner / outer accumulator state (FP-shaped, fpNW = 1+8+M_acc bits).
      // Zero-pad to 96 bits for uniform debug-port width across arch variants.
      d.innerAccReg   := Cat(0.U((96 - fpNW).W), innerAccReg)
      d.outerAccReg   := Cat(0.U((96 - fpNW).W), outerAccReg)
      d.blockDoneFlag := blockDone
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

    // ── V-lane signed integer adder tree (width-preserving) ────────────────
    def signedAddTree(vals: Seq[SInt]): SInt =
      if (vals.length == 1) vals.head
      else signedAddTree(vals.grouped(2).map(g => if (g.length == 2) g(0) + g(1) else g(0)).toSeq)
    val laneSum = signedAddTree(laneAligned.toSeq)

    // ── Wide fixed-point Kulisch accumulator ──────────────────────────────
    val asyncRstN   = (!reset.asBool).asAsyncReset
    val innerAccReg = withReset(asyncRstN)(RegInit(0.S(accInnerW.W)))
    val innerNext   = innerAccReg + laneSum   // width-preserving SInt add

    // ── Block-cycle counter ───────────────────────────────────────────────
    val blockCnt = withReset(asyncRstN)(RegInit(0.U(log2Ceil(cyclesPerBlock).W)))
    val isLastCycleOfBlock = blockCnt === (cyclesPerBlock - 1).U
    val blockDone = io.validIn && isLastCycleOfBlock

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

    val blockPartialFP = Wire(UInt(fpNW.W))

    if (scfg.stype.mantScaleWidth == 0) {
      // ── UE8M0 fast path: pure biased-exp arithmetic ───────────────────────
      // biased_exp = (magW − 1 − lzc) + b + 127 + scaleExpSum + (mCarry ? 1 : 0)
      val adjScaleA = io.share_exp_A_i.zext - scfg.stype.bias.S
      val adjScaleB = io.share_exp_B_i.zext - scfg.stype.bias.S
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

      // Operand isolation: same rationale as buildBlockDeferred — FPxScale
      // output is consumed only on the last cycle of each block window;
      // holding its inputs stable on idle cycles cuts switching power on
      // its mantissa multiplier / LZC / normalize stages by ~7/8.
      val preFPHeld  = withReset(asyncRstN)(RegEnable(preFP,
                                                      isLastCycleOfBlock))
      val scaleAHeld = withReset(asyncRstN)(RegEnable(io.share_exp_A_i,
                                                      isLastCycleOfBlock))
      val scaleBHeld = withReset(asyncRstN)(RegEnable(io.share_exp_B_i,
                                                      isLastCycleOfBlock))
      val fpScale = Module(new FPxScale(scfg, actualAccMantBits))
      fpScale.io.fpIn   := Mux(isLastCycleOfBlock, preFP,
                                                   preFPHeld)
      fpScale.io.scaleA := Mux(isLastCycleOfBlock, io.share_exp_A_i,
                                                   scaleAHeld)
      fpScale.io.scaleB := Mux(isLastCycleOfBlock, io.share_exp_B_i,
                                                   scaleBHeld)
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

    when(io.resetAcc) {
      innerAccReg := 0.S
      outerAccReg := 0.U
      blockCnt    := 0.U
      validReg    := false.B
    }.elsewhen(io.validIn) {
      when(blockDone) {
        innerAccReg := 0.S
        outerAccReg := outerAdder.io.out
        blockCnt    := 0.U
      }.otherwise {
        innerAccReg := innerNext
        blockCnt    := blockCnt + 1.U
      }
      validReg := true.B
    }.otherwise {
      validReg := false.B
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
      // Kulisch inner = wide fixed-point (accInnerW bits, sign+magnitude).
      // outerAcc = standard FP (fpNW bits).  Pad both to 96 bits.
      d.innerAccReg   := Cat(0.U((96 - accInnerW).W), innerAccReg.asUInt)
      d.outerAccReg   := Cat(0.U((96 - fpNW).W),       outerAccReg)
      d.blockDoneFlag := blockDone
    }
  }
}

// ============================================================
// Emission helpers
// ============================================================

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

// ============================================================
// DSE comparison emitter — for each arch, emits 5 representative configs
// in its valid productExpRange range, side-by-side with a Generic baseline
// of the same config.  Each emit collapses all generated .sv files into a
// single PE.sv per folder for easy synthesis input.
// ============================================================
object DSECompareEmitMain extends App {
  import java.io.{File, PrintWriter}
  import scala.io.Source

  private val Root = "generated/dse_compare"
  private val Kdefault = 16384

  // Concatenate all .sv files in `dir` into `dir/PE.sv`, then remove the
  // original split files.  Preserves per-module visibility for synthesis.
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

  private def emitOne(label: String, scfg: ScaleAddConfig, archForce: TreeArch,
                      vsize: Int, cpb: Int): Unit = {
    val dir = s"$Root/$label"
    new File(dir).mkdirs()
    // Wipe any prior files so the consolidator only sees this emit's output.
    Option(new File(dir).listFiles()).foreach(_.foreach(_.delete()))
    emitVerilog(
      new FDPUPostScaleReductionTree(
        scfg          = scfg,
        vectorSize    = vsize,
        K             = Kdefault,
        treeArch      = archForce,
        cyclesPerBlock = cpb,
        istest        = false),
      Array("--target-dir", dir)
    )
    consolidatePE(dir)
    println(s"[DSE] $label → $dir/PE.sv  (R=${scfg.productExpRange})")
  }

  // (typeA, typeB, scale, vectorSize, label)
  private case class Cfg(typeA: ElementType, typeB: ElementType, scale: ScaleType,
                         vsize: Int, label: String)

  // ── Arch-I IntOnlySigned (productExpRange == 0; only INT8×INT8 qualifies,
  // vary scale to cover 5 cases) ──
  private val archIConfigs = Seq(
    Cfg(MXFormats.INT8, MXFormats.INT8, ScaleFormats.UE8M0, 4, "INT8xINT8_UE8M0_V4"),
    Cfg(MXFormats.INT8, MXFormats.INT8, ScaleFormats.UE4M4, 4, "INT8xINT8_UE4M4_V4"),
    Cfg(MXFormats.INT8, MXFormats.INT8, ScaleFormats.UE6M2, 4, "INT8xINT8_UE6M2_V4"),
    Cfg(MXFormats.INT8, MXFormats.INT8, ScaleFormats.UE7M1, 4, "INT8xINT8_UE7M1_V4"),
    Cfg(MXFormats.INT8, MXFormats.INT8, ScaleFormats.UE5M3, 4, "INT8xINT8_UE5M3_V4"),
  )

  // ── Arch-II SmallFixedShift (R ∈ [1, 4]) ──
  private val archIIConfigs = Seq(
    Cfg(MXFormats.E2M1, MXFormats.E2M1, ScaleFormats.UE8M0, 4, "E2M1xE2M1_UE8M0_V4"),
    Cfg(MXFormats.E2M3, MXFormats.E2M3, ScaleFormats.UE8M0, 4, "E2M3xE2M3_UE8M0_V4"),
    Cfg(MXFormats.E2M1, MXFormats.E2M3, ScaleFormats.UE8M0, 4, "E2M1xE2M3_UE8M0_V4"),
    Cfg(MXFormats.INT8, MXFormats.E2M1, ScaleFormats.UE8M0, 4, "INT8xE2M1_UE8M0_V4"),
    Cfg(MXFormats.INT8, MXFormats.E2M3, ScaleFormats.UE8M0, 4, "INT8xE2M3_UE8M0_V4"),
  )

  // ── Arch-III TwoStageBarrel (R ∈ [5, 32]) ──
  private val archIIIConfigs = Seq(
    Cfg(MXFormats.E3M2, MXFormats.E3M2, ScaleFormats.UE8M0, 4, "E3M2xE3M2_UE8M0_V4"),
    Cfg(MXFormats.E4M3, MXFormats.E4M3, ScaleFormats.UE8M0, 4, "E4M3xE4M3_UE8M0_V4"),
    Cfg(MXFormats.INT8, MXFormats.E4M3, ScaleFormats.UE8M0, 4, "INT8xE4M3_UE8M0_V4"),
    Cfg(MXFormats.INT8, MXFormats.E5M2, ScaleFormats.UE8M0, 4, "INT8xE5M2_UE8M0_V4"),
    Cfg(MXFormats.E3M2, MXFormats.E4M3, ScaleFormats.UE8M0, 4, "E3M2xE4M3_UE8M0_V4"),
  )

  // ── Arch-IV.b KulischInner (R > 32 + UE8M0 only).  Only 3 pairs satisfy
  // R > 32, so vary vsize to reach 5 configs. ──
  private val archIVConfigs = Seq(
    Cfg(MXFormats.E5M2, MXFormats.E5M2, ScaleFormats.UE8M0, 4, "E5M2xE5M2_UE8M0_V4"),
    Cfg(MXFormats.E5M2, MXFormats.E5M2, ScaleFormats.UE8M0, 8, "E5M2xE5M2_UE8M0_V8"),
    Cfg(MXFormats.E5M2, MXFormats.E4M3, ScaleFormats.UE8M0, 4, "E5M2xE4M3_UE8M0_V4"),
    Cfg(MXFormats.E5M2, MXFormats.E4M3, ScaleFormats.UE8M0, 8, "E5M2xE4M3_UE8M0_V8"),
    Cfg(MXFormats.E5M2, MXFormats.E3M2, ScaleFormats.UE8M0, 4, "E5M2xE3M2_UE8M0_V4"),
  )

  // For each (arch, configs), emit:
  //   <archLabel>_<cfgLabel>         : config with arch's TreeArch
  //   baseline_<archLabel>_<cfgLabel>: same config but TreeArch.Generic, same cpb
  // Same cyclesPerBlock per arch so the comparison is apples-to-apples.
  //   Arch-I/II/III: cpb=1 (tree-level archs; FDPU uses buildSingleAcc)
  //   Arch-IV.b   : cpb=8 (FDPU uses buildKulischDeferred for arch and
  //                  buildBlockDeferred for baseline)
  private def doArch(archLabel: String, archForce: TreeArch,
                     configs: Seq[Cfg], cpb: Int): Unit = {
    println(s"\n── ${archLabel} sweep (cpb=$cpb) ──")
    configs.foreach { c =>
      val scfg = ScaleAddConfig(c.typeA, c.typeB, c.scale)
      emitOne(s"${archLabel}_${c.label}",            scfg, archForce,         c.vsize, cpb)
      emitOne(s"baseline_${archLabel}_${c.label}",   scfg, TreeArch.Generic,  c.vsize, cpb)
    }
  }

  doArch("archI",   TreeArch.IntOnlySigned,   archIConfigs,   cpb = 1)
  doArch("archII",  TreeArch.SmallFixedShift, archIIConfigs,  cpb = 1)
  doArch("archIII", TreeArch.TwoStageBarrel,  archIIIConfigs, cpb = 1)
  doArch("archIV",  TreeArch.KulischInner,    archIVConfigs,  cpb = 8)

  println(s"\n[DSE] All emissions complete under $Root/")
}
