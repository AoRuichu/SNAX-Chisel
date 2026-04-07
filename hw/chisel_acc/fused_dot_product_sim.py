#!/usr/bin/env python3
"""
Bit-exact Python simulation of FusedDotProductUnit.

Mirrors the Chisel RTL pipeline:
  CustomOperator → ScaleAddition → ScaleToFP32 → FP32ReduceTree → FP32Accumulator

All integer arithmetic matches Chisel's UInt/SInt truncation and sign-extension
behaviour, including bit-exact FP32 addition and RNE rounding.

No NaN / Inf handling — consistent with the hardware.
"""

import struct
from dataclasses import dataclass, field
from typing import List, Tuple


# ============================================================
# Parameter types  (mirrors Parameter.scala)
# ============================================================

@dataclass
class ElementType:
    elementWidthExp:  int
    elementWidthMant: int
    name:             str
    implicitScaleExp: int = 0

    @property
    def totalWidth(self) -> int:
        return 1 + self.elementWidthExp + self.elementWidthMant

    @property
    def bias(self) -> int:
        if self.elementWidthExp > 0:
            return (1 << (self.elementWidthExp - 1)) - 1
        return 0


@dataclass
class ScaleType:
    expScaleWidth:  int
    mantScaleWidth: int
    name:           str

    @property
    def totalScaleWidth(self) -> int:
        return self.expScaleWidth + self.mantScaleWidth

    @property
    def bias(self) -> int:
        if self.expScaleWidth > 0:
            return (1 << (self.expScaleWidth - 1)) - 1
        return 0


class MXFormats:
    E5M2 = ElementType(5, 2, "E5M2")
    E4M3 = ElementType(4, 3, "E4M3")
    E3M2 = ElementType(3, 2, "E3M2")
    E2M3 = ElementType(2, 3, "E2M3")
    E2M1 = ElementType(2, 1, "E2M1")
    INT8 = ElementType(0, 7, "INT8", implicitScaleExp=-6)


class ScaleFormats:
    UE8M0 = ScaleType(8, 0, "UE8M0")
    UE7M1 = ScaleType(7, 1, "UE7M1")
    UE6M2 = ScaleType(6, 2, "UE6M2")
    UE5M3 = ScaleType(5, 3, "UE5M3")
    UE4M4 = ScaleType(4, 4, "UE4M4")
    UE3M5 = ScaleType(3, 5, "UE3M5")
    UE2M6 = ScaleType(2, 6, "UE2M6")


# ============================================================
# Config computed parameters  (mirrors OperatorConfig / ScaleAddConfig)
# ============================================================

class OperatorConfig:
    def __init__(self, elementTypeA: ElementType, elementTypeB: ElementType):
        self.elementTypeA = elementTypeA
        self.elementTypeB = elementTypeB

        def get_ext_mant_width(t: ElementType) -> int:
            return t.elementWidthMant if t.name == "INT8" else t.elementWidthMant + 1

        def min_adj_exp(t: ElementType) -> int:
            if t.elementWidthExp == 0:
                return t.implicitScaleExp
            return (1 - t.bias) + t.implicitScaleExp

        def sint_bits_for_neg(v: int) -> int:
            return 1 if v >= 0 else ((-v).bit_length() + 2)

        self.maxElementExp       = max(elementTypeA.elementWidthExp, elementTypeB.elementWidthExp)
        min_sum                  = min_adj_exp(elementTypeA) + min_adj_exp(elementTypeB)
        self.resOperatorExpWidth = max(self.maxElementExp + 2, sint_bits_for_neg(min_sum))
        self.resOperatorMantWidth = (get_ext_mant_width(elementTypeA) +
                                     get_ext_mant_width(elementTypeB))


class ScaleAddConfig:
    def __init__(self, elementTypeA: ElementType, elementTypeB: ElementType, stype: ScaleType):
        self.elementTypeA = elementTypeA
        self.elementTypeB = elementTypeB
        self.stype        = stype

        def get_ext_mant_width(t: ElementType) -> int:
            return t.elementWidthMant if t.name == "INT8" else t.elementWidthMant + 1

        def get_scale_mant_width(s: ScaleType) -> int:
            return 1 if s.mantScaleWidth == 0 else s.mantScaleWidth + 1

        def min_adj_exp(t: ElementType) -> int:
            if t.elementWidthExp == 0:
                return t.implicitScaleExp
            return (1 - t.bias) + t.implicitScaleExp

        def sint_bits_for_neg(v: int) -> int:
            return 1 if v >= 0 else ((-v).bit_length() + 2)

        self.maxElementExp        = max(elementTypeA.elementWidthExp, elementTypeB.elementWidthExp)
        min_sum                   = min_adj_exp(elementTypeA) + min_adj_exp(elementTypeB)
        self.resOperatorExpWidth  = max(self.maxElementExp + 2, sint_bits_for_neg(min_sum))
        self.resOperatorMantWidth = (get_ext_mant_width(elementTypeA) +
                                     get_ext_mant_width(elementTypeB))

        self.resScaleExpWidth    = stype.expScaleWidth + 2
        self.resScaleMantWidth   = get_scale_mant_width(stype) * 2
        self.maxScaleAddExp      = max(self.resOperatorExpWidth, self.resScaleExpWidth)
        self.resScaleAddExpWidth = self.maxScaleAddExp + 2
        self.resScaleAddMantWidth = 32   # fixed in RTL


# ============================================================
# Bit helpers
# ============================================================

def _mask(n: int) -> int:
    """n-bit all-ones mask."""
    return (1 << n) - 1

def _trunc(x: int, n: int) -> int:
    """Keep the lower n bits (UInt assignment in Chisel)."""
    return x & _mask(n)

def _leading_zeros(x: int, width: int) -> int:
    """
    Count leading zeros of x in a 'width'-bit field.
    Equivalent to PriorityEncoder(x.asBools.reverse) in Chisel.
    """
    if x == 0:
        return width
    return width - x.bit_length()


# ============================================================
# CustomOperator  (mirrors CustomOperator.scala)
# ============================================================

