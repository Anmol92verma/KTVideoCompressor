package com.mutualmobile.mmvideocompressor.utils

import android.media.MediaExtractor
import android.media.MediaFormat

object MediaExtractorUtils {

  class TrackResult {
    var mVideoTrackIndex: Int = 0
    var mVideoTrackMime: String? = null
    var mVideoTrackFormat: MediaFormat? = null
    var mAudioTrackIndex: Int = 0
    var mAudioTrackMime: String? = null
    var mAudioTrackFormat: MediaFormat? = null
  }

  fun getFirstVideoAndAudioTrack(extractor: MediaExtractor): TrackResult {
    val trackResult = TrackResult()
    trackResult.mVideoTrackIndex = -1
    trackResult.mAudioTrackIndex = -1
    val trackCount = extractor.trackCount
    for (i in 0 until trackCount) {
      val format = extractor.getTrackFormat(i)
      val mime = format.getString(MediaFormat.KEY_MIME)
      if (trackResult.mVideoTrackIndex < 0 && mime!!.startsWith("video/")) {
        trackResult.mVideoTrackIndex = i
        trackResult.mVideoTrackMime = mime
        trackResult.mVideoTrackFormat = format
      } else if (trackResult.mAudioTrackIndex < 0 && mime!!.startsWith("audio/")) {
        trackResult.mAudioTrackIndex = i
        trackResult.mAudioTrackMime = mime
        trackResult.mAudioTrackFormat = format
      }
      if (trackResult.mVideoTrackIndex >= 0 && trackResult.mAudioTrackIndex >= 0) break
    }
    return trackResult
  }
}
