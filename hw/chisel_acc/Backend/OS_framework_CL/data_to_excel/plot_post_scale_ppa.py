"""
plot_post_scale_ppa.py — PPA plots for post_scale_manifest.csv (vec=4 only).

Produces:
  fig1_area.png    — area:   3-panel bar (per elem_a) + heatmap
  fig2_timing.png  — timing: 3-panel bar (per elem_a) + heatmap
  fig3_power.png   — power:  3-panel bar (per elem_a) + annotated scatter
  fig4_pareto.png  — Pareto fronts: area-freq, power-freq, area-power
"""

import re
from pathlib import Path

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import matplotlib.patches as mpatches
import numpy as np
import pandas as pd

# ── paths ──────────────────────────────────────────────────────────────────
HERE   = Path(__file__).parent
CSV    = HERE / "post_scale_manifest.csv"
OUTDIR = HERE / "plots_post_scale"
OUTDIR.mkdir(exist_ok=True)

# ── global style ───────────────────────────────────────────────────────────
plt.rcParams.update({
    "font.family": "sans-serif",
    "font.size": 9,
    "axes.titlesize": 10,
    "axes.titleweight": "bold",
    "axes.labelsize": 9,
    "xtick.labelsize": 8,
    "ytick.labelsize": 8,
    "figure.dpi": 150,
})

SCALE_ORDER  = ["UE4M4", "UE6M2", "UE8M0"]
ELEM_A_ORDER = ["E4M3", "E5M2", "INT8"]

SCALE_COLORS = {"UE4M4": "#4C72B0", "UE6M2": "#DD8452", "UE8M0": "#55A868"}
ELEMA_COLORS = {"E4M3": "#4C72B0", "E5M2": "#C44E52", "INT8": "#55A868"}
# One distinct marker per elem_b (up to 6 values)
ELEMB_MARKERS = {"E2M1": "o", "E2M3": "s", "E3M2": "^", "E4M3": "D",
                 "E5M2": "v", "INT8": "P"}
SCALE_HATCHES  = {"UE4M4": "", "UE6M2": "///", "UE8M0": "xxx"}
# (ELEMB_MARKERS kept for backward compat; Pareto now uses ELEMA_MARKERS)



# ── load & filter ──────────────────────────────────────────────────────────
df_all = pd.read_csv(CSV)
df = df_all[df_all["vec_size"] == 4].copy().reset_index(drop=True)
df["power_mW"] = df["power_default_W"] * 1e3

# elem_b ordering per elem_a
ELEMB_BY_A = {ea: sorted(df[df["elem_a"] == ea]["elem_b"].unique().tolist())
              for ea in ELEM_A_ORDER}

# flat combo order for heatmap rows
combo_order = []
for ea in ELEM_A_ORDER:
    for eb in ELEMB_BY_A[ea]:
        combo_order.append(f"{ea}×{eb}")
df["combo"] = df["elem_a"] + "×" + df["elem_b"]


# ── helpers ────────────────────────────────────────────────────────────────

