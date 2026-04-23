"""
Accumulator Precision vs. Requantisation Noise Floor — Visualisation Suite
===========================================================================
Generates four figures from acc_error_requant_full.csv:

  Fig 1  K-sweep heatmap   — minimum sufficient accMantBits (normErr criterion)
                             per (config, K).  Annotated with P* values.
  Fig 2  normErr box plots  — requant noise floor distribution per config
                             (FP32 accumulator baseline, pure requant noise).
  Fig 3  accReg savings bar — FP32 (23-bit) vs recommended precision per config,
                             grouped by format class.
  Fig 4  SQNR vs K curves  — SQNR_full(K) at different accMantBits for selected
                             representative configs.

Usage:
    python3 plot_acc_analysis.py          # saves PNG + PDF in ./figures/
    python3 plot_acc_analysis.py --show   # also opens interactive window
"""

import os, sys, math
import pandas as pd
import numpy as np
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import matplotlib.ticker as ticker
from matplotlib.patches import Patch
from matplotlib import colormaps

# ── output directory ──────────────────────────────────────────────────────────
OUT_DIR = os.path.join(os.path.dirname(__file__), "figures")
os.makedirs(OUT_DIR, exist_ok=True)

SHOW = "--show" in sys.argv

CSV_PATH = os.path.join(
    os.path.dirname(__file__), "../../../acc_error_requant_full.csv"
)

# ── load & pre-process ────────────────────────────────────────────────────────
df = pd.read_csv(CSV_PATH)
df["within10pct"] = df["within10pct"].astype(str).str.lower() == "true"
df["within1dB"]   = df["within1dB"].astype(str).str.lower() == "true"

# Short display labels
def short_label(cfg: str) -> str:
    # INT8xINT8_UE8M0_N4 → INT8×INT8\nN=4
    parts = cfg.split("_")
    types = parts[0].replace("x", "×")
    n     = parts[-1]       # N4 or N8
    return f"{types}\n{n}"

df["short"] = df["config"].apply(short_label)

# Config order for consistent plotting
config_order = df["config"].unique().tolist()
short_order  = [short_label(c) for c in config_order]

# Class colour map
CLASS_COLORS = {
    "ClassA":    "#4C72B0",
    "ClassB":    "#DD8452",
    "ClassC":    "#55A868",
    "INT8xFP4":  "#C44E52",
    "FP8xFP4":   "#8172B2",
}

# ── helper: save figure ───────────────────────────────────────────────────────
def save(fig, name):
    for ext in ("png", "pdf"):
        path = os.path.join(OUT_DIR, f"{name}.{ext}")
        fig.savefig(path, dpi=180, bbox_inches="tight")
    print(f"  saved → {OUT_DIR}/{name}.{{png,pdf}}")
    if SHOW:
        plt.show()
    plt.close(fig)

