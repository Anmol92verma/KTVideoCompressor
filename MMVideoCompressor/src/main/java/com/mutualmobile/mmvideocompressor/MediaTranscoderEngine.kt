package com.mutualmobile.mmvideocompressor

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.os.Build
import com.mutualmobile.mmvideocompressor.mediaStrategy.MediaFormatStrategy
import com.mutualmobile.mmvideocompressor.muxer.QueuedMuxer
import com.mutualmobile.mmvideocompressor.muxer.SampleInfo.SampleType.AUDIO
import com.mutualmobile.mmvideocompressor.muxer.SampleInfo.SampleType.VIDEO
import com.mutualmobile.mmvideocompressor.transcoders.AudioTrackTranscoder
import com.mutualmobile.mmvideocompressor.transcoders.PassThroughTrackTranscoder
import com.mutualmobile.mmvideocompressor.transcoders.TrackTranscoder
import com.mutualmobile.mmvideocompressor.transcoders.VideoTrackTranscoder
import com.mutualmobile.mmvideocompressor.utils.ISO6709LocationParser
import com.mutualmobile.mmvideocompressor.utils.MediaExtractorUtils
import com.mutualmobile.mmvideocompressor.utils.MediaExtractorUtils.TrackResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.FileDescriptor
import kotlin.coroutines.CoroutineContext
import kotlin.math.min

class MediaTranscoderEngine {

  companion object {
    private const val PROGRESS_UNKNOWN = -1.0
    private const val PROGRESS_INTERVAL_STEPS: Long = 10
  }

  suspend fun extractInfo(
    mediaFileDescriptor: FileDescriptor,
    coroutineContext: CoroutineContext
  ): Pair<MediaFormat?, MediaFormat?> {
    return withContext(coroutineContext) {
      val mediaExtractor = MediaExtractor()
      mediaExtractor.setDataSource(mediaFileDescriptor)

      val trackResult = MediaExtractorUtils.getFirstVideoAndAudioTrack(mediaExtractor)
      val videoOutputFormat = trackResult.mVideoTrackFormat
      val audioOutputFormat = trackResult.mAudioTrackFormat
      Pair(videoOutputFormat, audioOutputFormat)
    }
  }

