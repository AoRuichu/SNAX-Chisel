"""
BFP_PE PPA Analysis Plots
--------------------------
Compares adaptive (optimized reduction tree) vs baseline FPU designs.
Focus: vec_size = 4.

Figures generated:
  1. Critical path comparison  : old vs new for matching variants
  2. Critical path improvement  : % reduction heatmap (new vs old)
  3. Area landscape (new only)  : heatmap elem_a × (elem_b, scale_type)
  4. Power landscape (new only) : same structure as area
  5. PPA by scale type          : how each metric trends with scale precision
  6. INT8 vs FP impact          : INT8 penalty relative to E4M3/E5M2 in new design
  7. Power vs area scatter       : trade-off space coloured by elem_a
"""

import warnings
warnings.filterwarnings("ignore")

from pathlib import Path
import pandas as pd
import numpy as np
import matplotlib.pyplot as plt
import matplotlib.patches as mpatches
import seaborn as sns

# ── Paths ──────────────────────────────────────────────────────────────────────
DATA_DIR   = Path(__file__).parent
ADAPTIVE   = DATA_DIR / "adaptive_bfp_pe_manifest.csv"
BASELINE   = DATA_DIR / "bfp_pe_manifest.csv"
OUTPUT_DIR = DATA_DIR / "plots"
OUTPUT_DIR.mkdir(exist_ok=True)

# ── Load & filter ──────────────────────────────────────────────────────────────
adp = pd.read_csv(ADAPTIVE)
bas = pd.read_csv(BASELINE)

# Focus on vec=4; drop vec=32 in adaptive
adp4 = adp[adp["vec_size"] == 4].copy()
bas4 = bas[bas["vec_size"] == 4].copy()

# Convenience columns
adp4["power_mW"] = adp4["power_default_W"] * 1e3
bas4["power_mW"] = bas4["power_default_W"] * 1e3

# Ordered scale types (decreasing mantissa complexity)
SCALE_ORDER = ["UE2M6", "UE4M4", "UE6M2", "UE8M0"]
SCALE_LABEL = {
    "UE2M6": "UE2M6\n(exp2/man6)",
    "UE4M4": "UE4M4\n(exp4/man4)",
    "UE6M2": "UE6M2\n(exp6/man2)",
    "UE8M0": "UE8M0\n(exp8/man0)",
}

ELEM_A_COLORS = {
    "E4M3": "#4C72B0",
    "E5M2": "#DD8452",
    "INT8": "#55A868",
}

plt.rcParams.update({
    "font.size": 9,
    "axes.titlesize": 10,
    "axes.labelsize": 9,
    "legend.fontsize": 8,
    "figure.dpi": 150,
})

# ══════════════════════════════════════════════════════════════════════════════
# Figure 1 — Critical Path Comparison: old vs new (matching variants, vec=4)
# ══════════════════════════════════════════════════════════════════════════════
# Matching key: (elem_a, elem_b, scale_type); baseline has no UE2M6 / INT8
merge_key = ["elem_a", "elem_b", "scale_type"]
matched = adp4.merge(
    bas4[merge_key + ["critical_path_ns", "power_mW"]],
    on=merge_key,
    suffixes=("_new", "_old"),
)
matched = matched[matched["scale_type"].isin(["UE4M4", "UE6M2", "UE8M0"])]

fig, axes = plt.subplots(1, 2, figsize=(14, 5.5), constrained_layout=True)
fig.suptitle("Critical Path & Power: New (Adaptive) vs Old (Baseline) — vec=4", fontweight="bold")

