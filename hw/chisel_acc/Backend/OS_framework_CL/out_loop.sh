#! /usr/bin/bash
#
# OS_framework_CL — Open Source EDA flow orchestrator
# Equivalent to SV_framework_CL but uses open-source tools:
#
#   Synthesis    : Yosys  (replaces Synopsys Design Compiler)
#   Verification : iverilog/vvp  (replaces ModelSim)
#   Power        : OpenSTA  (replaces Synopsys PrimeTime)
#   PDK          : NanGate 45nm Open Cell Library
#
# Pipeline steps (per sweep point):
#   1. syn          — Yosys synthesis → gate-level netlist + area report
#   2. verify_syn   — iverilog gate-level verification (skipped if GATE_LEVEL_SIM=false)
#   3. sim-syn      — iverilog VCD generation (RTL or gate-level mode)
#   4. sta          — OpenSTA VCD-annotated power analysis
#
# Usage:
#   ./out_loop.sh [options]
#
# Options:
#   --verify-only       Run verify.sh (RTL verification) and exit
#   --skip-verify-syn   Skip step 2 (gate-level functional verification)
#   --skip-sim          Skip step 3 (VCD simulation); use static power in OpenSTA
#   --start-step=STEP   Resume from STEP {syn|verify_syn|sim_syn|sta} for the first unfinished run
#

# ----------------------------------------------------------------
# Resolve project root and move there so all relative paths work
# ----------------------------------------------------------------
PROJ_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
export PROJ_DIR
cd "$PROJ_DIR"

# ----------------------------------------------------------------
# Log all stdout+stderr to a timestamped file (and still print to terminal)
# ----------------------------------------------------------------
LOG_FILE="$PROJ_DIR/out_loop_$(date +%Y%m%d_%H%M%S).log"
exec > >(tee -a "$LOG_FILE") 2>&1
ln -sf "$LOG_FILE" "$PROJ_DIR/out_loop_latest.log"
echo "=== OS_framework_CL out_loop.sh started at $(date) ==="
echo "=== Args: $* ==="
trap 'echo "=== out_loop.sh exited with code $? at $(date) ==="' EXIT

# ----------------------------------------------------------------
# Parse CLI arguments
# ----------------------------------------------------------------
VERIFY_ONLY=false
SKIP_VERIFY_SYN=false
SKIP_SIM_CLI=false
START_STEP=""

while [[ $# -gt 0 ]]; do
    case "$1" in
        --verify-only)     VERIFY_ONLY=true; shift ;;
        --skip-verify-syn) SKIP_VERIFY_SYN=true; shift ;;
        --skip-sim)        SKIP_SIM_CLI=true; shift ;;
        --start-step=*)    START_STEP="${1#--start-step=}"; shift ;;
        *) echo "Unknown argument: $1"; exit 1 ;;
    esac
done

# ----------------------------------------------------------------
# Verify-only mode: run verify.sh and exit
# ----------------------------------------------------------------
if [[ "$VERIFY_ONLY" == true ]]; then
    _progress_file="$PROJ_DIR/progress.txt"
    > "$_progress_file"
    _t0=$SECONDS
    bash "$PROJ_DIR/verify.sh"
    _rc=$?
    _elapsed=$(( SECONDS - _t0 ))
    printf "[verify] finished in %dm%ds\n" $((_elapsed/60)) $((_elapsed%60)) >> "$_progress_file"
    if [[ $_rc -ne 0 ]]; then
        echo "FAILED verify" > "$PROJ_DIR/status.txt"
        _fail_file="$PROJ_DIR/verify_failures.txt"
        > "$_fail_file"
        for _log in "$PROJ_DIR"/verify_rtl/logs/verify_*.log; do
            [[ ! -f "$_log" ]] && continue
            _logname=$(basename "$_log")
            _fails=$(grep -n "^FAIL" "$_log" | head -20)
            _summary=$(grep "^Verification complete:" "$_log")
            if [[ -n "$_fails" || -n "$_summary" ]]; then
                echo "--- $_logname ---" >> "$_fail_file"
                [[ -n "$_fails" ]]   && echo "$_fails" >> "$_fail_file"
                [[ -n "$_summary" ]] && echo "$_summary" >> "$_fail_file"
                echo "" >> "$_fail_file"
            fi
        done
    fi
    exit $_rc
fi

# Step ordering for --start-step
declare -A STEP_ORDER=([syn]=1 [verify_syn]=2 [sim_syn]=3 [sta]=4)

