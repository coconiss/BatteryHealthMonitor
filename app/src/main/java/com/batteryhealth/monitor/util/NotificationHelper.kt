// util/NotificationHelper.kt (개선 버전)
package com.batteryhealth.monitor.util

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.batteryhealth.monitor.R
import com.batteryhealth.monitor.ui.main.MainActivity
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NotificationHelper @Inject constructor(
    private val context: Context
) {

    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // 충전 모니터링 채널
            val chargingChannel = NotificationChannel(
                CHANNEL_MONITORING,
                "충전 모니터링",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "배터리 충전 중 데이터 수집"
                setShowBadge(false)
            }

            // 방전 모니터링 채널
            val dischargeChannel = NotificationChannel(
                CHANNEL_DISCHARGE_MONITORING,
                "방전 모니터링",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "배터리 사용 중 데이터 수집"
                setShowBadge(false)
            }

            // 완료 채널
            val completionChannel = NotificationChannel(
                CHANNEL_COMPLETION,
                "측정 완료",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "충전 세션 완료 알림"
            }

            notificationManager.createNotificationChannel(chargingChannel)
            notificationManager.createNotificationChannel(dischargeChannel)
            notificationManager.createNotificationChannel(completionChannel)
        }
    }

    /**
     * 충전 모니터링 알림
     */
    fun createMonitoringNotification(
        percentage: Int? = null,
        temperature: Float? = null
    ): Notification {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val contentText = if (percentage != null && temperature != null) {
            "배터리: ${percentage}% | 온도: ${String.format("%.1f", temperature)}°C"
        } else {
            "배터리 충전 데이터 수집 중..."
        }

        return NotificationCompat.Builder(context, CHANNEL_MONITORING)
            .setContentTitle("배터리 Health 측정 중 (충전)")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_battery)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    /**
     * 방전 모니터링 알림
     */
    fun createDischargeMonitoringNotification(
        percentage: Int? = null,
        temperature: Float? = null
    ): Notification {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            1, // 다른 ID 사용
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val contentText = if (percentage != null && temperature != null) {
            "배터리: ${percentage}% | 온도: ${String.format("%.1f", temperature)}°C"
        } else {
            "배터리 사용 데이터 수집 중..."
        }

        return NotificationCompat.Builder(context, CHANNEL_DISCHARGE_MONITORING)
            .setContentTitle("배터리 Health 측정 중 (방전)")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_battery)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setColor(0xFFFF9800.toInt()) // 주황색으로 구분
            .build()
    }

    fun showSessionCompletedNotification(
        sessionId: Long,
        estimatedCapacity: Int?
    ) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            sessionId.toInt(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val contentText = if (estimatedCapacity != null) {
            "추정 용량: ${estimatedCapacity} mAh"
        } else {
            "충전 데이터가 수집되었습니다"
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_COMPLETION)
            .setContentTitle("충전 세션 완료")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_battery)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        notificationManager.notify(sessionId.toInt(), notification)
    }

    companion object {
        const val CHANNEL_MONITORING = "battery_monitoring"
        const val CHANNEL_DISCHARGE_MONITORING = "battery_discharge_monitoring"
        const val CHANNEL_COMPLETION = "session_completion"

        const val MONITORING_NOTIFICATION_ID = 1001
        const val DISCHARGE_MONITORING_NOTIFICATION_ID = 1002
    }
}