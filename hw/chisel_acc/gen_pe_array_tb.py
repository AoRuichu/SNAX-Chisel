#!/usr/bin/env python3
"""Generate testbench + stimulus + SDC + filelist for the integrated PE_Array.

For each subdir under generated/key_configs/, parses the emitted PE_Array.sv to
discover the module IO list and writes alongside it:

  <label>/
    PE_Array.sv             (already there from Chisel emit)
    tb_pe_array.sv          (this script)
    stim_pe_array_op_a.hex      (4 row inputs × 32-bit, N_VECTORS lines)
    stim_pe_array_op_b.hex      (16 col inputs × 32-bit, N_VECTORS lines)
    stim_pe_array_exp_a.hex     (4 row scales × 8-bit, N_VECTORS lines)
    stim_pe_array_exp_b.hex     (16 col scales × 8-bit, N_VECTORS lines)
    filelist_pe_array.f     (newline-separated SV file list)

The synthetic stim is a placeholder.  Replace with the real-workload trace
from `gen_real_workload_stim.py` (Task B) before running DC/PT.

Stimulus pattern:
  - mode/group bits held constant (RTL is statically baked at Chisel emit
    time, so runtime mode pins don't re-route logic — they just sit)
  - valid_in held high every cycle
  - acc_reset_i pulsed at start, send_output_i pulsed every 4 cycles
    (one block = blockSize/vec = 16/4 = 4 cycles)
  - random 32-bit datapath values per cycle
  - random scale bytes per block boundary, repeated within a block

Run:  python3 gen_pe_array_tb.py
"""
from __future__ import annotations
import os, re, random, sys
from pathlib import Path

ROOT = Path("generated/key_configs")
N_VECTORS  = 256
SEED       = 0xDEC0DE
BLOCK_SIZE = 16
VEC        = 4
TILE_ROWS  = 4
TILE_COLS  = 16
CPB        = BLOCK_SIZE // VEC   # 4 cycles per block

# Mode bit values per format (from gen_pe_array_rtl.py mappings).
# These are runtime-tie values used to label-correctly drive the array; the
# Chisel-emitted RTL is statically baked, so the mode pins don't reroute
# datapaths — they hold a valid value to keep mux selectors from floating.
ELEM_MODE = {"INT8": 0, "E5M2": 1, "E4M3": 2, "E3M2": 3, "E2M3": 4, "E2M1": 5}
SCALE_MODE = {"UE8M0": 0, "UE7M1": 1, "UE6M2": 2, "UE5M3": 3, "UE4M4": 4,
              "UE3M5": 5, "UE2M6": 6, "UE4M3": 0}   # UE4M3 not in scale-mode LUT → 0
RESULT_MODE = {"INT8": 0, "E5M2": 2, "E4M3": 3, "E3M2": 0, "E2M3": 0, "E2M1": 0}

# Parse label like "E5M2_E5M2_UE6M2_M11" → (act, weight, scale, m_acc)
LABEL_RE = re.compile(r"^([A-Z0-9]+)_([A-Z0-9]+)_(UE[0-9]M[0-9])_M(\d+)$")


def parse_label(label: str) -> tuple[str, str, str, int]:
    m = LABEL_RE.match(label)
    if not m:
        raise ValueError(f"Cannot parse config label: {label}")
    return m.group(1), m.group(2), m.group(3), int(m.group(4))


def parse_pe_array_sv(sv_path: Path) -> dict:
    """Pull the PE_Array module's IO widths from the emitted .sv file."""
    txt = sv_path.read_text()
    body_match = re.search(r"^module\s+PE_Array\((.*?)\);", txt, re.S | re.M)
    if not body_match:
        raise RuntimeError(f"No `module PE_Array(` found in {sv_path}")
    body = body_match.group(1)

    def width_of(port: str) -> int:
        m = re.search(rf"(?:input|output)\s*\[\s*(\d+):0\s*\]\s*io_{re.escape(port)}\b", body)
        if m:
            return int(m.group(1)) + 1
        if re.search(rf"(?:input|output)\s+io_{re.escape(port)}\b", body):
            return 1
        # Comma-separated in same width group: only first declares width.
        # Walk back through the body to find the enclosing width.
        idx = body.find(f"io_{port}")
        if idx < 0:
            return 0
        prefix = body[:idx]
        ms = list(re.finditer(r"\[\s*(\d+):0\s*\]", prefix))
        if ms:
            # Could be width of an earlier port in same group; verify no
            # intervening "input"/"output" keyword separates them.
            last_w = ms[-1]
            tail = prefix[last_w.end():]
            if not re.search(r"\b(input|output)\b", tail):
                return int(last_w.group(1)) + 1
        return 1   # default to bool if width keyword not found

    info = {
        "op_a_w":   width_of("op_a_i_0"),
        "op_b_w":   width_of("op_b_i_0"),
        "exp_a_w":  width_of("shared_exp_A_i_0"),
        "exp_b_w":  width_of("shared_exp_B_i_0"),
        "result_w": width_of("result"),
        "scale_out_w": width_of("shared_scale_out"),
    }
    return info


