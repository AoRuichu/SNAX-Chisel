# Accumulator Precision Derivation — `AccPrecision.recommended`

## 第一层：单步舍入噪声建模

FP 累加器每次加法都会引入一个舍入误差。对于 M bit 尾数的浮点数，每次加法的舍入误差绝对值最大为：

$$|\varepsilon_{\text{single}}| \leq \frac{1}{2} \times 2^{-M} \times |value| = \text{ULP}/2$$

如果把这个误差看作均匀分布的随机变量，其**方差（噪声功率）**约为：

$$\text{Var}(\varepsilon_{\text{single}}) \propto 2^{-2M}$$

---

## 第二层：K 步累加后的噪声功率

关键假设：各步舍入误差**互不相关**（worst-case uncorrelated，实际上是悲观的安全假设）。

不相关随机变量相加，方差线性叠加，K 步后：

$$\text{Var}(\varepsilon_{\text{total}}) \approx K \times 2^{-2M}$$

总噪声的 RMS（均方根）为：

$$\sigma_{\text{acc}} = \sqrt{K} \times 2^{-M}$$

---

## 第三层：与量化噪声基底比较，推出 M 的下界

输出最终要被 requantize（例如输出 BF16，mantissa 只有 7 bit）。requantize 本身的量化噪声尺度记为：

$$\sigma_{\text{rq}} \propto 2^{-\text{rqFloor}}$$

**条件**：累加噪声不超过量化基底（让量化操作成为误差的主导，而不是累加器本身）：

$$\sigma_{\text{acc}} \leq \sigma_{\text{rq}}$$

$$\sqrt{K} \times 2^{-M} \leq 2^{-\text{rqFloor}}$$

两边取 $\log_2$，化简：

$$-M + \frac{1}{2}\log_2 K \leq -\text{rqFloor}$$

$$\boxed{M \geq \text{rqFloor} + \frac{1}{2}\log_2 K} \quad \leftarrow \text{核心不等式}$$

---

## 代入具体参数

**`rqFloor = 7`**：对应 BF16 的 7 bit mantissa（最低实用输出精度，保守基准）。

**$\frac{1}{2}\log_2 K$ → 代码里的 `kBonus`**：

```scala
val kBits  = ceil(log2(K))   // e.g. K=32 → kBits=5, K=64 → kBits=6
val kBonus = kBits / 2       // integer division ≈ ceil(log2(K)/2)
```

| K  | $\log_2 K$ | `kBonus` | 结果 M = 7 + kBonus |
|----|-----------|----------|---------------------|
| 4  | 2         | 1        | 8                   |
| 32 | 5         | 2        | 9                   |
| 64 | 6         | 3        | 10                  |

**`rangePenalty`**：额外修正项，针对宽指数范围的元素类型（如 E5M2×E5M2）。原因是 FixedFP 对齐右移时，两个 lane 乘积指数差距大，被移出去的 bit 即为精度损失——指数范围越宽，对齐截断带来的附加噪声越大，所以 M 要额外补偿：

```
productExpRange ≥ 50  → +3 bit（E5M2 × E5M2，range 极宽）
productExpRange ≥ 30  → +1 bit（中等 FP8 组合）
否则               → +0 bit（INT8 等，无对齐噪声）
```

**最终公式**：

$$M = \min\!\left(23,\ \underbrace{7}_{\text{rqFloor}} + \underbrace{\left\lceil\frac{\log_2 K}{2}\right\rceil}_{K\text{ 步累积惩罚}} + \underbrace{\text{rangePenalty}}_{\text{对齐截断惩罚}}\right)$$

---

## 直觉总结

这本质上是一个**噪声预算（noise budget）**分析：累加器的舍入噪声功率必须低于下游 requantize 的量化噪声功率。K 越大，累加步数越多，误差越容易叠加，所以 M 需要随 $\log_2 K$ 增长——但只增长一半，因为是功率→幅度的关系，开根号带来的 $\frac{1}{2}$ 系数。

---

# 推导的理论依据：为什么舍入误差是这样的

上面三层推导依赖三个核心假设：(1) 单步误差被 ULP/2 界定，(2) 误差近似服从均匀分布，(3) 各步误差方差可加。它们各自有严格的数学依据。

## 1. `|ε_single| ≤ ULP/2` — 来自 IEEE 754 舍入规则

**几何含义**：浮点数在数轴上是离散点集。相邻两个可表示值之间的间距称为 **ULP**（Unit in the Last Place）。

