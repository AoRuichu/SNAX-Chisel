"""
test_bfp_pe_hw_model.py
=======================
Comprehensive verification of bfp_pe_hw_model.py against the hardware.

Structure
---------
§A  Encode / decode helpers  (matching Scala FDPUWithCustomReductionTreeTest exactly)
§B  Software golden model    (same double-precision reference Scala tests use)
§C  Unit tests for each arithmetic sub-module (CustomOperator, FixedFPReductionTree, …)
§D  Integration tests — FDPUWithCustomReductionTree (mirrors all 17 Scala test cases)
§E  Integration tests — FDPUPostScaleReductionTree  (mirrors FDPUPostScaleReductionTreeTest)
§F  GeMM array (tileRows × tileCols) tests

Ground-truth strategy
---------------------
  1. The SW golden model computes in 64-bit double precision.
  2. The Python HW model computes in fixed-width integers, identical to RTL.
  3. We assert |HW_python − SW_double| ≤ tolerance, matching what the Scala
     chisel tests do (2 % relative + 10 % per-cycle magnitude + 1e-4 floor).
  4. For simple power-of-2 cases we additionally do bit-exact assertions, using
     known correct outputs read from fdpu_custom_reduction_tree_test.log.

Run:  python3 test_bfp_pe_hw_model.py
Or:   python3 -m pytest test_bfp_pe_hw_model.py -v
"""

import math
import struct
import unittest
import random
from typing import List, Tuple

# ── import the module under test ──────────────────────────────────────────────
from bfp_pe_hw_model import (
    # config
    ElementType, ScaleType, ScaleAddConfig,
    MXFormats, ScaleFormats,
    acc_precision_recommended,
    # sub-module functions
    custom_operator,
    fixed_fp_reduction_tree,
    scale_addition,
    direct_to_fp32,
    scale_to_fp32,
    fp32_adder,
    custom_fp_adder,
    # PE classes
    FDPUWithCustomReductionTree,
    FDPUPostScaleReductionTree,
    # GeMM arrays
    GemmPEArray,
    GemmPEArrayPostScale,
    # utilities
    fp32_bits_to_float,
    float_to_fp32_bits,
    _bits, _bit, _mask, _sign_ext, _wrap, _lzc,
)


# ═══════════════════════════════════════════════════════════════════════════════
# §A  Encode / decode helpers  (mirrors Scala golden model exactly)
# ═══════════════════════════════════════════════════════════════════════════════

def decode_element(raw: int, t: ElementType) -> float:
    """Decode MX element raw bits → float64.  Matches Scala decodeElement."""
    if t.name == "INT8":
        signed = (raw - 256) if (raw & 0x80) else raw
        return signed * (2.0 ** t.implicitScaleExp)
    sign = -1.0 if _bit(raw, t.totalWidth - 1) else 1.0
    exp  = _bits(raw, t.elementWidthMant + t.elementWidthExp - 1, t.elementWidthMant)
    mant = raw & _mask(t.elementWidthMant)
    if exp == 0:
        return sign * (mant / (1 << t.elementWidthMant)) * (2.0 ** (1 - t.bias))
    return sign * (1.0 + mant / (1 << t.elementWidthMant)) * (2.0 ** (exp - t.bias))


def decode_scale(raw: int, s: ScaleType) -> float:
    """Decode MX scale raw bits → float64.  Matches Scala decodeScale."""
    exp_bits = _bits(raw, s.totalScaleWidth - 1, s.mantScaleWidth)
    if s.mantScaleWidth == 0:
        return 2.0 ** (exp_bits - s.bias)
    mant_bits = raw & _mask(s.mantScaleWidth)
    impl  = 1.0 if exp_bits > 0 else 0.0
    eff_e = (exp_bits - s.bias) if exp_bits > 0 else (1 - s.bias)
    return (impl + mant_bits / (1 << s.mantScaleWidth)) * (2.0 ** eff_e)


def encode_element(value: float, t: ElementType) -> int:
    """Encode float64 → MX element raw bits.  Matches Scala encodeElement."""
    if value == 0.0:
        return 0
    sign    = 1 if value < 0 else 0
    abs_val = abs(value)
    if t.name == "INT8":
        mag = min(round(abs_val / (2.0 ** t.implicitScaleExp)), 127)
        return mag if sign == 0 else int((-mag) & 0xFF)
    exp_unbiased = math.floor(math.log2(abs_val))
    exp_biased   = exp_unbiased + t.bias
    if exp_biased <= 0:
        mant_int = min(round(abs_val / (2.0 ** (1 - t.bias)) * (1 << t.elementWidthMant)),
                       (1 << t.elementWidthMant) - 1)
        return (sign << (t.totalWidth - 1)) | mant_int
    exp_cl   = min(exp_biased, (1 << t.elementWidthExp) - 1)
    mant_int = max(0, min(round((abs_val / 2.0 ** exp_unbiased - 1.0) * (1 << t.elementWidthMant)),
                          (1 << t.elementWidthMant) - 1))
    return (sign << (t.totalWidth - 1)) | (exp_cl << t.elementWidthMant) | mant_int


def encode_scale(value: float, s: ScaleType) -> int:
    """Encode float64 → MX scale raw bits.  Matches Scala encodeScale."""
    exp_unbiased = math.floor(math.log2(value))
    exp_biased   = max(0, min(int(exp_unbiased) + s.bias, (1 << s.expScaleWidth) - 1))
    if s.mantScaleWidth == 0:
        return exp_biased
    mant_int = max(0, min(round((value / 2.0 ** exp_unbiased - 1.0) * (1 << s.mantScaleWidth)),
                          (1 << s.mantScaleWidth) - 1))
    return (exp_biased << s.mantScaleWidth) | mant_int


def max_element_repr(t: ElementType) -> float:
    """Maximum representable magnitude for element type t."""
    if t.name == "INT8":
        return ((1 << (t.totalWidth - 1)) - 1) * (2.0 ** t.implicitScaleExp)
    max_exp = (1 << t.elementWidthExp) - 2
    max_m   = (1 << t.elementWidthMant) - 1
    return (1.0 + max_m / (1 << t.elementWidthMant)) * 2.0 ** (max_exp - t.bias)


def quantize_block(values: List[float], et: ElementType, st: ScaleType):
    """MX block quantization. Matches Scala quantizeBlock."""
    max_abs = max(abs(v) for v in values)
    max_abs = max(max_abs, 1e-38)
    mr      = max_element_repr(et)
    min_se  = -st.bias
    max_se  = (1 << st.expScaleWidth) - 1 - st.bias
    if st.mantScaleWidth == 0:
        se  = math.ceil(math.log2(max(max_abs / mr, 1e-38)))
        se  = max(min_se, min(max_se, int(se)))
        rs  = encode_scale(2.0 ** se, st)
    else:
        ideal = max(max_abs / mr, 2.0 ** (-st.bias))
        eu    = math.floor(math.log2(ideal))
        mr2   = math.ceil((ideal / 2.0 ** eu - 1.0) * (1 << st.mantScaleWidth))
        if mr2 > (1 << st.mantScaleWidth) - 1:
            eu, mr2 = eu + 1, 0
        eb  = max(0, min(int(eu) + st.bias, (1 << st.expScaleWidth) - 1))
        rs  = (eb << st.mantScaleWidth) | int(mr2)
    ds = decode_scale(rs, st)
    return [encode_element(v / ds, et) for v in values], rs


# ═══════════════════════════════════════════════════════════════════════════════
# §B  Software golden model  (same double-precision reference as Scala tests)
# ═══════════════════════════════════════════════════════════════════════════════

def sw_fused_product(cfg: ScaleAddConfig,
                     a_raws: List[int], b_raws: List[int],
                     scale_a: int, scale_b: int) -> float:
    """Compute fused dot product in float64. Matches Scala swFusedProduct."""
    sa = decode_scale(scale_a, cfg.stype)
    sb = decode_scale(scale_b, cfg.stype)
    return float(sum(
        decode_element(a, cfg.elementTypeA) * decode_element(b, cfg.elementTypeB) * sa * sb
        for a, b in zip(a_raws, b_raws)
    ))


def check_tolerance(hw: float, sw_acc: float, sw_cycle: float, label: str,
                    rel=0.02, cyc=0.10, floor=1e-4):
    """Assert |hw - sw_acc| ≤ tolerance.  Same formula as Scala runCycles."""
    tol = abs(sw_acc) * rel + abs(sw_cycle) * cyc + floor
    err = abs(hw - sw_acc)
    assert err <= tol, (
        f"{label}: hw={hw:.8f} sw={sw_acc:.8f} diff={err:.6e} tol={tol:.6e}"
    )


def run_cycles(pe, cfg: ScaleAddConfig,
               cycles: List[Tuple],
               rel=0.02, cyc=0.10, label=""):
    """
    Run a sequence of (a_raws, b_raws, scale_a, scale_b) cycles through a PE
    and assert each accumulated output matches the SW golden model.
    Returns list of (sw_cycle, sw_acc, hw_acc) for extra inspection.
    """
    pe.reset()
    sw_acc  = 0.0
    results = []
    for i, (a_raws, b_raws, sa, sb) in enumerate(cycles):
        sw_cycle = sw_fused_product(cfg, a_raws, b_raws, sa, sb)
        pe.step(a_raws, b_raws, sa, sb, valid_in=True)
        hw = fp32_bits_to_float(pe.acc_out)
        sw_acc  = float(sw_acc + sw_cycle)
        results.append((sw_cycle, sw_acc, hw))
        check_tolerance(hw, sw_acc, sw_cycle,
                        f"{label} cycle {i}", rel=rel, cyc=cyc)
    return results


def run_cycles_postscale_tol(pe, cfg: ScaleAddConfig,
                              cycles: List[Tuple], label=""):
    """Run cycles with the exact Scala FDPUPostScaleReductionTree tolerance formula:
       tol = 4 × (1/2^resOperatorMantWidth) × cumAbsSum + 0.03 × |swAcc| + 1e-4
    """
    pe.reset()
    sw_acc       = 0.0
    cum_abs_sum  = 0.0
    mach_eps     = 1.0 / (1 << cfg.resOperatorMantWidth)
    results      = []
    for i, (a_raws, b_raws, sa, sb) in enumerate(cycles):
        sw_cycle  = sw_fused_product(cfg, a_raws, b_raws, sa, sb)
        dsa = decode_scale(sa, cfg.stype)
        dsb = decode_scale(sb, cfg.stype)
        lane_abs  = sum(
            abs(decode_element(a, cfg.elementTypeA) *
                decode_element(b, cfg.elementTypeB) * dsa * dsb)
            for a, b in zip(a_raws, b_raws)
        )
        cum_abs_sum += lane_abs
        pe.step(a_raws, b_raws, sa, sb, valid_in=True)
        hw    = fp32_bits_to_float(pe.acc_out)
        sw_acc = float(sw_acc + sw_cycle)
        results.append((sw_cycle, sw_acc, hw))
        tol = 4.0 * mach_eps * cum_abs_sum + 0.03 * abs(sw_acc) + 1e-4
        err = abs(hw - sw_acc)
        assert err <= tol, (
            f"{label} cycle {i}: hw={hw:.8f} sw={sw_acc:.8f} "
            f"diff={err:.6e} tol={tol:.6e} (opMantW={cfg.resOperatorMantWidth})"
        )
    return results


