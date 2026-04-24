"""
plot_comparison.py — Compare adaptive vs post_scale (vec=4, matched configs).

Adaptive  : bfp_pe_manifest.csv   — single CustomOperator (no tree, no accum)
Post-scale: post_scale_manifest.csv — full pipeline (tree + scale + FPNAdder)

Produces (in plots_post_scale/):
  fig5_compare_timing.png  — critical path & max freq side-by-side + improvement
  fig6_compare_power.png   — power side-by-side + improvement ratio
  fig7_compare_area.png    — area side-by-side (post_scale includes tree overhead)
  fig8_compare_pareto.png  — Pareto fronts: both datasets overlaid (power vs freq,
                             area vs freq), coloured by source
"""

from pathlib import Path
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import matplotlib.patches as mpatches
import numpy as np
import pandas as pd

# ── paths ──────────────────────────────────────────────────────────────────
HERE   = Path(__file__).parent
OUTDIR = HERE / "plots_post_scale"
OUTDIR.mkdir(exist_ok=True)

plt.rcParams.update({
    "font.family": "sans-serif",
    "font.size": 9,
    "axes.titlesize": 10,
    "axes.titleweight": "bold",
    "axes.labelsize": 9,
    "xtick.labelsize": 7.5,
    "ytick.labelsize": 8,
    "figure.dpi": 150,
})

# ── load & harmonise ───────────────────────────────────────────────────────
adp = pd.read_csv(HERE / "bfp_pe_manifest.csv")
psc = pd.read_csv(HERE / "post_scale_manifest.csv")

# Both: vec=4 only
adp = adp[adp["vec_size"] == 4].copy()
psc = psc[psc["vec_size"] == 4].copy()

# Normalise power to mW
adp["power_mW"] = adp["power_default_W"] * 1e3
psc["power_mW"] = psc["power_default_W"] * 1e3

# max_freq for adaptive (not pre-computed)
adp["max_freq_mhz"] = 1000.0 / adp["critical_path_ns"]

# ── merge on matching configurations ──────────────────────────────────────
KEY = ["elem_a", "elem_b", "scale_type"]
merged = adp.merge(psc, on=KEY, suffixes=("_adp", "_psc"))

# Improvement ratios (post_scale relative to adaptive)
merged["freq_improve"]  = merged["max_freq_mhz_psc"] / merged["max_freq_mhz_adp"]
merged["power_improve"] = merged["power_mW_adp"]     / merged["power_mW_psc"]   # >1 = psc uses less
merged["area_improve"]  = merged["area_um2_adp"]     / merged["area_um2_psc"]   # typically <1 (psc larger)

# Ordered x-axis label: elem_a × elem_b × scale_type
ELEM_A_ORDER = ["E4M3", "E5M2"]
SCALE_ORDER  = ["UE4M4", "UE6M2", "UE8M0"]
SCALE_COLORS = {"UE4M4": "#4C72B0", "UE6M2": "#DD8452", "UE8M0": "#55A868"}
ELEMA_COLORS = {"E4M3": "#4C72B0", "E5M2": "#C44E52"}
ELEMA_MARKERS= {"E4M3": "o", "E5M2": "s"}

# Per-elem_a, elem_b ordering
from collections import defaultdict
ELEMB_BY_A = defaultdict(list)
for ea in ELEM_A_ORDER:
    ELEMB_BY_A[ea] = sorted(merged[merged["elem_a"]==ea]["elem_b"].unique().tolist())


# ── helper: 3-panel side-by-side bars ──────────────────────────────────────

