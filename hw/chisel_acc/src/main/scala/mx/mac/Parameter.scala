package mx.mac

case class ElementType(
    elementWidthExp: Int,
    elementWidthMant: Int,
    name: String,
    implicitScaleExp: Int = 0,                   // implicit scale factor 2^implicitScaleExp (e.g. -6 for INT8)
    reservesMaxExpForSpecial: Boolean = false    // IEEE-like Inf/NaN reservation on max exp encoding.
                                                  //   true  → max usable encoded exp = (1<<W) − 2 (E5M2)
                                                  //   false → max usable encoded exp = (1<<W) − 1 (OCP MX
                                                  //          E4M3 / E3M2 / E2M3 / E2M1 — no Inf, NaN at
                                                  //          most at a single mant code that we don't filter)
){
    // For FP types: 1 (sign) + exp + mant
    // For integer types (exp == 0): 2 + mant, the extra '1' pads to the full hardware word width (e.g. INT8: 2+6=8)
    def totalWidth: Int = if (elementWidthExp == 0) 2 + elementWidthMant else 1 + elementWidthExp + elementWidthMant
    def bias: Int = if(elementWidthExp>0){(1<<(elementWidthExp-1))-1}else{0}
}

case class ScaleType(
    expScaleWidth: Int,
    mantScaleWidth: Int,
    name: String
){
    def totalScaleWidth: Int = expScaleWidth + mantScaleWidth
    def bias: Int = if(expScaleWidth>0){(1<<(expScaleWidth-1))-1}else{0}
    assert( expScaleWidth + mantScaleWidth == 8)
}

case class OperatorConfig(
    elementTypeA: ElementType,
    elementTypeB: ElementType
){
    // add implicit bit +1 
    private def getExtendedMantWidth(t: ElementType): Int = {
        t.elementWidthMant + 1 // 加上隐式位
    }
    // minimum exponent for this element format(include implicit scale -6 for INT8)
    private def minAdjExp(t: ElementType): Int = {
        if (t.elementWidthExp == 0) t.implicitScaleExp //for INT8. exp=0,so worst case is only -6
        else (1 - t.bias) + t.implicitScaleExp  // for other cases, they are always subnormal (1-bias)
    }
    // 容纳负值 v 所需的 SInt 位宽
    private def sIntBitsForNeg(v: Int): Int =
        if (v >= 0) 1 else BigInt(-v).bitLength + 2 //负数-v 需要bitLength(-v)+1(sign), 1bit for overflow

    val maxElementExp = elementTypeA.elementWidthExp max elementTypeB.elementWidthExp
    private val minSumAdjExp = minAdjExp(elementTypeA) + minAdjExp(elementTypeB)
    val resOperatorExpWidth = (maxElementExp + 2) max sIntBitsForNeg(minSumAdjExp)
    //指数相加： expenent output width
    //upper bound max of two exp element , + 2 for overflow 
    //lower bound: 两个最小指数相加，
    val resOperatorMantWidth = getExtendedMantWidth(elementTypeA) + getExtendedMantWidth(elementTypeB)
    //尾数相乘： 两个尾数位宽直接相加，不用考虑溢出
}