for ax, metric, ylabel, title in zip(
    axes,
    ["critical_path_ns", "power_mW"],
    ["Critical Path (ns)", "Power (mW)"],
    ["Critical Path Comparison", "Power Comparison"],
):
    matched_sorted = matched.sort_values(["elem_a", "elem_b", "scale_type"])
    labels = [f"{r.elem_a}\n{r.elem_b}\n{r.scale_type}" for _, r in matched_sorted.iterrows()]
    x = np.arange(len(labels))
    w = 0.35

    bars_new = ax.bar(x - w / 2, matched_sorted[f"{metric}_new"], w, label="New (adaptive)", color="#4C72B0", alpha=0.85)
    bars_old = ax.bar(x + w / 2, matched_sorted[f"{metric}_old"], w, label="Old (baseline)", color="#DD8452", alpha=0.85)

    ax.set_xticks(x)
    ax.set_xticklabels(labels, fontsize=6.5, rotation=0)
    ax.set_ylabel(ylabel)
    ax.set_title(title)
    ax.legend()
    ax.grid(axis="y", alpha=0.3)
    ax.set_xlim(-0.7, len(labels) - 0.3)

    # Shade elem_a groups
    ea_order = matched_sorted["elem_a"].values
    group_colors = {"E4M3": "#e8f0ff", "E5M2": "#fff4e8"}
    prev, start = ea_order[0], 0
    for i, ea in enumerate(ea_order):
        if ea != prev or i == len(ea_order) - 1:
            end = i if ea != prev else i + 1
            ax.axvspan(start - 0.5, end - 0.5, color=group_colors.get(prev, "#f0f0f0"), alpha=0.3, zorder=0)
            # label group
            mid = (start + end - 1) / 2
            ax.text(mid, ax.get_ylim()[1] * 0.98, prev, ha="center", va="top", fontsize=7, color="#555555")
            prev, start = ea, i

plt.savefig(OUTPUT_DIR / "fig1_critical_path_power_comparison.png", bbox_inches="tight")
print("Saved fig1_critical_path_power_comparison.png")
plt.close()

# ══════════════════════════════════════════════════════════════════════════════
# Figure 2 — Improvement Heatmap: % reduction in critical path & power (new vs old)
# ══════════════════════════════════════════════════════════════════════════════
matched["cp_reduction_%"] = (matched["critical_path_ns_old"] - matched["critical_path_ns_new"]) / matched["critical_path_ns_old"] * 100
matched["pwr_reduction_%"] = (matched["power_mW_old"] - matched["power_mW_new"]) / matched["power_mW_old"] * 100

fig, axes = plt.subplots(1, 2, figsize=(14, 5), constrained_layout=True)
fig.suptitle("Improvement of New Design over Baseline (%, higher = better) — vec=4", fontweight="bold")

for ax, col, title, cmap in zip(
    axes,
    ["cp_reduction_%", "pwr_reduction_%"],
    ["Critical Path Reduction (%)", "Power Reduction (%)"],
    ["RdYlGn", "RdYlGn"],
):
    pivot = matched.pivot_table(index=["elem_a", "elem_b"], columns="scale_type", values=col)
    pivot = pivot.reindex(columns=[c for c in SCALE_ORDER if c in pivot.columns])
    # Sort rows by elem_a
    pivot = pivot.sort_index()

    vmax = max(abs(pivot.values[~np.isnan(pivot.values)].max()),
               abs(pivot.values[~np.isnan(pivot.values)].min()))
    sns.heatmap(
        pivot,
        ax=ax,
        annot=True,
        fmt=".1f",
        cmap=cmap,
        center=0,
        vmin=-vmax,
        vmax=vmax,
        linewidths=0.5,
        cbar_kws={"label": "%"},
    )
    ax.set_title(title)
    ax.set_xlabel("Scale Type")
    ax.set_ylabel("(elem_a, elem_b)")
    ax.tick_params(axis="x", rotation=0)
    ax.tick_params(axis="y", rotation=0)

plt.savefig(OUTPUT_DIR / "fig2_improvement_heatmap.png", bbox_inches="tight")
print("Saved fig2_improvement_heatmap.png")
plt.close()

# ══════════════════════════════════════════════════════════════════════════════
# Figure 3 — Area Landscape (new design, vec=4)
# ══════════════════════════════════════════════════════════════════════════════
fig, axes = plt.subplots(1, 3, figsize=(16, 5), constrained_layout=True)
fig.suptitle("Area Landscape — New Design (vec=4), by elem_a", fontweight="bold")

