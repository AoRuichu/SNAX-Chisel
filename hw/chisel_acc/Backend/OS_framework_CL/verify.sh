#!/usr/bin/bash
#
# Standalone RTL verification using Icarus Verilog (iverilog/vvp).
# Runs every verification testbench against RTL sources,
# sweeping all RTL parameter combinations × workloads from config.sh.
#
# Usage:  ./verify.sh
#

set -e

PROJ_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
export PROJ_DIR
cd "$PROJ_DIR"

. config.sh

# Validate RTL parameter names are uppercase
for _pname in "${RTL_PARAM_NAMES[@]}"; do
    if [[ "$_pname" != "${_pname^^}" ]]; then
        echo "ERROR: RTL parameter name '$_pname' must be ALL UPPERCASE (e.g. '${_pname^^}')."
        echo "       Rename it in the RTL source, testbenches, and config.sh."
        exit 1
    fi
done

verify_dir="verify_rtl"
tb_dir="rtl/tb"

# ================================================================
# Generate RTL parameter combinations (Cartesian product)
# Only sweeps RTL_PARAM_NAMES — frequency/constraints are irrelevant
# for behavioural RTL verification.
# ================================================================

generate_param_combos() {
    local -a runs=("")
    for axis in "${RTL_PARAM_NAMES[@]}"; do
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

extract_param_val() {
    local run="$1" axis="$2"
    echo " $run " | sed -n "s/.* ${axis}=\([^ ]*\).*/\1/p"
}

mapfile -t param_runs < <(generate_param_combos)

total=0
pass_total=0
fail_total=0

num_tbs=${#TB_VERIFY[@]}

echo ""
echo "========================================================"
echo " RTL Verification — ${num_tbs} TB(s) × ${#param_runs[@]} param combo(s) × ${#WORKLOADS[@]} workload(s)"
echo " Tool: iverilog/vvp"
echo "========================================================"

for tb_name in "${TB_VERIFY[@]}"; do
  for run in "${param_runs[@]}"; do
    # Set parameter variables
    for pname in "${RTL_PARAM_NAMES[@]}"; do
        val=$(extract_param_val "$run" "$pname")
        printf -v "$pname" '%s' "$val"
        export "$pname"
    done

    for workload in "${WORKLOADS[@]}"; do
        run_label=$(echo "${tb_name}_${run}_${workload}" | tr ' =' '_')
        total=$((total + 1))

        echo ""
        echo "--- [${tb_name}] [$run] workload=${workload} ---"

        log_dir="$verify_dir/logs"
        log_file="$log_dir/verify_${run_label}.log"
        mkdir -p "$log_dir"

        # ---- Fill verification TB template ----
        cp "$tb_dir/templates/${tb_name}.sv" "$tb_dir/${tb_name}.sv"
        for pname in "${RTL_PARAM_NAMES[@]}"; do
            sed -i "s|{${pname}}|${!pname}|g" "$tb_dir/${tb_name}.sv"
        done
        sed -i "s|{CLK_HALF}|5.0|g"                             "$tb_dir/${tb_name}.sv"
        sed -i "s|{WORKLOAD}|${workload}|g"                      "$tb_dir/${tb_name}.sv"
        sed -i "s|{PREC_MODE}|${PREC_MODE[$workload]}|g"        "$tb_dir/${tb_name}.sv"
        sed -i "s|{FP_MODE}|${FP_MODE[$workload]}|g"            "$tb_dir/${tb_name}.sv"

        # ---- Build RTL file list ----
        rtl_file_args=()
        for rtl_file in "${RTL_FILES[@]}"; do
            rtl_file_args+=("$PROJ_DIR/$rtl_file")
        done

        # ---- Compile with iverilog ----
        bin_file="$verify_dir/logs/verify_${run_label}.vvp"
        iverilog -g2012 \
            -o "$bin_file" \
            "${rtl_file_args[@]}" \
            "$tb_dir/${tb_name}.sv" \
            2>&1 | tee "${log_file}.compile"

        if [[ ${PIPESTATUS[0]} -ne 0 ]]; then
            echo "  FAIL — iverilog compile error, see ${log_file}.compile"
            fail_total=$((fail_total + 1))
            continue
        fi

        # ---- Simulate ----
        vvp "$bin_file" 2>&1 | tee "$log_file"

        # ---- Check result ----
        if grep -q "ALL TESTS PASSED" "$log_file"; then
            echo "  PASS"
            pass_total=$((pass_total + 1))
        else
            echo "  FAIL — see $log_file"
            fail_total=$((fail_total + 1))
        fi
    done
  done
done

echo ""
echo "========================================================"
echo " RTL Verification Summary"
echo "   PASS : ${pass_total} / ${total}"
echo "   FAIL : ${fail_total} / ${total}"
echo "========================================================"

if [[ $fail_total -gt 0 ]]; then
    exit 1
fi