def draw_3panel_bars(axes_list, metric, ylabel, title_prefix, df=df):
    """
    Draw one bar-cluster panel per elem_a group.
    axes_list: list of 3 Axes (one per elem_a).
    Bars are grouped by elem_b on x-axis; colour + hatch = scale_type.
    Each bar is labelled with its numeric value above the bar.
    """
    bar_w = 0.22
    for ax, ea in zip(axes_list, ELEM_A_ORDER):
        sub_ea = df[df["elem_a"] == ea]
        eb_list = ELEMB_BY_A[ea]
        x = np.arange(len(eb_list))

        for si, sc in enumerate(SCALE_ORDER):
            sub_sc = sub_ea[sub_ea["scale_type"] == sc].set_index("elem_b")
            vals   = [sub_sc.loc[eb, metric] if eb in sub_sc.index else 0
                      for eb in eb_list]
            bars = ax.bar(x + si * bar_w, vals, width=bar_w,
                          color=SCALE_COLORS[sc], hatch=SCALE_HATCHES[sc],
                          label=sc, alpha=0.88, edgecolor="white", linewidth=0.5)
            # value label above each bar
            for bar, val in zip(bars, vals):
                if val:
                    ax.text(bar.get_x() + bar.get_width() / 2,
                            bar.get_height() * 1.01,
                            f"{val:.0f}" if val >= 10 else f"{val:.2f}",
                            ha="center", va="bottom", fontsize=5.5, rotation=90,
                            color="#333333")

        ax.set_xticks(x + bar_w)
        ax.set_xticklabels(eb_list, rotation=30, ha="right", fontsize=8)
        ax.set_xlabel("elem_b (weight type)")
        ax.set_ylabel(ylabel)
        ax.set_title(f"{title_prefix} — {ea}", color=ELEMA_COLORS[ea])
        ax.legend(title="scale", fontsize=7, title_fontsize=7)
        ax.yaxis.grid(True, linestyle="--", alpha=0.4)
        ax.set_axisbelow(True)




def pareto_mask(costs: np.ndarray) -> np.ndarray:
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


# ══════════════════════════════════════════════════════════════════════════
# FIGURE 1 — Area Analysis  (3 panels, no heatmap)
# ══════════════════════════════════════════════════════════════════════════
fig1, axes1 = plt.subplots(1, 3, figsize=(18, 7), sharey=False)
fig1.suptitle("Area Analysis — vec=4  (NanGate 45 nm)", fontsize=13, y=1.01)
draw_3panel_bars(axes1, "area_um2", "Area (µm²)", "Area")
fig1.tight_layout()
fig1.savefig(OUTDIR / "fig1_area.png", bbox_inches="tight")
plt.close(fig1)
print("Saved fig1_area.png")


# ══════════════════════════════════════════════════════════════════════════
# FIGURE 2 — Timing Analysis  (3 panels, no heatmap)
# ══════════════════════════════════════════════════════════════════════════
fig2, axes2 = plt.subplots(1, 3, figsize=(18, 7), sharey=False)
fig2.suptitle("Timing Analysis — vec=4  (max freq = 1000 / CP_ns)", fontsize=13, y=1.01)
draw_3panel_bars(axes2, "max_freq_mhz", "Max Frequency (MHz)", "Max Freq")
fig2.tight_layout()
fig2.savefig(OUTDIR / "fig2_timing.png", bbox_inches="tight")
plt.close(fig2)
print("Saved fig2_timing.png")


# ══════════════════════════════════════════════════════════════════════════
# FIGURE 3 — Power Analysis  (3 panels, no heatmap)
# ══════════════════════════════════════════════════════════════════════════
fig3, axes3 = plt.subplots(1, 3, figsize=(18, 7), sharey=False)
fig3.suptitle("Power Analysis — vec=4  (RTL-VCD annotated)", fontsize=13, y=1.01)
draw_3panel_bars(axes3, "power_mW", "Power (mW)", "Power")
fig3.tight_layout()
fig3.savefig(OUTDIR / "fig3_power.png", bbox_inches="tight")
plt.close(fig3)
print("Saved fig3_power.png")


# ══════════════════════════════════════════════════════════════════════════
# FIGURE 4 — Pareto Fronts
# ══════════════════════════════════════════════════════════════════════════

# marker shape encodes elem_a (3 types)
ELEMA_MARKERS = {"E4M3": "o", "E5M2": "s", "INT8": "^"}


