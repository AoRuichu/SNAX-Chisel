#!/usr/bin/env python3
"""Generate NN-realistic input vectors for AccTruncationSweepTest (Scala).

For each (workload, typeA, typeB, scaleType, K) configuration, produces a
TSV file under ``acc_trunc_vectors/<workload>/`` containing the MX-quantised
inputs that the Scala test should feed to the HW DUT.

Pure-Gaussian workloads matched to measured Qwen2.5-3B layer statistics
(no synthetic outlier injection — the measured σ already captures the
full distribution width):
    q_proj_l0     σ_act=0.372,  σ_w=0.0366  (clean, well-behaved attention proj)
    gate_proj_l1  σ_act=9.420,  σ_w=0.0120  (heavy variance, MLP gate)

Sweep coverage:  K = 2048 (matches MX-format MAC accumulation depth per
output channel in Qwen-2.5-3B; one trial = one output channel).

File format (one TSV per (workload, A, B, scale, K)):
    trial \\t cycle \\t sA_raw \\t sB_raw \\t a0..a{V-1} \\t b0..b{V-1}
"""

import os
import sys
import importlib.util
from pathlib import Path

import numpy as np

# ── Load quantize_mx_v2 from quantize(4).py ─────────────────────────────────
_spec = importlib.util.spec_from_file_location("quantize", "quantize.py")
_q4   = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_q4)
quantize_mx_v6 = _q4.quantize_mx_v6

# ── Format mappings (Scala name → quantize_mx_v2 dtype string) ──────────────
ELEM_TO_DTYPE = {
    "INT8": "mxint8",
    "E5M2": "fp8_e5m2",
    "E4M3": "fp8_e4m3",
    "E3M2": "fp6_e3m2",
    "E2M3": "fp6_e2m3",
    "E2M1": "fp4_e2m1",
}

# ── Workload presets — pure Gaussian, σ from measured Qwen2.5-3B layers ──
# Stats from layer_stats(1).csv (Qwen2.5-3B forward pass):
#   q_proj_l0    = model.layers.0.self_attn.q_proj   → σ_act = 0.372, σ_w = 0.0366
#   gate_proj_l1 = model.layers.1.mlp.gate_proj      → σ_act = 9.420, σ_w = 0.0120
# Pure Gaussian — no outlier injection.  The measured σ already characterises
# the full layer distribution; synthetic outlier injection would inflate
# the realised σ above the measured value and bias the validation.
WORKLOADS = {
    "q_proj_l0":    dict(sigma_act=0.372, sigma_w=0.0366),
    "gate_proj_l1": dict(sigma_act=9.420, sigma_w=0.0120),
}

# ── Sweep dimensions ────────────────────────────────────────────────────────
VEC         = 4
PAIRS       = [("E4M3", "INT8"),
               ("E2M3", "E2M3"),
               ("E4M3", "E4M3"),
               ("E5M2", "E4M3"),
               ("E5M2", "E5M2"),
               ("E2M1", "E2M1"),
               ("E4M3", "E2M1"),     # A8W4 LLM combo
               ("E5M2", "INT8"),     # heterogeneous (A8W8 alt)
               ("E3M2", "E3M2"),
               ("INT8", "INT8")]
SCALES      = ["UE8M0", "UE6M2", "UE4M4"]
N_TRIALS    = 256                 # output-channel samples per workload — enough for stable median/p95
BLOCK_SIZE  = 16                  # NVFP4-style micro-block size (16 elements per shared scale)
CPB         = BLOCK_SIZE // VEC   # 4 cycles per block (V=4)
ROOT        = Path("acc_trunc_vectors")
ROOT.mkdir(exist_ok=True)

# Sweep matrix (workload → list of Ks to generate).
# K=2048 matches the GEMM inner dim of Qwen2.5-3B attention and MLP layers
# (one trial = one output channel accumulation).  Skip-if-exists handles re-runs.
SWEEP_MATRIX = {
    "q_proj_l0":    [2048],
    "gate_proj_l1": [2048],
}


