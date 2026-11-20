package com.jmd.android

import android.content.Context
import java.util.Calendar

internal data class TaskNotificationConfig(
    val enabled: Boolean,
    val priorities: Set<String>,
    val paused: Boolean,
    val limitToHours: Boolean,
    val startMinute: Int,
    val endMinute: Int,
    val activeDays: Set<Int>
) {
    fun isWithinHours(minuteOfDay: Int, dayOfWeek: Int): Boolean {
        if (!limitToHours) return true
        val scheduledDay = if (startMinute == endMinute || startMinute < endMinute || minuteOfDay >= startMinute) {
            dayOfWeek
        } else {
            if (dayOfWeek == Calendar.SUNDAY) Calendar.SATURDAY else dayOfWeek - 1
        }
        if (scheduledDay !in activeDays) return false
        if (startMinute == endMinute) return true
        return if (startMinute < endMinute) {
            minuteOfDay in startMinute until endMinute
        } else {
            minuteOfDay >= startMinute || minuteOfDay < endMinute
        }
    }

    fun shouldNotify(categoryColor: String, minuteOfDay: Int, dayOfWeek: Int): Boolean =
        enabled && !paused && categoryColor in priorities && isWithinHours(minuteOfDay, dayOfWeek)
}

internal object TaskNotificationSettings {
    val categories = listOf("Vermelho", "Laranja", "Amarelo", "Verde")
    const val WORK_NAME = "jmdroid-task-notifications"

    private const val PREFS_NAME = "jmdroid_task_notifications"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_PRIORITIES = "priorities"
    private const val KEY_PAUSED = "paused"
    private const val KEY_LIMIT_HOURS = "limit_hours"
    private const val KEY_START_MINUTE = "start_minute"
    private const val KEY_END_MINUTE = "end_minute"
    private const val KEY_ACTIVE_DAYS = "active_days"
    private const val KEY_INITIALIZED = "initialized"
    private const val KEY_SEEN_IDS = "seen_ids"
    private const val KEY_REMINDER_PREFIX = "reminder_notified_"

    fun load(context: Context): TaskNotificationConfig {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return TaskNotificationConfig(
            enabled = prefs.getBoolean(KEY_ENABLED, false),
            priorities = prefs.getStringSet(KEY_PRIORITIES, setOf("Vermelho", "Laranja"))?.toSet()
                ?: setOf("Vermelho", "Laranja"),
            paused = prefs.getBoolean(KEY_PAUSED, false),
            limitToHours = prefs.getBoolean(KEY_LIMIT_HOURS, true),
            startMinute = prefs.getInt(KEY_START_MINUTE, 9 * 60).coerceIn(0, 1439),
            endMinute = prefs.getInt(KEY_END_MINUTE, 17 * 60).coerceIn(0, 1439),
            activeDays = prefs.getStringSet(KEY_ACTIVE_DAYS, weekdays().map(Int::toString).toSet())?.mapNotNull { it.toIntOrNull() }?.toSet()
                ?.intersect((Calendar.SUNDAY..Calendar.SATURDAY).toSet()) ?: weekdays()
        )
    }

    fun save(context: Context, config: TaskNotificationConfig) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ENABLED, config.enabled)
            .putStringSet(KEY_PRIORITIES, config.priorities.intersect(categories.toSet()))
            .putBoolean(KEY_PAUSED, config.paused)
            .putBoolean(KEY_LIMIT_HOURS, config.limitToHours)
            .putInt(KEY_START_MINUTE, config.startMinute.coerceIn(0, 1439))
            .putInt(KEY_END_MINUTE, config.endMinute.coerceIn(0, 1439))
            .putStringSet(KEY_ACTIVE_DAYS, config.activeDays.intersect((Calendar.SUNDAY..Calendar.SATURDAY).toSet()).map(Int::toString).toSet())
            .apply()
    }

    private fun weekdays() = setOf(Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY, Calendar.FRIDAY)

    fun hasBaseline(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_INITIALIZED, false)

    fun seenIds(context: Context): Set<String> =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getStringSet(KEY_SEEN_IDS, emptySet())?.toSet().orEmpty()

    fun saveSeenIds(context: Context, ids: Set<String>, initialized: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putStringSet(KEY_SEEN_IDS, ids.toList().takeLast(1000).toSet())
            .putBoolean(KEY_INITIALIZED, initialized)
            .apply()
    }

    fun reminderNotifiedAt(context: Context, taskId: String): Long? =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .takeIf { it.contains(reminderKey(taskId)) }
            ?.getLong(reminderKey(taskId), 0L)

    fun saveReminderNotifiedAt(context: Context, taskId: String, timestamp: Long) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putLong(reminderKey(taskId), timestamp)
            .apply()
    }

    fun retainReminderTasks(context: Context, taskIds: Set<String>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val validKeys = taskIds.map(::reminderKey).toSet()
        prefs.edit().apply {
            prefs.all.keys.filter { it.startsWith(KEY_REMINDER_PREFIX) && it !in validKeys }
                .forEach(::remove)
        }.apply()
    }

    private fun reminderKey(taskId: String) = "$KEY_REMINDER_PREFIX$taskId"
}