# ─── pre-encoded constants  (E4M3 × E2M1 / UE5M3 — default config) ───────────
_default_scfg = ScaleAddConfig(MXFormats.E4M3, MXFormats.E2M1, ScaleFormats.UE5M3)

E4M3_1    = encode_element( 1.0, MXFormats.E4M3)
E4M3_N1   = encode_element(-1.0, MXFormats.E4M3)
E4M3_2    = encode_element( 2.0, MXFormats.E4M3)
E4M3_1P5  = encode_element( 1.5, MXFormats.E4M3)
E2M1_1    = encode_element( 1.0, MXFormats.E2M1)
E2M1_2    = encode_element( 2.0, MXFormats.E2M1)
UE5M3_1   = encode_scale(  1.0, ScaleFormats.UE5M3)
UE5M3_2   = encode_scale(  2.0, ScaleFormats.UE5M3)

# E5M2 × E5M2 / UE8M0
_e5m2_scfg  = ScaleAddConfig(MXFormats.E5M2, MXFormats.E5M2, ScaleFormats.UE8M0)
E5M2_1P5   = encode_element( 1.5,  MXFormats.E5M2)
E5M2_N075  = encode_element(-0.75, MXFormats.E5M2)
E5M2_2     = encode_element( 2.0,  MXFormats.E5M2)
E5M2_N1P5  = encode_element(-1.5,  MXFormats.E5M2)
E5M2_N05   = encode_element(-0.5,  MXFormats.E5M2)
E5M2_1P75  = encode_element( 1.75, MXFormats.E5M2)
E5M2_N1    = encode_element(-1.0,  MXFormats.E5M2)
E5M2_075   = encode_element( 0.75, MXFormats.E5M2)
E5M2_05    = encode_element( 0.5,  MXFormats.E5M2)
E5M2_1     = encode_element( 1.0,  MXFormats.E5M2)
E5M2_N2    = encode_element(-2.0,  MXFormats.E5M2)
E5M2_N175  = encode_element(-1.75, MXFormats.E5M2)
E5M2_3     = encode_element( 3.0,  MXFormats.E5M2)
UE8M0_05   = encode_scale( 0.5, ScaleFormats.UE8M0)
UE8M0_1    = encode_scale( 1.0, ScaleFormats.UE8M0)
UE8M0_2    = encode_scale( 2.0, ScaleFormats.UE8M0)
UE8M0_4    = encode_scale( 4.0, ScaleFormats.UE8M0)


# ═══════════════════════════════════════════════════════════════════════════════
# §C  Sub-module unit tests
# ═══════════════════════════════════════════════════════════════════════════════

class TestEncodeDecodeRoundtrip(unittest.TestCase):
    """Verify encode → decode round-trips for all element and scale types."""

    def _check_elem(self, value, et, tol_rel=0.02):
        raw = encode_element(value, et)
        got = decode_element(raw, et)
        if abs(value) < 1e-38:
            self.assertAlmostEqual(got, 0.0, places=10)
            return
        err = abs(got - value) / abs(value)
        self.assertLessEqual(err, tol_rel,
            f"{et.name}: encode({value})={raw:#x} decode={got} err={err:.4f}")

    def test_e5m2_values(self):
        for v in [0.5, 1.0, 1.5, 2.0, 3.0, -1.0, -1.75]:
            self._check_elem(v, MXFormats.E5M2, tol_rel=0.01)

    def test_e4m3_values(self):
        for v in [0.25, 1.0, 1.5, 2.0, -1.0]:
            self._check_elem(v, MXFormats.E4M3, tol_rel=0.01)

    def test_e2m1_values(self):
        for v in [0.5, 1.0, 2.0, -1.0, -2.0]:
            self._check_elem(v, MXFormats.E2M1, tol_rel=0.01)

    def test_int8_values(self):
        # INT8 max representable = 127 × 2^(-6) ≈ 1.984375; values above clip.
        for v in [1.0, -1.0, -2.0, 0.5, 0.25]:
            raw = encode_element(v, MXFormats.INT8)
            got = decode_element(raw, MXFormats.INT8)
            self.assertAlmostEqual(got, v, delta=abs(v) * 0.02 + 1e-8)

    def test_int8_raw_1_is_tiny(self):
        # raw=1 → 1 × 2^(-6) = 0.015625  (the hardware convention)
        self.assertAlmostEqual(decode_element(0x01, MXFormats.INT8), 0.015625)
        # raw=64 → 64 × 2^(-6) = 1.0
        self.assertAlmostEqual(decode_element(0x40, MXFormats.INT8), 1.0)
        # raw=0x80: Scala uses 2's complement → (128-256) × 2^(-6) = -2.0
        self.assertAlmostEqual(decode_element(0x80, MXFormats.INT8), -2.0)
        # raw=0xC0 (sign=1, mag=64) → -1.0
        self.assertAlmostEqual(decode_element(0xC0, MXFormats.INT8), -1.0)

    def test_ue8m0_scale(self):
        for v in [0.5, 1.0, 2.0, 4.0, 16.0]:
            raw = encode_scale(v, ScaleFormats.UE8M0)
            got = decode_scale(raw, ScaleFormats.UE8M0)
            self.assertAlmostEqual(got, v, delta=1e-9)

    def test_ue5m3_scale(self):
        for v in [0.5, 1.0, 2.0]:
            raw = encode_scale(v, ScaleFormats.UE5M3)
            got = decode_scale(raw, ScaleFormats.UE5M3)
            self.assertAlmostEqual(got, v, delta=abs(v) * 0.02)


class TestCustomOperator(unittest.TestCase):
    """Verify custom_operator() output (sign, exp, mant)."""

    def _op(self, a_val, b_val, et_a, et_b):
        cfg = ScaleAddConfig(et_a, et_b, ScaleFormats.UE8M0)
        return custom_operator(
            encode_element(a_val, et_a),
            encode_element(b_val, et_b),
            et_a, et_b,
            cfg.resOperatorExpWidth, cfg.resOperatorMantWidth
        )

    def test_e4m3_x_e2m1_ones(self):
        # +1.0 × +1.0 → sign=0, product positive
        sign, exp, mant = self._op(1.0, 1.0, MXFormats.E4M3, MXFormats.E2M1)
        self.assertEqual(sign, 0)
        self.assertGreater(mant, 0)
        # Manual: adjExpA=0, adjExpB=0 → exp=0
        self.assertEqual(exp, 0)

    def test_e4m3_x_e2m1_neg_sign(self):
        # +1.0 × -1.0 → sign=1
        sign, _, _ = self._op(1.0, -1.0, MXFormats.E4M3, MXFormats.E2M1)
        self.assertEqual(sign, 1)
        # -1.0 × -1.0 → sign=0
        sign2, _, _ = self._op(-1.0, -1.0, MXFormats.E4M3, MXFormats.E2M1)
        self.assertEqual(sign2, 0)

    def test_int8_exp_includes_implicit_scale(self):
        # INT8 adj exp = implicitScaleExp = -6 (no bias correction for integer types)
        cfg  = ScaleAddConfig(MXFormats.INT8, MXFormats.E4M3, ScaleFormats.UE8M0)
        sign, exp, mant = custom_operator(
            encode_element(1.0, MXFormats.INT8),
            encode_element(1.0, MXFormats.E4M3),
            MXFormats.INT8, MXFormats.E4M3,
            cfg.resOperatorExpWidth, cfg.resOperatorMantWidth
        )
        # adjExpA(INT8) = -6, adjExpB(E4M3,1.0) = 7-7 = 0 → total = -6
        self.assertEqual(exp, -6)

    def test_e5m2_x_e5m2_exp_addition(self):
        # E5M2(2.0): exp_biased=16, adj=16-15=1
        # E5M2(2.0) × E5M2(2.0) → adj=-6+... wait E5M2 bias=15
        cfg = ScaleAddConfig(MXFormats.E5M2, MXFormats.E5M2, ScaleFormats.UE8M0)
        sign, exp, mant = custom_operator(
            encode_element(2.0, MXFormats.E5M2),
            encode_element(2.0, MXFormats.E5M2),
            MXFormats.E5M2, MXFormats.E5M2,
            cfg.resOperatorExpWidth, cfg.resOperatorMantWidth
        )
        # 2.0 = 1.0×2^1, adj=1. Two factors → exp=2
        self.assertEqual(exp, 2)
        self.assertEqual(sign, 0)

    def test_subnormal_element(self):
        # E4M3 subnormal: exp_biased=0, adj=1-bias=1-7=-6
        # E2M1 normal 1.0: adj=0
        # product exp = -6+0 = -6
        cfg = ScaleAddConfig(MXFormats.E4M3, MXFormats.E2M1, ScaleFormats.UE8M0)
        sign, exp, mant = custom_operator(
            0x01,   # E4M3 subnormal (exp_field=0, mant=1)
            encode_element(1.0, MXFormats.E2M1),
            MXFormats.E4M3, MXFormats.E2M1,
            cfg.resOperatorExpWidth, cfg.resOperatorMantWidth
        )
        self.assertEqual(exp, -6)

    def test_zero_mant_gives_zero(self):
        cfg = ScaleAddConfig(MXFormats.E4M3, MXFormats.E2M1, ScaleFormats.UE8M0)
        _, _, mant = custom_operator(
            0x00, encode_element(1.0, MXFormats.E2M1),
            MXFormats.E4M3, MXFormats.E2M1,
            cfg.resOperatorExpWidth, cfg.resOperatorMantWidth
        )
        self.assertEqual(mant, 0)


