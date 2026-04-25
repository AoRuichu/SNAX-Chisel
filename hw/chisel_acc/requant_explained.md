# RequantFP8 ExMy 路径详解

参考代码：`hw/chisel_acc/src/main/scala/mx/requant/RequantFP8.scala`

---

## Part A · 整体逻辑梳理

### A.1 目标与数据流

把 PE 阵列输出的一批 FP32 累加结果，按 MX 格式压回 8 位（或 6 位）小元素 + 一个 **block 共享的 8 位 scale**：

```
N 个 FP32  →  1 个 8-bit shared_scale  +  N 个 (E5M2/E4M3/E3M2/E2M3) 元素
其中 N = blockSize ∈ {16, 32, 64}
```

MX 的核心思想：**block 内所有元素共用一个尺度因子**，把 block 的最大值正规化到目标格式可表达范围内，单元素只剩一个小指数 + 小尾数。

### A.2 三层模块结构

```
RequantFP8        // 顶层：寄存器 + batch 控制
└─ RequantBlock   // 一行一组：1 finder + N converter，纯组合
   ├─ MaxScaleFinder      // 找 block 的共享 scale
   └─ FP32ToMXFP8 × N     // 单元素量化
```

| 模块 | 文件位置 |
|---|---|
| `RequantFP8` | `RequantFP8.scala:275-348` |
| `RequantBlock` | `RequantFP8.scala:237-257` |
| `MaxScaleFinder` | `RequantFP8.scala:180-228` |
| `FP32ToMXFP8` | `RequantFP8.scala:24-165` |

### A.3 顶层 RequantFP8：缓存 + 节拍控制

**输入接口**
- `fp32_in`：一拍来 `tileRows × tileCols` 个 FP32（PE 阵列的一次输出）
- `valid_in`：本拍数据有效

**batch 计数器**
`B = batchesPerBlock = blockSize / tileCols`，例：blk32 + tileCols=4 → B=8。每 B 次 `valid_in` 凑齐一行 block。每拍来的 `tileCols` 个元素塞到 `buffer[row][batchCnt × tileCols + col]`。

**"最后一拍直通"优化**
当本拍是最后一 batch（`blockDone`）时，最后一组列直接从 `extractFP32` 取，而不是写进 `buffer` 再读出来——这样组合逻辑紧接着这拍的有效数据计算结果，少 1 拍延迟。

**输出寄存**
`blockDone` 那拍的组合结果（scale + N 个元素 × tileRows）打一拍 `Reg`，下一拍 `valid_out` 拉高输出。

### A.4 MaxScaleFinder：找共享 scale

#### A.4.1 通用部分：max-tree
`maxTree` 是递归构造的二叉比较树，深度 log₂(blockSize)。

#### A.4.2 两条 scale 编码路径

**UE8M0（M=0）— 纯 2 的幂**

直接对每个 FP32 取 biased exp（30..23 共 8 bit）做 max。比较只看指数 → block 里指数最大的那个数的 biased_exp 就是 shared_scale。
- scale 语义：`2^(shared_scale − 127)`

**ExMy（M≥1）— 带尾数的 scale**

1. 对每个 FP32 的低 31 位（去 sign）做 max-tree → 等价于绝对值最大的那个 FP32。因为 IEEE-754 的指数+尾数按无符号比大小就是按绝对值排。
2. 把这个最大值的指数从 FP32 的 bias=127 改基到 ExMy 的 scale_bias：`expRaw = maxBiasedExp − 127 + scaleBias`。
3. clamp 到 `[0, 2^E − 1]`。
4. 取 FP32 尾数的高 M 位作为 scale 的尾数（**floor 编码**，向下取整）。
5. 输出 `{expClamped(E bits), mantEnc(M bits)}`。

**关键性质**：floor 编码保证 `scale ≤ |max element|`，所以 `q = max/scale ≥ 1` 但 `< 2`，普通元素则 `|element/scale| < 2`。这决定了 ExMy 路径里 `q ∈ (0.5, 2)` 的范围。

