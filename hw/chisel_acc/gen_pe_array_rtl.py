#!/usr/bin/env python3
# Standalone driver: read a params.hjson and emit the combined PE-Array + Requant
# RTL via `sbt "runMain mx.GeneratePEArray ..."`.
#
# This script is intentionally separate from orchestrator(5).py — once the
# end-to-end flow is validated, the body of `run_pe_array_gen` can be folded
# back into the orchestrator (replacing today's separate run_mac_gen + run_requant_gen calls).
#
# Usage:
#   python gen_pe_array_rtl.py
#   python gen_pe_array_rtl.py --swcfg path/to/params.hjson
#   python gen_pe_array_rtl.py --swcfg ... --out-dir ./generated/pe_array/test
#   python gen_pe_array_rtl.py --dry-run   # print the sbt command without running it

import argparse
import shlex
import subprocess
import sys
from pathlib import Path

try:
    import hjson  # preferred; same dep orchestrator(5).py uses
except ModuleNotFoundError:
    hjson = None  # fall back to a tiny built-in parser (see _load_params)


def _parse_hjson_simple(text: str) -> dict:
    """Minimal parser for the flat `key: value` hjson subset that
    params(1).hjson uses. Handles // line comments, quoted strings,
    ints, floats, and trailing commas. No nested objects/arrays."""
    out: dict = {}
    for raw in text.splitlines():
        line = raw.split("//", 1)[0].strip()
        if not line or line in ("{", "}"):
            continue
        line = line.rstrip(",").strip()
        if ":" not in line:
            continue
        key, _, val = line.partition(":")
        key = key.strip().strip('"').strip("'")
        val = val.strip().rstrip(",").strip()
        if (val.startswith('"') and val.endswith('"')) or \
           (val.startswith("'") and val.endswith("'")):
            parsed: object = val[1:-1]
        else:
            try:
                parsed = int(val)
            except ValueError:
                try:
                    parsed = float(val)
                except ValueError:
                    parsed = val
        out[key] = parsed
    return out


def _load_params(path: Path) -> dict:
    text = path.read_text(encoding="utf-8")
    if hjson is not None:
        return hjson.loads(text)
    return _parse_hjson_simple(text)

SCRIPT_DIR    = Path(__file__).parent.resolve()
CHISEL_DIR    = SCRIPT_DIR
DEFAULT_SWCFG = SCRIPT_DIR / "params(1).hjson"

# ── Mapping tables — keep in sync with orchestrator(5).py ──────────────────────
_ELEMENT_TYPE_MAP = {0: "INT8", 1: "E5M2", 2: "E4M3", 3: "E3M2", 4: "E2M3", 5: "E2M1"}
_SCALE_FORMAT_MAP = {0: "UE8M0", 1: "UE7M1", 2: "UE6M2", 3: "UE5M3",
                     4: "UE4M4", 5: "UE3M5", 6: "UE2M6"}

# params.hjson dtype string → element-format index (into _ELEMENT_TYPE_MAP)
_DTYPE_TO_ELEMENT_TYPE = {
    'mxint8':   0,  # INT8
    'fp8_e5m2': 1,  # E5M2
    'fp8_e4m3': 2,  # E4M3
    'fp6_e3m2': 3,  # E3M2
    'fp6_e2m3': 4,  # E2M3
    'fp4_e2m1': 5,  # E2M1
}

# quantize_mode → requant-output label (just for log lines)
_REQUANT_LABEL = {
    1: "bf16",
    2: "fp8_e5m2",
    3: "fp8_e4m3",
    4: "mxint8",
    5: "fp6_e2m3",
    6: "fp6_e3m2",
}

# Element bit widths — keep in sync with orchestrator(5).py's _DTYPE_BITS.
_DTYPE_BITS = {
    'fp8_e4m3': 8, 'fp8_e5m2': 8,
    'fp6_e3m2': 6, 'fp6_e2m3': 6,
    'fp4_e2m1': 4, 'mxint8':   8,
}

# requant_mode → (out-tag for default dir name, requant element width in bits)
_REQUANT_OUT_TAG = {
    1: ("BF16",         16),  # BF16: bf16_out (16 bits per element)
    2: ("E5M2",          8),  # FP8
    3: ("E4M3",          8),  # FP8
    4: ("INT8",          8),  # mxint8
    5: ("E2M3",          6),  # FP6
    6: ("E3M2",          6),  # FP6
}


