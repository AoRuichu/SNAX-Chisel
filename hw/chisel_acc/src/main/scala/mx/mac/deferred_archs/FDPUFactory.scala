package mx.mac.deferred_archs

import chisel3._
import mx.mac._

/** Factory for the 3-way FDPU dispatch (baseline / blockdef / kulisch).
 *
 *  Production code on the deployed PE path instantiates
 *  [[mx.mac.FDPUPostScaleReductionTree]] directly (cycleFP baseline,
 *  cpb implicit = 1).  DSE drivers that sweep across cpb values + the
 *  dropped architectures use this factory to pick the right variant
 *  without each caller having to encode the dispatch.
 *
 *  Mapping:
 *    cyclesPerBlock == 1                       → baseline cycleFP
 *    cyclesPerBlock >= 2 + KulischInner tree   → kulisch deferred
 *    cyclesPerBlock >= 2 + other tree archs    → block deferred
 *
 *  All three variants expose an identical IO bundle so callers can swap
 *  freely; only the internal pipeline differs.
 */
object FDPUFactory {
  def apply(
    scfg:           ScaleAddConfig,
    vectorSize:     Int,
    K:              Int      = 32,
    accMantBits:    Int      = -1,
    treeArch:       TreeArch = TreeArch.Generic,
    cyclesPerBlock: Int      = 1,
    istest:         Boolean  = false,
  ): Module = {
    require(cyclesPerBlock >= 1, s"cyclesPerBlock must be >= 1, got $cyclesPerBlock")
    if (cyclesPerBlock == 1) {
      require(treeArch != TreeArch.KulischInner,
        "KulischInner requires cyclesPerBlock >= 2; use cpb >= 2 or pick a different tree arch.")
      new FDPUPostScaleReductionTree(scfg, vectorSize, K, accMantBits, treeArch, istest)
    } else if (treeArch == TreeArch.KulischInner) {
      new FDPUKulischDeferred(scfg, vectorSize, K, accMantBits, cyclesPerBlock, istest)
    } else {
      new FDPUBlockDeferred(scfg, vectorSize, K, accMantBits, treeArch, cyclesPerBlock, istest)
    }
  }
}
