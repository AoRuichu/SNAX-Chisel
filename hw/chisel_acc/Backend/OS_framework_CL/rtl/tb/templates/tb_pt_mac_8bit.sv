// Power testbench for mac_8bit
`timescale 1ns/1ps

module tb_pt_mac_8bit;

    logic        clk_i;
    logic        rst_n;
    logic [7:0]  a;
    logic [7:0]  b;
    logic [31:0] acc;

    // Clock generation
    always begin #({CLK_HALF}); clk_i <= ~clk_i; end

    // DUT instantiation
    `ifdef GATE_LEVEL_SIM
    mac_8bit mac_8bit0 (
    `else
    mac_8bit mac_8bit0 (
    `endif
        .clk_i (clk_i),
        .rst_n (rst_n),
        .a     (a),
        .b     (b),
        .acc   (acc)
    );

    integer i;

    initial begin
        clk_i = 0;
        rst_n = 0; a = 0; b = 0;

        $dumpfile("{PROJ_DIR}/sim-syn/vcd/{DIR_LOC_TEMP}/{DESIGN_NAME}_{WORKLOAD}.vcd");
        $dumpvars(0, tb_pt_mac_8bit);
        $dumpon;

        // Reset
        repeat(4) @(posedge clk_i);
        rst_n = 1;

        // --- High-toggle phase: random inputs ---
        for (i = 0; i < 128; i = i + 1) begin
            a = $random;
            b = $random;
            @(posedge clk_i);
        end

        // --- Moderate-toggle phase: structured patterns ---
        for (i = 0; i < 64; i = i + 1) begin
            a = i[7:0];
            b = ~i[7:0];
            @(posedge clk_i);
        end

        // --- Idle/leakage phase: fixed inputs ---
        a = 8'hAA; b = 8'h55;
        repeat(128) @(posedge clk_i);

        $dumpoff;
        $finish;
    end

endmodule
