package com.follow.clash.service.models

data class NotificationParams(
    val title: String = "FlClash",
    val stopText: String = "STOP",
    val onlyStatisticsProxy: Boolean = false,
    val showStopAction: Boolean = true,
    val liveUpdateNotification: Boolean = true,
    val liveUpdateShowUpload: Boolean = true,
    val liveUpdateFixedWidth: Boolean = true,
    val trafficUsed: Long = 0L,
    val trafficTotal: Long = 0L,
)
