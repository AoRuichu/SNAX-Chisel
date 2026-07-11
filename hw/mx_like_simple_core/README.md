# mx_like_simple_core

A differentiation-preserving DPU (Dot-Product Unit) template for MX-format
tensor cores, following Lutz et al. ARITH 2024 Early Accumulation (EA)
architecture.

**Design principle:** every datapath width is a monotone function of format
parameters `(eA, mA, eW, mW, eS, mS, N)`. No caps, no shared-worst-case,
no per-config branches. The (A, W, Scale) design space directly maps to
a hardware-cost Pareto surface.

## Configs

- Elements: E5M2, E4M3, E3M2, E2M3, E2M1, INT8 (MX INT8 = 8-bit sign-mag
  with implicit `impSc = -6`)
- Scales: UE8M0, UE7M1, UE6M2, UE5M3, UE4M4, UE4M3
- Vector: N = 4
- Output: BF16 (1+8+7), RNE round to BF16 per vector

**Total: 126 configs** (21 upper-triangle elem pairs × 6 scales).

## Module hierarchy (per config)

Each config elaborates to 4 or 5 synthesizable modules so synthesis can
measure per-block PPA (the 5th, `ScaleMult`, is elided for UE8M0):

```
SimpleDPU_<A>_<W>_<S>
├── LaneMul_<A>_<W>            × N  (per-lane multiply)
├── AlignSumTree_<A>_<W>_vec4  × 1  (barrel-shift align + signed sum)
├── ScaleMult_<A>_<W>_<S>      × 1  (post-tree scale mant mult; UE8M0 skips)
└── AccUpdate_<A>_<W>_<S>      × 1  (accreg align + LSC + RNE + register)
```

## Clock / reset

- **Clock:** positive-edge, single domain.
- **Reset:** async, **active-HIGH** (`reset=1` = registers cleared,
  `reset=0` = normal op). Realized in Chisel as
  `withReset(reset.asAsyncReset)(RegInit(...))`; emitted SV uses
  `always_ff @(posedge clock or posedge reset)`.
- **Note:** this is inverted vs `mx_like_tensor_core` (which uses
  active-low `(!reset).asAsyncReset`). Chosen here because chiseltest's
  default reset polarity matches active-high — avoids manual reset poking
  in every test.

## Files

- `scripts/datapath_widths.py` — Python width model, sanity check
  reproduces Lutz FP8 E5M3 (SoP=69, final_adder=94).
- `src/main/scala/mx_simple/Parameter.scala` — Elem / Scale / DPUConfig /
  Widths definitions.
- `src/main/scala/mx_simple/SimpleDPU.scala` — top module + 4 submodules.
- `src/main/scala/mx_simple/EmitSimpleDPU.scala` — Verilog emit main +
  `simple_dpu_meta.txt` writer.
- `src/test/scala/mx_simple/Reference.scala` — FP64 reference model +
  BF16 rounding + random stimulus helpers.
- `src/test/scala/mx_simple/SimpleDPUSpec.scala` — sanity tests (reset,
  clearAcc, elaboration).
- `src/test/scala/mx_simple/SimpleDPUNumericSpec.scala` — end-to-end
  numerical verification against FP64 reference.
- `test/gen_simple_dpu_tb.py` — per-config SV testbench generator
  (deterministic random stimulus, produces $dumpfile VCD).
- `test/gen_all_vcds.py` — batch driver: gen tb + verilate + run for each
  config, producing one VCD per config for power analysis.
- `generated/<A>_<W>_<S>/` — per-config Verilog + `simple_dpu_meta.txt` +
  `tb_SimpleDPU.sv` (after tb gen) + `tb_SimpleDPU_<cfg>.vcd` (after run).
- `generated/widths_manifest.csv` — width table for all 126 configs.

## Usage

```bash
# Show width table for all 126 configs.
python3 scripts/datapath_widths.py

# Emit one config's Verilog + meta.
sbt "runMain mx_simple.EmitSimpleDPU E4M3 E4M3 UE6M2 generated/E4M3_E4M3_UE6M2"

# Emit all 126 configs + widths manifest (~40s).
sbt "runMain mx_simple.EmitAllSimpleDPU generated"

# Numerical + sanity tests (Verilator backend).
sbt test
sbt "testOnly mx_simple.SimpleDPUNumericSpec"

# Generate VCDs for all 126 configs (parallel).
python3 test/gen_all_vcds.py --jobs 4
```

## Datapath Overview (Lutz EA)

All combinational within one cycle; only the accreg is a register.

```
S1  Per-lane multiply       Signed × N   (mA+1)(mW+1) unsigned mult
S2  Long-int align + sum    Barrel shift by (sopShift+prodExp) then reduce
S3  Post-tree scale mant    Only when S.m > 0 (UE8M0 bypasses)
S4  Bidir accreg align      Left / right shift + sticky
S5  Signed CPA sum          scaled_term + shifted_accreg
S6  Sign-magnitude + LSC    Leading Sign Count, shift left
S7  RNE to BF16             Extract hidden+7mant, guard/round/sticky
S8  Pack BF16 accreg        Handle overflow / underflow
```

Key formulas (per Widths):
```
P_max        = maxOpValExp_A + maxOpValExp_W + 1   (Lutz max{Pexp})
belowAnchor  = |minOpValExp_A + minOpValExp_W|      (fractional bits)
sopShift     = belowAnchor - fracBitsA - fracBitsW  (constant left-shift per product)
sopFieldW    = 1 + log2(N) + (P_max+1) + belowAnchor
scaledTermW  = sopFieldW + 2*(mS+1)                 (0 if UE8M0)
finalAdderW  = 1 + BF16.sigBits + scaledTermW
```

Where `fracBits = m` for FP (m fractional bits below the hidden 1) and
`fracBits = 0` for INT8 (mant field IS the integer magnitude).

## Known simplifications

- Subnormal INPUTS accepted; subnormal OUTPUTS become zero
- No NaN / Inf propagation
- Overflow saturates via BF16 max normal (exp=254, mant=0x7F)
- No internal pipeline stages (may not meet high Fmax; synth reports actual)

## Verification status

- **Elaboration:** all 126 configs elaborate cleanly.
- **Numerical:** `SimpleDPUNumericSpec` passes 4 representative configs
  vs FP64 reference (E4M3/UE8M0, E4M3/UE6M2, E5M2/UE8M0, INT8/UE8M0).
- **Sanity:** `SimpleDPUSpec` covers reset + clearAcc + INT8 + asymmetric.
- **VCD generation:** `gen_all_vcds.py` produces one VCD per config.
- **Synthesis:** to be run externally with the emitted
  `generated/*/SimpleDPU_*.sv` + submodules.

## Alert rule (from memory)

Same-`mS` scale configs must synth to near-identical PPA (see
`memory/feedback_ue4m3_ue5m3_parity.md`). If UE4M3 vs UE5M3 or any other
same-mS pair differs by >5% on area/power, that's a red flag — investigate
before accepting the number.
