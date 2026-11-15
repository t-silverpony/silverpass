package com.silverpass.core.data

import com.silverpass.core.model.TimeOfDay
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * 시연용 시계. 고정 시각을 정하면 오늘 날짜의 그 시각으로 동작한다(오후 6시 30분 시나리오를 언제든 시연하기 위해).
 * null이면 실제 시각을 쓴다.
 */
class DemoClock(private val zone: ZoneId = ZoneId.of("Asia/Seoul")) {
    @Volatile
    var fixed: TimeOfDay? = null

    val isFixed get() = fixed != null

    fun now(): ZonedDateTime {
        val real = ZonedDateTime.now(zone)
        val f = fixed ?: return real
        return LocalDate.now(zone).atTime(f.hour, f.minute).atZone(zone)
    }

    fun timeOfDay(): TimeOfDay = now().let { TimeOfDay.of(it.hour, it.minute) }

    fun millis(): Long = now().toInstant().toEpochMilli()
}