case class ScaleAddConfig(
    elementTypeA: ElementType,
    elementTypeB: ElementType,
    stype: ScaleType
){
    // 定义内部函数来计算扩展后的尾数位宽
    //For Element
    private def getExtendedMantWidth(t: ElementType): Int = {
         t.elementWidthMant + 1 // 加上隐式位
    }
    //For Scale
    private def getScaleMantWidth(s: ScaleType): Int = {
        if (s.mantScaleWidth == 0) 1 else s.mantScaleWidth + 1
    }
    // 计算每种格式的最小调整指数（含隐含缩放）
    private def minAdjExp(t: ElementType): Int = {
        if (t.elementWidthExp == 0) t.implicitScaleExp
        else (1 - t.bias) + t.implicitScaleExp
    }
    private def sIntBitsForNeg(v: Int): Int =
        if (v >= 0) 1 else BigInt(-v).bitLength + 2

    //Operator
    val maxElementExp = elementTypeA.elementWidthExp max elementTypeB.elementWidthExp
    private val minSumAdjExp = minAdjExp(elementTypeA) + minAdjExp(elementTypeB)
    val resOperatorExpWidth = (maxElementExp + 2) max sIntBitsForNeg(minSumAdjExp)
    val resOperatorMantWidth = getExtendedMantWidth(elementTypeA) + getExtendedMantWidth(elementTypeB)
    //Scale
    val resScaleExpWidth = stype.expScaleWidth + 2
    val resScaleMantWidth = getScaleMantWidth(stype) * 2 
    //Scale Operate
    val maxScaleAddExp = resOperatorExpWidth max resScaleExpWidth
    val resScaleAddExpWidth = maxScaleAddExp + 1
    val resScaleAddMantWidth = resOperatorMantWidth + resScaleMantWidth
    //val resScaleAddMantWidth = 32

    // Use a custom reduction tree when mantissa is narrow enough to be cheaper than FP32 adders
    def useCustomTree: Boolean = resScaleAddMantWidth < 24

    // Maximum possible exponent of a product (both operands at their largest value).
    //   IEEE-like FP (E5M2):   max usable encoded exp = (1<<W) − 2, top encoding reserved for Inf/NaN
    //   OCP MX FP (others):    max usable encoded exp = (1<<W) − 1, all encodings are valid normals
    //                          (E4M3 has one NaN code at S.1111.111, but the max adjExp = 8 is still
    //                           reachable via mant ≠ 7, and the hardware doesn't filter NaN anyway)
    private def maxExpOf(t: ElementType): Int =
      if (t.elementWidthExp == 0) t.implicitScaleExp
      else {
        val bias = (1 << (t.elementWidthExp - 1)) - 1
        val maxEncReserved = if (t.reservesMaxExpForSpecial) 2 else 1
        ((1 << t.elementWidthExp) - maxEncReserved) - bias + t.implicitScaleExp
      }
    // Range of product exponents: used by FixedFPReductionTree to bound alignment shifts.
    // INT8×INT8 → 0 (all products have the same implicit exponent, no alignment needed).
    val productExpRange: Int =
      (maxExpOf(elementTypeA) + maxExpOf(elementTypeB)) - (minAdjExp(elementTypeA) + minAdjExp(elementTypeB))

    // Bounds on the signed product exponent (compile-time). Used by FDPU's
    // Kulisch (Arch-IV.b) path as the global anchor: every product is
    // left-shifted by (exp − minProductExp) into a wide fixed-point accumulator.
    val minProductExp: Int = minAdjExp(elementTypeA) + minAdjExp(elementTypeB)
    val maxProductExp: Int = maxExpOf(elementTypeA) + maxExpOf(elementTypeB)
}




object ScaleFormats{
    val UE8M0 = ScaleType(8,0,"UE8M0")
    val UE7M1 = ScaleType(7,1,"UE7M1")
    val UE6M2 = ScaleType(6,2,"UE6M2")
    val UE5M3 = ScaleType(5,3,"UE5M3")
    val UE4M4 = ScaleType(4,4,"UE4M4")
    val UE3M5 = ScaleType(3,5,"UE3M5")
    val UE2M6 = ScaleType(2,6,"UE2M6")

    val allScaleTypes = List(UE8M0,UE7M1,UE6M2,UE5M3,UE4M4,UE3M5,UE2M6)
  
}

/** Elaboration-time accumulator precision advisor.
 *
 *  Computes the minimum FP32 mantissa bits needed in the accumulator register
 *  so that accumulation noise stays below the requant noise floor.
 *
 *  Derivation (from Cuyckens et al. 2026 + empirical sweep, K ∈ {4..64}):
 *    - Each accumulation step contributes rounding noise ∝ 2^(−accMantBits).
 *    - After K steps the noise power grows by ~K (worst-case uncorrelated).
 *    - Required: K × 2^(−2M) ≤ 2^(−2·rqFloor)
 *      → M ≥ rqFloor + ceil(log2(K)/2)
 *    - Wide productExpRange adds alignment noise → extra correction term.
 *
 *  Conservative defaults — safe across the full tested K range:
 *    │ productExpRange  │ formula base │ K scaling  │ typical result   │
 *    │ 0  (INT8×INT8)   │ 7            │ ceil(log2K/2) │ K=32→9, K=64→10 │
 *    │ 1..49 (FP8 mix)  │ 7            │ same       │ K=32→9, flat=7  │
 *    │ ≥50 (E5M2×E5M2)  │ 7 + 3        │ same       │ K=64→13         │
 */
object AccPrecision {
  /** Recommended accumulator mantissa bits for a given config and K. */
  def recommended(scfg: ScaleAddConfig, K: Int): Int = {
    require(K >= 1)
    val kBits       = math.ceil(math.log(K.toDouble) / math.log(2.0)).toInt
    val kBonus      = (kBits / 2).max(0)
    val rangePenalty = if (scfg.productExpRange >= 50) 3
                       else if (scfg.productExpRange >= 30) 1
                       else 0
    math.min(23, 7 + kBonus + rangePenalty)
  }

