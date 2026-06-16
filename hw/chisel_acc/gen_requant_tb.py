#!/usr/bin/env python3
"""Generate per-config testbench + stimulus + SDC for the 126 requant variants.

For each subdir under generated/requant_sweep/, parses the emitted Verilog
to discover the module name and port widths, then writes:

  <label>/
    requant_in<W>.sv     (already there from Chisel emit)
    tb.sv                (this script)
    stim.hex             (this script)
    sim.sdc              (this script,  400 MHz clock)
    filelist.f           (this script,  newline-separated SV file list)

The bundle is self-contained — tar/zip and ship to the DC machine.

Per-element stimulus is synthesised:  random sign, random biased
exponent within a reasonable accumulator-output range, random
mantissa bits.  Not a real workload trace yet — replace with the
captured FP accumulator output from numpy reference when available.
"""
from __future__ import annotations
import os, re, random, sys
from pathlib import Path

import sys
# Multiple roots:  --key-configs  → process the 6 sweep-aligned key configs,
#                                   each in generated/key_configs/<label>/requant_standalone/
#                                   (TB lands one level up next to PE_Array.sv).
#                  (default)      → process the 126-config requant_sweep/.
ROOTS = (
    [(Path("generated/key_configs"), True)]                       # key-configs mode
    if "--key-configs" in sys.argv else
    [(Path("generated/requant_sweep"), False)]                     # 126-sweep mode (default)
)
N_VECTORS = 256           # stimulus length per config
SEED      = 0xC0FFEE      # reproducible random per config

# IEEE-style biased exponent range producing values ~ [2^-32, 2^32]
# (typical FP32 accumulator output range for sub-8-bit MAC arrays).
EXP_BIASED_LO = 127 - 32
EXP_BIASED_HI = 127 + 32

OUT_W_BY_ACT = {  # output element width per activation format
    "INT8": 8, "E5M2": 8, "E4M3": 8,
    "E3M2": 6, "E2M3": 6, "E2M1": 4,
}

# ----------------------------------------------------------------------
def parse_sv(sv_path: Path) -> dict:
    """Pull module name + port widths from the emitted .sv file."""
    txt = sv_path.read_text()
    info = {}

    # Top module name: the last `module <name>(` line whose body has io_fp32_in[N:0]
    mods = re.findall(r"^module\s+(\w+)\(", txt, re.M)
    # Heuristic: top module is the last one defined that has tile-level packed io_fp32_in
    info["module"] = None
    for m in reversed(mods):
        body_match = re.search(
            rf"module\s+{m}\(.*?endmodule",
            txt, re.S)
        if body_match and re.search(r"input\s*\[\s*(\d+):0\s*\]\s*io_fp32_in\b",
                                    body_match.group(0)):
            info["module"] = m
            break
    if info["module"] is None:
        raise RuntimeError(f"No top module with io_fp32_in found in {sv_path}")

    # IO widths from the top-module signature
    body = re.search(rf"module\s+{info['module']}\((.*?)\);",
                     txt, re.S).group(1)

    def width_of(port: str) -> int:
        # Match  "input  [N:0] io_<port>"  or  "output [N:0] io_<port>"
        m = re.search(rf"(?:input|output)\s*\[\s*(\d+):0\s*\]\s*io_{port}\b", body)
        if m:
            return int(m.group(1)) + 1
        # Boolean (no width brackets)
        if re.search(rf"(?:input|output)\s+io_{port}\b", body):
            return 1
        return 0

    info["fp32_in_w"]          = width_of("fp32_in")
    info["shared_scale_out_w"] = width_of("shared_scale_out")
    # INT8 path → io_int8_out, FP path → io_elem_out
    info["int8_out_w"] = width_of("int8_out")
    info["elem_out_w"] = width_of("elem_out")
    info["has_int8"] = info["int8_out_w"] > 0
    return info