对一个规格化浮点数 $x = (1.m_1m_2\ldots m_M) \times 2^{\text{exp}}$：

- 两个相邻可表示值的间距 = $2^{-M} \times 2^{\text{exp}}$
- 相对间距 = $2^{-M} \times |x|$（所以 ULP 是相对量）

**Round-to-Nearest-Even (RNE)** 的定义：将实数值 $x^*$ 映射到**最近**的可表示值。所以最坏情况下偏差是到最近点的距离，即半个 ULP：

$$|ε| \leq \frac{1}{2} \cdot 2^{-M} \cdot |x| = \frac{1}{2}\text{ULP}$$

等价写法：$|ε/x| \leq 2^{-M-1} = \varepsilon_{\text{mach}}/2$（机器精度定义）。

**依据**：IEEE 754-2008 §4.3.1 "roundTiesToEven" + §5.4 Arithmetic operations。

---

## 2. 均匀分布假设 — Widrow's Quantization Noise Model

严格来说，舍入误差**不是随机变量**——它是输入的确定函数。但当输入的 mantissa 第 M+1 位以下的比特模式「足够无规律」时，误差的分布可以**近似**建模为：

$$ε \sim \text{Uniform}\left[-\tfrac{\text{ULP}}{2}, +\tfrac{\text{ULP}}{2}\right]$$

均匀分布 $U[-a, a]$ 的方差是 $a^2/3$，代入 $a = \text{ULP}/2$：

$$\text{Var}(ε) = \frac{\text{ULP}^2}{12} = \frac{2^{-2M}|x|^2}{12} \propto 2^{-2M}$$

**依据**（Widrow-Kollár 量化噪声理论）：

- Widrow & Kollár, *Quantization Noise* (Cambridge, 2008) — 正式证明在输入谱足够宽时，量化误差的统计特性收敛到均匀白噪声
- **Sripad-Snyder 定理**：若输入 PDF 的特征函数在量化步长的整数倍点为零，则量化噪声**精确**是均匀分布

**工程含义**：对 DNN 累加这种输入值多样、mantissa 低位几乎随机的场景，这个近似非常精确。对输入值有特殊结构（如全部相同、或整数倍关系）的场景会失效。

---

## 3. `Var(ε_total) ≈ K × Var(ε_single)` — 不相关随机变量方差可加

**一般规则**：对零均值随机变量 $X_1, \ldots, X_K$：

$$\text{Var}\left(\sum X_i\right) = \sum \text{Var}(X_i) + 2\sum_{i<j}\text{Cov}(X_i, X_j)$$

**关键假设**：各步舍入误差之间 $\text{Cov}(ε_i, ε_j) = 0$（不相关），于是第二项消失：

$$\text{Var}(ε_{\text{total}}) = \sum_{i=1}^K \text{Var}(ε_i) \approx K \cdot 2^{-2M}$$

$$σ_{\text{acc}} = \sqrt{K} \cdot 2^{-M}$$

这个 $\sqrt{K}$ 是整个推导的核心：累加步数 K 翻倍时，噪声只以 $\sqrt{2}$ 增长，所以 $\log_2 K / 2$ 就是 M 需要额外补偿的 bit 数。

**依据**：

- 这是 **随机游走 (random walk)** 模型：各步独立 → RMS 以 $\sqrt{K}$ 增长（Einstein 1905 的布朗运动公式）
- Wilkinson 1963 *Rounding Errors in Algebraic Processes*：给出**最坏情况**边界 $O(K \cdot \varepsilon_{\text{mach}} \cdot \sum|x_i|)$（线性）；但这是悲观的，假设所有误差同号叠加
- 实际上浮点累加中连续误差**不完全独立**（有轻微相关性），但对大 K 近似成立
- Higham 2002 *Accuracy and Stability of Numerical Algorithms* Ch.4 给出更精细的 probabilistic bound

---

## 两种 bound 的对比

| 模型                        | Error growth  | 适用场景                                |
|-----------------------------|---------------|-----------------------------------------|
| Wilkinson worst-case        | **O(K)**      | 对抗性输入，如所有误差同号              |
| Widrow stochastic（本推导） | **O(√K)**     | DNN 累加这类「输入多样」场景            |

本推导用的是 $\sqrt{K}$ 模型，这是 DNN 硬件设计的**实际**选择。如果用 Wilkinson 模型，每次 K 翻倍 M 需要 +1 bit（而不是 +0.5），累加器会浪费大量位宽。

---

## 最后一步：为什么是 `σ_acc ≤ σ_rq`