def _resolve_element(name: str, dtype_field: str) -> str:
    if dtype_field not in _DTYPE_TO_ELEMENT_TYPE:
        sys.exit(
            f"[gen_pe_array_rtl] unknown {name}={dtype_field!r}, "
            f"valid: {list(_DTYPE_TO_ELEMENT_TYPE)}"
        )
    return _ELEMENT_TYPE_MAP[_DTYPE_TO_ELEMENT_TYPE[dtype_field]]


def _default_out_dir(p: dict) -> Path:
    """Mirror the Scala-side outDirDefault in mx.GeneratePEArray so Python
    knows where the SV wrapper should land."""
    type_a = _resolve_element("A_dtype", p["A_dtype"])
    type_b = _resolve_element("B_dtype", p["B_dtype"])
    scale  = _SCALE_FORMAT_MAP[p.get("shared_format", 0)]
    rqmode = p["quantize_mode"]
    out_tag, _ = _REQUANT_OUT_TAG[rqmode]
    blk_size = p.get("block_size", 32)
    tag = "BF16" if rqmode == 1 else f"{out_tag}_blk{blk_size}"
    name = (
        f"{p['parfor_M']}x{p['parfor_N']}"
        f"_{type_a}_{type_b}_{scale}_vec{p['parfor_K']}_{tag}"
    )
    return CHISEL_DIR / "generated" / "pe_array" / name


def build_sbt_args(p: dict, out_dir: Path | None) -> list[str]:
    """Translate a params.hjson dict into the `mx.GeneratePEArray` CLI args."""
    requant_mode = p.get("quantize_mode")
    if requant_mode not in _REQUANT_LABEL:
        sys.exit(
            f"[gen_pe_array_rtl] quantize_mode={requant_mode} is not a "
            f"requant mode. Valid: {list(_REQUANT_LABEL)}"
        )

    type_a = _resolve_element("A_dtype", p["A_dtype"])
    type_b = _resolve_element("B_dtype", p["B_dtype"])

    shared_format = p.get("shared_format", 0)
    if shared_format not in _SCALE_FORMAT_MAP:
        sys.exit(
            f"[gen_pe_array_rtl] unknown shared_format={shared_format}, "
            f"valid: {_SCALE_FORMAT_MAP}"
        )
    scale = _SCALE_FORMAT_MAP[shared_format]

    cli = [
        "--type-a",      type_a,
        "--type-b",      type_b,
        "--scale",       scale,
        "--vec",         str(p["parfor_K"]),
        "--tile-rows",   str(p["parfor_M"]),
        "--tile-cols",   str(p["parfor_N"]),
        "--block-size",  str(p.get("block_size", 32)),
        "--requant-mode", str(requant_mode),
    ]
    if out_dir is not None:
        cli += ["--out-dir", str(out_dir)]
    return cli