def draw_compare_panels(axes, metric_adp, metric_psc, ylabel, title_prefix):
    """
    2 panels (one per elem_a).  Within each panel: groups of bars by elem_b,
    3 pairs per group (one pair per scale_type).
    Adaptive = hatched lighter bar, post_scale = solid darker bar.
    """
    bar_w = 0.18
    for ax, ea in zip(axes, ELEM_A_ORDER):
        sub  = merged[merged["elem_a"] == ea]
        ebs  = ELEMB_BY_A[ea]
        x    = np.arange(len(ebs))
        for si, sc in enumerate(SCALE_ORDER):
            sc_sub   = sub[sub["scale_type"] == sc].set_index("elem_b")
            col      = SCALE_COLORS[sc]
            offset_a = (si * 2)     * bar_w
            offset_p = (si * 2 + 1) * bar_w
            vals_a   = [sc_sub.loc[eb, metric_adp] if eb in sc_sub.index else 0 for eb in ebs]
            vals_p   = [sc_sub.loc[eb, metric_psc] if eb in sc_sub.index else 0 for eb in ebs]
            ax.bar(x + offset_a, vals_a, width=bar_w, color=col,
                   alpha=0.45, hatch="///", edgecolor=col, linewidth=0.5,
                   label=f"{sc} adaptive" if ea == ELEM_A_ORDER[0] else "")
            ax.bar(x + offset_p, vals_p, width=bar_w, color=col,
                   alpha=0.9, edgecolor="white", linewidth=0.4,
                   label=f"{sc} post_scale" if ea == ELEM_A_ORDER[0] else "")
            # value labels
            for bars, vals in [(ax.patches[-len(ebs)*2:-len(ebs)], vals_a),
                                (ax.patches[-len(ebs):], vals_p)]:
                for bar, v in zip(bars, vals):
                    if v:
                        ax.text(bar.get_x() + bar.get_width()/2,
                                bar.get_height() * 1.01,
                                f"{v:.0f}" if v >= 10 else f"{v:.2f}",
                                ha="center", va="bottom", fontsize=4.8, rotation=90)

        center = (len(SCALE_ORDER) * 2 - 1) * bar_w / 2
        ax.set_xticks(x + center)
        ax.set_xticklabels(ebs, rotation=25, ha="right", fontsize=8)
        ax.set_xlabel("elem_b")
        ax.set_ylabel(ylabel)
        ax.set_title(f"{title_prefix} — {ea}", color=ELEMA_COLORS[ea])
        ax.yaxis.grid(True, linestyle="--", alpha=0.4)
        ax.set_axisbelow(True)
        if ea == ELEM_A_ORDER[0]:
            ax.legend(fontsize=6, ncol=3, loc="upper left", title="scale · source",
                      title_fontsize=6)


def draw_improve_panel(ax, ratio_col, ylabel, title, ref_line=1.0, better_above=True):
    """
    Scatter improvement ratio vs variant, coloured by elem_a, shaped by scale_type.
    """
    scale_mk = {"UE4M4": "o", "UE6M2": "s", "UE8M0": "^"}
    for _, row in merged.iterrows():
        ea, sc = row["elem_a"], row["scale_type"]
        ax.scatter(row["elem_b"], row[ratio_col],
                   color=ELEMA_COLORS.get(ea, "grey"),
                   marker=scale_mk[sc], s=55, alpha=0.85, zorder=3,
                   edgecolors="white", linewidths=0.3)
        ax.annotate(f"{ea}",
                    (row["elem_b"], row[ratio_col]),
                    fontsize=4.5, color=ELEMA_COLORS.get(ea, "grey"),
                    xytext=(3, 2), textcoords="offset points")

    ax.axhline(ref_line, color="black", lw=1.0, ls="--", label=f"baseline = {ref_line:.1f}×")
    better = "▲ post_scale better" if better_above else "▼ post_scale better"
    ax.text(0.98, ref_line + (ax.get_ylim()[1]-ref_line)*0.1 if better_above else ref_line - 0.05,
            better, transform=ax.get_yaxis_transform(), ha="right", fontsize=7,
            color="green" if better_above else "red")

    ea_patches = [mpatches.Patch(color=ELEMA_COLORS[ea], label=f"elem_a={ea}")
                  for ea in ELEM_A_ORDER]
    sc_lines   = [plt.Line2D([0],[0], marker=scale_mk[sc], color="grey",
                              ls="None", markersize=6, label=f"scale={sc}")
                  for sc in SCALE_ORDER]
    ax.legend(handles=ea_patches + sc_lines, fontsize=6.5, ncol=2)
    ax.set_xlabel("elem_b")
    ax.set_ylabel(ylabel)
    ax.set_title(title)
    ax.yaxis.grid(True, linestyle="--", alpha=0.4)
    ax.set_axisbelow(True)