def custom_operator(inA: int, inB: int,
                    cfg: OperatorConfig) -> Tuple[int, int, int]:
    """
    Element-wise product of two MX elements.

    Returns (outSign: 0|1, outExp: signed int, outMant: unsigned int)
    where outMant is truncated to cfg.resOperatorMantWidth bits.
    """
    def get_extended_mantissa(inp: int, etype: ElementType):
        sign = (inp >> (etype.totalWidth - 1)) & 1
        if etype.name == "INT8":
            # 2's complement: negate lower 7 bits when sign bit is set (mirrors hardware)
            raw7 = inp & _mask(etype.elementWidthMant)
            magnitude = (-raw7) & _mask(etype.elementWidthMant) if sign else raw7
            return sign, 0, magnitude
        else:
            exp  = (inp >> etype.elementWidthMant) & _mask(etype.elementWidthExp)
            mant = inp & _mask(etype.elementWidthMant)
            implicit = 1 if exp != 0 else 0          # 0 for subnormal
            full_mant = (implicit << etype.elementWidthMant) | mant
            return sign, exp, full_mant

    signA, expA, fullMantA = get_extended_mantissa(inA, cfg.elementTypeA)
    signB, expB, fullMantB = get_extended_mantissa(inB, cfg.elementTypeB)

    def adjusted_exp(exp_raw: int, etype: ElementType) -> int:
        if etype.elementWidthExp == 0:
            # INT8: exponent field is always 0; only implicit scale applies
            return etype.implicitScaleExp
        else:
            # Normal formats: subnormal exponent is fixed to 1-bias
            raw = (1 - etype.bias) if exp_raw == 0 else (exp_raw - etype.bias)
            return raw + etype.implicitScaleExp

    adj_a = adjusted_exp(expA, cfg.elementTypeA)
    adj_b = adjusted_exp(expB, cfg.elementTypeB)

    out_sign = signA ^ signB
    out_exp  = adj_a + adj_b                          # signed Python int
    product  = fullMantA * fullMantB
    out_mant = _trunc(product, cfg.resOperatorMantWidth)

    return out_sign, out_exp, out_mant


# ============================================================
# ScaleAddition  (mirrors ScaleAddition.scala)
# ============================================================

def scale_addition(in_op_sign: int, in_op_exp: int, in_op_mant: int,
                   in_share_scale_a: int, in_share_scale_b: int,
                   scfg: ScaleAddConfig) -> Tuple[int, int, int]:
    """
    Multiply the operator result by the two shared MX scale factors.

    Returns (outSign: 0|1, outExp: signed int, outMant: unsigned int)
    where outMant is truncated to scfg.resScaleAddMantWidth (== 32) bits.
    """
    stype = scfg.stype

    def get_scaled_parts(inp: int, st: ScaleType) -> Tuple[int, int]:
        exp = (inp >> st.mantScaleWidth) & _mask(st.expScaleWidth)
        if st.mantScaleWidth == 0:
            full_mant = 1                             # UE8M0: implicit-only mantissa
        else:
            mant = inp & _mask(st.mantScaleWidth)
            implicit = 1 if exp != 0 else 0
            full_mant = (implicit << st.mantScaleWidth) | mant
        return exp, full_mant

    exp_sa, mant_sa = get_scaled_parts(in_share_scale_a, stype)
    exp_sb, mant_sb = get_scaled_parts(in_share_scale_b, stype)

    def adj_scale_exp(exp_raw: int, st: ScaleType) -> int:
        # Subnormal scale (exp=0 with explicit mantissa bits): fixed to 1-bias
        if st.mantScaleWidth > 0 and exp_raw == 0:
            return 1 - st.bias
        return exp_raw - st.bias

    scale_exp  = adj_scale_exp(exp_sa, stype) + adj_scale_exp(exp_sb, stype)
    scale_mant = mant_sa * mant_sb

    out_sign = in_op_sign
    out_exp  = scale_exp + in_op_exp
    out_mant = _trunc(scale_mant * in_op_mant, scfg.resScaleAddMantWidth)

    return out_sign, out_exp, out_mant


# ============================================================
# ScaleToFP32  (mirrors ScaleToFP32.scala)
# ============================================================

def scale_to_fp32(in_sign: int, in_exp: int, in_mant: int,
                  scfg: ScaleAddConfig) -> int:
    """
    Normalize and convert (sign, biased_exp, wide_mant) to IEEE-754 FP32
    with RNE rounding.

    Returns a 32-bit unsigned integer (bit pattern).
    """
    def elem_frac(t: ElementType) -> int:
        return 0 if t.name == "INT8" else t.elementWidthMant

    frac_bits  = (elem_frac(scfg.elementTypeA) + elem_frac(scfg.elementTypeB) +
                  2 * scfg.stype.mantScaleWidth)
    mant_width = scfg.resScaleAddMantWidth   # 32
    exp_bias   = 127 + mant_width - 1 - frac_bits

    # Zero check
    if in_mant == 0:
        return 0

    # Leading-zero count on the mant_width-bit mantissa
    lzc          = _leading_zeros(in_mant, mant_width)
    shifted_mant = _trunc(in_mant << lzc, mant_width)

    # EXTRA padding so that mant23 / guard / round / sticky fields always exist.
    # For mant_width == 32, EXTRA == 0.
    EXTRA = max(0, 27 - mant_width)
    if EXTRA > 0:
        padded_shift = _trunc(shifted_mant << EXTRA, mant_width + EXTRA)
    else:
        padded_shift = shifted_mant

    # safe_extract_pos: bit index of the first fractional bit (just below implicit-1)
    safe_extract_pos = EXTRA + mant_width - 2   # == 30 when mant_width == 32

    # 23-bit mantissa field, guard, round, sticky
    mant23    = (padded_shift >> (safe_extract_pos - 22)) & _mask(23)
    guard_bit = (padded_shift >> (safe_extract_pos - 23)) & 1
    round_bit = (padded_shift >> (safe_extract_pos - 24)) & 1
    # Sticky = any bit in [safe_extract_pos-25 : 0]  OR  lzc overflowed the field
    low_bits   = padded_shift & _mask(safe_extract_pos - 24)   # bits below round_bit
    sticky_bit = int(low_bits != 0) | int(lzc > mant_width - 1)

    # RNE round-up condition
    round_up = int(guard_bit and (mant23 & 1 or round_bit or sticky_bit))
    rounded_m   = mant23 + round_up              # up to 24 bits; bit 23 = carry
    round_carry = (rounded_m >> 23) & 1
    final_mant  = rounded_m & _mask(23)

    # Biased exponent (Chisel: inExp - lzc + expBias + roundCarry)
    adj_exp = in_exp - lzc + exp_bias + round_carry

    if adj_exp >= 255:
        final_exp = 255
    elif adj_exp <= 0:
        final_exp = 0
    else:
        final_exp = adj_exp & 0xFF

    return (in_sign << 31) | (final_exp << 23) | final_mant


