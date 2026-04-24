#! /usr/bin/bash
#
# run_bfp_pe_sweep.sh — Batch synthesis sweep over BFP_PE variants.
#
# Supports three RTL source directories:
#   generated/adaptive    — Single BFP_PE variants (no array, no requant).
#                           Top module: BFP_PE  File: BFP_PE.sv
#   generated/pe_array    — Array variants (with or without requant block).
#                           Old (no _blk suffix): BFP_PE.sv → synthesised as BFP_PE
#                           New (with _blk suffix): BFP_PE_16.sv or BFP_PE_64.sv
#                                                   → synthesised as BFP_PE_16/64
#   generated/post_scale  — Post-scale reduction-tree variants (single BFP_PE.sv).
#                           Top module: BFP_PE  File: BFP_PE.sv
#                           Reports max_freq_mhz = 1000 / critical_path_ns.
#
# Usage:
#   ./run_bfp_pe_sweep.sh [options]
#
# Options:
#   --source=adaptive|pe_array|post_scale   RTL source directory (default: post_scale)
#   --skip-sim                              Skip VCD simulation (step 2); use static 10% toggle
#   --skip-verify-syn                       Skip gate-level functional verification (default: skipped)
#   --filter=GLOB                           Only process variants matching GLOB (e.g. '*_vec4')
#   --resume                                Skip variants already listed in bfp_pe_done.txt
#

set -euo pipefail

# ----------------------------------------------------------------
# Tool environment
# ----------------------------------------------------------------
source "$HOME/oss-cad-suite/environment"
export PATH="$HOME/no_backup/opensta-install/bin:$PATH"

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
SKIP_VERIFY_SYN=true    # always true: no verify TB for these designs
FILTER_GLOB="*"
RESUME=false
SOURCE_DIR="post_scale"   # adaptive | pe_array | post_scale

while [[ $# -gt 0 ]]; do
    case "$1" in
        --source=*)          SOURCE_DIR="${1#--source=}"; shift ;;
        --skip-sim)          SKIP_SIM=true; shift ;;
        --skip-verify-syn)   SKIP_VERIFY_SYN=true; shift ;;
        --filter=*)          FILTER_GLOB="${1#--filter=}"; shift ;;
        --resume)            RESUME=true; shift ;;
        *) echo "Unknown argument: $1"; exit 1 ;;
    esac
done

case "$SOURCE_DIR" in
    adaptive|pe_array|post_scale) ;;
    *) echo "ERROR: --source must be 'adaptive', 'pe_array', or 'post_scale', got: $SOURCE_DIR"; exit 1 ;;
esac
export SKIP_SIM SOURCE_DIR

# ----------------------------------------------------------------
# Common synthesis configuration
# (DESIGN_NAME is resolved per-variant inside the loop)
# ----------------------------------------------------------------

CLOCK_PORT="clock"           # BFP_PE uses 'clock', not 'clk_i'
TB_SIM="tb_pt_bfp_pe"
VCD_INSTANCE_SUFFIX=""       # scope: {TB_SIM}/{DESIGN_NAME}
TB_VERIFY=""                 # no RTL verify TB
export CLOCK_PORT TB_SIM VCD_INSTANCE_SUFFIX

declare -a WORKLOADS=("default")
export WORKLOADS

declare -A PREC_MODE=(["default"]="2'b00")
declare -A FP_MODE=(["default"]="2'b00")
export PREC_MODE FP_MODE

declare -a RTL_PARAM_NAMES=()
declare -a TECH_PARAM_NAMES=()
export RTL_PARAM_NAMES TECH_PARAM_NAMES

# Frequency: 500 MHz (2 ns period) drives ABC to optimise timing;
# max_freq_mhz is then reported as 1000/critical_path_ns after STA.
FREQ=500
export FREQ