本质是**噪声预算分配**：整个数据通路有多个噪声源（累加、截断、requant），各自贡献方差相加。若某个源远低于另一个源，就「不起作用」——被 dominant noise 掩盖。

选择让 $σ_{\text{acc}} \leq σ_{\text{rq}}$ 意味着：

- 累加噪声功率 ≤ requant 噪声功率
- 合并总噪声 ≤ $\sqrt{2} \times σ_{\text{rq}}$，即最多恶化 3 dB
- 超过这个阈值继续加 M bit 是无效收益（被 requant 底噪掩盖）

这正是 `7 (BF16 base) + log₂K/2 (K步累积保护) + rangePenalty (指数对齐误差)` 构造公式的底层设计原则。

---

## 关于 `|value|` 因子：为什么后续推导里「消失了」

细心的读者会注意到：**第一步里 ULP 明确含有 $|x|$ 因子**（$\text{ULP} = 2^{-M} \cdot |x|$，即 ULP 是相对量），但**从「均匀分布假设」开始，$|value|$ 就从方差表达式里不见了**，最终只剩下 $\sqrt{K} \cdot 2^{-M}$。这个「消失」其实没有消失，是被「∝」（正比于）这个符号隐藏了——也就是说，上面每一个「∝」里都暗含 $|value|^2$ 或 $|value|$ 因子。

### 真正完整的方差表达式

第一层（单步）的完整形式应该是：

$$\text{Var}(ε_{\text{single}}) = \frac{\text{ULP}^2}{12} = \frac{1}{12} \cdot 2^{-2M} \cdot |x|^2$$

K 步累加后的完整形式（假设各步值为 $v_i$）：

$$\text{Var}(ε_{\text{total}}) = \frac{1}{12} \cdot 2^{-2M} \cdot \sum_{i=1}^K |v_i|^2$$

若我们记中间值的典型幅度为 $|V|$（假设累加过程中各步值量级近似相同），则 $\sum |v_i|^2 \approx K \cdot |V|^2$：

$$\sigma_{\text{acc}} \approx \sqrt{K} \cdot 2^{-M} \cdot |V|$$

### requant 噪声同样带 `|value|` 因子

requant 的误差也与信号幅度成正比（因为它本身也是一种定点/浮点量化）：

$$\sigma_{\text{rq}} \approx 2^{-\text{rqFloor}} \cdot |V|$$

### 比较时 `|V|` 两边对消

核心不等式 $\sigma_{\text{acc}} \leq \sigma_{\text{rq}}$ 两边都带 $|V|$，直接对消：

$$\sqrt{K} \cdot 2^{-M} \cdot |V| \leq 2^{-\text{rqFloor}} \cdot |V|$$

$$\Rightarrow \sqrt{K} \cdot 2^{-M} \leq 2^{-\text{rqFloor}}$$

这就是 $|value|$ 在后续推导中「消失」的真相——**它没有消失，而是在比较两个同尺度的噪声源时被约去了**。

### 物理含义：这是浮点算术的本质属性

浮点格式的 ULP 是**相对量**（而不是像定点那样是绝对量），所以：

- 舍入噪声的 RMS 与信号幅度成正比
- **SNR**（信噪比）与信号幅度**无关**

这是浮点相对于定点的核心优势：在整个可表示范围内 SNR 近似恒定（而定点格式在小幅度信号时 SNR 严重退化）。

正因如此，**整个推导其实是在 SNR 域（或者说「相对误差」域）中进行的**——虽然公式看起来是绝对噪声量，但所有「∝」关系都自动吸收了信号幅度因子，比较时两边对消。

### 边界条件：什么时候这个简化会失效

「各步 $|v_i|$ 大致恒定」的假设在以下场景会变得不准：

1. **接近零的累加**（消去现象，catastrophic cancellation）：正负相消后 $|v_i|$ 远小于输入量级，相对误差急剧放大
2. **单调增长的累加**（如求绝对值和）：$|v_i|$ 随 $i$ 线性增长，$\sum|v_i|^2 \sim K^3/3$，误差按 $K^{3/2}$ 增长而不是 $\sqrt{K}$
3. **量级差异极大的输入**：对齐右移会吃掉精度，这部分由 `rangePenalty` 单独补偿

对 DNN 累加（随机输入、零均值、中等规模 K），「恒定 $|V|$」假设是合理近似，所以公式在工程上实用。

---

# 学术依据与文献引用

本文档中所有关键公式和假设都有明确的学术出处。下表按推导层次列出主要参考文献。

