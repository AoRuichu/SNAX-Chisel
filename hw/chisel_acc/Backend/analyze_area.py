"""
DPU Area Analysis Script
Generates comparison charts for different data format combinations and vector sizes.

Config format: {input_fmt}_{weight_fmt}_{scale_fmt}_vec{size}
  - input_fmt:  E4M3, E5M2, INT8
  - weight_fmt: E2M1, E2M3, E3M2, E4M3, E5M2, INT8
  - scale_fmt:  UE2M6, UE4M4, UE6M2, UE8M0
  - vec_size:   vec1, vec4, vec16, vec32
"""

import pandas as pd
import matplotlib.pyplot as plt
import matplotlib.cm as cm
import numpy as np
from pathlib import Path
import re

# ── Config ────────────────────────────────────────────────────────────────────
SUMMARY_CSV     = Path(__file__).parent / "adaptive_area_summary.csv"
HIERARCHICAL_CSV= Path(__file__).parent / "adaptive_area_hierachy.csv"
OUTPUT_DIR      = Path(__file__).parent / "Figs"/"adaptive"
OUTPUT_DIR.mkdir(exist_ok=True)

VEC_ORDER   = ["vec4", "vec16", "vec32"]
VEC_INT_MAP = { "vec4": 4, "vec16": 16, "vec32": 32}

# ── Load & parse ──────────────────────────────────────────────────────────────
def load_summary(path: Path) -> pd.DataFrame:
    df = pd.read_csv(path)
    df = df[df["status"] == "OK"].copy()

    def parse_config(c):
        parts = c.split("_")
        vec_idx = next(i for i, p in enumerate(parts) if p.startswith("vec"))
        return pd.Series({
            "input_fmt":  parts[0],
            "weight_fmt": parts[1],
            "scale_fmt":  parts[vec_idx - 1],
            "vec_str":    parts[vec_idx],
            "vec_size":   VEC_INT_MAP.get(parts[vec_idx], 0),
            "precision":  f"{parts[0]}×{parts[1]}",
        })

    parsed = df["config"].apply(parse_config)
    df = pd.concat([df, parsed], axis=1)
    df["area_mm2"] = df["top_area_mm2"].astype(float)
    df["area_um2_per_lane"] = df["top_area_um2"].astype(float) / df["vec_size"].replace(0, np.nan)
    return df


def load_hierarchical(path: Path) -> pd.DataFrame:
    df = pd.read_csv(path)
    # Only sub-modules (not top)
    sub = df[df["is_top"] == False].copy()

    def parse_config(c):
        parts = c.split("_")
        vec_idx = next(i for i, p in enumerate(parts) if p.startswith("vec"))
        return pd.Series({
            "input_fmt":  parts[0],
            "weight_fmt": parts[1],
            "scale_fmt":  parts[vec_idx - 1],
            "vec_str":    parts[vec_idx],
            "vec_size":   VEC_INT_MAP.get(parts[vec_idx], 0),
        })

    parsed = sub["config"].apply(parse_config)
    sub = pd.concat([sub, parsed], axis=1)
    sub["area_um2"] = sub["area_um2"].astype(float)
    return sub


summary = load_summary(SUMMARY_CSV)
hier    = load_hierarchical(HIERARCHICAL_CSV)

# Shared style helpers
COLORS   = plt.rcParams["axes.prop_cycle"].by_key()["color"]
plt.rcParams.update({"figure.dpi": 150, "font.size": 9})


def savefig(fig, name: str):
    out = OUTPUT_DIR / f"area_{name}.png"
    fig.savefig(out, bbox_inches="tight")
    print(f"  Saved → {out}")
    plt.close(fig)


# ═══════════════════════════════════════════════════════════════════════════════
# Plot 1 ─ Same Vector Size: all precision combos compared
# ═══════════════════════════════════════════════════════════════════════════════
def plot_same_vecsize():
    print("[1] Same vector size comparison …")
    vec_sizes = [v for v in VEC_ORDER if v in summary["vec_str"].values]

    for scale in summary["scale_fmt"].unique():
        sub = summary[summary["scale_fmt"] == scale].copy()
        sub["label"] = sub["input_fmt"] + "×" + sub["weight_fmt"]

        fig, axes = plt.subplots(1, len(vec_sizes), figsize=(5 * len(vec_sizes), 5), sharey=False)
        fig.suptitle(f"Top-Level Area by Precision Combo  |  Scale = {scale}", fontsize=11, fontweight="bold")

        for ax, vec in zip(axes, vec_sizes):
            d = sub[sub["vec_str"] == vec].sort_values("area_mm2", ascending=False)
            bars = ax.bar(range(len(d)), d["area_mm2"] * 1e3, color=COLORS[:len(d)], edgecolor="white", linewidth=0.5)
            ax.set_xticks(range(len(d)))
            ax.set_xticklabels(d["label"], rotation=40, ha="right", fontsize=7)
            ax.set_title(f"{vec}", fontsize=9)
            ax.set_ylabel("Area (×10⁻³ mm²)" if ax == axes[0] else "")
            ax.yaxis.grid(True, linestyle="--", alpha=0.5)
            ax.set_axisbelow(True)
            # value labels
            for bar in bars:
                h = bar.get_height()
                ax.text(bar.get_x() + bar.get_width() / 2, h * 1.01, f"{h:.1f}",
                        ha="center", va="bottom", fontsize=6)

        fig.tight_layout()
        savefig(fig, f"1_vecsize_compare_scale_{scale}")


