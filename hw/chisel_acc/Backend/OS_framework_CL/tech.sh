#! /usr/bin/bash

# ================================================================
# TECHNOLOGY CONFIGURATION — TSMC 28nm HPC+ (tcbn28hpcplusbwp30p140)
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
TECH_DIR="/users/micas/micas/design/tsmc28hpcplus"

# Liberty file for synthesis (Yosys) and power analysis (OpenSTA)
# Format: .lib (Liberty ASCII — NOT .db compiled format)
# Corner: TT 0.9V 25C
SYNTH_LIBS=(
    "standard_cell_libraries/tcbn28hpcplusbwp30p140-set/tcbn28hpcplusbwp30p140_190a_FE/TSMCHOME/digital/Front_End/timing_power_noise/NLDM/tcbn28hpcplusbwp30p140_180a/tcbn28hpcplusbwp30p140tt0p9v25c.lib"
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
#         Requires Verilog behavioral models for TSMC28 HPC+ cells.
# "false": uses RTL-level VCD (less accurate power, but no sim models needed).
# ================================================================
GATE_LEVEL_SIM="false"

# Gate-level simulation models (Verilog behavioral — only used when GATE_LEVEL_SIM="true")
# Set GATE_LEVEL_SIM="true" and provide the path to TSMC28 Verilog cell models if available.
SIM_MODELS=(
)

# ================================================================
# TECHNOLOGY PARAMETERS (optional, sweepable)
#
# Use {TECH_PARAM1}, {TECH_PARAM2}, ... placeholders in the paths above.
# The parameter names and sweep values are configured in config.sh
# (TECH_PARAM_NAMES and matching _LIST arrays).
# ================================================================
