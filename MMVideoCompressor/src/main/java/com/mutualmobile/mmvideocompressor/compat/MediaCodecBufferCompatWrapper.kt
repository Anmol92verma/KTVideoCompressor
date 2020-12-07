package com.mutualmobile.mmvideocompressor.compat

import android.media.MediaCodec
import java.nio.ByteBuffer

class MediaCodecBufferCompatWrapper(private val mediaCodec: MediaCodec) {

  fun getInputBuffer(index: Int): ByteBuffer? {
    return mediaCodec.getInputBuffer(index)
  }

  fun getOutputBuffer(index: Int): ByteBuffer? {
    return mediaCodec.getOutputBuffer(index)
  }
}
