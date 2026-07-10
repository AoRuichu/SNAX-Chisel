package mx.mac.deferred_archs

import chisel3._
import chisel3.util.{Cat, log2Ceil, PriorityEncoder, RegEnable, Reverse}
import mx.mac._

/** Kulisch-deferred FDPU variant — Arch-IV.b (DROPPED FROM THESIS).
 *
 *  Wide-fixed-point inner accumulator + FP outer accumulator with scale apply
 *  deferred to the block boundary.
 *
 *  Per cycle (intra-block):
 *    lane products (sign, exp, mant) from CustomOperator
 *      → align to GLOBAL anchor (compile-time minProductExp) via LEFT-shift
 *      → sign-magnitude → 2's complement
 *      → signed integer adder tree (V → 1)
 *      → innerAccReg += laneSum   (wide fixed-point register)
 *    NO LZC, NO normalize, NO FP add inside the block.
 *
 *  On the cyclesPerBlock-th cycle (block boundary):
 *    innerAccReg → LZC + normalize + RNE → mant + biased_exp
 *                                          + scaleA × scaleB (UE8M0: exp arith)
 *                 → blockPartialFP
 *    outerAccReg ← FPNAdder(outerAccReg, blockPartialFP)
 *    innerAccReg ← 0
 *
 *  Anchor and width math:
 *    b      = scfg.minProductExp                  (compile-time)
 *    R      = scfg.productExpRange = maxE − b     (compile-time)
 *    m      = scfg.resOperatorMantWidth           (product mantissa bits)
 *    log2CV = log2(cyclesPerBlock × V)            (accumulation growth)
 *    magW   = R + m + log2CV                      (inner magnitude bits)
 *    accInnerW = 1 + magW                         (sign + magnitude)
 *
 *  Precondition: share_exp_A_i / share_exp_B_i constant across the block
 *  (MX block semantics — wrapper enforces this).
 *
 *  Supports UE8M0 (pure biased-exp scale apply) and non-UE8M0 (mant-multiply
 *  via FPxScale at block boundary).
 *
 *  ── Status ──
 *  This architecture was DROPPED from the thesis after Pareto comparison.
 *  Kept under `deferred_archs/` for reviewer reference / DSE-compare emit.
 */
class FDPUKulischDeferred(
  val scfg:          ScaleAddConfig,
  val vectorSize:    Int,
  val K:             Int        = 32,
  val accMantBits:   Int        = -1,
  val cyclesPerBlock: Int,
  val istest:        Boolean    = false,
) extends Module {
  require(cyclesPerBlock >= 2,
    s"FDPUKulischDeferred requires cyclesPerBlock >= 2, got $cyclesPerBlock")
  require(vectorSize >= 1, "vectorSize must be >= 1")
  require(K >= 1, "K must be >= 1")

  // Shared width math (Kulisch path doesn't use the FP tree's mantissa widening
  // — only actualAccMantBits and fpNW are needed for the outer FP accumulator).
  private val w = FDPUWidthMath(scfg, vectorSize, K, accMantBits = accMantBits)
  val actualAccMantBits: Int = w.actualAccMantBits
  private val fpNW = w.fpNW

  private val wA = scfg.elementTypeA.totalWidth
  private val wB = scfg.elementTypeB.totalWidth

  override def desiredName =
    if (!istest) "BFP_PE"
    else s"FDPUKulischDeferred_${scfg.elementTypeA.name}_x_${scfg.elementTypeB.name}" +
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

  // ── Compile-time anchors + widths ─────────────────────────────────────
  // CustomOperator emits (sign, exp, mant) where the true product value is:
  //   product = (-1)^sign × mant × 2^(exp − fracBitsElem)
  // Two separate anchors:
  //   bShift = minProductExp                — shift amount uses this
  //   bValue = minProductExp − fracBitsElem — value anchor; bit 0 = 2^bValue
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
    val shiftAmt = Mux(expDiff < 0.S,            0.U(shiftAmtW.W),
                     Mux(expDiff.asUInt > R.U,   R.U(shiftAmtW.W),
                                                  expDiff.asUInt(shiftAmtW - 1, 0)))
    val extended  = Cat(0.U((R + log2CV).W), laneOp(i).mant)
    val shifted   = (extended << shiftAmt)(magW - 1, 0)
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

  // ── Block-end bridge: Kulisch → FP ─────────────────────────────────────
  val kSign     = innerNext(accInnerW - 1)
  val kMagFull  = Mux(kSign.asBool, (-innerNext).asUInt(magW - 1, 0),
                                     innerNext.asUInt(magW - 1, 0))
  val isMagZero = kMagFull === 0.U

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
    // ── non-UE8M0 path: assemble pre-scale FP word, apply scale via FPxScale.
    val constOff   = magW - 1 + bValue + 127
    val lzcS       = Cat(false.B, lzc).asSInt
    val preExpBase = constOff.S - lzcS
    val preExpRaw  = Mux(mCarry, preExpBase + 1.S, preExpBase)

    val preBiasedExp = Mux(isMagZero, 0.U(8.W), preExpRaw.asUInt(7, 0))
    val preFP        = Mux(isMagZero, 0.U(fpNW.W),
                                      Cat(kSign, preBiasedExp, finalMantExp))

    // Operand isolation: same rationale as FDPUBlockDeferred — FPxScale
    // output is consumed only on the last cycle of each block window;
    // holding inputs stable on idle cycles cuts power by ~7/8 on
    // mantissa multiplier / LZC / normalize stages.
    val preFPHeld  = withReset(asyncRstN)(RegEnable(preFP,                isLastCycleOfBlock))
    val scaleAHeld = withReset(asyncRstN)(RegEnable(io.share_exp_A_i,     isLastCycleOfBlock))
    val scaleBHeld = withReset(asyncRstN)(RegEnable(io.share_exp_B_i,     isLastCycleOfBlock))
    val fpScale = Module(new FPxScale(scfg, actualAccMantBits))
    fpScale.io.fpIn   := Mux(isLastCycleOfBlock, preFP,            preFPHeld)
    fpScale.io.scaleA := Mux(isLastCycleOfBlock, io.share_exp_A_i, scaleAHeld)
    fpScale.io.scaleB := Mux(isLastCycleOfBlock, io.share_exp_B_i, scaleBHeld)
    blockPartialFP := fpScale.io.fpOut
  }

  // ── Outer FP accumulator (block partials) ─────────────────────────────
  val outerAccReg = withReset(asyncRstN)(RegInit(0.U(fpNW.W)))

  val blockPartialHeld = withReset(asyncRstN)(RegEnable(blockPartialFP, isLastCycleOfBlock))
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
    // outerAcc = standard FP (fpNW bits). Pad both to 96 bits.
    d.innerAccReg   := Cat(0.U((96 - accInnerW).W), innerAccReg.asUInt)
    d.outerAccReg   := Cat(0.U((96 - fpNW).W),       outerAccReg)
    d.blockDoneFlag := blockDone
  }
}
