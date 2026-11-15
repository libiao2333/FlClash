package com.follow.clash.service.modules

import android.app.Notification
import android.app.Notification.FOREGROUND_SERVICE_IMMEDIATE
import android.app.NotificationManager
import android.app.Service
import android.app.Service.STOP_FOREGROUND_REMOVE
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import com.follow.clash.common.Components
import com.follow.clash.common.GlobalState
import com.follow.clash.common.QuickAction
import com.follow.clash.common.ensureNotificationChannel
import com.follow.clash.common.quickIntent
import com.follow.clash.common.receiveBroadcastFlow
import com.follow.clash.common.startForeground
import com.follow.clash.common.toPendingIntent
import com.follow.clash.core.Core
import com.follow.clash.service.R
import com.follow.clash.service.ServiceConfig
import com.follow.clash.service.models.NotificationParams
import com.follow.clash.service.models.Traffic
import com.follow.clash.service.models.directionalText
import com.follow.clash.service.models.downRateText
import com.follow.clash.service.models.formatRate
import com.follow.clash.service.models.getSpeedTraffic
import com.follow.clash.service.models.upRateText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch

/**
 * Android 16 实时更新（Live Updates，OPPO 侧叫“流体云”）进度条的满量程：
 * 1MB/s 记为 100%，超过则饱和到 100%。想让低速流量也把进度条顶满，
 * 把这个值改小即可，例如 256 * 1024。
 */
private const val LIVE_UPDATE_FULL_SCALE = 1024L * 1024L

/** 上行速率标记点颜色，对应 systemBlue。 */
private const val LIVE_UPDATE_UPLOAD_COLOR = 0xFF0A84FF.toInt()

private data class ExtendedNotificationParams(
    val title: String,
    val stopText: String,
    val showStopAction: Boolean,
    val traffic: Traffic?,
    val liveUpdate: Boolean,
    val showUpload: Boolean,
    val fixedWidth: Boolean,
) {
    val contentText: String
        get() = traffic?.directionalText ?: ""

    /** 展开卡片的大字：下行速率。 */
    val liveTitle: String
        get() = traffic?.downRateText ?: title

    /** 展开卡片的第二行：上行速率。 */
    val liveText: String
        get() = traffic?.upRateText ?: contentText

    /**
     * 状态栏胶囊文案：上传在左、下载在右。关掉“显示上传”后只剩下载，
     * 字符数恒定由 fixedWidth 保证，避免胶囊宽度变化挤压信号/WiFi 图标。
     */
    val shortCriticalText: String
        get() {
            val traffic = traffic ?: return ""
            val down = traffic.down.formatRate(fixedWidth)
            if (!showUpload) return "↓$down"
            return "↑${traffic.up.formatRate(fixedWidth)} ↓$down"
        }

    val up: Long
        get() = traffic?.up ?: 0L

    val down: Long
        get() = traffic?.down ?: 0L
}

private val NotificationParams.extended: ExtendedNotificationParams
    get() = ExtendedNotificationParams(
        title = title,
        stopText = stopText,
        showStopAction = showStopAction,
        traffic = Core.getSpeedTraffic(onlyStatisticsProxy),
        liveUpdate = liveUpdateNotification,
        showUpload = liveUpdateShowUpload,
        fixedWidth = liveUpdateFixedWidth,
    )

/** 把字节/秒换算成 0..100 的进度值。 */
private fun Long.toProgressPercent(): Int =
    (this * 100L / LIVE_UPDATE_FULL_SCALE).coerceIn(0L, 100L).toInt()