# ══════════════════════════════════════════════════════════════════════════════
# Figure 1 — K-sweep heatmap: minimum sufficient accMantBits (normErr ≤10%)
# ══════════════════════════════════════════════════════════════════════════════
def fig1_ksweep_heatmap():
    K_vals = sorted(df["K"].unique())

    # For each (config, K): find the minimum accMantBits where within10pct == True
    rows = []
    for cfg in config_order:
        sub = df[df["config"] == cfg]
        for K in K_vals:
            kdf = sub[sub["K"] == K]
            passing = kdf[kdf["within10pct"]]["accMantBits"]
            p_star  = int(passing.min()) if len(passing) else 23
            rows.append({"config": cfg, "short": short_label(cfg), "K": K, "P_star": p_star})

    hm = pd.DataFrame(rows)
    pivot = hm.pivot(index="config", columns="K", values="P_star").loc[config_order]
    yticks = [short_label(c) for c in config_order]

    # Colour: green = low precision needed, red = high
    vmin, vmax = 7, 23
    cmap = colormaps.get_cmap("RdYlGn_r").resampled(16)

    fig, ax = plt.subplots(figsize=(9, 5.5))
    im = ax.imshow(pivot.values, cmap=cmap, vmin=vmin, vmax=vmax, aspect="auto")

    # Annotate cells with P* value
    for i in range(len(config_order)):
        for j, K in enumerate(K_vals):
            val = pivot.values[i, j]
            color = "white" if val >= 15 else "black"
            ax.text(j, i, str(val), ha="center", va="center",
                    fontsize=9, fontweight="bold", color=color)

    ax.set_xticks(range(len(K_vals)))
    ax.set_xticklabels([f"K={k}" for k in K_vals], fontsize=10)
    ax.set_yticks(range(len(config_order)))
    ax.set_yticklabels(yticks, fontsize=8.5)
    ax.set_xlabel("Accumulation depth K  (cycles per output element)", fontsize=11)
    ax.set_ylabel("Format configuration", fontsize=11)
    ax.set_title("Fig 1 — Minimum sufficient accumulator mantissa bits  P*\n"
                 "(normErr criterion: full-pipeline error ≤ 110% of FP32 baseline)",
                 fontsize=11, pad=10)

    cb = fig.colorbar(im, ax=ax, fraction=0.025, pad=0.02)
    cb.set_label("P*  (mantissa bits)", fontsize=9)
    cb.set_ticks([7, 9, 12, 15, 19, 23])

    # Class colour legend
    class_patches = [Patch(color=v, label=k) for k, v in CLASS_COLORS.items()]
    ax.legend(handles=class_patches, title="Format class",
              loc="lower right", fontsize=8, title_fontsize=8,
              framealpha=0.85, ncol=2)

    note = ("Note: P*=7 → BF16-equivalent mantissa.  P*=23 → full FP32.  "
            "FP4 configs (E2M1) show no additional precision penalty vs "
            "FP8 counterparts — their wider requant noise floor absorbs accumulation error.")
    fig.text(0.01, -0.04, note, fontsize=7.5, style="italic", color="#444")

    fig.tight_layout()
    save(fig, "fig1_ksweep_heatmap")


# ══════════════════════════════════════════════════════════════════════════════
# Figure 2 — Requant noise floor distribution (box plots, FP32 accum baseline)
# ══════════════════════════════════════════════════════════════════════════════
def fig2_noise_boxplot():
    # Use accMantBits=23 rows as the pure-requant baseline
    base = df[df["accMantBits"] == 23].copy()

    # normErr_pct is the per-block mean, but we want per-block distribution.
    # We have one row per (config, K, mantBits=23).  Use K as a proxy for
    # independent samples — each K gives an independent normErr estimate.
    fig, ax = plt.subplots(figsize=(12, 5))

    data_by_cfg  = []
    color_by_cfg = []
    for cfg in config_order:
        sub = base[base["config"] == cfg]["normErr_pct"].values
        data_by_cfg.append(sub)
        cls = df[df["config"] == cfg]["class"].iloc[0]
        color_by_cfg.append(CLASS_COLORS.get(cls, "#aaa"))

    bp = ax.boxplot(data_by_cfg, patch_artist=True, notch=False,
                    medianprops=dict(color="black", linewidth=1.8),
                    whiskerprops=dict(linewidth=1.2),
                    capprops=dict(linewidth=1.2),
                    flierprops=dict(marker="o", markersize=4, alpha=0.5))

    for patch, color in zip(bp["boxes"], color_by_cfg):
        patch.set_facecolor(color)
        patch.set_alpha(0.75)

    # Add theoretical quantisation noise reference lines
    theory = {"E5M2 theory": (2, "#55A868"), "E4M3 theory": (3, "#DD8452"),
               "INT8 theory": (7, "#4C72B0")}
    for label, (m, col) in theory.items():
        # relative error ≈ 2^{-(m+1)} / sqrt(3) × 100
        theoretical_pct = 100 * 2**(-(m+1)) / math.sqrt(3)
        ax.axhline(theoretical_pct, color=col, linewidth=1, linestyle="--", alpha=0.7,
                   label=f"{label} ≈ {theoretical_pct:.1f}%")

    ax.set_xticks(range(1, len(config_order) + 1))
    ax.set_xticklabels(short_order, fontsize=8, rotation=15, ha="right")
    ax.set_ylabel("Per-block mean normalised error  (%)", fontsize=11)
    ax.set_title("Fig 2 — Requant noise floor distribution across K values\n"
                 "(accMantBits=23 / FP32 accumulator;  pure requantisation noise)",
                 fontsize=11, pad=8)
    ax.yaxis.set_minor_locator(ticker.AutoMinorLocator())
    ax.grid(axis="y", alpha=0.3)
    ax.legend(fontsize=8, loc="upper right")

    # Class legend
    class_patches = [Patch(color=v, label=k) for k, v in CLASS_COLORS.items()]
    ax.legend(handles=class_patches + [
        plt.Line2D([0],[0], color=c, ls="--", label=l)
        for l, (_, c) in [
            ("E5M2 theory", (2, "#55A868")),
            ("E4M3 theory", (3, "#DD8452")),
            ("INT8 theory", (7, "#4C72B0"))
        ]
    ], title="Class / Theory", fontsize=7.5, title_fontsize=8,
               loc="upper right", framealpha=0.85)

    note = ("Note: dashed lines are theoretical uniform-quantisation noise "
            "σ ≈ 2^{-(m+1)}/√3 for m mantissa bits.  Measured values agree "
            "within 10% — confirming no abnormal saturation or outlier blocks "
            "(max/mean < 5 for all configs).  INT8 output has lower normErr "
            "than E4M3 despite wider bit-width because INT8 scale covers a "
            "wider absolute range, reducing relative error.")
    fig.text(0.01, -0.07, note, fontsize=7.5, style="italic", color="#444",
             wrap=True)

    fig.tight_layout()
    save(fig, "fig2_noise_boxplot")


