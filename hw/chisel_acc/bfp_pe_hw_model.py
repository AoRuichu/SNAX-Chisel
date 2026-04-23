"""
bfp_pe_hw_model.py
==================
Bit-exact hardware simulation of the MX Block Floating-Point PE GeMM array.

Simulates two PE architectures and their tileRows × tileCols GeMM wrappers:

  FDPUPostScaleReductionTree (FDPUPostScaleReductionTree.scala)
    Pipeline per cycle:
      vectorSize × CustomOperator
        → FixedFPReductionTree  (single maxExp + aligned integer sum + one normalise)
        → DirectToFP32 (UE8M0) OR ScaleAddition + ScaleToFP32 (non-UE8M0)
        → FP32Adder → reduced-precision accumulator register (actualAccMantBits)

  FDPUWithCustomReductionTree (FDPUWithCustomReductionTree.scala, used in PEArray.scala)
    Pipeline per cycle:
      vectorSize × (CustomOperator + ScaleAddition)
        → customFPReduceTree (if useCustomTree) + ScaleToFP32
          OR per-lane ScaleToFP32 + fp32ReduceTree
        → FP32Adder → full FP32 accumulator register

Both PEs are composed into a tileRows × tileCols GeMM array matching PEArray.scala:
  - PE[r][c] receives op_a[r] (vectorSize element bits) and op_b[c]
  - shared scale A[r] broadcast to all PEs in row r
  - shared scale B[c] broadcast to all PEs in col c
  - output: results_o[r][c] as FP32 (uint32 bit pattern), tileRows × tileCols

All arithmetic is integer-only to match RTL bit-widths and operation order exactly.
"""

import math
import struct
from dataclasses import dataclass
from typing import List, Tuple


# ═══════════════════════════════════════════════════════════════════════════════
# §1  Bit-manipulation primitives
# ═══════════════════════════════════════════════════════════════════════════════

def _mask(w: int) -> int:
    """(1<<w) - 1  — a w-bit all-ones mask."""
    return (1 << w) - 1

def _bits(x: int, hi: int, lo: int) -> int:
    """Extract unsigned bit field x[hi:lo] (both ends inclusive)."""
    return (x >> lo) & _mask(hi - lo + 1)

def _bit(x: int, pos: int) -> int:
    """Extract single bit x[pos]."""
    return (x >> pos) & 1

def _sign_ext(x: int, w: int) -> int:
    """Sign-extend w-bit value to Python int (arbitrary-precision signed)."""
    x &= _mask(w)
    return x - (1 << w) if (x >> (w - 1)) else x

def _wrap(x: int, w: int) -> int:
    """Truncate / wrap x to w-bit unsigned (equivalent to Chisel UInt)."""
    return x & _mask(w)

def _lzc(x: int, w: int) -> int:
    """Count leading zeros in w-bit unsigned value x.
    Matches Chisel PriorityEncoder(x.asBools.reverse) semantics."""
    x &= _mask(w)
    if x == 0:
        return w
    return w - x.bit_length()

def _orr(x: int) -> bool:
    """Reduction OR: True if any bit is set."""
    return x != 0


# ═══════════════════════════════════════════════════════════════════════════════
# §2  Format descriptors  (mirror Parameter.scala)
# ═══════════════════════════════════════════════════════════════════════════════

@dataclass(frozen=True)
class ElementType:
    elementWidthExp:  int
    elementWidthMant: int
    name:             str
    implicitScaleExp: int = 0   # INT8 uses -6

    @property
    def totalWidth(self) -> int:
        # FP: 1(sign)+exp+mant   INT8: 2+mant  (matches Chisel totalWidth)
        return (2 + self.elementWidthMant) if self.elementWidthExp == 0 \
               else (1 + self.elementWidthExp + self.elementWidthMant)

    @property
    def bias(self) -> int:
        return ((1 << (self.elementWidthExp - 1)) - 1) if self.elementWidthExp > 0 else 0


@dataclass(frozen=True)
class ScaleType:
    expScaleWidth:  int
    mantScaleWidth: int
    name:           str

    @property
    def totalScaleWidth(self) -> int:
        return self.expScaleWidth + self.mantScaleWidth

    @property
    def bias(self) -> int:
        return ((1 << (self.expScaleWidth - 1)) - 1) if self.expScaleWidth > 0 else 0


# ── Derived widths (mirrors ScaleAddConfig companion computations) ─────────────

def _sint_bits_for_neg(v: int) -> int:
    """Minimum SInt width to represent a negative value v."""
    return 1 if v >= 0 else int(-v).bit_length() + 2

def _min_adj_exp(t: ElementType) -> int:
    if t.elementWidthExp == 0: # If INT8,  exp is  fixed to implicit -6 
        return t.implicitScaleExp
    return (1 - t.bias) + t.implicitScaleExp # other type has implicit bit 0 but 1-bias as the minimum exp

def _max_exp_of(t: ElementType) -> int:
    if t.elementWidthExp == 0: # If INT8,  exp is  fixed to implicit -6 
        return t.implicitScaleExp
    bias = (1 << (t.elementWidthExp - 1)) - 1
    return ((1 << t.elementWidthExp) - 2) - bias + t.implicitScaleExp


@dataclass
class ScaleAddConfig:
    elementTypeA: ElementType
    elementTypeB: ElementType
    stype:        ScaleType

    def __post_init__(self):
        a, b, s = self.elementTypeA, self.elementTypeB, self.stype

        # ── Operator output widths ─────────────────────────────────────────────
        max_elem_exp   = max(a.elementWidthExp, b.elementWidthExp)
        min_sum_adj    = _min_adj_exp(a) + _min_adj_exp(b)
        self.resOperatorExpWidth  = max(max_elem_exp + 2, _sint_bits_for_neg(min_sum_adj))
        self.resOperatorMantWidth = (a.elementWidthMant + 1) + (b.elementWidthMant + 1)

        # ── Scale output widths ────────────────────────────────────────────────
        scale_mant_w   = 1 if s.mantScaleWidth == 0 else s.mantScaleWidth + 1
        self.resScaleExpWidth  = s.expScaleWidth + 2
        self.resScaleMantWidth = scale_mant_w * 2

        # ── ScaleAdd output widths ─────────────────────────────────────────────
        max_sa_exp              = max(self.resOperatorExpWidth, self.resScaleExpWidth)
        self.resScaleAddExpWidth  = max_sa_exp + 1
        self.resScaleAddMantWidth = self.resOperatorMantWidth + self.resScaleMantWidth

        # ── FixedFPReductionTree exponent-spread bound ─────────────────────────
        self.productExpRange = (
            (_max_exp_of(a) + _max_exp_of(b)) -
            (_min_adj_exp(a) + _min_adj_exp(b))
        )

    @property
    def useCustomTree(self) -> bool:
        """True → use CustomFPAdder tree + single ScaleToFP32; False → per-lane FP32."""
        return self.resScaleAddMantWidth < 24


