#!/usr/bin/env python3
"""Batch driver: for every emitted `generated/<config>/`, generate the
testbench, invoke Verilator to compile + run, and produce one VCD per config.

Usage:
    python3 test/gen_all_vcds.py                 # all 126 configs
    python3 test/gen_all_vcds.py --filter "E4M3" # only matching configs
    python3 test/gen_all_vcds.py --jobs 4        # parallel Verilator jobs
"""

from __future__ import annotations
import argparse
import glob
import os
import subprocess
import sys
from concurrent.futures import ProcessPoolExecutor, as_completed

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
GEN  = os.path.join(ROOT, "generated")
TB_GEN = os.path.join(os.path.dirname(__file__), "gen_simple_dpu_tb.py")


def run_one(pedir: str) -> tuple[str, bool, str]:
    """Generate tb, verilate + run, return (config, ok, message)."""
    cfg = os.path.basename(pedir)
    try:
        # 1) Generate tb SV
        subprocess.run(["python3", TB_GEN, pedir], check=True, capture_output=True)

        # 2) Find the SimpleDPU SV file (exact name embeds config)
        svs = [f for f in os.listdir(pedir)
               if f.startswith("SimpleDPU_") and f.endswith(".sv")]
        if not svs:
            return (cfg, False, "no SimpleDPU_*.sv found")
        dut_sv = svs[0]

        # 3) Verilator: --trace produces the VCD via $dumpfile/$dumpvars in tb.
        v = subprocess.run(
            ["verilator", "--binary", "--timing", "--trace",
             "-Wno-fatal", "-Wno-WIDTHEXPAND", "-Wno-WIDTHTRUNC",
             "--top-module", "tb_SimpleDPU",
             dut_sv, "tb_SimpleDPU.sv"],
            cwd=pedir, capture_output=True, text=True,
        )
        if v.returncode != 0:
            return (cfg, False, f"verilator failed:\n{v.stderr[-500:]}")

        # 4) Run the compiled binary — produces tb_SimpleDPU_<cfg>.vcd in cwd.
        r = subprocess.run(
            ["./obj_dir/Vtb_SimpleDPU"],
            cwd=pedir, capture_output=True, text=True, timeout=120,
        )
        if r.returncode != 0:
            return (cfg, False, f"tb run failed:\n{r.stderr[-500:]}")

        vcd_files = glob.glob(os.path.join(pedir, "tb_SimpleDPU_*.vcd"))
        if not vcd_files:
            return (cfg, False, "no VCD produced")
        vcd_size = sum(os.path.getsize(v) for v in vcd_files)
        return (cfg, True, f"vcd={os.path.basename(vcd_files[0])} ({vcd_size/1024:.0f} KB)")

    except subprocess.CalledProcessError as e:
        return (cfg, False, f"subprocess error: {e}")
    except Exception as e:
        return (cfg, False, f"exception: {e}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--filter", default="", help="only run configs matching this substring")
    ap.add_argument("--jobs", type=int, default=1, help="parallel Verilator jobs")
    args = ap.parse_args()

    pedirs = sorted(d for d in glob.glob(os.path.join(GEN, "*"))
                    if os.path.isdir(d))
    if args.filter:
        pedirs = [d for d in pedirs if args.filter in os.path.basename(d)]
    print(f"Processing {len(pedirs)} configs, jobs={args.jobs}")

    ok_count = fail_count = 0
    if args.jobs == 1:
        for pedir in pedirs:
            cfg, ok, msg = run_one(pedir)
            if ok:
                ok_count += 1
                print(f"  [OK]   {cfg}  {msg}")
            else:
                fail_count += 1
                print(f"  [FAIL] {cfg}  {msg}")
    else:
        with ProcessPoolExecutor(max_workers=args.jobs) as ex:
            futures = {ex.submit(run_one, d): d for d in pedirs}
            for fut in as_completed(futures):
                cfg, ok, msg = fut.result()
                if ok:
                    ok_count += 1
                    print(f"  [OK]   {cfg}  {msg}")
                else:
                    fail_count += 1
                    print(f"  [FAIL] {cfg}  {msg}")

    print(f"\nSummary: {ok_count} OK / {fail_count} FAIL / {len(pedirs)} total")
    sys.exit(0 if fail_count == 0 else 1)


if __name__ == "__main__":
    main()