# ═══════════════════════════════════════════════════════════════════════════════
# Plot 2 ─ Same Precision: area vs vector size (scaling behaviour)
# ═══════════════════════════════════════════════════════════════════════════════
def plot_vecsize_scaling():
    print("[2] Area vs vector size (scaling) …")
    scale_fmts = sorted(summary["scale_fmt"].unique())

    for scale in scale_fmts:
        sub = summary[summary["scale_fmt"] == scale].copy()
        combos = sorted(sub["precision"].unique())

        fig, ax = plt.subplots(figsize=(8, 5))
        ax.set_title(f"Area Scaling vs Vector Size  |  Scale = {scale}", fontsize=11, fontweight="bold")

        for i, combo in enumerate(combos):
            d = sub[sub["precision"] == combo].sort_values("vec_size")
            ax.plot(d["vec_size"], d["area_mm2"] * 1e3, marker="o", label=combo,
                    color=COLORS[i % len(COLORS)], linewidth=1.5, markersize=5)

        ax.set_xlabel("Vector Size (lanes)")
        ax.set_ylabel("Top Area (×10⁻³ mm²)")
        ax.set_xticks(sorted(summary["vec_size"].unique()))
        ax.legend(bbox_to_anchor=(1.01, 1), loc="upper left", fontsize=7, frameon=True)
        ax.yaxis.grid(True, linestyle="--", alpha=0.5)
        ax.set_axisbelow(True)
        fig.tight_layout()
        savefig(fig, f"2_scaling_scale_{scale}")

    # Combined: all scales on one figure per precision group
    for combo in sorted(summary["precision"].unique()):
        sub = summary[summary["precision"] == combo].copy()
        fig, ax = plt.subplots(figsize=(7, 4))
        ax.set_title(f"Area Scaling vs Vector Size  |  Precision = {combo}", fontsize=11, fontweight="bold")
        for i, scale in enumerate(scale_fmts):
            d = sub[sub["scale_fmt"] == scale].sort_values("vec_size")
            ax.plot(d["vec_size"], d["area_mm2"] * 1e3, marker="o", label=scale,
                    color=COLORS[i % len(COLORS)], linewidth=1.5, markersize=5)
        ax.set_xlabel("Vector Size (lanes)")
        ax.set_ylabel("Top Area (×10⁻³ mm²)")
        ax.set_xticks(sorted(summary["vec_size"].unique()))
        ax.legend(fontsize=8)
        ax.yaxis.grid(True, linestyle="--", alpha=0.5)
        ax.set_axisbelow(True)
        fig.tight_layout()
        combo_tag = combo.replace("×", "x")
        savefig(fig, f"2_scaling_prec_{combo_tag}")