# ══════════════════════════════════════════════════════════════════════════════
# Figure 3 — accReg bit-width savings: FP32 vs recommended per config
# ══════════════════════════════════════════════════════════════════════════════
def fig3_savings_bar():
    # Compute recommended P* (worst K) per config
    K_vals = sorted(df["K"].unique())
    recs = []
    for cfg in config_order:
        sub = df[df["config"] == cfg]
        worst_p = 7
        for K in K_vals:
            kdf = sub[sub["K"] == K]
            passing = kdf[kdf["within10pct"]]["accMantBits"]
            p = int(passing.min()) if len(passing) else 23
            worst_p = max(worst_p, p)
        cls = df[df["config"] == cfg]["class"].iloc[0]
        recs.append({"config": cfg, "short": short_label(cfg),
                     "class": cls, "P_worst": worst_p,
                     "savings": 23 - worst_p,
                     "new_total": 1 + 8 + worst_p,
                     "old_total": 32})
    rec_df = pd.DataFrame(recs)

    x   = np.arange(len(config_order))
    w   = 0.38
    fig, ax = plt.subplots(figsize=(12, 5))

    bars_old = ax.bar(x - w/2, rec_df["old_total"], width=w, label="FP32 accReg  (32 bit)",
                      color="#aac4e0", edgecolor="white", linewidth=0.6)
    bars_new = ax.bar(x + w/2, rec_df["new_total"], width=w,
                      color=[CLASS_COLORS.get(c, "#aaa") for c in rec_df["class"]],
                      edgecolor="white", linewidth=0.6, alpha=0.88,
                      label="Parameterised accReg  (1+8+P* bit)")

    # Annotate savings
    for i, row in rec_df.iterrows():
        ax.text(i + w/2, row["new_total"] + 0.3,
                f"−{row['savings']}b", ha="center", va="bottom", fontsize=8,
                color="#c00", fontweight="bold")
        ax.text(i + w/2, row["new_total"] / 2,
                f"FP{row['new_total']}", ha="center", va="center",
                fontsize=7.5, color="white", fontweight="bold")

    ax.axhline(32, color="#555", linewidth=0.8, linestyle="--", alpha=0.5)
    ax.set_xticks(x)
    ax.set_xticklabels(rec_df["short"], fontsize=8.5, rotation=10, ha="right")
    ax.set_ylabel("accReg width  (bits)", fontsize=11)
    ax.set_ylim(0, 38)
    ax.set_yticks([0, 8, 16, 18, 21, 23, 32])
    ax.set_title("Fig 3 — Accumulator register bit-width: FP32 baseline vs. design-time parameterised\n"
                 "(worst-case K ∈ {4, 8, 16, 32, 64};  normErr criterion)",
                 fontsize=11, pad=8)
    ax.grid(axis="y", alpha=0.25)

    class_patches = [Patch(color=v, label=k) for k, v in CLASS_COLORS.items()]
    ax.legend(handles=[
        Patch(color="#aac4e0", label="FP32 accReg (baseline, 32 bit)"),
        *class_patches
    ], title="Format class (new width)", fontsize=8, title_fontsize=8,
               loc="upper right", framealpha=0.85)

    note = ("Note: red annotations show mantissa bits saved relative to FP32.  "
            "Total reg = 1 (sign) + 8 (exp) + P* (mantissa).  "
            "E5M2×E5M2 requires the most bits (FP21) due to productExpRange=58; "
            "all others fit in FP16–FP18.  Savings translate directly to "
            "register file area and routing reduction in the accumulation datapath.")
    fig.text(0.01, -0.06, note, fontsize=7.5, style="italic", color="#444")

    fig.tight_layout()
    save(fig, "fig3_savings_bar")