# ── Predefined format objects ──────────────────────────────────────────────────

class MXFormats:
    E5M2 = ElementType(5, 2, "E5M2")
    E4M3 = ElementType(4, 3, "E4M3")
    E3M2 = ElementType(3, 2, "E3M2")
    E2M3 = ElementType(2, 3, "E2M3")
    E2M1 = ElementType(2, 1, "E2M1")
    INT8 = ElementType(0, 6, "INT8", implicitScaleExp=-6)


class ScaleFormats:
    UE8M0 = ScaleType(8, 0, "UE8M0")
    UE7M1 = ScaleType(7, 1, "UE7M1")
    UE6M2 = ScaleType(6, 2, "UE6M2")
    UE5M3 = ScaleType(5, 3, "UE5M3")
    UE4M4 = ScaleType(4, 4, "UE4M4")
    UE3M5 = ScaleType(3, 5, "UE3M5")
    UE2M6 = ScaleType(2, 6, "UE2M6")


# ═══════════════════════════════════════════════════════════════════════════════
# §3  AccPrecision  (mirrors AccPrecision.scala)
# ═══════════════════════════════════════════════════════════════════════════════

def acc_precision_recommended(scfg: ScaleAddConfig, K: int) -> int:
    """Minimum accumulator mantissa bits so that acc noise stays below requant floor."""
    k_bits       = math.ceil(math.log2(K)) if K > 1 else 0
    k_bonus      = k_bits // 2
    range_penalty = 3 if scfg.productExpRange >= 50 else \
                    1 if scfg.productExpRange >= 30 else 0
    return min(23, 7 + k_bonus + range_penalty)


# ═══════════════════════════════════════════════════════════════════════════════
# §4  CustomOperator  (mirrors CustomOperator.scala)
#
#  Decodes two MX elements and produces their product in CustomFP format:
#    outSign = signA ^ signB
#    outExp  = adjExpA + adjExpB      (SInt, resOperatorExpWidth bits)
#    outMant = fullMantA × fullMantB  (UInt, resOperatorMantWidth bits)
# ═══════════════════════════════════════════════════════════════════════════════

def custom_operator(
        in_a: int, in_b: int,
        type_a: ElementType, type_b: ElementType,
        res_exp_w: int, res_mant_w: int
) -> Tuple[int, int, int]:
    """
    Returns (outSign, outExp_signed_python_int, outMant_unsigned).
    outExp is already sign-extended to a Python int.
    """
    def decode(val: int, et: ElementType) -> Tuple[int, int, int]:
        sign = _bit(val, et.totalWidth - 1)
        if et.elementWidthExp == 0:          # INT8: two's-complement magnitude
            raw7 = val & _mask(et.totalWidth - 1)
            magnitude = (_wrap(~raw7 + 1, et.totalWidth - 1)) if sign else raw7
            return sign, 0, magnitude
        else:                                # FP: implicit leading-1 bit
            exp_val  = _bits(val, et.elementWidthMant + et.elementWidthExp - 1,
                                  et.elementWidthMant)
            mant_raw = _bits(val, et.elementWidthMant - 1, 0)
            impl     = 1 if exp_val != 0 else 0   # implicit bit (0 for subnormal)
            return sign, exp_val, (impl << et.elementWidthMant) | mant_raw

    sign_a, exp_a, full_mant_a = decode(in_a, type_a)
    sign_b, exp_b, full_mant_b = decode(in_b, type_b)

    def adj_exp(exp_val: int, et: ElementType) -> int:
        if et.elementWidthExp == 0:
            return et.implicitScaleExp
        base = (1 - et.bias) if exp_val == 0 else (exp_val - et.bias)
        return base + et.implicitScaleExp

    exp_sum_raw = adj_exp(exp_a, type_a) + adj_exp(exp_b, type_b)
    out_sign    = sign_a ^ sign_b
    out_mant    = _wrap(full_mant_a * full_mant_b, res_mant_w)
    out_exp     = _sign_ext(_wrap(exp_sum_raw, res_exp_w), res_exp_w)
    return out_sign, out_exp, out_mant


# ═══════════════════════════════════════════════════════════════════════════════
# §5  FixedFPReductionTree  (mirrors FixedFPReductionTree in CustomReduction.scala)
#
#  Single-pass signed-integer reduction of N CustomFP lanes:
#   1. Find maxExp across all inputs.
#   2. Align each mantissa: right-shift by (maxExp − expᵢ), clamped to fracBits.
#   3. Convert sign-magnitude → 2's-complement, sum.
#   4. LZC + normalise, then RNE round to outMantW bits.
# ═══════════════════════════════════════════════════════════════════════════════

