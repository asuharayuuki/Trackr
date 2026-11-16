package com.trackr.util

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.app.AlarmManagerCompat
import com.trackr.core.data.local.ReminderScheduleStateStore
import com.trackr.core.data.local.SerialWriter
import com.trackr.core.domain.platform.ReminderAlarms
import com.trackr.core.model.ScheduledReminder
import com.trackr.receiver.ScheduledReminderReceiver
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap

/**
 * 定时提醒硬件闹钟调度器 (AlarmManager)
 *
 * 第 4 步从 object 改成类，由 Koin 提供单例，接收器注入它；第 7 步起实现 `:core:domain` 的 [ReminderAlarms]。
 * 每个提醒上次排的触发时刻记在 [ReminderScheduleState] 里（第 9 步）：USE_NEW_STORE 开时在 Room
 * `reminder.lastScheduledTriggerAt`，关时在原来的 `trackr_reminder_schedule_state` 文件。
 * 原来的 object `ReminderScheduler` 已删（只有文件名还沿用旧名）；纯函数和常量在伴生对象里。
 */
class ReminderAlarmScheduler(
    context: Context,
    private val scheduleState: ReminderScheduleState = SharedPrefsReminderScheduleState(context)
) : ReminderAlarms {

    private val appContext: Context = context.applicationContext ?: context

    override fun canScheduleExact(): Boolean = canScheduleExact(appContext)

    override fun openExactAlarmSettings(): Boolean = openExactAlarmSettings(appContext)

    override fun lastScheduledTriggerMillis(reminderId: String): Long? = scheduleState.get(reminderId)

    override fun schedule(reminder: ScheduledReminder) = scheduleReminder(appContext, reminder, scheduleState)

    override fun cancel(reminderId: String) = cancelReminder(appContext, reminderId, scheduleState)

    override fun rescheduleAll(reminders: List<ScheduledReminder>) = rescheduleAll(appContext, reminders, scheduleState)

    companion object {

        private const val TAG = "ReminderScheduler"

        /**
         * 当前是否可以设置精确闹钟。
         * Android 12 以下无需授权，始终为 true；Android 12+ 取决于用户是否授予 SCHEDULE_EXACT_ALARM。
         * 供 UI 设置提醒时判断是否需要引导用户开启「闹钟和提醒」权限。
         */
        fun canScheduleExact(context: Context): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return false
            return alarmManager.canScheduleExactAlarms()
        }

        /**
         * 打开本应用的「闹钟和提醒」授权页（Android 12+）；打不开时退回应用详情页。都打不开返回 false。
         * Android 12 以下不用授权，直接返回 false（编辑页那时也不会显示「去设置」）。
         */
        fun openExactAlarmSettings(context: Context): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
            val packageUri = Uri.fromParts("package", context.packageName, null)
            val intents = listOf(
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, packageUri),
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri)
            )
            return intents.any { intent ->
                try {
                    context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    true
                } catch (e: ActivityNotFoundException) {
                    Log.w(TAG, "Cannot open ${intent.action}", e)
                    false
                } catch (e: SecurityException) {
                    Log.w(TAG, "Cannot open ${intent.action}", e)
                    false
                }
            }
        }

        /**
         * 读取指定提醒最近一次计划的触发时间；从未调度或已取消时返回 null
         */
        fun getLastScheduledTriggerMillis(context: Context, reminderId: String): Long? =
            SharedPrefsReminderScheduleState(context).get(reminderId)

        /**
         * 找出计划时间已过却未触发（如关机期间错过）的一次性提醒 ID。
         * 没有计划时间记录的提醒不视为过期，保持原有重新调度行为。
         *
         * @param lastScheduledTrigger 根据提醒 ID 查询上次计划触发时间
         */
        fun findExpiredOneTimeReminderIds(
            reminders: List<ScheduledReminder>,
            nowMillis: Long,
            lastScheduledTrigger: (String) -> Long?
        ): Set<String> {
            return reminders
                .filter { it.enabled && it.isOneTime }
                .filter { reminder ->
                    val planned = lastScheduledTrigger(reminder.id)
                    planned != null && planned <= nowMillis
                }
                .map { it.id }
                .toSet()
        }

        /**
         * 计算下一个准确触发的时间戳 (毫秒)
         *
         * @param hour 目标小时 (0..23)
         * @param minute 目标分钟 (0..59)
         * @param daysOfWeek 目标星期集合 (1=周一 .. 7=周日)；若为空表示仅响一次
         * @param nowMillis 当前时间毫秒戳，方便单元测试注入
         */
        fun calculateNextTriggerMillis(
            hour: Int,
            minute: Int,
            daysOfWeek: Set<Int>,
            nowMillis: Long = System.currentTimeMillis()
        ): Long {
            val nowCal = Calendar.getInstance().apply {
                timeInMillis = nowMillis
            }

            if (daysOfWeek.isEmpty()) {
                // 仅一次模式
                val targetCal = Calendar.getInstance().apply {
                    timeInMillis = nowMillis
                    set(Calendar.HOUR_OF_DAY, hour)
                    set(Calendar.MINUTE, minute)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                if (targetCal.timeInMillis <= nowMillis) {
                    targetCal.add(Calendar.DAY_OF_YEAR, 1)
                }
                return targetCal.timeInMillis
            }

            // 周期重复模式 (1=周一 .. 7=周日)
            // Calendar 中的 DAY_OF_WEEK: SUNDAY=1, MONDAY=2, TUESDAY=3, WEDNESDAY=4, THURSDAY=5, FRIDAY=6, SATURDAY=7
            // 我们将其转换为 ISO-8601 标准：周一=1, 周日=7
            for (dayOffset in 0..7) {
                val candidateCal = Calendar.getInstance().apply {
                    timeInMillis = nowMillis
                    add(Calendar.DAY_OF_YEAR, dayOffset)
                    set(Calendar.HOUR_OF_DAY, hour)
                    set(Calendar.MINUTE, minute)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }

                if (candidateCal.timeInMillis > nowMillis) {
                    val isoDayOfWeek = when (candidateCal.get(Calendar.DAY_OF_WEEK)) {
                        Calendar.SUNDAY -> 7
                        Calendar.MONDAY -> 1
                        Calendar.TUESDAY -> 2
                        Calendar.WEDNESDAY -> 3
                        Calendar.THURSDAY -> 4
                        Calendar.FRIDAY -> 5
                        Calendar.SATURDAY -> 6
                        else -> 1
                    }
                    if (daysOfWeek.contains(isoDayOfWeek)) {
                        return candidateCal.timeInMillis
                    }
                }
            }

            // 降级保护：若未匹配则默认明天相同时间
            return Calendar.getInstance().apply {
                timeInMillis = nowMillis
                add(Calendar.DAY_OF_YEAR, 1)
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
        }

        /**
         * 注册或更新单个提醒的系统闹钟
         */
        fun scheduleReminder(
            context: Context,
            reminder: ScheduledReminder,
            scheduleState: ReminderScheduleState = SharedPrefsReminderScheduleState(context)
        ) {
            if (!reminder.enabled) {
                cancelReminder(context, reminder.id, scheduleState)
                return
            }

            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val nextTriggerMillis = calculateNextTriggerMillis(
                hour = reminder.hour,
                minute = reminder.minute,
                daysOfWeek = reminder.daysOfWeek
            )

            val pendingIntent = createPendingIntent(context, reminder.id)

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    if (alarmManager.canScheduleExactAlarms()) {
                        AlarmManagerCompat.setExactAndAllowWhileIdle(
                            alarmManager,
                            AlarmManager.RTC_WAKEUP,
                            nextTriggerMillis,
                            pendingIntent
                        )
                    } else {
                        alarmManager.setAndAllowWhileIdle(
                            AlarmManager.RTC_WAKEUP,
                            nextTriggerMillis,
                            pendingIntent
                        )
                    }
                } else {
                    AlarmManagerCompat.setExactAndAllowWhileIdle(
                        alarmManager,
                        AlarmManager.RTC_WAKEUP,
                        nextTriggerMillis,
                        pendingIntent
                    )
                }
                Log.d(TAG, "Scheduled reminder ${reminder.id} at $nextTriggerMillis")
            } catch (e: SecurityException) {
                Log.w(TAG, "Cannot schedule exact alarm, falling back to standard alarm", e)
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    nextTriggerMillis,
                    pendingIntent
                )
            }
            scheduleState.record(reminder.id, nextTriggerMillis)
        }

        /**
         * 取消指定提醒的系统闹钟
         */
        fun cancelReminder(
            context: Context,
            reminderId: String,
            scheduleState: ReminderScheduleState = SharedPrefsReminderScheduleState(context)
        ) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val pendingIntent = createPendingIntent(context, reminderId)
            alarmManager.cancel(pendingIntent)
            pendingIntent.cancel()
            scheduleState.remove(reminderId)
            Log.d(TAG, "Cancelled reminder $reminderId")
        }

        /**
         * 重新调度所有启用的提醒
         */
        fun rescheduleAll(
            context: Context,
            reminders: List<ScheduledReminder>,
            scheduleState: ReminderScheduleState = SharedPrefsReminderScheduleState(context)
        ) {
            reminders.forEach { reminder ->
                if (reminder.enabled) {
                    scheduleReminder(context, reminder, scheduleState)
                } else {
                    cancelReminder(context, reminder.id, scheduleState)
                }
            }
        }

        private fun createPendingIntent(context: Context, reminderId: String): PendingIntent {
            val intent = Intent(context, ScheduledReminderReceiver::class.java).apply {
                action = ScheduledReminderReceiver.ACTION_TRIGGER_REMINDER
                putExtra(ScheduledReminderReceiver.EXTRA_REMINDER_ID, reminderId)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            return PendingIntent.getBroadcast(
                context,
                reminderId.hashCode(),
                intent,
                flags
            )
        }
    }
}