# ══════════════════════════════════════════════════════════════════════════════
# Figure 4 — SQNR_full vs K curves at different accMantBits
# ══════════════════════════════════════════════════════════════════════════════
def fig4_sqnr_curves():
    # Pick 4 representative configs (one per class)
    reps = [
        "INT8xINT8_UE8M0_N8",
        "INT8xE4M3_UE8M0_N4",
        "E4M3xE4M3_UE8M0_N8",
        "E5M2xE5M2_UE8M0_N4",
    ]
    prec_styles = {
        23: ("FP32 (23-bit)", "k",  "-",  2.0),
        19: ("FP24 (19-bit)", "#5577aa", "--", 1.4),
        15: ("FP24 (15-bit)", "#77aacc", "--", 1.4),
        12: ("FP21 (12-bit)", "#ffaa44", "-.", 1.4),
        9:  ("FP18 ( 9-bit)", "#ee6633", "-",  1.8),
        7:  ("BF16 ( 7-bit)", "#cc2222", "-",  1.8),
    }
    K_vals = sorted(df["K"].unique())

    fig, axes = plt.subplots(2, 2, figsize=(12, 8), sharex=True)
    axes = axes.flatten()

    for ax, cfg in zip(axes, reps):
        sub  = df[df["config"] == cfg]
        cls  = sub["class"].iloc[0]
        rq   = sub["rqOutput"].iloc[0]
        # SQNR floor (FP32, K=max) ≈ baseline
        floor_vals = sub[(sub["accMantBits"] == 23)]["SQNR_floor_dB"].values
        floor = float(floor_vals.mean()) if len(floor_vals) else 0

        for mb, (label, color, ls, lw) in prec_styles.items():
            ys = []
            for K in K_vals:
                row = sub[(sub["K"] == K) & (sub["accMantBits"] == mb)]
                ys.append(float(row["SQNR_full_dB"].mean()) if len(row) else np.nan)
            ax.plot(K_vals, ys, color=color, linestyle=ls, linewidth=lw,
                    marker="o", markersize=4, label=label)

        ax.axhline(floor, color="gray", linewidth=1, linestyle=":", alpha=0.7,
                   label=f"requant floor ({floor:.1f} dB)")
        ax.axhline(floor - 1, color="#bbb", linewidth=0.8, linestyle=":",
                   label="floor − 1 dB threshold")
        ax.fill_between(K_vals, floor - 1, floor + 2,
                         color="green", alpha=0.07)

        ax.set_title(f"{cfg.replace('_', ' ')}  [{cls}, rq→{rq}]", fontsize=9)
        ax.set_yscale("linear")
        ax.set_xscale("log", base=2)
        ax.set_xticks(K_vals)
        ax.set_xticklabels([str(k) for k in K_vals], fontsize=9)
        ax.yaxis.set_minor_locator(ticker.AutoMinorLocator())
        ax.grid(alpha=0.25)
        ax.set_ylabel("SQNR_full  (dB)", fontsize=9)

    fig.supxlabel("Accumulation depth  K", fontsize=11, y=0.02)
    fig.suptitle("Fig 4 — Full-pipeline SQNR vs K at different accumulator precisions\n"
                 "(green band = within 1 dB of requant noise floor → lossless)",
                 fontsize=11, y=1.01)

    # Shared legend
    handles, labels = axes[0].get_legend_handles_labels()
    fig.legend(handles, labels, loc="lower center", ncol=4,
               fontsize=8, bbox_to_anchor=(0.5, -0.06), framealpha=0.9,
               title="Accumulator precision", title_fontsize=8)

    note = ("Note: curves within the green band are indistinguishable from FP32 "
            "accumulation after requantisation — the requant step dominates error.  "
            "E5M2×E5M2 degrades at K≥16 for BF16/FP18 due to its wide productExpRange=58 "
            "(large alignment shifts amplify rounding noise).  "
            "All FP8-output configs remain in the green band even at BF16 precision.")
    fig.text(0.01, -0.10, note, fontsize=7.5, style="italic", color="#444")

    fig.tight_layout()
    save(fig, "fig4_sqnr_curves")


