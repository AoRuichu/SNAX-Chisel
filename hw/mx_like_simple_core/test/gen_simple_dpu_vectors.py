#!/usr/bin/env python3
"""Per-config test-vector generator for SimpleDPU using realistic LLM
workload distribution + MX block quantization (production `quantize_mx_v6`).

Adapted from `hw/mx_like_tensor_core/test/gen_pe_testvectors.py`. Key
adaptations for simple_core:
  - Per-lane fields (sign, exp, mant) instead of packed op_a/op_b word
  - BF16 output (no M_acc knob)
  - No macc_final_selection.csv dependency
  - Scale broken into (exp, mant) fields instead of raw byte

Reuses `quantize.py` from `hw/chisel_acc/` (the canonical copy on this
tree — `snax-mx` submodule may be missing).

Output JSON contains everything the testbench needs to drive the DUT and
self-check against the FP64 golden dot product.

Usage:
    python3 test/gen_simple_dpu_vectors.py --act E4M3 --weight E4M3 \\
        --scale UE6M2 --K 64 --outdir vectors/
"""

from __future__ import annotations
import argparse
import json
import os
import sys
import numpy as np

# ── Import production quantizer from chisel_acc ─────────────────
_QUANTIZE_DIR = os.path.join(os.path.dirname(__file__), "..", "..", "chisel_acc")
sys.path.insert(0, os.path.abspath(_QUANTIZE_DIR))
import quantize  # noqa: E402


class _ScaleFormatN(quantize.ScaleFormat):
    """ScaleFormat variant that allows ebits + mbits != 8 (e.g. 7-bit UE4M3)."""
    def __init__(self, ebits: int, mbits: int):
        assert ebits > 0 and mbits > 0
        self.ebits = ebits
        self.mbits = mbits
        self.bias = (1 << (ebits - 1)) - 1
        max_exp_b = (1 << ebits) - 1
        max_mant = (1 << mbits) - 1
        self.max_val = (2.0 ** (max_exp_b - self.bias)) * (1.0 + max_mant / (2 ** mbits))
        self.saturate_raw = (max_exp_b << mbits) | max_mant
        self.min_raw = 1
        self.min_val = (2.0 ** (1 - self.bias)) / (2 ** mbits)


if "UE4M3" not in quantize.VALID_SCALE_FORMATS:
    quantize.VALID_SCALE_FORMATS["UE4M3"] = _ScaleFormatN(4, 3)


# ── Simple-core element format tables (parallel to Parameter.scala) ──

# name -> (e_bits, m_bits, has_hidden_bit, impSc, quantize_dtype)
ELEM_TABLE = {
    "E5M2": (5, 2, True, 0, "fp8_e5m2"),
    "E4M3": (4, 3, True, 0, "fp8_e4m3"),
    "E3M2": (3, 2, True, 0, "fp6_e3m2"),
    "E2M3": (2, 3, True, 0, "fp6_e2m3"),
    "E2M1": (2, 1, True, 0, "fp4_e2m1"),
    "INT8": (0, 7, False, -6, "mxint8"),
}

# name -> (e_bits, m_bits)
SCALE_TABLE = {
    "UE8M0": (8, 0),
    "UE7M1": (7, 1),
    "UE6M2": (6, 2),
    "UE5M3": (5, 3),
    "UE4M4": (4, 4),
    "UE4M3": (4, 3),
}


# ── Workload generation (verbatim port) ────────────────────────

def gen_workload_fitted(K: int, *, seed: int, act_std: float, weight_std: float,
                        outlier_frac: float = 0.05,
                        outlier_gain: tuple = (8.0, 32.0)):
    """Transformer-like: clean Gaussian weights + outlier-boosted activations."""
    rng = np.random.default_rng(seed)
    B = (rng.standard_normal((K, 1)) * weight_std).astype(np.float32)
    A = (rng.standard_normal((1, K)) * act_std).astype(np.float32)
    n_out = max(1, int(round(outlier_frac * K)))
    cols = rng.choice(K, size=n_out, replace=False)
    gains = rng.uniform(outlier_gain[0], outlier_gain[1], size=n_out).astype(np.float32)
    A[:, cols] *= gains
    return A, B


def gen_workload_gaussian(K: int, *, seed: int, variance: float):
    rng = np.random.default_rng(seed)
    A = (rng.standard_normal((1, K)) * variance).astype(np.float32)
    B = (rng.standard_normal((K, 1)) * variance).astype(np.float32)
    return A, B


# ── Raw byte → per-lane (sign, exp, mant) decoders ─────────────

def decode_elem_raw(el_name: str, raw: int) -> tuple[int, int, int]:
    """Decode a quantize.py raw byte into (sign, biased_exp, mant) matching
    SimpleDPU's ElemOperand IO bundle."""
    e, m, has_hidden, _, _ = ELEM_TABLE[el_name]
    if not has_hidden:
        # INT8: quantize.py stores 2's complement signed int; SimpleDPU expects
        # sign-magnitude (sign bit + 7-bit unsigned magnitude).
        r = int(raw)
        if r >= 128: r -= 256                     # 2's-complement -> signed
        sign = 1 if r < 0 else 0
        mant = abs(r) & 0x7F                       # max |x| = 127 for MX INT8
        return sign, 0, mant
    # FP path — raw is a uint8/uint16 with meaningful low (1+e+m) bits.
    total_w = 1 + e + m
    r = int(raw) & ((1 << total_w) - 1)
    sign = (r >> (e + m)) & 1
    exp  = (r >> m) & ((1 << e) - 1)
    mant = r & ((1 << m) - 1)
    return sign, exp, mant


