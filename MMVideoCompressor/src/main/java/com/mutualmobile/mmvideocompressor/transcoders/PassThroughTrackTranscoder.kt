package com.mutualmobile.mmvideocompressor.transcoders

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import com.mutualmobile.mmvideocompressor.muxer.QueuedMuxer
import com.mutualmobile.mmvideocompressor.muxer.SampleInfo
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PassThroughTrackTranscoder(
  private val mediaExtractor: MediaExtractor,
  private val mVideoTrackIndex: Int,
  private val queuedMuxer: QueuedMuxer,
  private val sampleType: SampleInfo.SampleType
) : TrackTranscoder {

  private var mWrittenPresentationTimeUs: Long = 0
  private var bufferSize: Int
  private var format: MediaFormat = mediaExtractor.getTrackFormat(mVideoTrackIndex)
  private var mBuffer: ByteBuffer
  private var mIsEOS: Boolean = false

  init {
    queuedMuxer.setOutputFormat(sampleType, format)
    bufferSize = format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
    mBuffer = ByteBuffer.allocateDirect(bufferSize)
      .order(ByteOrder.nativeOrder())
  }

  override fun setup() {
  }

  override fun getDeterminedFormat(): MediaFormat {
    return format
  }

  override fun stepPipeline(mBufferInfo: MediaCodec.BufferInfo): Boolean {
    return when {
      mIsEOS -> false
      else -> {
        val trackIndex = mediaExtractor.sampleTrackIndex
        when {
          trackIndex < 0 -> {
            mBuffer.clear()
            mBufferInfo.set(0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            queuedMuxer.writeSampleData(sampleType, mBuffer, mBufferInfo)
            mIsEOS = true
            true
          }
          trackIndex != mVideoTrackIndex -> false
          else -> {
            mBuffer.clear()
            val sampleSize = mediaExtractor.readSampleData(mBuffer, 0)
            assert(sampleSize <= bufferSize)
            val isKeyFrame = mediaExtractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0
            val flags = if (isKeyFrame) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
            mBufferInfo.set(0, sampleSize, mediaExtractor.sampleTime, flags)
            queuedMuxer.writeSampleData(sampleType, mBuffer, mBufferInfo)
            mWrittenPresentationTimeUs = mBufferInfo.presentationTimeUs
            mediaExtractor.advance()
            true
          }
        }
      }
    }
  }

  override fun getWrittenPresentationTimeUS(): Long {
    return mWrittenPresentationTimeUs
  }

  override fun isFinished(): Boolean {
    return mIsEOS
  }

  override fun release() {
  }
}