if [[ -n "$START_STEP" && -z "${STEP_ORDER[$START_STEP]+x}" ]]; then
    echo "ERROR: Invalid --start-step value '$START_STEP'. Must be one of: syn, verify_syn, sim_syn, sta"
    exit 1
fi

# Clear any SDC_* variables from a previous run
for _v in $(compgen -v SDC_); do unset "$_v"; done

# Load configuration and stage scripts
. config.sh
# --skip-sim CLI flag overrides SKIP_SIM from config.sh
[[ "$SKIP_SIM_CLI" == true ]] && SKIP_SIM=true
export SKIP_SIM
. tech.sh
. syn.sh
. verify_syn.sh
. sim-syn.sh
. sta.sh
. data_to_excel.sh

# ----------------------------------------------------------------
# Validate parameter names are uppercase
# ----------------------------------------------------------------
for _pname in "${RTL_PARAM_NAMES[@]}"; do
    if [[ "$_pname" != "${_pname^^}" ]]; then
        echo "ERROR: RTL parameter name '$_pname' must be ALL UPPERCASE (e.g. '${_pname^^}')."
        exit 1
    fi
done
for _pname in "${TECH_PARAM_NAMES[@]}"; do
    if [[ "$_pname" != "${_pname^^}" ]]; then
        echo "ERROR: Tech parameter name '$_pname' must be ALL UPPERCASE."
        exit 1
    fi
done

# ================================================================
# Technology path resolution (called per-run to substitute TECH_PARAM* placeholders)
# ================================================================
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
                echo "       Set GATE_LEVEL_SIM=\"false\" in tech.sh to use RTL-level VCD instead."
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

    echo "Technology: ${#SYNTH_LIBS_RESOLVED[@]} lib(s), ${#SIM_MODELS_RESOLVED[@]} sim model(s)  [GATE_LEVEL_SIM=${GATE_LEVEL_SIM:-false}]"
}

# ================================================================
# Build the ordered list of all sweep axes.
# ================================================================
_sdc_axes=()
for var in $(compgen -v SDC_); do
    _sdc_axes+=("${var#SDC_}")
done

AXES=(
    FREQ
    "${TECH_PARAM_NAMES[@]}"
    "${_sdc_axes[@]}"
    COMPILE_NO_AUTOUNGROUP
    COMPILE_RETIME
    COMPILE_CLOCK_GATING
    "${RTL_PARAM_NAMES[@]}"
)
export AXES

# ================================================================
# Helper: extract a named axis value from a run string.
# ================================================================
extract_val() {
    local run="$1"
    local axis="$2"
    local padded=" $run "
    if [[ "$padded" == *" ${axis}="* ]]; then
        echo "$padded" | sed -n "s/.* ${axis}=\([^ ]*\).*/\1/p"
    else
        local list_var="${axis}_LIST[0]"
        echo "${!list_var}"
    fi
}

