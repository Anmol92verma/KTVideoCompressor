package com.mutualmobile.mmvideocompressor

import android.media.MediaFormat
import com.mutualmobile.mmvideocompressor.mediaStrategy.MediaFormatStrategy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import java.io.FileInputStream

class KTMediaTranscoder {

  var currentTranscodingPath: String? = null
  var currentTranscodingOutPath: String? = null

  suspend fun transcodeVideo(
    inPath: String,
    outPath: String,
    outFormatStrategy: MediaFormatStrategy,
    progressChannel: MutableStateFlow<Double>?
  ) {
    return withContext(Dispatchers.IO) {
      currentTranscodingPath = inPath
      currentTranscodingOutPath = outPath
      val fileInputStream = FileInputStream(inPath)
      val inFileDescriptor = fileInputStream.fd
      val engine = MediaTranscoderEngine()
      engine.transcodeVideo(
        outFormatStrategy = outFormatStrategy,
        coroutineContext = coroutineContext,
        progressChannel = progressChannel,
        outPath = outPath,
        mediaFileDescriptor = inFileDescriptor
      )
      fileInputStream.close()
    }
  }

  suspend fun videoInfoExtract(videoPath: String?): Pair<MediaFormat?, MediaFormat?> {
    return withContext(Dispatchers.IO) {
      val fileInputStream = FileInputStream(videoPath)
      val inFileDescriptor = fileInputStream.fd
      val engine = MediaTranscoderEngine()
      val info = engine.extractInfo(mediaFileDescriptor = inFileDescriptor, coroutineContext)
      fileInputStream.close()
      info
    }
  }
}
