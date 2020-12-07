package com.mutualmobile.mmvideocompressor.transcoders

import android.media.MediaCodec
import android.media.MediaFormat

interface TrackTranscoder {
  fun setup()
  fun getDeterminedFormat(): MediaFormat?
  fun stepPipeline(mBufferInfo: MediaCodec.BufferInfo): Boolean
  fun getWrittenPresentationTimeUS(): Long
  fun isFinished(): Boolean
  fun release()
}