def fixed_fp_reduction_tree(
        inputs: List[Tuple[int, int, int]],   # [(sign, exp_signed, mant_unsigned), ...]
        exp_w: int, in_mant_w: int, out_mant_w: int,
        product_exp_range: int
) -> Tuple[int, int, int]:
    """Returns (out_sign, out_exp_signed, out_mant_unsigned)."""
    N       = len(inputs)
    G       = 3   # guard bits
    log2N   = math.ceil(math.log2(max(N, 2)))
    frac_bits = product_exp_range + G
    abs_mag_w = in_mant_w + frac_bits + log2N

    # ── Step 1: max exponent ──────────────────────────────────────────────────
    max_exp = max(exp for _, exp, _ in inputs)

    # ── Step 2: align each input as a signed 2's-complement integer ───────────
    # Layout in abs_mag_w bits:
    #   [abs_mag_w-1 : frac_bits+log2N] ← inMantW mantissa bits
    #   [frac_bits+log2N-1 : frac_bits] ← log2N carry-overflow bits (0 per input)
    #   [frac_bits-1 : 0]               ← productExpRange fractional + G guard
    aligned_vals = []
    for sign, exp, mant in inputs:
        diff      = max_exp - exp                      # always >= 0
        shift_amt = min(diff, frac_bits)
        extended  = _wrap(mant << frac_bits, abs_mag_w)  # mant at [frac_bits+inMantW-1:frac_bits]
        shifted   = _wrap(extended >> shift_amt, abs_mag_w)
        # sign-magnitude → 2's complement (abs_mag_w+1 wide)
        signed_val = _wrap(~shifted + 1, abs_mag_w + 1) if sign else shifted
        aligned_vals.append(_sign_ext(signed_val, abs_mag_w + 1))

    # ── Step 3: integer sum, truncate to abs_mag_w+1 signed ──────────────────
    raw_sum   = sum(aligned_vals)
    total_sum = _sign_ext(_wrap(raw_sum, abs_mag_w + 1), abs_mag_w + 1)

    # ── Step 4: normalise + RNE round ─────────────────────────────────────────
    is_neg  = total_sum < 0
    sum_u   = _wrap(total_sum, abs_mag_w + 1)
    # 2's-complement magnitude (abs_mag_w bits)
    abs_mag = _wrap(~sum_u + 1, abs_mag_w) if is_neg else (sum_u & _mask(abs_mag_w))
    is_zero = (abs_mag == 0)

    lzc_val    = _lzc(abs_mag, abs_mag_w)
    normalized = _wrap(abs_mag << lzc_val, abs_mag_w)   # MSB-aligned

    # RNE round: abs_mag_w bits → out_mant_w bits
    # Position offsets from the MSB of 'normalized':
    #   mantissa bits  : [abs_mag_w-1 : abs_mag_w-out_mant_w]
    #   guard bit      : [abs_mag_w-out_mant_w-1]
    #   round bit      : [abs_mag_w-out_mant_w-2]
    #   sticky region  : [abs_mag_w-out_mant_w-3 : 0]
    g_pos = abs_mag_w - out_mant_w - 1
    r_pos = abs_mag_w - out_mant_w - 2

    mant_raw   = _bits(normalized, abs_mag_w - 1, abs_mag_w - out_mant_w)
    guard_bit  = _bit(normalized, g_pos) if g_pos >= 0 else 0
    round_bit  = _bit(normalized, r_pos) if r_pos >= 0 else 0
    s_top      = abs_mag_w - out_mant_w - 3
    sticky_bits = _orr(_bits(normalized, s_top, 0)) if s_top >= 0 else False

    round_up  = guard_bit and (_bit(mant_raw, 0) or round_bit or sticky_bits)
    rounded_m = _wrap(mant_raw + (1 if round_up else 0), out_mant_w + 1)
    m_carry   = _bit(rounded_m, out_mant_w)
    final_mant = (1 << (out_mant_w - 1)) if m_carry else (rounded_m & _mask(out_mant_w))

    # outExp = maxExp + (inMantW + log2N − outMantW) − lzc [+1 if mCarry]
    exp_base    = max_exp + (in_mant_w + log2N - out_mant_w) - lzc_val
    out_exp_raw = exp_base + (1 if m_carry else 0)
    out_exp     = _sign_ext(_wrap(out_exp_raw, exp_w), exp_w)

    if is_zero:
        return 0, out_exp, 0
    return (1 if is_neg else 0), out_exp, final_mant


# ═══════════════════════════════════════════════════════════════════════════════
# §6  ScaleAddition  (mirrors ScaleAddition.scala)
#
#  Multiplies the scale factors (from the MX block header) into the CustomFP
#  product coming out of CustomOperator:
#    outExp  = adjExpScaleA + adjExpScaleB + inOpExp
#    outMant = fullMantScaleA × fullMantScaleB × inOpMant
# ═══════════════════════════════════════════════════════════════════════════════

def scale_addition(
        op_sign: int, op_exp: int, op_mant: int,
        share_scale_a: int, share_scale_b: int,
        scfg: ScaleAddConfig
) -> Tuple[int, int, int]:
    """Returns (out_sign, out_exp_signed, out_mant_unsigned)."""
    def decode_scale(val: int) -> Tuple[int, int]:
        s = scfg.stype
        exp_s = _bits(val, s.totalScaleWidth - 1, s.mantScaleWidth)
        if s.mantScaleWidth == 0:
            return exp_s, 1   # UE8M0: pure power-of-2, implicit mantissa = 1
        mant_s  = _bits(val, s.mantScaleWidth - 1, 0)
        impl    = 1 if exp_s != 0 else 0
        return exp_s, (impl << s.mantScaleWidth) | mant_s

    def adj_scale_exp(exp_val: int) -> int:
        s = scfg.stype
        if exp_val == 0 and s.mantScaleWidth > 0:
            return 1 - s.bias      # subnormal scale: use 1 - bias
        return exp_val - s.bias

    exp_sa, full_mant_sa = decode_scale(share_scale_a)
    exp_sb, full_mant_sb = decode_scale(share_scale_b)

    scale_exp_sum  = adj_scale_exp(exp_sa) + adj_scale_exp(exp_sb)
    scale_mant_prod = _wrap(full_mant_sa * full_mant_sb, scfg.resScaleMantWidth)

    out_exp_raw = scale_exp_sum + op_exp
    out_mant    = _wrap(scale_mant_prod * op_mant, scfg.resScaleAddMantWidth)
    out_exp     = _sign_ext(_wrap(out_exp_raw, scfg.resScaleAddExpWidth),
                             scfg.resScaleAddExpWidth)
    return op_sign, out_exp, out_mant


# ═══════════════════════════════════════════════════════════════════════════════
# §7  DirectToFP32  (mirrors DirectToFP32 in FP32Common.scala)
#
#  UE8M0-only fast path: both scale factors are pure powers of 2.
#  Replaces ScaleAddition + ScaleToFP32 with pure exponent arithmetic + wiring:
#    fp32Exp  = treeExp + (scaleA - bias) + (scaleB - bias) + fixedBias
#    fp32Mant = treeOpMant[opMantW-2:0] zero-padded to 23 bits
# ═══════════════════════════════════════════════════════════════════════════════

