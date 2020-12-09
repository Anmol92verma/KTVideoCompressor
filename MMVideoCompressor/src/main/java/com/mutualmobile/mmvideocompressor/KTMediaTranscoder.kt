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
  ): Boolean {
    return withContext(Dispatchers.IO) {
      currentTranscodingPath = inPath
      currentTranscodingOutPath = outPath
      val fileInputStream = FileInputStream(inPath)
      val inFileDescriptor = fileInputStream.fd
      val engine = MediaTranscoderEngine(
        mediaFileDescriptor = inFileDescriptor
      )
      engine.transcodeVideo(
        outFormatStrategy = outFormatStrategy,
        coroutineContext = coroutineContext,
        progressChannel = progressChannel,
        outPath = outPath
      )
      fileInputStream.close()
      true
    }
  }

  suspend fun videoInfoExtract(videoPath: String?): Pair<MediaFormat?, MediaFormat?> {
    return withContext(Dispatchers.IO) {
      val fileInputStream = FileInputStream(videoPath)
      val inFileDescriptor = fileInputStream.fd
      val engine = MediaTranscoderEngine(mediaFileDescriptor = inFileDescriptor)
      val info = engine.extractInfo(coroutineContext)
      fileInputStream.close()
      info
    }
  }
}