# ============================================================
# FP32Adder  (mirrors FP32Adder in FusedDotProductUnit.scala)
# ============================================================

def fp32_adder(a: int, b: int) -> int:
    """
    Custom IEEE-754 single-precision adder with RNE rounding.
    No NaN / Inf handling (consistent with the hardware).
    Inputs and output are 32-bit unsigned integers (bit patterns).
    """
    # Unpack operands
    val_a_s = (a >> 31) & 1
    val_a_e = (a >> 23) & 0xFF
    val_a_m = ((1 if val_a_e else 0) << 23) | (a & _mask(23))   # 24-bit with implicit-1

    val_b_s = (b >> 31) & 1
    val_b_e = (b >> 23) & 0xFF
    val_b_m = ((1 if val_b_e else 0) << 23) | (b & _mask(23))

    # Which operand has larger magnitude?
    exp_diff  = val_a_e - val_b_e                                 # signed
    if exp_diff > 0:
        a_greater = True
    elif exp_diff == 0:
        a_greater = val_a_m >= val_b_m
    else:
        a_greater = False

    far_e = val_a_e if a_greater else val_b_e
    far_m = val_a_m if a_greater else val_b_m
    far_s = val_a_s if a_greater else val_b_s
    near_m = val_b_m if a_greater else val_a_m

    abs_diff = abs(exp_diff)

    # nearM with 3 guard bits appended (27-bit value)
    near_m27 = _trunc(near_m << 3, 27)

    # Align near operand: logical right-shift
    aligned_near_m = near_m27 >> abs_diff          # Python ints: safe for large shifts

    # Sticky: any bit of near_m27 shifted past bit 0
    if abs_diff == 0:
        sticky_from_align = 0
    else:
        lost_mask = _mask(min(abs_diff, 27))
        sticky_from_align = int((near_m27 & lost_mask) != 0)

    # far operand with 3 guard zeros (27 bits)
    far_m27 = _trunc(far_m << 3, 27)

    # Add or subtract (result is 28 bits)
    is_sub = val_a_s ^ val_b_s
    if is_sub:
        res_mag = _trunc(far_m27 - aligned_near_m, 28)
    else:
        res_mag = _trunc(far_m27 + aligned_near_m, 28)

    res_sign = far_s

    # Exact cancellation → zero
    if res_mag == 0:
        return 0

    # Normalize: left-shift so that implicit-1 lands at bit 27
    lzc        = _leading_zeros(res_mag, 28)
    norm_shift = res_mag << lzc                    # wider than 28 bits is fine

    # Extract mantissa / guard / round / sticky from the shifted result
    # Bit layout after normalization: bit27=implicit-1, bits[26:4]=mant, bit3=G, bit2=R, bits[1:0]=S
    mant23    = (norm_shift >> 4) & _mask(23)
    guard_bit = (norm_shift >> 3) & 1
    round_bit = (norm_shift >> 2) & 1
    sticky_bit = int((norm_shift & _mask(2)) != 0) | sticky_from_align

    # RNE rounding
    round_up  = int(guard_bit and (mant23 & 1 or round_bit or sticky_bit))
    rounded_m = mant23 + round_up                  # up to 24 bits
    mant_carry = (rounded_m >> 23) & 1
    final_m   = rounded_m & _mask(23)

    # Chisel: finalE = farExp.zext - resLZC.asSInt + 1.S + mantCarry.zext
    final_e = far_e - lzc + 1 + mant_carry
    final_e = max(0, min(255, final_e))            # clamp (hardware doesn't, but range is safe)

    return (res_sign << 31) | (final_e << 23) | final_m


# ============================================================
# FP32 balanced reduction tree
# ============================================================

def fp32_reduce_tree(inputs: List[int]) -> int:
    """
    Balanced binary tree of FP32Adder instances.
    Odd-length input: the last element passes through unmodified (no adder).
    """
    if len(inputs) == 1:
        return inputs[0]
    next_level = []
    for i in range(0, len(inputs), 2):
        if i + 1 < len(inputs):
            next_level.append(fp32_adder(inputs[i], inputs[i + 1]))
        else:
            next_level.append(inputs[i])          # odd lane passes through
    return fp32_reduce_tree(next_level)


# ============================================================
# FusedDotProductUnit — one combinational cycle
# ============================================================