def emit_pe_array_wrapper_sv(p: dict, out_dir: Path) -> Path:
    """Emit a thin SV adaptor module that exposes packed multi-dim IO around
    the Chisel-emitted PE_Array module, matching the connection style used by
    snax_mx_alu_shell_wrapper.

    The wrapper exposes only the requant outputs (shared_scale_out + result),
    not the FP32 accumulator path — that's internal to PE_Array.

    Sizes are baked in to match the elaborated Chisel module.
    """
    rqmode  = p["quantize_mode"]
    rows    = p["parfor_M"]
    cols    = p["parfor_N"]
    vec     = p["parfor_K"]
    blk     = p.get("block_size", 32)
    A_W     = _DTYPE_BITS[p["A_dtype"]]
    B_W     = _DTYPE_BITS[p["B_dtype"]]
    scale_W = 8       # all ScaleFormats are 8 bits total (UE8M0..UE2M6)
    out_tag, elem_W = _REQUANT_OUT_TAG[rqmode]
    scale_name = _SCALE_FORMAT_MAP[p.get("shared_format", 0)]

    # BF16 mode: per-element pass-through, no block / no shared scale.
    # Output shape is [TileRows][TileCols][16].
    # FP8 / FP6 / INT8: blocked output [TileRows][BlockSize][ELEM_WIDTH] +
    # one 8-bit shared scale per row.
    is_bf16 = (rqmode == 1)

    lines: list[str] = [
        "// AUTO-GENERATED by gen_pe_array_rtl.py — DO NOT EDIT.",
        "// Adaptor wrapper around Chisel-emitted PE_Array. Re-packs the flat",
        "// scalar Chisel ports (io_op_a_i_0, io_op_a_i_1, …) into packed",
        "// multi-dim arrays expected by snax_mx_alu_shell_wrapper.",
        f"// Config: A={p['A_dtype']} B={p['B_dtype']} scale={scale_name}"
        f" {rows}x{cols} vec{vec} blk{blk} requant_mode={rqmode} ({_REQUANT_LABEL[rqmode]})",
        "`timescale 1ns / 1ps",
        "",
        "module PE_Array_wrapper #(",
        f"    parameter int unsigned A_WIDTH     = {A_W},",
        f"    parameter int unsigned B_WIDTH     = {B_W},",
        f"    parameter int unsigned ELEM_WIDTH  = {16 if is_bf16 else elem_W},",
        f"    parameter int unsigned TileRows    = {rows},",
        f"    parameter int unsigned TileCols    = {cols},",
        f"    parameter int unsigned VectorSize  = {vec},",
        f"    parameter int unsigned BlockSize   = {blk},",
        f"    parameter int unsigned SCALE_WIDTH = {scale_W}",
        ")(",
        "    input  logic clk_i,",
        "    input  logic rst_ni,",
        "",
        "    // CSR / control",
        "    input  logic [2:0] A_mode,",
        "    input  logic [2:0] B_mode,",
        "    input  logic [1:0] Result_mode_quan,",
        "    input  logic [1:0] group_size,",
        "    input  logic [3:0] shared_format_i,",
        "",
        "    input  logic acc_reset_i,",
        "    input  logic send_output_i,",
        "    input  logic A_valid_i,",
        "    input  logic B_valid_i,",
        "    output logic A_ready_o,",
        "    output logic B_ready_o,",
        "",
        "    // Packed operand / shared-exp inputs",
        "    input  logic [0:TileRows-1][A_WIDTH*VectorSize-1:0] op_a_i,",
        "    input  logic [0:TileCols-1][B_WIDTH*VectorSize-1:0] op_b_i,",
        "    input  logic [0:TileRows-1][SCALE_WIDTH-1:0]        shared_exp_A_i,",
        "    input  logic [0:TileCols-1][SCALE_WIDTH-1:0]        shared_exp_B_i,",
    ]

    if is_bf16:
        lines += [
            "",
            "    // BF16 requantised output: [TileRows][TileCols][16] packed; no shared scale.",
            "    output logic [0:TileRows-1][0:TileCols-1][ELEM_WIDTH-1:0] result_o,",
        ]
    else:
        lines += [
            "",
            f"    // {out_tag} requantised output: [TileRows][BlockSize][{elem_W}] packed,"
            " plus one 8-bit shared scale per row.",
            "    output logic [0:TileRows-1][7:0]                          shared_scale_out,",
            "    output logic [0:TileRows-1][0:BlockSize-1][ELEM_WIDTH-1:0] result_o,",
        ]

    lines += [
        "    output logic                                              valid_out",
        ");",
        "",
        "    // Chisel emits positive-asserted reset; shell uses active-low async rst_ni.",
        "    PE_Array u_pe (",
        "        .clock              (clk_i),",
        "        .reset              (~rst_ni),",
        "        .io_A_mode          (A_mode),",
        "        .io_B_mode          (B_mode),",
        "        .io_result_mode_quan(Result_mode_quan),",
        "        .io_group_size      (group_size),",
        "        .io_shared_format_i (shared_format_i),",
        "        .io_acc_reset_i     (acc_reset_i),",
        "        .io_send_output_i   (send_output_i),",
        "        .io_A_valid_i       (A_valid_i),",
        "        .io_B_valid_i       (B_valid_i),",
        "        .io_A_ready_o       (A_ready_o),",
        "        .io_B_ready_o       (B_ready_o),",
    ]

    for r in range(rows):
        lines.append(f"        .io_op_a_i_{r}        (op_a_i[{r}]),")
    for c in range(cols):
        lines.append(f"        .io_op_b_i_{c}        (op_b_i[{c}]),")
    for r in range(rows):
        lines.append(f"        .io_shared_exp_A_i_{r}(shared_exp_A_i[{r}]),")
    for c in range(cols):
        lines.append(f"        .io_shared_exp_B_i_{c}(shared_exp_B_i[{c}]),")

    # Debug FP32 results_o ports on the inner PE_Array are intentionally left
    # unconnected at the wrapper level — the snax_mx_alu_shell_wrapper does not
    # consume them. Empty parens make the intent explicit (vs. silently dropping).
    for r in range(rows):
        for c in range(cols):
            lines.append(f"        .io_results_o_{r}_{c}    (),")

    if not is_bf16:
        lines.append("        .io_shared_scale_out(shared_scale_out),")
    lines += [
        "        .io_result          (result_o),",
        "        .io_valid_out       (valid_out)",
        "    );",
        "",
        "endmodule",
        "",
    ]

    sv_path = out_dir / "PE_Array_wrapper.sv"
    sv_path.write_text("\n".join(lines), encoding="utf-8")
    return sv_path