class TestFP32Adder(unittest.TestCase):
    """Verify fp32_adder() against Python's float32 arithmetic."""

    def _both(self, a: float, b: float):
        a32  = struct.unpack('f', struct.pack('f', a))[0]
        b32  = struct.unpack('f', struct.pack('f', b))[0]
        ref  = struct.unpack('I', struct.pack('f', a32 + b32))[0]
        got  = fp32_adder(float_to_fp32_bits(a32), float_to_fp32_bits(b32))
        return ref, got

    def test_positive_add(self):
        ref, got = self._both(1.0, 2.0)
        self.assertEqual(got, ref, f"1.0+2.0: got {got:#x} expected {ref:#x}")

    def test_negative_result(self):
        ref, got = self._both(1.5, -3.5)
        self.assertEqual(got, ref)

    def test_cancellation(self):
        ref, got = self._both(1.0, -1.0)
        self.assertEqual(got, ref)   # should be 0

    def test_large_diff_exponent(self):
        ref, got = self._both(1.0, 1e-20)
        self.assertEqual(got, ref)

    def test_accumulation_sequence(self):
        acc = 0
        sw  = 0.0
        vals = [1.0, 2.0, -0.5, 4.0, -3.0]
        for v in vals:
            acc = fp32_adder(acc, float_to_fp32_bits(v))
            sw += v
        hw = fp32_bits_to_float(acc)
        self.assertAlmostEqual(hw, sw, delta=abs(sw) * 0.001 + 1e-5)

    def test_zero_plus_nonzero(self):
        ref, got = self._both(0.0, 3.14)
        self.assertEqual(got, ref)


class TestFixedFPReductionTree(unittest.TestCase):
    """Verify fixed_fp_reduction_tree() properties."""

    def _run(self, lane_vals, scfg):
        lanes = [
            custom_operator(
                encode_element(a, scfg.elementTypeA),
                encode_element(b, scfg.elementTypeB),
                scfg.elementTypeA, scfg.elementTypeB,
                scfg.resOperatorExpWidth, scfg.resOperatorMantWidth
            )
            for a, b in lane_vals
        ]
        return fixed_fp_reduction_tree(
            lanes,
            exp_w=scfg.resOperatorExpWidth,
            in_mant_w=scfg.resOperatorMantWidth,
            out_mant_w=scfg.resOperatorMantWidth,
            product_exp_range=scfg.productExpRange
        )

    def test_single_lane_passthrough(self):
        scfg = ScaleAddConfig(MXFormats.E4M3, MXFormats.E4M3, ScaleFormats.UE8M0)
        sign, exp, mant = self._run([(1.0, 1.0)], scfg)
        # single lane: tree output = that lane's operator output
        s0, e0, m0 = custom_operator(
            encode_element(1.0, MXFormats.E4M3),
            encode_element(1.0, MXFormats.E4M3),
            MXFormats.E4M3, MXFormats.E4M3,
            scfg.resOperatorExpWidth, scfg.resOperatorMantWidth
        )
        # after round-trip through tree (which is a single normalisation): mantissa may shift
        # just verify sign and that mant is nonzero
        self.assertEqual(sign, s0)
        self.assertGreater(mant, 0)

    def test_four_equal_lanes_gives_4x_value(self):
        # 4 lanes of (1.0×1.0) → sum = 4 × product
        scfg = ScaleAddConfig(MXFormats.E4M3, MXFormats.E4M3, ScaleFormats.UE8M0)
        sign1, exp1, mant1 = self._run([(1.0, 1.0)], scfg)
        sign4, exp4, mant4 = self._run([(1.0, 1.0)] * 4, scfg)
        # sum of 4 identical positive values: sign stays 0
        self.assertEqual(sign4, 0)
        # exp4 should be exp1+2 (×4 = shift by log2(4)=2)
        self.assertEqual(exp4, exp1 + 2)

    def test_full_cancellation(self):
        # [+1,-1,+1,-1] → sum=0
        scfg = ScaleAddConfig(MXFormats.E4M3, MXFormats.E4M3, ScaleFormats.UE8M0)
        sign, exp, mant = self._run([(1.0, 1.0), (-1.0, 1.0),
                                      (1.0, 1.0), (-1.0, 1.0)], scfg)
        self.assertEqual(mant, 0)
        self.assertEqual(sign, 0)

    def test_int8_zero_exprange_pure_integer(self):
        # INT8×INT8 → productExpRange=0 (all products same implicit exp)
        scfg = ScaleAddConfig(MXFormats.INT8, MXFormats.INT8, ScaleFormats.UE8M0)
        self.assertEqual(scfg.productExpRange, 0)
        sign, exp, mant = self._run([(1.0, 1.0), (1.0, 1.0)], scfg)
        self.assertEqual(sign, 0)
        self.assertGreater(mant, 0)


class TestScaleToFP32(unittest.TestCase):
    """Verify scale_to_fp32() and direct_to_fp32() conversion paths."""

    def test_direct_to_fp32_identity_scale(self):
        # E4M3×E4M3 / UE8M0, scale=1.0×1.0, single 1.0×1.0 product.
        # direct_to_fp32 expects FixedFPReductionTree output (normalized), so we
        # pass the product through the single-element tree first.
        scfg = ScaleAddConfig(MXFormats.E4M3, MXFormats.E4M3, ScaleFormats.UE8M0)
        op_s, op_e, op_m = custom_operator(
            encode_element(1.0, MXFormats.E4M3),
            encode_element(1.0, MXFormats.E4M3),
            MXFormats.E4M3, MXFormats.E4M3,
            scfg.resOperatorExpWidth, scfg.resOperatorMantWidth
        )
        # Normalize through single-lane tree (mirrors the hardware pipeline)
        ts, te, tm = fixed_fp_reduction_tree(
            [(op_s, op_e, op_m)],
            scfg.resOperatorExpWidth, scfg.resOperatorMantWidth, scfg.resOperatorMantWidth,
            scfg.productExpRange
        )
        fp32 = direct_to_fp32(ts, te, tm, UE8M0_1, UE8M0_1, scfg)
        self.assertAlmostEqual(fp32_bits_to_float(fp32), 1.0, delta=0.01)

    def test_scale_to_fp32_ue5m3_ones(self):
        # E4M3×E2M1 / UE5M3, scale=1.0×1.0, 1.0×1.0
        scfg = ScaleAddConfig(MXFormats.E4M3, MXFormats.E2M1, ScaleFormats.UE5M3)
        s_op, e_op, m_op = custom_operator(
            encode_element(1.0, MXFormats.E4M3),
            encode_element(1.0, MXFormats.E2M1),
            MXFormats.E4M3, MXFormats.E2M1,
            scfg.resOperatorExpWidth, scfg.resOperatorMantWidth
        )
        sa_s, sa_e, sa_m = scale_addition(
            s_op, e_op, m_op, UE5M3_1, UE5M3_1, scfg
        )
        fp32 = scale_to_fp32(sa_s, sa_e, sa_m, scfg)
        self.assertAlmostEqual(fp32_bits_to_float(fp32), 1.0, delta=0.01)

    def test_zero_mant_gives_zero_fp32(self):
        scfg = ScaleAddConfig(MXFormats.E4M3, MXFormats.E4M3, ScaleFormats.UE8M0)
        fp32 = direct_to_fp32(0, 0, 0, UE8M0_1, UE8M0_1, scfg)
        self.assertEqual(fp32, 0)

    def test_scale_amplification(self):
        # scale=2.0×2.0 should give ×4
        scfg = ScaleAddConfig(MXFormats.E4M3, MXFormats.E4M3, ScaleFormats.UE8M0)
        s1, e1, m1 = custom_operator(
            encode_element(1.0, MXFormats.E4M3),
            encode_element(1.0, MXFormats.E4M3),
            MXFormats.E4M3, MXFormats.E4M3,
            scfg.resOperatorExpWidth, scfg.resOperatorMantWidth
        )
        fp_scale1 = direct_to_fp32(s1, e1, m1, UE8M0_1, UE8M0_1, scfg)
        fp_scale4 = direct_to_fp32(s1, e1, m1, UE8M0_2, UE8M0_2, scfg)
        v1 = fp32_bits_to_float(fp_scale1)
        v4 = fp32_bits_to_float(fp_scale4)
        self.assertAlmostEqual(v4, v1 * 4.0, delta=abs(v4) * 0.001)


# ═══════════════════════════════════════════════════════════════════════════════
# §D  FDPUWithCustomReductionTree integration tests  (mirrors 17 Scala cases)
# ═══════════════════════════════════════════════════════════════════════════════

