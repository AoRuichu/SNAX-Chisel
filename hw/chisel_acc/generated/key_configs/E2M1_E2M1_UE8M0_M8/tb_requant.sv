// Auto-generated testbench for E2M1_E2M1_UE8M0_M8
// Module: requant_in17    Input width per elem: FP17    Output: elem_out4
`timescale 1ns/1ps

module tb_E2M1_E2M1_UE8M0_M8;
  // ── Geometry (fixed: blockSize=16, tileRows=4, tileCols=16) ──
  localparam IN_W_PER_ELEM = 17;
  localparam N_ELEMS        = 64;
  localparam IN_TOTAL_W     = 1088;       // = IN_W_PER_ELEM * N_ELEMS
  localparam OUT_W_PER_ELEM = 4;
  localparam OUT_TOTAL_W    = 256;
  localparam SCALE_TOTAL_W  = 32;
  localparam N_VECTORS      = 256;

  // ── 400 MHz clock (period = 2.5 ns) ──
  reg clock = 1'b0;
  always #1.25 clock = ~clock;

  reg                     reset      = 1'b1;
  reg                     io_valid_in = 1'b0;
  reg  [IN_TOTAL_W-1:0]   io_fp32_in  = '0;

  wire [SCALE_TOTAL_W-1:0]  io_shared_scale_out;
  wire [OUT_TOTAL_W-1:0]    io_elem_out;
  wire                      io_valid_out;

  requant_in17 dut (
    .clock(clock),
    .reset(reset),
    .io_fp32_in(io_fp32_in),
    .io_valid_in(io_valid_in),
    .io_shared_scale_out(io_shared_scale_out),
    .io_elem_out(io_elem_out),
    .io_valid_out(io_valid_out)
  );

  // Stimulus
  reg [IN_TOTAL_W-1:0] stim_arr [0:N_VECTORS-1];

  integer i;
  initial begin
    $readmemh("stim_requant.hex", stim_arr);

    // VCD for PT-PX power analysis
    $dumpfile("requant.vcd");
    $dumpvars(0, dut);

    #2 reset = 1'b1;
    #10 reset = 1'b0;
    #5;

    for (i = 0; i < N_VECTORS; i = i + 1) begin
      @(posedge clock);
      io_fp32_in   = stim_arr[i];
      io_valid_in  = 1'b1;
    end

    @(posedge clock) io_valid_in = 1'b0;
    #50;
    $finish;
  end
endmodule
