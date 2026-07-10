package mx.mac

/** Print a CSV table of the K-derived accumulator mantissa width
 *  M_acc^(K) for every (typeA, typeB, scaleType, K) combination used
 *  in the validation / HW DSE sweeps. The output is intended as the
 *  analytical counterpart of Cuyckens et al. (2024) Fig. 4 (right):
 *  the critical mantissa width for which addition error equals
 *  quantization error, here computed by formula rather than by
 *  empirical sweep across M_acc.
 *
 *  Columns:
 *    config             — typeA × typeB / scaleType
 *    K                  — accumulation depth
 *    productExpRange    — R, exponent span across products
 *    resScaleAddMantWidth — width of internal sum-mantissa
 *    structural_cap     — non-UE8M0 RNE structural ceiling on M_acc
 *    M_acc_recommended  — K-derived 7 + floor(log2 K / 2) + pi(R)
 *    M_acc_actual       — min(recommended, cap), value actually used in HW
 */
object DerivedMaccTableMain extends App {

  private case class PairCfg(label: String, tA: ElementType, tB: ElementType)
  private val pairs = Seq(
    PairCfg("INT8xINT8",  MXFormats.INT8, MXFormats.INT8),
    PairCfg("E3M2xE3M2",  MXFormats.E3M2, MXFormats.E3M2),
    PairCfg("E4M3xE4M3",  MXFormats.E4M3, MXFormats.E4M3),
    PairCfg("E5M2xE5M2",  MXFormats.E5M2, MXFormats.E5M2),
    PairCfg("E5M2xE4M3",  MXFormats.E5M2, MXFormats.E4M3),
    PairCfg("E4M3xINT8",  MXFormats.E4M3, MXFormats.INT8),
    PairCfg("E5M2xINT8",  MXFormats.E5M2, MXFormats.INT8),
  )
  private val scales = Seq(
    ScaleFormats.UE8M0,
    ScaleFormats.UE6M2,
    ScaleFormats.UE4M4,
  )
  private val Ks = Seq(8192, 16384)

  println("config,K,productExpRange,resScaleAddMantWidth,structural_cap,M_acc_recommended,M_acc_actual")
  for (p <- pairs; sT <- scales; K <- Ks) {
    val scfg = ScaleAddConfig(p.tA, p.tB, sT)
    val R         = scfg.productExpRange
    val mantW     = scfg.resScaleAddMantWidth
    val cap       =
      if (sT.mantScaleWidth == 0) 23
      else math.max(7, mantW - 3)
    val rec       = AccPrecision.recommended(scfg, K)
    val actual    = math.min(rec, cap)
    println(s"${p.label}_${sT.name},$K,$R,$mantW,$cap,$rec,$actual")
  }
}