> 注意：UE8M0 max-tree 比的是 8 位 exp；ExMy 比的是 31 位（去掉 sign）。两条路找的"最大值"语义不同（UE8M0 只看指数最大、不看 mantissa；ExMy 看绝对值最大）。这是因为 UE8M0 反正只能编码 2^k，看 mantissa 没用。

### A.5 FP32ToMXFP8：单元素量化

每个元素都做：`out = round(fp32_in / scale, 目标格式)`。

**关键参数**

| 名字 | 含义 | 例（E4M3 + UE7M1）|
|---|---|---|
| `outExpBits` | 输出元素的指数位数 | 4 |
| `outMantBits` | 输出元素的尾数位数 | 3 |
| `outBias` | 输出元素的指数 bias | 7 |
| `outMaxNormalExp` | 输出元素最大正常 biased exp | 14 |
| `M`, `E` | scale 的尾数/指数位数 | M=1, E=7 |
| `scaleBias` | scale 的 bias | 63 |

#### A.5.1 UE8M0 路径

因为 scale 是 2^k，**除法变成指数减法**，没有尾数运算：
1. `out_biased_exp = fp32_biased_exp − shared_scale + outBias`
2. 尾数直接从 fp32 尾数高位截 outMantBits 位
3. RNE：guard + (LSB | sticky) 决定是否进位
4. underflow（指数 ≤ 0 或 fp32 是 0/subnormal）→ flush 到 0；overflow → 饱和到 ±max-normal

**这条路径完全没有乘除法**，开销极小。

#### A.5.2 ExMy 路径 — 真正算除法的地方

把 fp32 / scale 分成两半，**指数相减**，**尾数相除**，最后再合起来。

**4.5.2.1 拆解 scale**
- `scale_biased_exp` = 高 E 位
- `scale_mant_raw` = 低 M 位
- `scale_full_mant` = `{implicit_1, scale_mant_raw}`，共 `M+1` 位
- 其中 implicit-1 用 `scale_biased_exp.orR` 模拟："biased exp ≠ 0 → normal → 隐含 1.x"；"biased exp = 0 → subnormal/zero → 隐含 0.x"

**4.5.2.2 指数：先减后修正**
```
out_exp_raw = fp32_biased_exp − scale_biased_exp + correction
correction  = scaleBias − 127 + outBias   // 设计期常量
```
推导：`element_exp = (fp32_exp − 127) − (scale_exp − scaleBias) = fp32_exp − scale_exp + (scaleBias − 127)`，再加 `outBias` 转成输出 biased 形式。

**4.5.2.3 尾数：算 q = (1.fp32_mant) / (1.scale_mant)**

q 的范围：1.fp32_mant ∈ [1, 2)，1.scale_mant ∈ [1, 2)，所以 q ∈ (0.5, 2)。

要把这个浮点比值用整数除法表达，给被除数左移 EXTRA 位增加精度：

```
EXTRA   = outMantBits + 3       // 多留 3 bit 给 guard/round/sticky
qNum    = fp32FullMant << EXTRA // (24 + EXTRA) 位
q_int   = qNum / scale_full_mant
q_rem   = qNum % scale_full_mant  // 用作 sticky
```

`safeDenom` 的作用：当整 block 全 0（`shared_scale==0`）时 `scale_full_mant=0`，会触发硬件除 0；这里用 1 替代避免综合产生奇怪 X，反正这种情况 `out_exp_raw ≤ 0`，最后会被 underflow 路径冲成 0。

**4.5.2.4 q_int 的位字段含义**

`q_int` 把比值的结果按定点表示：
- `q ≥ 1`：q_int 的 MSB（implicit-1）在第 `IMPL = 23 − M + EXTRA` 位
- `q < 1`：implicit-1 在第 `IMPL − 1` 位（差一格，需要左移 1）

`qGeq1 = q_int(IMPL)` 一位检测就能判别。

**4.5.2.5 取尾数 + 凑 G/R/S**

