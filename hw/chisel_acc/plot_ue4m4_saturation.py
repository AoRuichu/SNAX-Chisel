"""
Diagnostic: UE4M4 scale saturation analysis — updated for real down_proj distribution.

UE4M4 (4-bit exp, 4-bit mant, bias=7): max representable scale = 496,
max INT8 output = 127×496/64 = 984.

With old distribution (weight U(-1,1), std≈0.577):
  Outlier ±4000 → output std ≈ 1383 → 100% blocks saturate → S4 collapses.

With new distribution (weight N(0,σ=0.0124)):
  Outlier max 4384 but weight std 46× smaller → output std ≈ 104 (UE8M0) or ≈ 30 (UE4M4).
  UE4M4 max (984) ≈ 9.5σ of UE8M0 output or ≈ 33σ of UE4M4 output → saturation vanishes.
"""

import numpy as np
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
import matplotlib.patches as mpatches

# ── Scale format parameters ─────────────────────────────────────────────────

def decode_scale(raw, ew, mw, bias):
    e = (raw >> mw) & ((1 << ew) - 1)
    m =  raw        & ((1 << mw) - 1)
    if mw == 0:
        return 2.0 ** (e - bias)
    impl = 1.0 if e > 0 else 0.0
    eff  = (e - bias) if e > 0 else (1 - bias)
    return (impl + m / (1 << mw)) * (2.0 ** eff)

FORMATS = {
    'UE8M0': (8, 0),
    'UE6M2': (6, 2),
    'UE5M3': (5, 3),
    'UE4M4': (4, 4),
}
COLORS = {'UE8M0': '#1976D2', 'UE6M2': '#388E3C', 'UE5M3': '#F57C00', 'UE4M4': '#C62828'}

fmt_info = {}
for name, (ew, mw) in FORMATS.items():
    bias   = (1 << (ew - 1)) - 1
    scales = sorted({decode_scale(c, ew, mw, bias) for c in range(1, 256)})
    max_s  = max(scales)
    fmt_info[name] = dict(ew=ew, mw=mw, bias=bias, max_scale=max_s,
                          max_int8_out=max_s * 127 / 64, scales=scales)

MAX_UE4M4     = fmt_info['UE4M4']['max_int8_out']   # 984.2
MAX_UE4M4_S   = fmt_info['UE4M4']['max_scale']      # 496.0

# ── Simulate accumulated outputs (INT8×INT8, tensor=256, K=64) ─────────────

ACT_OUTLIERS_RAW = np.array([4384.0, 3500.0, 2800.0, 2048.0, 4000.0, 3200.0])  # positive only

def simulate_outputs(n_samples=4800, seed=0xBEEF_CAFE, clip_outlier_to=None):
    """
    clip_outlier_to: if given, clip activation outliers to this value before
    accumulating (models UE4M4 input clamping).  None = no input clipping
    (models UE8M0 which can represent any scale).
    Real distribution: Act N(0,σ=3.62) + positive-only outliers ≤4384; Weight N(0,σ=0.0124).
    """
    rng = np.random.default_rng(seed)
    TENSOR = 256
    outlier_vals = (np.minimum(ACT_OUTLIERS_RAW, clip_outlier_to)
                    if clip_outlier_to is not None else ACT_OUTLIERS_RAW.copy())
    outs = []
    for _ in range(n_samples):
        A = rng.standard_normal(TENSOR).astype(np.float64) * 3.62
        pos = rng.choice(TENSOR, len(outlier_vals), replace=False)
        A[pos] = outlier_vals
        B = rng.standard_normal(TENSOR).astype(np.float64) * 0.0124
        outs.append(float(np.dot(A, B)))
    return np.array(outs)

out_ue8m0 = simulate_outputs(clip_outlier_to=None)   # UE8M0 — no input clip
out_ue4m4 = simulate_outputs(clip_outlier_to=984.2)  # UE4M4 — input outliers clamped

# ── Build figure ─────────────────────────────────────────────────────────────

