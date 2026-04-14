#!/usr/bin/bash
#
# Clean all generated outputs from a previous run.
# Preserves: templates, tech.sh, config.sh, RTL sources, testbench templates
#
# Usage:  ./clean.sh
#         ./clean.sh --all   (also removes done.txt and manifest.csv)
#

PROJ_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
cd "$PROJ_DIR"

ALL=false
if [[ "${1:-}" == "--all" ]]; then
    ALL=true
fi

echo "Cleaning OS_framework_CL outputs..."

# Synthesis outputs
rm -rf syn/outputs syn/reports syn/logs syn/synth.ys

# RTL verification
rm -rf verify_rtl/logs
rm -f  rtl/tb/*.sv  # instantiated TBs (templates are kept)

# Post-synthesis verification
rm -rf verify_syn/logs

# Simulation / VCD
rm -rf sim-syn/vcd sim-syn/logs rtl/tb/debug

# OpenSTA power analysis
rm -rf sta/reports sta/logs sta/terminal_logs sta/debug sta/power.tcl

# Logs
rm -f out_loop_*.log out_loop_latest.log
rm -f verify_failures.txt status.txt progress.txt

if [[ "$ALL" == true ]]; then
    rm -f done.txt neg_slack.txt
    rm -rf data_to_excel
    echo "  (--all: also removed done.txt and data_to_excel/)"
fi

# Recreate empty directories
mkdir -p syn/{outputs,reports,logs}
mkdir -p verify_rtl/logs
mkdir -p verify_syn/logs
mkdir -p sim-syn/{vcd,logs}
mkdir -p sta/{reports,logs,terminal_logs,debug}
mkdir -p rtl/tb/debug
[[ "$ALL" == true ]] && mkdir -p data_to_excel

echo "Done."