根据 `qGeq1` 在 q_int 上选不同的窗口：

| 字段 | qGeq1 | qLt1（窗口往低移 1 位）|
|---|---|---|
| out_mant_raw | `q_int[IMPL-1 : IMPL-outMantBits]` | `q_int[IMPL-2 : IMPL-1-outMantBits]` |
| guardBit | `q_int[IMPL-outMantBits-1]` | `q_int[IMPL-outMantBits-2]` |
| roundBit | `q_int[IMPL-outMantBits-2]` | `q_int[IMPL-outMantBits-3]` |
| stickyQ | `q_int[IMPL-outMantBits-3:0].orR` | `q_int[IMPL-outMantBits-4:0].orR` |

sticky 还要再 OR 上整数除法的余数 `q_rem != 0`——余数代表"被除数没整除完的小尾巴"，全是粘性位。

**4.5.2.6 进位 + 进位溢出 + 指数补偿**

标准 RNE：guard ∧ (LSB ∨ round ∨ sticky) → +1。如果 `out_mant_raw` 全 1 进位会溢出到 `outMantBits` 位（`mantOverflow`）。

最终指数：
```
out_exp_full = out_exp_raw
             + (qGeq1 ? 0 : -1)   // q<1 时 normalize 左移 1，指数 -1
             + mantOverflow       // 尾数全 1 进位 → 指数 +1
```

**4.5.2.7 underflow / overflow 处理**
与 UE8M0 相同：指数 ≤ 0 或 fp32 是 0/subnormal → 0；超 max → 饱和到 ±max-normal。

### A.6 ExMy 数据流图

```
fp32_in ──┬──► sign ────────────────────────────────────────┐
          ├──► fp32_exp(8)  ──┐                             │
          └──► fp32_man(23)  ──┐│                            │
                              ││                            │
shared_scale ──┬──► scale_biased_exp(E)                     │
               └──► scale_mant_raw(M) ──► {1, mant} = scale_full_mant(M+1)
                                          │                 │
                  out_exp_raw = fp32_exp − scale_exp + correction
                                          ▼
                           qNum = {1.fp32_man, 0...0}(24+EXTRA)
                           q_int = qNum / scale_full_mant   ←── 大除法器
                           q_rem = qNum % scale_full_mant
                                          │
                                          ▼
                           qGeq1 = q_int[IMPL]
                           取 out_mant / guard / round / sticky
                                          ▼
                           RNE 进位 → mantOverflow
                                          ▼
                           out_exp_full = out_exp_raw − !qGeq1 + mantOverflow
                                          ▼
                           underflow / overflow 处理
                                          ▼
                          out = {sign, out_exp, out_mant}
```

### A.7 几个隐藏约定

1. **scale 的 implicit-1 用 `orR` 而非常量 1**：让 `shared_scale=0`（全零 block）时 `scale_full_mant=0`，直接被 `safeDenom` 拦截。
2. **MaxScaleFinder 的 floor 编码确保 `q < 2`**：取尾数高 M 位是向下取整；如果取整到 ceiling，scale 可能 > max element，`q` 必 < 1，但是范围会变成 `(0.25, 1)`，这条 ExMy 路径里的 `qGeq1` 字段窗口选择就要重写。
3. **out_exp_full 的位宽**：`out_exp_raw` 是 9-bit SInt，`+ normAdj(2-bit) + mantOverflow(1-bit)` 后在 Chisel 里自动扩展，足够容纳所有越界情况，再用 `> outMaxNormalExp.S` / `<= 0.S` 检测溢出/下溢。
4. **EXTRA = outMantBits + 3**：这 3 个额外位精确等于 G/R/S 三位，不多不少；`q_rem` 提供整除留下的所有更低位 sticky。这是最小够用的精度配置。

---

## Part B · 数值例子完整走查

### B.0 选例

为了让 scale 的尾数可以表示 1.25，得选 **M ≥ 2** 的 scale 格式。这里用 **UE6M2** 替代 UE7M1（UE7M1 只能表示 1.0 或 1.5），输出取 **E4M3**。

