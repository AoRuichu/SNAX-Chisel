#! /usr/bin/bash
#
# Power analysis stage — OpenSTA (replaces PrimeTime in SV_framework_CL)
# Sourced by out_loop.sh — provides sta_main().
#
# For each workload:
#   1. Fills sta/templates/power.tcl with run-specific values
#   2. Runs: sta power.tcl
#   3. Checks for errors in the log
#
# Outputs:
#   sta/reports/{RUN_ID}/power_{workload}.rpt           — flat total power
#   sta/reports/{RUN_ID}/power_hierarchy_{workload}.rpt — per-instance breakdown
#   sta/terminal_logs/{RUN_ID}/sta_power_{workload}.log — full OpenSTA output
#

sta_dir="sta"

sta_main () {
    mkdir -p "$sta_dir/reports/${RUN_ID}"
    mkdir -p "$sta_dir/logs/${RUN_ID}"
    mkdir -p "$sta_dir/terminal_logs/${RUN_ID}"
    mkdir -p "$sta_dir/debug/${RUN_ID}"

    # ---- Liberty library: build read_liberty commands ----
    local read_liberty_cmds=""
    for lib in "${SYNTH_LIBS_RESOLVED[@]}"; do
        read_liberty_cmds+="read_liberty ${lib}"$'\n'
    done

    # ---- Clock period (reuse clk_period computed by syn.sh, recalculate if needed) ----
    if [[ -z "${clk_period:-}" ]]; then
        clk_period=$(echo "scale=4; 1000 / $FREQ" | bc)
        [[ "$clk_period" == .* ]] && clk_period="0${clk_period}"
    fi
    local clk_half
    clk_half=$(echo "scale=4; $clk_period / 2" | bc)
    [[ "$clk_half" == .* ]] && clk_half="0${clk_half}"

    for workload in "${WORKLOADS[@]}"; do
        echo "  sta: workload=${workload}"

        # ---- Select power template (VCD-annotated vs static) ----
        if [[ "${SKIP_SIM:-false}" == "true" ]]; then
            cp "$sta_dir/templates/power_no_vcd.tcl" "$sta_dir/power.tcl"
        else
            cp "$sta_dir/templates/power.tcl" "$sta_dir/power.tcl"
        fi

        # Liberty commands (multi-line)
        perl -i -0pe "s|\{READ_LIBERTY_CMDS\}|${read_liberty_cmds}|g" "$sta_dir/power.tcl"

        # Design / run identifiers
        sed -i "s|{DESIGN_NAME}|${DESIGN_NAME}|g"               "$sta_dir/power.tcl"
        sed -i "s|{PROJ_DIR}|${PROJ_DIR}|g"                     "$sta_dir/power.tcl"
        sed -i "s|{RUN_ID}|${RUN_ID}|g"                         "$sta_dir/power.tcl"
        sed -i "s|{WORKLOAD}|${workload}|g"                      "$sta_dir/power.tcl"

        # VCD / testbench info
        sed -i "s|{TB_SIM}|${TB_SIM}|g"                         "$sta_dir/power.tcl"
        sed -i "s|{VCD_INSTANCE_SUFFIX}|${VCD_INSTANCE_SUFFIX}|g" "$sta_dir/power.tcl"

        # ---- Clock command ----
        # Activates real (physical port) or virtual clock based on CLOCK_PORT.
        if [[ -n "${CLOCK_PORT:-}" ]]; then
            local clock_cmd="create_clock [get_ports ${CLOCK_PORT}] -period ${clk_period} -name CORE_CLK -waveform \"0 ${clk_half}\""
        else
            local clock_cmd="create_clock -period ${clk_period} -name CORE_CLK"
        fi
        sed -i "s|{CLOCK_CMD}|${clock_cmd}|g" "$sta_dir/power.tcl"

        # ---- SDC constraint block ----
        local sdc_block=""
        for var in $(compgen -v SDC_); do
            local axis_name="${var#SDC_}"
            local sdc_line="${!var}"
            # Replace {AXIS_NAME} placeholder with current sweep value
            sdc_line="${sdc_line//\{${axis_name}\}/${!axis_name}}"
            sdc_block+="${sdc_line}"$'\n'
        done
        perl -i -0pe "s|\{SDC_CONSTRAINTS\}|${sdc_block}|g" "$sta_dir/power.tcl"

        # Save debug copy
        cp "$sta_dir/power.tcl" "$sta_dir/debug/${RUN_ID}/power_${workload}.tcl"

        # ---- Run OpenSTA ----
        local terminal_log="$sta_dir/terminal_logs/${RUN_ID}/sta_power_${workload}.log"
        sta -exit "$sta_dir/power.tcl" 2>&1 | tee "$terminal_log"
        local sta_rc=${PIPESTATUS[0]}

        if [[ $sta_rc -ne 0 ]]; then
            echo "[$RUN_ID] sta FAILED (OpenSTA exit code $sta_rc)"
            return 1
        fi

        # ---- Check for errors ----
        if grep -iq "^Error:" "$terminal_log" 2>/dev/null; then
            echo "[$RUN_ID] sta FAILED (Error in OpenSTA log)"
            grep -i "^Error:" "$terminal_log"
            return 1
        fi

        # ---- Check power report was produced ----
        local power_rpt="$sta_dir/reports/${RUN_ID}/power_${workload}.rpt"
        if [[ ! -f "$power_rpt" ]]; then
            echo "[$RUN_ID] sta FAILED (power report not produced: $power_rpt)"
            return 1
        fi

        echo "  sta: power report -> $power_rpt"

        # ---- Extract critical path delay from timing report ----
        # report_checks -path_delay max writes a summary section:
        #
        #   XX.XXXX   data required time
        #   -Y.YYYY   data arrival time      ← negative because it is subtracted
        #   ---------
        #    S.SSSS   slack (MET|VIOLATED)
        #
        # "data arrival time" is the actual worst-case combinational path delay.
        # It appears negated in the subtraction row, so we take its absolute value.
        # No set_input_delay / set_output_delay are used, so input ports launch at
        # time 0 and the arrival time IS the pure logic propagation delay.
        local timing_rpt_local="$sta_dir/reports/${RUN_ID}/timing_${workload}.rpt"
        local cp_raw=""
        if [[ -f "$timing_rpt_local" ]]; then
            # OpenSTA prints "data arrival time" twice per path:
            #   1. Bare label at end of path trace: "   9.4152   data arrival time"
            #      → $1 is positive, not a valid summary line
            #   2. Summary subtraction row:        "  -9.4152   data arrival time"
            #      → $1 is negative (value being subtracted from required time)
            #
            # Multiple paths are reported; we want the MAXIMUM arrival time
            # (= worst-case combinational delay) across all paths.
            # The reset recovery path has arrival=0.0000 and appears first;
            # scanning all lines and taking the max avoids exiting early on it.
            cp_raw=$(awk '/data arrival time/ && $1 ~ /^-[0-9]/ {
                v = -($1 + 0)
                if (v > max) max = v
            } END { if (max > 0) print max }' "$timing_rpt_local" 2>/dev/null)
        fi
        if [[ -n "$cp_raw" ]]; then
            CP_RESULT="$cp_raw"
            export CP_RESULT
            echo "  sta: critical_path = ${CP_RESULT} ns"
        else
            CP_RESULT=""
            export CP_RESULT
            echo "  sta: critical path not found in timing report (no paths traced)"
        fi

        local timing_rpt="$sta_dir/reports/${RUN_ID}/timing_${workload}.rpt"
        [[ -f "$timing_rpt" ]] && echo "  sta: timing report -> $timing_rpt"
    done

    echo "[$RUN_ID] sta OK"
}
