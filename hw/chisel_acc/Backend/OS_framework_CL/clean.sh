#!/usr/bin/env bash
# clean.sh — remove all runtime-generated files to free disk space.
# Keeps: source RTL, templates, scripts, config, and data_to_excel results.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

echo "Cleaning runtime-generated files in: $SCRIPT_DIR"
echo ""

# Helper: wipe contents of a directory but keep the directory itself
wipe_dir() {
    local dir="$SCRIPT_DIR/$1"
    if [ -d "$dir" ]; then
        echo "  Clearing $1/ ..."
        rm -rf "$dir"/*/
        rm -f  "$dir"/*
    fi
}

# ── Synthesis outputs ────────────────────────────────────────────────────────
wipe_dir "syn/reports"    # per-variant area reports
wipe_dir "syn/outputs"    # per-variant mapped netlists (.v)
wipe_dir "syn/logs"       # yosys log files

# ── Static timing / power analysis outputs ──────────────────────────────────
wipe_dir "sta/reports"    # per-variant power reports
wipe_dir "sta/debug"      # per-variant generated TCL debug scripts
wipe_dir "sta/logs"       # OpenSTA log files
wipe_dir "sta/terminal_logs"  # terminal capture logs

# ── Post-synthesis simulation ────────────────────────────────────────────────
wipe_dir "sim-syn/logs"   # simulation logs
wipe_dir "sim-syn/vcd"    # VCD waveform dumps (can be very large)

# ── RTL / netlist verification ───────────────────────────────────────────────
wipe_dir "verify_rtl/logs"
wipe_dir "verify_syn/logs"

# ── Runtime status markers ───────────────────────────────────────────────────
rm -f "$SCRIPT_DIR/done.txt"
rm -f "$SCRIPT_DIR/status.txt"
rm -f "$SCRIPT_DIR/progress.txt"

echo ""
echo "Done. Disk usage after clean:"
du -sh "$SCRIPT_DIR"
