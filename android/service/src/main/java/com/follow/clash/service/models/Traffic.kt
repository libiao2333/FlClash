package com.follow.clash.service.models

import com.follow.clash.common.GlobalState
import com.follow.clash.core.Core
import com.google.gson.Gson

private val gson = Gson()

data class Traffic(
    val up: Long,
    val down: Long,
)

private val Long.formatBytes: String
    get() {
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var value = toDouble()
        var unit = 0
        while (value >= 1024 && unit < units.lastIndex) {
            value /= 1024
            unit++
        }
        return if (unit == 0) {
            "${value.toLong()}${units[unit]}"
        } else {
            "%.1f${units[unit]}".format(value)
        }
    }

val Traffic.speedText: String
    get() = "${up.formatBytes}/s↑  ${down.formatBytes}/s↓"

/** 状态栏胶囊（setShortCriticalText）用的极简文案，最长约 7 个字符。 */
val Traffic.shortSpeedText: String
    get() = "↓${down.formatBytes}/s"

fun Core.getSpeedTraffic(onlyStatisticsProxy: Boolean): Traffic? {
    return runCatching {
        gson.fromJson(getTraffic(onlyStatisticsProxy), Traffic::class.java)
    }.onFailure { error ->
        GlobalState.log("Unable to read traffic: $error")
    }.getOrNull()
}
