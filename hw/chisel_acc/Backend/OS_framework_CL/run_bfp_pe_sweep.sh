#! /usr/bin/bash
#
# run_bfp_pe_sweep.sh — Batch synthesis sweep over all BFP_PE variants.
#
# Iterates every BFP_PE.sv under generated/fused_dot/, runs the full
# OS_framework_CL pipeline (syn → sim-syn → sta) for each variant, and
# aggregates results into data_to_excel/bfp_pe_manifest.csv.
#
# Uses the SAME stage scripts as out_loop.sh (syn.sh, sim-syn.sh, sta.sh)
# with per-variant variable overrides.  All outputs are namespaced by
# the variant directory name, which is used as RUN_ID.
#
# Usage:
#   ./run_bfp_pe_sweep.sh [options]
#
# Options:
#   --skip-sim          Skip VCD simulation (step 3); use static 10% toggle power
#   --skip-verify-syn   Skip gate-level functional verification (default: skipped)
#   --filter=GLOB       Only process variants matching GLOB (e.g. '*_vec4')
#   --resume            Skip variants already listed in bfp_pe_done.txt
#

set -euo pipefail

# ----------------------------------------------------------------
# Resolve project root
# ----------------------------------------------------------------
PROJ_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
export PROJ_DIR
cd "$PROJ_DIR"

# ----------------------------------------------------------------
# Logging
# ----------------------------------------------------------------
LOG_FILE="$PROJ_DIR/bfp_pe_sweep_$(date +%Y%m%d_%H%M%S).log"
exec > >(tee -a "$LOG_FILE") 2>&1
ln -sf "$LOG_FILE" "$PROJ_DIR/bfp_pe_sweep_latest.log"
echo "=== run_bfp_pe_sweep.sh started at $(date) ==="
echo "=== Args: $* ==="
trap 'echo "=== run_bfp_pe_sweep.sh exited with code $? at $(date) ==="' EXIT

# ----------------------------------------------------------------
# Parse CLI arguments
# ----------------------------------------------------------------
SKIP_SIM=false
SKIP_VERIFY_SYN=true    # always true: no verify TB for BFP_PE
FILTER_GLOB="*"
RESUME=false

while [[ $# -gt 0 ]]; do
    case "$1" in
        --skip-sim)          SKIP_SIM=true; shift ;;
        --skip-verify-syn)   SKIP_VERIFY_SYN=true; shift ;;
        --filter=*)          FILTER_GLOB="${1#--filter=}"; shift ;;
        --resume)            RESUME=true; shift ;;
        *) echo "Unknown argument: $1"; exit 1 ;;
    esac
done
export SKIP_SIM

# ----------------------------------------------------------------
# BFP_PE sweep configuration
# (replaces config.sh for BFP_PE variants)
# ----------------------------------------------------------------

DESIGN_NAME="BFP_PE"
CLOCK_PORT="clock"           # BFP_PE uses 'clock', not 'clk_i'
TB_SIM="tb_pt_bfp_pe"
VCD_INSTANCE_SUFFIX=""       # scope: {TB_SIM}/{DESIGN_NAME}  →  tb_pt_bfp_pe/BFP_PE
TB_VERIFY=""                 # no RTL verify TB for BFP_PE
export DESIGN_NAME CLOCK_PORT TB_SIM VCD_INSTANCE_SUFFIX

declare -a WORKLOADS=("default")
export WORKLOADS

# Associative arrays used by sim-syn.sh placeholder substitution
declare -A PREC_MODE=(["default"]="2'b00")
declare -A FP_MODE=(["default"]="2'b00")
export PREC_MODE FP_MODE

# RTL / tech parameters — none for BFP_PE sweep
declare -a RTL_PARAM_NAMES=()
declare -a TECH_PARAM_NAMES=()
export RTL_PARAM_NAMES TECH_PARAM_NAMES

# Frequency
FREQ=500
export FREQ

# Synthesis compile flags (same defaults as config.sh)
COMPILE_NO_AUTOUNGROUP=""
COMPILE_RETIME=""
COMPILE_CLOCK_GATING="-gate_clock"
export COMPILE_NO_AUTOUNGROUP COMPILE_RETIME COMPILE_CLOCK_GATING

# SDC constraints (same values as config.sh)
# Clear any leftover SDC_* from a previous run
for _v in $(compgen -v SDC_); do unset "$_v"; done

SDC_MAX_TRANSITION="set_max_transition {MAX_TRANSITION} [current_design]"
MAX_TRANSITION=0.250

SDC_MAX_FANOUT="set_max_fanout {MAX_FANOUT} [current_design]"
MAX_FANOUT=32

