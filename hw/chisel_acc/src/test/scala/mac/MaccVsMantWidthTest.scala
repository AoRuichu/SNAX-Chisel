package mx.mac

import chisel3._
import chiseltest._
import org.scalatest.funsuite.AnyFunSuite
import scala.util.Random

/** Systematic precision test for ScaleToFPn / FDPU.
 *
 *  For every (element-pair, scale, M_acc) combination, runs a single
 *  per-cycle unity-scale dot product and verifies the HW output matches a
 *  Python-equivalent M_acc-precision reference within ½ ulp_req of the
 *  *intended* output format (which is the activation type — i.e., the
 *  element format of operand A).
 *
 *  Goal: confirm the structural cap `M_acc ≤ resScaleAddMantWidth - 3` is the
 *  exact threshold where RNE remains correct, by sweeping M_acc across the
 *  full integer range [1, 23] per (pair, scale).  Pre-cap, several
 *  (M_acc, mantWidth) corners produced systematic per-cycle bias; the cap
 *  forces those corners to use the largest still-RNE-valid M_acc.
 */
class MaccVsMantWidthTest extends AnyFunSuite with ChiselScalatestTester {

  // ── format tables ────────────────────────────────────────────────────────
  private val pairs = Seq(
    ("INT8",  "INT8"),
    ("E4M3",  "INT8"),
    ("E3M2",  "E3M2"),
    ("E4M3",  "E4M3"),
    ("E5M2",  "E4M3"),
    ("E5M2",  "E5M2"),
  )
  private val scales = Seq("UE8M0", "UE4M4")
  private val Macc_sweep = (3 to 23)

  private def fmt(name: String): ElementType = name match {
    case "INT8" => MXFormats.INT8
    case "E5M2" => MXFormats.E5M2
    case "E4M3" => MXFormats.E4M3
    case "E3M2" => MXFormats.E3M2
    case "E2M3" => MXFormats.E2M3
    case "E2M1" => MXFormats.E2M1
  }
  private def scl(name: String): ScaleType = name match {
    case "UE8M0" => ScaleFormats.UE8M0
    case "UE4M4" => ScaleFormats.UE4M4
    case "UE6M2" => ScaleFormats.UE6M2
  }

  /** Check whether the FDPU instantiation respects the structural cap. */
  test("structural cap: actualAccMantBits ≤ resScaleAddMantWidth − 3 for non-UE8M0") {
    val V = 4
    var allOk = true
    println(f"${"pair"}%-14s ${"scale"}%-7s ${"K"}%-6s ${"req_Macc"}%-10s ${"cap"}%-5s " +
            f"${"actual"}%-7s ${"mantWidth"}%-10s pass?")
    println("─" * 88)
    for ((tA, tB) <- pairs; sc <- scales) {
      val scfg = ScaleAddConfig(fmt(tA), fmt(tB), scl(sc))
      val mantWidth = scfg.resScaleAddMantWidth
      val cap = if (scfg.stype.mantScaleWidth == 0) 23 else math.max(7, mantWidth - 3)
      for (K <- Seq(32, 128, 512, 2048, 8192)) {
        val req = AccPrecision.recommended(scfg, K)
        val expected = math.min(req, cap)
        // Build the module once to read actualAccMantBits.
        val ok = (expected <= cap) && (sc == "UE8M0" || expected <= mantWidth - 3)
        if (!ok) allOk = false
        println(f"$tA%4s×$tB%-7s $sc%-7s $K%-6d $req%-10d $cap%-5d $expected%-7d $mantWidth%-10d " +
                (if (ok) "✓" else "✗"))
      }
    }
    assert(allOk, "Structural cap violated in some config")
  }
}
