# 400 MHz clock constraint for E2M1_E2M1_UE8M0_M8
create_clock -name clk -period 2.5 [get_ports clock]
set_clock_uncertainty -setup 0.10 [get_clocks clk]
set_clock_uncertainty -hold  0.05 [get_clocks clk]

set_input_delay  -clock clk 0.5 [remove_from_collection [all_inputs]  [get_ports clock]]
set_output_delay -clock clk 0.5 [all_outputs]
set_max_fanout 16 [current_design]