def gen_activation_matrix(n_trials: int, n_elems: int, workload: str,
                          rng: np.random.Generator) -> np.ndarray:
    """Pure Gaussian sample with the layer's measured σ_act."""
    cfg = WORKLOADS[workload]
    return rng.normal(0.0, cfg["sigma_act"], (n_trials, n_elems)).astype(np.float32)


def gen_weight_matrix(n_trials: int, n_elems: int, workload: str,
                      rng: np.random.Generator) -> np.ndarray:
    cfg = WORKLOADS[workload]
    return rng.normal(0.0, cfg["sigma_w"], (n_trials, n_elems)).astype(np.float32)


def generate_one_config(workload: str, typeA: str, typeB: str,
                        scale: str, K: int) -> Path:
    """Produce one TSV under ROOT/<workload>/ with K*N_TRIALS cycle rows."""
    n_elems = K * VEC
    seed = abs(hash((workload, typeA, typeB, scale, K))) & 0xFFFFFFFF
    rng = np.random.default_rng(seed)

    A_fp32 = gen_activation_matrix(N_TRIALS, n_elems, workload, rng)
    B_fp32 = gen_weight_matrix    (N_TRIALS, n_elems, workload, rng)

    _, _, sA_raw, A_raw = quantize_mx_v6(
        A_fp32, dtype=ELEM_TO_DTYPE[typeA],
        block_size=BLOCK_SIZE, axis=1, scale_format=scale)
    _, _, sB_raw, B_raw = quantize_mx_v6(
        B_fp32, dtype=ELEM_TO_DTYPE[typeB],
        block_size=BLOCK_SIZE, axis=1, scale_format=scale)

    out_dir = ROOT / workload
    out_dir.mkdir(exist_ok=True)
    out_path = out_dir / f"{typeA}_{typeB}_{scale}_K{K}.tsv"
    with open(out_path, "w") as f:
        cols = (["trial", "cycle", "sA", "sB"]
                + [f"a{i}" for i in range(VEC)] + [f"b{i}" for i in range(VEC)])
        f.write("\t".join(cols) + "\n")
        for t in range(N_TRIALS):
            for c in range(K):
                blk    = c // CPB
                sA     = int(sA_raw[t, blk])
                sB     = int(sB_raw[t, blk])
                start  = c * VEC
                a_row  = A_raw[t, start:start+VEC]
                b_row  = B_raw[t, start:start+VEC]
                a_vals = [int(v) & 0xFF for v in a_row]
                b_vals = [int(v) & 0xFF for v in b_row]
                row = [str(t), str(c), str(sA), str(sB)] + \
                      [str(x) for x in a_vals] + [str(x) for x in b_vals]
                f.write("\t".join(row) + "\n")
    return out_path


def main():
    # Enumerate the actual config list (workload × pair × scale × K)
    configs = []
    for workload, Ks in SWEEP_MATRIX.items():
        for typeA, typeB in PAIRS:
            for scale in SCALES:
                for K in Ks:
                    configs.append((workload, typeA, typeB, scale, K))
    total = len(configs)
    print(f"Generating {total} configurations × {N_TRIALS} trials → {ROOT}/")
    for i, (workload, typeA, typeB, scale, K) in enumerate(configs, start=1):
        out_path = ROOT / workload / f"{typeA}_{typeB}_{scale}_K{K}.tsv"
        if out_path.exists():
            size = out_path.stat().st_size / 1024
            print(f"  [{i:3d}/{total}] {workload}/{out_path.name:35s}  (SKIP, exists, {size:.0f} KB)")
            continue
        path = generate_one_config(workload, typeA, typeB, scale, K)
        size = path.stat().st_size / 1024
        print(f"  [{i:3d}/{total}] {workload}/{path.name:35s}  ({size:.0f} KB)")
    print(f"\nDone. Vectors under {ROOT}/")


if __name__ == "__main__":
    main()
