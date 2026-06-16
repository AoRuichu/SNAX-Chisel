#!/usr/bin/env python3
"""Replace synthetic random stim with real-workload trace for the 6 key configs.

For each generated/key_configs/<label>/ this script regenerates:

  PE_Array stim (Task C TB):
    stim_pe_array_op_a.hex      ← MX-quantised activation rows (per cycle, 4 row ports)
    stim_pe_array_op_b.hex      ← MX-quantised weight cols    (per cycle, 16 col ports)
    stim_pe_array_exp_a.hex     ← row shared scales (8b each, held within block)
    stim_pe_array_exp_b.hex     ← col shared scales

  Requant standalone stim (Task B TB, integrated requant inside PE_Array uses
                           the live PE accumulator output — no separate stim
                           file needed for that path):
    stim_requant.hex            ← FP narrow accumulator output (1+8+M_acc bits)
                                  per element, 64 elements per cycle

Workload: pure Gaussian matching the measured q_proj_l0 stats from Qwen2.5-3B
(σ_act=0.372, σ_w=0.0366) — the same workload used by the M_acc accuracy
sweep, so the activity factor at synthesis matches what the algo signed off on.

Run:  python3 gen_real_workload_stim.py
"""
from __future__ import annotations
import importlib.util
import re
import struct
import sys
from pathlib import Path

import numpy as np

# ── Load quantize_mx_v6 from quantize.py ─────────────────────────────────
_spec = importlib.util.spec_from_file_location("quantize", "quantize.py")
_q   = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_q)
quantize_mx_v6 = _q.quantize_mx_v6

# ── Geometry (matches Chisel emit) ───────────────────────────────────────
ROOT = Path("generated/key_configs")
TILE_ROWS  = 4
TILE_COLS  = 16
VEC        = 4
BLOCK_SIZE = 16
CPB        = BLOCK_SIZE // VEC          # 4 cycles per block
N_VECTORS  = 256                         # matches gen_pe_array_tb.py / gen_requant_tb.py
SEED       = 0xBEEF1234

# Workload — q_proj_l0 layer of Qwen2.5-3B (clean attention proj).
SIGMA_ACT = 0.372
SIGMA_W   = 0.0366

# Element format → quantize_mx_v6 dtype string
ELEM_TO_DTYPE = {
    "INT8": "mxint8",
    "E5M2": "fp8_e5m2",
    "E4M3": "fp8_e4m3",
    "E3M2": "fp6_e3m2",
    "E2M3": "fp6_e2m3",
    "E2M1": "fp4_e2m1",
}

# Element bit width (raw on-wire) — VEC * BITS packs into one io_op_X port.
ELEM_BITS = {
    "INT8": 8, "E5M2": 8, "E4M3": 8,
    "E3M2": 6, "E2M3": 6,
    "E2M1": 4,
}

LABEL_RE = re.compile(r"^([A-Z0-9]+)_([A-Z0-9]+)_(UE[0-9]M[0-9])_M(\d+)$")


def parse_label(label: str) -> tuple[str, str, str, int]:
    m = LABEL_RE.match(label)
    if not m:
        raise ValueError(f"Cannot parse label: {label}")
    return m.group(1), m.group(2), m.group(3), int(m.group(4))


def pack_vec(raw_row: np.ndarray, bits: int) -> int:
    """Pack VEC consecutive elements into a single integer, MSB = element 0."""
    val = 0
    mask = (1 << bits) - 1
    for k in range(VEC):
        val |= (int(raw_row[k]) & mask) << ((VEC - 1 - k) * bits)
    return val


def pack_ports(values: list[int], port_w: int) -> int:
    """Pack a list of n_ports per-port values into one wide integer (MSB = port 0)."""
    val = 0
    for p, v in enumerate(values):
        val |= (v & ((1 << port_w) - 1)) << ((len(values) - 1 - p) * port_w)
    return val


