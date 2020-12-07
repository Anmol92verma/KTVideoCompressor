package com.mutualmobile.mmvideocompressor.muxer

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import com.mutualmobile.mmvideocompressor.muxer.SampleInfo.SampleType
import com.mutualmobile.mmvideocompressor.muxer.SampleInfo.SampleType.AUDIO
import com.mutualmobile.mmvideocompressor.muxer.SampleInfo.SampleType.VIDEO
import timber.log.Timber
import java.nio.ByteBuffer
import java.nio.ByteOrder

class QueuedMuxer(private val mediaMuxer: MediaMuxer) {

  private var mByteBuffer: ByteBuffer? = null
  private var mAudioTrackIndex: Int? = null
  private var mVideoTrackIndex: Int? = null
  private var muxerStarted: Boolean? = null
  private val mSampleInfoList by lazy { mutableListOf<SampleInfo>() }
  private var mVideoFormat: MediaFormat? = null
  private var mAudioFormat: MediaFormat? = null

  fun setOutputFormat(
    sampleType: SampleType,
    mediaFormat: MediaFormat
  ) {
    when (sampleType) {
      VIDEO -> mVideoFormat = mediaFormat
      AUDIO -> mAudioFormat = mediaFormat
    }

    when (sampleType) {
      AUDIO -> {
        mAudioTrackIndex = mediaMuxer.addTrack(mediaFormat)
        Timber.e(
          "Added track #$mAudioTrackIndex with ${mAudioFormat?.getString(
            MediaFormat.KEY_MIME
          )} to muxer"
        )
      }
      VIDEO -> {
        mVideoTrackIndex = mediaMuxer.addTrack(mediaFormat)
        Timber.e(
          "Added track #$mVideoTrackIndex with ${mVideoFormat?.getString(
            MediaFormat.KEY_MIME
          )} to muxer"
        )
      }
    }
    if (mAudioTrackIndex == null || mVideoTrackIndex == null) return
    mediaMuxer.start()
    muxerStarted = true

    if (mByteBuffer == null) {
      mByteBuffer = ByteBuffer.allocate(0)
    }
    mByteBuffer?.flip()

    val bufferInfo = MediaCodec.BufferInfo()
    var offset = 0
    mSampleInfoList.forEach {
      it.writeToBufferInfo(bufferInfo, offset = offset)
      mByteBuffer?.let { buff ->
        getTrackIndexForSampleType(it.sampleType)?.let { it1 ->
          mediaMuxer.writeSampleData(it1, buff, bufferInfo)
        }
      }
      offset += it.size
    }

    mSampleInfoList.clear()
    mByteBuffer?.clear()
  }

  private fun getTrackIndexForSampleType(sampleType: SampleType): Int? {
    return when (sampleType) {
      AUDIO -> {
        mAudioTrackIndex
      }
      VIDEO -> {
        mVideoTrackIndex
      }
    }
  }

  fun writeSampleData(
    sampleType: SampleType,
    byteBuffer: ByteBuffer,
    bufferInfo: MediaCodec.BufferInfo
  ) {
    if (muxerStarted == true) {
      getTrackIndexForSampleType(sampleType)?.let {
        mediaMuxer.writeSampleData(it, byteBuffer, bufferInfo)
      }
      return
    }

    byteBuffer.limit(bufferInfo.offset.plus(bufferInfo.size))
    byteBuffer.position(bufferInfo.offset)

    if (mByteBuffer == null) {
      mByteBuffer = ByteBuffer.allocateDirect(64 * 1024)
        .order(ByteOrder.nativeOrder())
    }

    mByteBuffer?.put(byteBuffer)
    mSampleInfoList.add(
      SampleInfo(
        sampleType, bufferInfo.size,
        bufferInfo
      )
    )
  }
}

class SampleInfo(
  val sampleType: SampleType,
  val size: Int = 0,
  private val bufferInfo: MediaCodec.BufferInfo
) {

  enum class SampleType {
    VIDEO,
    AUDIO
  }

  fun writeToBufferInfo(
    bufferInfoToWrite: MediaCodec.BufferInfo,
    offset: Int
  ) {
    bufferInfoToWrite.set(offset, size, bufferInfo.presentationTimeUs, bufferInfo.flags)
  }
}