def direct_to_fp32(
        op_sign: int, op_exp: int, op_mant: int,
        share_scale_a: int, share_scale_b: int,
        scfg: ScaleAddConfig
) -> int:
    """Returns 32-bit IEEE-754 FP bit pattern (uint32)."""
    assert scfg.stype.mantScaleWidth == 0, "DirectToFP32 requires UE8M0 scale type"

    op_mant_w = scfg.resOperatorMantWidth

    # elemFrac: fractional mantissa bits baked into the integer product
    def elem_frac(t: ElementType) -> int:
        return 0 if t.elementWidthExp == 0 else t.elementWidthMant

    frac_bits_elem = elem_frac(scfg.elementTypeA) + elem_frac(scfg.elementTypeB)
    fixed_bias     = 126 + op_mant_w - frac_bits_elem
    scale_bias     = scfg.stype.bias   # 127 for UE8M0

    adj_exp_a = share_scale_a - scale_bias
    adj_exp_b = share_scale_b - scale_bias
    exp_total = op_exp + adj_exp_a + adj_exp_b + fixed_bias

    is_zero     = (op_mant == 0)
    is_overflow = (exp_total >= 255)
    is_under    = (exp_total <= 0)

    if is_zero or is_under:
        return 0
    if is_overflow:
        return (op_sign << 31) | (0xFF << 23)

    # FP32 mantissa: treeOpMant[opMantW-2:0] zero-padded to 23 bits.
    # lzc is always 2 for UE8M0 (normalised tree output + 2-bit zero-pad from UE8M0).
    # The opMantW-1 fractional bits land in fp32[22 : 24-opMantW], rest = 0.
    frac_part = _bits(op_mant, op_mant_w - 2, 0)           # opMantW-1 bits
    fp32_mant = _wrap(frac_part << (24 - op_mant_w), 23)   # shift to [22:24-opMantW]

    return (op_sign << 31) | (_wrap(exp_total, 8) << 23) | fp32_mant


# ═══════════════════════════════════════════════════════════════════════════════
# §8  ScaleToFP32  (mirrors ScaleToFP32 in FP32Common.scala)
#
#  Converts (sign, SInt exp, UInt mant) from ScaleAddition to IEEE-754 FP32
#  via LZC normalisation + RNE rounding.
# ═══════════════════════════════════════════════════════════════════════════════

def scale_to_fp32(
        in_sign: int, in_exp: int, in_mant: int,
        scfg: ScaleAddConfig
) -> int:
    """Returns 32-bit IEEE-754 FP bit pattern (uint32)."""
    def elem_frac(t: ElementType) -> int:
        return 0 if t.name == "INT8" else t.elementWidthMant

    frac_bits  = (elem_frac(scfg.elementTypeA) + elem_frac(scfg.elementTypeB) +
                  2 * scfg.stype.mantScaleWidth)
    mant_width = scfg.resScaleAddMantWidth
    exp_bias   = 127 + mant_width - 1 - frac_bits

    if in_mant == 0:
        return 0

    lzc_val      = _lzc(in_mant, mant_width)
    shifted_mant = _wrap(in_mant << lzc_val, mant_width)

    # Pad to at least 27 bits so guard/round/sticky always exist
    EXTRA        = max(0, 27 - mant_width)
    padded       = _wrap(shifted_mant << EXTRA, mant_width + EXTRA)
    sep          = EXTRA + mant_width - 2   # index of the first fractional bit

    mant23    = _bits(padded, sep, sep - 22)
    guard_bit = _bit(padded, sep - 23) if sep - 23 >= 0 else 0
    round_bit = _bit(padded, sep - 24) if sep - 24 >= 0 else 0
    sticky_bits = (_orr(_bits(padded, sep - 25, 0)) if sep - 25 >= 0 else False) \
                  or (lzc_val > mant_width - 1)

    round_up    = guard_bit and (_bit(mant23, 0) or round_bit or sticky_bits)
    rounded_m   = _wrap(mant23 + (1 if round_up else 0), 24)
    round_carry = _bit(rounded_m, 23)
    final_mant  = rounded_m & _mask(23)

    adj_exp    = in_exp - lzc_val + exp_bias + round_carry
    is_over    = adj_exp >= 255
    is_under   = adj_exp <= 0

    if is_under:
        return 0
    if is_over:
        return (in_sign << 31) | (0xFF << 23)
    return (in_sign << 31) | (_wrap(adj_exp, 8) << 23) | final_mant


# ═══════════════════════════════════════════════════════════════════════════════
# §9  FP32Adder  (mirrors FP32Adder in FP32Common.scala)
#
#  Purely-combinational IEEE-754 single-precision adder.
#  3 guard bits for RNE. No NaN/Inf special-casing.
# ═══════════════════════════════════════════════════════════════════════════════

def fp32_adder(a: int, b: int) -> int:
    """Returns 32-bit IEEE-754 FP bit pattern (uint32)."""
    # Unpack
    a_s = _bit(a, 31);  a_e = _bits(a, 30, 23)
    b_s = _bit(b, 31);  b_e = _bits(b, 30, 23)
    # 24-bit mantissa with implicit leading-1 (0 for denormal/zero)
    a_m = ((1 if a_e else 0) << 23) | _bits(a, 22, 0)
    b_m = ((1 if b_e else 0) << 23) | _bits(b, 22, 0)

    exp_diff = int(a_e) - int(b_e)
    a_greater = (exp_diff > 0) or (exp_diff == 0 and a_m >= b_m)

    far_e  = a_e if a_greater else b_e
    far_m  = a_m if a_greater else b_m
    far_s  = a_s if a_greater else b_s
    near_m = b_m if a_greater else a_m

    abs_diff = abs(exp_diff)
    # Append 3 guard bits below the 24-bit mantissa
    near_m27     = _wrap(near_m << 3, 27)
    # Bits of near_m27 shifted past position 0 become sticky
    sticky_align = _orr(near_m27 & _mask(abs_diff)) if abs_diff > 0 else False
    aligned_near = _wrap(near_m27 >> abs_diff, 27)

    is_sub   = bool(a_s ^ b_s)
    far_m27  = _wrap(far_m << 3, 27)

    if is_sub:
        # Unsigned subtraction in 28 bits (may wrap if near > far, but far was chosen as larger)
        res_mag = _wrap(far_m27 - aligned_near, 28)
    else:
        res_mag = _wrap(far_m27 + aligned_near, 28)

    # Normalize: shift MSB of res_mag to bit 27
    lzc_val    = _lzc(res_mag, 28)
    norm_shift = _wrap(res_mag << lzc_val, 28)

    # Layout of norm_shift: bit27=implicit-1, [26:4]=mant(23b), 3=G, 2=R, [1:0]=S
    mant23     = _bits(norm_shift, 26, 4)
    guard_bit  = _bit(norm_shift, 3)
    round_bit  = _bit(norm_shift, 2)
    sticky_bit = _orr(_bits(norm_shift, 1, 0)) or sticky_align

    round_up   = guard_bit and (_bit(mant23, 0) or round_bit or sticky_bit)
    rounded_m  = _wrap(mant23 + (1 if round_up else 0), 24)
    mant_carry = _bit(rounded_m, 23)
    final_m    = rounded_m & _mask(23)

    # Exponent: account for normalisation shift and optional rounding carry
    final_e = int(far_e) - lzc_val + 1 + mant_carry

    is_zero  = (res_mag == 0) or (final_e <= 0)
    is_inf   = (final_e >= 255)

    if is_zero:
        return 0
    if is_inf:
        return (far_s << 31) | (0xFF << 23)
    return (far_s << 31) | (_wrap(final_e, 8) << 23) | final_m