# Synthesis compile flags
COMPILE_NO_AUTOUNGROUP=""
COMPILE_RETIME=""
COMPILE_CLOCK_GATING="-gate_clock"
export COMPILE_NO_AUTOUNGROUP COMPILE_RETIME COMPILE_CLOCK_GATING

# SDC constraints
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

SDC_IO_DELAY="set_input_delay  {IO_DELAY} -clock CORE_CLK [all_inputs]
set_output_delay {IO_DELAY} -clock CORE_CLK [all_outputs]"
IO_DELAY=0
export SDC_IO_DELAY IO_DELAY

# ----------------------------------------------------------------
# Source stage scripts
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
# Locate RTL variants directory
# ----------------------------------------------------------------
FUSED_DOT_DIR="$(realpath "$PROJ_DIR/../../generated/${SOURCE_DIR}")"
if [[ ! -d "$FUSED_DOT_DIR" ]]; then
    echo "ERROR: RTL source directory not found: $FUSED_DOT_DIR"
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
echo "Found ${#ALL_VARIANTS[@]} variant(s) in ${SOURCE_DIR} matching '$FILTER_GLOB'"

# ----------------------------------------------------------------
# Done-tracking
# ----------------------------------------------------------------
DONE_FILE="$PROJ_DIR/bfp_pe_done.txt"
[[ ! -f "$DONE_FILE" ]] && touch "$DONE_FILE"

# ----------------------------------------------------------------
# Results CSV — format depends on source
# ----------------------------------------------------------------
mkdir -p "$PROJ_DIR/data_to_excel"

if [[ "$SOURCE_DIR" == "adaptive" ]]; then
    MANIFEST="$PROJ_DIR/data_to_excel/adaptive_manifest.csv"
    if [[ ! -f "$MANIFEST" ]]; then
        echo "variant,elem_a,elem_b,scale_type,vec_size,freq_mhz,area_um2,submodule_areas,power_default_W,submodule_powers,critical_path_ns" \
            > "$MANIFEST"
    fi
elif [[ "$SOURCE_DIR" == "post_scale" ]]; then
    MANIFEST="$PROJ_DIR/data_to_excel/post_scale_manifest.csv"
    if [[ ! -f "$MANIFEST" ]]; then
        echo "variant,elem_a,elem_b,scale_type,vec_size,synth_freq_mhz,area_um2,submodule_areas,power_default_W,submodule_powers,critical_path_ns,max_freq_mhz" \
            > "$MANIFEST"
    fi
else
    MANIFEST="$PROJ_DIR/data_to_excel/array_manifest.csv"
    if [[ ! -f "$MANIFEST" ]]; then
        echo "variant,array_size,elem_a,elem_b,scale_type,vec_size,requant_type,blk_size,freq_mhz,area_um2,submodule_areas,power_default_W,submodule_powers,critical_path_ns" \
            > "$MANIFEST"
    fi
fi

# ----------------------------------------------------------------
# Helper: resolve RTL filename and top-level design name per variant.
# Sets globals: RTL_FNAME, DESIGN_NAME
# ----------------------------------------------------------------
resolve_rtl_file() {
    local var_dir="$1"
    if [[ -f "$var_dir/BFP_PE_16.sv" ]]; then
        RTL_FNAME="BFP_PE_16.sv"
        DESIGN_NAME="BFP_PE_16"
    elif [[ -f "$var_dir/BFP_PE_64.sv" ]]; then
        RTL_FNAME="BFP_PE_64.sv"
        DESIGN_NAME="BFP_PE_64"
    else
        RTL_FNAME="BFP_PE.sv"
        DESIGN_NAME="BFP_PE"
    fi
}