# ══════════════════════════════════════════════════════════════════════════
# FIG 5 — Timing comparison
# ══════════════════════════════════════════════════════════════════════════
fig5, axes5 = plt.subplots(1, 3, figsize=(20, 7))
fig5.suptitle(
    "Timing Comparison — vec=4   (hatched=adaptive, solid=post_scale)\n"
    "Note: adaptive synthesised @50 MHz, post_scale @500 MHz",
    fontsize=11, y=1.02)

draw_compare_panels(axes5[:2], "max_freq_mhz_adp", "max_freq_mhz_psc",
                    "Max Frequency (MHz)", "Max Freq")

draw_improve_panel(axes5[2],
                   ratio_col="freq_improve",
                   ylabel="post_scale freq / adaptive freq",
                   title="Freq Improvement Ratio\n(>1 = post_scale faster)",
                   ref_line=1.0, better_above=True)

fig5.tight_layout()
fig5.savefig(OUTDIR / "fig5_compare_timing.png", bbox_inches="tight")
plt.close(fig5)
print("Saved fig5_compare_timing.png")


# ══════════════════════════════════════════════════════════════════════════
# FIG 6 — Power comparison
# ══════════════════════════════════════════════════════════════════════════
fig6, axes6 = plt.subplots(1, 3, figsize=(20, 7))
fig6.suptitle(
    "Power Comparison — vec=4   (hatched=adaptive, solid=post_scale)",
    fontsize=11, y=1.02)

draw_compare_panels(axes6[:2], "power_mW_adp", "power_mW_psc",
                    "Power (mW)", "Power")

draw_improve_panel(axes6[2],
                   ratio_col="power_improve",
                   ylabel="adaptive power / post_scale power",
                   title="Power Improvement Ratio\n(>1 = post_scale uses less power)",
                   ref_line=1.0, better_above=True)

fig6.tight_layout()
fig6.savefig(OUTDIR / "fig6_compare_power.png", bbox_inches="tight")
plt.close(fig6)
print("Saved fig6_compare_power.png")


# ══════════════════════════════════════════════════════════════════════════
# FIG 7 — Area comparison
# ══════════════════════════════════════════════════════════════════════════
fig7, axes7 = plt.subplots(1, 3, figsize=(20, 7))
fig7.suptitle(
    "Area Comparison — vec=4   (hatched=adaptive operator-only, solid=post_scale full pipeline)\n"
    "Post_scale area includes reduction tree + FPNAdder overhead",
    fontsize=10, y=1.02)

draw_compare_panels(axes7[:2], "area_um2_adp", "area_um2_psc",
                    "Area (µm²)", "Area")

draw_improve_panel(axes7[2],
                   ratio_col="area_improve",
                   ylabel="adaptive area / post_scale area",
                   title="Area Ratio  (adp / psc)\n(<1 = post_scale larger, expected)",
                   ref_line=1.0, better_above=False)

fig7.tight_layout()
fig7.savefig(OUTDIR / "fig7_compare_area.png", bbox_inches="tight")
plt.close(fig7)
print("Saved fig7_compare_area.png")


# ══════════════════════════════════════════════════════════════════════════
# FIG 8 — Overlaid Pareto fronts: adaptive vs post_scale
# ══════════════════════════════════════════════════════════════════════════

SOURCE_COLORS = {"adaptive": "#888888", "post_scale": "#C44E52"}
SOURCE_ALPHA  = {"adaptive": 0.55,      "post_scale": 0.85}


def pareto_mask(costs):
    n = len(costs)
    dominated = np.zeros(n, dtype=bool)
    for i in range(n):
        for j in range(n):
            if i == j:
                continue
            if np.all(costs[j] <= costs[i]) and np.any(costs[j] < costs[i]):
                dominated[i] = True
                break
    return ~dominated


