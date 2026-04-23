#!/usr/bin/env python3
"""
BF16 Accumulator Safety Analysis — Realistic Activation/Weight Distributions.

Uses quantize_mx_v2 (from quantize(4).py) for:
  - Input MX quantization of A (activation) and B (weight) matrices
  - Output requantization of the accumulated C matrix

Input model (matches realistic DNN inference worst-case):
  Activation A : Gaussian N(0, σ=3.62) body + a few hardcoded positive-only outlier
                 values up to 4384 (models real down_proj input statistics from deployed
                 LLMs: std≈3.62, max≈4384 but min≈−19 → outliers are single-sided).
  Weight     B : Gaussian N(0, σ=0.0124) — matches measured down_proj weight std;
                 weight is tightly concentrated near zero (no outliers).

Pipeline modelled (matches BFP_PE hardware):
  FP64 exact (after input MX quant)
    ↓ S1: per-cycle reducedSum cast to FP32 (~99 dB, negligible)
  accFP32  [23-bit baseline]
    ↓ S2: K-cycle accumulation at accMantBits  (RNE round after each add)
  accHW    [accMantBits precision]
    ↓ S3: BF16 output — truncate to top-7 mantissa bits  ← KEY stage
  accBF16  [7 mant bits → requant input]
    ↓ S4: quantize_mx_v2 on output matrix                ← dominant noise floor
  rqOut

Hardware parameters: 4×16 PE array, vecSize=4, blockSize_in=32 for A/B inputs.
"""

import math, sys, csv, importlib.util
import numpy as np

matplotlib_ok = True
try:
    import matplotlib
    matplotlib.use('Agg')
    import matplotlib.pyplot as plt
except ImportError:
    matplotlib_ok = False

# ── Load quantize_mx_v2 from quantize(4).py ─────────────────────────────────
_spec = importlib.util.spec_from_file_location("quantize4", "quantize(4).py")
_q4   = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_q4)
quantize_mx_v2 = _q4.quantize_mx_v2

# ── Hardware constants ───────────────────────────────────────────────────────
VEC_SIZE      = 4    # MACs per cycle per PE
ARRAY_ROWS    = 4    # PE array rows
ARRAY_COLS    = 16   # PE array columns = output elements per tile
BLOCK_SIZE_IN = 32   # MX quantization block size for A and B inputs

# Hardcoded outlier values injected into activations (worst-case LLM channels).
# A small number of positions per tile are overwritten with these values before
# MX quantization so the block scale compresses the rest of the distribution.
# Positive-only outliers (real data: max=4384, min=-19 → outliers are single-sided).
ACT_OUTLIERS = np.array([4384.0, 3500.0, 2800.0, 2048.0, 4000.0, 3200.0], dtype=np.float32)

# ── dtype / format mappings ──────────────────────────────────────────────────
ELEM_DTYPE = {
    'E5M2': 'fp8_e5m2',
    'E4M3': 'fp8_e4m3',
    'E3M2': 'fp6_e3m2',
    'INT8': 'mxint8',
}

ELEM_MAX_EXP = {
    'E5M2': 15, 'E4M3': 8, 'E3M2': 4, 'INT8': 0,
}
ELEM_MIN_ADJ_EXP = {
    'E5M2': -14, 'E4M3': -6, 'E3M2': -2, 'INT8': -6,
}

SCALE_FORMATS = ['UE8M0', 'UE6M2', 'UE5M3', 'UE4M4']

MAC_PAIRS = [
    ('E5M2', 'E5M2'), ('E4M3', 'E4M3'), ('E3M2', 'E3M2'),
    ('E5M2', 'E4M3'),
    ('INT8', 'E5M2'), ('INT8', 'E4M3'), ('INT8', 'INT8'),
]

TENSOR_SIZES = [64, 256]    # square matrix dimension → K = tensor_size
BLOCK_SIZES  = [8, 16, 32]  # output requant block sizes
N_TRIALS     = 3            # independent (A,B) matrix pairs per config


# ── AccPrecision.recommended (ported from Parameter.scala) ──────────────────

def product_exp_range(typeA: str, typeB: str) -> int:
    return (ELEM_MAX_EXP[typeA] + ELEM_MAX_EXP[typeB]) - (ELEM_MIN_ADJ_EXP[typeA] + ELEM_MIN_ADJ_EXP[typeB])