# ═══════════════════════════════════════════════════════════════════════════════
# §10  CustomFPAdder  (mirrors CustomFPAdder in CustomReduction.scala)
#
#  Sign-magnitude FP adder for the per-lane-scale custom reduction tree.
#  Unlike FP32Adder, this operates on the unnormalized (sign, SInt exp, UInt mant)
#  format produced by ScaleAddition; normalization is deferred to ScaleToFP32.
# ═══════════════════════════════════════════════════════════════════════════════

def custom_fp_adder(
        a_sign: int, a_exp: int, a_mant: int,
        b_sign: int, b_exp: int, b_mant: int,
        exp_w: int, mant_w: int
) -> Tuple[int, int, int]:
    """Returns (out_sign, out_exp_signed, out_mant_unsigned)."""
    G   = 3
    EXT = mant_w + G

    # ── 1. Select far (larger magnitude) and near operands ────────────────────
    a_zero   = (a_mant == 0)
    b_zero   = (b_mant == 0)
    exp_diff = a_exp - b_exp

    a_larger = (not a_zero and b_zero) or (
        not a_zero and not b_zero and
        (exp_diff > 0 or (exp_diff == 0 and a_mant >= b_mant))
    )
    far_sign  = a_sign  if a_larger else b_sign
    far_exp   = a_exp   if a_larger else b_exp
    far_mant  = a_mant  if a_larger else b_mant
    near_sign = b_sign  if a_larger else a_sign
    near_mant = b_mant  if a_larger else a_mant

    # ── 2. Align near mantissa (3 guard bits below) ───────────────────────────
    near_ext   = _wrap(near_mant << G, EXT)
    far_ext    = _wrap(far_mant  << G, EXT)
    abs_shift  = abs(exp_diff)
    clamped_sh = min(abs_shift, EXT)

    sticky_raw  = _orr(near_ext & _mask(clamped_sh))
    sticky_over = abs_shift > EXT
    sticky0     = sticky_raw or sticky_over
    aligned     = _wrap(near_ext >> clamped_sh, EXT)

    # ── 3. Add / subtract in sign-magnitude ───────────────────────────────────
    is_sub = (far_sign ^ near_sign) != 0
    sub_borrow = False

    if is_sub:
        sub_fwd    = _wrap(far_ext - aligned, EXT + 1)
        sub_borrow = bool(_bit(sub_fwd, EXT))
        if sub_borrow:
            sub_result = _wrap(~sub_fwd + 1, EXT + 1)
        else:
            sub_result = sub_fwd
        res_mag = sub_result
    else:
        res_mag = _wrap(far_ext + aligned, EXT + 1)

    # ── 4a. Absorb addition carry ──────────────────────────────────────────────
    carry = bool(_bit(res_mag, EXT))
    if carry:
        shifted       = _wrap(res_mag >> 1, EXT)
        sticky_carry  = bool(_bit(res_mag, 0))
        exp_aft_carry = far_exp + 1
    else:
        shifted       = res_mag & _mask(EXT)
        sticky_carry  = False
        exp_aft_carry = far_exp

    # ── 4b. Post-subtraction normalisation (remove catastrophic-cancellation zeros) ──
    is_nonzero  = _orr(shifted)
    lzc_val     = _lzc(shifted, EXT)
    norm_amt    = lzc_val if (is_sub and not carry and is_nonzero) else 0
    shifted_norm = _wrap(shifted << norm_amt, EXT)
    exp_norm     = exp_aft_carry - norm_amt

    # ── 5. RNE rounding from EXT bits → mant_w bits ───────────────────────────
    # Layout: [EXT-1:G] = mant_w-bit mantissa, [2]=G, [1]=R, [0]=S-low
    mant_raw    = _bits(shifted_norm, EXT - 1, G)
    guard_bit   = _bit(shifted_norm, 2)
    round_bit   = _bit(shifted_norm, 1)
    sticky_fin  = bool(_bit(shifted_norm, 0)) or sticky0 or sticky_carry

    round_up    = guard_bit and (_bit(mant_raw, 0) or round_bit or sticky_fin)
    rounded_m   = _wrap(mant_raw + (1 if round_up else 0), mant_w + 1)
    mant_carry  = bool(_bit(rounded_m, mant_w))

    if mant_carry:
        final_mant  = 1 << (mant_w - 1)
        final_exp_w = exp_norm + 1
    else:
        final_mant  = rounded_m & _mask(mant_w)
        final_exp_w = exp_norm

    # ── 6. Output ──────────────────────────────────────────────────────────────
    is_zero  = (res_mag == 0)
    out_sign = near_sign if (is_sub and sub_borrow) else far_sign
    out_exp  = _sign_ext(_wrap(final_exp_w, exp_w), exp_w)

    if is_zero:
        return 0, out_exp, 0
    return out_sign, out_exp, final_mant & _mask(mant_w)


# ═══════════════════════════════════════════════════════════════════════════════
# §11  FDPUPostScaleReductionTree  (mirrors FDPUPostScaleReductionTree.scala)
#
#  Single PE: vectorSize CustomOperators → FixedFPReductionTree
#          → DirectToFP32 or ScaleAdd+ScaleToFP32
#          → FP32Adder → reduced-precision accumulator register
#
#  The accumulator stores (1 + 8 + actualAccMantBits) bits.
#  Output zero-extends lower mantissa bits to full FP32.
# ═══════════════════════════════════════════════════════════════════════════════

