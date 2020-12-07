package com.mutualmobile.mmvideocompressor.utils

import java.util.regex.Pattern

class ISO6709LocationParser {
  private val pattern: Pattern = Pattern.compile("([+\\-][0-9.]+)([+\\-][0-9.]+)")

  /**
   * This method parses the given string representing a geographic point location by coordinates in ISO 6709 format
   * and returns the latitude and the longitude in float. If `location` is not in ISO 6709 format,
   * this method returns `null`
   *
   * @param location a String representing a geographic point location by coordinates in ISO 6709 format
   * @return `null` if the given string is not as expected, an array of floats with size 2,
   * where the first element represents latitude and the second represents longitude, otherwise.
   */
  fun parse(location: String?): FloatArray? {
    if (location == null) return null
    val m = pattern.matcher(location)
    if (m.find() && m.groupCount() == 2) {
      val latstr = m.group(1)
      val lonstr = m.group(2)
      try {
        latstr?.let {
          lonstr?.let {
            val lat = java.lang.Float.parseFloat(latstr)
            val lon = java.lang.Float.parseFloat(lonstr)
            return floatArrayOf(lat, lon)
          }
        }
      } catch (ignored: NumberFormatException) {
      }
    }
    return null
  }
}