def pareto_overlay(ax, x_adp, y_adp, x_psc, y_psc,
                   x_label, y_label, title,
                   minimise_x=True, minimise_y=True):
    """
    Scatter both datasets; compute & draw each dataset's Pareto front.
    Points labelled with elem_a×elem_b.
    """
    for src, xv, yv, rows in [
        ("adaptive",   x_adp.values, y_adp.values, merged),
        ("post_scale", x_psc.values, y_psc.values, merged),
    ]:
        cx = xv if minimise_x else -xv
        cy = yv if minimise_y else -yv
        mask = pareto_mask(np.stack([cx, cy], axis=1))
        col  = SOURCE_COLORS[src]

        for i, row in rows.reset_index(drop=True).iterrows():
            ea, sc = row["elem_a"], row["scale_type"]
            is_opt = mask[i]
            ax.scatter(xv[i], yv[i],
                       color=col,
                       marker=ELEMA_MARKERS[ea],
                       s=80 if is_opt else 30,
                       alpha=SOURCE_ALPHA[src],
                       edgecolors="black" if is_opt else "none",
                       linewidths=0.8, zorder=5 if is_opt else 2)
            label = f"{row['elem_a']}×{row['elem_b']}\n{sc}"
            if is_opt:
                ax.annotate(label, (xv[i], yv[i]),
                            fontsize=5.0, color=col, fontweight="bold",
                            xytext=(4, 3), textcoords="offset points")

        # Pareto front line
        p_idx = np.where(mask)[0]
        if len(p_idx) > 1:
            order = np.argsort(xv[p_idx])
            ax.plot(xv[p_idx[order]], yv[p_idx[order]],
                    color=col, lw=1.4, ls="--", zorder=4)

    # legend
    src_patches = [mpatches.Patch(color=SOURCE_COLORS[s], label=s,
                                  alpha=SOURCE_ALPHA[s])
                   for s in ["adaptive", "post_scale"]]
    ea_lines = [plt.Line2D([0],[0], marker=ELEMA_MARKERS[ea], color="grey",
                            ls="None", markersize=7, label=f"elem_a={ea}")
                for ea in ELEM_A_ORDER]
    opt_mk = plt.Line2D([0],[0], marker="o", color="grey", ls="None",
                         markersize=8, markeredgecolor="black",
                         markeredgewidth=0.8, label="Pareto-optimal")
    ax.legend(handles=src_patches + ea_lines + [opt_mk],
              fontsize=6.5, ncol=2, framealpha=0.9)
    ax.set_xlabel(x_label)
    ax.set_ylabel(y_label)
    ax.set_title(title)
    ax.grid(True, ls="--", alpha=0.3)


fig8, axes8 = plt.subplots(1, 2, figsize=(16, 8))
fig8.suptitle(
    "Pareto Comparison — adaptive (grey) vs post_scale (red)  ·  vec=4\n"
    "bold border = Pareto-optimal per source  ·  shape = elem_a",
    fontsize=11, y=1.02)

pareto_overlay(axes8[0],
               merged["max_freq_mhz_adp"], merged["power_mW_adp"],
               merged["max_freq_mhz_psc"], merged["power_mW_psc"],
               "Max Frequency (MHz)", "Power (mW)",
               "Power vs Frequency\n(↑ freq, ↓ power)",
               minimise_x=False, minimise_y=True)

pareto_overlay(axes8[1],
               merged["area_um2_adp"], merged["max_freq_mhz_adp"],
               merged["area_um2_psc"], merged["max_freq_mhz_psc"],
               "Area (µm²)", "Max Frequency (MHz)",
               "Area vs Frequency\n(↓ area, ↑ freq)",
               minimise_x=True, minimise_y=False)

fig8.tight_layout()
fig8.savefig(OUTDIR / "fig8_compare_pareto.png", bbox_inches="tight")
plt.close(fig8)
print("Saved fig8_compare_pareto.png")

print(f"\nAll comparison figures written to {OUTDIR}/")