def fused_dot_product_unit(
    op_a_packed:    int,
    op_b_packed:    int,
    share_exp_a:    int,
    share_exp_b:    int,
    scfg:           ScaleAddConfig,
    vector_size:    int,
    acc_reg:        int  = 0,
    valid_in:       bool = True,
    reset_acc:      bool = False,
) -> Tuple[int, int, List[int]]:
    """
    Simulate one clock cycle of FusedDotProductUnit.

    Inputs
    ------
    op_a_packed  : packed bit vector [vectorSize*wA-1 : 0]; lane i at bits [i*wA +: wA]
    op_b_packed  : packed bit vector [vectorSize*wB-1 : 0]
    share_exp_a  : shared MX scale for A (stype.totalScaleWidth bits)
    share_exp_b  : shared MX scale for B
    scfg         : ScaleAddConfig
    vector_size  : number of parallel MAC lanes
    acc_reg      : current accumulator value (FP32 bit pattern), default 0
    valid_in     : drive the validIn control signal
    reset_acc    : drive the resetAcc control signal (synchronous)

    Returns
    -------
    (new_acc_reg, reduced_sum, lane_fp32_results)
      new_acc_reg      : updated accumulator (FP32 bit pattern)
      reduced_sum      : output of the FP32 reduction tree (FP32 bit pattern)
      lane_fp32_results: list of per-lane FP32 bit patterns
    """
    wA = scfg.elementTypeA.totalWidth
    wB = scfg.elementTypeB.totalWidth
    op_cfg = OperatorConfig(scfg.elementTypeA, scfg.elementTypeB)

    lane_results: List[int] = []
    for i in range(vector_size):
        inA = (op_a_packed >> (i * wA)) & _mask(wA)
        inB = (op_b_packed >> (i * wB)) & _mask(wB)

        # Stage 1: CustomOperator
        op_sign, op_exp, op_mant = custom_operator(inA, inB, op_cfg)

        # Stage 2: ScaleAddition
        sa_sign, sa_exp, sa_mant = scale_addition(
            op_sign, op_exp, op_mant,
            share_exp_a, share_exp_b, scfg
        )

        # Stage 3: ScaleToFP32
        fp32_val = scale_to_fp32(sa_sign, sa_exp, sa_mant, scfg)
        lane_results.append(fp32_val)

    # Stage 4: FP32 balanced reduction tree
    reduced_sum = fp32_reduce_tree(lane_results)

    # Stage 5: FP32 accumulator register logic
    if reset_acc:
        new_acc = 0
    elif valid_in:
        new_acc = fp32_adder(acc_reg, reduced_sum)
    else:
        new_acc = acc_reg

    return new_acc, reduced_sum, lane_results


# ============================================================
# Utility helpers
# ============================================================

def fp32_bits_to_float(bits: int) -> float:
    """Convert a 32-bit FP32 bit pattern to Python float."""
    return struct.unpack('f', struct.pack('I', bits & 0xFFFFFFFF))[0]

def float_to_fp32_bits(f: float) -> int:
    """Convert a Python float to its 32-bit FP32 bit pattern."""
    return struct.unpack('I', struct.pack('f', f))[0]

def _round_half_up(x: float) -> int:
    """Round-half-up, matching Scala/Java math.round() semantics.
    Python's built-in round() uses banker's rounding (round-half-to-even),
    which differs from Java's round-half-up for x.5 values."""
    import math
    return int(math.floor(x + 0.5))


# ============================================================
# MX encode / decode helpers  (mirrors Scala test helpers)
# ============================================================

def decode_element(raw: int, etype: ElementType) -> float:
    """Decode a raw MX element bit-pattern to float64.  Matches Scala decodeElement."""
    import math
    if etype.name == "INT8":
        # 2's complement: treat as signed 8-bit integer
        signed_val = raw if raw < 128 else raw - 256
        val = signed_val * (2 ** etype.implicitScaleExp)
    else:
        sign  = -1.0 if ((raw >> (etype.totalWidth - 1)) & 1) else 1.0
        exp   = (raw >> etype.elementWidthMant) & _mask(etype.elementWidthExp)
        mant  = raw & _mask(etype.elementWidthMant)
        if exp == 0:
            val = sign * (mant / (1 << etype.elementWidthMant)) * (2 ** (1 - etype.bias))
        else:
            val = sign * (1.0 + mant / (1 << etype.elementWidthMant)) * (2 ** (exp - etype.bias))
    return val


def decode_scale(raw: int, stype: ScaleType) -> float:
    """Decode a raw MX scale bit-pattern to float64.  Matches Scala decodeScale."""
    import math
    exp_bits  = (raw >> stype.mantScaleWidth) & _mask(stype.expScaleWidth)
    if stype.mantScaleWidth == 0:
        return 2.0 ** (exp_bits - stype.bias)
    mant_bits = raw & _mask(stype.mantScaleWidth)
    implicit1 = 1.0 if exp_bits > 0 else 0.0
    eff_exp   = (exp_bits - stype.bias) if exp_bits > 0 else (1 - stype.bias)
    return (implicit1 + mant_bits / (1 << stype.mantScaleWidth)) * (2.0 ** eff_exp)


def encode_element(value: float, etype: ElementType) -> int:
    """Encode a float64 to MX element raw bits.  Matches Scala encodeElement.
    Uses round-half-up (_round_half_up) to match Java/Scala math.round() semantics."""
    import math
    if value == 0.0:
        return 0
    sign   = 1 if value < 0 else 0
    abs_v  = abs(value)
    if etype.name == "INT8":
        magnitude = min(_round_half_up(abs_v / (2 ** etype.implicitScaleExp)), 127)
        return magnitude if sign == 0 else (-magnitude) & 0xFF  # 2's complement
    else:
        exp_unbiased = int(math.floor(math.log2(abs_v)))
        exp_biased   = exp_unbiased + etype.bias
        if exp_biased <= 0:
            mant_int = min(_round_half_up(abs_v / (2 ** (1 - etype.bias)) * (1 << etype.elementWidthMant)),
                          (1 << etype.elementWidthMant) - 1)
            return (sign << (etype.totalWidth - 1)) | mant_int
        else:
            exp_clamped = min(exp_biased, (1 << etype.elementWidthExp) - 1)
            mant_int    = max(0, min(_round_half_up((abs_v / (2 ** exp_unbiased) - 1.0) * (1 << etype.elementWidthMant)),
                                     (1 << etype.elementWidthMant) - 1))
            return (sign << (etype.totalWidth - 1)) | (exp_clamped << etype.elementWidthMant) | mant_int


def encode_scale(value: float, stype: ScaleType) -> int:
    """Encode a positive float64 to MX scale raw bits.  Matches Scala encodeScale."""
    import math
    exp_unbiased = int(math.floor(math.log2(value)))
    exp_biased   = max(0, min(exp_unbiased + stype.bias, (1 << stype.expScaleWidth) - 1))
    if stype.mantScaleWidth == 0:
        return exp_biased
    mant_int = max(0, min(_round_half_up((value / (2 ** exp_unbiased) - 1.0) * (1 << stype.mantScaleWidth)),
                           (1 << stype.mantScaleWidth) - 1))
    return (exp_biased << stype.mantScaleWidth) | mant_int