def plot_pareto_2d(ax, x_col, y_col, x_label, y_label, title,
                  minimise_x=True, minimise_y=True):
    """
    One Pareto curve per scale_type (colour = scale_type).
    Marker shape = elem_a.
    Every point labelled 'elem_a×elem_b'.
    Pareto-optimal points per scale get a black border + bold label.
    """
    for sc in SCALE_ORDER:
        sub  = df[df["scale_type"] == sc].reset_index(drop=True)
        xv   = sub[x_col].values.astype(float)
        yv   = y_col if isinstance(y_col, np.ndarray) else sub[y_col].values.astype(float)
        yv   = sub[y_col].values.astype(float)
        cx   = xv if minimise_x else -xv
        cy   = yv if minimise_y else -yv
        mask = pareto_mask(np.stack([cx, cy], axis=1))
        col  = SCALE_COLORS[sc]

        # All points for this scale
        for i, row in sub.iterrows():
            ea = row["elem_a"]
            is_opt = mask[i]
            ax.scatter(xv[i], yv[i],
                       color=col, marker=ELEMA_MARKERS[ea],
                       s=90 if is_opt else 40,
                       alpha=1.0 if is_opt else 0.45,
                       edgecolors="black" if is_opt else col,
                       linewidths=0.9 if is_opt else 0,
                       zorder=5 if is_opt else 2)
            # label every point: elem_a×elem_b
            label = f"{row['elem_a']}×{row['elem_b']}"
            ax.annotate(label, (xv[i], yv[i]),
                        fontsize=5.2,
                        fontweight="bold" if is_opt else "normal",
                        color=col,
                        xytext=(4, 3), textcoords="offset points")

        # Pareto front line for this scale (sorted by x)
        p_idx = np.where(mask)[0]
        if len(p_idx) > 1:
            order = np.argsort(xv[p_idx])
            px, py = xv[p_idx[order]], yv[p_idx[order]]
            ax.plot(px, py, color=col, lw=1.4, ls="--", zorder=4)

    # ── legend ──────────────────────────────────────────────────────────
    sc_patches = [mpatches.Patch(color=SCALE_COLORS[sc], label=f"scale={sc}")
                  for sc in SCALE_ORDER]
    ea_lines   = [plt.Line2D([0],[0], marker=ELEMA_MARKERS[ea], color="grey",
                              ls="None", markersize=7, label=f"elem_a={ea}")
                  for ea in ELEM_A_ORDER]
    opt_marker = plt.Line2D([0],[0], marker="o", color="grey", ls="None",
                             markersize=8, markeredgecolor="black",
                             markeredgewidth=0.9, label="Pareto-optimal")
    ax.legend(handles=sc_patches + ea_lines + [opt_marker],
              fontsize=6.5, ncol=2, loc="best", framealpha=0.9)
    ax.set_xlabel(x_label)
    ax.set_ylabel(y_label)
    ax.set_title(title)
    ax.grid(True, linestyle="--", alpha=0.3)


fig4, axes4 = plt.subplots(1, 3, figsize=(22, 8))
fig4.suptitle(
    "Pareto Fronts — vec=4   colour=scale_type · shape=elem_a · label=elem_a×elem_b\n"
    "(bold border = Pareto-optimal per scale)",
    fontsize=11, y=1.02)

plot_pareto_2d(axes4[0],
               "area_um2", "max_freq_mhz",
               "Area (µm²)", "Max Frequency (MHz)",
               "Area vs Frequency\n(↓ area, ↑ freq)",
               minimise_x=True, minimise_y=False)

plot_pareto_2d(axes4[1],
               "power_mW", "max_freq_mhz",
               "Power (mW)", "Max Frequency (MHz)",
               "Power vs Frequency\n(↓ power, ↑ freq)",
               minimise_x=True, minimise_y=False)

plot_pareto_2d(axes4[2],
               "area_um2", "power_mW",
               "Area (µm²)", "Power (mW)",
               "Area vs Power\n(↓ area, ↓ power)",
               minimise_x=True, minimise_y=True)

fig4.tight_layout()
fig4.savefig(OUTDIR / "fig4_pareto.png", bbox_inches="tight")
plt.close(fig4)
print("Saved fig4_pareto.png")

print(f"\nAll figures written to {OUTDIR}/")