class FDPUPostScaleReductionTree:
    """
    Single PE of the post-scale architecture.

    Parameters
    ----------
    scfg         : ScaleAddConfig
    vector_size  : parallel MACs per cycle (≥ 1)
    K            : accumulation depth (used to derive default acc precision)
    acc_mant_bits: -1 = auto (AccPrecision.recommended), else explicit 1..23
    """
    def __init__(self, scfg: ScaleAddConfig, vector_size: int,
                 K: int = 32, acc_mant_bits: int = -1):
        self.scfg        = scfg
        self.vector_size = vector_size
        self.K           = K
        self.actual_acc_mant_bits = (
            acc_precision_recommended(scfg, K) if acc_mant_bits == -1
            else acc_mant_bits
        )
        self.mant_drop   = 23 - self.actual_acc_mant_bits
        self.acc_reg_w   = 1 + 8 + self.actual_acc_mant_bits
        self._acc_reg    = 0   # stored as acc_reg_w unsigned bits

    def reset(self):
        self._acc_reg = 0

    def _acc_fp32(self) -> int:
        """Reconstruct FP32 from reduced-precision register (zero-extend low mantissa)."""
        return _wrap(self._acc_reg << self.mant_drop, 32) if self.mant_drop > 0 \
               else (self._acc_reg & _mask(32))

    def step(self,
             op_a_vec: List[int],   # [vector_size] raw element bits for A
             op_b_vec: List[int],   # [vector_size] raw element bits for B
             share_exp_a: int,      # 8-bit shared scale for row A
             share_exp_b: int,      # 8-bit shared scale for col B
             valid_in:  bool = True,
             reset_acc: bool = False) -> int:
        """Run one pipeline cycle. Returns accOut (32-bit FP32 uint32)."""
        if reset_acc:
            self._acc_reg = 0
            return self._acc_fp32()
        if not valid_in:
            return self._acc_fp32()

        scfg = self.scfg

        # ── Per-lane CustomOperator ────────────────────────────────────────────
        lane_ops = [
            custom_operator(op_a_vec[i], op_b_vec[i],
                            scfg.elementTypeA, scfg.elementTypeB,
                            scfg.resOperatorExpWidth, scfg.resOperatorMantWidth)
            for i in range(self.vector_size)
        ]

        # ── FixedFP Reduction Tree ─────────────────────────────────────────────
        tree_sign, tree_exp, tree_mant = fixed_fp_reduction_tree(
            lane_ops,
            exp_w=scfg.resOperatorExpWidth,
            in_mant_w=scfg.resOperatorMantWidth,
            out_mant_w=scfg.resOperatorMantWidth,
            product_exp_range=scfg.productExpRange
        )

        # ── Scale path: UE8M0 → DirectToFP32; others → ScaleAdd + ScaleToFP32 ─
        if scfg.stype.mantScaleWidth == 0:
            reduced_sum = direct_to_fp32(tree_sign, tree_exp, tree_mant,
                                         share_exp_a, share_exp_b, scfg)
        else:
            sa_sign, sa_exp, sa_mant = scale_addition(
                tree_sign, tree_exp, tree_mant,
                share_exp_a, share_exp_b, scfg)
            reduced_sum = scale_to_fp32(sa_sign, sa_exp, sa_mant, scfg)

        # ── FP32 accumulator: add, then truncate to actualAccMantBits ─────────
        acc_fp32    = self._acc_fp32()
        adder_out   = fp32_adder(acc_fp32, reduced_sum)
        # Drop the lower mant_drop mantissa bits before storing (adder_out[31:mant_drop])
        adder_trunc = _bits(adder_out, 31, self.mant_drop)
        self._acc_reg = _wrap(adder_trunc, self.acc_reg_w)

        return self._acc_fp32()

    @property
    def acc_out(self) -> int:
        return self._acc_fp32()


# ═══════════════════════════════════════════════════════════════════════════════
# §12  FDPUWithCustomReductionTree  (mirrors FDPUWithCustomReductionTree.scala)
#
#  Single PE of the per-lane-scale architecture (used in PEArray GeMM wrappers).
#
#  Path selected at construction time based on scfg.useCustomTree:
#    True  → per-lane (CustomOp + ScaleAdd) → balanced CustomFPAdder tree
#            → single ScaleToFP32
#    False → per-lane (CustomOp + ScaleAdd + ScaleToFP32) → balanced FP32Adder tree
#
#  Accumulator: full FP32 (no mantissa truncation).
# ═══════════════════════════════════════════════════════════════════════════════