/**
 * 每个提醒上次给系统闹钟排的触发时刻。同步接口：开机接收器在 onReceive 里直接读。
 */
interface ReminderScheduleState {
    fun get(reminderId: String): Long?
    fun record(reminderId: String, triggerAtMillis: Long)
    fun remove(reminderId: String)
}

/** 旧存储：`trackr_reminder_schedule_state` 里的 `next_trigger_<提醒 id>`（8e0c41e），写法和原来一样 */
class SharedPrefsReminderScheduleState(context: Context) : ReminderScheduleState {

    private val prefs = context.getSharedPreferences(SCHEDULE_STATE_PREFS, Context.MODE_PRIVATE)

    override fun get(reminderId: String): Long? {
        val key = KEY_PREFIX_NEXT_TRIGGER + reminderId
        return if (prefs.contains(key)) prefs.getLong(key, 0L) else null
    }

    override fun record(reminderId: String, triggerAtMillis: Long) {
        prefs.edit().putLong(KEY_PREFIX_NEXT_TRIGGER + reminderId, triggerAtMillis).apply()
    }

    override fun remove(reminderId: String) {
        prefs.edit().remove(KEY_PREFIX_NEXT_TRIGGER + reminderId).apply()
    }

    companion object {
        /** 记录每个提醒最近一次计划触发时间的独立 SharedPreferences（不改动提醒数据模型） */
        const val SCHEDULE_STATE_PREFS = "trackr_reminder_schedule_state"
        const val KEY_PREFIX_NEXT_TRIGGER = "next_trigger_"
    }
}

