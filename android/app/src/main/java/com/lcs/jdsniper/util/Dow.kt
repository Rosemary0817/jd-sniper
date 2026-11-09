package com.lcs.jdsniper.util

/** ISO 周几（1=周一 … 7=周日）与中文标签互转，界面和日志共用一套。 */
object Dow {

    val LABELS = arrayOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

    fun label(dow: Int): String = LABELS[((dow - 1) % 7 + 7) % 7]

    fun next(dow: Int): Int = dow % 7 + 1

    fun short(dow: Int): String = LABELS[((dow - 1) % 7 + 7) % 7].removePrefix("周")
}