class FDPUWithCustomReductionTree:
    """Single PE of the per-lane-scale architecture."""
    def __init__(self, scfg: ScaleAddConfig, vector_size: int):
        self.scfg        = scfg
        self.vector_size = vector_size
        self._acc_reg    = 0   # full 32-bit FP

    def reset(self):
        self._acc_reg = 0

    # ── Balanced tree helpers ──────────────────────────────────────────────────
    @staticmethod
    def _fp32_reduce_tree(vals: List[int]) -> int:
        """Pair-wise balanced FP32Adder reduction (matches fp32ReduceTree in Scala)."""
        while len(vals) > 1:
            nxt = []
            for i in range(0, len(vals), 2):
                if i + 1 < len(vals):
                    nxt.append(fp32_adder(vals[i], vals[i + 1]))
                else:
                    nxt.append(vals[i])
            vals = nxt
        return vals[0]

    def _custom_fp_reduce_tree(
            self, vals: List[Tuple[int, int, int]]
    ) -> Tuple[int, int, int]:
        """Pair-wise balanced CustomFPAdder reduction (matches customFPReduceTree)."""
        exp_w  = self.scfg.resScaleAddExpWidth
        mant_w = self.scfg.resScaleAddMantWidth
        while len(vals) > 1:
            nxt = []
            for i in range(0, len(vals), 2):
                if i + 1 < len(vals):
                    a, b = vals[i], vals[i + 1]
                    nxt.append(custom_fp_adder(
                        a[0], a[1], a[2], b[0], b[1], b[2], exp_w, mant_w))
                else:
                    nxt.append(vals[i])
            vals = nxt
        return vals[0]

    # ── Main pipeline ──────────────────────────────────────────────────────────
    def step(self,
             op_a_vec: List[int],
             op_b_vec: List[int],
             share_exp_a: int,
             share_exp_b: int,
             valid_in:  bool = True,
             reset_acc: bool = False) -> int:
        """Run one pipeline cycle. Returns accOut (32-bit FP32 uint32)."""
        if reset_acc:
            self._acc_reg = 0
            return self._acc_reg
        if not valid_in:
            return self._acc_reg

        scfg = self.scfg

        if scfg.useCustomTree:
            # Path A: per-lane CustomOp + ScaleAdd → custom tree → single ScaleToFP32
            lane_custom = []
            for i in range(self.vector_size):
                op = custom_operator(op_a_vec[i], op_b_vec[i],
                                     scfg.elementTypeA, scfg.elementTypeB,
                                     scfg.resOperatorExpWidth, scfg.resOperatorMantWidth)
                sa = scale_addition(op[0], op[1], op[2],
                                    share_exp_a, share_exp_b, scfg)
                lane_custom.append(sa)
            reduced    = self._custom_fp_reduce_tree(lane_custom)
            reduced_sum = scale_to_fp32(reduced[0], reduced[1], reduced[2], scfg)

        else:
            # Path B: per-lane CustomOp + ScaleAdd + ScaleToFP32 → FP32 tree
            lane_fp32 = []
            for i in range(self.vector_size):
                op = custom_operator(op_a_vec[i], op_b_vec[i],
                                     scfg.elementTypeA, scfg.elementTypeB,
                                     scfg.resOperatorExpWidth, scfg.resOperatorMantWidth)
                sa = scale_addition(op[0], op[1], op[2],
                                    share_exp_a, share_exp_b, scfg)
                lane_fp32.append(scale_to_fp32(sa[0], sa[1], sa[2], scfg))
            reduced_sum = self._fp32_reduce_tree(lane_fp32)

        self._acc_reg = fp32_adder(self._acc_reg, reduced_sum) & _mask(32)
        return self._acc_reg

    @property
    def acc_out(self) -> int:
        return self._acc_reg


# ═══════════════════════════════════════════════════════════════════════════════
# §13  GeMM PE Arrays  (mirrors PEArray.scala wrappers)
#
#  Both array classes share the same tileRows × tileCols connectivity:
#    PE[r][c].op_a ← op_a[r]              (vectorSize packed elements)
#    PE[r][c].op_b ← op_b[c]              (vectorSize packed elements)
#    PE[r][c].share_exp_A ← shared_exp_a[r]
#    PE[r][c].share_exp_B ← shared_exp_b[c]
#    results[r][c] = PE[r][c].accOut       (FP32)
# ═══════════════════════════════════════════════════════════════════════════════

class GemmPEArray:
    """
    tileRows × tileCols GeMM block using FDPUWithCustomReductionTree PEs.
    Matches PEArrayWrapper / PEArrayWrapperINT8 / PEArrayWrapperBF16 in PEArray.scala.

    Usage
    -----
    array = GemmPEArray(scfg, vector_size=4, tile_rows=4, tile_cols=4)
    array.reset()
    for k_step in range(K):
        # op_a[r] = list of vector_size raw-bit elements for row r
        # op_b[c] = list of vector_size raw-bit elements for col c
        array.step(op_a, op_b, shared_exp_a, shared_exp_b)
    fp32_results = array.get_results()         # [[uint32, ...], ...]
    float_results = array.get_results_float()  # [[float, ...], ...]
    """
    def __init__(self, scfg: ScaleAddConfig, vector_size: int,
                 tile_rows: int, tile_cols: int):
        self.scfg        = scfg
        self.vector_size = vector_size
        self.tile_rows   = tile_rows
        self.tile_cols   = tile_cols
        self._pes: List[List[FDPUWithCustomReductionTree]] = [
            [FDPUWithCustomReductionTree(scfg, vector_size) for _ in range(tile_cols)]
            for _ in range(tile_rows)
        ]

    def reset(self):
        for row in self._pes:
            for pe in row:
                pe.reset()

    def step(self,
             op_a: List[List[int]],   # [tile_rows][vector_size] raw element bits
             op_b: List[List[int]],   # [tile_cols][vector_size] raw element bits
             shared_exp_a: List[int], # [tile_rows] 8-bit shared scale
             shared_exp_b: List[int], # [tile_cols] 8-bit shared scale
             valid_in:  bool = True,
             reset_acc: bool = False) -> List[List[int]]:
        """One cycle. Returns results_o[r][c] as FP32 uint32 bit patterns."""
        return [
            [self._pes[r][c].step(op_a[r], op_b[c],
                                   shared_exp_a[r], shared_exp_b[c],
                                   valid_in=valid_in, reset_acc=reset_acc)
             for c in range(self.tile_cols)]
            for r in range(self.tile_rows)
        ]

    def get_results(self) -> List[List[int]]:
        """Current accumulator values as FP32 uint32 bit patterns."""
        return [[self._pes[r][c].acc_out for c in range(self.tile_cols)]
                for r in range(self.tile_rows)]

    def get_results_float(self) -> List[List[float]]:
        """Current accumulator values as Python floats."""
        return [[_fp32_to_float(self._pes[r][c].acc_out) for c in range(self.tile_cols)]
                for r in range(self.tile_rows)]


class GemmPEArrayPostScale:
    """
    tileRows × tileCols GeMM block using FDPUPostScaleReductionTree PEs.
    Same connectivity as GemmPEArray but uses the post-scale architecture with
    FixedFPReductionTree and a reduced-precision accumulator register.

    Parameters
    ----------
    K            : accumulation depth, used to derive default acc_mant_bits
    acc_mant_bits: -1 = auto (AccPrecision.recommended), else explicit 1..23
    """
    def __init__(self, scfg: ScaleAddConfig, vector_size: int,
                 tile_rows: int, tile_cols: int,
                 K: int = 32, acc_mant_bits: int = -1):
        self.scfg        = scfg
        self.vector_size = vector_size
        self.tile_rows   = tile_rows
        self.tile_cols   = tile_cols
        self._pes: List[List[FDPUPostScaleReductionTree]] = [
            [FDPUPostScaleReductionTree(scfg, vector_size, K, acc_mant_bits)
             for _ in range(tile_cols)]
            for _ in range(tile_rows)
        ]

    def reset(self):
        for row in self._pes:
            for pe in row:
                pe.reset()

    def step(self,
             op_a: List[List[int]],
             op_b: List[List[int]],
             shared_exp_a: List[int],
             shared_exp_b: List[int],
             valid_in:  bool = True,
             reset_acc: bool = False) -> List[List[int]]:
        """One cycle. Returns results_o[r][c] as FP32 uint32 bit patterns."""
        return [
            [self._pes[r][c].step(op_a[r], op_b[c],
                                   shared_exp_a[r], shared_exp_b[c],
                                   valid_in=valid_in, reset_acc=reset_acc)
             for c in range(self.tile_cols)]
            for r in range(self.tile_rows)
        ]

    def get_results(self) -> List[List[int]]:
        return [[self._pes[r][c].acc_out for c in range(self.tile_cols)]
                for r in range(self.tile_rows)]

    def get_results_float(self) -> List[List[float]]:
        return [[_fp32_to_float(self._pes[r][c].acc_out) for c in range(self.tile_cols)]
                for r in range(self.tile_rows)]


