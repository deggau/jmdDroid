package com.jmd.android

import android.content.Context

internal data class TaskNotificationConfig(
    val enabled: Boolean,
    val priorities: Set<String>,
    val paused: Boolean,
    val limitToHours: Boolean,
    val startMinute: Int,
    val endMinute: Int
) {
    fun isWithinHours(minuteOfDay: Int): Boolean {
        if (!limitToHours || startMinute == endMinute) return true
        return if (startMinute < endMinute) {
            minuteOfDay in startMinute until endMinute
        } else {
            minuteOfDay >= startMinute || minuteOfDay < endMinute
        }
    }

    fun shouldNotify(categoryColor: String, minuteOfDay: Int): Boolean =
        enabled && !paused && categoryColor in priorities && isWithinHours(minuteOfDay)
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
    private const val KEY_INITIALIZED = "initialized"
    private const val KEY_SEEN_IDS = "seen_ids"

    fun load(context: Context): TaskNotificationConfig {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return TaskNotificationConfig(
            enabled = prefs.getBoolean(KEY_ENABLED, false),
            priorities = prefs.getStringSet(KEY_PRIORITIES, setOf("Vermelho", "Laranja"))?.toSet()
                ?: setOf("Vermelho", "Laranja"),
            paused = prefs.getBoolean(KEY_PAUSED, false),
            limitToHours = prefs.getBoolean(KEY_LIMIT_HOURS, false),
            startMinute = prefs.getInt(KEY_START_MINUTE, 8 * 60).coerceIn(0, 1439),
            endMinute = prefs.getInt(KEY_END_MINUTE, 18 * 60).coerceIn(0, 1439)
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
            .apply()
    }

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
}