# ----------------------------------------------------------------
# Helper: parse variant directory name into metadata fields.
# Sets globals: ARRAY_SIZE, ELEM_A, ELEM_B, SCALE_TYPE, VEC_SIZE,
#               REQUANT_TYPE, BLK_SIZE
#
# Supported formats:
#   adaptive  : {ELEM_A}_{ELEM_B}_{SCALE}_vec{N}
#   pe_array  : {SIZE}_{ELEM_A}_{ELEM_B}_{SCALE}_vec{N}
#               {SIZE}_{ELEM_A}_{ELEM_B}_{SCALE}_vec{N}_{REQUANT}_blk{M}
# ----------------------------------------------------------------
parse_variant_name() {
    local vname="$1"
    ARRAY_SIZE="" ELEM_A="" ELEM_B="" SCALE_TYPE="" VEC_SIZE="" REQUANT_TYPE="" BLK_SIZE=""

    local rest="$vname"

    # Strip array-size prefix (e.g. "4x4_" or "8x8_")
    if [[ "$rest" =~ ^([0-9]+x[0-9]+)_(.+)$ ]]; then
        ARRAY_SIZE="${BASH_REMATCH[1]}"
        rest="${BASH_REMATCH[2]}"
    fi

    # Strip _blk{M} suffix → REQUANT_TYPE is the segment between _vec{N}_ and _blk
    if [[ "$rest" =~ _blk([0-9]+)$ ]]; then
        BLK_SIZE="${BASH_REMATCH[1]}"
        rest="${rest%_blk${BLK_SIZE}}"
        # rest is now like: ELEM_A_ELEM_B_SCALE_vec{N}_REQUANT
        if [[ "$rest" =~ ^(.+)_vec([0-9]+)_(.+)$ ]]; then
            VEC_SIZE="${BASH_REMATCH[2]}"
            REQUANT_TYPE="${BASH_REMATCH[3]}"
            rest="${BASH_REMATCH[1]}"   # ELEM_A_ELEM_B_SCALE
        fi
    else
        # No blk: rest ends with _vec{N}
        VEC_SIZE="${rest##*_vec}"
        rest="${rest%_vec*}"
    fi

    IFS='_' read -ra _parts <<< "$rest"
    ELEM_A="${_parts[0]}"
    ELEM_B="${_parts[1]}"
    SCALE_TYPE="${_parts[2]}"
}