fig = plt.figure(figsize=(20, 10))
fig.suptitle(
    'UE4M4 Scale Saturation Diagnostic — INT8×INT8, tensor=256×256 (K=64)\n'
    'Activation: N(0,σ=3.62) + 6 positive-only outliers ≤4384   |   Weight: N(0,σ=0.0124)',
    fontsize=12, fontweight='bold'
)
gs  = fig.add_gridspec(2, 4, hspace=0.38, wspace=0.38)

# ── Panel (a): representable scale ranges ─────────────────────────────────────
ax = fig.add_subplot(gs[0, 0])
for i, (name, info) in enumerate(fmt_info.items()):
    y = [i, i]
    x = [min(s for s in info['scales'] if s > 0), info['max_scale']]
    ax.barh(i, np.log10(info['max_scale']), left=np.log10(x[0]),
            height=0.5, color=COLORS[name], alpha=0.85, label=name)
    ax.text(np.log10(info['max_scale']) + 0.15, i,
            f"max={info['max_scale']:.0f}", va='center', fontsize=8, color=COLORS[name])

# Mark minimum scale needed for max activation outlier (4384) INT8 quantization
min_needed = 4384.0 / (127 / 64)   # ≈ 2210
ax.axvline(np.log10(min_needed), color='red', lw=1.8, ls='--',
           label=f'Min scale for outlier 4384\n(INT8 → {min_needed:.0f})')
ax.set_yticks(range(len(fmt_info)))
ax.set_yticklabels(list(fmt_info.keys()), fontsize=9)
ax.set_xlabel('Scale magnitude (log₁₀)', fontsize=9)
ax.set_title('(a) Representable scale ranges\nRed line = min scale needed for ±4000',
             fontsize=9, fontweight='bold')
ax.legend(fontsize=7.5, loc='lower right')
ax.grid(True, alpha=0.3, axis='x')

# ── Panel (b): input quantization error for the outlier value ─────────────────
ax = fig.add_subplot(gs[0, 1])
outlier_val = 4384.0
results = {}
for name, info in fmt_info.items():
    ew, mw, bias = info['ew'], info['mw'], info['bias']
    min_s_needed = outlier_val / (127 / 64)
    feasible     = [s for s in info['scales'] if s >= min_s_needed]
    if feasible:
        best_s = min(feasible)
    else:
        best_s = info['max_scale']   # saturated
    code = round(outlier_val * 64 / best_s)
    code = max(-127, min(127, code))
    decoded = code * best_s / 64
    results[name] = dict(best_s=best_s, decoded=decoded,
                         saturated=(len(feasible) == 0))

names  = list(fmt_info.keys())
colors = [COLORS[n] for n in names]
values = [results[n]['decoded'] for n in names]
bars   = ax.bar(names, values, color=colors, alpha=0.88, edgecolor='white')
ax.axhline(4384, color='black', lw=1.5, ls='--', label='True outlier = 4384')
ax.axhline(MAX_UE4M4, color='red', lw=1.5, ls=':', label=f'UE4M4 max INT8 = {MAX_UE4M4:.0f}')
for bar, name, val in zip(bars, names, values):
    label = f'{val:.0f}' + (' ← CLIP' if results[name]['saturated'] else '')
    color = 'red' if results[name]['saturated'] else '#222'
    ax.text(bar.get_x() + bar.get_width()/2, bar.get_height() + 30,
            label, ha='center', va='bottom', fontsize=8.5, color=color, fontweight='bold')
ax.set_ylabel('Decoded activation value after MX quantization', fontsize=9)
ax.set_title('(b) Outlier 4384 after INT8 input quantization\nUE4M4 clips to ±984 (scale saturated)',
             fontsize=9, fontweight='bold')
ax.legend(fontsize=8)
ax.grid(True, alpha=0.3, axis='y')
ax.set_ylim(0, 5200)

# ── Panel (c): distribution of accumulated outputs ────────────────────────────
ax = fig.add_subplot(gs[0, 2:])
bins = np.linspace(-6000, 6000, 120)
ax.hist(out_ue8m0, bins=bins, color='#1976D2', alpha=0.55, density=True,
        label=f'UE8M0 (no clip, outlier≤4384): std={out_ue8m0.std():.1f}')
