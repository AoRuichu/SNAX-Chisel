#! /usr/bin/bash
#
# Synthesis stage — Yosys + NanGate 45nm Liberty
# Sourced by out_loop.sh — provides syn_main().
#
# Tool: yosys
# Template: syn/templates/synth.ys → syn/synth.ys (filled per run)
#
# Outputs:
#   syn/outputs/{RUN_ID}/{DESIGN_NAME}_mapped.v   — gate-level netlist
#   syn/reports/{RUN_ID}/area_report.rpt           — Yosys stat output (area)
#   syn/logs/{RUN_ID}/syn.log                      — full Yosys log
#

syn_dir="syn"

syn_main () {
    # Create output directories for this run
    mkdir -p "$syn_dir/reports/${RUN_ID}"
    mkdir -p "$syn_dir/outputs/${RUN_ID}"
    mkdir -p "$syn_dir/logs/${RUN_ID}"

    # Copy template
    cp "$syn_dir/templates/synth.ys" "$syn_dir/synth.ys"

    # ---- Liberty file (first entry from SYNTH_LIBS_RESOLVED) ----
    local lib_file="${SYNTH_LIBS_RESOLVED[0]}"

    # ---- Clock period (ns) ----
    clk_period=$(echo "scale=4; 1000 / $FREQ" | bc)
    clk_period_half=$(echo "scale=4; $clk_period / 2" | bc)
    # Ensure leading zero for values < 1 (bc omits it)
    [[ "$clk_period" == .* ]] && clk_period="0${clk_period}"
    [[ "$clk_period_half" == .* ]] && clk_period_half="0${clk_period_half}"
    echo "  clk_period = ${clk_period} ns"

    # ---- Build read_verilog commands ----
    local read_cmds=""
    for rtl_file in "${RTL_FILES[@]}"; do
        read_cmds+="read_verilog -sv ${PROJ_DIR}/${rtl_file}"$'\n'
    done

    # ---- Build chparam commands for RTL parameters ----
    local chparam_cmds=""
    for pname in "${RTL_PARAM_NAMES[@]}"; do
        chparam_cmds+="chparam -set ${pname} ${!pname}"$'\n'
    done

    # ---- Synthesis flags ----
    # COMPILE_NO_AUTOUNGROUP: non-empty → keep hierarchy (synth -noflatten)
    local synth_flags=""
    if [[ -n "${COMPILE_NO_AUTOUNGROUP:-}" ]]; then
        synth_flags="-noflatten"
    fi

    # ---- ABC flags ----
    # COMPILE_RETIME: non-empty → enable register retiming
    local abc_flags=""
    if [[ -n "${COMPILE_RETIME:-}" ]]; then
        abc_flags="-retime"
    fi
    # COMPILE_CLOCK_GATING: Yosys clock gating is not implemented in this framework.
    # The -gate_clock flag (DC equivalent) is accepted but has no Yosys mapping here.
    # Clock gating can be added via Yosys clkgateopt in a future enhancement.

    # ---- Substitute placeholders in synth.ys ----
    # Use perl for multi-line substitution (read_cmds and chparam_cmds may be multi-line)
    perl -i -0pe "s|\{READ_VERILOG_CMDS\}|${read_cmds}|g"   "$syn_dir/synth.ys"
    perl -i -0pe "s|\{CHPARAM_CMDS\}|${chparam_cmds}|g"     "$syn_dir/synth.ys"
    sed -i "s|{DESIGN_NAME}|${DESIGN_NAME}|g"                "$syn_dir/synth.ys"
    sed -i "s|{LIB_FILE}|${lib_file}|g"                      "$syn_dir/synth.ys"
    sed -i "s|{SYNTH_FLAGS}|${synth_flags}|g"                "$syn_dir/synth.ys"
    sed -i "s|{ABC_FLAGS}|${abc_flags}|g"                    "$syn_dir/synth.ys"
    sed -i "s|{PROJ_DIR}|${PROJ_DIR}|g"                      "$syn_dir/synth.ys"
    sed -i "s|{RUN_ID}|${RUN_ID}|g"                          "$syn_dir/synth.ys"

    # Save debug copy of the filled script
    cp "$syn_dir/synth.ys" "$syn_dir/logs/${RUN_ID}/synth_filled.ys"

    # ---- Run Yosys ----
    yosys "$syn_dir/synth.ys" 2>&1 | tee "$syn_dir/logs/${RUN_ID}/syn.log"
    local yosys_rc=${PIPESTATUS[0]}

    # ---- Check for errors ----
    if [[ $yosys_rc -ne 0 ]]; then
        echo "[$RUN_ID] syn FAILED (yosys exit code $yosys_rc)"
        return 1
    fi
    if grep -q "^ERROR:" "$syn_dir/logs/${RUN_ID}/syn.log" 2>/dev/null; then
        echo "[$RUN_ID] syn FAILED (ERROR in Yosys log)"
        return 1
    fi

    # ---- Verify netlist was written ----
    local netlist="$syn_dir/outputs/${RUN_ID}/${DESIGN_NAME}_mapped.v"
    if [[ ! -f "$netlist" ]]; then
        echo "[$RUN_ID] syn FAILED (mapped netlist not produced: $netlist)"
        return 1
    fi

    echo "[$RUN_ID] syn OK  (area_report: $syn_dir/reports/${RUN_ID}/area_report.rpt)"
}
