#! /usr/bin/bash
#
# Data gathering and CSV export.
# Sourced by out_loop.sh — provides gather_data(), gather_data_neg_slack(),
# put_data_in_excel(), and hierarchy-parsing helpers.
#
# Report format differences vs SV_framework_CL:
#   Area  : Yosys stat — "Chip area for module '\<design>': <value>"
#   Power : OpenSTA report_power — "Total  <int> <sw> <lk> <total>  100.0%"
#

# ================================================================
# Area hierarchy parsing
# Reads Yosys "stat -liberty" output and returns semicolon-separated
# per-submodule area when -noflatten (COMPILE_NO_AUTOUNGROUP) is active.
#
# Yosys stat hierarchy format (with -noflatten):
#   === <submodule> ===
#   ...
#   Chip area for module '\<submodule>': <value>
# ================================================================
parse_area_hierarchy () {
    local area_file="$1" design="$2"
    [[ ! -f "$area_file" ]] && return

    awk -v design="$design" '
    /^=== / {
        cur = $2
        gsub(/\\/, "", cur)
        next
    }
    /^   Chip area for module / {
        val = $NF + 0
        if (cur != design && cur != "") {
            ne++
            names[ne] = cur
            areas[ne] = val
        }
    }
    END {
        out = ""
        for (i = 1; i <= ne; i++) {
            if (out != "") out = out "; "
            out = out names[i] ": " sprintf("%.4f", areas[i])
        }
        print out
    }
    ' "$area_file"
}

# ================================================================
# Power hierarchy parsing
# Reads OpenSTA "report_power -instances" output and returns a
# semicolon-separated breakdown of per-instance power.
#
# OpenSTA hierarchy format:
#   Instance                  Internal Power  Switching Power  Leakage Power  Total Power (Watts)
#   <design>                  x.xxe-xx        x.xxe-xx         x.xxe-xx       x.xxe-xx
#     U1 (CELL_TYPE)          x.xxe-xx        ...
#     ...
# ================================================================
parse_power_hierarchy () {
    local power_file="$1" design="$2"
    [[ ! -f "$power_file" ]] && return

    awk -v design="$design" '
    BEGIN { in_data=0; ne=0 }

    /^Instance/ { in_data=1; next }
    /^---/      { next }

    in_data && /^[[:space:]]*$/ { next }

    in_data {
        line = $0

        # Determine depth from leading spaces
        match(line, /^( *)/, arr)
        indent = length(arr[1])
        dep = int(indent / 2)

        nf = split(line, f)
        if (nf < 2) next

        inst = f[1]
        gsub(/[()]/, "", inst)

        # Total Power is the 4th numeric column (last before potential %)
        total_pw = f[nf] + 0

        # Skip top-level instance
        if (dep == 0) { top_pw = total_pw; next }

        ne++
        shorts_p[ne] = inst
        powers[ne]   = total_pw
        deps_p[ne]   = dep
        par_idx[ne]  = 0
        for (back = ne-1; back >= 1; back--) {
            if (deps_p[back] == dep - 1) { par_idx[ne] = back; break }
        }
    }

    END {
        out = ""
        for (i = 1; i <= ne; i++) {
            if (deps_p[i] != 1) continue
            if (out != "") out = out "; "
            pct = (top_pw > 0) ? powers[i] / top_pw * 100 : 0
            out = out shorts_p[i] ": " sprintf("%.3e", powers[i]) \
                  " (" sprintf("%.1f", pct) "%)"

            # Top-3 children
            delete gc; ngc = 0
            for (j = 1; j <= ne; j++) {
                if (deps_p[j] == 2 && par_idx[j] == i) {
                    ngc++; gc[ngc] = j
                }
            }
            for (a = 1; a < ngc; a++) {
                for (b = a+1; b <= ngc; b++) {
                    if (powers[gc[b]] > powers[gc[a]]) {
                        tmp = gc[a]; gc[a] = gc[b]; gc[b] = tmp
                    }
                }
            }
            shown = 0
            for (k = 1; k <= ngc && shown < 3; k++) {
                j = gc[k]
                pct_par = (powers[i] > 0) ? powers[j] / powers[i] * 100 : 0
                out = out "; > " shorts_p[j] ": " sprintf("%.3e", powers[j]) \
                      " (" sprintf("%.1f", pct_par) "% of parent)"
                shown++
            }
        }
        print out
    }
    ' "$power_file"
}

# ================================================================
# Data gathering
# ================================================================

gather_data_neg_slack () {
    echo "  Gathering data (NEG_SLACK) for ${RUN_ID}..."

    local area_file="$syn_dir/reports/${RUN_ID}/area_report.rpt"
    local area
    area=$(grep -m1 "Chip area for module" "$area_file" 2>/dev/null | sed "s/.*: //")

    local area_hier=""
    if [[ "${COMPILE_NO_AUTOUNGROUP:-}" == *"-no_auto_ungroup"* ]] || \
       [[ "${COMPILE_NO_AUTOUNGROUP:-}" == *"-noflatten"* ]]; then
        area_hier=$(parse_area_hierarchy "$area_file" "$DESIGN_NAME")
    fi

    local row="$RUN_ID"
    for axis in "${AXES[@]}"; do
        row="${row},${!axis}"
    done
    row="${row},${area},\"${area_hier}\""
    for workload in "${WORKLOADS[@]}"; do
        row="${row},NEG_SLACK,"
    done
    echo "$row" >> "data_to_excel/manifest.csv"
}

gather_data () {
    echo "  Gathering data for ${RUN_ID}..."

    # ---- Area ----
    local area_file="$syn_dir/reports/${RUN_ID}/area_report.rpt"
    local area
    # Yosys stat format: "   Chip area for module '\mac_8bit': 12345.67"
    area=$(grep -m1 "Chip area for module" "$area_file" 2>/dev/null | sed "s/.*: //")

    local area_hier=""
    if [[ "${COMPILE_NO_AUTOUNGROUP:-}" == *"-noflatten"* ]]; then
        area_hier=$(parse_area_hierarchy "$area_file" "$DESIGN_NAME")
    fi

    # ---- Power per workload ----
    declare -A power_vals
    declare -A power_hier_vals
    for workload in "${WORKLOADS[@]}"; do
        local power_file="$sta_dir/reports/${RUN_ID}/power_${workload}.rpt"
        # OpenSTA format: "Total  <int>  <sw>  <lk>  <total>  100.0%"
        # The total power is the 4th number on the "Total" line.
        local power
        power=$(awk '/^Total[[:space:]]/ { print $5 }' "$power_file" 2>/dev/null | head -1)
        power_vals["$workload"]="${power}"

        power_hier_vals["$workload"]=""
        if [[ -f "$sta_dir/reports/${RUN_ID}/power_hierarchy_${workload}.rpt" ]]; then
            power_hier_vals["$workload"]=$(parse_power_hierarchy \
                "$sta_dir/reports/${RUN_ID}/power_hierarchy_${workload}.rpt" "$DESIGN_NAME")
        fi
    done

    # ---- Append row to manifest ----
    local row="$RUN_ID"
    for axis in "${AXES[@]}"; do
        row="${row},${!axis}"
    done
    row="${row},${area},\"${area_hier}\""
    for workload in "${WORKLOADS[@]}"; do
        row="${row},${power_vals[$workload]},\"${power_hier_vals[$workload]}\""
    done
    echo "$row" >> "data_to_excel/manifest.csv"
}

put_data_in_excel () {
    cp "data_to_excel/manifest.csv" "data_to_excel/output.csv"
}