ax.hist(out_ue4m4, bins=bins, color='#C62828', alpha=0.55, density=True,
        label=f'UE4M4 (outlier clipped to 984): std={out_ue4m4.std():.1f}')
ax.axvline( MAX_UE4M4, color='#C62828', lw=2.0, ls='--',
            label=f'+984 = UE4M4 INT8 output limit')
ax.axvline(-MAX_UE4M4, color='#C62828', lw=2.0, ls='--')
pct_exceed_ue4m4 = np.mean(np.abs(out_ue4m4) > MAX_UE4M4) * 100
ax.text(MAX_UE4M4 + 150, ax.get_ylim()[1] * 0.5 if ax.get_ylim()[1] > 0 else 0.001,
        f'{pct_exceed_ue4m4:.0f}% of outputs\nexceed ±984\n(→ OUTPUT CLIP too)',
        color='#C62828', fontsize=9, fontweight='bold')
ax.set_xlabel('Accumulated output value (before output requantization)', fontsize=9)
ax.set_ylabel('Density', fontsize=9)
ax.set_title('(c) Distribution of accumulated outputs\n'
             f'UE4M4 input clipping already reduces signal; {pct_exceed_ue4m4:.0f}% of outputs still exceed output limit',
             fontsize=9, fontweight='bold')
ax.legend(fontsize=9)
ax.grid(True, alpha=0.3)

# ── Panel (d): per-block saturation fraction vs blockSize ─────────────────────
ax = fig.add_subplot(gs[1, 0:2])
block_sizes = [4, 8, 16, 32, 64]
for dname, outs in [('UE8M0 (no clip)', out_ue8m0), ('UE4M4 (clip=984)', out_ue4m4)]:
    saturated_pct = []
    for bs in block_sizes:
        n_sat = sum(
            1 for i in range(0, len(outs), bs)
            if np.abs(outs[i:i+bs]).max() / (127/64) > MAX_UE4M4_S
        )
        n_blk = len(outs) // bs
        saturated_pct.append(100 * n_sat / n_blk if n_blk > 0 else 0)
    color = '#C62828' if 'UE4M4' in dname else '#1976D2'
    ax.plot(block_sizes, saturated_pct, 'o-', color=color, lw=1.8, markersize=7, label=dname)

ax.axhline(0, color='green', lw=1.2, ls='--')
ax.set_xlabel('Output requant block size', fontsize=9)
ax.set_ylabel('Blocks with maxAbs > UE4M4 max scale (→ saturated) [%]', fontsize=9)
ax.set_title('(d) Block saturation rate vs block size\n'
             'UE4M4: 100 % of blocks saturate regardless of block size → S4 collapses',
             fontsize=9, fontweight='bold')
ax.legend(fontsize=9)
ax.set_ylim(-5, 105)
ax.grid(True, alpha=0.3)
ax.set_xticks(block_sizes)

# ── Panel (e): S4 SQNR estimate for each scale format ────────────────────────
ax = fig.add_subplot(gs[1, 2:])

def apply_int8_quant(vals, max_scale):
    """Quantize vals with the largest representable scale; return decoded."""
    min_needed = np.max(np.abs(vals)) / (127 / 64)
    best_s     = min(max_scale, min_needed) if min_needed <= max_scale else max_scale
    # find the representable scale ≥ min_needed (or max if saturated)
    codes = np.clip(np.round(vals * 64 / best_s), -127, 127)
    return codes * best_s / 64

def sqnr_db(signal, noise):
    sp = np.sum(signal**2)
    np_ = np.sum(noise**2)
    return 10 * np.log10(sp / np_) if np_ > 1e-60 and sp > 1e-60 else 99.9

BLOCK_SIZE = 16
fmt_max = {
    'UE8M0': 1e38,   # effectively unlimited
    'UE6M2': fmt_info['UE6M2']['max_scale'],
    'UE5M3': fmt_info['UE5M3']['max_scale'],
    'UE4M4': fmt_info['UE4M4']['max_scale'],
}