for ax, ea in zip(axes, ["E4M3", "E5M2", "INT8"]):
    sub = adp4[adp4["elem_a"] == ea]
    pivot = sub.pivot_table(index="elem_b", columns="scale_type", values="area_um2")
    pivot = pivot.reindex(columns=[c for c in SCALE_ORDER if c in pivot.columns])

    sns.heatmap(
        pivot,
        ax=ax,
        annot=True,
        fmt=".0f",
        cmap="YlOrRd",
        linewidths=0.5,
        cbar_kws={"label": "Area (µm²)"},
    )
    ax.set_title(f"elem_a = {ea}")
    ax.set_xlabel("Scale Type")
    ax.set_ylabel("elem_b")
    ax.tick_params(axis="x", rotation=0)
    ax.tick_params(axis="y", rotation=0)

plt.savefig(OUTPUT_DIR / "fig3_area_heatmap.png", bbox_inches="tight")
print("Saved fig3_area_heatmap.png")
plt.close()

# ══════════════════════════════════════════════════════════════════════════════
# Figure 4 — Critical Path Landscape (new design, vec=4)
# ══════════════════════════════════════════════════════════════════════════════
fig, axes = plt.subplots(1, 3, figsize=(16, 5), constrained_layout=True)
fig.suptitle("Critical Path Landscape — New Design (vec=4), by elem_a", fontweight="bold")

for ax, ea in zip(axes, ["E4M3", "E5M2", "INT8"]):
    sub = adp4[adp4["elem_a"] == ea]
    pivot = sub.pivot_table(index="elem_b", columns="scale_type", values="critical_path_ns")
    pivot = pivot.reindex(columns=[c for c in SCALE_ORDER if c in pivot.columns])

    sns.heatmap(
        pivot,
        ax=ax,
        annot=True,
        fmt=".2f",
        cmap="YlOrRd",
        linewidths=0.5,
        cbar_kws={"label": "Critical Path (ns)"},
    )
    ax.set_title(f"elem_a = {ea}")
    ax.set_xlabel("Scale Type")
    ax.set_ylabel("elem_b")
    ax.tick_params(axis="x", rotation=0)
    ax.tick_params(axis="y", rotation=0)

plt.savefig(OUTPUT_DIR / "fig4_critical_path_heatmap.png", bbox_inches="tight")
print("Saved fig4_critical_path_heatmap.png")
plt.close()

# ══════════════════════════════════════════════════════════════════════════════
# Figure 5 — PPA vs Scale Type trend (new design, vec=4)
# ══════════════════════════════════════════════════════════════════════════════
fig, axes = plt.subplots(1, 3, figsize=(15, 5), constrained_layout=True)
fig.suptitle("PPA Trend vs Scale Type Precision — New Design (vec=4)", fontweight="bold")

metrics = [
    ("area_um2",        "Area (µm²)",        "Area"),
    ("power_mW",        "Power (mW)",         "Power"),
    ("critical_path_ns","Critical Path (ns)", "Critical Path"),
]

for ax, (metric, ylabel, title) in zip(axes, metrics):
    for ea, color in ELEM_A_COLORS.items():
        sub = adp4[adp4["elem_a"] == ea]
        grp = (
            sub.groupby("scale_type")[metric]
            .agg(["mean", "min", "max"])
            .reindex([s for s in SCALE_ORDER if s in sub["scale_type"].unique()])
        )
        x = range(len(grp))
        ax.plot(x, grp["mean"], marker="o", color=color, label=ea, linewidth=1.8)
        ax.fill_between(x, grp["min"], grp["max"], color=color, alpha=0.15)

    ax.set_xticks(range(len(SCALE_ORDER)))
    ax.set_xticklabels(SCALE_ORDER, rotation=0)
    ax.set_ylabel(ylabel)
    ax.set_title(title)
    ax.legend()
    ax.grid(alpha=0.3)
    # Annotate direction
    ax.set_xlabel("← higher mantissa precision       lower →")

plt.savefig(OUTPUT_DIR / "fig5_ppa_vs_scale_type.png", bbox_inches="tight")
print("Saved fig5_ppa_vs_scale_type.png")
plt.close()

# ══════════════════════════════════════════════════════════════════════════════
# Figure 6 — INT8 Overhead vs FP8 (new design, vec=4)
# ══════════════════════════════════════════════════════════════════════════════
# Compare INT8 vs E4M3 and E5M2 for shared (elem_b, scale_type) pairs
# INT8 elem_b can be FP types or INT8 itself; focus on shared elem_b types
fp_eb_types = ["E2M1", "E2M3", "E3M2", "E4M3", "E5M2"]

