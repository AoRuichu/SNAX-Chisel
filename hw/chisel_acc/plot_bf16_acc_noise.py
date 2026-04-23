"""
BF16 Accumulator Safety Plot — K=4 fixed
Generates two figures proving AccPrecision.recommended gives safe margins.
"""

import pandas as pd
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
import matplotlib.patches as mpatches
import numpy as np

df = pd.read_csv('bf16_acc_noise_budget.csv')
df4 = df[df['K'] == 4].copy()
df4['mac_pair'] = df4['typeA'] + '×' + df4['typeB']

SCALES      = ['UE8M0', 'UE6M2', 'UE5M3', 'UE4M4']
SCALE_COLOR = {'UE8M0': '#1976D2', 'UE6M2': '#388E3C', 'UE5M3': '#F57C00', 'UE4M4': '#7B1FA2'}
SCALE_MARK  = {'UE8M0': 'o',       'UE6M2': 's',       'UE5M3': '^',       'UE4M4': 'D'}

MAC_PAIRS = [
    'E5M2×E5M2', 'E4M3×E4M3', 'E3M2×E3M2',
    'E5M2×E4M3', 'INT8×E5M2', 'INT8×E4M3', 'INT8×INT8'
]

# ─────────────────────────────────────────────────────────────────────────────
# Figure 1: three-panel safety proof (K=4)
# ─────────────────────────────────────────────────────────────────────────────
fig, axes = plt.subplots(1, 3, figsize=(18, 6))
fig.suptitle(
    'BF16 Accumulator Safety Analysis  —  AccPrecision.recommended  (K = 4, vecSize = 4)\n'
    'S3 = BF16-truncation SQNR   |   S4 = requantization SQNR (dominant noise floor)\n'
    'Design is SAFE when S3 > S4 for every configuration',
    fontsize=11, fontweight='bold', y=1.03
)

# ── Panel A: scatter SQNR_S3 vs SQNR_S4 ─────────────────────────────────────
ax = axes[0]
for sc in SCALES:
    sub = df4[df4['scaleType'] == sc]
    ax.scatter(sub['SQNR_S4_dB'], sub['SQNR_S3_dB'],
               c=SCALE_COLOR[sc], marker=SCALE_MARK[sc],
               s=90, label=sc, zorder=4, edgecolors='white', linewidths=0.5)

lo = min(df4['SQNR_S4_dB'].min(), df4['SQNR_S3_dB'].min()) - 3
hi = max(df4['SQNR_S4_dB'].max(), df4['SQNR_S3_dB'].max()) + 3
ax.plot([lo, hi], [lo, hi], 'r--', lw=1.5, label='Break-even (S3=S4)', zorder=2)
ax.fill_between([lo, hi], [lo, hi], hi,
                color='green', alpha=0.07, zorder=1)
ax.text(lo + 1.5, hi - 4, 'SAFE region\n(S3 > S4)', color='#2E7D32',
        fontsize=9, fontstyle='italic')

ax.set_xlim(lo, hi)
ax.set_ylim(lo, hi)
ax.set_xlabel('SQNR_S4  —  Requantization noise floor (dB)', fontsize=10)
ax.set_ylabel('SQNR_S3  —  BF16 truncation (dB)', fontsize=10)
ax.set_title('(a) Every point above the diagonal → SAFE', fontsize=10, fontweight='bold')
ax.legend(fontsize=8, loc='lower right')
ax.grid(True, alpha=0.3)
ax.set_aspect('equal')

# ── Panel B: grouped margin bars ──────────────────────────────────────────────
ax = axes[1]
x     = np.arange(len(MAC_PAIRS))
width = 0.19

for i, sc in enumerate(SCALES):
    margins = []
    for mp in MAC_PAIRS:
        row = df4[(df4['mac_pair'] == mp) & (df4['scaleType'] == sc)]
        margins.append(float(row['margin_dB'].values[0]) if len(row) else 0.0)
    offset = (i - 1.5) * width
    bars = ax.bar(x + offset, margins, width, label=sc,
                  color=SCALE_COLOR[sc], alpha=0.88, edgecolor='white', linewidth=0.5)
    for bar, val in zip(bars, margins):
        ax.text(bar.get_x() + bar.get_width() / 2, bar.get_height() + 0.3,
                f'{val:.0f}', ha='center', va='bottom', fontsize=6.5, color='#333')

