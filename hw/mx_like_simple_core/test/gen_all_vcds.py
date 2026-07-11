#!/usr/bin/env python3
"""Batch driver: for every emitted `generated/<config>/`, optionally generate
a workload vector (JSON), generate the SV testbench, invoke Verilator to
compile+run, and produce one VCD per config.

Two stimulus modes:
  --stim workload  (default): production LLM quantization pipeline (fitted
                    Gaussian + outlier activation, block-scale via
                    quantize_mx_v6), self-checks against FP64 golden.
  --stim random   : deterministic random stimulus (SEED=0xC0FFEE, K=64), no
                    self-check — for pure switching-activity VCD.

Usage:
    python3 test/gen_all_vcds.py                        # 126 workload VCDs, 4 jobs
    python3 test/gen_all_vcds.py --filter E4M3          # subset
    python3 test/gen_all_vcds.py --stim random --jobs 8
"""

from __future__ import annotations
import argparse
import glob
import os
import subprocess
import sys
from concurrent.futures import ProcessPoolExecutor, as_completed

HERE = os.path.dirname(__file__)
ROOT = os.path.abspath(os.path.join(HERE, ".."))
GEN  = os.path.join(ROOT, "generated")
VEC_GEN = os.path.join(HERE, "gen_simple_dpu_vectors.py")
TB_GEN  = os.path.join(HERE, "gen_simple_dpu_tb.py")

# Workload defaults (match tensor_core convention)
WORKLOAD_K   = 64
WORKLOAD_BS  = 16
WORKLOAD_VEC = 4
WORKLOAD_SEED = 0


def parse_meta(pedir: str) -> dict:
    d: dict[str, str] = {}
    with open(os.path.join(pedir, "simple_dpu_meta.txt")) as f:
        for line in f:
            if "=" not in line: continue
            k, v = line.strip().split("=", 1)
            d[k] = v
    return d


def run_one(pedir: str, stim: str) -> tuple[str, bool, str]:
    """Generate stimulus + tb + verilator + run. Return (config, ok, msg)."""
    cfg = os.path.basename(pedir)
    try:
        # 1) Generate workload vector JSON (if requested)
        vec_json = None
        if stim == "workload":
            meta = parse_meta(pedir)
            vecs_dir = os.path.join(pedir)  # sit next to the tb
            vec_json = os.path.join(
                vecs_dir,
                f"{meta['config']}_K{WORKLOAD_K}_bs{WORKLOAD_BS}_vec{WORKLOAD_VEC}_seed{WORKLOAD_SEED}.json"
            )
            v = subprocess.run(
                ["python3", VEC_GEN,
                 "--act", meta["elA"], "--weight", meta["elW"], "--scale", meta["scale"],
                 "--K", str(WORKLOAD_K), "--block-size", str(WORKLOAD_BS),
                 "--vector-size", str(WORKLOAD_VEC), "--seed", str(WORKLOAD_SEED),
                 "--outdir", vecs_dir],
                capture_output=True, text=True,
            )
            if v.returncode != 0:
                return (cfg, False, f"vec-gen failed:\n{v.stderr[-400:]}")

        # 2) Generate testbench SV
        tb_args = ["python3", TB_GEN, pedir]
        if vec_json: tb_args.append(vec_json)
        v = subprocess.run(tb_args, capture_output=True, text=True)
        if v.returncode != 0:
            return (cfg, False, f"tb-gen failed:\n{v.stderr[-400:]}")

        # 3) Verilator: --trace produces the VCD via $dumpfile in tb.
        svs = [f for f in os.listdir(pedir)
               if f.startswith("SimpleDPU_") and f.endswith(".sv")]
        if not svs: return (cfg, False, "no SimpleDPU_*.sv")
        dut_sv = svs[0]

        v = subprocess.run(
            ["verilator", "--binary", "--timing", "--trace",
             "-Wno-fatal", "-Wno-WIDTHEXPAND", "-Wno-WIDTHTRUNC",
             "--top-module", "tb_SimpleDPU",
             dut_sv, "tb_SimpleDPU.sv"],
            cwd=pedir, capture_output=True, text=True,
        )
        if v.returncode != 0:
            return (cfg, False, f"verilator failed:\n{v.stderr[-400:]}")

        # 4) Run
        r = subprocess.run(
            ["./obj_dir/Vtb_SimpleDPU"],
            cwd=pedir, capture_output=True, text=True, timeout=120,
        )
        if r.returncode != 0:
            return (cfg, False, f"run failed:\n{r.stderr[-400:]}")

        # 5) Report status — self-check line if workload mode
        vcd_files = glob.glob(os.path.join(pedir, "tb_SimpleDPU_*.vcd"))
        if not vcd_files: return (cfg, False, "no VCD produced")
        vcd_kb = os.path.getsize(vcd_files[0]) / 1024

        pass_line = [l for l in r.stdout.splitlines() if "RESULT:" in l or "CFG=" in l]
        check_summary = ""
        if pass_line:
            check_summary = "  " + " ".join(pass_line[-2:])
        return (cfg, True, f"vcd={os.path.basename(vcd_files[0])} ({vcd_kb:.0f} KB){check_summary}")

    except Exception as e:
        return (cfg, False, f"exception: {e}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--filter", default="", help="only run configs matching substring")
    ap.add_argument("--jobs", type=int, default=1)
    ap.add_argument("--stim", choices=["workload", "random"], default="workload",
                    help="workload: LLM-quantized stimulus + self-check; "
                         "random: deterministic random, no check")
    args = ap.parse_args()

    pedirs = sorted(d for d in glob.glob(os.path.join(GEN, "*")) if os.path.isdir(d))
    if args.filter:
        pedirs = [d for d in pedirs if args.filter in os.path.basename(d)]
    print(f"Processing {len(pedirs)} configs, jobs={args.jobs}, stim={args.stim}")

    ok = fail = 0
    def report(cfg, o, m):
        nonlocal ok, fail
        if o:
            ok += 1; print(f"  [OK]   {cfg}  {m}")
        else:
            fail += 1; print(f"  [FAIL] {cfg}  {m}")

    if args.jobs == 1:
        for pedir in pedirs: report(*run_one(pedir, args.stim))
    else:
        with ProcessPoolExecutor(max_workers=args.jobs) as ex:
            futures = {ex.submit(run_one, d, args.stim): d for d in pedirs}
            for fut in as_completed(futures):
                report(*fut.result())

    print(f"\nSummary: {ok} OK / {fail} FAIL / {len(pedirs)} total")
    sys.exit(0 if fail == 0 else 1)


if __name__ == "__main__":
    main()
