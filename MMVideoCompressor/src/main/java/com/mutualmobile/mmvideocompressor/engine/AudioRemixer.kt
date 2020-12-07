package com.mutualmobile.mmvideocompressor.engine

import java.nio.ShortBuffer

interface AudioRemixer {
  fun remix(
    inShortBuffer: ShortBuffer,
    outShortBuffer: ShortBuffer
  )
}

val DOWNMIX: AudioRemixer by lazy {
  object : AudioRemixer {
    private val SIGNED_SHORT_LIMIT = 32768
    private val UNSIGNED_SHORT_MAX = 65535

    override fun remix(
      inShortBuffer: ShortBuffer,
      outShortBuffer: ShortBuffer
    ) {
      // Down-mix stereo to mono
      // Viktor Toth's algorithm -
      // See: http://www.vttoth.com/CMS/index.php/technical-notes/68
      //      http://stackoverflow.com/a/25102339
      val inRemaining = inShortBuffer.remaining() / 2
      val outSpace = outShortBuffer.remaining()

      val samplesToBeProcessed = Math.min(inRemaining, outSpace)
      for (i in 0 until samplesToBeProcessed) {
        // Convert to unsigned
        val a = inShortBuffer.get() + SIGNED_SHORT_LIMIT
        val b = inShortBuffer.get() + SIGNED_SHORT_LIMIT
        var m: Int
        // Pick the equation
        if (a < SIGNED_SHORT_LIMIT || b < SIGNED_SHORT_LIMIT) {
          // Viktor's first equation when both sources are "quiet"
          // (i.e. less than middle of the dynamic range)
          m = a * b / SIGNED_SHORT_LIMIT
        } else {
          // Viktor's second equation when one or both sources are loud
          m = 2 * (a + b) - a * b / SIGNED_SHORT_LIMIT - UNSIGNED_SHORT_MAX
        }
        // Convert output back to signed short
        if (m == UNSIGNED_SHORT_MAX + 1) m = UNSIGNED_SHORT_MAX
        outShortBuffer.put((m - SIGNED_SHORT_LIMIT).toShort())
      }
    }
  }
}

val PASSTHROUGH: AudioRemixer by lazy {
  object : AudioRemixer {
    override fun remix(
      inShortBuffer: ShortBuffer,
      outShortBuffer: ShortBuffer
    ) {
      // Passthrough
      outShortBuffer.put(inShortBuffer)
    }
  }
}

val UPMIX: AudioRemixer by lazy {
  object : AudioRemixer {
    override fun remix(
      inShortBuffer: ShortBuffer,
      outShortBuffer: ShortBuffer
    ) {
      // Up-mix mono to stereo
      val inRemaining = inShortBuffer.remaining()
      val outSpace = outShortBuffer.remaining() / 2

      val samplesToBeProcessed = Math.min(inRemaining, outSpace)
      for (i in 0 until samplesToBeProcessed) {
        val inSample = inShortBuffer.get()
        outShortBuffer.put(inSample)
        outShortBuffer.put(inSample)
      }
    }
  }
}