def gen_stim_lines(n_ports: int, port_w: int, n_vectors: int,
                   rng: random.Random,
                   per_block: bool = False) -> list[str]:
    """Generate one hex line per cycle, n_ports * port_w bits packed MSB-first.

    If per_block is True, the value is held constant within each block (CPB
    cycles).  Otherwise a fresh random value is drawn every cycle.
    """
    lines = []
    cached = None
    for i in range(n_vectors):
        if per_block and (i % CPB) != 0 and cached is not None:
            lines.append(cached)
            continue
        packed = 0
        for p in range(n_ports):
            val = rng.getrandbits(port_w)
            # Bias scale bytes to the middle of the exponent range so the
            # array sees realistic (non-zero, non-saturating) magnitudes.
            if port_w == 8:
                val = (val & 0x3F) | 0x40    # exp in ~[0x40, 0x7F]
            packed |= val << ((n_ports - 1 - p) * port_w)
        s = f"{packed:0{(n_ports * port_w + 3) // 4}x}"
        cached = s
        lines.append(s)
    return lines


TB_TEMPLATE = r"""// Auto-generated testbench for integrated PE_Array
// Config: {label}    Act={act}  Wt={wt}  Scale={scale}  M_acc={m_acc}
// Result width: {result_w} bits ({result_w_per_elem}b/elem × {n_result_elems} elems)
`timescale 1ns/1ps

module tb_pe_array_{label_sane};
  // ── Geometry (fixed) ──
  localparam TILE_ROWS    = {tile_rows};
  localparam TILE_COLS    = {tile_cols};
  localparam VEC          = {vec};
  localparam BLOCK_SIZE   = {block_size};
  localparam CPB          = BLOCK_SIZE / VEC;   // = 4
  localparam OP_A_W       = {op_a_w};
  localparam OP_B_W       = {op_b_w};
  localparam EXP_A_W      = {exp_a_w};
  localparam EXP_B_W      = {exp_b_w};
  localparam RESULT_W     = {result_w};
  localparam SCALE_OUT_W  = {scale_out_w};
  localparam N_VECTORS    = {n_vectors};

  // ── 400 MHz clock (period = 2.5 ns) ──
  reg clock = 1'b0;
  always #1.25 clock = ~clock;

  reg                  reset                  = 1'b1;
  // Mode pins — held constant; Chisel emit baked the format into RTL,
  // these just keep mux selectors from floating during synthesis.
  reg [2:0]            io_A_mode              = {a_mode}'d{a_mode_v};
  reg [2:0]            io_B_mode              = {b_mode}'d{b_mode_v};
  reg [1:0]            io_result_mode_quan    = {res_mode}'d{res_mode_v};
  reg [1:0]            io_group_size          = 2'd0;
  reg [3:0]            io_shared_format_i     = 4'd{scale_mode_v};

  reg                  io_acc_reset_i         = 1'b0;
  reg                  io_send_output_i       = 1'b0;
  reg [31:0]           io_accumulation_count_i = 32'd0;
  reg                  io_A_valid_i           = 1'b0;
  reg                  io_B_valid_i           = 1'b0;
  wire                 io_A_ready_o;
  wire                 io_B_ready_o;

  // Tile inputs (TILE_ROWS row ports, TILE_COLS col ports — each OP_A_W/OP_B_W wide)
  reg [OP_A_W-1:0]     io_op_a_i [0:TILE_ROWS-1];
  reg [OP_B_W-1:0]     io_op_b_i [0:TILE_COLS-1];
  reg [EXP_A_W-1:0]    io_shared_exp_A_i [0:TILE_ROWS-1];
  reg [EXP_B_W-1:0]    io_shared_exp_B_i [0:TILE_COLS-1];

  wire [SCALE_OUT_W-1:0] io_shared_scale_out;
  wire [RESULT_W-1:0]    io_result;
  wire                   io_valid_out;

  PE_Array dut (
    .clock                  (clock),
    .reset                  (reset),
    .io_A_mode              (io_A_mode),
    .io_B_mode              (io_B_mode),
    .io_result_mode_quan    (io_result_mode_quan),
    .io_group_size          (io_group_size),
    .io_shared_format_i     (io_shared_format_i),
    .io_acc_reset_i         (io_acc_reset_i),
    .io_send_output_i       (io_send_output_i),
    .io_accumulation_count_i(io_accumulation_count_i),
    .io_A_valid_i           (io_A_valid_i),
    .io_B_valid_i           (io_B_valid_i),
    .io_A_ready_o           (io_A_ready_o),
    .io_B_ready_o           (io_B_ready_o),
{op_a_connections}
{op_b_connections}
{exp_a_connections}
{exp_b_connections}
    .io_shared_scale_out    (io_shared_scale_out),
    .io_result              (io_result),
    .io_valid_out           (io_valid_out)
  );

  // ── Stimulus storage (packed across all ports per cycle) ──
  reg [TILE_ROWS*OP_A_W-1:0]  stim_op_a [0:N_VECTORS-1];
  reg [TILE_COLS*OP_B_W-1:0]  stim_op_b [0:N_VECTORS-1];
  reg [TILE_ROWS*EXP_A_W-1:0] stim_exp_a [0:N_VECTORS-1];
  reg [TILE_COLS*EXP_B_W-1:0] stim_exp_b [0:N_VECTORS-1];

  integer i, r, c;
  initial begin
    $readmemh("stim_pe_array_op_a.hex",  stim_op_a);
    $readmemh("stim_pe_array_op_b.hex",  stim_op_b);
    $readmemh("stim_pe_array_exp_a.hex", stim_exp_a);
    $readmemh("stim_pe_array_exp_b.hex", stim_exp_b);

    // VCD for PT-PX power analysis
    $dumpfile("pe_array.vcd");
    $dumpvars(0, dut);

    // Reset / accumulator clear
    #2  reset = 1'b1;
        io_acc_reset_i = 1'b1;
    #10 reset = 1'b0;
    #5  io_acc_reset_i = 1'b0;

    // Drive all N_VECTORS cycles
    for (i = 0; i < N_VECTORS; i = i + 1) begin
      @(posedge clock);
      // Unpack per-port slots (MSB = port 0).
      for (r = 0; r < TILE_ROWS; r = r + 1) begin
        io_op_a_i[r]        = stim_op_a[i] [(TILE_ROWS-1-r)*OP_A_W +: OP_A_W];
        io_shared_exp_A_i[r] = stim_exp_a[i][(TILE_ROWS-1-r)*EXP_A_W +: EXP_A_W];
      end
      for (c = 0; c < TILE_COLS; c = c + 1) begin
        io_op_b_i[c]        = stim_op_b[i] [(TILE_COLS-1-c)*OP_B_W +: OP_B_W];
        io_shared_exp_B_i[c] = stim_exp_b[i][(TILE_COLS-1-c)*EXP_B_W +: EXP_B_W];
      end
      io_A_valid_i = 1'b1;
      io_B_valid_i = 1'b1;
      // Pulse send_output at the end of every block (CPB cycles)
      io_send_output_i = ((i % CPB) == (CPB - 1));
      io_accumulation_count_i = i;
    end

    @(posedge clock);
    io_A_valid_i     = 1'b0;
    io_B_valid_i     = 1'b0;
    io_send_output_i = 1'b0;
    #50;
    $finish;
  end
endmodule
"""