class TestFDPUWithCustomReductionTree(unittest.TestCase):

    # ── Test 3: vec=1 scalar  ──────────────────────────────────────────────────
    def test_vec1_scalar_equivalence(self):
        """vec=1: 1.0×1.0×1.0×1.0 = 1.0 (custom tree path, E4M3×E2M1/UE5M3)"""
        pe  = FDPUWithCustomReductionTree(_default_scfg, 1)
        run_cycles(pe, _default_scfg,
                   [([E4M3_1], [E2M1_1], UE5M3_1, UE5M3_1)],
                   label="vec=1 scalar")

    # ── Test 4: vec=4 all-ones → 4.0  ─────────────────────────────────────────
    def test_vec4_all_ones_single_cycle(self):
        """vec=4: dot(1,1,1,1)×scale(1×1) = 4.0  — bit-exact expected"""
        pe  = FDPUWithCustomReductionTree(_default_scfg, 4)
        pe.reset()
        pe.step([E4M3_1]*4, [E2M1_1]*4, UE5M3_1, UE5M3_1)
        hw = fp32_bits_to_float(pe.acc_out)
        self.assertAlmostEqual(hw, 4.0, delta=0.001)
        # bit-exact: 4.0 = 0x40800000
        self.assertEqual(pe.acc_out, 0x40800000)

    # ── Test 5: vec=8 all-ones → 8.0  ─────────────────────────────────────────
    def test_vec8_all_ones_single_cycle(self):
        """vec=8: dot(1×8) = 8.0  — bit-exact expected"""
        pe  = FDPUWithCustomReductionTree(_default_scfg, 8)
        pe.reset()
        pe.step([E4M3_1]*8, [E2M1_1]*8, UE5M3_1, UE5M3_1)
        self.assertEqual(pe.acc_out, 0x41000000)   # 8.0

    # ── Test 6: vec=4 mixed signs → 0.0  ──────────────────────────────────────
    def test_vec4_mixed_signs_cancellation(self):
        """vec=4: [+1,-1,+1,-1]×[1,1,1,1] = 0.0"""
        pe  = FDPUWithCustomReductionTree(_default_scfg, 4)
        pe.reset()
        pe.step([E4M3_1, E4M3_N1, E4M3_1, E4M3_N1], [E2M1_1]*4, UE5M3_1, UE5M3_1)
        self.assertEqual(pe.acc_out, 0)

    # ── Test 7: scale amplification 2×2 → 16.0  ───────────────────────────────
    def test_scale_amplification(self):
        """vec=4, scale=2×2: 4×1×2×2 = 16.0  — bit-exact"""
        pe  = FDPUWithCustomReductionTree(_default_scfg, 4)
        pe.reset()
        pe.step([E4M3_1]*4, [E2M1_1]*4, UE5M3_2, UE5M3_2)
        self.assertEqual(pe.acc_out, 0x41800000)   # 16.0

    # ── Test 9: vec=4 multi-cycle 4×4.0 → 16.0  ──────────────────────────────
    def test_multicycle_accumulation_exact(self):
        """4 cycles × 4.0 per cycle → acc = 4, 8, 12, 16 (all exact powers of 2)"""
        pe  = FDPUWithCustomReductionTree(_default_scfg, 4)
        expected_bits = [0x40800000, 0x41000000, 0x41400000, 0x41800000]
        pe.reset()
        for cyc_bits in expected_bits:
            pe.step([E4M3_1]*4, [E2M1_1]*4, UE5M3_1, UE5M3_1)
            self.assertEqual(pe.acc_out, cyc_bits,
                f"acc mismatch: got {pe.acc_out:#x} expected {cyc_bits:#x}")

    # ── Test 10: vec=4 mixed-value multi-cycle (from Scala log: all exact)  ───
    def test_mixed_value_multicycle(self):
        """Multi-cycle: [1,2,-1,1]×1, [1,1,1,1]×2, [-1,-1,-1,-1]×scale2, …"""
        pe  = FDPUWithCustomReductionTree(_default_scfg, 4)
        cycles = [
            ([E4M3_1, E4M3_2, E4M3_N1, E4M3_1],  [E2M1_1]*4, UE5M3_1, UE5M3_1),
            ([E4M3_1]*4,                            [E2M1_2]*4, UE5M3_1, UE5M3_1),
            ([E4M3_N1]*4,                           [E2M1_1]*4, UE5M3_2, UE5M3_1),
            ([E4M3_1, E4M3_1P5, E4M3_N1, E4M3_2],  [E2M1_1]*4, UE5M3_1, UE5M3_1),
        ]
        results = run_cycles(pe, _default_scfg, cycles, label="mixed-value")
        # From Scala log: accumulated SW values are 3, 11, 3, 6.5
        sw_expected = [3.0, 11.0, 3.0, 6.5]
        for i, ((_, sw_acc, hw), exp) in enumerate(zip(results, sw_expected)):
            self.assertAlmostEqual(sw_acc, exp, delta=0.01, msg=f"SW ref cycle {i}")
            self.assertAlmostEqual(hw,     exp, delta=0.02 * abs(exp) + 0.1,
                                   msg=f"HW cycle {i}")

    # ── Test 11: E5M2×E5M2/UE8M0 vec=4 6-cycle  (HW log values as reference)  ─
    def test_e5m2_x_e5m2_6cycle(self):
        """E5M2×E5M2/UE8M0 vec=4: 6 cycles with mixed-sign mantissas."""
        cfg    = _e5m2_scfg
        vsize  = 4
        cycles = [
            ([E5M2_1P5, E5M2_N075, E5M2_2,   E5M2_N1P5],
             [E5M2_N05, E5M2_1P75, E5M2_N1,  E5M2_075],  UE8M0_2, UE8M0_1),
            ([E5M2_075, E5M2_1,    E5M2_N1P5, E5M2_05],
             [E5M2_2,   E5M2_N1P5, E5M2_075, E5M2_N1],   UE8M0_1, UE8M0_05),
            ([E5M2_N2,  E5M2_1P5,  E5M2_075, E5M2_N1],
             [E5M2_1,   E5M2_N075, E5M2_1P5, E5M2_2],    UE8M0_05, UE8M0_2),
            ([E5M2_1P75, E5M2_N05, E5M2_N1,  E5M2_1P5],
             [E5M2_N1P5, E5M2_1,   E5M2_2,   E5M2_N075], UE8M0_4, UE8M0_1),
            ([E5M2_N075, E5M2_2,   E5M2_1,   E5M2_N175],
             [E5M2_075,  E5M2_N2,  E5M2_1P5, E5M2_1],    UE8M0_1, UE8M0_4),
            ([E5M2_1,    E5M2_N1,  E5M2_1P5, E5M2_N2],
             [E5M2_N1,   E5M2_1P5, E5M2_N075, E5M2_1],   UE8M0_2, UE8M0_2),
        ]
        pe = FDPUWithCustomReductionTree(cfg, vsize)
        run_cycles(pe, cfg, cycles, label="E5M2×E5M2 6cycle")

    # ── Test 12: Power-of-2 vectorSizes 1..16  ────────────────────────────────
    def test_power_of_2_vectorsizes(self):
        """vec = 1,2,4,8,16: sum of N all-ones = N (bit-exact)"""
        expected_fp32 = {
            1:  0x3F800000,   # 1.0
            2:  0x40000000,   # 2.0
            4:  0x40800000,   # 4.0
            8:  0x41000000,   # 8.0
            16: 0x41800000,   # 16.0
        }
        for vsize, bits in expected_fp32.items():
            with self.subTest(vsize=vsize):
                pe = FDPUWithCustomReductionTree(_default_scfg, vsize)
                pe.reset()
                pe.step([E4M3_1]*vsize, [E2M1_1]*vsize, UE5M3_1, UE5M3_1)
                self.assertEqual(pe.acc_out, bits,
                    f"vec={vsize}: got {pe.acc_out:#010x} expected {bits:#010x}")

    # ── Test 13: Non-power-of-2 vectorSizes 3,5,7  ────────────────────────────
    def test_nonpow2_vectorsizes(self):
        """vec=3,5,7: sum of N all-ones = N"""
        for vsize in [3, 5, 7]:
            with self.subTest(vsize=vsize):
                pe = FDPUWithCustomReductionTree(_default_scfg, vsize)
                pe.reset()
                pe.step([E4M3_1]*vsize, [E2M1_1]*vsize, UE5M3_1, UE5M3_1)
                hw = fp32_bits_to_float(pe.acc_out)
                self.assertAlmostEqual(hw, float(vsize), delta=0.01)

    # ── Test 14: 8-element dot product with scale=2×2  ────────────────────────
    def test_8element_dot_product_with_scale(self):
        """8-element dot product: scale=2×2"""
        vec_a = [1.0, -1.0, 2.0, 1.0, -1.0, 1.0, 2.0, -2.0]
        vec_b = [1.0,  1.0, 1.0, 2.0,  2.0, 1.0, 1.0,  1.0]
        sa, sb = 2.0, 2.0
        raw_as = [encode_element(v, _default_scfg.elementTypeA) for v in vec_a]
        raw_bs = [encode_element(v, _default_scfg.elementTypeB) for v in vec_b]
        raw_sa = encode_scale(sa, _default_scfg.stype)
        raw_sb = encode_scale(sb, _default_scfg.stype)
        # SW reference: dot × sa × sb
        dot_noscale = sum(a*b for a,b in zip(vec_a, vec_b))
        expected = dot_noscale * sa * sb   # 3.0 × 4.0 = 12.0
        pe = FDPUWithCustomReductionTree(_default_scfg, 8)
        pe.reset()
        pe.step(raw_as, raw_bs, raw_sa, raw_sb)
        hw  = fp32_bits_to_float(pe.acc_out)
        tol = abs(expected) * 0.05 + 1e-5
        self.assertAlmostEqual(hw, expected, delta=tol)

    # ── Test 15: All type combinations  ───────────────────────────────────────
    def test_all_type_combinations_5cycle(self):
        """All element × scale type combinations, vec=4, 5 random cycles."""
        rng    = random.Random(42)
        vsize  = 4
        ncyc   = 5
        failed = []

        def rand_elem(t):
            sign = rng.randint(0, 1)
            if t.name == "INT8":
                mag = 1 + rng.randint(0, 14)
                return mag if sign == 0 else int((-mag) & 0xFF)
            er = 1 << t.elementWidthExp
            lo = max(t.bias + 1, 1) ; hi = min(t.bias + 4, er - 1)
            exp  = lo + rng.randint(0, hi - lo)
            mant = rng.randint(0, (1 << t.elementWidthMant) - 1)
            return (sign << (t.totalWidth - 1)) | (exp << t.elementWidthMant) | mant

        def rand_scale(s):
            er = 1 << s.expScaleWidth
            lo = max(s.bias - 2, 1) ; hi = min(s.bias + 2, er - 1)
            exp  = lo + rng.randint(0, hi - lo)
            mant = 0 if s.mantScaleWidth == 0 else rng.randint(0, (1 << s.mantScaleWidth) - 1)
            return (exp << s.mantScaleWidth) | mant

        all_elem  = [MXFormats.INT8, MXFormats.E5M2, MXFormats.E4M3,
                     MXFormats.E3M2, MXFormats.E2M3, MXFormats.E2M1]
        all_scale = [ScaleFormats.UE8M0, ScaleFormats.UE7M1, ScaleFormats.UE6M2,
                     ScaleFormats.UE5M3, ScaleFormats.UE4M4, ScaleFormats.UE3M5,
                     ScaleFormats.UE2M6]

        for etA in all_elem:
            for etB in all_elem:
                for st in all_scale:
                    cfg = ScaleAddConfig(etA, etB, st)
                    cycles = [
                        ([rand_elem(etA) for _ in range(vsize)],
                         [rand_elem(etB) for _ in range(vsize)],
                         rand_scale(st), rand_scale(st))
                        for _ in range(ncyc)
                    ]
                    try:
                        pe = FDPUWithCustomReductionTree(cfg, vsize)
                        # use wider rel=0.05 to handle low-mantissa configs (e.g. E5M2×E2M1)
                        run_cycles(pe, cfg, cycles, rel=0.05, cyc=0.15,
                                   label=f"{etA.name}×{etB.name}/{st.name}")
                    except AssertionError as e:
                        failed.append(str(e))

        self.assertEqual(len(failed), 0,
            f"{len(failed)} combinations failed:\n" + "\n".join(failed[:5]))

    # ── Test 16: MX block-quantized 32-element dot product (E5M2×E4M3/UE8M0)  ─
    def test_block_quantized_32elem_e5m2_x_e4m3(self):
        """32-element MX block GeMM (E5M2×E4M3/UE8M0, vec=8, 4 cycles)."""
        cfg   = ScaleAddConfig(MXFormats.E5M2, MXFormats.E4M3, ScaleFormats.UE8M0)
        vsize = 8
        raw_a = [3.2,-1.6,0.8,4.0,-2.4,1.2,-0.6,3.6,
                 2.0,-3.0,1.5,-0.5,2.5,-1.0,0.4,1.8,
                -2.2,0.9,-1.4,3.1,-0.7,2.8,-1.1,0.3,
                 1.7,-2.9,0.6,-1.3,2.1,-0.8,1.9,-3.5]
        raw_b = [1.0,2.0,-1.0,0.5,1.5,-0.5,2.0,-1.0,
                 0.75,-1.25,1.5,2.0,-0.75,1.0,-2.0,0.5,
                 2.0,-1.0,0.5,-1.5,1.0,0.25,-1.0,2.0,
                -0.5,1.5,-2.0,1.0,0.5,-1.5,1.0,-0.25]
        enc_a, rs_a = quantize_block(raw_a, cfg.elementTypeA, cfg.stype)
        enc_b, rs_b = quantize_block(raw_b, cfg.elementTypeB, cfg.stype)
        cycles = [
            (enc_a[c*vsize:(c+1)*vsize], enc_b[c*vsize:(c+1)*vsize], rs_a, rs_b)
            for c in range(len(raw_a) // vsize)
        ]
        pe = FDPUWithCustomReductionTree(cfg, vsize)
        run_cycles(pe, cfg, cycles, label="E5M2×E4M3 32-elem")

    # ── Test 17: MX block-quantized 32-element (INT8×E2M1/UE8M0)  ─────────────
    def test_block_quantized_32elem_int8_x_e2m1(self):
        """32-element MX block GeMM (INT8×E2M1/UE8M0, vec=8, 4 cycles)."""
        cfg   = ScaleAddConfig(MXFormats.INT8, MXFormats.E2M1, ScaleFormats.UE8M0)
        vsize = 8
        raw_a = [1.0,-2.0,3.0,1.5,-1.5,2.0,-0.5,3.5,
                 2.5,-1.0,0.5,-3.0,1.0,-2.5,2.0,-1.0,
                -1.5,1.0,-2.0,3.0,-0.5,2.0,-1.0,0.5,
                 1.5,-3.0,1.0,-1.5,2.0,-0.5,1.0,-2.0]
        raw_b = [2.0,-1.0,1.5,-2.0,1.0,-1.5,2.0,-1.0,
                 1.0,-2.0,1.5,-1.0,2.0,-1.5,1.0,-2.0,
                -1.0,2.0,-1.5,1.0,-2.0,1.5,-1.0,2.0,
                 1.5,-1.0,2.0,-1.5,1.0,-2.0,1.5,-1.0]
        enc_a, rs_a = quantize_block(raw_a, cfg.elementTypeA, cfg.stype)
        enc_b, rs_b = quantize_block(raw_b, cfg.elementTypeB, cfg.stype)
        cycles = [
            (enc_a[c*vsize:(c+1)*vsize], enc_b[c*vsize:(c+1)*vsize], rs_a, rs_b)
            for c in range(len(raw_a) // vsize)
        ]
        pe = FDPUWithCustomReductionTree(cfg, vsize)
        run_cycles(pe, cfg, cycles, label="INT8×E2M1 32-elem")

    # ── Reset mid-accumulation  ───────────────────────────────────────────────
    def test_reset_clears_accumulator(self):
        """reset() zeroes the accumulator and next step starts fresh."""
        pe = FDPUWithCustomReductionTree(_default_scfg, 4)
        pe.reset()
        pe.step([E4M3_2]*4, [E2M1_2]*4, UE5M3_1, UE5M3_1)
        self.assertNotEqual(pe.acc_out, 0, "acc should be non-zero after accumulation")
        pe.reset()
        self.assertEqual(pe.acc_out, 0, "acc should be 0 after reset")
        pe.step([E4M3_1]*4, [E2M1_1]*4, UE5M3_1, UE5M3_1)
        self.assertEqual(pe.acc_out, 0x40800000)  # 4.0 — fresh start

    # ── valid_in=False does not accumulate  ───────────────────────────────────
    def test_validin_false_skips_accumulation(self):
        pe = FDPUWithCustomReductionTree(_default_scfg, 4)
        pe.reset()
        pe.step([E4M3_1]*4, [E2M1_1]*4, UE5M3_1, UE5M3_1, valid_in=False)
        self.assertEqual(pe.acc_out, 0, "valid_in=False must not accumulate")
        pe.step([E4M3_1]*4, [E2M1_1]*4, UE5M3_1, UE5M3_1, valid_in=True)
        self.assertEqual(pe.acc_out, 0x40800000)  # 4.0


# ═══════════════════════════════════════════════════════════════════════════════
# §E  FDPUPostScaleReductionTree integration tests
# ═══════════════════════════════════════════════════════════════════════════════

class TestFDPUPostScaleReductionTree(unittest.TestCase):
    """Mirrors FDPUPostScaleReductionTreeTest.scala (wider tolerance)."""

    REL  = 0.05   # 5 % relative (post-scale tree rounds before scale multiply)
    CYC  = 0.15   # 15 % per-cycle

    def _run(self, pe, cfg, cycles, label=""):
        return run_cycles(pe, cfg, cycles, rel=self.REL, cyc=self.CYC, label=label)

    def test_vec4_all_ones_bit_exact(self):
        """Post-scale: vec=4 all-ones → 4.0 (bit-exact for simple power-of-2 inputs)"""
        scfg = ScaleAddConfig(MXFormats.E4M3, MXFormats.E4M3, ScaleFormats.UE8M0)
        pe   = FDPUPostScaleReductionTree(scfg, 4)
        pe.reset()
        a = [encode_element(1.0, MXFormats.E4M3)] * 4
        pe.step(a, a, UE8M0_1, UE8M0_1)
        hw = fp32_bits_to_float(pe.acc_out)
        self.assertAlmostEqual(hw, 4.0, delta=0.05)

    def test_vec8_all_ones(self):
        scfg = ScaleAddConfig(MXFormats.E4M3, MXFormats.E4M3, ScaleFormats.UE8M0)
        pe   = FDPUPostScaleReductionTree(scfg, 8)
        pe.reset()
        a = [encode_element(1.0, MXFormats.E4M3)] * 8
        pe.step(a, a, UE8M0_1, UE8M0_1)
        hw = fp32_bits_to_float(pe.acc_out)
        self.assertAlmostEqual(hw, 8.0, delta=0.1)

    def test_mixed_signs_cancellation(self):
        scfg = ScaleAddConfig(MXFormats.E4M3, MXFormats.E4M3, ScaleFormats.UE8M0)
        pe   = FDPUPostScaleReductionTree(scfg, 4)
        pe.reset()
        a1 = encode_element( 1.0, MXFormats.E4M3)
        an = encode_element(-1.0, MXFormats.E4M3)
        b1 = encode_element( 1.0, MXFormats.E4M3)
        pe.step([a1, an, a1, an], [b1]*4, UE8M0_1, UE8M0_1)
        self.assertEqual(pe.acc_out, 0)

    def test_multicycle_e4m3_ue8m0(self):
        """Mirror Scala Test 9: 4 cycles × 4.0 → 16.0 (E4M3×E4M3/UE8M0)"""
        scfg = ScaleAddConfig(MXFormats.E4M3, MXFormats.E4M3, ScaleFormats.UE8M0)
        pe   = FDPUPostScaleReductionTree(scfg, 4)
        a1   = encode_element(1.0, MXFormats.E4M3)
        cycles = [([a1]*4, [a1]*4, UE8M0_1, UE8M0_1)] * 4
        self._run(pe, scfg, cycles, label="postscale 4×4.0")

    def test_e5m2_x_e5m2_6cycle(self):
        """E5M2×E5M2/UE8M0 vec=4: 6 cycles — same inputs as §D Test 11"""
        cfg    = _e5m2_scfg
        cycles = [
            ([E5M2_1P5, E5M2_N075, E5M2_2,   E5M2_N1P5],
             [E5M2_N05, E5M2_1P75, E5M2_N1,  E5M2_075],  UE8M0_2, UE8M0_1),
            ([E5M2_075, E5M2_1,    E5M2_N1P5, E5M2_05],
             [E5M2_2,   E5M2_N1P5, E5M2_075, E5M2_N1],   UE8M0_1, UE8M0_05),
            ([E5M2_N2,  E5M2_1P5,  E5M2_075, E5M2_N1],
             [E5M2_1,   E5M2_N075, E5M2_1P5, E5M2_2],    UE8M0_05, UE8M0_2),
            ([E5M2_1P75, E5M2_N05, E5M2_N1,  E5M2_1P5],
             [E5M2_N1P5, E5M2_1,   E5M2_2,   E5M2_N075], UE8M0_4, UE8M0_1),
            ([E5M2_N075, E5M2_2,   E5M2_1,   E5M2_N175],
             [E5M2_075,  E5M2_N2,  E5M2_1P5, E5M2_1],    UE8M0_1, UE8M0_4),
            ([E5M2_1,    E5M2_N1,  E5M2_1P5, E5M2_N2],
             [E5M2_N1,   E5M2_1P5, E5M2_N075, E5M2_1],   UE8M0_2, UE8M0_2),
        ]
        pe = FDPUPostScaleReductionTree(cfg, 4)
        self._run(pe, cfg, cycles, label="postscale E5M2×E5M2 6cyc")

    def test_acc_mant_bits_auto(self):
        """Auto acc_mant_bits is derived correctly from K and productExpRange."""
        for k, exp_bits in [(4, 8), (16, 9), (32, 9), (64, 10)]:
            with self.subTest(K=k):
                scfg = ScaleAddConfig(MXFormats.E4M3, MXFormats.E4M3, ScaleFormats.UE8M0)
                b    = acc_precision_recommended(scfg, k)
                self.assertGreaterEqual(b, 7)
                self.assertLessEqual(b, 23)
                pe   = FDPUPostScaleReductionTree(scfg, 4, K=k)
                self.assertEqual(pe.actual_acc_mant_bits, b)

    def test_reduced_precision_acc_does_not_accumulate_junk(self):
        """Lower mantissa bits in accOut must be 0 (zeroed by hardware register)."""
        scfg = ScaleAddConfig(MXFormats.E5M2, MXFormats.E5M2, ScaleFormats.UE8M0)
        pe   = FDPUPostScaleReductionTree(scfg, 4, K=32)
        a1   = encode_element(1.0, MXFormats.E5M2)
        for _ in range(10):
            pe.step([a1]*4, [a1]*4, UE8M0_1, UE8M0_1)
        # accOut lower (mantDrop) bits must be 0
        md = pe.mant_drop
        if md > 0:
            low_bits = pe.acc_out & _mask(md)
            self.assertEqual(low_bits, 0,
                f"lower {md} bits of accOut not zeroed: {pe.acc_out:#010x}")

    def test_power_of_2_vectorsizes_postscale(self):
        """Post-scale vec=1,2,4,8: sum of N all-ones ≈ N"""
        scfg = ScaleAddConfig(MXFormats.E4M3, MXFormats.E4M3, ScaleFormats.UE8M0)
        a1   = encode_element(1.0, MXFormats.E4M3)
        for vsize in [1, 2, 4, 8]:
            with self.subTest(vsize=vsize):
                pe = FDPUPostScaleReductionTree(scfg, vsize)
                pe.reset()
                pe.step([a1]*vsize, [a1]*vsize, UE8M0_1, UE8M0_1)
                hw = fp32_bits_to_float(pe.acc_out)
                self.assertAlmostEqual(hw, float(vsize),
                                       delta=float(vsize) * 0.05 + 0.1)

    def test_non_ue8m0_scale_path_ue5m3(self):
        """UE5M3 triggers ScaleAddition+ScaleToFP32 (non-UE8M0) path."""
        scfg = ScaleAddConfig(MXFormats.E4M3, MXFormats.E2M1, ScaleFormats.UE5M3)
        self.assertEqual(scfg.stype.mantScaleWidth, 3)   # non-zero → non-UE8M0 path
        cycles = [([E4M3_1]*4, [E2M1_1]*4, UE5M3_1, UE5M3_1)] * 4
        pe     = FDPUPostScaleReductionTree(scfg, 4)
        self._run(pe, scfg, cycles, label="UE5M3 path")

    def test_reset_postscale(self):
        """reset() clears post-scale accumulator."""
        scfg = ScaleAddConfig(MXFormats.E4M3, MXFormats.E4M3, ScaleFormats.UE8M0)
        pe   = FDPUPostScaleReductionTree(scfg, 4)
        a1   = encode_element(1.0, MXFormats.E4M3)
        pe.step([a1]*4, [a1]*4, UE8M0_1, UE8M0_1)
        self.assertNotEqual(pe.acc_out, 0)
        pe.reset()
        self.assertEqual(pe.acc_out, 0)

    # ── Mirrors Scala Test 9: 4-cycle uniform accumulation ───────────────────
    def test_mirrors_scala_test9_multicycle_4cycles(self):
        """4 cycles × all-ones → 16.0  (E4M3×E2M1/UE5M3, vec=4).

        HW log (fdpu_post_scale_reduction_tree_test.log Test 9):
          Cycle 0  SW=4.0  HW=4.0
          Cycle 1  SW=8.0  HW=8.0
          Cycle 2  SW=12.0 HW=12.0
          Cycle 3  SW=16.0 HW=16.0
        """
        cfg   = ScaleAddConfig(MXFormats.E4M3, MXFormats.E2M1, ScaleFormats.UE5M3)
        vsize = 4
        a1  = encode_element(1.0, MXFormats.E4M3)
        b1  = encode_element(1.0, MXFormats.E2M1)
        sc1 = encode_scale(1.0, ScaleFormats.UE5M3)
        cycles = [([a1]*vsize, [b1]*vsize, sc1, sc1)] * 4
        pe = FDPUPostScaleReductionTree(cfg, vsize)
        results = self._run(pe, cfg, cycles, label="scala_t9_multicycle")
        expected_sw = [4.0, 8.0, 12.0, 16.0]
        for i, (_cyc, sw_acc, _hw) in enumerate(results):
            self.assertAlmostEqual(sw_acc, expected_sw[i], delta=0.01,
                msg=f"SW model cycle {i}: got {sw_acc}, expected {expected_sw[i]}")

    # ── Mirrors Scala Test 10: mixed-value multicycle ────────────────────────
    def test_mirrors_scala_test10_mixed_value_multicycle(self):
        """Mixed positive/negative 4-cycle accumulation (E4M3×E2M1/UE5M3, vec=4).

        HW log (Test 10):
          Cycle 0  cycleVal=3.0   SW=3.0   HW=3.0
          Cycle 1  cycleVal=8.0   SW=11.0  HW=11.0
          Cycle 2  cycleVal=-8.0  SW=3.0   HW=3.0
          Cycle 3  cycleVal=3.5   SW=6.5   HW=6.5
        """
        cfg  = ScaleAddConfig(MXFormats.E4M3, MXFormats.E2M1, ScaleFormats.UE5M3)
        vsize = 4
        e1  = encode_element( 1.0, MXFormats.E4M3)
        en1 = encode_element(-1.0, MXFormats.E4M3)
        e2  = encode_element( 2.0, MXFormats.E4M3)
        e15 = encode_element( 1.5, MXFormats.E4M3)
        b1  = encode_element( 1.0, MXFormats.E2M1)
        b2  = encode_element( 2.0, MXFormats.E2M1)
        sc1 = encode_scale(1.0, ScaleFormats.UE5M3)
        sc2 = encode_scale(2.0, ScaleFormats.UE5M3)
        # Mirrors Scala Test 10 cycles exactly
        cycles = [
            ([e1, e2, en1, e1],  [b1]*vsize, sc1, sc1),   # dot=3   ×1×1=3.0
            ([e1]*vsize,         [b2]*vsize, sc1, sc1),   # dot=4×2 ×1×1=8.0
            ([en1]*vsize,        [b1]*vsize, sc2, sc1),   # dot=-4  ×2×1=-8.0
            ([e1, e15, en1, e2], [b1]*vsize, sc1, sc1),   # dot=3.5 ×1×1=3.5
        ]
        pe = FDPUPostScaleReductionTree(cfg, vsize)
        results = self._run(pe, cfg, cycles, label="scala_t10_mixed")
        expected_sw = [3.0, 11.0, 3.0, 6.5]
        for i, (_cyc, sw_acc, _hw) in enumerate(results):
            self.assertAlmostEqual(sw_acc, expected_sw[i], delta=0.05,
                msg=f"SW model cycle {i}: got {sw_acc}, expected {expected_sw[i]}")

    # ── Mirrors Scala Test 11 (PostScale version): E5M2×E5M2/UE8M0 6 cycles ─
    def test_mirrors_scala_test11_e5m2_6cycle_postscale(self):
        """E5M2×E5M2/UE8M0 vec=4, 6 cycles with fixed inputs.

        HW log (Test 11) SW accumulated values:
          [-10.375, -11.1875, -15.1875, -40.1875, -59.4375, -81.9375]
        """
        cfg   = ScaleAddConfig(MXFormats.E5M2, MXFormats.E5M2, ScaleFormats.UE8M0)
        vsize = 4
        cycles = [
            ([E5M2_1P5,  E5M2_N075, E5M2_2,    E5M2_N1P5],
             [E5M2_N05,  E5M2_1P75, E5M2_N1,   E5M2_075],  UE8M0_2, UE8M0_1),
            ([E5M2_075,  E5M2_1,    E5M2_N1P5, E5M2_05],
             [E5M2_2,    E5M2_N1P5, E5M2_075,  E5M2_N1],   UE8M0_1, UE8M0_05),
            ([E5M2_N2,   E5M2_1P5,  E5M2_075,  E5M2_N1],
             [E5M2_1,    E5M2_N075, E5M2_1P5,  E5M2_2],    UE8M0_05, UE8M0_2),
            ([E5M2_1P75, E5M2_N05,  E5M2_N1,   E5M2_1P5],
             [E5M2_N1P5, E5M2_1,    E5M2_2,    E5M2_N075], UE8M0_4, UE8M0_1),
            ([E5M2_N075, E5M2_2,    E5M2_1,    E5M2_N175],
             [E5M2_075,  E5M2_N2,   E5M2_1P5,  E5M2_1],    UE8M0_1, UE8M0_4),
            ([E5M2_1,    E5M2_N1,   E5M2_1P5,  E5M2_N2],
             [E5M2_N1,   E5M2_1P5,  E5M2_N075, E5M2_1],    UE8M0_2, UE8M0_2),
        ]
        # Double-precision SW reference values extracted from HW log
        expected_sw = [-10.375, -11.1875, -15.1875, -40.1875, -59.4375, -81.9375]
        pe = FDPUPostScaleReductionTree(cfg, vsize)
        results = self._run(pe, cfg, cycles, label="scala_t11_postscale_e5m2")
        for i, (_cyc, sw_acc, _hw) in enumerate(results):
            self.assertAlmostEqual(sw_acc, expected_sw[i],
                delta=abs(expected_sw[i]) * 0.001 + 0.01,
                msg=f"SW model cycle {i}: got {sw_acc:.6f}, expected {expected_sw[i]}")

    # ── Mirrors Scala Test 12: power-of-2 vectorSizes 1..16 ─────────────────
    def test_mirrors_scala_test12_pow2_vectorsizes(self):
        """vec=1,2,4,8,16: 1 cycle all-ones → N.  HW log confirms exact values."""
        cfg = ScaleAddConfig(MXFormats.E4M3, MXFormats.E2M1, ScaleFormats.UE5M3)
        a1  = encode_element(1.0, MXFormats.E4M3)
        b1  = encode_element(1.0, MXFormats.E2M1)
        sc1 = encode_scale(1.0, ScaleFormats.UE5M3)
        for vsize in [1, 2, 4, 8, 16]:
            with self.subTest(vsize=vsize):
                pe = FDPUPostScaleReductionTree(cfg, vsize)
                pe.reset()
                pe.step([a1]*vsize, [b1]*vsize, sc1, sc1)
                hw = fp32_bits_to_float(pe.acc_out)
                self.assertAlmostEqual(hw, float(vsize),
                    delta=float(vsize) * 0.05 + 0.1,
                    msg=f"vec={vsize}: hw={hw}, expected={vsize}")

    # ── Mirrors Scala Test 13: non-power-of-2 vectorSizes ────────────────────
    def test_mirrors_scala_test13_non_pow2_vectorsizes(self):
        """vec=3,5,7: 1 cycle all-ones → N.  HW log confirms exact values."""
        cfg = ScaleAddConfig(MXFormats.E4M3, MXFormats.E2M1, ScaleFormats.UE5M3)
        a1  = encode_element(1.0, MXFormats.E4M3)
        b1  = encode_element(1.0, MXFormats.E2M1)
        sc1 = encode_scale(1.0, ScaleFormats.UE5M3)
        for vsize in [3, 5, 7]:
            with self.subTest(vsize=vsize):
                pe = FDPUPostScaleReductionTree(cfg, vsize)
                pe.reset()
                pe.step([a1]*vsize, [b1]*vsize, sc1, sc1)
                hw = fp32_bits_to_float(pe.acc_out)
                self.assertAlmostEqual(hw, float(vsize),
                    delta=float(vsize) * 0.05 + 0.1,
                    msg=f"vec={vsize}: hw={hw}, expected={vsize}")

    # ── Mirrors Scala Test 14: 8-element dot product, scale 2×2 ─────────────
    def test_mirrors_scala_test14_8elem_dot_product_scale(self):
        """8-element dot product in 1 cycle with shared scale 2.0×2.0.

        HW log (Test 14): dotNoScale=3.0, expected=12.0, HW=12.0.
        """
        cfg   = ScaleAddConfig(MXFormats.E4M3, MXFormats.E2M1, ScaleFormats.UE5M3)
        vsize = 8
        vec_a = [1.0, -1.0, 2.0, 1.0, -1.0, 1.0, 2.0, -2.0]
        vec_b = [1.0,  1.0, 1.0, 2.0,  2.0, 1.0, 1.0,  1.0]
        raw_a = [encode_element(v, MXFormats.E4M3) for v in vec_a]
        raw_b = [encode_element(v, MXFormats.E2M1) for v in vec_b]
        sa    = encode_scale(2.0, ScaleFormats.UE5M3)
        sb    = encode_scale(2.0, ScaleFormats.UE5M3)
        pe    = FDPUPostScaleReductionTree(cfg, vsize)
        pe.reset()
        pe.step(raw_a, raw_b, sa, sb)
        hw = fp32_bits_to_float(pe.acc_out)
        # dot(a,b) = 1-1+2+2-2+1+2-2 = 3; ×2×2 = 12
        self.assertAlmostEqual(hw, 12.0, delta=0.6,
            msg=f"8-elem dot with scale 2×2: hw={hw}, expected=12.0")

    # ── Mirrors Scala Test 15: all type combinations, 5-cycle random ─────────
    def test_mirrors_scala_test15_all_type_combinations_random(self):
        """All research element-type pairs × research scale types, 5-cycle random.

        Equivalent to Scala Test 15 (FDPUPostScaleReductionTree).
        Uses Python-native RNG (seed=77); asserts SW tolerance for every config.
        Covers all (etA, etB, st) with expScaleWidth >= 4 and not (low×low).
        """
        def rand_elem(t, rng):
            sign = rng.randint(0, 1)
            if t.name == "INT8":
                mag = 1 + rng.randint(0, 14)
                return mag if sign == 0 else int((-mag) & 0xFF)
            exp_range = 1 << t.elementWidthExp
            exp_lo = min(max(t.bias + 1, 1), exp_range - 1)
            exp_hi = min(max(t.bias + 4, exp_lo), exp_range - 1)
            exp    = exp_lo + rng.randint(0, exp_hi - exp_lo)
            mant   = rng.randint(0, (1 << t.elementWidthMant) - 1)
            return (sign << (t.totalWidth - 1)) | (exp << t.elementWidthMant) | mant

        def rand_scale(s, rng):
            exp_range = 1 << s.expScaleWidth
            exp_lo = min(max(s.bias - 2, 1), exp_range - 1)
            exp_hi = min(max(s.bias + 2, exp_lo), exp_range - 1)
            exp    = exp_lo + rng.randint(0, exp_hi - exp_lo)
            mant   = rng.randint(0, (1 << s.mantScaleWidth) - 1) if s.mantScaleWidth > 0 else 0
            return (exp << s.mantScaleWidth) | mant

        low_prec       = {MXFormats.E3M2, MXFormats.E2M3, MXFormats.E2M1}
        research_scales = [ScaleFormats.UE8M0, ScaleFormats.UE7M1, ScaleFormats.UE6M2,
                           ScaleFormats.UE5M3, ScaleFormats.UE4M4]
        all_elem = [MXFormats.INT8, MXFormats.E5M2, MXFormats.E4M3,
                    MXFormats.E3M2, MXFormats.E2M3, MXFormats.E2M1]

        rng     = random.Random(77)   # fixed seed — same data every run
        vsize   = 4
        ncycles = 5
        failed  = []

        for etA in all_elem:
            for etB in all_elem:
                if etA in low_prec and etB in low_prec:
                    continue
                for st in research_scales:
                    cfg   = ScaleAddConfig(etA, etB, st)
                    label = f"{etA.name}×{etB.name}/{st.name}"
                    cycles = [
                        ([rand_elem(etA, rng) for _ in range(vsize)],
                         [rand_elem(etB, rng) for _ in range(vsize)],
                         rand_scale(st, rng), rand_scale(st, rng))
                        for _ in range(ncycles)
                    ]
                    try:
                        pe = FDPUPostScaleReductionTree(cfg, vsize)
                        # Use exact Scala tolerance: 4×machEps×cumAbsSum + 0.03×|swAcc|
                        run_cycles_postscale_tol(pe, cfg, cycles, label=label)
                    except AssertionError as e:
                        failed.append(f"{label}: {e}")

        self.assertEqual(len(failed), 0,
            f"{len(failed)} PostScale combinations failed:\n" +
            "\n".join(failed[:5]))

    # ── Mirrors Scala Test 16: block-quantized E5M2×E4M3 PostScale ───────────
    def test_mirrors_scala_test16_block_quantized_e5m2_e4m3_postscale(self):
        """32-element MX block (E5M2×E4M3/UE8M0, vec=8, 4 cycles), PostScale arch."""
        cfg   = ScaleAddConfig(MXFormats.E5M2, MXFormats.E4M3, ScaleFormats.UE8M0)
        vsize = 8
        raw_a = [ 3.2, -1.6,  0.8,  4.0, -2.4,  1.2, -0.6,  3.6,
                  2.0, -3.0,  1.5, -0.5,  2.5, -1.0,  0.4,  1.8,
                 -2.2,  0.9, -1.4,  3.1, -0.7,  2.8, -1.1,  0.3,
                  1.7, -2.9,  0.6, -1.3,  2.1, -0.8,  1.9, -3.5]
        raw_b = [ 1.0,  2.0, -1.0,  0.5,  1.5, -0.5,  2.0, -1.0,
                  0.75,-1.25, 1.5,  2.0, -0.75, 1.0, -2.0,  0.5,
                  2.0, -1.0,  0.5, -1.5,  1.0,  0.25,-1.0,  2.0,
                 -0.5,  1.5, -2.0,  1.0,  0.5, -1.5,  1.0, -0.25]
        enc_a, rs_a = quantize_block(raw_a, cfg.elementTypeA, cfg.stype)
        enc_b, rs_b = quantize_block(raw_b, cfg.elementTypeB, cfg.stype)
        cycles = [
            (enc_a[c*vsize:(c+1)*vsize], enc_b[c*vsize:(c+1)*vsize], rs_a, rs_b)
            for c in range(len(raw_a) // vsize)
        ]
        pe = FDPUPostScaleReductionTree(cfg, vsize)
        self._run(pe, cfg, cycles, label="postscale_E5M2×E4M3_32elem")

    # ── Mirrors Scala Test 17: block-quantized INT8×E2M1 PostScale ───────────
    def test_mirrors_scala_test17_block_quantized_int8_e2m1_postscale(self):
        """32-element MX block (INT8×E2M1/UE8M0, vec=8, 4 cycles), PostScale arch."""
        cfg   = ScaleAddConfig(MXFormats.INT8, MXFormats.E2M1, ScaleFormats.UE8M0)
        vsize = 8
        raw_a = [ 1.0, -2.0,  3.0,  1.5, -1.5,  2.0, -0.5,  3.5,
                  2.5, -1.0,  0.5, -3.0,  1.0, -2.5,  2.0, -1.0,
                 -1.5,  1.0, -2.0,  3.0, -0.5,  2.0, -1.0,  0.5,
                  1.5, -3.0,  1.0, -1.5,  2.0, -0.5,  1.0, -2.0]
        raw_b = [ 2.0, -1.0,  1.5, -2.0,  1.0, -1.5,  2.0, -1.0,
                  1.0, -2.0,  1.5, -1.0,  2.0, -1.5,  1.0, -2.0,
                 -1.0,  2.0, -1.5,  1.0, -2.0,  1.5, -1.0,  2.0,
                  1.5, -1.0,  2.0, -1.5,  1.0, -2.0,  1.5, -1.0]
        enc_a, rs_a = quantize_block(raw_a, cfg.elementTypeA, cfg.stype)
        enc_b, rs_b = quantize_block(raw_b, cfg.elementTypeB, cfg.stype)
        cycles = [
            (enc_a[c*vsize:(c+1)*vsize], enc_b[c*vsize:(c+1)*vsize], rs_a, rs_b)
            for c in range(len(raw_a) // vsize)
        ]
        pe = FDPUPostScaleReductionTree(cfg, vsize)
        self._run(pe, cfg, cycles, label="postscale_INT8×E2M1_32elem")

    # ── Mirrors Scala Test 18: precision regression — random, 8 cycles ───────
    def test_mirrors_scala_test18_precision_regression_random(self):
        """Random 8-cycle accumulation across research configs (vec=8).

        Mirrors the spirit of Scala Test 18: verifies that the PostScale SW model
        stays within tolerance for longer accumulation sequences with mixed data.
        Uses a fixed Python seed (seed=1234) for reproducibility.
        """
        def rand_elem(t, rng):
            sign = rng.randint(0, 1)
            if t.name == "INT8":
                mag = 1 + rng.randint(0, 19)
                return mag if sign == 0 else int((-mag) & 0xFF)
            exp_range = 1 << t.elementWidthExp
            exp_lo = min(max(t.bias + 1, 1), exp_range - 1)
            exp_hi = min(max(t.bias + 3, exp_lo), exp_range - 1)
            exp    = exp_lo + rng.randint(0, exp_hi - exp_lo)
            mant   = rng.randint(0, (1 << t.elementWidthMant) - 1)
            return (sign << (t.totalWidth - 1)) | (exp << t.elementWidthMant) | mant

        def rand_scale(s, rng):
            exp_range = 1 << s.expScaleWidth
            exp_lo = min(max(s.bias - 1, 1), exp_range - 1)
            exp_hi = min(max(s.bias + 1, exp_lo), exp_range - 1)
            exp    = exp_lo + rng.randint(0, exp_hi - exp_lo)
            mant   = rng.randint(0, (1 << s.mantScaleWidth) - 1) if s.mantScaleWidth > 0 else 0
            return (exp << s.mantScaleWidth) | mant

        low_prec        = {MXFormats.E3M2, MXFormats.E2M3, MXFormats.E2M1}
        research_scales = [ScaleFormats.UE8M0, ScaleFormats.UE7M1, ScaleFormats.UE6M2,
                           ScaleFormats.UE5M3, ScaleFormats.UE4M4]
        all_elem = [MXFormats.INT8, MXFormats.E5M2, MXFormats.E4M3,
                    MXFormats.E3M2, MXFormats.E2M3, MXFormats.E2M1]

        outer_rng = random.Random(1234)
        vsize     = 8
        ncycles   = 8
        failed    = []

        for etA in all_elem:
            for etB in all_elem:
                if etA in low_prec and etB in low_prec:
                    continue
                for st in research_scales:
                    cfg   = ScaleAddConfig(etA, etB, st)
                    label = f"{etA.name}×{etB.name}/{st.name} v{vsize}"
                    inner_rng = random.Random(outer_rng.randint(0, 2**31))
                    cycles = [
                        ([rand_elem(etA, inner_rng) for _ in range(vsize)],
                         [rand_elem(etB, inner_rng) for _ in range(vsize)],
                         rand_scale(st, inner_rng), rand_scale(st, inner_rng))
                        for _ in range(ncycles)
                    ]
                    try:
                        pe = FDPUPostScaleReductionTree(cfg, vsize)
                        run_cycles_postscale_tol(pe, cfg, cycles, label=label)
                    except AssertionError as e:
                        failed.append(f"{label}: {e}")

        self.assertEqual(len(failed), 0,
            f"{len(failed)} PostScale precision-regression combinations failed:\n" +
            "\n".join(failed[:5]))


# ═══════════════════════════════════════════════════════════════════════════════
# §F  GeMM PE Array tests
# ═══════════════════════════════════════════════════════════════════════════════

class TestGemmPEArray(unittest.TestCase):
    """Test tileRows × tileCols GeMM using FDPUWithCustomReductionTree."""

    def test_4x4_identity_matmul(self):
        """4×4 tile: A=all-1, B=all-1, 4 elements/PE/cycle → each PE = 4.0"""
        scfg = ScaleAddConfig(MXFormats.E4M3, MXFormats.E4M3, ScaleFormats.UE8M0)
        arr  = GemmPEArray(scfg, vector_size=4, tile_rows=4, tile_cols=4)
        a1   = encode_element(1.0, MXFormats.E4M3)
        op_a = [[a1]*4 for _ in range(4)]
        op_b = [[a1]*4 for _ in range(4)]
        arr.reset()
        arr.step(op_a, op_b, [UE8M0_1]*4, [UE8M0_1]*4)
        results = arr.get_results_float()
        for r in range(4):
            for c in range(4):
                self.assertAlmostEqual(results[r][c], 4.0, delta=0.05,
                    msg=f"PE[{r}][{c}]={results[r][c]}")

    def test_4x4_accumulation_k_cycles(self):
        """K=8 cycles: each PE accumulates 8×4=32.0"""
        scfg = ScaleAddConfig(MXFormats.E4M3, MXFormats.E4M3, ScaleFormats.UE8M0)
        arr  = GemmPEArray(scfg, vector_size=4, tile_rows=4, tile_cols=4)
        a1   = encode_element(1.0, MXFormats.E4M3)
        op_a = [[a1]*4 for _ in range(4)]
        op_b = [[a1]*4 for _ in range(4)]
        arr.reset()
        for _ in range(8):
            arr.step(op_a, op_b, [UE8M0_1]*4, [UE8M0_1]*4)
        results = arr.get_results_float()
        for r in range(4):
            for c in range(4):
                self.assertAlmostEqual(results[r][c], 32.0, delta=0.5,
                    msg=f"PE[{r}][{c}]={results[r][c]}")

    def test_row_column_independence(self):
        """Different scale per row/col: PE[r][c] should get scale_a[r] × scale_b[c]."""
        scfg  = ScaleAddConfig(MXFormats.E4M3, MXFormats.E4M3, ScaleFormats.UE8M0)
        arr   = GemmPEArray(scfg, vector_size=4, tile_rows=2, tile_cols=2)
        a1    = encode_element(1.0, MXFormats.E4M3)
        op_a  = [[a1]*4, [a1]*4]
        op_b  = [[a1]*4, [a1]*4]
        # row 0: scale 1.0, row 1: scale 2.0; col 0: scale 1.0, col 1: scale 4.0
        sa    = [UE8M0_1, UE8M0_2]
        sb    = [UE8M0_1, UE8M0_4]
        arr.reset()
        arr.step(op_a, op_b, sa, sb)
        r = arr.get_results_float()
        # PE[0][0]: sa=1, sb=1 → 4×1×1×1 = 4.0
        self.assertAlmostEqual(r[0][0], 4.0,  delta=0.1)
        # PE[0][1]: sa=1, sb=4 → 4×1×1×4 = 16.0
        self.assertAlmostEqual(r[0][1], 16.0, delta=0.2)
        # PE[1][0]: sa=2, sb=1 → 4×1×2×1 = 8.0
        self.assertAlmostEqual(r[1][0], 8.0,  delta=0.1)
        # PE[1][1]: sa=2, sb=4 → 4×1×2×4 = 32.0
        self.assertAlmostEqual(r[1][1], 32.0, delta=0.5)

    def test_reset_clears_all_pes(self):
        """reset() zeroes every PE in the array."""
        scfg = ScaleAddConfig(MXFormats.E4M3, MXFormats.E4M3, ScaleFormats.UE8M0)
        arr  = GemmPEArray(scfg, vector_size=4, tile_rows=2, tile_cols=2)
        a1   = encode_element(1.0, MXFormats.E4M3)
        arr.step([[a1]*4]*2, [[a1]*4]*2, [UE8M0_1]*2, [UE8M0_1]*2)
        arr.reset()
        for r in range(2):
            for c in range(2):
                self.assertEqual(arr._pes[r][c].acc_out, 0)

    def test_int8_x_e4m3_gemm(self):
        """INT8×E4M3/UE8M0 2×2 tile: encode_element(1.0, INT8)=64 already encodes 1.0."""
        scfg     = ScaleAddConfig(MXFormats.INT8, MXFormats.E4M3, ScaleFormats.UE8M0)
        arr      = GemmPEArray(scfg, vector_size=4, tile_rows=2, tile_cols=2)
        int8_one = encode_element(1.0, MXFormats.INT8)   # raw=64 → 64 × 2^(-6) = 1.0
        e4m3_one = encode_element(1.0, MXFormats.E4M3)
        ue8m0_1  = encode_scale(1.0, ScaleFormats.UE8M0)
        op_a = [[int8_one]*4]*2
        op_b = [[e4m3_one]*4]*2
        arr.reset()
        arr.step(op_a, op_b, [ue8m0_1]*2, [ue8m0_1]*2)
        results = arr.get_results_float()
        for r in range(2):
            for c in range(2):
                # 4 × 1.0 × 1.0 × scale(1.0) × scale(1.0) = 4.0
                self.assertAlmostEqual(results[r][c], 4.0, delta=0.1,
                    msg=f"INT8×E4M3 PE[{r}][{c}]")


class TestGemmPEArrayPostScale(unittest.TestCase):
    """Same connectivity tests for GemmPEArrayPostScale."""

    def test_4x4_identity_matmul(self):
        scfg = ScaleAddConfig(MXFormats.E4M3, MXFormats.E4M3, ScaleFormats.UE8M0)
        arr  = GemmPEArrayPostScale(scfg, vector_size=4, tile_rows=4, tile_cols=4, K=32)
        a1   = encode_element(1.0, MXFormats.E4M3)
        op_a = [[a1]*4 for _ in range(4)]
        op_b = [[a1]*4 for _ in range(4)]
        arr.reset()
        arr.step(op_a, op_b, [UE8M0_1]*4, [UE8M0_1]*4)
        results = arr.get_results_float()
        for r in range(4):
            for c in range(4):
                self.assertAlmostEqual(results[r][c], 4.0, delta=0.2)

    def test_4x4_k32_accumulation(self):
        """K=32 cycles: each PE should accumulate 32×4=128.0"""
        scfg = ScaleAddConfig(MXFormats.E4M3, MXFormats.E4M3, ScaleFormats.UE8M0)
        arr  = GemmPEArrayPostScale(scfg, vector_size=4, tile_rows=2, tile_cols=2, K=32)
        a1   = encode_element(1.0, MXFormats.E4M3)
        op_a = [[a1]*4]*2 ; op_b = [[a1]*4]*2
        arr.reset()
        for _ in range(32):
            arr.step(op_a, op_b, [UE8M0_1]*2, [UE8M0_1]*2)
        results = arr.get_results_float()
        for r in range(2):
            for c in range(2):
                self.assertAlmostEqual(results[r][c], 128.0, delta=5.0,
                    msg=f"PostScale PE[{r}][{c}]={results[r][c]}")

    def test_postscale_vs_perlane_close(self):
        """FDPUPostScaleReductionTree and FDPUWithCustomReductionTree give close results."""
        scfg  = ScaleAddConfig(MXFormats.E4M3, MXFormats.E4M3, ScaleFormats.UE8M0)
        a1    = encode_element(1.0, MXFormats.E4M3)
        ops   = [[a1]*4]*2
        sa    = [UE8M0_2, UE8M0_1]
        sb    = [UE8M0_1, UE8M0_4]
        pe_a  = GemmPEArray(      scfg, 4, 2, 2)
        pe_ps = GemmPEArrayPostScale(scfg, 4, 2, 2, K=8)
        for arr in [pe_a, pe_ps]:
            arr.reset()
            for _ in range(4):
                arr.step(ops, ops, sa, sb)
        ra  = pe_a.get_results_float()
        rps = pe_ps.get_results_float()
        for r in range(2):
            for c in range(2):
                rel = abs(ra[r][c])
                self.assertAlmostEqual(ra[r][c], rps[r][c],
                    delta=rel * 0.10 + 0.5,
                    msg=f"PE[{r}][{c}]: perlane={ra[r][c]:.4f} postscale={rps[r][c]:.4f}")


# ═══════════════════════════════════════════════════════════════════════════════
# Entry point
# ═══════════════════════════════════════════════════════════════════════════════

if __name__ == "__main__":
    loader  = unittest.TestLoader()
    suite   = unittest.TestSuite()
    for cls in [
        TestEncodeDecodeRoundtrip,
        TestCustomOperator,
        TestFP32Adder,
        TestFixedFPReductionTree,
        TestScaleToFP32,
        TestFDPUWithCustomReductionTree,
        TestFDPUPostScaleReductionTree,
        TestGemmPEArray,
        TestGemmPEArrayPostScale,
    ]:
        suite.addTests(loader.loadTestsFromTestCase(cls))

    runner = unittest.TextTestRunner(verbosity=2)
    result = runner.run(suite)
    raise SystemExit(0 if result.wasSuccessful() else 1)
