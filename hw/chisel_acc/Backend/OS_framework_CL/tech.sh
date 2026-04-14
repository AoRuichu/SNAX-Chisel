#! /usr/bin/bash

# ================================================================
# TECHNOLOGY CONFIGURATION — NanGate 45nm Open Cell Library
#
# Used by:
#   - syn.sh      : Yosys dfflibmap + abc (Liberty .lib)
#   - sta.sh      : OpenSTA power analysis (Liberty .lib)
#   - verify_syn.sh / sim-syn.sh : iverilog gate-level sim (Verilog models)
#
# Paths can be absolute or relative to TECH_DIR.
# You can use {TECH_PARAM1}, {TECH_PARAM2}, ... placeholders in TECH_DIR,
# SYNTH_LIBS, and SIM_MODELS. Define matching _LIST arrays to sweep them.
# ================================================================

# Base directory for technology files
TECH_DIR="/users/micas/scuycken/Claude/RTL_LLM_Loop_v1.4/OpenSource/PDK/OpenROAD-flow-scripts/flow/platforms/nangate45"

# Liberty file for synthesis (Yosys) and power analysis (OpenSTA)
# Format: .lib (Liberty ASCII — NOT .db compiled format)
SYNTH_LIBS=(
    "lib/NangateOpenCellLibrary_typical.lib"
)

# ================================================================
# GATE-LEVEL SIMULATION MODE
#
# MUST be "true" for valid power analysis.
# RTL-level VCD only captures port switching — all internal node
# activity (flip-flop outputs, combinational nets) is missing, which
# makes power results meaningless for design comparison or optimization.
#
# "true": iverilog compiles SIM_MODELS + mapped netlist + testbench.
#         VCD contains every internal gate/FF transition → accurate power.
#         Requires NangateOpenCellLibrary.v at ${TECH_DIR}/NangateOpenCellLibrary.v
#         See download instructions below.
# ================================================================
GATE_LEVEL_SIM="true"

# Gate-level simulation models (Verilog behavioral — only used when GATE_LEVEL_SIM="true")
# NangateOpenCellLibrary.v contains all 135 cells, auto-generated from the Liberty file
# by OpenSource/PDK/gen_nangate45_verilog.py.  Do NOT add cells_adders.v / cells_clkgate.v
# / cells_latch.v here — those are subsets already covered by NangateOpenCellLibrary.v
# and duplicate module definitions will cause iverilog to error out.
SIM_MODELS=(
    "NangateOpenCellLibrary.v"
)

# ================================================================
# TECHNOLOGY PARAMETERS (optional, sweepable)
#
# Use {TECH_PARAM1}, {TECH_PARAM2}, ... placeholders in the paths above.
# The parameter names and sweep values are configured in config.sh
# (TECH_PARAM_NAMES and matching _LIST arrays).
# ================================================================