def fp32_to_narrow(fp32_arr: np.ndarray, m_acc: int) -> np.ndarray:
    """Truncate FP32 mantissa to m_acc bits (RNE-style round half to even).

    Returns uint32 array of (1+8+m_acc)-bit values, right-justified in the LSBs.
    Sign and (8-bit biased) exponent kept verbatim.  Subnormals/infs/NaNs
    pass through with their representable forms in the truncated mantissa
    space (no special-case re-encoding — the synth array doesn't either).
    """
    bits = fp32_arr.view(np.uint32).astype(np.uint64)
    sign = (bits >> 31) & 0x1
    exp  = (bits >> 23) & 0xFF
    mant_full = bits & ((1 << 23) - 1)
    drop = 23 - m_acc
    if drop > 0:
        # RNE: take top m_acc bits, then round half-to-even using the dropped bits.
        kept = mant_full >> drop
        # round bit = MSB of the dropped portion
        round_bit = (mant_full >> (drop - 1)) & 0x1
        # sticky = OR of bits below the round bit
        sticky = (mant_full & ((1 << (drop - 1)) - 1)) != 0
        # half_to_even: round up if round_bit=1 AND (sticky OR LSB of kept)
        round_up = round_bit & (sticky | (kept & 0x1))
        kept = kept + round_up
        # mantissa overflow → exp++, mant=0
        overflow = (kept >> m_acc) & 0x1
        kept = kept & ((1 << m_acc) - 1)
        exp = exp + overflow
        # saturate to Inf (exp=0xFF, mant=0) if exp overflows
        exp = np.minimum(exp, 0xFF)
    else:
        kept = mant_full << (-drop)
    out = (sign << (1 + 8 + m_acc - 1)) | (exp << m_acc) | kept
    return out.astype(np.uint64)


def generate_workload(rng: np.random.Generator, k_total: int) -> tuple[np.ndarray, np.ndarray]:
    """Return (act_rows, weight_cols) shaped (TILE_ROWS, K) and (TILE_COLS, K)."""
    act = rng.normal(0.0, SIGMA_ACT, (TILE_ROWS, k_total)).astype(np.float32)
    wt  = rng.normal(0.0, SIGMA_W,   (TILE_COLS, k_total)).astype(np.float32)
    return act, wt


def emit_pe_array_stim(subdir: Path, act_raw: np.ndarray, wt_raw: np.ndarray,
                       sa_raw: np.ndarray, sb_raw: np.ndarray,
                       elem_bits_a: int, elem_bits_b: int) -> None:
    """Emit the 4 PE_Array stim files for N_VECTORS cycles."""
    port_a_w = VEC * elem_bits_a
    port_b_w = VEC * elem_bits_b
    op_a_total_w = TILE_ROWS * port_a_w
    op_b_total_w = TILE_COLS * port_b_w
    exp_a_total_w = TILE_ROWS * 8
    exp_b_total_w = TILE_COLS * 8

    op_a_lines, op_b_lines, exp_a_lines, exp_b_lines = [], [], [], []
    for cyc in range(N_VECTORS):
        block_idx = cyc // CPB
        within    = cyc % CPB
        k_start   = block_idx * BLOCK_SIZE + within * VEC

        # Pack row act ports
        row_vals = [pack_vec(act_raw[r, k_start:k_start+VEC], elem_bits_a)
                    for r in range(TILE_ROWS)]
        op_a_lines.append(pack_ports(row_vals, port_a_w))
        # Pack col weight ports
        col_vals = [pack_vec(wt_raw[c, k_start:k_start+VEC], elem_bits_b)
                    for c in range(TILE_COLS)]
        op_b_lines.append(pack_ports(col_vals, port_b_w))
        # Scales (held within block)
        exp_a_lines.append(pack_ports([int(sa_raw[r, block_idx]) for r in range(TILE_ROWS)], 8))
        exp_b_lines.append(pack_ports([int(sb_raw[c, block_idx]) for c in range(TILE_COLS)], 8))

    def dump(path: Path, lines: list[int], width_bits: int):
        hex_chars = (width_bits + 3) // 4
        with path.open("w") as f:
            for v in lines:
                f.write(f"{v:0{hex_chars}x}\n")

    dump(subdir / "stim_pe_array_op_a.hex",  op_a_lines,  op_a_total_w)
    dump(subdir / "stim_pe_array_op_b.hex",  op_b_lines,  op_b_total_w)
    dump(subdir / "stim_pe_array_exp_a.hex", exp_a_lines, exp_a_total_w)
    dump(subdir / "stim_pe_array_exp_b.hex", exp_b_lines, exp_b_total_w)


def compute_requant_stim(act_fp32_deq: np.ndarray, wt_fp32_deq: np.ndarray,
                         m_acc: int) -> np.ndarray:
    """For each tile-block, compute FP32 accumulator outputs (4 rows × 16 cols)
    and truncate to (1+8+m_acc) narrow FP.

    Returns uint64 array shape (n_blocks, 64) where each row is the 64 narrow-FP
    values for one tile-block dump (row-major: row 0 cols 0..15, row 1 cols 0..15, ...).
    """
    k_total = act_fp32_deq.shape[1]
    n_blocks = k_total // BLOCK_SIZE
    n_blocks = min(n_blocks, N_VECTORS)
    out = np.zeros((n_blocks, TILE_ROWS * TILE_COLS), dtype=np.uint64)

    for b in range(n_blocks):
        k0, k1 = b * BLOCK_SIZE, (b + 1) * BLOCK_SIZE
        # FP32 accumulator: 4×16 matrix, sum over k = 0..15
        # act_fp32_deq[r, k0:k1] @ wt_fp32_deq[:, k0:k1].T → (4, 16)
        acc_fp32 = act_fp32_deq[:, k0:k1] @ wt_fp32_deq[:, k0:k1].T   # (4,16)
        narrow   = fp32_to_narrow(acc_fp32.astype(np.float32).flatten(), m_acc)
        out[b] = narrow
    return out


