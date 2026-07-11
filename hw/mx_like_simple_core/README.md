# mx_like_simple_core

A differentiation-preserving DPU (Dot-Product Unit) template for MX-format
tensor cores, following Lutz et al. ARITH 2024 Early Accumulation (EA)
architecture.

**Design principle:** every datapath width is a monotone function of format
parameters `(eA, mA, eW, mW, eS, mS, N)`. No caps, no shared-worst-case,
no per-config branches. This makes the (A, W, Scale) design space directly
mappable to a hardware-cost Pareto surface.

## Contrast with `mx_like_tensor_core`

`mx_like_tensor_core` supports several accuracy-preserving optimizations
(early rounding, tree cap, M_acc truncation, UE8M0 branch) that flatten
the config→cost curve. Those are useful in production but obscure the DSE
story.  `mx_like_simple_core` deliberately drops all of them.

| Feature | tensor_core | simple_core |
|---|---|---|
| Reference arch | FDPU FusedScaleAcc | Lutz EA (fixed-point long integer) |
| Accumulator | Parameterized (M_acc knob) | BF16 fixed |
| Early rounding | Yes (tree exit RNE) | **No** |
| Tree PER cap | Yes (min(pER, M_target)) | **No** |
| UE8M0 branch | Separate DirectToFPn+FPNAdder | **Same module chain** |
| Pipeline | Yes | **No** (combinational + accreg register) |

## Configs

- Elements: E5M2, E4M3, E3M2, E2M3, E2M1, INT8
- Scales: UE8M0, UE7M1, UE6M2, UE5M3, UE4M4, UE4M3
- Vector: N = 4
- Output: BF16 (1+8+7), RNE round to BF16 per vector

**Total: 126 configs** (21 upper-triangle elem pairs × 6 scales).

## Files

- `scripts/datapath_widths.py` — Python model of per-config widths, sanity
  check reproduces Lutz FP8 E5M3 (SoP=69, final_adder=94).
- `src/main/scala/mx_simple/Parameter.scala` — Elem / Scale / DPUConfig /
  Widths definitions (mirror Python).
- `src/main/scala/mx_simple/SimpleDPU.scala` — 8-stage combinational datapath
  + BF16 accreg (single register).
- `src/main/scala/mx_simple/EmitSimpleDPU.scala` — Verilog emit main.
- `src/test/scala/mx_simple/SimpleDPUSpec.scala` — sanity tests
  (reset, clearAcc, elaboration).
- `generated/<A>_<W>_<S>/SimpleDPU.sv` — per-config Verilog.
- `generated/widths_manifest.csv` — width table for all 126 configs.

## Usage

```bash
# Show width table for all 126 configs (~5s).
python3 scripts/datapath_widths.py

# Emit one config's Verilog.
sbt "runMain mx_simple.EmitSimpleDPU E4M3 E4M3 UE6M2 generated/E4M3_E4M3_UE6M2"

# Emit all 126 configs + widths manifest (~10 min).
sbt "runMain mx_simple.EmitAllSimpleDPU generated"

# Sanity tests (reset, clearAcc, per-format elaborate).
sbt test
```

## Datapath Overview

All combinational within one cycle; only the accreg is a register.

```
S1  Per-lane multiply       Signed × 4  (mA+1)(mW+1) unsigned mult
S2  Long-int align + sum    Barrel shifter × 4 + signed reduce
S3  Post-tree scale mant    Absent for UE8M0 (synth away)
S4  Bidir accreg align      Left shift / right shift + sticky
S5  Signed CPA sum          scaled_term + shifted_accreg
S6  Sign-magnitude + LSC    Leading Sign Count, shift left
S7  RNE to BF16             Extract hidden+7mant, guard/round/sticky
S8  Pack BF16 accreg        Handle overflow / underflow
```

Per Lutz EA, alignment is anchor-based (Eq 5): `anchor = max{Pexp} + log2(N) + 1`.
Scale exp portion enters as anchor shift; scale mant (our extension) needs
a post-tree multiplier.

## Known simplifications

- Subnormal INPUTS accepted but treated as normal (hidden bit gating only)
- No NaN / Inf propagation
- Underflow to zero at output; overflow saturates
- No internal pipeline stages (may not meet high Fmax; synth reports actual)

## Verification status

- **Elaboration:** all 126 configs elaborate cleanly. See `generated/`.
- **Basic timing / reset:** `SimpleDPUSpec` verifies reset and clearAcc.
- **Numerical:** TBD — reference model vs FP64 comparison to be added.
- **Synthesis:** to be run externally with the emitted `generated/*/SimpleDPU.sv`.
