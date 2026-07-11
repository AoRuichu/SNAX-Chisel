// Emit main: elaborate SimpleDPU for a specified config and print widths.
//
// Usage (single config):
//   sbt "runMain mx_simple.EmitSimpleDPU E4M3 E4M3 UE6M2 generated/E4M3_E4M3_UE6M2"
//
// Usage (all 126 configs):
//   sbt "runMain mx_simple.EmitAllSimpleDPU generated"

package mx_simple

import chisel3._
import circt.stage.ChiselStage
import java.io.{File, PrintWriter}

object EmitSimpleDPU extends App {
  val (elA, elW, sc, outDir) = args match {
    case Array(a, b, s, o) => (a, b, s, o)
    case _ =>
      println("Usage: EmitSimpleDPU <elA> <elW> <scale> <outDir>")
      sys.exit(1)
  }
  val cfg = DPUConfig(
    A = Elem.byName.getOrElse(elA, sys.error(s"Unknown Elem: $elA")),
    W = Elem.byName.getOrElse(elW, sys.error(s"Unknown Elem: $elW")),
    S = Scale.byName.getOrElse(sc, sys.error(s"Unknown Scale: $sc")),
  )
  println("Width report:")
  println("  " + Widths(cfg).show())
  println()

  ChiselStage.emitSystemVerilogFile(
    new SimpleDPU(cfg),
    Array("--target-dir", outDir),
    firtoolOpts = Array("-disable-all-randomization", "-strip-debug-info"),
  )
}

/** Emit all 126 configs (upper triangle of 6 elem types x 6 scales). */
object EmitAllSimpleDPU extends App {

  val ELEM_TYPES = Seq("E5M2", "E4M3", "E3M2", "E2M3", "E2M1", "INT8")
  val ALL_SCALES = Seq("UE8M0", "UE7M1", "UE6M2", "UE5M3", "UE4M4", "UE4M3")

  // Upper triangle: A index <= W index — 21 pairs.
  val opPairs: Seq[(String, String)] =
    for {
      (a, i) <- ELEM_TYPES.zipWithIndex
      w      <- ELEM_TYPES.drop(i)
    } yield (a, w)

  val configs: Seq[(String, String, String)] =
    for {
      (a, w) <- opPairs
      s      <- ALL_SCALES
    } yield (a, w, s)

  val base = args.headOption.getOrElse("generated")
  println(s"Emitting ${configs.size} configs = ${opPairs.size} elem pairs x " +
          s"${ALL_SCALES.size} scales -> $base/")

  // Also dump a manifest CSV: config -> widths.
  val manifestFile = new File(s"$base/widths_manifest.csv")
  manifestFile.getParentFile.mkdirs()
  val mpw = new PrintWriter(manifestFile)
  mpw.println("config,elA,elW,scale,prodMantW,pMax,pMinVal,intBits," +
              "belowAnchor,aboveAnchor,sopFieldW,scaleMantProdW,scaledTermW,finalAdderW")

  for (((a, w, s), idx) <- configs.zipWithIndex) {
    val outDir = s"$base/${a}_${w}_${s}"
    print(f"[${idx+1}%3d/${configs.size}] $a/$w/$s -> $outDir  ")
    val cfg = DPUConfig(Elem.byName(a), Elem.byName(w), Scale.byName(s))
    val ww = Widths(cfg)
    println(f"SoP=${ww.sopFieldW}%3d term=${ww.scaledTermW}%3d fa=${ww.finalAdderW}%3d")

    ChiselStage.emitSystemVerilogFile(
      new SimpleDPU(cfg),
      Array("--target-dir", outDir),
      firtoolOpts = Array("-disable-all-randomization", "-strip-debug-info"),
    )

    mpw.println(s"${a}_${w}_${s},$a,$w,$s,${ww.prodMantW},${ww.pMax},${ww.pMinVal}," +
                s"${ww.intBits},${ww.belowAnchor},${ww.aboveAnchor}," +
                s"${ww.sopFieldW},${ww.scaleMantProdW},${ww.scaledTermW},${ww.finalAdderW}")
  }
  mpw.close()
  println(s"\nAll ${configs.size} configs emitted. Manifest: $manifestFile")
}