SDC_CLK_UNCERTAINTY_SETUP="set_clock_uncertainty {CLK_UNCERTAINTY_SETUP} -setup [get_clocks CORE_CLK]"
CLK_UNCERTAINTY_SETUP=0.05

SDC_CLK_UNCERTAINTY_HOLD="set_clock_uncertainty {CLK_UNCERTAINTY_HOLD} -hold [get_clocks CORE_CLK]"
CLK_UNCERTAINTY_HOLD=0.025

export SDC_MAX_TRANSITION MAX_TRANSITION
export SDC_MAX_FANOUT MAX_FANOUT
export SDC_CLK_UNCERTAINTY_SETUP CLK_UNCERTAINTY_SETUP
export SDC_CLK_UNCERTAINTY_HOLD CLK_UNCERTAINTY_HOLD

# ----------------------------------------------------------------
# Source stage scripts (same as out_loop.sh)
# ----------------------------------------------------------------
. tech.sh
. syn.sh
. sim-syn.sh
. sta.sh
. data_to_excel.sh

# ----------------------------------------------------------------
# Resolve technology library paths (done once — same lib for all variants)
# ----------------------------------------------------------------
_sub_tech_params() {
    local s="$1"
    for tp in "${TECH_PARAM_NAMES[@]}"; do
        s="${s//\{${tp}\}/${!tp}}"
    done
    echo "$s"
}