# ═══════════════════════════════════════════════════════════════════════════════
# §14  Utility: FP32 bit-pattern ↔ Python float
# ═══════════════════════════════════════════════════════════════════════════════

def _fp32_to_float(bits_val: int) -> float:
    return struct.unpack('f', struct.pack('I', bits_val & 0xFFFFFFFF))[0]

def _float_to_fp32(f: float) -> int:
    return struct.unpack('I', struct.pack('f', f))[0]

# Public aliases
fp32_bits_to_float = _fp32_to_float
float_to_fp32_bits = _float_to_fp32


# ═══════════════════════════════════════════════════════════════════════════════
# §15  Quick smoke-test  (run as  python bfp_pe_hw_model.py)
# ═══════════════════════════════════════════════════════════════════════════════
if __name__ == "__main__":
    import struct

    print("=== Smoke-test: GemmPEArray (FDPUWithCustomReductionTree) ===")

    # Config: 4×4 tile, E4M3×E4M3 / UE8M0, vector_size=4
    scfg = ScaleAddConfig(MXFormats.E4M3, MXFormats.E4M3, ScaleFormats.UE8M0)
    arr  = GemmPEArray(scfg, vector_size=4, tile_rows=4, tile_cols=4)
    arr.reset()

    # Encode +1.0 in E4M3:  sign=0, exp=7 (biased from 0→1-bias, but 1.0 → exp=7,mant=0)
    # E4M3: bias=7, 1.0 → exp_field=7, mant=0 → bits = 0_0111_000 = 0x38
    # UE8M0 scale: 1.0 → exp=127, bits = 0x7F
    e4m3_one  = 0b00111000   # +1.0 in E4M3
    ue8m0_one = 0x7F         # scale = 1.0 (exp=127, bias=127, value=2^0=1)

    op_a = [[e4m3_one] * 4 for _ in range(4)]   # each row: 4 elements of +1.0
    op_b = [[e4m3_one] * 4 for _ in range(4)]   # each col: 4 elements of +1.0
    exp_a = [ue8m0_one] * 4
    exp_b = [ue8m0_one] * 4

    # One step: dot(1,1,1,1) × scale(1×1) = 4.0 after 1 cycle
    arr.step(op_a, op_b, exp_a, exp_b, valid_in=True)
    results = arr.get_results_float()
    print(f"  After 1 cycle of dot(1×4): {results[0][0]:.4f}  (expected ~4.0)")

    # 8 more cycles → result ≈ 36.0
    for _ in range(8):
        arr.step(op_a, op_b, exp_a, exp_b, valid_in=True)
    print(f"  After 9 cycles: {arr.get_results_float()[0][0]:.4f}  (expected ~36.0)")

    print()
    print("=== Smoke-test: GemmPEArrayPostScale (FDPUPostScaleReductionTree) ===")

    arr2 = GemmPEArrayPostScale(scfg, vector_size=4, tile_rows=4, tile_cols=4,
                                K=32, acc_mant_bits=-1)
    arr2.reset()
    arr2.step(op_a, op_b, exp_a, exp_b, valid_in=True)
    print(f"  After 1 cycle of dot(1×4): {arr2.get_results_float()[0][0]:.4f}  (expected ~4.0)")
    for _ in range(8):
        arr2.step(op_a, op_b, exp_a, exp_b, valid_in=True)
    print(f"  After 9 cycles: {arr2.get_results_float()[0][0]:.4f}  (expected ~36.0)")
    print(f"  actualAccMantBits = {arr2._pes[0][0].actual_acc_mant_bits}")

    print()
    print("=== Smoke-test: INT8 × E4M3 / UE8M0 ===")
    # INT8 has implicitScaleExp=-6: INT8(1) represents the value 1×2^(-6) = 0.015625.
    # With scale_a=1 (0x7F): 4 lanes × 0.015625 × 1.0 = 0.0625
    # To get 4.0: use scale_a = 2^6 (UE8M0 field = 127+6 = 133 = 0x85)
    scfg_int8      = ScaleAddConfig(MXFormats.INT8, MXFormats.E4M3, ScaleFormats.UE8M0)
    arr3           = GemmPEArray(scfg_int8, vector_size=4, tile_rows=2, tile_cols=2)
    int8_one       = 0x01   # +1 in INT8 sign-magnitude (sign=0, raw7=1)
    op_a3          = [[int8_one]  * 4 for _ in range(2)]
    op_b3          = [[e4m3_one]  * 4 for _ in range(2)]

    arr3.reset()
    arr3.step(op_a3, op_b3, [ue8m0_one]*2, [ue8m0_one]*2)
    v1 = arr3.get_results_float()[0][0]
    assert abs(v1 - 0.0625) < 1e-6, f"Expected 0.0625, got {v1}"
    print(f"  scale_a=1  → {v1:.6f}  (correct: 4 × INT8(1)×2⁻⁶ × E4M3(1.0) = 0.0625)")

    ue8m0_scale_64 = 127 + 6   # = 133 = 0x85, represents 2^6 = 64
    arr3.reset()
    arr3.step(op_a3, op_b3, [ue8m0_scale_64]*2, [ue8m0_one]*2)
    v2 = arr3.get_results_float()[0][0]
    assert abs(v2 - 4.0) < 1e-4, f"Expected 4.0, got {v2}"
    print(f"  scale_a=64 → {v2:.4f}  (correct: 4 × INT8(1)×2⁻⁶ × 64 × E4M3(1.0) = 4.0)")

    print()
    print("All smoke-tests passed.")
