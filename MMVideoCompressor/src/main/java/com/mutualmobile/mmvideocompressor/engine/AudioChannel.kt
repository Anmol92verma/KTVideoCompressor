package com.mutualmobile.mmvideocompressor.engine

import android.media.MediaCodec
import android.media.MediaFormat
import com.mutualmobile.mmvideocompressor.compat.MediaCodecBufferCompatWrapper
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.ShortBuffer
import java.util.ArrayDeque

/**
 * Channel of raw audio from decoder to encoder.
 * Performs the necessary conversion between different input & output audio formats.
 *
 * We currently support upmixing from mono to stereo & downmixing from stereo to mono.
 * Sample rate conversion is not supported yet.
 */
internal class AudioChannel(
  private val mDecoder: MediaCodec,
  private val mEncoder: MediaCodec,
  private val mEncodeFormat: MediaFormat
) {

  private val mEmptyBuffers = ArrayDeque<AudioBuffer>()
  private val mFilledBuffers = ArrayDeque<AudioBuffer>()

  private var mInputSampleRate: Int = 0
  private var mInputChannelCount: Int = 0
  private var mOutputChannelCount: Int = 0

  private var mRemixer: AudioRemixer? = null

  private val mDecoderBuffers: MediaCodecBufferCompatWrapper =
    MediaCodecBufferCompatWrapper(mDecoder)
  private val mEncoderBuffers: MediaCodecBufferCompatWrapper =
    MediaCodecBufferCompatWrapper(mEncoder)

  private val mOverflowBuffer = AudioBuffer()

  private var mActualDecodedFormat: MediaFormat? = null

  private class AudioBuffer {
    internal var bufferIndex: Int = 0
    internal var presentationTimeUs: Long = 0
    internal var data: ShortBuffer? = null
  }

  fun setActualDecodedFormat(decodedFormat: MediaFormat) {
    mActualDecodedFormat = decodedFormat

    mInputSampleRate = mActualDecodedFormat!!.getInteger(MediaFormat.KEY_SAMPLE_RATE)
    if (mInputSampleRate != mEncodeFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)) {
      throw UnsupportedOperationException("Audio sample rate conversion not supported yet.")
    }

    mInputChannelCount = mActualDecodedFormat!!.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
    mOutputChannelCount = mEncodeFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

    if (mInputChannelCount != 1 && mInputChannelCount != 2) {
      throw UnsupportedOperationException(
        "Input channel count ($mInputChannelCount) not supported."
      )
    }

    if (mOutputChannelCount != 1 && mOutputChannelCount != 2) {
      throw UnsupportedOperationException(
        "Output channel count ($mOutputChannelCount) not supported."
      )
    }

    if (mInputChannelCount > mOutputChannelCount) {
      mRemixer = DOWNMIX
    } else if (mInputChannelCount < mOutputChannelCount) {
      mRemixer = UPMIX
    } else {
      mRemixer = PASSTHROUGH
    }

    mOverflowBuffer.presentationTimeUs = 0
  }

  fun drainDecoderBufferAndQueue(
    bufferIndex: Int,
    presentationTimeUs: Long
  ) {
    if (mActualDecodedFormat == null) {
      throw RuntimeException("Buffer received before format!")
    }

    val data = if (bufferIndex == BUFFER_INDEX_END_OF_STREAM)
      null
    else
      mDecoderBuffers.getOutputBuffer(bufferIndex)

    var buffer: AudioBuffer? = mEmptyBuffers.poll()
    if (buffer == null) {
      buffer = AudioBuffer()
    }

    buffer.bufferIndex = bufferIndex
    buffer.presentationTimeUs = presentationTimeUs
    buffer.data = data?.asShortBuffer()

    if (mOverflowBuffer.data == null) {
      mOverflowBuffer.data = ByteBuffer
        .allocateDirect(data!!.capacity())
        .order(ByteOrder.nativeOrder())
        .asShortBuffer()
      mOverflowBuffer.data!!.clear()
        .flip()
    }

    mFilledBuffers.add(buffer)
  }

  fun feedEncoder(timeoutUs: Long): Boolean {
    val hasOverflow = mOverflowBuffer.data != null && mOverflowBuffer.data!!.hasRemaining()
    if (mFilledBuffers.isEmpty() && !hasOverflow) {
      // No audio data - Bail out
      return false
    }

    val encoderInBuffIndex = mEncoder.dequeueInputBuffer(timeoutUs)
    if (encoderInBuffIndex < 0) {
      // Encoder is full - Bail out
      return false
    }

    // Drain overflow first
    val outBuffer = mEncoderBuffers.getInputBuffer(encoderInBuffIndex)
      ?.asShortBuffer()
    outBuffer?.let {
      if (hasOverflow) {
        val presentationTimeUs = drainOverflow(outBuffer)
        mEncoder.queueInputBuffer(
          encoderInBuffIndex,
          0, outBuffer.position() * BYTES_PER_SHORT,
          presentationTimeUs, 0
        )
        return true
      }
    }

    val inBuffer = mFilledBuffers.poll()
    if (inBuffer.bufferIndex == BUFFER_INDEX_END_OF_STREAM) {
      mEncoder.queueInputBuffer(encoderInBuffIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
      return false
    }

    outBuffer?.let {
      val presentationTimeUs = remixAndMaybeFillOverflow(inBuffer, outBuffer)
      mEncoder.queueInputBuffer(
        encoderInBuffIndex,
        0, outBuffer.position() * BYTES_PER_SHORT,
        presentationTimeUs, 0
      )
      mDecoder.releaseOutputBuffer(inBuffer.bufferIndex, false)
      mEmptyBuffers.add(inBuffer)
    }

    return true
  }

  private fun drainOverflow(outBuff: ShortBuffer): Long {
    val overflowBuff = mOverflowBuffer.data
    val overflowLimit = overflowBuff!!.limit()
    val overflowSize = overflowBuff.remaining()

    val beginPresentationTimeUs = mOverflowBuffer.presentationTimeUs + sampleCountToDurationUs(
      overflowBuff.position(), mInputSampleRate, mOutputChannelCount
    )

    outBuff.clear()
    // Limit overflowBuff to outBuff's capacity
    overflowBuff.limit(outBuff.capacity())
    // Load overflowBuff onto outBuff
    outBuff.put(overflowBuff)

    if (overflowSize >= outBuff.capacity()) {
      // Overflow fully consumed - Reset
      overflowBuff.clear()
        .limit(0)
    } else {
      // Only partially consumed - Keep position & restore previous limit
      overflowBuff.limit(overflowLimit)
    }

    return beginPresentationTimeUs
  }

  private fun remixAndMaybeFillOverflow(
    input: AudioBuffer,
    outBuff: ShortBuffer
  ): Long {
    val inBuff = input.data
    val overflowBuff = mOverflowBuffer.data

    outBuff.clear()

    // Reset position to 0, and set limit to capacity (Since MediaCodec doesn't do that for us)
    inBuff!!.clear()

    if (inBuff.remaining() > outBuff.remaining()) {
      // Overflow
      // Limit inBuff to outBuff's capacity
      inBuff.limit(outBuff.capacity())
      mRemixer!!.remix(inBuff, outBuff)

      // Reset limit to its own capacity & Keep position
      inBuff.limit(inBuff.capacity())

      // Remix the rest onto overflowBuffer
      // NOTE: We should only reach this point when overflow buffer is empty
      val consumedDurationUs = sampleCountToDurationUs(
        inBuff.position(), mInputSampleRate,
        mInputChannelCount
      )
      overflowBuff?.let { mRemixer!!.remix(inBuff, it) }

      // Seal off overflowBuff & mark limit
      overflowBuff!!.flip()
      mOverflowBuffer.presentationTimeUs = input.presentationTimeUs + consumedDurationUs
    } else {
      // No overflow
      mRemixer!!.remix(inBuff, outBuff)
    }

    return input.presentationTimeUs
  }

  companion object {

    val BUFFER_INDEX_END_OF_STREAM = -1

    private val BYTES_PER_SHORT = 2
    private val MICROSECS_PER_SEC: Long = 1000000

    private fun sampleCountToDurationUs(
      sampleCount: Int,
      sampleRate: Int,
      channelCount: Int
    ): Long {
      return sampleCount / (sampleRate * MICROSECS_PER_SEC) / channelCount
    }
  }
}