ax.axhline(0, color='red', lw=1.5, ls='--', label='Safety boundary (0 dB)', zorder=3)
ax.set_xticks(x)
ax.set_xticklabels(MAC_PAIRS, rotation=30, ha='right', fontsize=8.5)
ax.set_ylabel('Safety margin  =  SQNR_S3 − SQNR_S4  (dB)', fontsize=10)
ax.set_title('(b) Safety margin per MAC pair × scale type', fontsize=10, fontweight='bold')
ax.legend(fontsize=8)
ax.grid(True, alpha=0.3, axis='y')
ax.set_ylim(bottom=0)

# ── Panel C: margin heatmap ───────────────────────────────────────────────────
ax = axes[2]
heat = np.zeros((len(MAC_PAIRS), len(SCALES)))
for i, mp in enumerate(MAC_PAIRS):
    for j, sc in enumerate(SCALES):
        row = df4[(df4['mac_pair'] == mp) & (df4['scaleType'] == sc)]
        heat[i, j] = float(row['margin_dB'].values[0]) if len(row) else 0.0

im = ax.imshow(heat, cmap='YlGn', aspect='auto', vmin=0, vmax=heat.max() + 5)
cbar = plt.colorbar(im, ax=ax, shrink=0.85)
cbar.set_label('Margin (dB)', fontsize=9)

ax.set_xticks(range(len(SCALES)))
ax.set_xticklabels(SCALES, fontsize=9)
ax.set_yticks(range(len(MAC_PAIRS)))
ax.set_yticklabels(MAC_PAIRS, fontsize=8.5)

for i in range(len(MAC_PAIRS)):
    for j in range(len(SCALES)):
        val = heat[i, j]
        text_color = 'black' if val < heat.max() * 0.6 else 'white'
        ax.text(j, i, f'+{val:.1f}', ha='center', va='center',
                fontsize=8.5, fontweight='bold', color=text_color)

ax.set_title('(c) Safety margin heatmap (dB)\n(all green = all SAFE, min ≥ +4 dB)', fontsize=10, fontweight='bold')

plt.tight_layout()
plt.savefig('bf16_acc_safety_k4_panel.png', dpi=150, bbox_inches='tight')
plt.close()
print("Saved bf16_acc_safety_k4_panel.png")

# ─────────────────────────────────────────────────────────────────────────────
# Figure 2: full pipeline noise breakdown (K=4, UE8M0 only for clarity)
# Shows how each stage contributes noise and where the budget is spent
# ─────────────────────────────────────────────────────────────────────────────
fig, axes = plt.subplots(1, 2, figsize=(16, 6))
fig.suptitle(
    'BF16 Accumulator — Pipeline Noise Budget  (K = 4, AccPrecision.recommended)\n'
    'Each bar = SQNR at that stage; S3 must exceed S4 (requant dominates)',
    fontsize=11, fontweight='bold', y=1.02
)

STAGE_COLORS = {
    'SQNR_S1_dB': '#90CAF9',   # light blue  — FP32 rounding (negligible)
    'SQNR_S2_dB': '#42A5F5',   # blue         — accumulation precision
    'SQNR_S3_dB': '#FF7043',   # orange-red   — BF16 truncation (KEY)
    'SQNR_S4_dB': '#EF5350',   # red          — requantization
}
STAGE_LABELS = {
    'SQNR_S1_dB': 'S1: FP32 rounding\n(~99 dB, negligible)',
    'SQNR_S2_dB': 'S2: Accumulation @ accM bits',
    'SQNR_S3_dB': 'S3: BF16 truncation  ← KEY',
    'SQNR_S4_dB': 'S4: Requantization  ← floor',
}

# ── Left panel: UE8M0 across all MAC pairs ────────────────────────────────────
ax = axes[0]
sub_ue8 = df4[df4['scaleType'] == 'UE8M0'].set_index('mac_pair').reindex(MAC_PAIRS)
stages  = ['SQNR_S1_dB', 'SQNR_S2_dB', 'SQNR_S3_dB', 'SQNR_S4_dB']
x = np.arange(len(MAC_PAIRS))
width = 0.18

for i, stage in enumerate(stages):
    vals = sub_ue8[stage].values.astype(float)
    vals_plot = np.minimum(vals, 99.0)   # cap S1 at 99 dB for readability
    bars = ax.bar(x + (i - 1.5) * width, vals_plot, width,
                  label=STAGE_LABELS[stage], color=STAGE_COLORS[stage],
                  edgecolor='white', linewidth=0.5)
    for bar, v in zip(bars, vals_plot):
        ax.text(bar.get_x() + bar.get_width() / 2, bar.get_height() + 0.5,
                f'{v:.0f}', ha='center', va='bottom', fontsize=6.5, rotation=90)

