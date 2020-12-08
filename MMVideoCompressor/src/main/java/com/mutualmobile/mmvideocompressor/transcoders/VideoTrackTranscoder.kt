package com.mutualmobile.mmvideocompressor.transcoders

import android.media.MediaCodec
import android.media.MediaCodec.CONFIGURE_FLAG_ENCODE
import android.media.MediaExtractor
import android.media.MediaFormat
import com.mutualmobile.mmvideocompressor.muxer.QueuedMuxer
import com.mutualmobile.mmvideocompressor.muxer.SampleInfo.SampleType
import com.mutualmobile.mmvideocompressor.muxer.SampleInfo.SampleType.VIDEO
import com.mutualmobile.mmvideocompressor.utils.KEY_ROTATION_DEGREES
import com.mutualmobile.mmvideocompressor.videosurface.InputSurface
import com.mutualmobile.mmvideocompressor.videosurface.OutputSurface

class VideoTrackTranscoder(
  private val mediaExtractor: MediaExtractor,
  private val mVideoTrackIndex: Int,
  private val videoOutputFormat: MediaFormat,
  private val queuedMuxer: QueuedMuxer
) : TrackTranscoder {

  private lateinit var mEncoder: MediaCodec
  private lateinit var mDecoder: MediaCodec

  private var mActualOutputFormat: MediaFormat? = null
  private var mDecoderOutputSurfaceWrapper: OutputSurface? = null
  private var mEncoderInputSurfaceWrapper: InputSurface? = null
  private var mIsExtractorEOS: Boolean = false
  private var mIsDecoderEOS: Boolean = false
  private var mIsEncoderEOS: Boolean = false
  private var mDecoderStarted: Boolean = false
  private var mEncoderStarted: Boolean = false
  private var mWrittenPresentationTimeUs: Long = 0

  override fun setup() {
    mediaExtractor.selectTrack(mVideoTrackIndex)
    val inputFormat = mediaExtractor.getTrackFormat(mVideoTrackIndex)

    mEncoder = initEncoder()

    handleRotationInputFormat(inputFormat)

    mDecoder = initDecoder(inputFormat)
  }

  private fun handleRotationInputFormat(inputFormat: MediaFormat) {
    if (inputFormat.containsKey(KEY_ROTATION_DEGREES)) {
      // Decoded video is rotated automatically in Android 5.0 lollipop.
      // Turn off here because we don't want to encode rotated one.
      // refer: https://android.googlesource.com/platform/frameworks/av/+blame/lollipop-release/media/libstagefright/Utils.cpp
      inputFormat.setInteger(KEY_ROTATION_DEGREES, 0)
    }
  }

  private fun initEncoder(): MediaCodec {
    // create a inputsurface to grab video frames
    val mEncoder = MediaCodec.createEncoderByType(videoOutputFormat.getString(MediaFormat.KEY_MIME)!!)
    mEncoder.configure(videoOutputFormat, null, null, CONFIGURE_FLAG_ENCODE)
    mEncoderInputSurfaceWrapper = InputSurface(mEncoder.createInputSurface())
    mEncoderInputSurfaceWrapper?.makeCurrent()

    mEncoder.start()
    mEncoderStarted = true
    return mEncoder
  }

  private fun initDecoder(inputFormat: MediaFormat): MediaCodec {
    val mDecoder = MediaCodec.createDecoderByType(inputFormat.getString(MediaFormat.KEY_MIME)!!)
    mDecoderOutputSurfaceWrapper = OutputSurface()
    mDecoder.configure(inputFormat, mDecoderOutputSurfaceWrapper?.surface, null, 0)
    mDecoder.start()
    mDecoderStarted = true
    return mDecoder
  }

  override fun getDeterminedFormat() = mActualOutputFormat

  override fun stepPipeline(mBufferInfo: MediaCodec.BufferInfo): Boolean {
    var busy = false

    var status: Int
    while (drainEncoder(0, mBufferInfo) != DRAIN_STATE_NONE) busy = true
    do {
      status = drainDecoder(0, mBufferInfo)
      if (status != DRAIN_STATE_NONE) busy = true
      // NOTE: not repeating to keep from deadlock when encoder is full.
    } while (status == DRAIN_STATE_SHOULD_RETRY_IMMEDIATELY)
    while (drainExtractor(0) != DRAIN_STATE_NONE) busy = true

    return busy
  }

  override fun getWrittenPresentationTimeUS() = mWrittenPresentationTimeUs

  override fun isFinished() = mIsEncoderEOS

  override fun release() {
    mDecoderOutputSurfaceWrapper?.let {
      it.release()
    }
    mEncoderInputSurfaceWrapper?.let {
      it.release()
    }

    if (mDecoderStarted) {
      mDecoder.stop()
    }
    mDecoder.release()

    if (mEncoderStarted) {
      mEncoder.stop()
    }
    mEncoder.release()
  }

  private fun drainExtractor(timeoutUs: Long): Int {
    if (mIsExtractorEOS) return DRAIN_STATE_NONE
    val trackIndex = mediaExtractor.sampleTrackIndex
    trackIndex.let {
      if (trackIndex >= 0 && trackIndex != mVideoTrackIndex) {
        return DRAIN_STATE_NONE
      }

      val index = mDecoder.dequeueInputBuffer(timeoutUs)
      if (index < 0) return DRAIN_STATE_NONE
      if (trackIndex < 0) {
        mIsExtractorEOS = true
        mDecoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        return DRAIN_STATE_NONE
      }
      mDecoder.getInputBuffer(index)
        ?.let { byteBufferAtIndex ->
          val sampleSize = mediaExtractor.readSampleData(byteBufferAtIndex, 0)
          val presentationTime: Long = mediaExtractor.sampleTime
          if (sampleSize >= 0) {
            mDecoder.queueInputBuffer(
              index, 0, sampleSize, presentationTime,
              mediaExtractor.sampleFlags
            )
          } else {
            mDecoder.queueInputBuffer(
              index, 0, sampleSize, 0L,
              MediaCodec.BUFFER_FLAG_END_OF_STREAM
            )
          }

          mediaExtractor.advance()
        }
    }
    return DRAIN_STATE_CONSUMED
  }

  private fun drainDecoder(
    timeoutUs: Long,
    mBufferInfo: MediaCodec.BufferInfo
  ): Int {
    if (mIsDecoderEOS) return DRAIN_STATE_NONE
    val result = mDecoder.dequeueOutputBuffer(mBufferInfo, timeoutUs)
    when (result) {
      MediaCodec.INFO_TRY_AGAIN_LATER -> return DRAIN_STATE_NONE
      MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> return DRAIN_STATE_SHOULD_RETRY_IMMEDIATELY
    }
    if (mBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
      mEncoder.signalEndOfInputStream()
      mIsDecoderEOS = true
      mBufferInfo.size = 0
    }
    val doRender = mBufferInfo.size > 0
    // NOTE: doRender will block if buffer (of encoder) is full.
    // Refer: http://bigflake.com/mediacodec/CameraToMpegTest.java.txt
    mDecoder.releaseOutputBuffer(result, doRender)
    if (doRender) {
      mDecoderOutputSurfaceWrapper?.awaitNewImage()
      mDecoderOutputSurfaceWrapper?.drawImage()
      mEncoderInputSurfaceWrapper?.setPresentationTime(mBufferInfo.presentationTimeUs * 1000)
      mEncoderInputSurfaceWrapper?.swapBuffers()
    }
    return DRAIN_STATE_CONSUMED
  }

  private fun drainEncoder(
    timeoutUs: Long,
    mBufferInfo: MediaCodec.BufferInfo
  ): Int {
    if (mIsEncoderEOS) return DRAIN_STATE_NONE
    val result = mEncoder.dequeueOutputBuffer(mBufferInfo, timeoutUs)
    when (result) {
      MediaCodec.INFO_TRY_AGAIN_LATER -> return DRAIN_STATE_NONE
      MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
        if (mActualOutputFormat != null)
          throw RuntimeException("Video output format changed twice.")
        mActualOutputFormat = mEncoder.outputFormat
        mActualOutputFormat?.let { queuedMuxer.setOutputFormat(VIDEO, it) }
        return DRAIN_STATE_SHOULD_RETRY_IMMEDIATELY
      }
      MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> {
        TODO()
      }
    }
    if (mActualOutputFormat == null) {
      throw RuntimeException("Could not determine actual output format.")
    }

    if (mBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
      mIsEncoderEOS = true
      mBufferInfo.set(0, 0, 0, mBufferInfo.flags)
    }
    if (mBufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
      // SPS or PPS, which should be passed by MediaFormat.
      result.let { mEncoder.releaseOutputBuffer(it, false) }
      return DRAIN_STATE_SHOULD_RETRY_IMMEDIATELY
    }

    mEncoder.getOutputBuffer(result)
      ?.let {
        queuedMuxer.writeSampleData(SampleType.VIDEO, it, mBufferInfo)
      }

    mWrittenPresentationTimeUs = mBufferInfo.presentationTimeUs
    mEncoder.releaseOutputBuffer(result, false)
    return DRAIN_STATE_CONSUMED
  }

  companion object {
    const val DRAIN_STATE_NONE = 0
    const val DRAIN_STATE_SHOULD_RETRY_IMMEDIATELY = 1
    const val DRAIN_STATE_CONSUMED = 2
  }
}
