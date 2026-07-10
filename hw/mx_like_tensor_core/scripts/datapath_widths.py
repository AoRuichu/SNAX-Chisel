#!/usr/bin/env python3
"""Parametric width model for the mx_like_tensor_core FDPU datapath.

Single-knob principle: M_target = M_acc + G_RNE is the universal precision
target, and every internal stage width is derived from it.  Any bit beyond
M_target is truncated by RNE at some downstream stage and therefore should
not be paid for in area / power.

Usage:
    python3 datapath_widths.py                     # dump the 6 key configs
    python3 datapath_widths.py --config E5M2 E5M2 UE4M4 --m-acc 10

Rows marked * are the ones that changed vs the current Chisel formulas.
"""

from __future__ import annotations
from dataclasses import dataclass
from math import ceil, log2

# ── Element / Scale format tables (mirror Parameter.scala) ─────────────────

@dataclass
class Elem:
    name: str
    e:    int    # exp bits (0 for INT8)
    m:    int    # mant bits
    impSc:int    # implicit scale exp (-6 for INT8)
    resvNaN: bool  # True if top-exp encoding reserved (E5M2 IEEE-like)

ELEMS = {
    "INT8": Elem("INT8", 0, 7, -6, False),   # 8-bit signed; mant "field" 7
    "E5M2": Elem("E5M2", 5, 2,  0, True),
    "E4M3": Elem("E4M3", 4, 3,  0, False),
    "E3M2": Elem("E3M2", 3, 2,  0, False),
    "E2M3": Elem("E2M3", 2, 3,  0, False),
    "E2M1": Elem("E2M1", 2, 1,  0, False),
}

@dataclass
class Scale:
    name: str
    e:    int
    m:    int

SCALES = {
    "UE8M0": Scale("UE8M0", 8, 0),
    "UE7M1": Scale("UE7M1", 7, 1),
    "UE6M2": Scale("UE6M2", 6, 2),
    "UE5M3": Scale("UE5M3", 5, 3),
    "UE4M4": Scale("UE4M4", 4, 4),
    "UE4M3": Scale("UE4M3", 4, 3),
}

# ── Design constants ──────────────────────────────────────────────────────

G_RNE = 3       # universal RNE guard (single site of truth)
V     = 4       # vector size


def bias(e: int) -> int:
    return (1 << (e - 1)) - 1 if e > 0 else 0

def max_exp(el: Elem) -> int:
    if el.e == 0:
        return el.impSc  # INT8: implicit -6
    reserved = 2 if el.resvNaN else 1
    return ((1 << el.e) - reserved) - bias(el.e) + el.impSc

def min_adj_exp(el: Elem) -> int:
    if el.e == 0:
        return el.impSc
    return (1 - bias(el.e)) + el.impSc

def sint_neg_bits(v: int) -> int:
    if v >= 0:
        return 1
    return v.bit_length() + 2  # abs(v) bit_length + sign + overflow

# ── Width derivation ───────────────────────────────────────────────────────

@dataclass
class Widths:
    label: str
    M_acc: int
    M_target: int         # = M_acc + G_RNE
    # S0 Operator
    M_op: int
    E_op: int
    productExpRange: int
    # S1 Tree
    absMagW: int
    treeExtra: int
    tree_out_mant: int
    # S2 ScaleComposition
    resScaleMantW: int    # 2 * (mS + 1)
    term_mant: int
    term_exp: int
    # S3 FusedScaleAccumulator
    fieldMantW: int
    G: int
    fieldW: int
    # S6 accreg
    fpNW: int

def derive(elA: str, elB: str, scT: str, M_acc: int) -> Widths:
    A, B, S = ELEMS[elA], ELEMS[elB], SCALES[scT]
    log2N = ceil(log2(V))
    M_target = M_acc + G_RNE

    # S0 Operator
    M_op = (A.m + 1) + (B.m + 1)
    minSumAdj = min_adj_exp(A) + min_adj_exp(B)
    E_op = max(max(A.e, B.e) + 2, sint_neg_bits(minSumAdj))
    productExpRange = (max_exp(A) + max_exp(B)) - minSumAdj

    # S1 Tree — capped alignment + unified widening lower bound
    absMagW = M_op + min(productExpRange, M_target) + log2N
    twoMs   = 2 * (S.m + 1)          # scale mantissa product width
    treeExtra = max(0, M_target - twoMs - M_op)
    tree_out_mant = M_op + treeExtra

    # S2 ScaleComposition (pure multiply, no LZC)
    resScaleMantW = twoMs
    term_mant = tree_out_mant + resScaleMantW           # = max(M_op+twoMs, M_target)
    term_exp  = max(E_op, S.e + 2) + 1

    # S3 FusedScaleAccumulator
    fieldMantW = max(term_mant, M_acc + 1)
    G = M_target - 1                                    # = M_acc + G_RNE - 1
    fieldW = fieldMantW + G

    # S6 accreg
    fpNW = 1 + 8 + M_acc

    return Widths(
        label=f"{elA}_{elB}_{scT}",
        M_acc=M_acc, M_target=M_target,
        M_op=M_op, E_op=E_op, productExpRange=productExpRange,
        absMagW=absMagW, treeExtra=treeExtra, tree_out_mant=tree_out_mant,
        resScaleMantW=resScaleMantW, term_mant=term_mant, term_exp=term_exp,
        fieldMantW=fieldMantW, G=G, fieldW=fieldW,
        fpNW=fpNW,
    )

# ── Reporting ─────────────────────────────────────────────────────────────

def pretty(w: Widths) -> str:
    return (f"{w.label:<20s}"
            f" M_acc={w.M_acc:2d} M_target={w.M_target:2d}"
            f" | S0: M_op={w.M_op:2d} E_op={w.E_op} pER={w.productExpRange:3d}"
            f" | S1: absMagW={w.absMagW:3d} tree_out={w.tree_out_mant:2d}"
            f" | S2: term={w.term_mant:2d}/{w.term_exp}"
            f" | S3: field={w.fieldW:3d}"
            f" | acc={w.fpNW:2d}")

KEY_CONFIGS = [
    ("E5M2", "E5M2", "UE6M2", 11),
    ("E2M1", "E2M1", "UE8M0",  8),
    ("E2M1", "E2M1", "UE5M3", 10),
    ("E2M1", "E2M1", "UE4M3",  9),
    ("INT8", "INT8", "UE8M0", 13),
    ("E2M3", "E2M3", "UE6M2", 12),
]

def main():
    import argparse
    ap = argparse.ArgumentParser()
    ap.add_argument("--config", nargs=3, metavar=("A","B","S"),
                    help="single config e.g. --config E5M2 E5M2 UE4M4")
    ap.add_argument("--m-acc", type=int, default=None)
    args = ap.parse_args()

    if args.config:
        A, B, S = args.config
        M = args.m_acc or 10
        print(pretty(derive(A, B, S, M)))
        return

    print("Canonical 6 key_configs under UNIFIED formula:")
    print("-" * 130)
    for A, B, S, M in KEY_CONFIGS:
        print(pretty(derive(A, B, S, M)))

if __name__ == "__main__":
    main()