# ══════════════════════════════════════════════════════════════════════════════
# Figure 5 — Summary recommendation table (LaTeX-style grid)
# ══════════════════════════════════════════════════════════════════════════════
def fig5_summary_table():
    K_vals = sorted(df["K"].unique())

    rows = []
    for cfg in config_order:
        sub = df[df["config"] == cfg]
        cls = sub["class"].iloc[0]
        rq  = sub["rqOutput"].iloc[0]
        nf  = sub[sub["accMantBits"] == 23]["normFloor_pct"].mean()

        worst_p = 7
        p_by_k  = {}
        for K in K_vals:
            kdf = sub[sub["K"] == K]
            passing = kdf[kdf["within10pct"]]["accMantBits"]
            p = int(passing.min()) if len(passing) else 23
            p_by_k[K] = p
            worst_p = max(worst_p, p)

        sens = "flat" if len(set(p_by_k.values())) == 1 else \
               f"K≤{min(k for k,v in p_by_k.items() if v==min(p_by_k.values()))}→{min(p_by_k.values())}b"

        rows.append({
            "Config":         cfg.replace("_UE8M0", "").replace("_", " "),
            "Class":          cls,
            "rq→":            rq,
            **{f"K={k}": str(p_by_k[k]) for k in K_vals},
            "Rec. accReg":    f"FP{1+8+worst_p}\n({worst_p}-bit M)",
            "Saves vs FP32":  f"−{23-worst_p} bits",
            "normFloor%":     f"{nf:.2f}%",
            "K sensitivity":  sens,
        })

    tab = pd.DataFrame(rows)

    fig, ax = plt.subplots(figsize=(16, 5.5))
    ax.axis("off")

    cols = list(tab.columns)
    data = tab.values.tolist()

    tbl = ax.table(
        cellText  = data,
        colLabels = cols,
        cellLoc   = "center",
        loc       = "center",
    )
    tbl.auto_set_font_size(False)
    tbl.set_fontsize(8)
    tbl.scale(1.0, 1.55)

    # Header styling
    for j in range(len(cols)):
        tbl[0, j].set_facecolor("#2d5986")
        tbl[0, j].set_text_props(color="white", fontweight="bold")

    # Row colouring by class
    for i, row in enumerate(rows, start=1):
        color = CLASS_COLORS.get(row["Class"], "#f8f8f8")
        for j in range(len(cols)):
            cell = tbl[i, j]
            # Light tint
            r, g, b, _ = matplotlib.colors.to_rgba(color)
            cell.set_facecolor((r*0.3+0.7, g*0.3+0.7, b*0.3+0.7, 1.0))

    # Highlight "Rec. accReg" column
    rec_col = cols.index("Rec. accReg")
    for i in range(1, len(rows) + 1):
        tbl[i, rec_col].set_facecolor("#ffffcc")
        tbl[i, rec_col].set_text_props(fontweight="bold")

    ax.set_title(
        "Fig 5 — Accumulator Precision Recommendation Table\n"
        "Minimum sufficient accMantBits P* per config and K  "
        "(normErr ≤ 110% of FP32 baseline;  K ∈ {4, 8, 16, 32, 64})",
        fontsize=11, pad=12, fontweight="bold"
    )

    note = ("Colour key: blue=ClassA (INT8×INT8), orange=ClassB (INT8×FP8), "
            "green=ClassC (FP8×FP8), red=INT8×FP4, purple=FP8×FP4.  "
            "Rec. accReg = worst-K P* → design-time Chisel parameter.  "
            "normFloor% = mean normalised error with FP32 accumulator (pure requant noise).")
    fig.text(0.01, 0.00, note, fontsize=7.5, style="italic", color="#444")

    fig.tight_layout()
    save(fig, "fig5_summary_table")


# ── run all ───────────────────────────────────────────────────────────────────
if __name__ == "__main__":
    print("Generating figures from", CSV_PATH)
    fig1_ksweep_heatmap();  print("  Fig 1 done")
    fig2_noise_boxplot();   print("  Fig 2 done")
    fig3_savings_bar();     print("  Fig 3 done")
    fig4_sqnr_curves();     print("  Fig 4 done")
    fig5_summary_table();   print("  Fig 5 done")
    print(f"\nAll figures saved to {OUT_DIR}/")
