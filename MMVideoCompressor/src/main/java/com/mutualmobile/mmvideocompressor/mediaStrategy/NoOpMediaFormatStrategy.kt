package com.mutualmobile.mmvideocompressor.mediaStrategy

import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaFormat.KEY_AAC_PROFILE
import android.media.MediaFormat.KEY_BIT_RATE
import android.media.MediaFormat.KEY_COLOR_FORMAT
import android.media.MediaFormat.KEY_FRAME_RATE
import android.media.MediaFormat.KEY_I_FRAME_INTERVAL
import android.media.MediaFormat.KEY_MIME
import android.media.MediaFormat.KEY_SAMPLE_RATE
import android.media.MediaFormat.MIMETYPE_AUDIO_AAC
import android.media.MediaFormat.createAudioFormat
import android.media.MediaFormat.createVideoFormat

class NoOpMediaFormatStrategy(
  private val width: String,
  private val height: String,
  private val bitrate: String
) : MediaFormatStrategy {

  private val AUDIO_BITRATE_AS_IS = -1
  private val AUDIO_CHANNELS_AS_IS = -1
  private var mAudioBitrate: Int = 128 * 1000
  private var mAudioChannels: Int = 1

  override fun createVideoOutputFormat(inputFormat: MediaFormat): MediaFormat? {
    val format = createVideoFormat(inputFormat.getString(KEY_MIME)!!, width.toInt(), height.toInt())
    if (bitrate.isNotEmpty()) {
      format.setInteger(KEY_BIT_RATE, bitrate.toInt())
    }
    try {
      format.setInteger(KEY_FRAME_RATE, inputFormat.getInteger(KEY_FRAME_RATE))
    } catch (ex: Exception) {
      format.setInteger(KEY_FRAME_RATE, 30)
    }

    try {
      format.setInteger(
        KEY_I_FRAME_INTERVAL,
        inputFormat.getInteger(KEY_I_FRAME_INTERVAL)
      )
    } catch (ex: Exception) {
      format.setInteger(KEY_I_FRAME_INTERVAL, 3)
    }

    format.setInteger(
      KEY_COLOR_FORMAT,
      MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
    )
    return format
  }

  override fun createAudioOutputFormat(inputFormat: MediaFormat): MediaFormat? {
    if (mAudioBitrate == AUDIO_BITRATE_AS_IS || mAudioChannels == AUDIO_CHANNELS_AS_IS) return null

    // Use original sample rate, as resampling is not supported yet.
    val format = createAudioFormat(
      MIMETYPE_AUDIO_AAC,
      inputFormat.getInteger(KEY_SAMPLE_RATE), mAudioChannels
    )
    format.setInteger(KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
    format.setInteger(KEY_BIT_RATE, mAudioBitrate)
    return format
  }
}