| 量 | 值 |
|---|---|
| fp32 输入 | 1.5 |
| scale 值 | 1.25（用 UE6M2 编码）|
| 输出格式 | E4M3 |
| 期望结果 | 1.5 / 1.25 = **1.2** |

E4M3 周围可表示的值：1.125, 1.25, 1.375。1.2 距 1.25 最近 → RNE 应给出 **1.25**，编码 `0_0111_010`。

### B.1 输入信号编码（位宽 / 各位权重）

**fp32_in（32 bit）**：1.5 = 1.1₂ × 2⁰

| 字段 | 位 | 权重 | 值 |
|---|---|---|---|
| sign | [31] | ± | `0` |
| fp32_exp | [30:23] | biased，bias=127 | `0111_1111` = 127 |
| fp32_man | [22:0] | 2⁻¹ … 2⁻²³ | `100_0000_0000_0000_0000_0000` |

完整：`0_01111111_10000000000000000000000`

**shared_scale（8 bit，UE6M2：E=6, M=2, scaleBias=31）**：1.25 = 1.01₂ × 2⁰

| 字段 | 位 | 权重 | 值 |
|---|---|---|---|
| scale_biased_exp | [7:2] | biased，bias=31 | `011111` = 31 |
| scale_mant_raw | [1:0] | 2⁻¹, 2⁻² | `01` |

完整：`shared_scale = 0b01111101 = 0x7D`

### B.2 设计期常量

```
M           = 2
E           = 6
scaleBias   = 31
outBias     = 7
correction  = scaleBias − 127 + outBias = 31 − 127 + 7 = −89
EXTRA       = outMantBits + 3 = 3 + 3 = 6
IMPL        = 23 − M + EXTRA = 23 − 2 + 6 = 27
outMantBits = 3
outExpBits  = 4
outMaxNormalExp = 14
```

`correction` 的语义：把"FP32 bias=127 / scale bias=31 / 输出 bias=7"三种 bias 在指数减法时统一到输出端。

### B.3 解包 scale

| 信号 | 宽 | 数值 | 各位权重 |
|---|---|---|---|
| `scale_biased_exp` | 6 | `011111` = 31 | 位 i 权重 2ⁱ（biased 形式）|
| `scale_mant_raw` | 2 | `01` = 1 | 位 1 权重 2⁻¹，位 0 权重 2⁻² |
| `scale_full_mant` = `{scale_biased_exp.orR, scale_mant_raw}` | 3 (M+1) | `101` = **5** | 位 2 是 implicit-1（如果 exp≠0），位 1/0 是尾数 |

**关键约定**：把 `scale_full_mant` 当成无符号整数 N，则 N 表示 `(1.scale_mant) × 2^M`。
检验：`5 / 2² = 1.25` ✓

### B.4 指数路径

```
out_exp_raw = fp32_exp_s − scale_biased_exp.zext + correction.S
            = 127 − 31 + (−89)
            = 7   (SInt(9))
```

**为什么 correction = scaleBias − 127 + outBias？**
真实指数 = `(fp32_exp − 127) − (scale_exp − scaleBias) = fp32_exp − scale_exp + (scaleBias − 127)`
转成输出 biased 形式还要 `+ outBias`，合并后就是 `correction`。

验证：1.5/1.25 真实指数为 0 → 输出 biased exp 应为 `0 + outBias = 7` ✓（先不算 normAdj 和 mantOverflow）。

### B.5 拼接 qNum

`fp32FullMant`（24 bit）= `{fp32_exp.orR, fp32_man}`：

| 位 | 权重（相对 fp32 尾数）| 值 |
|---|---|---|
| [23] | 2⁰（implicit-1）| `1` |
| [22] | 2⁻¹ | `1` |
| [21:0] | 2⁻² … 2⁻²³ | `0…0` |

整数值 N = 1.5 × 2²³ = 12,582,912 = `0xC00000`。

`qNum`（30 bit）= `{fp32FullMant, 0(EXTRA=6)}`：