def acc_precision_recommended(typeA: str, typeB: str, K: int) -> int:
    k_bits    = math.ceil(math.log2(max(K, 2)))
    k_bonus   = k_bits // 2
    per       = product_exp_range(typeA, typeB)
    range_pen = 3 if per >= 50 else (1 if per >= 30 else 0)
    return min(23, 7 + k_bonus + range_pen)

def natural_rq_target(typeA: str, typeB: str) -> str:
    PREC = {'INT8': 7, 'E4M3': 3, 'E5M2': 2, 'E3M2': 2}
    higher = typeA if PREC[typeA] >= PREC[typeB] else typeB
    return ELEM_DTYPE[higher]


# ── Numpy-vectorized FP precision helpers ────────────────────────────────────

def round_to_mant_bits_np(arr: np.ndarray, mant_bits: int) -> np.ndarray:
    """RNE-round a float32 array to `mant_bits` mantissa bits."""
    a = np.asarray(arr, dtype=np.float32)
    if mant_bits >= 23:
        return a
    bits = a.view(np.int32).copy()
    exp_f  = (bits >> 23) & 0xFF
    normal = (exp_f > 0) & (exp_f < 255)
    drop   = 23 - mant_bits
    lsb    = (bits >> drop) & 1
    guard  = (bits >> (drop - 1)) & 1
    sticky = (bits & ((1 << (drop - 1)) - 1)) != 0 if drop >= 2 else np.zeros(bits.shape, dtype=bool)
    round_up  = (guard == 1) & ((lsb == 1) | sticky)
    mask_keep = np.int32(-1) << np.int32(drop)
    truncated = bits & mask_keep
    rounded   = np.where(round_up, truncated + (1 << drop), truncated)
    return np.where(normal, rounded, bits).view(np.float32)

def truncate_to_mant_bits_np(arr: np.ndarray, mant_bits: int) -> np.ndarray:
    """Truncate (no rounding) a float32 array to `mant_bits` mantissa bits."""
    a = np.asarray(arr, dtype=np.float32)
    if mant_bits >= 23:
        return a
    bits = a.view(np.int32).copy()
    exp_f  = (bits >> 23) & 0xFF
    normal = (exp_f > 0) & (exp_f < 255)
    drop   = 23 - mant_bits
    mask_keep = np.int32(-1) << np.int32(drop)
    truncated = bits & mask_keep
    return np.where(normal, truncated, bits).view(np.float32)


# ── SQNR ────────────────────────────────────────────────────────────────────

def sqnr_db(signal: np.ndarray, noise: np.ndarray) -> float:
    sp  = float(np.sum(signal.astype(np.float64) ** 2))
    np_ = float(np.sum(noise.astype(np.float64) ** 2))
    if np_ < 1e-60 or sp < 1e-60:
        return 99.9
    return 10.0 * math.log10(sp / np_)


# ── Input generation ─────────────────────────────────────────────────────────

def gen_activation(rows: int, cols: int, rng: np.random.Generator) -> np.ndarray:
    """
    Activation matrix: Gaussian N(0, σ=3.62) body + positive-only outliers.
    Real data (down_proj input): std=3.62, max=4384, min=-19 (single-sided outliers).
    A few positions are overwritten with ACT_OUTLIERS; all others come from the body.
    """
    A    = rng.normal(0.0, 3.62, (rows, cols)).astype(np.float32)
    flat = A.ravel()
    positions = rng.choice(len(flat), size=len(ACT_OUTLIERS), replace=False)
    for i, pos in enumerate(positions):
        flat[pos] = ACT_OUTLIERS[i]
    return A

def gen_weight(rows: int, cols: int, rng: np.random.Generator) -> np.ndarray:
    """Weight matrix: Gaussian N(0, σ=0.0124). Real data: std=0.0124, max=0.527."""
    return rng.normal(0.0, 0.0124, (rows, cols)).astype(np.float32)


# ── Simulate accumulation pipeline for one (A, B) matrix pair ───────────────

