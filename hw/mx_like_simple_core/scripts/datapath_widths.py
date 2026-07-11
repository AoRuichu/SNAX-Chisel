#!/usr/bin/env python3
"""Per-config datapath width derivation for mx_like_simple_core.

Reference architecture: Lutz et al. ARITH 2024 "Fused FP8 4-Way Dot Product
With Scaling and FP32 Accumulation", Section II.B (Early Accumulation, EA).

Central formulas (Lutz + Cuyckens EA implementation, generalized to include INT8):

  Eq (5):        anchor       = max{Pexp} + log2(N) + 1
  Cuyckens:      SoP_field_W  = 1 + log2(N) + INT_BITS + BELOW_ANCHOR
  Cuyckens:      FIXED_SUM_W  = 1 + DST_SIG + (SoP_field_W - 1) + 1_buf

Unified derivation for FP and INT8:

  max_op_val_exp:
    FP:   ((2^e - reserved) - 1) - bias        # max normalized-form exp
    INT:  m + impSc                             # max int magnitude ~ 2^m
  min_op_val_exp:
    FP:   1 - bias - m                          # min subnormal representable
    INT:  impSc                                 # min non-zero int = 2^impSc

  P_max        = max_op_val_exp_A + max_op_val_exp_W + 1
  BELOW_ANCHOR = |min_op_val_exp_A + min_op_val_exp_W|
  INT_BITS     = P_max + 1
  SoP_field_W  = 1 + log2(N) + INT_BITS + BELOW_ANCHOR

Extensions over Lutz (not in paper):
  (i)  Per-config tailored widths (Lutz hardwires FP8 E5M3 worst-case).
  (ii) Fractional scale mant (UE7M1/UE6M2/UE4M4/etc): post-tree multiplier.
  (iii) BF16 accumulator (Lutz uses FP32).
  (iv) INT8 support with impSc convention (MX INT8 = -6).
"""

from __future__ import annotations
from dataclasses import dataclass
from math import ceil, log2


# ── Element / Scale format tables ──────────────────────────────

@dataclass
class Elem:
    name:    str
    e:       int          # exp bits (0 for INT8)
    m:       int          # mant frac bits (FP) or magnitude bits (INT)
    resvNaN: bool         # FP: top-exp reserved for NaN?
    impSc:   int = 0      # INT: implicit scale exp; unused for FP

    @property
    def isInt(self) -> bool: return self.e == 0
    @property
    def hasHiddenBit(self) -> bool: return not self.isInt
    @property
    def bias(self) -> int:
        return 0 if self.isInt else (1 << (self.e - 1)) - 1
    @property
    def maxOpValExp(self) -> int:
        if self.isInt:
            return self.m + self.impSc
        reserved = 2 if self.resvNaN else 1
        return ((1 << self.e) - reserved) - self.bias
    @property
    def minOpValExp(self) -> int:
        if self.isInt:
            return self.impSc
        return 1 - self.bias - self.m


ELEMS = {
    "E5M2": Elem("E5M2", 5, 2, resvNaN=True),
    "E4M3": Elem("E4M3", 4, 3, resvNaN=False),
    "E3M2": Elem("E3M2", 3, 2, resvNaN=False),
    "E2M3": Elem("E2M3", 2, 3, resvNaN=False),
    "E2M1": Elem("E2M1", 2, 1, resvNaN=False),
    "INT8": Elem("INT8", 0, 7, resvNaN=False, impSc=-6),
}


@dataclass
class Scale:
    name: str
    e:    int
    m:    int
    @property
    def bias(self) -> int: return (1 << (self.e - 1)) - 1


SCALES = {
    "UE8M0": Scale("UE8M0", 8, 0),
    "UE7M1": Scale("UE7M1", 7, 1),
    "UE6M2": Scale("UE6M2", 6, 2),
    "UE5M3": Scale("UE5M3", 5, 3),
    "UE4M4": Scale("UE4M4", 4, 4),
    "UE4M3": Scale("UE4M3", 4, 3),
}


# ── Datapath width derivation ─────────────────────────────────

@dataclass
class Widths:
    label: str
    prodMantW: int
    pMax: int
    pMinVal: int
    intBits: int
    belowAnchor: int
    aboveAnchor: int
    sopFieldW: int
    sopShift: int
    scaleMantProdW: int
    scaledTermW: int
    finalAdderW: int