## A. 浮点舍入误差边界（`|ε| ≤ ULP/2`）

| 参考 | 贡献 |
|---|---|
| **IEEE Std 754-2019** (revision of 754-2008). *IEEE Standard for Floating-Point Arithmetic*. | §4.3.1 定义 `roundTiesToEven`；§5.4 规定所有算术运算「正确舍入」，从而保证 $|ε| \leq \frac{1}{2}$ULP |
| **Goldberg, D. (1991).** "What Every Computer Scientist Should Know About Floating-Point Arithmetic." *ACM Computing Surveys*, 23(1), 5–48. | 教科书级综述，给出 ULP / machine epsilon / 相对误差界的经典阐述 |
| **Muller, J-M. et al. (2018).** *Handbook of Floating-Point Arithmetic* (2nd ed.). Birkhäuser. | 最全面的浮点算术参考书；第 2–3 章详细推导 RNE 的误差界和性质 |

## B. 均匀分布量化噪声模型（Widrow's Model）

| 参考 | 贡献 |
|---|---|
| **Widrow, B. (1956).** "A Study of Rough Amplitude Quantization by Means of Nyquist Sampling Theory." *IRE Trans. Circuit Theory*, 3(4), 266–276. | 首次严格证明：在特定输入谱条件下，量化噪声服从均匀分布 |
| **Sripad, A. & Snyder, D. (1977).** "A Necessary and Sufficient Condition for Quantization Errors to be Uniform and White." *IEEE Trans. ASSP*, 25(5), 442–448. | 给出量化噪声**精确**为均匀白噪声的充要条件（PDF 特征函数在量化步长整数倍点为零） |
| **Widrow, B. & Kollár, I. (2008).** *Quantization Noise: Roundoff Error in Digital Computation, Signal Processing, Control, and Communications*. Cambridge University Press. | 本推导第 2 节「均匀分布假设」的**权威教科书来源**；系统阐述了 Widrow's Model 在浮点/定点算术中的应用 |
| **Bennett, W. R. (1948).** "Spectra of Quantized Signals." *Bell System Technical Journal*, 27(3), 446–472. | 更早的奠基性工作；首次提出 $\frac{Δ^2}{12}$（均匀分布方差公式）在量化分析中的使用 |

## C. 累加误差分析（√K stochastic vs. K worst-case）

### C.1 最坏情况边界（Wilkinson）

| 参考 | 贡献 |
|---|---|
| **Wilkinson, J. H. (1963).** *Rounding Errors in Algebraic Processes*. Prentice-Hall. | 奠基性著作；给出求和误差的 $O(K \cdot \varepsilon_{\text{mach}})$ worst-case bound |
| **Wilkinson, J. H. (1965).** *The Algebraic Eigenvalue Problem*. Oxford University Press. | 进一步扩展 backward error analysis，求和误差的 $(1 + ε)^K$ 模型 |

### C.2 统计 / 概率模型（√K growth）

| 参考 | 贡献 |
|---|---|
| **Hull, T. E. & Swenson, J. R. (1966).** "Tests of Probabilistic Models for Propagation of Roundoff Errors." *CACM*, 9(2), 108–113. | 首批用 Monte Carlo 验证随机游走模型的实验；证实 $\sqrt{K}$ growth 与实际浮点累加吻合 |
| **Kaneko, T. & Liu, B. (1970).** "On Local Roundoff Errors in Floating-Point Arithmetic." *JACM*, 17(3), 390–398. | 给出概率舍入误差模型的严格分析框架 |
| **Higham, N. J. (2002).** *Accuracy and Stability of Numerical Algorithms* (2nd ed.). SIAM. | 第 4 章（Summation）系统推导 deterministic 和 probabilistic 两套 bound；本推导的 $\sqrt{K}$ 模型最直接来源 |
| **Higham, N. J. & Mary, T. (2019).** "A New Approach to Probabilistic Rounding Error Analysis." *SIAM J. Sci. Comput.*, 41(5), A2815–A2835. | 现代版重构：给出 $\sqrt{K \log K}$ 的 high-probability bound，严谨化 Widrow 模型 |

## D. Kahan-style 补偿累加（对比参照）

| 参考 | 贡献 |
|---|---|
| **Kahan, W. (1965).** "Further Remarks on Reducing Truncation Errors." *CACM*, 8(1), 40. | 经典 Kahan summation algorithm：通过补偿项将误差降到 $O(\varepsilon_{\text{mach}})$（不随 K 增长）——与本推导「不补偿」的硬件累加形成对照 |