/**
 * 新存储：内存里一份（同步读），写入按调用顺序排队落到 [ReminderScheduleStateStore]（Room `reminder.lastScheduledTriggerAt`）。
 * 用 [create] 建，建好时已从库里读出全部记录。进程在写盘前被杀，丢的只是这一条记录，效果等同「没记过」，开机时照常重排。
 */
class CachedReminderScheduleState private constructor(
    private val store: ReminderScheduleStateStore,
    private val writer: SerialWriter,
    initial: Map<String, Long>
) : ReminderScheduleState {

    private val cache = ConcurrentHashMap(initial)

    override fun get(reminderId: String): Long? = cache[reminderId]

    override fun record(reminderId: String, triggerAtMillis: Long) {
        cache[reminderId] = triggerAtMillis
        writer.enqueue { store.set(reminderId, triggerAtMillis) }
    }

    override fun remove(reminderId: String) {
        cache.remove(reminderId)
        writer.enqueue { store.remove(reminderId) }
    }

    /** 等排队的写入做完（测试用） */
    suspend fun flush() = writer.flush()

    companion object {
        suspend fun create(store: ReminderScheduleStateStore, writer: SerialWriter): CachedReminderScheduleState =
            CachedReminderScheduleState(store, writer, store.loadAll())
    }
}

