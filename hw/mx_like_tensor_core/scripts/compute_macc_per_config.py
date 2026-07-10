#!/usr/bin/env python3
"""Compute per-(act, weight, scale) M_acc selection for the 126 requant configs.

Selection rule (mirrors plot_macc_sweep_band.select_M):
  - Smallest M_acc with max(eps_acc/eps_req across q_proj_l0 & gate_proj_l1) < 0.5
  - If none, smallest M with ratio < 1.0
  - Else max measured M

Coverage:
  - macc_sweep_all_{q_proj_l0,gate_proj_l1}_final.csv covers ~30 (act, weight, scale) tuples
  - Our target is 126 = 21 (act >= weight) × 6 scales

Fallback chain for configs missing from sweep:
  (1) Same (act, weight) but different scale  → use max M_acc across that pair's scales
  (2) Same (act, act) symmetric anchor       → use max M_acc across that anchor's scales
  (3) Per-activation default                  → conservative anchor

Output: macc_per_config.csv with columns: act, weight, scale, m_acc, source
"""
from __future__ import annotations
import pandas as pd
from pathlib import Path

SAFETY_MARGIN = 0.5

# Resolve CSV paths relative to this script's parent's `data/` folder so the
# script runs from any cwd inside the standalone tensor_core_gen package.
_DATA = Path(__file__).resolve().parent.parent / "data"

WORKLOADS = ["q_proj_l0", "gate_proj_l1"]
ALL = {wl: pd.read_csv(_DATA / f"macc_sweep_all_{wl}_final.csv") for wl in WORKLOADS}

ACT_ORDER = ["INT8", "E5M2", "E4M3", "E3M2", "E2M3", "E2M1"]
SCALE_ORDER = ["UE8M0", "UE7M1", "UE6M2", "UE5M3", "UE4M4", "UE4M3"]

# Strict precision rank (1 = highest)
RANK = {"INT8": 1, "E5M2": 2, "E4M3": 3, "E3M2": 4, "E2M3": 5, "E2M1": 6}

# Conservative per-act fallback (last-resort, never sweep-aligned).
DEFAULT_M_PER_ACT = {"INT8": 14, "E5M2": 11, "E4M3": 12, "E3M2": 11,
                     "E2M3": 12, "E2M1": 9}


def per_config_data(act: str, weight: str, scale: str, wl: str):
    df = ALL[wl]
    mask = ((df["typeA"] == act) & (df["typeB"] == weight) & (df["scaleType"] == scale))
    sub = df[mask]
    if sub.empty:
        return None, None
    floor = float(sub["floor_emp_median"].iloc[0])
    by_M = {int(r["M_acc"]): float(r["addErr_rel_median"]) for _, r in sub.iterrows()}
    return floor, by_M


def select_M_from_sweep(act: str, weight: str, scale: str) -> int | None:
    """Return M_acc per the safety-margin rule, or None if not in sweep."""
    fq, bq = per_config_data(act, weight, scale, "q_proj_l0")
    fg, bg = per_config_data(act, weight, scale, "gate_proj_l1")
    if fq is None or fg is None:
        return None
    common_Ms = set(bq) & set(bg)
    candidates = [M for M in common_Ms
                  if max(bq[M]/fq, bg[M]/fg) < SAFETY_MARGIN]
    if candidates:
        return min(candidates)
    fb = [M for M in common_Ms if max(bq[M]/fq, bg[M]/fg) < 1.0]
    if fb:
        return min(fb)
    return max(common_Ms) if common_Ms else None


def pick_m(act: str, weight: str, scale: str) -> tuple[int, str]:
    """Returns (m_acc, source) using the fallback chain."""
    # (1) exact sweep hit
    m = select_M_from_sweep(act, weight, scale)
    if m is not None:
        return m, "sweep"

    # (2) same (act, weight), different scale — use max across available scales
    cross_scale = [select_M_from_sweep(act, weight, s) for s in SCALE_ORDER]
    cross_scale = [m for m in cross_scale if m is not None]
    if cross_scale:
        return max(cross_scale), "scale_extrap"

    # (3) symmetric anchor (act, act, any scale) — use its max M_acc
    sym = [select_M_from_sweep(act, act, s) for s in SCALE_ORDER]
    sym = [m for m in sym if m is not None]
    if sym:
        return max(sym), "symmetric_anchor"

    # (4) last-resort act default
    return DEFAULT_M_PER_ACT[act], "act_default"


def main() -> None:
    rows = []
    for act in ACT_ORDER:
        for weight in ACT_ORDER:
            if RANK[act] > RANK[weight]:   # filter to 21 pairs (act >= weight precision)
                continue
            for scale in SCALE_ORDER:
                m, src = pick_m(act, weight, scale)
                rows.append({"act": act, "weight": weight, "scale": scale,
                             "m_acc": m, "source": src})

    df = pd.DataFrame(rows)
    out = _DATA / "macc_final_selection.csv"
    df.to_csv(out, index=False)

    print(f"Wrote {out}: {len(df)} configs")
    print()
    print("Source distribution:")
    print(df["source"].value_counts().to_string())
    print()
    print("M_acc distribution per source:")
    for src in df["source"].unique():
        sub = df[df["source"] == src]
        print(f"  {src:18s}  count={len(sub):3d}  "
              f"M_acc range [{sub['m_acc'].min()}, {sub['m_acc'].max()}]")
    print()
    print("First 30 rows:")
    print(df.head(30).to_string(index=False))


if __name__ == "__main__":
    main()