def max_elem_repr(etype: ElementType) -> float:
    """Maximum representable (positive) value for an ElementType."""
    if etype.name == "INT8":
        return ((1 << (etype.totalWidth - 1)) - 1) * (2 ** etype.implicitScaleExp)
    max_exp_biased = (1 << etype.elementWidthExp) - 2
    max_mant       = (1 << etype.elementWidthMant) - 1
    import math
    return (1.0 + max_mant / (1 << etype.elementWidthMant)) * (2 ** (max_exp_biased - etype.bias))


def quantize_block(values: List[float], etype: ElementType, stype: ScaleType) -> Tuple[List[int], int]:
    """
    MX block quantization: find shared scale, encode all elements.
    Matches Scala quantizeBlock exactly.
    """
    import math
    max_abs = max(abs(v) for v in values)
    max_abs = max(max_abs, 1e-38)
    if etype.name == "INT8":
        scale_exp = int(math.floor(math.log2(max_abs)))
    else:
        ideal_scale = max_abs / max_elem_repr(etype)
        ideal_scale = max(ideal_scale, 1e-38)
        scale_exp   = int(math.ceil(math.log2(ideal_scale)))
    min_scale_exp = -stype.bias
    max_scale_exp = (1 << stype.expScaleWidth) - 1 - stype.bias
    clamped_exp   = max(min_scale_exp, min(max_scale_exp, scale_exp))
    raw_scale     = encode_scale(2.0 ** clamped_exp, stype)
    decoded_scale = decode_scale(raw_scale, stype)
    raw_elems     = [encode_element(v / decoded_scale, etype) for v in values]
    return raw_elems, raw_scale


def pack_elements(elems: List[int], width: int) -> int:
    """Pack element raw values into a single integer (element 0 at LSB)."""
    result = 0
    mask   = _mask(width)
    for i, v in enumerate(elems):
        result |= (v & mask) << (i * width)
    return result


# ============================================================
# Accumulation test runner  (mirrors Scala runCycles)
# ============================================================

def sw_cycle_val(as_raw: List[int], bs_raw: List[int], scale_a: int, scale_b: int,
                 scfg: ScaleAddConfig) -> float:
    """
    Software golden model for one cycle: simple float64 dot-product + scale.
    Matches Scala swFusedProduct (returns float32).
    """
    sA = decode_scale(scale_a, scfg.stype)
    sB = decode_scale(scale_b, scfg.stype)
    products = [decode_element(a, scfg.elementTypeA) * decode_element(b, scfg.elementTypeB)
                for a, b in zip(as_raw, bs_raw)]
    return float(struct.unpack('f', struct.pack('f', sum(products) * sA * sB))[0])


def run_accumulation_test(
    test_name: str,
    scfg: ScaleAddConfig,
    vector_size: int,
    cycles: List[Tuple[List[int], List[int], int, int]],
    tol_pct: float = 0.10,
    tol_abs: float = 1e-5,
    verbose: bool = True,
) -> bool:
    """
    Bit-exact accumulation test matching Scala's runCycles.

    cycles: list of (as_list, bs_list, raw_scale_a, raw_scale_b)

    Runs fused_dot_product_unit cycle-by-cycle (acc_reg threading),
    compares the hardware-exact FP32 accumulator against the SW golden
    (simple float32 running sum, same as Scala), and reports per-cycle results.

    Returns True if all cycles pass within tolerance.
    """
    if verbose:
        print("=" * 70)
        print(f"[TEST] {test_name}")
        print("=" * 70)

    acc_reg  = 0          # FP32 bit pattern of running accumulator
    sw_acc   = 0.0        # SW golden (float32, same as Scala swAcc)
    all_pass = True

    for i, (as_raw, bs_raw, sA, sB) in enumerate(cycles):
        wA      = scfg.elementTypeA.totalWidth
        wB      = scfg.elementTypeB.totalWidth
        op_a    = pack_elements(as_raw, wA)
        op_b    = pack_elements(bs_raw, wB)

        # Bit-exact Python/hardware simulation
        new_acc, reduced_sum, lane_fp32 = fused_dot_product_unit(
            op_a, op_b, sA, sB, scfg, vector_size,
            acc_reg=acc_reg, valid_in=True, reset_acc=False,
        )
        acc_reg = new_acc

        # SW golden (same as Scala: simple float32 accumulation)
        cycle_val = sw_cycle_val(as_raw, bs_raw, sA, sB, scfg)
        sw_acc    = struct.unpack('f', struct.pack('f', sw_acc + cycle_val))[0]

        hw_f   = fp32_bits_to_float(acc_reg)
        tol    = abs(sw_acc) * tol_pct + tol_abs
        passed = abs(hw_f - sw_acc) <= tol

        if not passed:
            all_pass = False

        if verbose:
            status = "OK  " if passed else "FAIL"
            print(f"  Cycle {i:2d} [{status}]  HW=0x{acc_reg:08X} ({hw_f:+.6e})  "
                  f"SW={sw_acc:+.6e}  diff={abs(hw_f - sw_acc):.2e}  tol={tol:.2e}")
            if not passed:
                print(f"           *** MISMATCH at cycle {i}: hw={hw_f} expected={sw_acc} ***")

    if verbose:
        result_str = "PASSED" if all_pass else "FAILED"
        print(f"[{result_str}] {test_name}\n")

    return all_pass


# ============================================================
# Accumulation tests  (mirror Scala FusedDotProductUnitTest)
# ============================================================

def _default_scfg():
    return ScaleAddConfig(MXFormats.E4M3, MXFormats.E2M1, ScaleFormats.UE5M3)


def test9_vec4_multicycle_4x1():
    """Test 9: vec=4 multi-cycle (4 cycles × all-ones → 16.0) — E4M3×E2M1/UE5M3"""
    scfg  = _default_scfg()
    e4m3_1  = encode_element(1.0, MXFormats.E4M3)
    e2m1_1  = encode_element(1.0, MXFormats.E2M1)
    ue5m3_1 = encode_scale(1.0, ScaleFormats.UE5M3)
    cycles = [(
        [e4m3_1] * 4, [e2m1_1] * 4, ue5m3_1, ue5m3_1
    )] * 4
    return run_accumulation_test("Test 9: vec=4 multi-cycle (4×1.0 → 16.0)", scfg, 4, cycles)


