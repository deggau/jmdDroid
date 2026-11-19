package com.jmd.android

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.Calendar
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

internal class TaskNotificationWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val config = TaskNotificationSettings.load(applicationContext)
        if (!config.enabled) return Result.success()
        val paired = JmdroidCredentialStore(applicationContext).load() ?: return Result.success()
        val client = try {
            JmdroidApiClient(paired)
        } catch (_: Exception) {
            return Result.success()
        }
        return try {
            val tasks = client.getTasks("open")
            val currentIds = tasks.map { it.id }.toSet()
            val nowMillis = System.currentTimeMillis()
            if (!TaskNotificationSettings.hasBaseline(applicationContext)) {
                TaskNotificationSettings.saveSeenIds(applicationContext, currentIds, initialized = true)
                tasks.filter { it.reminderIntervalMinutes != null && it.reminderIntervalMinutes > 0 }
                    .forEach { TaskNotificationSettings.saveReminderNotifiedAt(applicationContext, it.id, nowMillis) }
                TaskNotificationSettings.retainReminderTasks(applicationContext, currentIds)
                return Result.success()
            }

            val previousIds = TaskNotificationSettings.seenIds(applicationContext)
            val newTasks = tasks.filter { it.id !in previousIds }
            val newTaskIds = newTasks.map { it.id }.toSet()
            val now = Calendar.getInstance()
            val minuteOfDay = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
            newTasks.filter { config.shouldNotify(it.categoryColor, minuteOfDay) }.forEach { showNotification(it, reminder = false) }

            tasks.forEach { task ->
                val intervalMillis = task.reminderIntervalMinutes?.takeIf { it > 0 }?.toLong()?.times(60_000L)
                    ?: return@forEach
                val lastNotifiedAt = TaskNotificationSettings.reminderNotifiedAt(applicationContext, task.id)
                    ?: parseTimestamp(task.lastReminderAt) ?: parseTimestamp(task.createdAt) ?: nowMillis

                if (task.pausedAt != null) {
                    TaskNotificationSettings.saveReminderNotifiedAt(applicationContext, task.id, nowMillis)
                } else if (task.id !in newTaskIds &&
                    nowMillis - lastNotifiedAt >= intervalMillis &&
                    config.shouldNotify(task.categoryColor, minuteOfDay)
                ) {
                    showNotification(task, reminder = true)
                    TaskNotificationSettings.saveReminderNotifiedAt(applicationContext, task.id, nowMillis)
                } else if (TaskNotificationSettings.reminderNotifiedAt(applicationContext, task.id) == null) {
                    TaskNotificationSettings.saveReminderNotifiedAt(applicationContext, task.id, lastNotifiedAt)
                }
            }
            TaskNotificationSettings.retainReminderTasks(applicationContext, currentIds)
            TaskNotificationSettings.saveSeenIds(applicationContext, previousIds + currentIds, initialized = true)
            Result.success()
        } catch (error: ApiException) {
            if (error.status == 429 || error.status >= 500) Result.retry() else Result.success()
        } catch (_: Exception) {
            Result.retry()
        } finally {
            client.close()
        }
    }

    private fun showNotification(task: JmdroidTask, reminder: Boolean) {
        if (Build.VERSION.SDK_INT >= 33 && applicationContext.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(NotificationChannel(
                CHANNEL_ID,
                "Tarefas do JMD",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = "Novas tarefas do JMD conforme as prioridades configuradas." })
        }
        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            task.id.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_jmd)
            .setContentTitle(if (reminder) "Lembrete de tarefa · ${task.categoryColor}" else "Nova tarefa · ${task.categoryColor}")
            .setContentText(task.title)
            .setStyle(NotificationCompat.BigTextStyle().bigText(task.description.ifBlank { task.title }))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        try {
            NotificationManagerCompat.from(applicationContext).notify(task.id.hashCode(), builder.build())
        } catch (_: SecurityException) {
            // Android 13+ can revoke notification permission after scheduling.
        }
    }

    private fun parseTimestamp(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        val normalized = value.trim()
            .replace(Regex("\\.(\\d{3})\\d+(?=Z|[+-]\\d{2}:?\\d{2}$)")) { ".${it.groupValues[1]}" }
            .replace(Regex("Z$"), "+0000")
            .replace(Regex("([+-]\\d{2}):(\\d{2})$")) { it.groupValues[1] + it.groupValues[2] }
        val patterns = listOf("yyyy-MM-dd'T'HH:mm:ss.SSSZ", "yyyy-MM-dd'T'HH:mm:ssZ")
        return patterns.firstNotNullOfOrNull { pattern ->
            try {
                SimpleDateFormat(pattern, Locale.US).apply {
                    isLenient = false
                    timeZone = TimeZone.getTimeZone("UTC")
                }.parse(normalized)?.time
            } catch (_: Exception) { null }
        }
    }

    companion object {
        private const val CHANNEL_ID = "jmdroid_task_notifications"
        private const val INITIAL_WORK_NAME = "jmdroid-task-notifications-initial"

        fun schedule(context: Context) {
            val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                INITIAL_WORK_NAME,
                androidx.work.ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<TaskNotificationWorker>().setConstraints(constraints).build()
            )
            val request = PeriodicWorkRequestBuilder<TaskNotificationWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                TaskNotificationSettings.WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(TaskNotificationSettings.WORK_NAME)
            WorkManager.getInstance(context).cancelUniqueWork(INITIAL_WORK_NAME)
        }
    }
}