# ----------------------------------------------------------------
# Helper: remove large intermediate files after a finished variant.
# ----------------------------------------------------------------
cleanup_variant() {
    local vid="$1" dname="$2"
    echo "  [cleanup] removing intermediates for $vid"
    rm -rf "$PROJ_DIR/sim-syn/vcd/${vid}"
    rm -f  "$PROJ_DIR/syn/outputs/${vid}/${dname}_mapped.v"
    rm -rf "$PROJ_DIR/sim-syn/logs/${vid}/pp_rtl"
    rm -f  "$PROJ_DIR/sim-syn/logs/${vid}"/*.vvp
    rm -rf "$PROJ_DIR/sta/debug/${vid}"
    rm -rf "$PROJ_DIR/rtl/tb/debug/${vid}"
    echo "  [cleanup] done"
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
    echo "[$run_num/$total]  $variant  (source: $SOURCE_DIR)"
    echo "================================================================"

    # ------ Resume check ------
    if [[ "$RESUME" == true ]] && grep -qxF "$variant" "$DONE_FILE" 2>/dev/null; then
        echo "  already done — skipping"
        continue
    fi

    # ------ Parse metadata ------
    parse_variant_name "$variant"
    echo "  array_size=${ARRAY_SIZE:-n/a}  elem_a=$ELEM_A  elem_b=$ELEM_B  scale=$SCALE_TYPE  vec=$VEC_SIZE  requant=${REQUANT_TYPE:-n/a}  blk=${BLK_SIZE:-n/a}"

    # ------ Resolve RTL file and top-level design name ------
    local_variant_dir="$FUSED_DOT_DIR/$variant"
    resolve_rtl_file "$local_variant_dir"
    export DESIGN_NAME
    echo "  RTL file: ${RTL_FNAME}  design: ${DESIGN_NAME}"

    # ------ Set per-variant variables ------
    RUN_ID="$variant"
    export RUN_ID

    # Path relative to PROJ_DIR (syn.sh prepends PROJ_DIR/)
    RTL_FILES=("../../generated/${SOURCE_DIR}/${variant}/${RTL_FNAME}")
    export RTL_FILES

    VARIANT_RTL="$local_variant_dir/$RTL_FNAME"
    if [[ ! -f "$VARIANT_RTL" ]]; then
        echo "  ERROR: RTL not found: $VARIANT_RTL"
        fails+=("$variant (RTL missing)")
        continue
    fi

    # ------ Step 1: Synthesis ------
    echo "  [step 1] syn (design: $DESIGN_NAME)"
    if ! syn_main; then
        echo "  FAILED: syn"
        fails+=("$variant (syn)")
        continue
    fi

    # ------ Step 2: Generate testbench + VCD simulation ------
    if [[ "${SKIP_SIM}" != "true" ]]; then
        echo "  [step 2] gen_bfp_pe_tb + sim-syn"

        TB_TEMPLATE="$PROJ_DIR/rtl/tb/templates/${TB_SIM}.sv"
        python3 "$PROJ_DIR/gen_bfp_pe_tb.py" "$VARIANT_RTL" "$TB_TEMPLATE"
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
    area=$(grep -m1 "Chip area for top module" "$area_file" 2>/dev/null | sed "s/.*: //")
    submod_areas=$(parse_area_hierarchy "$area_file" "$DESIGN_NAME")

    power_file="$PROJ_DIR/sta/reports/${RUN_ID}/power_default.rpt"
    power=$(awk '/^Total[[:space:]]/ { print $5 }' "$power_file" 2>/dev/null | head -1)
    submod_powers=$(parse_submodule_powers \
        "$PROJ_DIR/sta/reports/${RUN_ID}/power_hierarchy_default.rpt")

    cp_ns="${CP_RESULT:-}"

    # Compute max achievable frequency from critical path
    max_freq_mhz=""
    if [[ -n "$cp_ns" ]] && awk "BEGIN{exit !($cp_ns > 0)}" 2>/dev/null; then
        max_freq_mhz=$(awk "BEGIN{printf \"%.1f\", 1000 / $cp_ns}")
    fi

    echo "  area=${area} um2   power=${power} W   critical_path=${cp_ns} ns   max_freq=${max_freq_mhz} MHz"
    echo "  submodule_areas=${submod_areas}"
    echo "  submodule_powers=${submod_powers}"

    # Append row to manifest (format depends on source)
    if [[ "$SOURCE_DIR" == "adaptive" ]]; then
        echo "${variant},${ELEM_A},${ELEM_B},${SCALE_TYPE},${VEC_SIZE},${FREQ},${area},\"${submod_areas}\",${power},\"${submod_powers}\",${cp_ns}" \
            >> "$MANIFEST"
    elif [[ "$SOURCE_DIR" == "post_scale" ]]; then
        echo "${variant},${ELEM_A},${ELEM_B},${SCALE_TYPE},${VEC_SIZE},${FREQ},${area},\"${submod_areas}\",${power},\"${submod_powers}\",${cp_ns},${max_freq_mhz}" \
            >> "$MANIFEST"
    else
        echo "${variant},${ARRAY_SIZE},${ELEM_A},${ELEM_B},${SCALE_TYPE},${VEC_SIZE},${REQUANT_TYPE},${BLK_SIZE},${FREQ},${area},\"${submod_areas}\",${power},\"${submod_powers}\",${cp_ns}" \
            >> "$MANIFEST"
    fi

    # Mark done
    echo "$variant" >> "$DONE_FILE"

    # Remove large intermediates now that results are captured
    cleanup_variant "$RUN_ID" "$DESIGN_NAME"
done

# ----------------------------------------------------------------
# Summary
# ----------------------------------------------------------------
echo ""
echo "==================================================================="
echo "Sweep complete: $run_num variant(s) processed  (source: $SOURCE_DIR)"
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