int8_sub = adp4[(adp4["elem_a"] == "INT8") & (adp4["elem_b"].isin(fp_eb_types))].copy()
e4m3_sub = adp4[adp4["elem_a"] == "E4M3"].copy()
e5m2_sub = adp4[adp4["elem_a"] == "E5M2"].copy()

# Compute mean over elem_b per (elem_a, scale_type) for a cleaner comparison
def mean_ppa(df):
    return df.groupby("scale_type")[["area_um2", "power_mW", "critical_path_ns"]].mean()

int8_mean  = mean_ppa(int8_sub)
e4m3_mean  = mean_ppa(e4m3_sub)
e5m2_mean  = mean_ppa(e5m2_sub)

fig, axes = plt.subplots(1, 3, figsize=(15, 5), constrained_layout=True)
fig.suptitle("INT8 vs FP8 Overhead — New Design (vec=4, mean over shared elem_b)", fontweight="bold")

for ax, metric, ylabel, title in zip(
    axes,
    ["area_um2", "power_mW", "critical_path_ns"],
    ["Area (µm²)", "Power (mW)", "Critical Path (ns)"],
    ["Area", "Power", "Critical Path"],
):
    scale_types = [s for s in SCALE_ORDER if s in int8_mean.index]
    x = np.arange(len(scale_types))
    w = 0.27

    for i, (df, label, color) in enumerate(
        [(e4m3_mean, "E4M3 (FP8)", "#4C72B0"),
         (e5m2_mean, "E5M2 (FP8)", "#DD8452"),
         (int8_mean,  "INT8",       "#55A868")]
    ):
        vals = [df.loc[s, metric] if s in df.index else np.nan for s in scale_types]
        ax.bar(x + (i - 1) * w, vals, w, label=label, color=color, alpha=0.85)

    ax.set_xticks(x)
    ax.set_xticklabels(scale_types, rotation=0)
    ax.set_ylabel(ylabel)
    ax.set_title(title)
    ax.legend()
    ax.grid(axis="y", alpha=0.3)

plt.savefig(OUTPUT_DIR / "fig6_int8_vs_fp8.png", bbox_inches="tight")
print("Saved fig6_int8_vs_fp8.png")
plt.close()

# ══════════════════════════════════════════════════════════════════════════════
# Figure 7 — Power vs Area Trade-off (new design, vec=4)
# ══════════════════════════════════════════════════════════════════════════════
fig, axes = plt.subplots(1, 2, figsize=(13, 5.5), constrained_layout=True)
fig.suptitle("PPA Trade-off Space — New Design (vec=4)", fontweight="bold")

marker_map = {s: m for s, m in zip(SCALE_ORDER, ["o", "s", "^", "D"])}

for ax, (xcol, ycol, xlabel, ylabel, title) in zip(
    axes,
    [
        ("area_um2", "power_mW", "Area (µm²)", "Power (mW)", "Power vs Area"),
        ("area_um2", "critical_path_ns", "Area (µm²)", "Critical Path (ns)", "Timing vs Area"),
    ],
):
    for ea, color in ELEM_A_COLORS.items():
        sub = adp4[adp4["elem_a"] == ea]
        for st in SCALE_ORDER:
            pts = sub[sub["scale_type"] == st]
            if pts.empty:
                continue
            ax.scatter(
                pts[xcol], pts[ycol],
                c=color, marker=marker_map[st],
                s=55, alpha=0.75, edgecolors="white", linewidths=0.4,
            )

    # Legend: elem_a color patches
    ea_patches = [mpatches.Patch(color=c, label=ea) for ea, c in ELEM_A_COLORS.items()]
    # Legend: scale_type markers
    scale_handles = [
        plt.Line2D([0], [0], marker=marker_map[s], color="gray",
                   markersize=7, linestyle="None", label=s)
        for s in SCALE_ORDER
    ]
    leg1 = ax.legend(handles=ea_patches, loc="upper left", title="elem_a", framealpha=0.8)
    ax.add_artist(leg1)
    ax.legend(handles=scale_handles, loc="lower right", title="scale_type", framealpha=0.8)

    ax.set_xlabel(xlabel)
    ax.set_ylabel(ylabel)
    ax.set_title(title)
    ax.grid(alpha=0.3)

