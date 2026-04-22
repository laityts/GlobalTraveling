package com.kankan.globaltraveling

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import android.util.Log

class SimulationForegroundService : Service() {
    companion object {
        private const val CHANNEL_ID = "simulation_channel"
        private const val NOTIF_ID = 1001

        fun start(context: Context, locationName: String) {
            // Android 13+ 需要通知权限，如果没有则无法启动前台服务，改为普通服务
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                Log.w("ShadowSim", "缺少通知权限，无法启动前台服务，位置模拟可能被系统限制")
                val intent = Intent(context, SimulationForegroundService::class.java).apply {
                    putExtra("location_name", locationName)
                }
                context.startService(intent)
                return
            }
            val intent = Intent(context, SimulationForegroundService::class.java).apply {
                putExtra("location_name", locationName)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SimulationForegroundService::class.java))
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val locationName = intent?.getStringExtra("location_name") ?: "未知位置"
        try {
            val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("位置模拟中")
                .setContentText("当前模拟位置: $locationName")
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
            startForeground(NOTIF_ID, notification)
        } catch (e: SecurityException) {
            Log.e("ShadowSim", "无法启动前台服务，缺少通知权限", e)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "位置模拟服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "保持位置模拟激活状态"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}