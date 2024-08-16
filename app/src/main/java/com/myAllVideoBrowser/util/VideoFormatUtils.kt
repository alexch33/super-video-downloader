package com.myAllVideoBrowser.util


class VideoFormatUtils {
    companion object {
//        val mapOfQuality =
//            mapOf<Int, Resolution>(
//                240 to Resolution.of(352, 240),
//                360 to Resolution.of(640, 360),
//                480 to Resolution.of(854, 480),
//                720 to Resolution.of(1280, 720),
//                1080 to Resolution.of(1920, 1080),
//                1440 to Resolution.of(2560, 1440),
//                2160 to Resolution.of(3860, 2160),
//                4320 to Resolution.of(7680, 4320)
//            )

        fun videoFormatToString(input: String): String {
            val widthReg = Regex("width=(\\d+)")
            val heightReg = Regex("height=(\\d+)")

            val resultWidth = widthReg.find(input)
            val resultHeight = heightReg.find(input)

            var width: String? = null
            if ((resultWidth?.groupValues?.size ?: 0) > 1) {
                width = resultWidth!!.groupValues[1]
            }
            var height: String? = null
            if ((resultHeight?.groupValues?.size ?: 0) > 1) {
                height = resultHeight!!.groupValues[1]
            }

            if (height != null && width != null) {
                return "$width X $height"
            }

            val youtubeDlReg = Regex("\\d+ - (\\d{3,4}x\\d{3,4})")
            val youtubeDlRes = youtubeDlReg.find(input)

            var result: String? = null
            if ((youtubeDlRes?.groupValues?.size ?: 0) > 1) {
                result = youtubeDlRes!!.groupValues[1]
            }

            return result ?: input
        }
    }
}