ScaleAddition 的冗余
ScaleAddition 做三件事：


scaleExpA + scaleExpB → adjExpScaleSum     （整数加法，轻量）
scaleA.mant × scaleB.mant → scaleMantProd  （小乘法）
scaleMantProd × treeMant → outMant          （主乘法，宽）
关键观察：当 stype = UE8M0 时 mantScaleWidth=0，两个 scale 的 mant 都退化为 1.U(1.W)：


scaleMantProduct = 1.U(1.W) * 1.U(1.W) = 1.U(2.W)   // 值永远是 1
outMant          = 1.U(2.W) * treeMant                // = treeMant 补零扩展 2 bit
即 ScaleAddition 对 UE8M0 的全部贡献是：把 treeMant 前面补两个 0，然后做一次纯整数加法算指数。两级乘法器在这里产生的硬件是无效的。

从数据来看（trivialMult=True 列）：

配置	opMantW	saMantW	冗余
INT8×INT8 / UE8M0	14	16	saMant = 00++treeMant
INT8×E4M3 / UE8M0	11	13	saMant = 00++treeMant
E4M3×E4M3 / UE8M0	8	10	saMant = 00++treeMant
E5M2×E5M2 / UE8M0	6	8	saMant = 00++treeMant
ScaleToFP32 的 LZC 退化
因为 FixedFPReductionTree 已经归一化了 treeMant（MSB 固定在 bit opMantW-1），所以 saMant 必然形如 0b00_1xxx...xxx。

这意味着 ScaleToFP32 里的 PriorityEncoder(Reverse(saMant)) 对 UE8M0 永远返回 2——它是一个恒常值，不依赖数据。目前代码却在一个长达 saMantW bit 的信号上跑完整的 LZC + 桶形移位器。

最终结论：对 UE8M0，ScaleToFP32 的变长移位永远等价于固定左移 2 位 + 截取 23 位：


fp32_mant = Cat(treeMant[opMantW-2 : 0], 0.U((24-opMantW).W))[22:0]
             ← 完全是连线，无任何逻辑门
fp32_exp  = treeExp + adjExpScaleA + adjExpScaleB + (127 + opMantW - 1 - fracBits_elem)
             ← 纯整数加法
优化结构
这形成了一个 2D 优化空间：element class × scale type：


                UE8M0 (trivial)          UE6M2/UE5M3/UE4M4 (real mult)
              ┌──────────────────────────┬───────────────────────────────┐
Class A       │ int add → wire → FP32   │ int add → small mult → FP32   │
(INT8×INT8)   │ 零乘法器，零 LZC        │ 乘法器不可避免                │
              ├──────────────────────────┼───────────────────────────────┤
Class B/C/D   │ int/fixed add → wire    │ 同上，两级 LZC 均必要         │
(FP8 mix)     │ → FP32                  │                               │
              └──────────────────────────┴───────────────────────────────┘
UE8M0 列可以把 ScaleAddition + ScaleToFP32 合并为一个极简的 DirectToFP32：去掉两个乘法器、一个 saMantW bit 的 LZC、一个桶形移位器。

非 UE8M0 列不存在这个冗余，两次 LZC 都是必要的（第一次在 tree 里缩窄乘法器入口，第二次在 ScaleToFP32 里归一化乘积结果）。