| 位 | 权重 |
|---|---|
| [29] | 2⁰（fp32 implicit-1，被左移了 EXTRA 位）|
| [28] | 2⁻¹ |
| [6] | 2⁻²³ |
| [5:0] | 0（精度填充）|

整数值 N = 12,582,912 × 2⁶ = **805,306,368** = `0x30000000`

**语义**：把 qNum 当整数 N，N = `(1.fp32_mant) × 2^(23 + EXTRA)`。

### B.6 整数除法

```
safeDenom = 5            (scale_full_mant ≠ 0，无替换)
q_int     = 805,306,368 / 5 = 161,061,273
q_rem     = 805,306,368 mod 5 = 3
```

**q_int 的语义**：
```
q_int = floor( (1.fp32_mant × 2^(23+EXTRA)) / (1.scale_mant × 2^M) )
      = floor( q × 2^(23 + EXTRA − M) )
      = floor( q × 2^IMPL )            // IMPL = 27
```
其中 `q = 1.fp32_mant / 1.scale_mant = 1.5/1.25 = 1.2`，落在 [1, 2)。

验证：`1.2 × 2²⁷ = 161,061,273.6 → floor = 161,061,273` ✓

**q_int 二进制**（30 bit）：`161,061,273 = 0x09999999`，对应 1.2 的二进制 `1.0011_0011_0011…`

| 位 [29:0] | 值 | 含义 |
|---|---|---|
| [29:28] | `00` | 留余 |
| **[27]** | **`1`** | **q 的 implicit-1（因为 q ≥ 1）** |
| [26:24] | `001` | q 的 3 位尾数 → out_mant_raw |
| [23] | `1` | guardBit |
| [22] | `0` | roundBit |
| [21:0] | `…0011_0011…` ≠ 0 | stickyQ |

### B.7 q_int 字段提取

```
qGeq1 = q_int(27) = 1   →  走 qGeq1 路径
```

| 字段 | 取窗 | 位 | 值 |
|---|---|---|---|
| out_mant_raw | `q_int[IMPL-1 : IMPL-outMantBits]` | [26:24] | `001` = 1 |
| guardBit | `q_int[IMPL-outMantBits-1]` | [23] | `1` |
| roundBit | `q_int[IMPL-outMantBits-2]` | [22] | `0` |
| stickyQ_geq1 | `q_int[IMPL-outMantBits-3 : 0].orR` | [21:0] | `1` |

`stickyBit = (q_rem ≠ 0) ∨ stickyQ_geq1 = (3 ≠ 0) ∨ 1 = 1`

### B.8 RNE 进位与尾数定型

```
roundUp        = guardBit ∧ (out_mant_raw[0] ∨ roundBit ∨ stickyBit)
               = 1 ∧ (1 ∨ 0 ∨ 1) = 1
out_mant_carry = out_mant_raw +& roundUp = 1 + 1 = 2  (4 bit: 0010)
mantOverflow   = out_mant_carry[3] = 0
out_mant       = out_mant_carry[2:0] = 010
```

### B.9 指数补偿与最终封装

```
normAdj      = qGeq1 ? 0 : −1   = 0
out_exp_full = out_exp_raw + normAdj + mantOverflow = 7 + 0 + 0 = 7
underflow    = (fp32 zero?) ∨ (7 ≤ 0) = 0
overflow     = (7 > 14) = 0
out_exp_clamped = 0111 (4 bit)
elem_out     = {0, 0111, 010} = 0_0111_010 = 0x3A
```

**解码**：`1.010₂ × 2^(7−7) = 1.25` ✓ 与 RNE 预期一致。

### B.10 位宽 / 权重一览表