def derive(elA: str, elW: str, scT: str, *, N: int = 4,
           acc_sig_W: int = 8) -> Widths:
    A, W, S = ELEMS[elA], ELEMS[elW], SCALES[scT]
    log2N = ceil(log2(max(N, 2)))

    prodMantW = (A.m + 1) + (W.m + 1)
    pMax     = A.maxOpValExp + W.maxOpValExp + 1
    pMinVal  = A.minOpValExp + W.minOpValExp

    aboveAnchor = pMax + log2N + 1
    belowAnchor = abs(pMinVal)
    intBits     = pMax + 1
    sopFieldW   = 1 + log2N + intBits + belowAnchor
    sopShift    = belowAnchor

    scaleMantProdW = 0 if S.m == 0 else 2 * (S.m + 1)
    scaledTermW    = sopFieldW if scaleMantProdW == 0 else sopFieldW + scaleMantProdW

    # Lutz FIXED_SUM_WIDTH: 1 sign + acc_sig + scaled_term body
    finalAdderW = 1 + acc_sig_W + scaledTermW

    return Widths(
        label=f"{elA}/{elW}/{scT}",
        prodMantW=prodMantW,
        pMax=pMax, pMinVal=pMinVal,
        intBits=intBits, belowAnchor=belowAnchor, aboveAnchor=aboveAnchor,
        sopFieldW=sopFieldW, sopShift=sopShift,
        scaleMantProdW=scaleMantProdW, scaledTermW=scaledTermW,
        finalAdderW=finalAdderW,
    )


# ── Sanity check: reproduce Lutz FP8 E5M3 numbers ──────────────

def sanity_check_lutz():
    """Verify our formulas reproduce Lutz E5M3 (paper Eq 5, Cuyckens 69/94)."""
    e5m3 = Elem("E5M3", 5, 3, resvNaN=True)
    ELEMS["E5M3"] = e5m3
    try:
        # Use FP32 accreg width (24) to match Lutz's paper
        r = derive("E5M3", "E5M3", "UE8M0", N=4, acc_sig_W=24)
    finally:
        ELEMS.pop("E5M3")

    checks = [
        ("pMax",         r.pMax,           31),
        ("|pMinVal|",    abs(r.pMinVal),   34),   # was 28+6 in old formula
        ("aboveAnchor",  r.aboveAnchor,    34),   # Lutz Eq 5
        ("belowAnchor",  r.belowAnchor,    34),   # coincidence for E5M3
        ("intBits",      r.intBits,        32),
        ("sopFieldW",    r.sopFieldW,      69),
        # Lutz 94 = 1 + 24 + 1 + (69 - 1); our formula: 1 + 24 + 69 = 94 too
        # (we absorb the "+1 buf" into the "SoP body includes its sign bit" accounting)
        ("finalAdderW",  r.finalAdderW,    94),
    ]
    print("Sanity check vs Lutz FP8 E5M3 + FP32 accumulator:")
    ok = True
    for name, got, want in checks:
        status = "OK" if got == want else "FAIL"
        if got != want: ok = False
        print(f"  {name:<16} got={got:>3}  want={want:>3}  [{status}]")
    print(f"  Overall: {'ALL PASS' if ok else 'MISMATCH'}\n")


# ── 126-config sweep table ─────────────────────────────────────

ELEM_TYPES = ["E5M2", "E4M3", "E3M2", "E2M3", "E2M1", "INT8"]
SCALES_ALL = ["UE8M0", "UE7M1", "UE6M2", "UE5M3", "UE4M4", "UE4M3"]

def all_126_configs():
    """21 upper-triangle elem pairs (symmetric + one-way asymm) x 6 scales."""
    pairs = []
    for i, a in enumerate(ELEM_TYPES):
        for w in ELEM_TYPES[i:]:  # upper triangle: A index <= W index
            pairs.append((a, w))
    return [(a, w, s) for (a, w) in pairs for s in SCALES_ALL]


def main():
    sanity_check_lutz()

    configs = all_126_configs()
    print(f"Enumerating {len(configs)} configs "
          f"({len(ELEM_TYPES)} elem, upper triangle = "
          f"{len(ELEM_TYPES)*(len(ELEM_TYPES)+1)//2} pairs x "
          f"{len(SCALES_ALL)} scales)")

    print("=" * 100)
    print("Per-config datapath widths for mx_like_simple_core (BF16 accreg)")
    print("=" * 100)
    hdr = (f"{'Config':<24}  {'Pmax':>4}  {'BLW':>4}  {'INT':>4}  "
           f"{'SoP':>4}  {'sMp':>4}  {'term':>5}  {'FinAdd':>7}")
    print(hdr)
    print("-" * len(hdr))
    last_pair = None
    for a, w, s in configs:
        pair = (a, w)
        if pair != last_pair:
            if last_pair is not None: print()
            last_pair = pair
        r = derive(a, w, s)
        print(f"{r.label:<24}  "
              f"{r.pMax:>4}  {r.belowAnchor:>4}  {r.intBits:>4}  "
              f"{r.sopFieldW:>4}  {r.scaleMantProdW:>4}  "
              f"{r.scaledTermW:>5}  {r.finalAdderW:>7}")


if __name__ == "__main__":
    main()