def emit_one(subdir: Path) -> None:
    label = subdir.name
    act, wt, scale, m_acc = parse_label(label)
    sv = subdir / "PE_Array.sv"
    if not sv.exists():
        print(f"  ! {label}: PE_Array.sv missing, skipping")
        return
    info = parse_pe_array_sv(sv)

    op_a_w  = info["op_a_w"]  or 32
    op_b_w  = info["op_b_w"]  or 32
    exp_a_w = info["exp_a_w"] or 8
    exp_b_w = info["exp_b_w"] or 8

    # Result width / element size depends on the requant output format.
    result_w = info["result_w"]
    # n_result_elems = tileRows * blockSize = 64 for FP outputs (8/6/4-bit elem),
    # but for INT8 path it's tileRows * tileCols = 64.  Use 64.
    n_result_elems = TILE_ROWS * BLOCK_SIZE
    result_w_per_elem = result_w // n_result_elems if n_result_elems else 0

    # ── Port connections ──────────────────────────────────────────────────
    def port_block(prefix: str, n: int) -> str:
        # The PE_Array port name uses lowercase `io_op_a_i_0..N`; for the
        # exp ports it's `io_shared_exp_A_i_0..N`.  Caller passes the
        # right prefix.
        lines = []
        for k in range(n):
            lines.append(f"    .io_{prefix}_{k:<2d}             (io_{prefix.replace('shared_exp_','shared_exp_').replace('op_','op_')}[{k}]),".replace("    .io_op_a_i_", "    .io_op_a_i_")
                         )
        return "\n".join(lines)

    def conn_lines(rtl_prefix: str, tb_array_prefix: str, n: int, pad: int = 32) -> str:
        out = []
        for k in range(n):
            out.append(f"    .io_{rtl_prefix}_{k:<2d}{' ' * max(1, pad - len(rtl_prefix) - len(str(k)) - 4)}({tb_array_prefix}[{k}]),")
        return "\n".join(out)

    op_a_conn  = conn_lines("op_a_i", "io_op_a_i", TILE_ROWS, pad=22)
    op_b_conn  = conn_lines("op_b_i", "io_op_b_i", TILE_COLS, pad=22)
    exp_a_conn = conn_lines("shared_exp_A_i", "io_shared_exp_A_i", TILE_ROWS, pad=22)
    exp_b_conn = conn_lines("shared_exp_B_i", "io_shared_exp_B_i", TILE_COLS, pad=22)

    # ── Fill template ─────────────────────────────────────────────────────
    tb = TB_TEMPLATE.format(
        label=label,
        label_sane=label.replace("-", "_"),
        act=act, wt=wt, scale=scale, m_acc=m_acc,
        tile_rows=TILE_ROWS, tile_cols=TILE_COLS, vec=VEC,
        block_size=BLOCK_SIZE,
        op_a_w=op_a_w, op_b_w=op_b_w,
        exp_a_w=exp_a_w, exp_b_w=exp_b_w,
        result_w=result_w, scale_out_w=info["scale_out_w"],
        result_w_per_elem=result_w_per_elem,
        n_result_elems=n_result_elems,
        n_vectors=N_VECTORS,
        a_mode=3, a_mode_v=ELEM_MODE[act],
        b_mode=3, b_mode_v=ELEM_MODE[wt],
        res_mode=2, res_mode_v=RESULT_MODE[act],
        scale_mode_v=SCALE_MODE[scale],
        op_a_connections=op_a_conn,
        op_b_connections=op_b_conn,
        exp_a_connections=exp_a_conn,
        exp_b_connections=exp_b_conn,
    )
    (subdir / "tb_pe_array.sv").write_text(tb)

    # ── Stimulus files ────────────────────────────────────────────────────
    rng = random.Random(SEED ^ hash(label))
    op_a_lines  = gen_stim_lines(TILE_ROWS, op_a_w,  N_VECTORS, rng)
    op_b_lines  = gen_stim_lines(TILE_COLS, op_b_w,  N_VECTORS, rng)
    exp_a_lines = gen_stim_lines(TILE_ROWS, exp_a_w, N_VECTORS, rng, per_block=True)
    exp_b_lines = gen_stim_lines(TILE_COLS, exp_b_w, N_VECTORS, rng, per_block=True)

    (subdir / "stim_pe_array_op_a.hex" ).write_text("\n".join(op_a_lines)  + "\n")
    (subdir / "stim_pe_array_op_b.hex" ).write_text("\n".join(op_b_lines)  + "\n")
    (subdir / "stim_pe_array_exp_a.hex").write_text("\n".join(exp_a_lines) + "\n")
    (subdir / "stim_pe_array_exp_b.hex").write_text("\n".join(exp_b_lines) + "\n")

    # ── Filelist ──────────────────────────────────────────────────────────
    (subdir / "filelist_pe_array.f").write_text("PE_Array.sv\ntb_pe_array.sv\n")

    print(f"  ✓ {label:32s}  op_a=4×{op_a_w}b  op_b=16×{op_b_w}b  "
          f"result={result_w}b ({result_w_per_elem}b×64)")


def main():
    if not ROOT.exists():
        print(f"error: {ROOT} not found", file=sys.stderr)
        sys.exit(1)
    subdirs = sorted([p for p in ROOT.iterdir() if p.is_dir()])
    print(f"Generating PE_Array TB + stimulus for {len(subdirs)} key configs ...")
    for d in subdirs:
        emit_one(d)
    print()
    print(f"Done. Each {ROOT}/<label>/ now contains:")
    print("  - PE_Array.sv               (integrated PE + Requant)")
    print("  - tb_pe_array.sv            (integrated array testbench)")
    print("  - stim_pe_array_op_a.hex    (4-row act inputs × {N} cycles)".format(N=N_VECTORS))
    print("  - stim_pe_array_op_b.hex    (16-col weight inputs × {N} cycles)".format(N=N_VECTORS))
    print("  - stim_pe_array_exp_a.hex   (row scales)")
    print("  - stim_pe_array_exp_b.hex   (col scales)")
    print("  - filelist_pe_array.f       (file list for DC synth)")


if __name__ == "__main__":
    main()