# ═══════════════════════════════════════════════════════════════════════════════
# Plot 3 ─ Fixed Weight & Scale: compare input formats
# ═══════════════════════════════════════════════════════════════════════════════
def plot_input_fmt_compare():
    print("[3] Input format comparison (fixed weight × scale) …")
    weight_fmts = sorted(summary["weight_fmt"].unique())
    scale_fmts  = sorted(summary["scale_fmt"].unique())
    vec_strs    = [v for v in VEC_ORDER if v in summary["vec_str"].values]

    for wf in weight_fmts:
        for scale in scale_fmts:
            sub = summary[(summary["weight_fmt"] == wf) & (summary["scale_fmt"] == scale)]
            input_fmts = sorted(sub["input_fmt"].unique())
            if len(input_fmts) < 2:
                continue

            fig, axes = plt.subplots(1, len(vec_strs), figsize=(4 * len(vec_strs), 4), sharey=False)
            fig.suptitle(f"Input Format Comparison  |  Weight={wf}  Scale={scale}", fontsize=10, fontweight="bold")

            for ax, vec in zip(axes, vec_strs):
                d = sub[sub["vec_str"] == vec].sort_values("input_fmt")
                colors = [COLORS[i] for i in range(len(d))]
                bars = ax.bar(d["input_fmt"], d["area_mm2"] * 1e3, color=colors, edgecolor="white")
                ax.set_title(f"{vec}", fontsize=9)
                ax.set_xlabel("Input Format")
                ax.set_ylabel("Area (×10⁻³ mm²)" if ax == axes[0] else "")
                ax.yaxis.grid(True, linestyle="--", alpha=0.5)
                ax.set_axisbelow(True)
                for bar in bars:
                    h = bar.get_height()
                    ax.text(bar.get_x() + bar.get_width() / 2, h * 1.01, f"{h:.1f}",
                            ha="center", va="bottom", fontsize=7)

            fig.tight_layout()
            savefig(fig, f"3_input_fmt_w{wf}_s{scale}")


# ═══════════════════════════════════════════════════════════════════════════════
# Plot 4 ─ Fixed Input & Scale: compare weight formats
# ═══════════════════════════════════════════════════════════════════════════════
def plot_weight_fmt_compare():
    print("[4] Weight format comparison (fixed input × scale) …")
    input_fmts = sorted(summary["input_fmt"].unique())
    scale_fmts = sorted(summary["scale_fmt"].unique())
    vec_strs   = [v for v in VEC_ORDER if v in summary["vec_str"].values]

    for inf in input_fmts:
        for scale in scale_fmts:
            sub = summary[(summary["input_fmt"] == inf) & (summary["scale_fmt"] == scale)]
            weight_fmts = sorted(sub["weight_fmt"].unique())
            if len(weight_fmts) < 2:
                continue

            fig, axes = plt.subplots(1, len(vec_strs), figsize=(4 * len(vec_strs), 4), sharey=False)
            fig.suptitle(f"Weight Format Comparison  |  Input={inf}  Scale={scale}", fontsize=10, fontweight="bold")

            for ax, vec in zip(axes, vec_strs):
                d = sub[sub["vec_str"] == vec].sort_values("weight_fmt")
                colors = [COLORS[i % len(COLORS)] for i in range(len(d))]
                bars = ax.bar(d["weight_fmt"], d["area_mm2"] * 1e3, color=colors, edgecolor="white")
                ax.set_title(f"{vec}", fontsize=9)
                ax.set_xlabel("Weight Format")
                ax.set_ylabel("Area (×10⁻³ mm²)" if ax == axes[0] else "")
                ax.tick_params(axis="x", rotation=30)
                ax.yaxis.grid(True, linestyle="--", alpha=0.5)
                ax.set_axisbelow(True)
                for bar in bars:
                    h = bar.get_height()
                    ax.text(bar.get_x() + bar.get_width() / 2, h * 1.01, f"{h:.1f}",
                            ha="center", va="bottom", fontsize=7)

            fig.tight_layout()
            savefig(fig, f"4_weight_fmt_i{inf}_s{scale}")


# ═══════════════════════════════════════════════════════════════════════════════
# Plot 5 ─ Fixed Input & Weight: compare scale formats
# ═══════════════════════════════════════════════════════════════════════════════
def plot_scale_fmt_compare():
    print("[5] Scale format comparison (fixed input × weight) …")
    input_fmts  = sorted(summary["input_fmt"].unique())
    weight_fmts = sorted(summary["weight_fmt"].unique())
    vec_strs    = [v for v in VEC_ORDER if v in summary["vec_str"].values]

    for inf in input_fmts:
        for wf in weight_fmts:
            sub = summary[(summary["input_fmt"] == inf) & (summary["weight_fmt"] == wf)]
            scale_fmts = sorted(sub["scale_fmt"].unique())
            if len(scale_fmts) < 2:
                continue

            fig, axes = plt.subplots(1, len(vec_strs), figsize=(4 * len(vec_strs), 4), sharey=False)
            fig.suptitle(f"Scale Format Comparison  |  Input={inf}  Weight={wf}", fontsize=10, fontweight="bold")

            for ax, vec in zip(axes, vec_strs):
                d = sub[sub["vec_str"] == vec].sort_values("scale_fmt")
                colors = [COLORS[i % len(COLORS)] for i in range(len(d))]
                bars = ax.bar(d["scale_fmt"], d["area_mm2"] * 1e3, color=colors, edgecolor="white")
                ax.set_title(f"{vec}", fontsize=9)
                ax.set_xlabel("Scale Format")
                ax.set_ylabel("Area (×10⁻³ mm²)" if ax == axes[0] else "")
                ax.tick_params(axis="x", rotation=30)
                ax.yaxis.grid(True, linestyle="--", alpha=0.5)
                ax.set_axisbelow(True)
                for bar in bars:
                    h = bar.get_height()
                    ax.text(bar.get_x() + bar.get_width() / 2, h * 1.01, f"{h:.1f}",
                            ha="center", va="bottom", fontsize=7)

            fig.tight_layout()
            savefig(fig, f"5_scale_fmt_i{inf}_w{wf}")


