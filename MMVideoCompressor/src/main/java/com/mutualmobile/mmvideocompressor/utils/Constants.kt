package com.mutualmobile.mmvideocompressor.utils

// from MediaFormat of API level >= 21, but might be usable in older APIs as native code implementation exists.
// https://android.googlesource.com/platform/frameworks/av/+/lollipop-release/media/libstagefright/ACodec.cpp#2621
// NOTE: native code enforces baseline profile.
// https://android.googlesource.com/platform/frameworks/av/+/lollipop-release/media/libstagefright/ACodec.cpp#2638
/** For encoder parameter. Use value of MediaCodecInfo.CodecProfileLevel.AVCProfile* .  */
val KEY_PROFILE = "profile"

// from https://android.googlesource.com/platform/frameworks/av/+/lollipop-release/media/libstagefright/ACodec.cpp#2623
/** For encoder parameter. Use value of MediaCodecInfo.CodecProfileLevel.AVCLevel* .  */
val KEY_LEVEL = "level"

// from https://android.googlesource.com/platform/frameworks/av/+/lollipop-release/media/libstagefright/MediaCodec.cpp#2197
/** Included in MediaFormat from [android.media.MediaExtractor.getTrackFormat]. Value is [java.nio.ByteBuffer].  */
val KEY_AVC_SPS = "csd-0"

/** Included in MediaFormat from [android.media.MediaExtractor.getTrackFormat]. Value is [java.nio.ByteBuffer].  */
val KEY_AVC_PPS = "csd-1"

/**
 * For decoder parameter and included in MediaFormat from [android.media.MediaExtractor.getTrackFormat].
 * Decoder rotates specified degrees before rendering video to surface.
 * NOTE: Only included in track format of API &gt;= 21.
 */
val KEY_ROTATION_DEGREES = "rotation-degrees"

// Video formats
// from MediaFormat of API level >= 21
val MIMETYPE_VIDEO_AVC = "video/avc"
val MIMETYPE_VIDEO_H263 = "video/3gpp"
val MIMETYPE_VIDEO_VP8 = "video/x-vnd.on2.vp8"

// Audio formats
// from MediaFormat of API level >= 21
val MIMETYPE_AUDIO_AAC = "audio/mp4a-latm"