def decode_scale_raw(sc_name: str, raw: int) -> tuple[int, int]:
    """Return (biased_exp, mant) for the DUT's scale IO fields."""
    e, m = SCALE_TABLE[sc_name]
    r = int(raw)
    if m == 0:
        return r & ((1 << e) - 1), 0
    exp  = (r >> m) & ((1 << e) - 1)
    mant = r & ((1 << m) - 1)
    return exp, mant


# ── Vector builder ─────────────────────────────────────────────

def build_vector(act: str, weight: str, scale: str, *,
                 K: int, block_size: int, vector_size: int,
                 seed: int, workload: str,
                 act_std: float, weight_std: float, variance: float) -> dict:
    if act not in ELEM_TABLE or weight not in ELEM_TABLE:
        raise ValueError(f"Unknown element format {act}/{weight}")
    if scale not in SCALE_TABLE:
        raise ValueError(f"Unknown scale {scale!r}")
    if scale not in quantize.VALID_SCALE_FORMATS:
        raise ValueError(f"scale {scale!r} not registered with quantize")
    if K % block_size != 0:
        raise ValueError(f"K({K}) must be a multiple of block_size({block_size})")
    if block_size % vector_size != 0:
        raise ValueError(f"block_size({block_size}) must be a multiple of vector_size({vector_size})")

    dtA = ELEM_TABLE[act][-1]
    dtB = ELEM_TABLE[weight][-1]

    if workload == "gaussian":
        A_fp32, B_fp32 = gen_workload_gaussian(K, seed=seed, variance=variance)
    else:
        A_fp32, B_fp32 = gen_workload_fitted(K, seed=seed,
                                              act_std=act_std, weight_std=weight_std)

    qA, _, scA_raw, rawA = quantize.quantize_mx_v6(
        A_fp32, dtA, block_size=block_size, axis=1, scale_format=scale)
    qB, _, scB_raw, rawB = quantize.quantize_mx_v6(
        B_fp32, dtB, block_size=block_size, axis=0, scale_format=scale)

    A_deq = qA[0, :].astype(np.float64)
    B_deq = qB[:, 0].astype(np.float64)
    golden_dot = float(np.dot(A_deq, B_deq))

    n_cycles = K // vector_size
    cycles = []
    for c in range(n_cycles):
        blk = (c * vector_size) // block_size
        # Per-lane decoded fields
        lanes_a = []
        lanes_w = []
        for i in range(vector_size):
            k = c * vector_size + i
            sA, eA, mA_ = decode_elem_raw(act, int(rawA[0, k]))
            sW, eW, mW_ = decode_elem_raw(weight, int(rawB[k, 0]))
            lanes_a.append({"sign": sA, "exp": eA, "mant": mA_})
            lanes_w.append({"sign": sW, "exp": eW, "mant": mW_})
        seA, smA = decode_scale_raw(scale, int(scA_raw[0, blk]))
        seW, smW = decode_scale_raw(scale, int(scB_raw[blk, 0]))
        cycles.append({
            "lanes_a": lanes_a,
            "lanes_w": lanes_w,
            "scaleA": {"exp": seA, "mant": smA},
            "scaleW": {"exp": seW, "mant": smW},
        })

    return {
        "act": act, "weight": weight, "scale": scale,
        "K": K, "block_size": block_size, "vector_size": vector_size,
        "n_cycles": n_cycles,
        "seed": seed, "workload": workload,
        "act_std": act_std, "weight_std": weight_std,
        "golden_dot": golden_dot,
        "A_deq": [float(x) for x in A_deq],
        "B_deq": [float(x) for x in B_deq],
        "cycles": cycles,
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--act", required=True)
    ap.add_argument("--weight", required=True)
    ap.add_argument("--scale", required=True)
    ap.add_argument("--K", type=int, default=64)
    ap.add_argument("--block-size", type=int, default=16)
    ap.add_argument("--vector-size", type=int, default=4)
    ap.add_argument("--seed", type=int, default=0)
    ap.add_argument("--workload", default="fitted", choices=["fitted", "gaussian"])
    ap.add_argument("--act-std", type=float, default=0.372)
    ap.add_argument("--weight-std", type=float, default=0.0366)
    ap.add_argument("--variance", type=float, default=1.0)
    ap.add_argument("--outdir", default=os.path.join(os.path.dirname(__file__), "vectors"))
    args = ap.parse_args()

    vec = build_vector(args.act, args.weight, args.scale,
                       K=args.K, block_size=args.block_size,
                       vector_size=args.vector_size, seed=args.seed,
                       workload=args.workload,
                       act_std=args.act_std, weight_std=args.weight_std,
                       variance=args.variance)
    os.makedirs(args.outdir, exist_ok=True)
    tag = f"{args.act}_{args.weight}_{args.scale}"
    out = os.path.join(args.outdir,
                       f"{tag}_K{args.K}_bs{args.block_size}_vec{args.vector_size}_seed{args.seed}.json")
    with open(out, "w") as f:
        json.dump(vec, f, indent=1)
    print(f"Wrote {out}  (golden_dot={vec['golden_dot']:.6f})")


if __name__ == "__main__":
    main()
