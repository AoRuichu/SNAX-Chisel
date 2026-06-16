// Auto-generated testbench for integrated PE_Array
// Config: INT8_INT8_UE8M0_M13    Act=INT8  Wt=INT8  Scale=UE8M0  M_acc=13
// Result width: 512 bits (8b/elem × 64 elems)
`timescale 1ns/1ps

module tb_pe_array_INT8_INT8_UE8M0_M13;
  // ── Geometry (fixed) ──
  localparam TILE_ROWS    = 4;
  localparam TILE_COLS    = 16;
  localparam VEC          = 4;
  localparam BLOCK_SIZE   = 16;
  localparam CPB          = BLOCK_SIZE / VEC;   // = 4
  localparam OP_A_W       = 32;
  localparam OP_B_W       = 32;
  localparam EXP_A_W      = 8;
  localparam EXP_B_W      = 8;
  localparam RESULT_W     = 512;
  localparam SCALE_OUT_W  = 32;
  localparam N_VECTORS    = 256;

  // ── 400 MHz clock (period = 2.5 ns) ──
  reg clock = 1'b0;
  always #1.25 clock = ~clock;

  reg                  reset                  = 1'b1;
  // Mode pins — held constant; Chisel emit baked the format into RTL,
  // these just keep mux selectors from floating during synthesis.
  reg [2:0]            io_A_mode              = 3'd0;
  reg [2:0]            io_B_mode              = 3'd0;
  reg [1:0]            io_result_mode_quan    = 2'd0;
  reg [1:0]            io_group_size          = 2'd0;
  reg [3:0]            io_shared_format_i     = 4'd0;

  reg                  io_acc_reset_i         = 1'b0;
  reg                  io_send_output_i       = 1'b0;
  reg [31:0]           io_accumulation_count_i = 32'd0;
  reg                  io_A_valid_i           = 1'b0;
  reg                  io_B_valid_i           = 1'b0;
  wire                 io_A_ready_o;
  wire                 io_B_ready_o;

  // Tile inputs (TILE_ROWS row ports, TILE_COLS col ports — each OP_A_W/OP_B_W wide)
  reg [OP_A_W-1:0]     io_op_a_i [0:TILE_ROWS-1];
  reg [OP_B_W-1:0]     io_op_b_i [0:TILE_COLS-1];
  reg [EXP_A_W-1:0]    io_shared_exp_A_i [0:TILE_ROWS-1];
  reg [EXP_B_W-1:0]    io_shared_exp_B_i [0:TILE_COLS-1];

  wire [SCALE_OUT_W-1:0] io_shared_scale_out;
  wire [RESULT_W-1:0]    io_result;
  wire                   io_valid_out;

  PE_Array dut (
    .clock                  (clock),
    .reset                  (reset),
    .io_A_mode              (io_A_mode),
    .io_B_mode              (io_B_mode),
    .io_result_mode_quan    (io_result_mode_quan),
    .io_group_size          (io_group_size),
    .io_shared_format_i     (io_shared_format_i),
    .io_acc_reset_i         (io_acc_reset_i),
    .io_send_output_i       (io_send_output_i),
    .io_accumulation_count_i(io_accumulation_count_i),
    .io_A_valid_i           (io_A_valid_i),
    .io_B_valid_i           (io_B_valid_i),
    .io_A_ready_o           (io_A_ready_o),
    .io_B_ready_o           (io_B_ready_o),
    .io_op_a_i_0            (io_op_a_i[0]),
    .io_op_a_i_1            (io_op_a_i[1]),
    .io_op_a_i_2            (io_op_a_i[2]),
    .io_op_a_i_3            (io_op_a_i[3]),
    .io_op_b_i_0            (io_op_b_i[0]),
    .io_op_b_i_1            (io_op_b_i[1]),
    .io_op_b_i_2            (io_op_b_i[2]),
    .io_op_b_i_3            (io_op_b_i[3]),
    .io_op_b_i_4            (io_op_b_i[4]),
    .io_op_b_i_5            (io_op_b_i[5]),
    .io_op_b_i_6            (io_op_b_i[6]),
    .io_op_b_i_7            (io_op_b_i[7]),
    .io_op_b_i_8            (io_op_b_i[8]),
    .io_op_b_i_9            (io_op_b_i[9]),
    .io_op_b_i_10          (io_op_b_i[10]),
    .io_op_b_i_11          (io_op_b_i[11]),
    .io_op_b_i_12          (io_op_b_i[12]),
    .io_op_b_i_13          (io_op_b_i[13]),
    .io_op_b_i_14          (io_op_b_i[14]),
    .io_op_b_i_15          (io_op_b_i[15]),
    .io_shared_exp_A_i_0    (io_shared_exp_A_i[0]),
    .io_shared_exp_A_i_1    (io_shared_exp_A_i[1]),
    .io_shared_exp_A_i_2    (io_shared_exp_A_i[2]),
    .io_shared_exp_A_i_3    (io_shared_exp_A_i[3]),
    .io_shared_exp_B_i_0    (io_shared_exp_B_i[0]),
    .io_shared_exp_B_i_1    (io_shared_exp_B_i[1]),
    .io_shared_exp_B_i_2    (io_shared_exp_B_i[2]),
    .io_shared_exp_B_i_3    (io_shared_exp_B_i[3]),
    .io_shared_exp_B_i_4    (io_shared_exp_B_i[4]),
    .io_shared_exp_B_i_5    (io_shared_exp_B_i[5]),
    .io_shared_exp_B_i_6    (io_shared_exp_B_i[6]),
    .io_shared_exp_B_i_7    (io_shared_exp_B_i[7]),
    .io_shared_exp_B_i_8    (io_shared_exp_B_i[8]),
    .io_shared_exp_B_i_9    (io_shared_exp_B_i[9]),
    .io_shared_exp_B_i_10  (io_shared_exp_B_i[10]),
    .io_shared_exp_B_i_11  (io_shared_exp_B_i[11]),
    .io_shared_exp_B_i_12  (io_shared_exp_B_i[12]),
    .io_shared_exp_B_i_13  (io_shared_exp_B_i[13]),
    .io_shared_exp_B_i_14  (io_shared_exp_B_i[14]),
    .io_shared_exp_B_i_15  (io_shared_exp_B_i[15]),
    .io_shared_scale_out    (io_shared_scale_out),
    .io_result              (io_result),
    .io_valid_out           (io_valid_out)
  );

  // ── Stimulus storage (packed across all ports per cycle) ──
  reg [TILE_ROWS*OP_A_W-1:0]  stim_op_a [0:N_VECTORS-1];
  reg [TILE_COLS*OP_B_W-1:0]  stim_op_b [0:N_VECTORS-1];
  reg [TILE_ROWS*EXP_A_W-1:0] stim_exp_a [0:N_VECTORS-1];
  reg [TILE_COLS*EXP_B_W-1:0] stim_exp_b [0:N_VECTORS-1];

  integer i, r, c;
  initial begin
    $readmemh("stim_pe_array_op_a.hex",  stim_op_a);
    $readmemh("stim_pe_array_op_b.hex",  stim_op_b);
    $readmemh("stim_pe_array_exp_a.hex", stim_exp_a);
    $readmemh("stim_pe_array_exp_b.hex", stim_exp_b);

    // VCD for PT-PX power analysis
    $dumpfile("pe_array.vcd");
    $dumpvars(0, dut);

    // Reset / accumulator clear
    #2  reset = 1'b1;
        io_acc_reset_i = 1'b1;
    #10 reset = 1'b0;
    #5  io_acc_reset_i = 1'b0;

    // Drive all N_VECTORS cycles
    for (i = 0; i < N_VECTORS; i = i + 1) begin
      @(posedge clock);
      // Unpack per-port slots (MSB = port 0).
      for (r = 0; r < TILE_ROWS; r = r + 1) begin
        io_op_a_i[r]        = stim_op_a[i] [(TILE_ROWS-1-r)*OP_A_W +: OP_A_W];
        io_shared_exp_A_i[r] = stim_exp_a[i][(TILE_ROWS-1-r)*EXP_A_W +: EXP_A_W];
      end
      for (c = 0; c < TILE_COLS; c = c + 1) begin
        io_op_b_i[c]        = stim_op_b[i] [(TILE_COLS-1-c)*OP_B_W +: OP_B_W];
        io_shared_exp_B_i[c] = stim_exp_b[i][(TILE_COLS-1-c)*EXP_B_W +: EXP_B_W];
      end
      io_A_valid_i = 1'b1;
      io_B_valid_i = 1'b1;
      // Pulse send_output at the end of every block (CPB cycles)
      io_send_output_i = ((i % CPB) == (CPB - 1));
      io_accumulation_count_i = i;
    end

    @(posedge clock);
    io_A_valid_i     = 1'b0;
    io_B_valid_i     = 1'b0;
    io_send_output_i = 1'b0;
    #50;
    $finish;
  end
endmodule
