// Simple 8-bit MAC: acc += a * b
// acc resets to 0 on rst_n low
module mac_8bit (
    input  wire        clk_i,
    input  wire        rst_n,
    input  wire [7:0]  a,
    input  wire [7:0]  b,
    output reg  [31:0] acc
);
    wire [15:0] product;
    assign product = a * b;

    always @(posedge clk_i or negedge rst_n) begin
        if (!rst_n)
            acc <= 32'd0;
        else
            acc <= acc + product;
    end

endmodule
