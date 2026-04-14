#! /usr/bin/bash

# ================================================================
# SECTION 1: DESIGN
# Edit this section for each new design.
# ================================================================

DESIGN_NAME="mac_8bit"
CLOCK_PORT="clk_i"

# RTL source files, listed in dependency order (dependencies first)
RTL_FILES=(
    "rtl/src/mac_8bit.v"
)

# Testbench basename — template must exist at rtl/tb/templates/${TB_SIM}.sv
# The testbench must dump VCDs to: sim-syn/vcd/${RUN_ID}/${DESIGN_NAME}_${workload}.vcd
TB_SIM="tb_pt_mac_8bit"

# DUT instance suffix: VCD strip path = ${TB_SIM}/${DESIGN_NAME}${VCD_INSTANCE_SUFFIX}
VCD_INSTANCE_SUFFIX="0"

# Verification testbench basename — template at rtl/tb/templates/${TB_VERIFY}.sv
TB_VERIFY="tb_verify_mac_8bit"

# Set to "true" to skip VCD simulation (step 3) and run static-only power analysis.
# Useful when no testbench exists for the design (e.g. bare Chisel-generated RTLs).
# Power numbers will use a default 10% toggle-rate; area and timing are unaffected.
SKIP_SIM=true

# ================================================================
# SECTION 2: SWEEP MODE
# "cartesian" — sweeps all combinations of the axes in Section 4
# "explicit"  — runs only the hand-picked configs in Section 5
# ================================================================

SWEEP_MODE="cartesian"

# ================================================================
# SECTION 4: SWEEP AXES
#
# Each axis is an array. Single value = fixed. Multiple values = swept.
# The framework generates all combinations (cartesian) or uses
# explicit runs (explicit mode). New RTL parameters can be added
# by following the pattern in "RTL parameters" below.
# ================================================================

# --- Frequency (MHz) ---
declare -a FREQ_LIST=(500)

# --- Technology parameters (optional, sweepable) ---
# Names must be TECH_PARAM1, TECH_PARAM2, etc.
# Use {TECH_PARAM1} etc. as placeholders in tech.sh paths.
# For each name listed, define a matching <NAME>_LIST array.
declare -a TECH_PARAM_NAMES=()

#declare -a TECH_PARAM_NAMES=("TECH_PARAM1" "TECH_PARAM2")
#declare -a TECH_PARAM1_LIST=("value_a" "value_b")
#declare -a TECH_PARAM2_LIST=("value_x" "value_y")

# --- RTL parameters ---
# Names must match 'parameter' declarations in the top-level RTL module.
# For each name in RTL_PARAM_NAMES, define a matching <NAME>_LIST array.
declare -a RTL_PARAM_NAMES=()

# --- Synthesis constraints (optional, sweepable) ---
# Each SDC_<NAME> defines a TCL command for constraints.sdc.
# {<NAME>} in the command is replaced with the sweep value at runtime.
# Define a <NAME>_LIST array to set/sweep the value.
# Comment out or remove any SDC_<NAME> + its _LIST to disable that constraint.

SDC_MAX_TRANSITION="set_max_transition {MAX_TRANSITION} [current_design]"
declare -a MAX_TRANSITION_LIST=(0.250)

SDC_MAX_FANOUT="set_max_fanout {MAX_FANOUT} [current_design]"
declare -a MAX_FANOUT_LIST=(32)

SDC_CLK_UNCERTAINTY_SETUP="set_clock_uncertainty {CLK_UNCERTAINTY_SETUP} -setup [get_clocks CORE_CLK]"
declare -a CLK_UNCERTAINTY_SETUP_LIST=(0.05)

SDC_CLK_UNCERTAINTY_HOLD="set_clock_uncertainty {CLK_UNCERTAINTY_HOLD} -hold [get_clocks CORE_CLK]"
declare -a CLK_UNCERTAINTY_HOLD_LIST=(0.025)

#SDC_IO_DELAY_PCT="set io_delay_pct {IO_DELAY_PCT}; set_input_delay -max [expr \$core_clock_period / \$io_delay_pct] -clock CORE_CLK [all_inputs]; set_output_delay -max [expr \$core_clock_period / \$io_delay_pct] -clock CORE_CLK [all_outputs]"
#declare -a IO_DELAY_PCT_LIST=(10)

# --- Synthesis compile flags ---
# Each flag is an independent axis: "" = off (default), string = on.
# To sweep: e.g. COMPILE_NO_AUTOUNGROUP_LIST=("" "-no_auto_ungroup")
#
#   -no_auto_ungroup  : keep hierarchy (prevents flattening sub-modules)
#   -retime           : allow registers to move across combinational logic
#   -gate_clock       : insert clock-gating cells to reduce dynamic power
declare -a COMPILE_NO_AUTOUNGROUP_LIST=("")
declare -a COMPILE_RETIME_LIST=("")
declare -a COMPILE_CLOCK_GATING_LIST=("-gate_clock")

# ================================================================
# SECTION 5: EXPLICIT RUNS  (only used when SWEEP_MODE="explicit")
#
# List each run as space-separated KEY=VALUE pairs.
# Any axis not listed takes the first value from its *_LIST above.
# ================================================================

declare -a EXPLICIT_RUNS=(
    "FREQ=500"
)

# ================================================================
# SECTION 6: WORKLOADS
# ================================================================

declare -a WORKLOADS=("default")
declare -A PREC_MODE=(["default"]="2'b00")
declare -A FP_MODE=(["default"]="2'b00")
