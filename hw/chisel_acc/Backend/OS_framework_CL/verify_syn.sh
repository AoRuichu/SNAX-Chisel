#! /usr/bin/bash
#
# Post-synthesis gate-level verification using Icarus Verilog (iverilog/vvp).
# Sourced by out_loop.sh — provides verify_syn_main().
#
# Requires GATE_LEVEL_SIM="true" in tech.sh AND the full NangateOpenCellLibrary.v.
# When unavailable, the step is skipped (use --skip-verify-syn to always skip).
#
# For each workload, compiles SIM_MODELS + mapped netlist + verification TB,
# runs the simulation, and fails if "ALL TESTS PASSED" is not found.
#

verify_syn_dir="verify_syn"

verify_syn_main () {
    local tb_top="${TB_VERIFY[0]}"
    local log_dir="$verify_syn_dir/logs/${RUN_ID}"
    local tb_file="rtl/tb/${tb_top}.sv"
    mkdir -p "$log_dir"

    # ---- Check gate-level sim availability ----
    if [[ "${GATE_LEVEL_SIM:-false}" != "true" ]]; then
        echo "  verify_syn: SKIP (GATE_LEVEL_SIM=false in tech.sh)"
        echo "  Set GATE_LEVEL_SIM=\"true\" and provide NangateOpenCellLibrary.v for gate-level verification."
        return 0
    fi

    # ---- Clock half period ----
    local clk_half
    clk_half=$(echo "scale=5; $clk_period / 2" | bc)
    [[ "$clk_half" == .* ]] && clk_half="0${clk_half}"

    for workload in "${WORKLOADS[@]}"; do
        echo "  verify_syn: workload=${workload}"

        # ---- Fill verification TB template ----
        cp "rtl/tb/templates/${tb_top}.sv" "$tb_file"
        for pname in "${RTL_PARAM_NAMES[@]}"; do
            sed -i "s|{${pname}}|${!pname}|g" "$tb_file"
        done
        sed -i "s|{CLK_HALF}|${clk_half}|g"                   "$tb_file"
        sed -i "s|{WORKLOAD}|${workload}|g"                    "$tb_file"
        sed -i "s|{PREC_MODE}|${PREC_MODE[$workload]}|g"      "$tb_file"
        sed -i "s|{FP_MODE}|${FP_MODE[$workload]}|g"          "$tb_file"

        local log_file="$log_dir/verify_syn_${workload}.log"
        local bin_file="$log_dir/verify_syn_${workload}.vvp"

        # ---- Compile: sim models + mapped netlist + TB ----
        local compile_log="$log_dir/verify_syn_${workload}.compile"
        iverilog -g2012 \
            -DGATE_LEVEL_SIM \
            "${SIM_MODELS_RESOLVED[@]}" \
            "$syn_dir/outputs/${RUN_ID}/${DESIGN_NAME}_mapped.v" \
            "$tb_file" \
            -o "$bin_file" \
            2>&1 | tee "$compile_log"

        if [[ ${PIPESTATUS[0]} -ne 0 ]]; then
            echo "[$RUN_ID] verify_syn FAILED (iverilog compile error)"
            return 1
        fi

        # ---- Simulate ----
        vvp "$bin_file" 2>&1 | tee "$log_file"

        # ---- Check result ----
        if ! grep -q "ALL TESTS PASSED" "$log_file"; then
            echo "[$RUN_ID] verify_syn FAILED"
            return 1
        fi
        echo "  verify_syn: PASSED"
    done
    echo "[$RUN_ID] verify_syn OK"
}