plt.savefig(OUTPUT_DIR / "fig7_ppa_scatter.png", bbox_inches="tight")
print("Saved fig7_ppa_scatter.png")
plt.close()

# ══════════════════════════════════════════════════════════════════════════════
# Figure 8 — vec=4 vs vec=16 scaling (new design only, no INT8 for clarity)
# ══════════════════════════════════════════════════════════════════════════════
adp_fp = adp[adp["elem_a"].isin(["E4M3", "E5M2"])].copy()
adp_fp["power_mW"] = adp_fp["power_default_W"] * 1e3

fig, axes = plt.subplots(1, 3, figsize=(15, 5.5), constrained_layout=True)
fig.suptitle("Scaling: vec=4 → vec=16 — New Design (FP8 types)", fontweight="bold")

for ax, metric, ylabel, title in zip(
    axes,
    ["area_um2", "power_mW", "critical_path_ns"],
    ["Area (µm²)", "Power (mW)", "Critical Path (ns)"],
    ["Area Scaling", "Power Scaling", "Timing Overhead"],
):
    v4  = adp_fp[adp_fp["vec_size"] == 4].groupby(["elem_a", "scale_type"])[metric].mean()
    v16 = adp_fp[adp_fp["vec_size"] == 16].groupby(["elem_a", "scale_type"])[metric].mean()
    ratio = (v16 / v4).reset_index()
    ratio.columns = ["elem_a", "scale_type", "ratio"]

    scale_types = [s for s in SCALE_ORDER if s in ratio["scale_type"].unique()]
    x = np.arange(len(scale_types))
    w = 0.35

    for i, (ea, color) in enumerate([(k, v) for k, v in ELEM_A_COLORS.items() if k in ["E4M3", "E5M2"]]):
        sub = ratio[ratio["elem_a"] == ea]
        vals = [sub[sub["scale_type"] == s]["ratio"].values[0]
                if s in sub["scale_type"].values else np.nan
                for s in scale_types]
        offset = (i - 0.5) * w
        ax.bar(x + offset, vals, w, label=ea, color=color, alpha=0.85)

    ax.axhline(4, color="red", linestyle="--", linewidth=1.2, label="4× (ideal linear)")
    ax.set_xticks(x)
    ax.set_xticklabels(scale_types, rotation=0)
    ax.set_ylabel("vec16 / vec4 ratio")
    ax.set_title(title)
    ax.legend()
    ax.grid(axis="y", alpha=0.3)

plt.savefig(OUTPUT_DIR / "fig8_vec4_to_vec16_scaling.png", bbox_inches="tight")
print("Saved fig8_vec4_to_vec16_scaling.png")
plt.close()

# ══════════════════════════════════════════════════════════════════════════════
# Summary print
# ══════════════════════════════════════════════════════════════════════════════
print("\n=== Summary Statistics (new design, vec=4) ===")
for ea in ["E4M3", "E5M2", "INT8"]:
    sub = adp4[adp4["elem_a"] == ea]
    print(f"\n  elem_a = {ea}:")
    print(f"    Area   : {sub['area_um2'].min():.0f} – {sub['area_um2'].max():.0f} µm²")
    print(f"    Power  : {sub['power_mW'].min():.2f} – {sub['power_mW'].max():.2f} mW")
    print(f"    Timing : {sub['critical_path_ns'].min():.2f} – {sub['critical_path_ns'].max():.2f} ns")

print("\n=== Critical Path Improvement Summary (matching variants, vec=4) ===")
for ea in ["E4M3", "E5M2"]:
    sub = matched[matched["elem_a"] == ea]
    print(f"  {ea}: mean {sub['cp_reduction_%'].mean():.1f}%  "
          f"[{sub['cp_reduction_%'].min():.1f}% .. {sub['cp_reduction_%'].max():.1f}%] reduction")

print(f"\nAll plots saved to: {OUTPUT_DIR}")
