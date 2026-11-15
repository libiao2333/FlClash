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

/** 通知正文用：上传在左、下载在右。 */
val Traffic.directionalText: String
    get() = "↑${up.formatBytes}/s   ↓${down.formatBytes}/s"

/**
 * 状态栏胶囊用的短速率。fixedWidth 为 true 时恒为 4 个字符，
 * 不足的位数用 figure space(U+2007) 补在左侧，避免胶囊宽度来回跳动。
 */
fun Long.formatRate(fixedWidth: Boolean): String {
    val units = arrayOf("B", "K", "M", "G")
    var value = toDouble()
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    val number = if (value < 10) {
        "%.1f".format(value)
    } else {
        value.toInt().coerceAtMost(999).toString()
    }
    val text = number + units[unit]
    return if (fixedWidth) text.padStart(4, "\u2007") else text
}

fun Core.getSpeedTraffic(onlyStatisticsProxy: Boolean): Traffic? {
    return runCatching {
        gson.fromJson(getTraffic(onlyStatisticsProxy), Traffic::class.java)
    }.onFailure { error ->
        GlobalState.log("Unable to read traffic: $error")
    }.getOrNull()
}