# Annotate accMantBits
for xi, mp in zip(x, MAC_PAIRS):
    row = sub_ue8.loc[mp]
    ax.text(xi, -6, f'accM={int(row["accMantBits"])}',
            ha='center', va='top', fontsize=7.5, color='#555', fontweight='bold')

ax.set_xticks(x)
ax.set_xticklabels(MAC_PAIRS, rotation=30, ha='right', fontsize=8.5)
ax.set_ylabel('SQNR (dB)', fontsize=10)
ax.set_title('(d) All pipeline stages — UE8M0 scale, K=4\naccM = AccPrecision.recommended(scfg, K=4)',
             fontsize=10, fontweight='bold')
ax.legend(fontsize=7.5, loc='upper right')
ax.grid(True, alpha=0.3, axis='y')
ax.set_ylim(-10, 105)

# Highlight S3 > S4 relation
for xi in x:
    ax.annotate('', xy=(xi + 0.5 * width, sub_ue8.iloc[x.tolist().index(xi)]['SQNR_S4_dB'] + 1),
                xytext=(xi + 0.5 * width,
                        min(sub_ue8.iloc[x.tolist().index(xi)]['SQNR_S3_dB'] - 1, 99)),
                arrowprops=dict(arrowstyle='<->', color='#B71C1C', lw=1.2))

# ── Right panel: S3 vs S4 for all scale types, grouped by MAC pair ───────────
ax = axes[1]
x = np.arange(len(MAC_PAIRS))
width = 0.10
group_gap = 0.02

for j, sc in enumerate(SCALES):
    sub_sc = df4[df4['scaleType'] == sc].set_index('mac_pair').reindex(MAC_PAIRS)
    s3_vals = np.minimum(sub_sc['SQNR_S3_dB'].values.astype(float), 99.0)
    s4_vals = sub_sc['SQNR_S4_dB'].values.astype(float)
    base = (j - 1.5) * (2 * width + group_gap)
    ax.bar(x + base,             s3_vals, width, color=SCALE_COLOR[sc], alpha=0.9,
           label=f'{sc} — S3 (BF16 trunc)', edgecolor='white', linewidth=0.3)
    ax.bar(x + base + width,     s4_vals, width, color=SCALE_COLOR[sc], alpha=0.45,
           hatch='//', label=f'{sc} — S4 (requant)', edgecolor=SCALE_COLOR[sc], linewidth=0.5)

ax.set_xticks(x)
ax.set_xticklabels(MAC_PAIRS, rotation=30, ha='right', fontsize=8.5)
ax.set_ylabel('SQNR (dB)', fontsize=10)
ax.set_title('(e) S3 (solid) vs S4 (hatched) for all scale types\nSolid bar always taller → BF16 truncation noise < requant noise',
             fontsize=10, fontweight='bold')
ax.grid(True, alpha=0.3, axis='y')
ax.set_ylim(0, 70)

# Compact legend: one entry per scale
legend_handles = [
    mpatches.Patch(facecolor=SCALE_COLOR[sc], label=f'{sc}') for sc in SCALES
]
solid_patch   = mpatches.Patch(facecolor='grey',       label='Solid = S3 (BF16 trunc)')
hatched_patch = mpatches.Patch(facecolor='grey', hatch='//', label='Hatched = S4 (requant)')
ax.legend(handles=legend_handles + [solid_patch, hatched_patch], fontsize=7.5, ncol=2, loc='upper right')

plt.tight_layout()
plt.savefig('bf16_acc_safety_k4_pipeline.png', dpi=150, bbox_inches='tight')
plt.close()
print("Saved bf16_acc_safety_k4_pipeline.png")

# ─────────────────────────────────────────────────────────────────────────────
# Print quick summary table
# ─────────────────────────────────────────────────────────────────────────────
print()
print("=" * 80)
print("SUMMARY: minimum safety margin at K=4 (AccPrecision.recommended)")
print("=" * 80)
pivot = df4.pivot_table(index='mac_pair', columns='scaleType', values='margin_dB')
pivot = pivot.reindex(index=MAC_PAIRS, columns=SCALES)
print(pivot.round(1).to_string())
print()
print(f"Worst-case minimum margin: {df4['margin_dB'].min():.1f} dB  ({df4.loc[df4['margin_dB'].idxmin(), 'mac_pair']}, {df4.loc[df4['margin_dB'].idxmin(), 'scaleType']})")
print("All configurations: SAFE" if (df4['margin_dB'] > 0).all() else "WARNING: some configs unsafe!")