# ================================================================
# Generate all runs
# ================================================================
generate_cartesian() {
    local -a runs=("")
    for axis in "${AXES[@]}"; do
        local list_var="${axis}_LIST[@]"
        local -a values=("${!list_var}")
        [[ ${#values[@]} -eq 0 ]] && continue
        local -a new_runs=()
        for existing in "${runs[@]}"; do
            for val in "${values[@]}"; do
                local entry="${axis}=${val}"
                if [[ -z "$existing" ]]; then
                    new_runs+=("$entry")
                else
                    new_runs+=("$existing $entry")
                fi
            done
        done
        runs=("${new_runs[@]}")
    done
    printf '%s\n' "${runs[@]}"
}

fill_defaults() {
    local run="$1"
    local filled=""
    for axis in "${AXES[@]}"; do
        local val
        val=$(extract_val "$run" "$axis")
        filled="${filled}${filled:+ }${axis}=${val}"
    done
    echo "$filled"
}

if [[ "$SWEEP_MODE" == "explicit" ]]; then
    all_runs=()
    for run in "${EXPLICIT_RUNS[@]}"; do
        all_runs+=("$(fill_defaults "$run")")
    done
else
    mapfile -t all_runs < <(generate_cartesian)
fi

total_runs=${#all_runs[@]}
echo "Sweep mode: ${SWEEP_MODE} — ${total_runs} run(s)"

# ================================================================
# Done tracking
# ================================================================
done_file="done.txt"
[[ ! -f "$done_file" ]] && touch "$done_file"

# ================================================================
# Initialise manifest CSV (header row)
# ================================================================
mkdir -p data_to_excel
manifest_file="data_to_excel/manifest.csv"

if [[ ! -f "$manifest_file" ]]; then
    header="run_id"
    for axis in "${AXES[@]}"; do
        header="${header},${axis}"
    done
    header="${header},area,area_hierarchy"
    for wl in "${WORKLOADS[@]}"; do
        header="${header},power_${wl},power_${wl}_hierarchy"
    done
    echo "$header" > "$manifest_file"
fi

# ================================================================
# Count remaining runs
# ================================================================
runs_remaining=$total_runs
_rn=0
for _r in "${all_runs[@]}"; do
    _rn=$(( _rn + 1 ))
    _rid=$(printf "run_%03d" "$_rn")
    if grep -qxF "$_rid" "$done_file" 2>/dev/null; then
        runs_remaining=$(( runs_remaining - 1 ))
    fi
done
unset _rn _rid _r

# ================================================================
# Progress / status files (read by synthesis_loop.py / CygniLink)
# ================================================================
progress_file="$PROJ_DIR/progress.txt"
> "$progress_file"
> "$PROJ_DIR/status.txt"

_progress() { echo "$1" >> "$progress_file"; }

_announce() {
    local step="$1" elapsed="$2"
    _progress "$(printf "[%s] %s finished in %dm%ds  (%d sweep point(s) remaining)" \
        "$RUN_ID" "$step" $((elapsed/60)) $((elapsed%60)) "$runs_remaining")"
}

# ================================================================
# Main loop over all sweep runs
# ================================================================
run_number=0
for run in "${all_runs[@]}"; do
    run_number=$((run_number + 1))
    RUN_ID=$(printf "run_%03d" "$run_number")
    export RUN_ID

    # Set each axis as a named variable
    for axis in "${AXES[@]}"; do
        val=$(extract_val "$run" "$axis")
        printf -v "$axis" '%s' "$val"
        export "$axis"
    done

    freq=$FREQ  # legacy alias for stage scripts

    # Skip already-done runs
    if grep -qxF "$RUN_ID" "$done_file" 2>/dev/null; then
        echo "[$RUN_ID] already done — skipping"
        continue
    fi

    _progress "[$RUN_ID] starting  $run"

    resolve_tech_paths

    # Determine which step to start from
    local_start=1
    if [[ -n "$START_STEP" ]]; then
        local_start=${STEP_ORDER[$START_STEP]}
        START_STEP=""  # only applies to first unfinished run
    fi

    # ---- Step 1: syn ----
    if [[ $local_start -le 1 ]]; then
        _t0=$SECONDS
        syn_main
        _syn_rc=$?
        if [[ $_syn_rc -ne 0 ]]; then
            echo "FAILED $RUN_ID syn" > "$PROJ_DIR/status.txt"
            exit 1
        fi
        _announce "syn" $(( SECONDS - _t0 ))
    fi

    # ---- Step 2: verify_syn (optional) ----
    if [[ $local_start -le 2 && "$SKIP_VERIFY_SYN" != true ]]; then
        _t0=$SECONDS
        verify_syn_main
        if [[ $? -ne 0 ]]; then
            echo "FAILED $RUN_ID verify_syn" > "$PROJ_DIR/status.txt"
            exit 1
        fi
        _announce "verify_syn" $(( SECONDS - _t0 ))
    fi

    # ---- Step 3: sim_syn (skipped when SKIP_SIM=true) ----
    if [[ $local_start -le 3 && "${SKIP_SIM:-false}" != "true" ]]; then
        _t0=$SECONDS
        sim_syn_main
        if [[ $? -ne 0 ]]; then
            echo "FAILED $RUN_ID sim_syn" > "$PROJ_DIR/status.txt"
            exit 1
        fi
        _announce "sim_syn" $(( SECONDS - _t0 ))
    elif [[ "${SKIP_SIM:-false}" == "true" ]]; then
        echo "  sim_syn: skipped (SKIP_SIM=true — static power mode)"
    fi

    # ---- Step 4: sta ----
    if [[ $local_start -le 4 ]]; then
        _t0=$SECONDS
        sta_main
        if [[ $? -ne 0 ]]; then
            echo "FAILED $RUN_ID sta" > "$PROJ_DIR/status.txt"
            exit 1
        fi
        _announce "sta" $(( SECONDS - _t0 ))
    fi

    gather_data

    runs_remaining=$(( runs_remaining - 1 ))
    echo "$RUN_ID" >> "$done_file"
done

put_data_in_excel

_progress "All runs complete."
echo "=== Results: data_to_excel/manifest.csv ==="