  suspend fun transcodeVideo(
    outFormatStrategy: MediaFormatStrategy,
    coroutineContext: CoroutineContext,
    progressChannel: MutableStateFlow<Double>?,
    outPath: String,
    mediaFileDescriptor: FileDescriptor,
  ): Boolean {
    return withContext(coroutineContext) {
      try {
        val mediaExtractor = MediaExtractor()
        mediaExtractor.setDataSource(mediaFileDescriptor)

        val mediaMuxer = MediaMuxer(outPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val duration = extractVideoDuration(mediaFileDescriptor, mediaMuxer)

        val transcoders = setupTrackTranscoders(outFormatStrategy, mediaExtractor, mediaMuxer)

        runPipelines(
          duration, transcoders.first, transcoders.second, coroutineContext,
          progressChannel
        )

        mediaMuxer.stop()

        transcoders.first?.release()
        transcoders.second?.release()

        mediaExtractor.release()
        mediaMuxer.release()
      } catch (ex: Exception) {
        ex.printStackTrace()
      }
      true
    }
  }

  private suspend fun runPipelines(
    duration: Long,
    videoTrackTranscoder: TrackTranscoder?,
    audioTrackTranscoder: TrackTranscoder?,
    coroutineContext: CoroutineContext,
    progressChannel: MutableStateFlow<Double>?
  ) {
    return withContext(coroutineContext) {
      var loopCount = 0
      if (duration <= 0) {
        Timber.d("Trans progress $PROGRESS_UNKNOWN")
        progressChannel?.emit(PROGRESS_UNKNOWN)
      }

      val mBufferInfoVideo = MediaCodec.BufferInfo()
      val mBufferInfoAudio = MediaCodec.BufferInfo()

      while (isTranscoderRunning(videoTrackTranscoder, audioTrackTranscoder, coroutineContext)) {

        videoTrackTranscoder?.stepPipeline(mBufferInfoVideo)
        audioTrackTranscoder?.stepPipeline(mBufferInfoAudio)

        loopCount++

        printTranscodeProgress(
          duration, loopCount, videoTrackTranscoder, audioTrackTranscoder, progressChannel
        )
      }
      printTranscodeProgress(
        duration, loopCount, videoTrackTranscoder, audioTrackTranscoder, progressChannel
      )
    }
  }

  private suspend fun printTranscodeProgress(
    duration: Long,
    loopCount: Int,
    videoTrackTranscoder: TrackTranscoder?,
    audioTrackTranscoder: TrackTranscoder?,
    progressChannel: MutableStateFlow<Double>?
  ) {
    if (isStillProcessing(duration, loopCount)) {
      val videoProgress =
        videoTrackTranscoder?.let { currentTranscodedProgress(it, duration) } ?: 1.0
      val audioProgress =
        audioTrackTranscoder?.let { currentTranscodedProgress(it, duration) } ?: 1.0
      val progress = (videoProgress + audioProgress) / 2.0
      Timber.d("video progress $videoProgress")
      Timber.d("audio progress $audioProgress")
      Timber.d("Trans progress $progress")
      progressChannel?.emit(progress)
    }
  }

  private fun isTranscoderRunning(
    videoTrackTranscoder: TrackTranscoder?,
    audioTrackTranscoder: TrackTranscoder?,
    coroutineContext: CoroutineContext
  ) =
    videoTrackTranscoder?.isFinished() == false && audioTrackTranscoder?.isFinished() == false && coroutineContext[Job]?.isCancelled?.not() == true

  private fun currentTranscodedProgress(
    trackTranscoder: TrackTranscoder,
    duration: Long
  ) = when {
    trackTranscoder.isFinished() -> 1.0
    else -> min(
      1.0,
      trackTranscoder.getWrittenPresentationTimeUS()
        .toDouble() / duration
    )
  }

  private fun isStillProcessing(
    duration: Long,
    loopCount: Int
  ) =
    duration > 0 && (loopCount % PROGRESS_INTERVAL_STEPS == 0L)

  private fun setupTrackTranscoders(
    formatStrategy: MediaFormatStrategy,
    mediaExtractor: MediaExtractor,
    mediaMuxer: MediaMuxer
  ): Pair<TrackTranscoder?, TrackTranscoder?> {
    val trackResult = MediaExtractorUtils.getFirstVideoAndAudioTrack(mediaExtractor)

    val videoOutputFormat = trackResult.mVideoTrackFormat?.let {
      formatStrategy.createVideoOutputFormat(it)
    }

    val audioOutputFormat = trackResult.mAudioTrackFormat?.let {
      formatStrategy.createAudioOutputFormat(it)
    }

    val queuedMuxer = QueuedMuxer(mediaMuxer)

    var videoTrackTranscoder: TrackTranscoder? = null
    var audioTrackTranscoder: TrackTranscoder? = null
    if (hasVideoTrack(trackResult)) {
      videoTrackTranscoder =
        getVideoTranscoder(videoOutputFormat, mediaExtractor, trackResult, queuedMuxer)
      setupAndSelectTrack(videoTrackTranscoder, mediaExtractor, trackResult)
    }

    if (hasAudioTrack(trackResult)) {
      audioTrackTranscoder =
        getAudioTranscoder(audioOutputFormat, mediaExtractor, trackResult, queuedMuxer)
      setupAndSelectTrack(audioTrackTranscoder, mediaExtractor, trackResult)
    }
    return Pair(videoTrackTranscoder, audioTrackTranscoder)
  }

  private fun setupAndSelectTrack(
    trackTranscoder: TrackTranscoder,
    mediaExtractor: MediaExtractor,
    trackResult: TrackResult
  ) {
    trackTranscoder.setup()
    mediaExtractor.selectTrack(
      getTrackIndex(trackTranscoder, trackResult)
    )
  }

  private fun getTrackIndex(
    videoTrackTranscoder: TrackTranscoder,
    trackResult: TrackResult
  ) =
    if (videoTrackTranscoder is VideoTrackTranscoder) trackResult.mVideoTrackIndex else trackResult.mAudioTrackIndex

  private fun hasAudioTrack(trackResult: TrackResult) =
    trackResult.mAudioTrackIndex != -1

  private fun hasVideoTrack(trackResult: TrackResult) =
    trackResult.mVideoTrackIndex != -1

  private fun getAudioTranscoder(
    audioOutputFormat: MediaFormat?,
    mediaExtractor: MediaExtractor,
    trackResult: TrackResult,
    queuedMuxer: QueuedMuxer
  ): TrackTranscoder {
    return audioOutputFormat?.let {
      AudioTrackTranscoder(
        mediaExtractor,
        trackResult.mAudioTrackIndex,
        audioOutputFormat,
        queuedMuxer
      )
    } ?: run {
      PassThroughTrackTranscoder(
        mediaExtractor,
        trackResult.mAudioTrackIndex,
        queuedMuxer,
        AUDIO
      )
    }
  }

  private fun getVideoTranscoder(
    videoOutputFormat: MediaFormat?,
    mediaExtractor: MediaExtractor,
    trackResult: TrackResult,
    queuedMuxer: QueuedMuxer
  ) = videoOutputFormat?.let {
    VideoTrackTranscoder(
      mediaExtractor,
      trackResult.mVideoTrackIndex,
      videoOutputFormat,
      queuedMuxer
    )
  } ?: run {
    PassThroughTrackTranscoder(
      mediaExtractor,
      trackResult.mVideoTrackIndex,
      queuedMuxer,
      VIDEO
    )
  }

  private fun extractVideoDuration(
    inFd: FileDescriptor,
    mediaMuxer: MediaMuxer
  ): Long {
    val mediaMetadataRetriever = MediaMetadataRetriever()
    mediaMetadataRetriever.setDataSource(inFd)

    val rotationString = mediaMetadataRetriever.extractMetadata(
      MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION
    )
    rotationString?.toSafeInt()
      ?.let { it1 -> mediaMuxer.setOrientationHint(it1) }

    val location = mediaMetadataRetriever.extractMetadata(
      MediaMetadataRetriever.METADATA_KEY_LOCATION
    )
    location?.let {
      val parsedLocation = ISO6709LocationParser().parse(it)
      parsedLocation?.let {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
          mediaMuxer.setLocation(parsedLocation[0], parsedLocation[1])
        }
      }
    }

    return try {
      mediaMetadataRetriever.extractMetadata(
        MediaMetadataRetriever.METADATA_KEY_DURATION
      )!!.toLong()
        .times(1000)
    } catch (e: NumberFormatException) {
      -1
    }
  }
}

fun String.toSafeInt(): Int? {
  return try {
    this.toInt()
  } catch (ex: Exception) {
    null
  }
}