# ----------------------------------------------------------------------
TB_TEMPLATE = r"""// Auto-generated testbench for {label}
// Module: {module}    Input width per elem: FP{in_w}    Output: {out_kind}{out_w}
`timescale 1ns/1ps

module tb_{label_sane};
  // ── Geometry (fixed: blockSize=16, tileRows=4, tileCols=16) ──
  localparam IN_W_PER_ELEM = {in_w};
  localparam N_ELEMS        = 64;
  localparam IN_TOTAL_W     = {fp32_in_w};       // = IN_W_PER_ELEM * N_ELEMS
  localparam OUT_W_PER_ELEM = {out_w};
  localparam OUT_TOTAL_W    = {out_total_w};
  localparam SCALE_TOTAL_W  = {scale_total_w};
  localparam N_VECTORS      = {n_vectors};

  // ── 400 MHz clock (period = 2.5 ns) ──
  reg clock = 1'b0;
  always #1.25 clock = ~clock;

  reg                     reset      = 1'b1;
  reg                     io_valid_in = 1'b0;
  reg  [IN_TOTAL_W-1:0]   io_fp32_in  = '0;

  wire [SCALE_TOTAL_W-1:0]  io_shared_scale_out;
  wire [OUT_TOTAL_W-1:0]    io_{out_kind};
  wire                      io_valid_out;

  {module} dut (
    .clock(clock),
    .reset(reset),
    .io_fp32_in(io_fp32_in),
    .io_valid_in(io_valid_in),
    .io_shared_scale_out(io_shared_scale_out),
    .io_{out_kind}(io_{out_kind}),
    .io_valid_out(io_valid_out)
  );

  // Stimulus
  reg [IN_TOTAL_W-1:0] stim_arr [0:N_VECTORS-1];

  integer i;
  initial begin
    $readmemh("{stim_filename}", stim_arr);

    // VCD for PT-PX power analysis
    $dumpfile("requant.vcd");
    $dumpvars(0, dut);

    #2 reset = 1'b1;
    #10 reset = 1'b0;
    #5;

    for (i = 0; i < N_VECTORS; i = i + 1) begin
      @(posedge clock);
      io_fp32_in   = stim_arr[i];
      io_valid_in  = 1'b1;
    end

    @(posedge clock) io_valid_in = 1'b0;
    #50;
    $finish;
  end
endmodule
"""

SDC_TEMPLATE = """# 400 MHz clock constraint for {label}
create_clock -name clk -period 2.5 [get_ports clock]
set_clock_uncertainty -setup 0.10 [get_clocks clk]
set_clock_uncertainty -hold  0.05 [get_clocks clk]

set_input_delay  -clock clk 0.5 [remove_from_collection [all_inputs]  [get_ports clock]]
set_output_delay -clock clk 0.5 [all_outputs]
set_max_fanout 16 [current_design]
"""

# ----------------------------------------------------------------------
def gen_stim(in_w_per_elem: int, n_elems: int, n_vectors: int,
             rng: random.Random) -> list[int]:
    """Generate n_vectors of (in_w_per_elem * n_elems)-bit packed FP values."""
    bias = 127
    out = []
    for _ in range(n_vectors):
        packed = 0
        for k in range(n_elems):
            sign = rng.randint(0, 1)
            exp  = rng.randint(EXP_BIASED_LO, EXP_BIASED_HI)
            # Some elements should be zero/subnormal occasionally (~5%)
            if rng.random() < 0.05:
                exp = 0
            mant = rng.getrandbits(in_w_per_elem - 8 - 1)
            val  = (sign << (in_w_per_elem - 1)) | (exp << (in_w_per_elem - 9)) | mant
            # Pack into MSB-first slot
            packed |= val << ((n_elems - 1 - k) * in_w_per_elem)
        out.append(packed)
    return out

