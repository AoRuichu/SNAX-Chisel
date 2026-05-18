// Quick emit check for FDPUPipelined under the new (pipeAfterTree, pipeInScale) interface.
package mx.mac
import chisel3._
object PipedEmitCheck extends App {
  val cfgKul     = ScaleAddConfig(MXFormats.E5M2, MXFormats.E5M2, ScaleFormats.UE8M0)
  val cfgArchIII = ScaleAddConfig(MXFormats.E4M3, MXFormats.E4M3, ScaleFormats.UE8M0)
  for (pAT <- Seq(false, true); pIS <- Seq(false, true)) {
    val tag = s"pAT${pAT.toString.head}_pIS${pIS.toString.head}"
    emitVerilog(
      new FDPUPostScaleReductionTreePiped(
        cfgKul, vectorSize = 4, K = 16384,
        treeArch = TreeArch.KulischInner, cyclesPerBlock = 8,
        pipeAfterTree = pAT, pipeInScale = pIS),
      Array("--target-dir", s"generated/piped_check/kulisch_$tag")
    )
    emitVerilog(
      new FDPUPostScaleReductionTreePiped(
        cfgArchIII, vectorSize = 4, K = 16384,
        treeArch = TreeArch.TwoStageBarrel, cyclesPerBlock = 1,
        pipeAfterTree = pAT, pipeInScale = pIS),
      Array("--target-dir", s"generated/piped_check/archIII_$tag")
    )
    println(s"  $tag: kulisch + archIII emitted")
  }
}