def test10_vec4_mixed_multicycle():
    """Test 10: vec=4 mixed-value multi-cycle — E4M3×E2M1/UE5M3"""
    scfg     = _default_scfg()
    e4m3_1   = encode_element(1.0,  MXFormats.E4M3)
    e4m3_n1  = encode_element(-1.0, MXFormats.E4M3)
    e4m3_2   = encode_element(2.0,  MXFormats.E4M3)
    e4m3_1p5 = encode_element(1.5,  MXFormats.E4M3)
    e2m1_1   = encode_element(1.0,  MXFormats.E2M1)
    e2m1_2   = encode_element(2.0,  MXFormats.E2M1)
    ue5m3_1  = encode_scale(1.0, ScaleFormats.UE5M3)
    ue5m3_2  = encode_scale(2.0, ScaleFormats.UE5M3)
    cycles = [
        # cycle 0: [1, 2, -1, 1] × [1,1,1,1] scale 1×1 → sum = 3.0
        ([e4m3_1, e4m3_2, e4m3_n1, e4m3_1], [e2m1_1]*4, ue5m3_1, ue5m3_1),
        # cycle 1: [1,1,1,1] × [2,2,2,2] scale 1×1 → sum = 8.0
        ([e4m3_1]*4, [e2m1_2]*4, ue5m3_1, ue5m3_1),
        # cycle 2: [-1,-1,-1,-1] × [1,1,1,1] scale 2×1 → sum = -8.0
        ([e4m3_n1]*4, [e2m1_1]*4, ue5m3_2, ue5m3_1),
        # cycle 3: [1,1.5,-1,2] × [1,1,1,1] scale 1×1 → sum = 3.5
        ([e4m3_1, e4m3_1p5, e4m3_n1, e4m3_2], [e2m1_1]*4, ue5m3_1, ue5m3_1),
    ]
    return run_accumulation_test("Test 10: vec=4 mixed-value multi-cycle", scfg, 4, cycles)


def test11_e5m2_e4m3_ue8m0():
    """Test 11: E5M2×E4M3/UE8M0 vec=4 3-cycle mixed"""
    cfg = ScaleAddConfig(MXFormats.E5M2, MXFormats.E4M3, ScaleFormats.UE8M0)
    raw_cycles = [
        ([1.0, 2.0, -1.0, 1.5], [1.0, 1.0,  2.0, 1.0], 1.0, 1.0),
        ([1.0, 1.0,  1.0, 1.0], [2.0, 1.0, -1.0, 2.0], 2.0, 1.0),
        ([2.0, 1.5,  1.0,-1.0], [1.0, 2.0,  1.0, 1.0], 1.0, 2.0),
    ]
    cycles = [([encode_element(v, cfg.elementTypeA) for v in a],
               [encode_element(v, cfg.elementTypeB) for v in b],
               encode_scale(sA, cfg.stype), encode_scale(sB, cfg.stype))
              for a, b, sA, sB in raw_cycles]
    return run_accumulation_test("Test 11: E5M2×E4M3/UE8M0 vec=4 3-cycle mixed", cfg, 4, cycles)


def test12_e3m2_e2m3_ue6m2():
    """Test 12: E3M2×E2M3/UE6M2 vec=4 4-cycle fractional mantissa"""
    cfg = ScaleAddConfig(MXFormats.E3M2, MXFormats.E2M3, ScaleFormats.UE6M2)
    raw_cycles = [
        ([1.0,  1.25, -1.5, 2.0],  [1.0,  1.0,  1.0, 1.0],  1.0, 1.0),
        ([1.5,  1.0,   1.0, 1.0],  [1.25, 1.0, -1.0, 1.5],  1.0, 1.5),
        ([2.0, -1.0,   1.5, 1.0],  [1.0,  2.0,  1.0, 1.0],  1.5, 1.0),
        ([-1.0, 1.0,  -1.0, 1.0],  [1.0,  1.0,  1.5, 1.0],  2.0, 2.0),
    ]
    cycles = [([encode_element(v, cfg.elementTypeA) for v in a],
               [encode_element(v, cfg.elementTypeB) for v in b],
               encode_scale(sA, cfg.stype), encode_scale(sB, cfg.stype))
              for a, b, sA, sB in raw_cycles]
    return run_accumulation_test("Test 12: E3M2×E2M3/UE6M2 vec=4 4-cycle fractional", cfg, 4, cycles)


def test17_e5m2_e5m2_ue8m0():
    """Test 17: E5M2×E5M2/UE8M0 vec=4 6-cycle"""
    cfg = ScaleAddConfig(MXFormats.E5M2, MXFormats.E5M2, ScaleFormats.UE8M0)
    # Pre-encode constants exactly as Scala does
    def e(v):  return encode_element(v, MXFormats.E5M2)
    def s(v):  return encode_scale(v, ScaleFormats.UE8M0)
    cycles = [
        ([e(1.5),  e(-0.75), e(2.0),   e(-1.5) ], [e(-0.5), e(1.75),  e(-1.0),  e(0.75)], s(2.0), s(1.0)),
        ([e(0.75), e(1.0),   e(-1.5),  e(0.5)  ], [e(2.0),  e(-1.5),  e(0.75),  e(-1.0)], s(1.0), s(0.5)),
        ([e(-2.0), e(1.5),   e(0.75),  e(-1.0) ], [e(1.0),  e(-0.75), e(1.5),   e(2.0) ], s(0.5), s(2.0)),
        ([e(1.75), e(-0.5),  e(-1.0),  e(1.5)  ], [e(-1.5), e(1.0),   e(2.0),   e(-0.75)],s(4.0), s(1.0)),
        ([e(-0.75),e(2.0),   e(1.0),   e(-1.75)], [e(0.75), e(-2.0),  e(1.5),   e(1.0) ], s(1.0), s(4.0)),
        ([e(1.0),  e(-1.0),  e(1.5),   e(-2.0) ], [e(-1.0), e(1.5),   e(-0.75), e(1.0) ], s(2.0), s(2.0)),
    ]
    return run_accumulation_test("Test 17: E5M2×E5M2/UE8M0 vec=4 6-cycle", cfg, 4, cycles)


