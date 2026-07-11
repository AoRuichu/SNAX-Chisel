// Reference model: decode Elem / Scale raw encodings to Double, compute FP64
// dot product, round the final result to BF16 (RNE). Used by numerical tests.
//
// Format conventions match Parameter.scala:
//   FP:   sign-magnitude, hidden bit gated on biased exp != 0
//   INT8: sign-magnitude, 7-bit unsigned magnitude, value = mant * 2^impSc
//   BF16: 1 sign + 8 exp (bias 127) + 7 mant fractional

package mx_simple

object Reference {

  /** Decode a raw element encoding (sign, biasedExp, mant) to a Double.
    * `raw` fields are already split into the operand's format. */
  def decodeElem(el: Elem, sign: Boolean, biasedExp: Int, mant: Int): Double = {
    val s = if (sign) -1.0 else 1.0
    if (el.isInt) {
      // Sign-magnitude convention: value = (-1)^sign * mant * 2^impSc
      s * mant.toDouble * math.pow(2.0, el.impSc)
    } else {
      val mantFrac = mant.toDouble / (1 << el.m).toDouble
      if (biasedExp == 0)
        s * mantFrac * math.pow(2.0, 1 - el.bias)                // subnormal
      else
        s * (1.0 + mantFrac) * math.pow(2.0, biasedExp - el.bias)
    }
  }

  /** Decode a raw scale encoding (biasedExp, mant) to a Double. */
  def decodeScale(sc: Scale, biasedExp: Int, mant: Int): Double = {
    if (sc.m == 0) {
      // Pure exponent: value = 2^(biasedExp - bias)
      math.pow(2.0, biasedExp - sc.bias)
    } else {
      val mantFrac = mant.toDouble / (1 << sc.m).toDouble
      val impl     = if (biasedExp > 0) 1.0 else 0.0
      val eff      = if (biasedExp > 0) biasedExp - sc.bias else 1 - sc.bias
      (impl + mantFrac) * math.pow(2.0, eff)
    }
  }

  /** Round a Double to BF16 (sign, biasedExp[8], mant[7]) with RNE.
    * Simplifications: no subnormal outputs, no NaN/Inf; overflow saturates. */
  def roundToBF16(x: Double): (Boolean, Int, Int) = {
    if (x == 0.0 || x.isNaN) return (false, 0, 0)
    val sign = x < 0
    val absX = math.abs(x)
    val bits = java.lang.Double.doubleToLongBits(absX)
    val doubleBiased = ((bits >>> 52) & 0x7FFL).toInt
    val doubleMant   = bits & 0x000FFFFFFFFFFFFFL

    // If Double exponent is zero, absX is subnormal in double — treat as zero
    // for our narrow BF16 target.
    if (doubleBiased == 0) return (false, 0, 0)

    val trueExp     = doubleBiased - 1023
    val bf16Biased0 = trueExp + 127

    // Double mant = 52 bits; keep top 7, get guard/round/sticky from below.
    val topMant0 = ((doubleMant >>> (52 - 7)) & 0x7FL).toInt
    val gBit     = ((doubleMant >>> (52 - 7 - 1)) & 1L).toInt
    val rBit     = ((doubleMant >>> (52 - 7 - 2)) & 1L).toInt
    val stickyMask = (1L << (52 - 7 - 2)) - 1L
    val sBit     = if ((doubleMant & stickyMask) != 0L) 1 else 0

    val roundUp = (gBit == 1) && ((rBit | sBit | (topMant0 & 1)) == 1)
    var mant    = topMant0 + (if (roundUp) 1 else 0)
    var biased  = bf16Biased0
    if ((mant & 0x80) != 0) { mant >>= 1; biased += 1 }

    if (biased >= 255) { biased = 254; mant = 0x7F }         // saturate
    if (biased <= 0)   return (sign, 0, 0)                    // underflow

    (sign, biased, mant & 0x7F)
  }

  /** Decode a BF16 encoding back to Double. */
  def decodeBF16(sign: Boolean, biasedExp: Int, mant: Int): Double = {
    val s = if (sign) -1.0 else 1.0
    if (biasedExp == 0) {
      if (mant == 0) 0.0
      else s * (mant.toDouble / 128.0) * math.pow(2.0, -126)  // subnormal
    } else {
      s * (1.0 + mant.toDouble / 128.0) * math.pow(2.0, biasedExp - 127)
    }
  }

  // ── Random stimulus generation ────────────────────────────

  /** Generate a random elem encoding in valid normal range.
    * Returns (sign, biasedExp, mant). */
  def randElem(el: Elem, rng: scala.util.Random): (Boolean, Int, Int) = {
    val sign = rng.nextBoolean()
    if (el.isInt) {
      // INT8: 7-bit magnitude, avoid all-zero for meaningful accumulation
      val mant = rng.nextInt(1 << el.m)
      (sign, 0, mant)
    } else {
      // FP: pick normal exp range [1, maxBiased] to avoid subnormals
      val maxBiased = (1 << el.e) - (if (el.resvNaN) 2 else 1)
      val biasedExp = 1 + rng.nextInt(maxBiased)
      val mant      = rng.nextInt(1 << el.m)
      (sign, biasedExp, mant)
    }
  }

  /** Generate a random scale encoding. Uses full biased range (including 0
    * for subnormals, which are common in real MX workloads when block max is
    * small vs element_max). Keeps exp near middle to avoid BF16 saturation
    * for the accumulated result. */
  def randScale(sc: Scale, rng: scala.util.Random): (Int, Int) = {
    val biasedExp = sc.bias + rng.nextInt(5) - 2     // {bias-2 .. bias+2}, can be 0
    val mant      = if (sc.m == 0) 0 else rng.nextInt(1 << sc.m)
    (biasedExp.max(0), mant)
  }
}