def simulate_pipeline(A_q: np.ndarray, B_q: np.ndarray, accMantBits: int):
    """
    A_q : (M, K) float32 — dequantized activation
    B_q : (N, K) float32 — dequantized weight

    Returns (C_f64ref, C_fpref, C_hw, C_bf16) all shape (M, N).
    """
    M, K = A_q.shape
    N    = B_q.shape[0]

    C_f64ref = np.zeros((M, N), dtype=np.float64)
    C_fpref  = np.zeros((M, N), dtype=np.float32)
    C_hw     = np.zeros((M, N), dtype=np.float32)

    for k0 in range(0, K, VEC_SIZE):
        k1     = min(k0 + VEC_SIZE, K)
        rs_f64 = A_q[:, k0:k1].astype(np.float64) @ B_q[:, k0:k1].T.astype(np.float64)

        C_f64ref += rs_f64

        # FP32 baseline: accumulate then RNE-round to 23 bits
        C_fpref = round_to_mant_bits_np(
            (C_fpref.astype(np.float64) + rs_f64).astype(np.float32), 23)

        # Hardware: accumulate then RNE-round to accMantBits
        tmp   = (C_hw.astype(np.float64) + rs_f64).astype(np.float32)
        C_hw  = round_to_mant_bits_np(tmp, accMantBits)

    # S3: hardware bit-selects top 7 mantissa bits (no rounding)
    C_bf16 = truncate_to_mant_bits_np(C_hw, 7)
    return C_f64ref, C_fpref, C_hw, C_bf16


# ── Apply output requantization via quantize_mx_v2 ───────────────────────────

def apply_output_rq(C_bf16: np.ndarray, rq_dtype: str, block_size: int, scale_fmt: str) -> np.ndarray:
    C_in = C_bf16.astype(np.float32)
    if C_in.ndim == 1:
        C_in = C_in[np.newaxis, :]
    q_arr, _, _, _ = quantize_mx_v2(C_in, rq_dtype, block_size=block_size, axis=1, scale_format=scale_fmt)
    return q_arr


# ── Main analysis loop ───────────────────────────────────────────────────────