def run_pe_array_gen(p: dict, out_dir: Path | None, dry_run: bool) -> int:
    # Always resolve out_dir up-front so the wrapper-emitter knows where to land.
    if out_dir is None:
        out_dir = _default_out_dir(p)

    cli = build_sbt_args(p, out_dir)
    sbt_cmd = "runMain mx.GeneratePEArray " + " ".join(shlex.quote(a) for a in cli)

    print(f"[gen_pe_array_rtl] requant_mode={p['quantize_mode']} "
          f"({_REQUANT_LABEL[p['quantize_mode']]}), "
          f"A={p['A_dtype']}, B={p['B_dtype']}, "
          f"M×N×K={p['parfor_M']}×{p['parfor_N']}×{p['parfor_K']}, "
          f"block_size={p.get('block_size', 32)}")
    print(f"[gen_pe_array_rtl] out_dir: {out_dir}")
    print(f"[gen_pe_array_rtl] cwd:     {CHISEL_DIR}")
    print(f"[gen_pe_array_rtl] sbt {sbt_cmd}")

    if dry_run:
        print("[gen_pe_array_rtl] --dry-run set; skipping sbt invocation.")
        # Still emit the wrapper preview so we can eyeball ports / sizes.
        out_dir.mkdir(parents=True, exist_ok=True)
        sv_path = emit_pe_array_wrapper_sv(p, out_dir)
        print(f"[gen_pe_array_rtl] wrote SV adaptor (dry-run preview) → {sv_path}")
        return 0

    result = subprocess.run(["sbt", sbt_cmd], cwd=CHISEL_DIR)
    if result.returncode != 0:
        sys.exit(f"[gen_pe_array_rtl] sbt failed (exit {result.returncode})")

    # sbt creates out_dir; emit the SV adaptor next to PE_Array.sv.
    sv_path = emit_pe_array_wrapper_sv(p, out_dir)
    print(f"[gen_pe_array_rtl] wrote SV adaptor → {sv_path}")
    return result.returncode


def main() -> None:
    ap = argparse.ArgumentParser(
        description="Read params.hjson and generate the combined PE-Array RTL "
                    "via mx.GeneratePEArray."
    )
    ap.add_argument("--swcfg",   default=DEFAULT_SWCFG,
                    help="params.hjson (sw config). Default: %(default)s")
    ap.add_argument("--out-dir", default=None,
                    help="Override --out-dir passed to the Scala generator. "
                         "Default: auto-named under generated/pe_array/.")
    ap.add_argument("--dry-run", action="store_true",
                    help="Print the sbt invocation without running it.")
    args = ap.parse_args()

    swcfg = Path(args.swcfg).resolve()
    if not swcfg.exists():
        sys.exit(f"[gen_pe_array_rtl] swcfg not found: {swcfg}")

    print(f"[gen_pe_array_rtl] loading params ← {swcfg}")
    params = _load_params(swcfg)
    if hjson is None:
        print("[gen_pe_array_rtl] note: hjson module not found — using built-in fallback parser")

    out_dir = Path(args.out_dir).resolve() if args.out_dir else None
    run_pe_array_gen(params, out_dir, args.dry_run)
    print("[gen_pe_array_rtl] done.")


if __name__ == "__main__":
    main()