  /** Full accumulator register width: 1 (sign) + 8 (exp) + mantissa bits. */
  def accRegWidth(scfg: ScaleAddConfig, K: Int): Int = 1 + 8 + recommended(scfg, K)
}

// ============================================================
// Reduction-tree micro-architecture selection (DSE knob)
// ============================================================
/** Reduction-tree micro-architecture variants.
 *
 *  All variants share the anchored / single-pass reduction style:
 *    align-once  →  integer adder tree  →  normalize-once
 *  They differ in how each internal stage is realised, so the right pick
 *  depends on (productExpRange, mantissa-width) of the (typeA, typeB) pair:
 *
 *    Generic         — full barrel align + balanced binary ripple adder
 *                       tree + LZC normalize.  Always correct; current
 *                       baseline.  Safe default for any config.
 *    IntOnlySigned   — productExpRange == 0 (INT8×INT8).  No alignment
 *                       shifter, no fracBits zero-padding; pure signed
 *                       integer adder tree on the mantissa products.
 *    SmallFixedShift — productExpRange ≤ 4.  Barrel degenerates to a
 *                       (productExpRange+1)-way mux.
 *    TwoStageBarrel  — 4 < productExpRange ≤ 32.  Two-stage alignment
 *                       (coarse-by-4 + fine-by-3) + Wallace integer tree
 *                       + LZA normalize.
 *    KulischInner    — productExpRange > 32.  FDPU dispatches to a wide
 *                       fixed-point Kulisch accumulator within each block
 *                       (anchored at minProductExp); FixedFPReductionTree is
 *                       bypassed for this arch.  See FDPU.buildKulischDeferred.
 *
 *  See [[TreeArch.recommended]] for the (productExpRange) → arch map.
 *  The intended use is to default to `recommended` but ALSO sweep every
 *  arch on every config to validate that the recommendation is Pareto
 *  optimal — that's the DSE contribution.
 */
sealed trait TreeArch {
  /** Short tag embedded in module desiredName for sweep tracing. */
  def name: String
}
object TreeArch {
  case object Generic         extends TreeArch { val name = "generic"    }
  case object IntOnlySigned   extends TreeArch { val name = "intonly"    }
  case object SmallFixedShift extends TreeArch { val name = "smallshift" }
  case object TwoStageBarrel  extends TreeArch { val name = "twostage"   }
  case object KulischInner    extends TreeArch { val name = "kulisch"   }

  /** Recommended micro-arch given a (typeA, typeB, scale) pair and vec size.
   *  Boundaries (0 / 4 / 32) are heuristic starting points chosen from the
   *  productExpRange distribution across MXFormats × MXFormats; they are
   *  themselves DSE variables and the sweep should validate them.
   */
  def recommended(scfg: ScaleAddConfig, vectorSize: Int): TreeArch = {
    val r = scfg.productExpRange
    if      (r == 0)  IntOnlySigned
    else if (r <= 4)  SmallFixedShift
    else if (r <= 32) TwoStageBarrel
    else              KulischInner
  }
}

object MXFormats{
    // E5M2 is the only MX FP format that follows IEEE-754 Inf/NaN conventions
    // (max exp encoding = Inf/NaN, max usable encoded exp = 30).
    // E4M3/E3M2/E2M3/E2M1 per OCP MX spec v1.0: no Inf, at most one NaN; all
    // other max-exp encodings are valid normal numbers and must be in range.
    val E5M2 = ElementType(5, 2, "E5M2", reservesMaxExpForSpecial = true)
    val E4M3 = ElementType(4, 3, "E4M3")
    val E3M2 = ElementType(3, 2, "E3M2")
    val E2M3 = ElementType(2, 3, "E2M3")
    val E2M1 = ElementType(2, 1, "E2M1")
    val INT8 = ElementType(0, 6, "INT8", implicitScaleExp = -6)


    val defaultConfig = OperatorConfig(
        elementTypeA = E5M2,
        elementTypeB = E5M2
    )

    val e5m2_e4m3_config = OperatorConfig(
        elementTypeA = E5M2,
        elementTypeB = E4M3
    )

    val e4m3_e2m1_config = OperatorConfig(
        elementTypeA = E4M3,
        elementTypeB = E2M1
    )

    val allElementTypes = List(INT8,E5M2,E4M3,E3M2,E2M3,E2M1)

}