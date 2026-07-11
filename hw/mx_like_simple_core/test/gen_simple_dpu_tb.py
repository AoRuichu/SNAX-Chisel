#!/usr/bin/env python3
"""Generate a self-contained SystemVerilog testbench for one SimpleDPU config.

Reads `<pedir>/simple_dpu_meta.txt` (produced by MetaDump on emit), builds a
tb that:
  1. drives the async active-low reset (0 -> 1 to release)
  2. asserts clearAcc for one cycle
  3. drives K cycles of pseudo-random stimulus
  4. dumps a VCD named tb_SimpleDPU_<tag>.vcd

The stimulus is deterministic (fixed seed) so different runs produce identical
VCDs, which is what synthesis power tools need for repeatable results.

Numerical correctness is covered by SimpleDPUNumericSpec (Chisel); this tb
exists purely to generate switching activity for power estimation.

Usage:
    python3 test/gen_simple_dpu_tb.py <pedir>
"""

from __future__ import annotations
import os
import random
import sys

K_CYCLES = 64        # per-config stimulus length
SEED     = 0xC0FFEE  # stable across runs


def parse_meta(meta_path: str) -> dict:
    d: dict[str, str] = {}
    with open(meta_path) as f:
        for line in f:
            line = line.strip()
            if not line or "=" not in line: continue
            k, v = line.split("=", 1)
            d[k.strip()] = v.strip()
    return d


def rand_elem(rng: random.Random, e_bits: int, m_bits: int,
              is_int: bool, resv_nan: bool) -> tuple[int, int, int]:
    """Return (sign, biased_exp, mant) in valid range."""
    sign = rng.randint(0, 1)
    if is_int:
        mant = rng.randint(0, (1 << m_bits) - 1)
        return sign, 0, mant
    max_biased = (1 << e_bits) - (2 if resv_nan else 1)
    biased = 1 + rng.randint(0, max_biased - 1)
    mant   = rng.randint(0, (1 << m_bits) - 1)
    return sign, biased, mant


def rand_scale(rng: random.Random, e_bits: int, m_bits: int, bias: int) -> tuple[int, int]:
    """Return (biased_exp, mant) near the middle of the scale range."""
    biased = max(1, bias + rng.randint(-1, 1))
    mant   = 0 if m_bits == 0 else rng.randint(0, (1 << m_bits) - 1)
    return biased, mant


