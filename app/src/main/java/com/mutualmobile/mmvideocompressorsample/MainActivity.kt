package com.mutualmobile.mmvideocompressorsample

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.database.DatabaseUtils
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.FileProvider
import com.mutualmobile.mmvideocompressor.mediaStrategy.Android480pFormatStrategy
import com.mutualmobile.mmvideocompressor.mediaStrategy.NoOpMediaFormatStrategy
import kotlinx.android.synthetic.main.activity_main.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import java.io.File


class MainActivity : AppCompatActivity() {

  private val progressChannel = MutableStateFlow<Double>(0.0)
  private var job: Job? = null
  private val ktMediaTranscoder = com.mutualmobile.mmvideocompressor.KTMediaTranscoder()
  private var transcodingJob: CoroutineScope? = null

  private val REQUEST_READ_STORAGE: Int = 1

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_main)

    pickVideo.setOnClickListener {
      if (ActivityCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
        != PackageManager.PERMISSION_GRANTED) {
        ActivityCompat.requestPermissions(this,
          arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
          REQUEST_READ_STORAGE)
      } else {
        pickVideoInternal()
      }
    }
  }

  override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>,
    grantResults: IntArray) {
    if (requestCode == REQUEST_READ_STORAGE) {
      if (grantResults.size == 1 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
        pickVideoInternal()
      } else {
        // Permission denied
        Toast.makeText(this, "Transcoder is useless without access to external storage :/",
          Toast.LENGTH_SHORT).show();
      }
    } else {
      super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }
  }

  private fun pickVideoInternal() {
    openCameraForVideo(1).apply {
      mediaPath = this
    }
  }
  var mediaPath: String = ""

  override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
    super.onActivityResult(requestCode, resultCode, data)
    displayVideoThumb(mediaPath)
    initTranscodingInfo(mediaPath)
  }

  fun Activity.openCameraForVideo(
    reqCode: Int
  ): String {

    val dataUriPair = setVideoUri()

    val takeVideoIntent = Intent(MediaStore.ACTION_VIDEO_CAPTURE).apply {
      putExtra(MediaStore.EXTRA_OUTPUT, dataUriPair.first)
      putExtra(MediaStore.EXTRA_DURATION_LIMIT, 60)
    }

    val chooserIntent = Intent.createChooser(takeVideoIntent, "Capture Video")
    this.startActivityForResult(chooserIntent, reqCode)

    return dataUriPair.second
  }

  private fun Activity.setVideoUri(): Pair<Uri, String> {
    val folder = File("${getExternalFilesDir(Environment.DIRECTORY_MOVIES)}")
    folder.mkdir()

    val file = File(folder, "temp.mp4")
    if (file.exists()) {
      file.delete()
    }

    file.createNewFile()

    val videoUri =
      FileProvider.getUriForFile(this, BuildConfig.APPLICATION_ID + ".fileProvider", file)
    val videoPath = file.absolutePath
    return Pair(videoUri, videoPath)
  }

  private fun initTranscodingInfo(videoPath: String?) {

    transcodingJob?.let {
      it.cancel()
    }
    job = Job()
    transcodingJob = CoroutineScope(Dispatchers.Main + job!!)

    transcodingJob?.launch {
      val (videoFormat, audioFormat) = ktMediaTranscoder.videoInfoExtract(videoPath)

      videoFormat?.let {
        val width = it.getInteger(MediaFormat.KEY_WIDTH)
        val height = it.getInteger(MediaFormat.KEY_HEIGHT)

        edtHeight.setText(height.toString())
        edtWidth.setText(width.toString())

      }

      val bitRate = audioFormat?.getInteger(MediaFormat.KEY_BIT_RATE)
      bitRate?.let {
        edtBitRate.setText(bitRate.toString())
      }
    }

    transcodeNow.setOnClickListener {
      transcodeVideo(videoPath, edtWidth.text.toString(), edtHeight.text.toString(),
        edtBitRate.text.toString())
    }
  }

  private fun displayVideoThumb(videoPath: String) {
    val videoPath = File(videoPath)
    mediathumb.setVideoURI(Uri.fromFile(videoPath))
    mediathumb.start()
    mediathumb.setOnCompletionListener {
      it.seekTo(0)
      it.start()
    }
  }

  private fun transcodeVideo(videoPath: String?, width: String,
    height: String, bitrate: String) {
    transcodingJob?.launch(Dispatchers.IO) {

      btnStop.setOnClickListener {
        transcodingJob?.cancel()
      }

      videoPath?.let {
        val formatStrategy = Android480pFormatStrategy()
        val tempFile = File(externalCacheDir, "${System.currentTimeMillis()}.mp4")
        ktMediaTranscoder.transcodeVideo(it, tempFile.path, formatStrategy,progressChannel)
        runOnUiThread {
          Log.e("path",File(it).length().toString())

          Log.e("path",tempFile.length().toString())
          onTranscodingDone(tempFile)
          progress.progress = 0
        }

      }
    }
    transcodingJob?.launch {
      progressChannel.collect {
        when {
          Build.VERSION.SDK_INT >= Build.VERSION_CODES.N -> progress.setProgress(
            it.times(100).toInt(), true)
          else -> progress.progress = it.times(100).toInt()
        }
      }
    }
  }

  private fun onTranscodingDone(tempFile: File) {
    val uri =  FileProvider.getUriForFile(this, BuildConfig.APPLICATION_ID + ".fileProvider", tempFile)
    videoview.setVideoURI(uri)
    videoview.start()

    videoview.setOnCompletionListener {
      it.seekTo(0)
      it.start()
    }

    /*//grant permision for app with package "packegeName", eg. before starting other app via intent
   grantUriPermission(packageName, uri,
       Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)

   val resInfoList = packageManager.queryIntentActivities(intent,
       PackageManager.MATCH_DEFAULT_ONLY)
   for (resolveInfo in resInfoList) {
     val packageName = resolveInfo.activityInfo.packageName
     grantUriPermission(packageName, uri,
         Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
   }

   val intent = Intent(Intent.ACTION_VIEW, uri)
   intent.setDataAndType(uri, "video/mp4")
   startActivity(intent)*/
  }

  override fun onDestroy() {
    super.onDestroy()
    transcodingJob?.cancel()
  }
}