# ═══════════════════════════════════════════════════════════════════════════════
# Plot 6 ─ Heatmap: input_fmt × weight_fmt area grid (per scale & vec)
# ═══════════════════════════════════════════════════════════════════════════════
def plot_heatmap():
    print("[6] Heatmaps (input × weight per scale & vec) …")
    vec_strs   = [v for v in VEC_ORDER if v in summary["vec_str"].values]
    scale_fmts = sorted(summary["scale_fmt"].unique())
    input_fmts = sorted(summary["input_fmt"].unique())
    weight_fmts= sorted(summary["weight_fmt"].unique())

    for vec in vec_strs:
        sub = summary[summary["vec_str"] == vec]
        fig, axes = plt.subplots(1, len(scale_fmts), figsize=(4.5 * len(scale_fmts), 3.5))
        fig.suptitle(f"Area Heatmap (×10⁻³ mm²)  |  {vec}", fontsize=11, fontweight="bold")

        vmin = sub["area_mm2"].min() * 1e3
        vmax = sub["area_mm2"].max() * 1e3

        for ax, scale in zip(axes, scale_fmts):
            grid = np.full((len(input_fmts), len(weight_fmts)), np.nan)
            for i, inf in enumerate(input_fmts):
                for j, wf in enumerate(weight_fmts):
                    row = sub[(sub["input_fmt"] == inf) & (sub["weight_fmt"] == wf) & (sub["scale_fmt"] == scale)]
                    if not row.empty:
                        grid[i, j] = row["area_mm2"].values[0] * 1e3

            im = ax.imshow(grid, aspect="auto", cmap="YlOrRd", vmin=vmin, vmax=vmax)
            ax.set_xticks(range(len(weight_fmts)))
            ax.set_yticks(range(len(input_fmts)))
            ax.set_xticklabels(weight_fmts, rotation=40, ha="right", fontsize=7)
            ax.set_yticklabels(input_fmts, fontsize=7)
            ax.set_xlabel("Weight Format", fontsize=8)
            ax.set_ylabel("Input Format" if ax == axes[0] else "", fontsize=8)
            ax.set_title(f"Scale={scale}", fontsize=9)

            for i in range(len(input_fmts)):
                for j in range(len(weight_fmts)):
                    if not np.isnan(grid[i, j]):
                        ax.text(j, i, f"{grid[i,j]:.1f}", ha="center", va="center",
                                fontsize=6.5, color="black")

        fig.colorbar(im, ax=axes[-1], label="Area (×10⁻³ mm²)", shrink=0.8)
        fig.tight_layout()
        savefig(fig, f"6_heatmap_{vec}")


# ═══════════════════════════════════════════════════════════════════════════════
# Plot 7 ─ Area per Lane: normalised efficiency
# ═══════════════════════════════════════════════════════════════════════════════
def plot_area_per_lane():
    print("[7] Area per lane (normalised efficiency) …")
    scale_fmts = sorted(summary["scale_fmt"].unique())
    vec_strs   = [v for v in VEC_ORDER if v in summary["vec_str"].values]

    for scale in scale_fmts:
        sub = summary[(summary["scale_fmt"] == scale) & (summary["vec_size"] > 0)].copy()
        combos = sorted(sub["precision"].unique())

        fig, ax = plt.subplots(figsize=(8, 5))
        ax.set_title(f"Area per Lane vs Vector Size  |  Scale = {scale}", fontsize=11, fontweight="bold")

        for i, combo in enumerate(combos):
            d = sub[sub["precision"] == combo].sort_values("vec_size")
            ax.plot(d["vec_size"], d["area_um2_per_lane"], marker="s", label=combo,
                    color=COLORS[i % len(COLORS)], linewidth=1.5, markersize=5, linestyle="--")

        ax.set_xlabel("Vector Size (lanes)")
        ax.set_ylabel("Area per Lane (μm²/lane)")
        ax.set_xticks(sorted(summary["vec_size"].unique()))
        ax.legend(bbox_to_anchor=(1.01, 1), loc="upper left", fontsize=7)
        ax.yaxis.grid(True, linestyle="--", alpha=0.5)
        ax.set_axisbelow(True)
        fig.tight_layout()
        savefig(fig, f"7_area_per_lane_scale_{scale}")