def gen_tb(pedir: str) -> str:
    meta_path = os.path.join(pedir, "simple_dpu_meta.txt")
    meta = parse_meta(meta_path)

    tag       = meta["config"]
    top       = meta["topModule"]
    N         = int(meta["N"])
    A_e       = int(meta["elAe"])
    A_m       = int(meta["elAm"])
    A_isInt   = meta["elAisInt"].lower() == "true"
    A_resv    = meta["elA"] == "E5M2"                 # only E5M2 reserves NaN
    W_e       = int(meta["elWe"])
    W_m       = int(meta["elWm"])
    W_isInt   = meta["elWisInt"].lower() == "true"
    W_resv    = meta["elW"] == "E5M2"
    S_e       = int(meta["scaleE"])
    S_m       = int(meta["scaleM"])
    S_bias    = int(meta["scaleBias"])
    bf16_e    = int(meta["BF16_expBits"])
    bf16_m    = int(meta["BF16_mantBits"])

    rng = random.Random(SEED)

    def wire(width: int) -> str: return "" if width <= 1 else f"[{width-1}:0] "

    # Header + port
    lines: list[str] = []
    lines.append(f"// Auto-generated tb for {tag} — power/vcd only, no self-check.")
    lines.append(f"// Generated with SEED=0x{SEED:X}, K_CYCLES={K_CYCLES}")
    lines.append("`timescale 1ns/1ps")
    lines.append("module tb_SimpleDPU;")
    lines.append("  logic clock = 1'b0;")
    lines.append("  logic reset = 1'b0;")
    lines.append("  logic io_enable, io_clearAcc;")
    for i in range(N):
        lines.append(f"  logic io_a_{i}_sign;")
        if A_e > 0: lines.append(f"  logic {wire(A_e)}io_a_{i}_exp;")
        if A_m > 0: lines.append(f"  logic {wire(A_m)}io_a_{i}_mant;")
        lines.append(f"  logic io_w_{i}_sign;")
        if W_e > 0: lines.append(f"  logic {wire(W_e)}io_w_{i}_exp;")
        if W_m > 0: lines.append(f"  logic {wire(W_m)}io_w_{i}_mant;")
    lines.append(f"  logic {wire(S_e)}io_scaleA_exp;")
    if S_m > 0: lines.append(f"  logic {wire(S_m)}io_scaleA_mant;")
    lines.append(f"  logic {wire(S_e)}io_scaleW_exp;")
    if S_m > 0: lines.append(f"  logic {wire(S_m)}io_scaleW_mant;")
    lines.append("  logic io_accOut_sign;")
    lines.append(f"  logic {wire(bf16_e)}io_accOut_exp;")
    lines.append(f"  logic {wire(bf16_m)}io_accOut_mant;")

    # DUT instantiation
    lines.append("")
    lines.append(f"  {top} dut (")
    lines.append("    .clock(clock), .reset(reset),")
    lines.append("    .io_enable(io_enable), .io_clearAcc(io_clearAcc),")
    for i in range(N):
        f = [f".io_a_{i}_sign(io_a_{i}_sign)"]
        if A_e > 0: f.append(f".io_a_{i}_exp(io_a_{i}_exp)")
        if A_m > 0: f.append(f".io_a_{i}_mant(io_a_{i}_mant)")
        lines.append(f"    " + ", ".join(f) + ",")
    for i in range(N):
        f = [f".io_w_{i}_sign(io_w_{i}_sign)"]
        if W_e > 0: f.append(f".io_w_{i}_exp(io_w_{i}_exp)")
        if W_m > 0: f.append(f".io_w_{i}_mant(io_w_{i}_mant)")
        lines.append(f"    " + ", ".join(f) + ",")
    f = [".io_scaleA_exp(io_scaleA_exp)"]
    if S_m > 0: f.append(".io_scaleA_mant(io_scaleA_mant)")
    lines.append("    " + ", ".join(f) + ",")
    f = [".io_scaleW_exp(io_scaleW_exp)"]
    if S_m > 0: f.append(".io_scaleW_mant(io_scaleW_mant)")
    lines.append("    " + ", ".join(f) + ",")
    lines.append("    .io_accOut_sign(io_accOut_sign),")
    lines.append("    .io_accOut_exp(io_accOut_exp),")
    lines.append("    .io_accOut_mant(io_accOut_mant)")
    lines.append("  );")

    # Clock
    lines.append("")
    lines.append("  always #1 clock = ~clock;")

    # Stimulus loops
    lines.append("")
    lines.append("  initial begin")
    lines.append(f"    $dumpfile(\"tb_SimpleDPU_{tag}.vcd\");")
    lines.append("    $dumpvars(0, tb_SimpleDPU);")
    lines.append("    // reset = 1 -> async-assert (clear regs), 0 -> release")
    lines.append("    reset = 1'b1;")
    lines.append("    io_enable = 1'b0; io_clearAcc = 1'b0;")
    # Default all inputs to 0
    for i in range(N):
        lines.append(f"    io_a_{i}_sign = 0;")
        if A_e > 0: lines.append(f"    io_a_{i}_exp = 0;")
        if A_m > 0: lines.append(f"    io_a_{i}_mant = 0;")
        lines.append(f"    io_w_{i}_sign = 0;")
        if W_e > 0: lines.append(f"    io_w_{i}_exp = 0;")
        if W_m > 0: lines.append(f"    io_w_{i}_mant = 0;")
    lines.append("    io_scaleA_exp = 0;")
    if S_m > 0: lines.append("    io_scaleA_mant = 0;")
    lines.append("    io_scaleW_exp = 0;")
    if S_m > 0: lines.append("    io_scaleW_mant = 0;")
    lines.append("    #4 reset = 1'b0;")            # release reset
    lines.append("    #2 io_clearAcc = 1'b1;")      # clear accreg
    lines.append("    #2 io_clearAcc = 1'b0; io_enable = 1'b1;")

    # K cycles of deterministic stimulus
    for _ in range(K_CYCLES):
        for i in range(N):
            s, e, m = rand_elem(rng, A_e, A_m, A_isInt, A_resv)
            lines.append(f"    io_a_{i}_sign = {s};")
            if A_e > 0: lines.append(f"    io_a_{i}_exp = {A_e}'d{e};")
            if A_m > 0: lines.append(f"    io_a_{i}_mant = {A_m}'d{m};")
            s, e, m = rand_elem(rng, W_e, W_m, W_isInt, W_resv)
            lines.append(f"    io_w_{i}_sign = {s};")
            if W_e > 0: lines.append(f"    io_w_{i}_exp = {W_e}'d{e};")
            if W_m > 0: lines.append(f"    io_w_{i}_mant = {W_m}'d{m};")
        sa_e, sa_m = rand_scale(rng, S_e, S_m, S_bias)
        sw_e, sw_m = rand_scale(rng, S_e, S_m, S_bias)
        lines.append(f"    io_scaleA_exp = {S_e}'d{sa_e};")
        if S_m > 0: lines.append(f"    io_scaleA_mant = {S_m}'d{sa_m};")
        lines.append(f"    io_scaleW_exp = {S_e}'d{sw_e};")
        if S_m > 0: lines.append(f"    io_scaleW_mant = {S_m}'d{sw_m};")
        lines.append("    #2;")

    lines.append("    #4 $finish;")
    lines.append("  end")
    lines.append("endmodule")
    return "\n".join(lines) + "\n"


def main():
    if len(sys.argv) < 2:
        print("Usage: gen_simple_dpu_tb.py <pedir>", file=sys.stderr)
        sys.exit(1)
    pedir = sys.argv[1]
    tb    = gen_tb(pedir)
    outpath = os.path.join(pedir, "tb_SimpleDPU.sv")
    with open(outpath, "w") as f: f.write(tb)
    print(f"Wrote {outpath} ({tb.count(chr(10))} lines)")


if __name__ == "__main__":
    main()
