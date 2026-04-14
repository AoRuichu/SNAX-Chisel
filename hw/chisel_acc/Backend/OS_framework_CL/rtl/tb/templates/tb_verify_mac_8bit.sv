// Verification testbench for mac_8bit
`timescale 1ns/1ps

module tb_verify_mac_8bit;

    localparam real CLK_HALF = {CLK_HALF};

    logic        clk_i;
    logic        rst_n;
    logic [7:0]  a;
    logic [7:0]  b;
    logic [31:0] acc;

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

    // Clock generation
    initial clk_i = 0;
    always #(CLK_HALF) clk_i = ~clk_i;

    // Pass/fail counters
    int pass_count, fail_count;

    // Accumulator expected value
    logic [31:0] expected_acc;
    int ia, ib, i;

    // Check task
    task check_acc(input logic [31:0] expected, input int line);
        if (acc === expected) begin
            pass_count++;
        end else begin
            fail_count++;
            $display("FAIL (line %0d): acc expected=0x%08h got=0x%08h", line, expected, acc);
        end
    endtask

    `define CHECK_ACC(exp) check_acc(exp, `__LINE__)

    initial begin
        pass_count   = 0;
        fail_count   = 0;
        expected_acc = 0;

        // Reset
        rst_n = 0; a = 0; b = 0;
        `ifdef GATE_LEVEL_SIM
        rst_n = 1;
        repeat(4) @(posedge clk_i);
        rst_n = 0;
        `endif
        repeat(4) @(posedge clk_i);
        rst_n = 1;

        // --- High-toggle phase: 64 cycles of varying inputs ---
        for (i = 0; i < 64; i++) begin
            a = i * 3 + 7;
            b = i * 5 + 3;
            ia = int'(a); ib = int'(b);
            expected_acc = expected_acc + ia * ib;
            @(posedge clk_i);
            #0.1;
        end
        `CHECK_ACC(expected_acc);

        // --- Idle phase ---
        a = 8'hAA; b = 8'h55;
        repeat(64) @(posedge clk_i);

        // Summary
        $display("--------------------------------------------------");
        $display("Verification complete: PASS=%0d  FAIL=%0d", pass_count, fail_count);
        if (fail_count == 0)
            $display("ALL TESTS PASSED");
        else
            $display("FAILURES DETECTED");
        $display("--------------------------------------------------");
        $finish;
    end

    // Timeout watchdog
    initial begin
        #(CLK_HALF * 2 * 2000);
        $display("TIMEOUT");
        $finish;
    end

endmodule