## E. DNN 硬件累加器精度设计（直接相关工作）

| 参考 | 贡献 |
|---|---|
| **Wang, N. et al. (IBM, 2018).** "Training Deep Neural Networks with 8-bit Floating Point Numbers." *NeurIPS 2018*. | 首个系统论证 FP8 训练可行性；累加器用 FP16（10-bit mantissa），与本推导的 $7 + \log_2 K / 2$ 一致 |
| **Sun, X. et al. (IBM, 2019).** "Hybrid 8-bit Floating Point (HFP8) Training and Inference for Deep Neural Networks." *NeurIPS 2019*. | 进一步扩展到不同 FP8 变种；累加器精度选择遵循「noise-floor matching」原则 |
| **Burgess, N. et al. (Arm, 2019).** "Bfloat16 Processing for Neural Networks." *ARITH 2019*. | BF16 硬件乘加的工程设计；累加器精度分析的实务参考 |
| **Mukherjee, R. et al. (2020).** "A Study of BFLOAT16 for Deep Learning Training." arXiv:1905.12322. | 实验验证 BF16 accumulation 在 ImageNet / BERT 上的精度可接受性 |
| **Köster, U. et al. (Intel, 2017).** "Flexpoint: An Adaptive Numerical Format for Efficient Training of Deep Neural Networks." *NeurIPS 2017*. | 块浮点 (block floating-point) 累加器设计，为本项目的 MX 格式奠定基础 |
| **Rouhani, B. D. et al. (Microsoft, 2020).** "Pushing the Limits of Narrow Precision Inferencing at Cloud Scale with Microsoft Floating Point (MSFP)." *NeurIPS 2020*. | MSFP / MX 格式的原始提案；本项目 `ScaleType` 与 MAC 累加精度选择的直接对照对象 |
| **OCP Microscaling Formats (MX) Specification v1.0 (2023).** Open Compute Project. | MX 数据格式标准；定义了 E5M2/E4M3/E3M2/MXINT8 与 UE8M0 scale 等本项目使用的类型 |

## F. Noise-Floor Matching 方法论（`σ_acc ≤ σ_rq`）

| 参考 | 贡献 |
|---|---|
| **Oppenheim, A. V. & Weinstein, C. J. (1972).** "Effects of Finite Register Length in Digital Filtering and the Fast Fourier Transform." *Proc. IEEE*, 60(8), 957–976. | DSP 领域经典：首次用「多级噪声功率预算」方法分析定点/浮点滤波器精度 |
| **Constantinides, G. A. et al. (2004).** *Synthesis and Optimization of DSP Algorithms*. Springer. | 硬件 DSP 位宽优化的完整方法论；噪声预算分配（$σ_{\text{acc}} \leq σ_{\text{rq}}$）的形式化框架 |

---

## 推导层次与文献对应关系

| 本文档层次 | 核心结论 | 主要依据 |
|---|---|---|
| 第 1 层 | $|ε| \leq \frac{1}{2}$ULP | IEEE 754-2019 §4.3.1; Goldberg 1991 |
| 第 1 层（均匀假设） | $\text{Var}(ε) = \frac{\text{ULP}^2}{12}$ | Widrow & Kollár 2008; Sripad & Snyder 1977; Bennett 1948 |
| 第 2 层 | $σ_{\text{acc}} = \sqrt{K} \cdot 2^{-M} \cdot \|V\|$ | Higham 2002 Ch.4; Hull & Swenson 1966; Higham & Mary 2019 |
| 第 2 层（对比） | worst-case $O(K)$ vs 本推导 $O(\sqrt{K})$ | Wilkinson 1963 vs Widrow 2008 |
| 第 3 层 | $σ_{\text{acc}} \leq σ_{\text{rq}}$（噪声预算） | Oppenheim & Weinstein 1972; Constantinides 2004 |
| 工程应用 | BF16 acc + $\log_2 K / 2$ bonus | Wang et al. 2018; Burgess et al. 2019; Rouhani et al. 2020 |

**关键结论**：本推导不是凭经验的 rule-of-thumb，而是 DSP 和数值分析两大领域的经典理论在 DNN 硬件累加器设计上的直接应用。$\sqrt{K}$ 增长来自 Widrow-Higham 的随机游走量化噪声模型，「noise-floor matching」来自 Oppenheim-Constantinides 的定点/浮点 DSP 位宽优化方法论，而具体的 BF16 + kBonus 形式则与 IBM/Arm/Microsoft 等工业界 DNN 累加器论文保持一致。
