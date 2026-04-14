package mx.array

import mx.mac.{ScaleAddConfig, MXFormats, ScaleFormats}
import mx.requant.RequantConfig

/**
 * Combined elaboration-time configuration for a PEArrayWrapper.
 *
 * @param macCfg     ScaleAddConfig for element/scale types driving each PE.
 * @param vectorSize Number of parallel MACs per PE (>= 1).
 * @param tileRows   Number of PE rows.
 * @param tileCols   Number of PE columns.
 * @param requantCfg RequantConfig for the FP32→MXFP8 requantization block.
 *                   Its tileRows/tileCols must match the array dimensions.
 */
case class PEArrayConfig(
  macCfg:     ScaleAddConfig,
  vectorSize: Int,
  tileRows:   Int,
  tileCols:   Int,
  requantCfg: RequantConfig
) {
  require(vectorSize >= 1, "vectorSize must be >= 1")
  require(tileRows == requantCfg.tileRows,
    s"PEArrayConfig tileRows ($tileRows) must match requantCfg.tileRows (${requantCfg.tileRows})")
  require(tileCols == requantCfg.tileCols,
    s"PEArrayConfig tileCols ($tileCols) must match requantCfg.tileCols (${requantCfg.tileCols})")

  /** Total packed input width for operand A (vectorSize elements). */
  val srcWidthA  = macCfg.elementTypeA.totalWidth * vectorSize
  /** Total packed input width for operand B (vectorSize elements). */
  val srcWidthB  = macCfg.elementTypeB.totalWidth * vectorSize
  /** Shared scale factor bit width. */
  val scaleWidth = macCfg.stype.totalScaleWidth
  /** PE accumulator output width (FP32). */
  val dstWidth   = 32
  /** Single MXFP8 element bit width. */
  val fp8Width   = requantCfg.outputType.totalWidth
}

object DefaultPEArrayConfigs {
  /** 4×4 tile, vec1, E5M2×E5M2 / UE8M0, block-32, E5M2 output */
  val e5m2_4x4 = PEArrayConfig(
    macCfg     = ScaleAddConfig(MXFormats.E5M2, MXFormats.E5M2, ScaleFormats.UE8M0),
    vectorSize = 1,
    tileRows   = 4,
    tileCols   = 4,
    requantCfg = RequantConfig(
      blockSize  = 32,
      tileRows   = 4,
      tileCols   = 4,
      outputType = MXFormats.E5M2
    )
  )

  /** 4×4 tile, vec1, E4M3×E4M3 / UE8M0, block-32, E4M3 output */
  val e4m3_4x4 = PEArrayConfig(
    macCfg     = ScaleAddConfig(MXFormats.E4M3, MXFormats.E4M3, ScaleFormats.UE8M0),
    vectorSize = 1,
    tileRows   = 4,
    tileCols   = 4,
    requantCfg = RequantConfig(
      blockSize  = 32,
      tileRows   = 4,
      tileCols   = 4,
      outputType = MXFormats.E4M3
    )
  )

  /** 8×8 tile, vec1, E4M3×E4M3 / UE8M0, block-16, E4M3 output */
  val e4m3_8x8 = PEArrayConfig(
    macCfg     = ScaleAddConfig(MXFormats.E4M3, MXFormats.E4M3, ScaleFormats.UE8M0),
    vectorSize = 1,
    tileRows   = 8,
    tileCols   = 8,
    requantCfg = RequantConfig(
      blockSize  = 16,
      tileRows   = 8,
      tileCols   = 8,
      outputType = MXFormats.E4M3
    )
  )
}