def main():
    results  = []
    rng_seed = 0xBEEF_CAFE

    total = len(MAC_PAIRS) * len(SCALE_FORMATS) * len(TENSOR_SIZES) * len(BLOCK_SIZES)
    done  = 0

    print(f"Running {total} configs × {N_TRIALS} trials ...")
    print(f"  Array: {ARRAY_ROWS}×{ARRAY_COLS}, vecSize={VEC_SIZE}, blockSize_in={BLOCK_SIZE_IN}")
    print(f"  Activation: N(0, var=9) + {len(ACT_OUTLIERS)} hardcoded outliers ±4000")
    print(f"  Weight:     U(−1, 1)")

    for typeA, typeB in MAC_PAIRS:
        for scale_fmt in SCALE_FORMATS:
            for tensor_size in TENSOR_SIZES:
                K           = tensor_size
                accMantBits = acc_precision_recommended(typeA, typeB, K // VEC_SIZE)
                rq_dtype    = natural_rq_target(typeA, typeB)
                dtype_a     = ELEM_DTYPE[typeA]
                dtype_b     = ELEM_DTYPE[typeB]

                rng = np.random.default_rng(
                    rng_seed ^ hash((typeA, typeB, scale_fmt, tensor_size)) & 0xFFFFFFFF)

                all_f64ref, all_fpref, all_hw, all_bf16 = [], [], [], []

                for _ in range(N_TRIALS):
                    # A is activation (ARRAY_ROWS × K), B is weight (ARRAY_COLS × K)
                    A_raw = gen_activation(ARRAY_ROWS, K, rng)
                    B_raw = gen_weight(ARRAY_COLS, K, rng)

                    A_q, _, _, _ = quantize_mx_v2(A_raw, dtype_a, BLOCK_SIZE_IN, axis=1, scale_format=scale_fmt)
                    B_q, _, _, _ = quantize_mx_v2(B_raw, dtype_b, BLOCK_SIZE_IN, axis=1, scale_format=scale_fmt)

                    C_f64, C_fp, C_hw, C_bf16 = simulate_pipeline(A_q, B_q, accMantBits)

                    all_f64ref.append(C_f64.ravel())
                    all_fpref.append(C_fp.ravel().astype(np.float64))
                    all_hw.append(C_hw.ravel().astype(np.float64))
                    all_bf16.append(C_bf16.ravel().astype(np.float64))

                f64ref = np.concatenate(all_f64ref)
                fpref  = np.concatenate(all_fpref)
                hw     = np.concatenate(all_hw)
                bf16   = np.concatenate(all_bf16)

                # S1/S2/S3 independent of blockSize — compute once
                sqnr_S1 = sqnr_db(fpref, fpref - f64ref)
                sqnr_S2 = sqnr_db(fpref, hw - fpref)
                sqnr_S3 = sqnr_db(hw,    bf16 - hw)

                for block_size in BLOCK_SIZES:
                    rq_parts, bf16_parts = [], []
                    idx = 0
                    for _ in range(N_TRIALS):
                        tile_bf16 = bf16[idx:idx + ARRAY_ROWS * ARRAY_COLS].reshape(ARRAY_ROWS, ARRAY_COLS).astype(np.float32)
                        tile_rq   = apply_output_rq(tile_bf16, rq_dtype, block_size, scale_fmt)
                        rq_parts.append(tile_rq.ravel().astype(np.float64))
                        bf16_parts.append(tile_bf16.ravel().astype(np.float64))
                        idx += ARRAY_ROWS * ARRAY_COLS

                    rq_all   = np.concatenate(rq_parts)
                    bf16_all = np.concatenate(bf16_parts)

                    sqnr_S4    = sqnr_db(bf16_all, rq_all - bf16_all)
                    sqnr_total = sqnr_db(f64ref, rq_all - f64ref)
                    margin     = sqnr_S3 - sqnr_S4

                    results.append(dict(
                        typeA=typeA, typeB=typeB,
                        mac_pair=f'{typeA}×{typeB}',
                        rq=rq_dtype, scale=scale_fmt,
                        tensor_size=tensor_size,
                        K=K // VEC_SIZE,
                        total_MACs=K,
                        accMantBits=accMantBits,
                        block_size=block_size,
                        sqnr_S1=sqnr_S1, sqnr_S2=sqnr_S2,
                        sqnr_S3=sqnr_S3, sqnr_S4=sqnr_S4,
                        sqnr_total=sqnr_total,
                        margin=margin,
                        verdict='SAFE' if margin > 0 else 'WARN',
                    ))

                    done += 1
                    pct = done / total * 100
                    print(f"  [{pct:5.1f}%] {typeA}×{typeB} {scale_fmt} tensor={tensor_size} blk={block_size}", flush=True)

    # ── Write CSV ─────────────────────────────────────────────────────────────
    csv_path = 'bf16_acc_distribution_analysis.csv'
    fieldnames = ['typeA','typeB','mac_pair','rq','scale','tensor_size','K','total_MACs',
                  'accMantBits','block_size','sqnr_S1','sqnr_S2','sqnr_S3','sqnr_S4',
                  'sqnr_total','margin','verdict']
    with open(csv_path, 'w', newline='') as f:
        w = csv.DictWriter(f, fieldnames=fieldnames)
        w.writeheader()
        for r in results:
            w.writerow({k: (f'{r[k]:.2f}' if isinstance(r[k], float) else r[k]) for k in fieldnames})
    print(f"\nSaved {csv_path}")

    # ── Print full table ──────────────────────────────────────────────────────
    W = 160
    print('\n' + '═' * W)
    print('BF16 ACCUMULATOR — REALISTIC DISTRIBUTION NOISE BUDGET')
    print('  Activation: N(0, var=9) + outliers ±4000   |   Weight: U(−1,1)')
    print('  margin = SQNR_S3(BF16 trunc) − SQNR_S4(requant)  →  positive = SAFE')
    print('═' * W)
    hdr = (f"{'typeA×typeB':<14} {'scale':<6} {'tensor':>6} {'K':>3} "
           f"{'accM':>4} {'blk':>3} "
           f"{'S1':>7} {'S2':>7} {'S3(BF16)':>9} {'S4(rq)':>8} {'total':>7} {'margin':>8}  verdict")
    print(hdr)
    print('-' * W)
    for r in results:
        print(f"{r['mac_pair']:<14} {r['scale']:<6} "
              f"{r['tensor_size']:>6} {r['K']:>3} {r['accMantBits']:>4} {r['block_size']:>3}  "
              f"{r['sqnr_S1']:>6.1f} {r['sqnr_S2']:>6.1f} {r['sqnr_S3']:>8.1f} "
              f"{r['sqnr_S4']:>7.1f} {r['sqnr_total']:>6.1f} {r['margin']:>+8.1f}  {r['verdict']}")

    # ── Summary: min margin per (macPair, scale) over (tensor, block_size) ───
    print()
    print('═' * 100)
    print('SUMMARY: worst margin per MAC pair × scale  (min over tensor_size × block_size)')
    print()
    print(f"{'typeA×typeB':<14} {'scale':<6} {'accM K=16/K=64':<16} {'min margin':>10}  verdict")
    print('-' * 60)
    for typeA, typeB in MAC_PAIRS:
        for scale in SCALE_FORMATS:
            rows = [r for r in results
                    if r['typeA'] == typeA and r['typeB'] == typeB and r['scale'] == scale]
            if not rows:
                continue
            min_m   = min(r['margin'] for r in rows)
            verdict = 'SAFE' if min_m > 0 else 'WARN'
            accM16  = acc_precision_recommended(typeA, typeB, 16)
            accM64  = acc_precision_recommended(typeA, typeB, 64)
            print(f"{typeA}×{typeB:<11} {scale:<6} {accM16}/{accM64:<13} {min_m:>+9.1f}  {verdict}")

    print()
    print('═' * 100)
    min_margin = min(r['margin'] for r in results)
    worst      = next(r for r in results if r['margin'] == min_margin)
    print(f"Worst-case margin: {min_margin:+.1f} dB  "
          f"({worst['mac_pair']}, {worst['scale']}, tensor={worst['tensor_size']}, blk={worst['block_size']})")
    print(f"All SAFE: {all(r['verdict'] == 'SAFE' for r in results)}")
    warns = [r for r in results if r['verdict'] != 'SAFE']
    if warns:
        print(f"WARN configs ({len(warns)}):")
        for r in warns:
            print(f"  {r['mac_pair']} {r['scale']} tensor={r['tensor_size']} blk={r['block_size']} margin={r['margin']:+.1f}")
    print('═' * 100)

    if not matplotlib_ok:
        print("matplotlib not available, skipping plots.")
        return

    MAC_PAIR_LABELS = [f'{a}×{b}' for a, b in MAC_PAIRS]
    SCALE_COLORS    = {'UE8M0': '#1976D2', 'UE6M2': '#388E3C', 'UE5M3': '#F57C00', 'UE4M4': '#7B1FA2'}
    SCALE_MARKERS   = {'UE8M0': 'o', 'UE6M2': 's', 'UE5M3': '^', 'UE4M4': 'D'}

    # ── Figure 1: margin bars (tensor=256, block_size=16) ────────────────────
    fig, ax = plt.subplots(figsize=(14, 6))
    fig.suptitle(
        'BF16 Accumulator Safety Margin — Realistic Input Distribution\n'
        'Activation: N(0, var=9) + ±4000 outliers   |   Weight: U(−1,1)\n'
        'tensor=256×256, block_size=16   |   margin = SQNR_S3 − SQNR_S4  (positive = SAFE)',
        fontsize=10, fontweight='bold'
    )

    x     = np.arange(len(MAC_PAIR_LABELS))
    width = 0.20
    for i, scale in enumerate(SCALE_FORMATS):
        margins = []
        for typeA, typeB in MAC_PAIRS:
            row = [r for r in results
                   if r['typeA'] == typeA and r['typeB'] == typeB
                   and r['scale'] == scale
                   and r['tensor_size'] == 256 and r['block_size'] == 16]
            margins.append(row[0]['margin'] if row else 0.0)
        bars = ax.bar(x + (i - 1.5) * width, margins, width,
                      label=scale, color=SCALE_COLORS[scale], alpha=0.88,
                      edgecolor='white', linewidth=0.5)
        for bar, val in zip(bars, margins):
            ax.text(bar.get_x() + bar.get_width() / 2, bar.get_height() + 0.3,
                    f'{val:.0f}', ha='center', va='bottom', fontsize=7.5, color='#222')

    ax.axhline(0, color='red', lw=1.5, ls='--', label='Safety boundary (0 dB)', zorder=3)
    ax.set_xticks(x)
    ax.set_xticklabels(MAC_PAIR_LABELS, rotation=28, ha='right', fontsize=9.5)
    ax.set_ylabel('Safety margin  =  SQNR_S3 − SQNR_S4  (dB)', fontsize=10)
    ax.legend(fontsize=9, loc='upper right')
    ax.grid(True, alpha=0.3, axis='y')
    ax.set_ylim(bottom=0)
    plt.tight_layout()
    plt.savefig('bf16_acc_dist_margin_bars.png', dpi=150, bbox_inches='tight')
    plt.close()
    print("Saved bf16_acc_dist_margin_bars.png")

    # ── Figure 2: S3 vs S4 scatter (all configs) ─────────────────────────────
    fig, ax = plt.subplots(figsize=(8, 7))
    fig.suptitle(
        'SQNR_S3 (BF16 truncation) vs SQNR_S4 (requantization)\n'
        'Activation: N(0,var=9)+outliers ±4000  |  Weight: U(−1,1)\n'
        'All points above diagonal → SAFE',
        fontsize=10, fontweight='bold'
    )
    for scale in SCALE_FORMATS:
        pts = [r for r in results if r['scale'] == scale]
        ax.scatter([r['sqnr_S4'] for r in pts], [r['sqnr_S3'] for r in pts],
                   c=SCALE_COLORS[scale], marker=SCALE_MARKERS[scale],
                   s=70, label=scale, zorder=4, edgecolors='white', linewidths=0.4)

    lo = min(r['sqnr_S4'] for r in results) - 3
    hi = max(r['sqnr_S3'] for r in results) + 3
    ax.plot([lo, hi], [lo, hi], 'r--', lw=1.5, label='S3=S4 (break-even)')
    ax.fill_between([lo, hi], [lo, hi], hi, color='green', alpha=0.07)
    ax.text(lo + 1, hi - 5, 'SAFE\n(S3 > S4)', color='#2E7D32', fontsize=9, fontstyle='italic')
    ax.set_xlim(lo, hi); ax.set_ylim(lo, hi)
    ax.set_aspect('equal')
    ax.set_xlabel('SQNR_S4 — Requantization floor (dB)', fontsize=10)
    ax.set_ylabel('SQNR_S3 — BF16 truncation (dB)', fontsize=10)
    ax.legend(fontsize=9, loc='lower right')
    ax.grid(True, alpha=0.3)
    plt.tight_layout()
    plt.savefig('bf16_acc_dist_scatter.png', dpi=150, bbox_inches='tight')
    plt.close()
    print("Saved bf16_acc_dist_scatter.png")

    # ── Figure 3: margin heatmap for two tensor sizes, block_size=16 ─────────
    fig, axes = plt.subplots(1, 2, figsize=(14, 5))
    fig.suptitle(
        'Safety Margin Heatmap (dB)  —  block_size=16\n'
        'Activation: N(0,var=9)+outliers ±4000  |  Weight: U(−1,1)',
        fontsize=11, fontweight='bold'
    )
    for ax_idx, ts in enumerate([64, 256]):
        ax = axes[ax_idx]
        heat = np.zeros((len(MAC_PAIR_LABELS), len(SCALE_FORMATS)))
        for i, (typeA, typeB) in enumerate(MAC_PAIRS):
            for j, scale in enumerate(SCALE_FORMATS):
                row = [r for r in results
                       if r['typeA'] == typeA and r['typeB'] == typeB
                       and r['scale'] == scale
                       and r['tensor_size'] == ts and r['block_size'] == 16]
                heat[i, j] = row[0]['margin'] if row else 0.0

        im = ax.imshow(heat, cmap='YlGn', aspect='auto', vmin=0, vmax=heat.max() + 5)
        plt.colorbar(im, ax=ax, label='Margin (dB)', shrink=0.85)
        ax.set_xticks(range(len(SCALE_FORMATS)))
        ax.set_xticklabels(SCALE_FORMATS, fontsize=9)
        ax.set_yticks(range(len(MAC_PAIR_LABELS)))
        ax.set_yticklabels(MAC_PAIR_LABELS, fontsize=9)
        for i in range(len(MAC_PAIR_LABELS)):
            for j in range(len(SCALE_FORMATS)):
                val = heat[i, j]
                ax.text(j, i, f'+{val:.1f}', ha='center', va='center',
                        fontsize=9, fontweight='bold',
                        color='black' if val < heat.max() * 0.6 else 'white')
        ax.set_title(f'({chr(97+ax_idx)}) tensor={ts}×{ts}  (K={ts//VEC_SIZE} cycles)',
                     fontsize=10, fontweight='bold')

    plt.tight_layout()
    plt.savefig('bf16_acc_dist_heatmap.png', dpi=150, bbox_inches='tight')
    plt.close()
    print("Saved bf16_acc_dist_heatmap.png")


if __name__ == '__main__':
    main()
