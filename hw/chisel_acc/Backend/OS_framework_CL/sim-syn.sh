#! /usr/bin/bash
#
# Post-synthesis simulation for VCD generation — Icarus Verilog (iverilog/vvp)
# Sourced by out_loop.sh — provides sim_syn_main().
#
# Two modes (controlled by GATE_LEVEL_SIM in tech.sh):
#
#   GATE_LEVEL_SIM="false" (default):
#     Compiles RTL sources + power testbench.
#     The VCD captures port-level toggling; OpenSTA annotates internal
#     node activity from the liberty power tables.
#     This matches the approach used by the POET paper for RTL-VCD flows.
#
#   GATE_LEVEL_SIM="true":
#     Compiles SIM_MODELS + mapped netlist + power testbench.
#     The VCD captures full gate-level switching activity.
#     Requires the full NangateOpenCellLibrary.v in SIM_MODELS.
#
# Outputs (per workload):
#   sim-syn/vcd/{RUN_ID}/{DESIGN_NAME}_{workload}.vcd — switching activity
#   sim-syn/logs/{RUN_ID}/                             — compile + sim logs
#

sim_syn_dir="sim-syn"
tb_dir="rtl/tb"

sim_syn_main () {
    mkdir -p "$sim_syn_dir/vcd/${RUN_ID}"
    mkdir -p "$sim_syn_dir/logs/${RUN_ID}"
    mkdir -p "$tb_dir/debug/${RUN_ID}"

    # ---- Clock half period ----
    local clk_period_half
    clk_period_half=$(echo "scale=5; $clk_period / 2" | bc)
    [[ "$clk_period_half" == .* ]] && clk_period_half="0${clk_period_half}"
    echo "  clk_period_half = ${clk_period_half} ns"

    local dir_loc="${RUN_ID}"

    # ---- Loop over workloads ----
    for workload in "${WORKLOADS[@]}"; do
        echo "  sim-syn: workload=${workload}"

        # ---- Fill power TB template ----
        cp "$tb_dir/templates/${TB_SIM}.sv" "$tb_dir/${TB_SIM}.sv"
        sed -i "s|{CLK_HALF}|${clk_period_half}|g"         "$tb_dir/${TB_SIM}.sv"
        sed -i "s|{DIR_LOC_TEMP}|${dir_loc}|g"              "$tb_dir/${TB_SIM}.sv"
        sed -i "s|{WORKLOAD}|${workload}|g"                  "$tb_dir/${TB_SIM}.sv"
        sed -i "s|{PREC_MODE}|${PREC_MODE[$workload]}|g"    "$tb_dir/${TB_SIM}.sv"
        sed -i "s|{FP_MODE}|${FP_MODE[$workload]}|g"        "$tb_dir/${TB_SIM}.sv"
        sed -i "s|{DESIGN_NAME}|${DESIGN_NAME}|g"           "$tb_dir/${TB_SIM}.sv"
        sed -i "s|{PROJ_DIR}|${PROJ_DIR}|g"                 "$tb_dir/${TB_SIM}.sv"
        for pname in "${RTL_PARAM_NAMES[@]}"; do
            sed -i "s|{${pname}}|${!pname}|g"               "$tb_dir/${TB_SIM}.sv"
        done

        # Save debug copy
        cp "$tb_dir/${TB_SIM}.sv" "$tb_dir/debug/${RUN_ID}/${TB_SIM}_${workload}.sv"

        local compile_log="$sim_syn_dir/logs/${RUN_ID}/compile_${workload}.log"
        local sim_log="$sim_syn_dir/logs/${RUN_ID}/sim_${workload}.log"
        local bin_file="$sim_syn_dir/logs/${RUN_ID}/${TB_SIM}_${workload}.vvp"

        # ---- Compile ----
        if [[ "${GATE_LEVEL_SIM:-false}" == "true" ]]; then
            # Gate-level: SIM_MODELS + mapped netlist + power TB
            echo "  sim-syn: gate-level mode"
            iverilog -g2012 \
                -DGATE_LEVEL_SIM \
                "${SIM_MODELS_RESOLVED[@]}" \
                "$syn_dir/outputs/${RUN_ID}/${DESIGN_NAME}_mapped.v" \
                "$tb_dir/${TB_SIM}.sv" \
                -o "$bin_file" \
                2>&1 | tee "$compile_log"
        else
            # RTL mode: RTL sources + power TB
            echo "  sim-syn: RTL mode (GATE_LEVEL_SIM=false)"
            local rtl_files_args=()
            for rtl_file in "${RTL_FILES[@]}"; do
                rtl_files_args+=("$PROJ_DIR/$rtl_file")
            done
            iverilog -g2012 \
                "${rtl_files_args[@]}" \
                "$tb_dir/${TB_SIM}.sv" \
                -o "$bin_file" \
                2>&1 | tee "$compile_log"
        fi

        if [[ ${PIPESTATUS[0]} -ne 0 ]]; then
            echo "[$RUN_ID] sim_syn FAILED (iverilog compile error)"
            return 1
        fi

        # ---- Simulate (VCD written by $dumpfile in testbench) ----
        vvp "$bin_file" 2>&1 | tee "$sim_log"

        if [[ ${PIPESTATUS[0]} -ne 0 ]]; then
            echo "[$RUN_ID] sim_syn FAILED (vvp error)"
            return 1
        fi

        # ---- Check VCD was produced ----
        local vcd_file="$sim_syn_dir/vcd/${RUN_ID}/${DESIGN_NAME}_${workload}.vcd"
        if [[ ! -f "$vcd_file" ]]; then
            echo "[$RUN_ID] sim_syn FAILED (VCD not produced: $vcd_file)"
            echo "  Verify that the testbench writes:"
            echo "    \$dumpfile(\"sim-syn/vcd/${RUN_ID}/${DESIGN_NAME}_${workload}.vcd\");"
            echo "  with {PROJ_DIR}, {DIR_LOC_TEMP}, {DESIGN_NAME}, {WORKLOAD} placeholders."
            return 1
        fi

        echo "  sim-syn: VCD -> $vcd_file"
    done

    echo "[$RUN_ID] sim_syn OK"
}