# ----------------------------------------------------------------------
def emit_one(subdir: Path, key_configs_mode: bool = False) -> None:
    label = subdir.name              # e.g. E5M2_E5M2_UE6M2_M11
    label_sane = label.replace("-", "_")
    # In key_configs mode, the requant RTL lives in subdir/requant_standalone/.
    # The TB / stim land DIRECTLY in subdir (next to PE_Array.sv).
    if key_configs_mode:
        rq_dir = subdir / "requant_standalone"
        svs = [p for p in rq_dir.glob("*.sv") if p.name != "tb.sv"]
    else:
        svs = [p for p in subdir.glob("*.sv") if p.name != "tb.sv"]
    if not svs:
        print(f"  ! {label}: no .sv found, skipping")
        return
    sv = svs[0]
    info = parse_sv(sv)

    # Derive widths
    fp32_in_w     = info["fp32_in_w"]                           # total packed
    in_w_per_elem = fp32_in_w // 64
    if fp32_in_w % 64 != 0:
        print(f"  ! {label}: fp32_in_w={fp32_in_w} not divisible by 64")
        return
    scale_total_w = info["shared_scale_out_w"]
    out_kind      = "int8_out" if info["has_int8"] else "elem_out"
    out_total_w   = info["int8_out_w"] if info["has_int8"] else info["elem_out_w"]
    out_w_per_elem = out_total_w // 64
    if out_total_w % 64 != 0:
        out_w_per_elem = out_total_w // (4 * 16)   # tileRows × blockSize

    # Where to drop TB/stim/SDC/filelist.  In key-configs mode they live
    # alongside PE_Array.sv (one level above the standalone .sv).
    target = subdir if not key_configs_mode else subdir
    sv_relpath = (sv.relative_to(target) if key_configs_mode
                  else Path(sv.name)).as_posix()

    # ── Write tb_requant.sv ──
    stim_filename = "stim_requant.hex" if key_configs_mode else "stim.hex"
    tb = TB_TEMPLATE.format(
        label=label, label_sane=label_sane,
        module=info["module"],
        in_w=in_w_per_elem,
        fp32_in_w=fp32_in_w,
        out_w=out_w_per_elem,
        out_total_w=out_total_w,
        out_kind=out_kind,
        scale_total_w=scale_total_w,
        n_vectors=N_VECTORS,
        stim_filename=stim_filename,
    )
    tb_name = "tb_requant.sv" if key_configs_mode else "tb.sv"
    (target / tb_name).write_text(tb)

    # ── Write stim_requant.hex ──
    rng = random.Random(SEED ^ hash(label))
    vectors = gen_stim(in_w_per_elem, 64, N_VECTORS, rng)
    hex_chars = (fp32_in_w + 3) // 4
    stim_name = "stim_requant.hex" if key_configs_mode else "stim.hex"
    with (target / stim_name).open("w") as f:
        for v in vectors:
            f.write(f"{v:0{hex_chars}x}\n")

    # ── Write sim.sdc ──
    if not (target / "sim.sdc").exists():
        (target / "sim.sdc").write_text(SDC_TEMPLATE.format(label=label))

    # ── Write filelist (requant standalone) ──
    fl_name = "filelist_requant.f" if key_configs_mode else "filelist.f"
    (target / fl_name).write_text(f"{sv_relpath}\n{tb_name}\n")

    print(f"  ✓ {label:32s}  FP{in_w_per_elem} in / {out_w_per_elem}b out × 64  ({info['module']})")

# ----------------------------------------------------------------------
def main():
    for root, key_mode in ROOTS:
        if not root.exists():
            print(f"warn: {root} not found, skipping")
            continue
        subdirs = sorted([p for p in root.iterdir() if p.is_dir()])
        tag = "key-configs (integrated PE+Requant)" if key_mode else "126-config sweep"
        print(f"Generating TB + stimulus for {len(subdirs)} variants in {root} ({tag}) ...")
        for d in subdirs:
            emit_one(d, key_configs_mode=key_mode)
        print()
        if key_mode:
            print(f"Done. Each {root}/<label>/ now contains:")
            print("  - PE_Array.sv                  (integrated PE Array + Requant)")
            print("  - requant_standalone/<...>.sv  (standalone Requant block)")
            print("  - tb_requant.sv                (Requant standalone testbench)")
            print("  - stim_requant.hex             (256-vector stimulus)")
            print("  - sim.sdc                      (400 MHz clock constraint)")
            print("  - filelist_requant.f           (file list for requant DC synth)")
        else:
            print(f"Done. Each {root}/<label>/ now contains:")
            print("  - requant_in<W>.sv   (Chisel emit)")
            print("  - tb.sv              (testbench)")
            print("  - stim.hex           (256-vector stimulus)")
            print("  - sim.sdc            (400 MHz clock constraint)")
            print("  - filelist.f         (file list for tools)")

if __name__ == "__main__":
    main()