def test18_mx_block_e5m2_e4m3():
    """Test 18: MX block-quantized 32-element dot product, E5M2×E4M3/UE8M0 vec=8 4 cycles"""
    cfg       = ScaleAddConfig(MXFormats.E5M2, MXFormats.E4M3, ScaleFormats.UE8M0)
    vsize     = 8
    block_size= 32
    rawA = [ 3.2, -1.6,  0.8,  4.0, -2.4,  1.2, -0.6,  3.6,
             2.0, -3.0,  1.5, -0.5,  2.5, -1.0,  0.4,  1.8,
            -2.2,  0.9, -1.4,  3.1, -0.7,  2.8, -1.1,  0.3,
             1.7, -2.9,  0.6, -1.3,  2.1, -0.8,  1.9, -3.5]
    rawB = [ 1.0,  2.0, -1.0,  0.5,  1.5, -0.5,  2.0, -1.0,
             0.75,-1.25, 1.5,  2.0, -0.75, 1.0, -2.0,  0.5,
             2.0, -1.0,  0.5, -1.5,  1.0,  0.25,-1.0,  2.0,
            -0.5,  1.5, -2.0,  1.0,  0.5, -1.5,  1.0, -0.25]
    enc_a, raw_sA = quantize_block(rawA, cfg.elementTypeA, cfg.stype)
    enc_b, raw_sB = quantize_block(rawB, cfg.elementTypeB, cfg.stype)
    cycles = [(enc_a[c*vsize:(c+1)*vsize], enc_b[c*vsize:(c+1)*vsize], raw_sA, raw_sB)
              for c in range(block_size // vsize)]
    return run_accumulation_test(
        "Test 18: MX block-quantized E5M2×E4M3/UE8M0 vec=8 4-cycle", cfg, vsize, cycles)


def test19_mx_block_e5m2_e2m1():
    """Test 19: MX block-quantized 32-element dot product, E5M2×E2M1/UE8M0 vec=8 4 cycles"""
    cfg        = ScaleAddConfig(MXFormats.E5M2, MXFormats.E2M1, ScaleFormats.UE8M0)
    vsize      = 8
    block_size = 32
    rawA = [ 3.2, -1.6,  0.8,  4.0, -2.4,  1.2, -0.6,  3.6,
             2.0, -3.0,  1.5, -0.5,  2.5, -1.0,  0.4,  1.8,
            -2.2,  0.9, -1.4,  3.1, -0.7,  2.8, -1.1,  0.3,
             1.7, -2.9,  0.6, -1.3,  2.1, -0.8,  1.9, -3.5]
    rawB = [ 1.0,  2.0, -1.0,  0.5,  1.5, -0.5,  2.0, -1.0,
             0.75,-1.25, 1.5,  2.0, -0.75, 1.0, -2.0,  0.5,
             2.0, -1.0,  0.5, -1.5,  1.0,  0.25,-1.0,  2.0,
            -0.5,  1.5, -2.0,  1.0,  0.5, -1.5,  1.0, -0.25]
    enc_a, raw_sA = quantize_block(rawA, cfg.elementTypeA, cfg.stype)
    enc_b, raw_sB = quantize_block(rawB, cfg.elementTypeB, cfg.stype)
    cycles = [(enc_a[c*vsize:(c+1)*vsize], enc_b[c*vsize:(c+1)*vsize], raw_sA, raw_sB)
              for c in range(block_size // vsize)]
    return run_accumulation_test(
        "Test 19: MX block-quantized E5M2×E2M1/UE8M0 vec=8 4-cycle", cfg, vsize, cycles)


class _JavaRandom:
    """
    Pure-Python reimplementation of java.util.Random (the same PRNG that
    scala.util.Random wraps) so that test 20 generates bit-identical data
    to the Scala hardware test.

    Implements nextDouble() and nextGaussian() using the same algorithm as
    the JDK source, including Box-Muller transform with StrictMath semantics.
    """
    _MASK48 = (1 << 48) - 1
    _MULT   = 0x5DEECE66D
    _ADD    = 0xB

    def __init__(self, seed: int):
        self._seed          = (seed ^ self._MULT) & self._MASK48
        self._have_next     = False
        self._next_gaussian = 0.0

    def _next(self, bits: int) -> int:
        self._seed = (self._seed * self._MULT + self._ADD) & self._MASK48
        return self._seed >> (48 - bits)

    def next_double(self) -> float:
        return (((self._next(26) << 27) + self._next(27)) / (1 << 53))

    def next_gaussian(self) -> float:
        """Box-Muller, identical to java.util.Random.nextGaussian()."""
        import math
        if self._have_next:
            self._have_next = False
            return self._next_gaussian
        while True:
            v1 = 2.0 * self.next_double() - 1.0
            v2 = 2.0 * self.next_double() - 1.0
            s  = v1 * v1 + v2 * v2
            if 0.0 < s < 1.0:
                break
        multiplier          = math.sqrt(-2.0 * math.log(s) / s)
        self._next_gaussian = v2 * multiplier
        self._have_next     = True
        return v1 * multiplier


def test20_e5m2_e2m1_multiblock_32cycles():
    """Test 20: E5M2×E2M1/UE8M0 multi-block accumulation (vec=8, 32 cycles).
    Uses _JavaRandom(1234) to replicate scala.util.Random(1234).nextGaussian()
    and produce bit-identical data to the Scala hardware test."""
    cfg        = ScaleAddConfig(MXFormats.E5M2, MXFormats.E2M1, ScaleFormats.UE8M0)
    vsize      = 8
    block_size = 32
    rng        = _JavaRandom(1234)

    all_cycles = []
    for _ in range(8):
        a_vals = [rng.next_gaussian() * 2.0 for _ in range(block_size)]
        b_vals = [rng.next_gaussian() * 1.5 for _ in range(block_size)]
        enc_a, raw_sA = quantize_block(a_vals, cfg.elementTypeA, cfg.stype)
        enc_b, raw_sB = quantize_block(b_vals, cfg.elementTypeB, cfg.stype)
        for c in range(block_size // vsize):
            s = c * vsize
            all_cycles.append((enc_a[s:s+vsize], enc_b[s:s+vsize], raw_sA, raw_sB))

    return run_accumulation_test(
        "Test 20: E5M2×E2M1/UE8M0 multi-block 32-cycle", cfg, vsize, all_cycles)


def run_all_tests() -> None:
    """Run all accumulation tests and print a summary."""
    tests = [
        test9_vec4_multicycle_4x1,
        test10_vec4_mixed_multicycle,
        test11_e5m2_e4m3_ue8m0,
        test12_e3m2_e2m3_ue6m2,
        test17_e5m2_e5m2_ue8m0,
        test18_mx_block_e5m2_e4m3,
        test19_mx_block_e5m2_e2m1,
        test20_e5m2_e2m1_multiblock_32cycles,
    ]
    results = []
    for fn in tests:
        try:
            passed = fn()
        except Exception as exc:
            print(f"  EXCEPTION in {fn.__name__}: {exc}")
            passed = False
        results.append((fn.__name__, passed))

    print("\n" + "=" * 70)
    print("SUMMARY")
    print("=" * 70)
    for name, ok in results:
        print(f"  {'PASS' if ok else 'FAIL'}  {name}")
    total  = len(results)
    passed = sum(1 for _, ok in results if ok)
    print(f"\n{passed}/{total} tests passed.")
    if passed < total:
        raise SystemExit(1)


# ============================================================
# Self-test / usage example
# ============================================================

if __name__ == "__main__":
    # ----------------------------------------------------------------
    # Config: INT8 × E2M1 / UE8M0, vectorSize = 4
    # ----------------------------------------------------------------
    scfg        = ScaleAddConfig(MXFormats.INT8, MXFormats.E2M1, ScaleFormats.UE8M0)
    vector_size = 4

    print("=" * 60)
    print(f"Config: {scfg.elementTypeA.name} x {scfg.elementTypeB.name} / {scfg.stype.name}")
    print(f"  resOperatorExpWidth   = {scfg.resOperatorExpWidth}")
    print(f"  resOperatorMantWidth  = {scfg.resOperatorMantWidth}")
    print(f"  resScaleAddExpWidth   = {scfg.resScaleAddExpWidth}")
    print(f"  resScaleAddMantWidth  = {scfg.resScaleAddMantWidth}")
    print("=" * 60)

    # ----------------------------------------------------------------
    # Single-cycle test
    # ----------------------------------------------------------------
    # INT8 encoding: 2's complement (signed 8-bit integer)
    #   0x01 → +1  →  value = 1 × 2^-6 = 0.015625
    # E2M1 encoding: 4 bits, exp=2b, mant=1b, bias=1
    #   0x05 = 0b0101 → sign=0, exp=0b10=2, mant=0b1=1 → 2^(2-1) × 1.5 = 3.0
    # UE8M0 scale: 0x7F = 2^(127-127) = 1.0
    inA     = 0x01   # MXINT8: value = 0.015625
    inB     = 0x05   # E2M1:   value = 3.0
    scale_a = 0x7F   # UE8M0:  1.0
    scale_b = 0x7F   # UE8M0:  1.0

    # Pack 4 identical lanes
    op_a = sum(inA << (i * scfg.elementTypeA.totalWidth) for i in range(vector_size))
    op_b = sum(inB << (i * scfg.elementTypeB.totalWidth) for i in range(vector_size))

    new_acc, reduced_sum, lane_fp32 = fused_dot_product_unit(
        op_a, op_b, scale_a, scale_b, scfg, vector_size
    )

    print("\nPer-lane FP32 results:")
    for i, fp in enumerate(lane_fp32):
        print(f"  Lane {i}: 0x{fp:08X}  {fp32_bits_to_float(fp):.6f}")

    print(f"\nReduction tree output: 0x{reduced_sum:08X}  {fp32_bits_to_float(reduced_sum):.6f}")
    print(f"Accumulator (cycle 1): 0x{new_acc:08X}  {fp32_bits_to_float(new_acc):.6f}")

    # Software golden: each lane = (1 × 2^-6) × 3.0 × 1.0 × 1.0 = 0.046875
    # Sum of 4 lanes = 0.1875
    expected = 4.0 * (1 * 2**-6) * 3.0 * 1.0 * 1.0
    print(f"\nSoftware golden (float64): {expected:.6f}")

    # ----------------------------------------------------------------
    # Multi-cycle accumulation test
    # ----------------------------------------------------------------
    print("\n--- Multi-cycle accumulation (4 cycles, same inputs) ---")
    acc = 0
    for cycle in range(4):
        acc, rs, _ = fused_dot_product_unit(
            op_a, op_b, scale_a, scale_b, scfg, vector_size,
            acc_reg=acc, valid_in=True, reset_acc=False
        )
        print(f"  Cycle {cycle + 1}: acc = 0x{acc:08X}  {fp32_bits_to_float(acc):.6f}")

    print(f"\nSoftware golden after 4 cycles: {4.0 * expected:.6f}")

    # ----------------------------------------------------------------
    # FP32Adder sanity check
    # ----------------------------------------------------------------
    print("\n--- FP32Adder sanity checks ---")
    cases = [
        (float_to_fp32_bits(1.0),  float_to_fp32_bits(2.0),  3.0),
        (float_to_fp32_bits(0.5),  float_to_fp32_bits(-0.5), 0.0),
        (float_to_fp32_bits(1e10), float_to_fp32_bits(1.0),  1e10 + 1.0),
    ]
    for a_bits, b_bits, expected_f in cases:
        result = fp32_adder(a_bits, b_bits)
        result_f = fp32_bits_to_float(result)
        expected_bits = float_to_fp32_bits(expected_f)
        match = "OK" if result == expected_bits else f"MISMATCH (expected 0x{expected_bits:08X})"
        print(f"  {fp32_bits_to_float(a_bits)} + {fp32_bits_to_float(b_bits)}"
              f" = {result_f:.6g}  [{match}]")

    # ----------------------------------------------------------------
    # Run all accumulation tests
    # ----------------------------------------------------------------
    print()
    run_all_tests()