internal class NotificationModule(
    private val service: Service,
    private val scope: CoroutineScope,
) : ServiceModule {
    override fun start() {
        service.ensureNotificationChannel()
        update(ServiceConfig.notificationParams.value.extended)
        scope.launch {
            // Some ROMs, vivo's among them, drop updates posted behind the
            // keyguard, so unlocking restarts the loop to post once more.
            // SystemUI, an app, sends USER_PRESENT, which a receiver that is
            // not exported never gets; all three actions are protected.
            service.receiveBroadcastFlow(exported = true) {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
            }.map { intent ->
                intent.action != Intent.ACTION_SCREEN_OFF
            }.onStart {
                emit(isScreenOn())
            }.collectLatest { screenOn ->
                if (!screenOn) return@collectLatest
                combine(
                    flow {
                        while (true) {
                            delay(1_000)
                            emit(Unit)
                        }
                    },
                    ServiceConfig.notificationParams,
                ) { _, params ->
                    params.extended
                }.distinctUntilChanged()
                    .collect(::update)
            }
        }
    }

    private fun isScreenOn() =
        service.getSystemService<PowerManager>()?.isInteractive ?: true

    private val contentIntent by lazy {
        Intent().setComponent(Components.mainActivity).toPendingIntent
    }

    private val stopIntent by lazy { QuickAction.STOP.quickIntent.toPendingIntent }

    private val notificationBuilder: NotificationCompat.Builder by lazy {
        NotificationCompat.Builder(
            service,
            GlobalState.NOTIFICATION_CHANNEL,
        ).apply {
            setSmallIcon(R.drawable.ic_service)
            setContentTitle("FlClash")
            setContentIntent(contentIntent)
            setPriority(NotificationCompat.PRIORITY_LOW)
            setCategory(NotificationCompat.CATEGORY_SERVICE)
            setOngoing(true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                foregroundServiceBehavior = FOREGROUND_SERVICE_IMMEDIATE
            }
            setShowWhen(true)
            setOnlyAlertOnce(true)
        }
    }

    private fun update(params: ExtendedNotificationParams) {
        val notification =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA && params.liveUpdate) {
                buildLiveUpdateNotification(params)
            } else {
                buildCompatNotification(params)
            }
        service.startForeground(notification)
    }

    /** Android 16 及以上：ProgressStyle + 请求提升为实时更新。 */
    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    private fun buildLiveUpdateNotification(params: ExtendedNotificationParams): Notification {
        // 系统/用户可以在设置里关掉本应用的实时更新，关掉后请求会被忽略，
        // 通知仍按 ProgressStyle 正常展示，因此这里只把结果用于日志。
        val canPromote = service.getSystemService<NotificationManager>()
            ?.canPostPromotedNotifications() == true
        if (!canPromote) {
            GlobalState.log("Promoted notifications are unavailable for this app")
        }

        val progressStyle = Notification.ProgressStyle()
            // 进度条按下行速率填充，上行速率用一个标记点表示，两者共用 0..100 的刻度。
            .setProgress(params.down.toProgressPercent())
            .setProgressPoints(
                listOf(
                    Notification.ProgressStyle.Point(params.up.toProgressPercent())
                        .setColor(LIVE_UPDATE_UPLOAD_COLOR),
                ),
            )
            .setStyledByProgress(true)

        return Notification.Builder(service, GlobalState.NOTIFICATION_CHANNEL).apply {
            setSmallIcon(R.drawable.ic_service)
            setContentTitle(params.liveTitle)
            setContentText(params.liveText)
            setContentIntent(contentIntent)
            setCategory(Notification.CATEGORY_SERVICE)
            setOngoing(true)
            setOnlyAlertOnce(true)
            setShowWhen(true)
            setColorized(false)
            setForegroundServiceBehavior(FOREGROUND_SERVICE_IMMEDIATE)
            // 状态栏胶囊：下行速率的极简文案
            setShortCriticalText(params.shortCriticalText)
            // 申请提升为实时更新（流体云），是否真的提升由系统/用户决定
            setRequestPromotedOngoing(true)
            setStyle(progressStyle)
            if (params.showStopAction) {
                // 不传图标，系统按文字按钮渲染
                addAction(
                    Notification.Action.Builder(
                        null,
                        params.stopText,
                        stopIntent,
                    ).build(),
                )
            }
        }.build()
    }

    /** Android 15 及以下：保持改造前的普通通知。 */
    private fun buildCompatNotification(params: ExtendedNotificationParams): Notification =
        with(notificationBuilder) {
            setContentTitle(params.title)
            setContentText(params.contentText)
            clearActions()
            if (params.showStopAction) {
                addAction(0, params.stopText, stopIntent)
            }
            build()
        }

    @Suppress("DEPRECATION")
    override fun stop() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            service.stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            service.stopForeground(true)
        }
    }
}
