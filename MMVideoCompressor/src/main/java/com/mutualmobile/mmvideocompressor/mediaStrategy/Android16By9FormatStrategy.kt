package com.mutualmobile.mmvideocompressor.mediaStrategy

import android.media.MediaCodecInfo.CodecCapabilities
import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaFormat
import com.mutualmobile.mmvideocompressor.utils.MIMETYPE_AUDIO_AAC
import timber.log.Timber

class Android16By9FormatStrategy(
  private val mScale: Int,
  private val mVideoBitrate: Int,
  private val mAudioBitrate: Int = AUDIO_BITRATE_AS_IS,
  private val mAudioChannels: Int = AUDIO_CHANNELS_AS_IS
) : MediaFormatStrategy {
  override fun createVideoOutputFormat(inputFormat: MediaFormat): MediaFormat? {
    val width = inputFormat.getInteger(MediaFormat.KEY_WIDTH)
    val height = inputFormat.getInteger(MediaFormat.KEY_HEIGHT)
    val targetLonger = mScale * 16 * 16
    val targetShorter = mScale * 16 * 9
    val longer: Int
    val shorter: Int
    val outWidth: Int
    val outHeight: Int
    if (width >= height) {
      longer = width
      shorter = height
      outWidth = targetLonger
      outHeight = targetShorter
    } else {
      shorter = width
      longer = height
      outWidth = targetShorter
      outHeight = targetLonger
    }
    if (longer * 9 != shorter * 16) {
      throw Exception(
        "This video is not 16:9, and is not able to transcode. (" + width + "x" + height + ")"
      )
    }
    if (shorter <= targetShorter) {
      Timber.e("This video is less or equal to, pass-through. (  $width  x $height  )")
      return null
    }
    val format = MediaFormat.createVideoFormat("video/avc", outWidth, outHeight)
    // From Nexus 4 Camera in 720p
    format.setInteger(MediaFormat.KEY_BIT_RATE, mVideoBitrate)
    format.setInteger(MediaFormat.KEY_FRAME_RATE, 30)
    format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 3)
    format.setInteger(MediaFormat.KEY_COLOR_FORMAT, CodecCapabilities.COLOR_FormatSurface)
    return format
  }

  override fun createAudioOutputFormat(inputFormat: MediaFormat): MediaFormat? {
    if (mAudioBitrate == AUDIO_BITRATE_AS_IS || mAudioChannels == AUDIO_CHANNELS_AS_IS) return null

    // Use original sample rate, as resampling is not supported yet.
    val format = MediaFormat.createAudioFormat(
      MIMETYPE_AUDIO_AAC,
      inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE), mAudioChannels
    )
    format.setInteger(MediaFormat.KEY_AAC_PROFILE, CodecProfileLevel.AACObjectLC)
    format.setInteger(MediaFormat.KEY_BIT_RATE, mAudioBitrate)
    return format
  }

  companion object {
    const val AUDIO_BITRATE_AS_IS = -1
    const val AUDIO_CHANNELS_AS_IS = -1
    const val SCALE_720P = 5
    private const val TAG = "Android16By9FormatStrategy"
  }
}