def emit_requant_stim(subdir: Path, narrow_vecs: np.ndarray, m_acc: int) -> None:
    """Dump narrow-FP accumulator values to stim_requant.hex (64 elements/line)."""
    in_w = 1 + 8 + m_acc
    n_elems = TILE_ROWS * TILE_COLS    # = 64
    total_w = in_w * n_elems
    hex_chars = (total_w + 3) // 4

    n_blocks = narrow_vecs.shape[0]
    # Pad to N_VECTORS by repeating if we have fewer blocks
    if n_blocks < N_VECTORS:
        reps = (N_VECTORS + n_blocks - 1) // n_blocks
        narrow_vecs = np.tile(narrow_vecs, (reps, 1))[:N_VECTORS]
    else:
        narrow_vecs = narrow_vecs[:N_VECTORS]

    with (subdir / "stim_requant.hex").open("w") as f:
        for line_idx in range(N_VECTORS):
            packed = 0
            for e in range(n_elems):
                packed |= (int(narrow_vecs[line_idx, e]) & ((1 << in_w) - 1)) \
                          << ((n_elems - 1 - e) * in_w)
            f.write(f"{packed:0{hex_chars}x}\n")


def process_one(subdir: Path) -> None:
    label = subdir.name
    act, wt, scale, m_acc = parse_label(label)

    # Need K large enough to fill N_VECTORS for both PE_Array (need
    # N_VECTORS * VEC samples per row) AND requant (need N_VECTORS blocks).
    # Use the larger: N_VECTORS * BLOCK_SIZE = 4096 K-samples.
    k_total = N_VECTORS * BLOCK_SIZE
    rng = np.random.default_rng((SEED ^ hash(label)) & 0xFFFFFFFF)
    act_fp32, wt_fp32 = generate_workload(rng, k_total)

    # MX-quantise per (dtype, scale) — the requant-input value lives in the
    # FP accumulator and depends on these quantised values.
    act_deq, _, sa_raw, act_raw = quantize_mx_v6(
        act_fp32, dtype=ELEM_TO_DTYPE[act],
        block_size=BLOCK_SIZE, axis=1, scale_format=scale)
    wt_deq, _, sb_raw, wt_raw = quantize_mx_v6(
        wt_fp32, dtype=ELEM_TO_DTYPE[wt],
        block_size=BLOCK_SIZE, axis=1, scale_format=scale)

    # PE_Array stim
    emit_pe_array_stim(subdir, act_raw, wt_raw, sa_raw, sb_raw,
                       elem_bits_a=ELEM_BITS[act], elem_bits_b=ELEM_BITS[wt])

    # Requant stim — FP32 ref matmul per tile-block, truncated to (1+8+M_acc)
    narrow = compute_requant_stim(act_deq, wt_deq, m_acc)
    emit_requant_stim(subdir, narrow, m_acc)

    in_w = 1 + 8 + m_acc
    n_blocks_used = min(N_VECTORS, k_total // BLOCK_SIZE)
    print(f"  ✓ {label:32s}  M_acc={m_acc:2d}  in_w=FP{in_w:<2d}  "
          f"K={k_total}  blocks={n_blocks_used}")


def main():
    if not ROOT.exists():
        print(f"error: {ROOT} not found", file=sys.stderr)
        sys.exit(1)
    subdirs = sorted([p for p in ROOT.iterdir() if p.is_dir()])
    print(f"Regenerating real-workload stim for {len(subdirs)} key configs "
          f"(q_proj_l0 Gaussian; σ_act={SIGMA_ACT}, σ_w={SIGMA_W}) ...")
    for d in subdirs:
        process_one(d)
    print()
    print("Done.  Each generated/key_configs/<label>/ now has:")
    print("  - stim_pe_array_op_a.hex   ← MX-quantised act rows")
    print("  - stim_pe_array_op_b.hex   ← MX-quantised weight cols")
    print("  - stim_pe_array_exp_a.hex  ← shared scales (row)")
    print("  - stim_pe_array_exp_b.hex  ← shared scales (col)")
    print("  - stim_requant.hex         ← FP32 acc → narrow-FP truncated, 64 elems/cycle")
    print()
    print("These overwrite the synthetic random stim. The requant-integrated")
    print("path inside PE_Array uses the live PE accumulator output — no")
    print("separate stim file is needed for that path.")


if __name__ == "__main__":
    main()