| 信号 | 宽 | 整数语义（=权重）|
|---|---|---|
| `fp32_man` | 23 | 表示 `frac × 2²³`，bit i 权重 = 2^(i−23+0) 相对 1.x |
| `fp32FullMant` | 24 | 表示 `1.fp32_mant × 2²³`，bit 23 = implicit-1 |
| `qNum` | 24 + EXTRA = 30 | 表示 `1.fp32_mant × 2^(23+EXTRA)`，bit 29 = implicit-1 |
| `scale_mant_raw` | M | 表示 `frac × 2^M`，bit i 权重 2^(i−M) |
| `scale_full_mant` | M+1 | 表示 `1.scale_mant × 2^M`，bit M = implicit-1 |
| `q_int` | 30 | 表示 `floor(q × 2^IMPL)`，IMPL = 23−M+EXTRA |
| q_int 上 implicit-1 位置 | — | `IMPL` (q≥1) 或 `IMPL−1` (q<1) |
| `out_exp_raw` | SInt(≥10) | biased，已含 `correction` 三 bias 修正 |
| `out_mant_carry` | outMantBits+1 | 多 1 bit 容纳 1.111…→10.000 进位 |
| `out_exp_full` | SInt | = `out_exp_raw + normAdj(±1) + mantOverflow(0/1)` |

### B.11 三个"为什么"小结

1. **EXTRA = outMantBits + 3**：除法商需要给 RNE 留 G/R/S 三位（多 3 bit），更低位的精度损失通过 `q_rem` 兜底。
2. **IMPL = 23 − M + EXTRA**：被除数权重 `2^(23+EXTRA)` 减去除数权重 `2^M`，所以商的整数值 `q × 2^IMPL`。
3. **qGeq1 双窗口**：q ∈ [0.5, 2) 跨越 1.0，q ≥ 1 时 implicit-1 在 IMPL，q < 1 时整商整体小一倍 → implicit-1 在 IMPL − 1，所有取窗下移一位，并且 normAdj 给指数 −1 补偿。

---

## Part C · 硬件代价分析与优化方向（先放结论，后续讨论）

### C.1 当前实现代价

`q_int = qNum / safeDenom` / `q_rem = qNum % safeDenom`：
- **位宽不小**：qNum 是 `(24 + EXTRA)` = 27~30 bit；safeDenom 是 `(M+1)` bit (2~7 bit)。综合工具默认展开成 ~30-bit / 7-bit 的恢复式阵列除法器。
- **实例化数巨大**：每个 `RequantBlock` 例化 blockSize 个 `FP32ToMXFP8`，顶层又是 tileRows 份。blk64 × 4 行 → **256 个除法器**全部并行展开。
- **冗余度高**：同一 block 内所有元素**共享同一个 scale**，64 个除法器在重复地对相同 denom 求倒数。

### C.2 优化方向（按收益排序）

**1. 倒数 LUT + 共享一次（最值得做）**

`scale_full_mant ∈ [1, 2)` 只有 `2^M` 种取值（M ∈ {1,2,4,6} → 2/4/16/64 个 entry）。在 `RequantBlock` 层每块只算一次倒数，把 `recip` 当作端口送给每个 `FP32ToMXFP8`，内部用乘法替代除法。

收益估算（blk64×4，UE4M4，E4M3）：
- 256 个 ~30/5 bit 除法器 → 256 个 24×9 bit 乘法器 + 4 个 16-entry LUT
- 面积下降 5–10×，关键路径从一长串 subtract-shift 变成一次乘法树（约 log₂(24) 级）

**2. 移除 `safeDenom` 保护**

`Mux(scale_full_mant === 0.U, 1.U, ...)` 只在整块全 0 时触发。可以在 block 级用 `scale_biased_exp === 0` 直接把 `underflow` 强制拉高（element 输出 0），把这个 mux 从每个 element 的关键路径上移走。

**3. Sticky 位简化**

切到乘法路径后 `q_rem` 整个消失—— `q_full` 低位自然带有原本要用 `q_rem != 0` 表达的 sticky 信息，可以并到一条 OR-reduce。

**4. （可选）裁剪输出精度**

只需要 `outMantBits + 3` 个小数位，但综合器没法替你裁除法器；改成乘法后位宽是设计时常量，能精准给出 `W = outMantBits + 6` 之类的最小值。