# ═══════════════════════════════════════════════════════════════════════════════
# Plot 8 ─ Sub-module breakdown (stacked bar) for selected configs
# ═══════════════════════════════════════════════════════════════════════════════
def plot_submodule_breakdown():
    print("[8] Sub-module area breakdown …")
    # Pick one representative vec size
    for vec in ["vec16"]:
        sub_df = hier[hier["vec_str"] == vec].copy()
        # Build pivot: config × module
        pivot = sub_df.pivot_table(index="config", columns="module", values="area_um2",
                                   aggfunc="sum", fill_value=0)

        # Sort configs alphabetically
        pivot = pivot.sort_index()
        configs_labels = [c.replace(f"_{vec}", "") for c in pivot.index]

        fig, ax = plt.subplots(figsize=(max(12, len(pivot) * 0.4), 5))
        ax.set_title(f"Sub-module Area Breakdown  |  {vec}", fontsize=11, fontweight="bold")

        bottom = np.zeros(len(pivot))
        for j, col in enumerate(pivot.columns):
            vals = pivot[col].values / 1e3  # → ×10³ μm²
            ax.bar(range(len(pivot)), vals, bottom=bottom,
                   label=col, color=COLORS[j % len(COLORS)], edgecolor="none")
            bottom += vals

        ax.set_xticks(range(len(pivot)))
        ax.set_xticklabels(configs_labels, rotation=60, ha="right", fontsize=5.5)
        ax.set_ylabel("Area (×10³ μm²)")
        ax.legend(bbox_to_anchor=(1.01, 1), loc="upper left", fontsize=7)
        ax.yaxis.grid(True, linestyle="--", alpha=0.4)
        ax.set_axisbelow(True)
        fig.tight_layout()
        savefig(fig, f"8_submodule_breakdown_{vec}")


# ═══════════════════════════════════════════════════════════════════════════════
# Plot 9 ─ Overview: grouped bar per input format, all scale formats, one vec
# ═══════════════════════════════════════════════════════════════════════════════
def plot_overview_grouped():
    print("[9] Overview grouped bar …")
    scale_fmts  = sorted(summary["scale_fmt"].unique())
    weight_fmts = sorted(summary["weight_fmt"].unique())

    for vec in ["vec16"]:
        for inf in sorted(summary["input_fmt"].unique()):
            sub = summary[(summary["vec_str"] == vec) & (summary["input_fmt"] == inf)]
            if sub.empty:
                continue

            n_w = len(weight_fmts)
            n_s = len(scale_fmts)
            x   = np.arange(n_w)
            width = 0.8 / n_s

            fig, ax = plt.subplots(figsize=(10, 5))
            ax.set_title(f"Area by Weight & Scale Format  |  Input={inf}  {vec}", fontsize=11, fontweight="bold")

            for si, scale in enumerate(scale_fmts):
                areas = []
                for wf in weight_fmts:
                    row = sub[(sub["weight_fmt"] == wf) & (sub["scale_fmt"] == scale)]
                    areas.append(row["area_mm2"].values[0] * 1e3 if not row.empty else 0)
                offset = (si - n_s / 2 + 0.5) * width
                bars = ax.bar(x + offset, areas, width * 0.9, label=scale,
                              color=COLORS[si % len(COLORS)], edgecolor="white")

            ax.set_xticks(x)
            ax.set_xticklabels(weight_fmts, fontsize=9)
            ax.set_xlabel("Weight Format")
            ax.set_ylabel("Area (×10⁻³ mm²)")
            ax.legend(title="Scale Format", fontsize=8)
            ax.yaxis.grid(True, linestyle="--", alpha=0.5)
            ax.set_axisbelow(True)
            fig.tight_layout()
            savefig(fig, f"9_overview_i{inf}_{vec}")


# ── Main ──────────────────────────────────────────────────────────────────────
if __name__ == "__main__":
    print(f"Loaded {len(summary)} configs from summary CSV")
    print(f"Output → {OUTPUT_DIR}\n")

    plot_same_vecsize()
    plot_vecsize_scaling()
    plot_input_fmt_compare()
    plot_weight_fmt_compare()
    plot_scale_fmt_compare()
    plot_heatmap()
    plot_area_per_lane()
    plot_submodule_breakdown()
    plot_overview_grouped()

    print("\nDone.")