resolve_tech_paths() {
    local resolved_dir
    resolved_dir=$(_sub_tech_params "$TECH_DIR")

    SYNTH_LIBS_RESOLVED=()
    for lib in "${SYNTH_LIBS[@]}"; do
        local path
        path=$(_sub_tech_params "$lib")
        [[ "$path" != /* ]] && path="${resolved_dir}/$path"
        if [[ ! -f "$path" ]]; then
            echo "ERROR: Synthesis library not found: $path"
            exit 1
        fi
        SYNTH_LIBS_RESOLVED+=("$path")
    done

    SIM_MODELS_RESOLVED=()
    if [[ "${GATE_LEVEL_SIM:-false}" == "true" ]]; then
        for model in "${SIM_MODELS[@]}"; do
            local path
            path=$(_sub_tech_params "$model")
            [[ "$path" != /* ]] && path="${resolved_dir}/$path"
            if [[ ! -f "$path" ]]; then
                echo "ERROR: Simulation model not found: $path"
                exit 1
            fi
            SIM_MODELS_RESOLVED+=("$path")
        done
    fi

    export SYNTH_LIBS_RESOLVED SIM_MODELS_RESOLVED

    SYNTH_LIBS_TCL=""
    for lib in "${SYNTH_LIBS_RESOLVED[@]}"; do
        SYNTH_LIBS_TCL+="$lib "
    done
    SYNTH_LIBS_TCL="${SYNTH_LIBS_TCL% }"
    export SYNTH_LIBS_TCL
}

resolve_tech_paths

# ----------------------------------------------------------------
# Locate generated RTL variants
# ----------------------------------------------------------------
FUSED_DOT_DIR="$(realpath "$PROJ_DIR/../../generated/fused_dot")"
if [[ ! -d "$FUSED_DOT_DIR" ]]; then
    echo "ERROR: Fused-dot variants directory not found: $FUSED_DOT_DIR"
    exit 1
fi

mapfile -t ALL_VARIANTS < <(
    find "$FUSED_DOT_DIR" -maxdepth 1 -mindepth 1 -type d -name "$FILTER_GLOB" \
    | sort | xargs -I{} basename {}
)

if [[ ${#ALL_VARIANTS[@]} -eq 0 ]]; then
    echo "ERROR: No variants found under $FUSED_DOT_DIR matching '$FILTER_GLOB'"
    exit 1
fi
echo "Found ${#ALL_VARIANTS[@]} BFP_PE variant(s) matching '$FILTER_GLOB'"

# ----------------------------------------------------------------
# Done-tracking and results CSV
# ----------------------------------------------------------------
DONE_FILE="$PROJ_DIR/bfp_pe_done.txt"
[[ ! -f "$DONE_FILE" ]] && touch "$DONE_FILE"

mkdir -p "$PROJ_DIR/data_to_excel"
MANIFEST="$PROJ_DIR/data_to_excel/bfp_pe_manifest.csv"

if [[ ! -f "$MANIFEST" ]]; then
    echo "variant,elem_a,elem_b,scale_type,vec_size,freq_mhz,area_um2,power_default_W" \
        > "$MANIFEST"
fi

# ----------------------------------------------------------------
# Helper: parse variant directory name into metadata fields
# ----------------------------------------------------------------
parse_variant_name() {
    local vname="$1"
    # Format: {ELEM_A}_{ELEM_B}_{SCALE}_vec{N}
    # Example: E4M3_E2M1_UE8M0_vec4  →  elem_a=E4M3 elem_b=E2M1 scale=UE8M0 vec=4
    VEC_SIZE="${vname##*_vec}"
    local prefix="${vname%_vec*}"
    IFS='_' read -ra _parts <<< "$prefix"
    ELEM_A="${_parts[0]}"
    ELEM_B="${_parts[1]}"
    SCALE_TYPE="${_parts[2]}"
}

# ----------------------------------------------------------------
# Main sweep loop
# ----------------------------------------------------------------
total=${#ALL_VARIANTS[@]}
run_num=0
fails=()

for variant in "${ALL_VARIANTS[@]}"; do
    run_num=$(( run_num + 1 ))
    echo ""
    echo "================================================================"
    echo "[$run_num/$total]  $variant"
    echo "================================================================"

    # ------ Resume check ------
    if [[ "$RESUME" == true ]] && grep -qxF "$variant" "$DONE_FILE" 2>/dev/null; then
        echo "  already done — skipping"
        continue
    fi

    # ------ Parse metadata ------
    parse_variant_name "$variant"
    echo "  elem_a=$ELEM_A  elem_b=$ELEM_B  scale=$SCALE_TYPE  vec=$VEC_SIZE"

    # ------ Set per-variant variables ------
    RUN_ID="$variant"
    export RUN_ID

    # RTL path relative to PROJ_DIR (syn.sh prepends PROJ_DIR/)
    BFP_PE_SV="$FUSED_DOT_DIR/$variant/BFP_PE.sv"
    RTL_FILES=("../../generated/fused_dot/${variant}/BFP_PE.sv")
    export RTL_FILES

    if [[ ! -f "$BFP_PE_SV" ]]; then
        echo "  ERROR: BFP_PE.sv not found: $BFP_PE_SV"
        fails+=("$variant (BFP_PE.sv missing)")
        continue
    fi

    # ------ Step 1: Synthesis ------
    echo "  [step 1] syn"
    if ! syn_main; then
        echo "  FAILED: syn"
        fails+=("$variant (syn)")
        continue
    fi

    # ------ Step 2: Generate testbench + VCD simulation ------
    if [[ "${SKIP_SIM}" != "true" ]]; then
        echo "  [step 2] gen_bfp_pe_tb + sim-syn"

        TB_TEMPLATE="$PROJ_DIR/rtl/tb/templates/${TB_SIM}.sv"
        python3 "$PROJ_DIR/gen_bfp_pe_tb.py" "$BFP_PE_SV" "$TB_TEMPLATE"
        if [[ $? -ne 0 ]]; then
            echo "  FAILED: gen_bfp_pe_tb.py"
            fails+=("$variant (gen_tb)")
            continue
        fi

        if ! sim_syn_main; then
            echo "  FAILED: sim-syn"
            fails+=("$variant (sim_syn)")
            continue
        fi
    else
        echo "  [step 2] sim-syn skipped (--skip-sim)"
    fi

    # ------ Step 3: Power analysis ------
    echo "  [step 3] sta"
    if ! sta_main; then
        echo "  FAILED: sta"
        fails+=("$variant (sta)")
        continue
    fi

    # ------ Gather results ------
    area_file="$PROJ_DIR/syn/reports/${RUN_ID}/area_report.rpt"
    area=$(grep -m1 "Chip area for module" "$area_file" 2>/dev/null | sed "s/.*: //")

    power_file="$PROJ_DIR/sta/reports/${RUN_ID}/power_default.rpt"
    power=$(awk '/^Total[[:space:]]/ { print $5 }' "$power_file" 2>/dev/null | head -1)

    echo "  area=${area} um2   power=${power} W"

    # Append row to BFP_PE manifest
    echo "${variant},${ELEM_A},${ELEM_B},${SCALE_TYPE},${VEC_SIZE},${FREQ},${area},${power}" \
        >> "$MANIFEST"

    # Mark done
    echo "$variant" >> "$DONE_FILE"
done

# ----------------------------------------------------------------
# Summary
# ----------------------------------------------------------------
echo ""
echo "==================================================================="
echo "BFP_PE sweep complete: $run_num variant(s) processed"
echo "Results: $MANIFEST"

if [[ ${#fails[@]} -gt 0 ]]; then
    echo ""
    echo "FAILED variants (${#fails[@]}):"
    for f in "${fails[@]}"; do
        echo "  - $f"
    done
    exit 1
fi

echo "All variants succeeded."