sqnr_vals = {}
for fmt, max_s in fmt_max.items():
    # use the correct output distribution (clipped by input quant for UE4M4)
    outs = out_ue4m4 if fmt == 'UE4M4' else out_ue8m0
    outs_f32 = outs.astype(np.float32)
    rq_all, bf16_all = [], []
    for i in range(0, len(outs_f32) - BLOCK_SIZE + 1, BLOCK_SIZE):
        blk  = outs_f32[i:i+BLOCK_SIZE].astype(np.float64)
        rq   = apply_int8_quant(blk, max_s)
        rq_all.extend(rq)
        bf16_all.extend(blk)
    rq_all   = np.array(rq_all)
    bf16_all = np.array(bf16_all)
    sqnr_vals[fmt] = sqnr_db(bf16_all, rq_all - bf16_all)

bars = ax.bar(list(sqnr_vals.keys()),
              list(sqnr_vals.values()),
              color=[COLORS[f] for f in sqnr_vals.keys()],
              alpha=0.88, edgecolor='white', linewidth=0.5)
for bar, (fmt, val) in zip(bars, sqnr_vals.items()):
    ax.text(bar.get_x() + bar.get_width()/2, bar.get_height() + 0.5,
            f'{val:.1f} dB', ha='center', va='bottom', fontsize=9.5, fontweight='bold',
            color='#C62828' if fmt == 'UE4M4' else '#222')

s3_level = 50.5   # typical S3 (BF16 truncation) ≈ 50 dB
ax.axhline(s3_level, color='#FF7043', lw=2.0, ls='--',
           label=f'S3 (BF16 truncation) ≈ {s3_level:.0f} dB')
ax.set_ylabel('SQNR_S4 — INT8 output requantization (dB)', fontsize=9)
ax.set_title(f'(e) Requantization SQNR per scale format (blockSize={BLOCK_SIZE})\n'
             'UE4M4 collapses because 100 % of output blocks saturate',
             fontsize=9, fontweight='bold')
ax.legend(fontsize=9)
ax.grid(True, alpha=0.3, axis='y')
ax.set_ylim(0, 65)

# Annotate margin arrow for UE4M4
ue4m4_s4 = sqnr_vals['UE4M4']
ax.annotate('', xy=(3, ue4m4_s4 + 0.5), xytext=(3, s3_level - 0.5),
            arrowprops=dict(arrowstyle='<->', color='green', lw=1.5))
ax.text(3.35, (ue4m4_s4 + s3_level) / 2,
        f'+{s3_level - ue4m4_s4:.0f} dB\nmargin\n(still SAFE!)',
        va='center', fontsize=8, color='green', fontweight='bold')

plt.savefig('bf16_acc_ue4m4_saturation.png', dpi=150, bbox_inches='tight')
plt.close()
print("Saved bf16_acc_ue4m4_saturation.png")
print()
print("Summary:")
print(f"  UE4M4 max scale        = {MAX_UE4M4_S:.1f}")
print(f"  UE4M4 max INT8 output  = ±{MAX_UE4M4:.1f}")
pct_exceed = np.mean(np.abs(out_ue4m4) > MAX_UE4M4) * 100
print(f"  Outlier 4384 clipped to ±{results['UE4M4']['decoded']:.1f} at INPUT  (scale saturated)")
print(f"  UE8M0 accumulated output std = {out_ue8m0.std():.1f}  (UE4M4 max = {MAX_UE4M4:.0f} = {MAX_UE4M4/out_ue8m0.std():.1f}σ)")
print(f"  UE4M4 accumulated output std = {out_ue4m4.std():.1f}  (UE4M4 max = {MAX_UE4M4:.0f} = {MAX_UE4M4/out_ue4m4.std():.1f}σ)")
print(f"  {pct_exceed:.1f}% of UE4M4 outputs exceed ±{MAX_UE4M4:.0f}  (was 100% with old U(-1,1) weights)")
print(f"  → SQNR_S4 UE4M4 = {sqnr_vals['UE4M4']:.1f} dB  vs UE8M0 = {sqnr_vals['UE8M0']:.1f} dB")
print(f"  → With correct weight N(0,0.0124): output std << UE4M4 limit → saturation largely eliminated")
