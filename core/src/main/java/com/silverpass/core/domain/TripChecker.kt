package com.silverpass.core.domain

import com.silverpass.core.model.QuietHours
import com.silverpass.core.model.TimeOfDay
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * 주행 계획(M07) 판정: 출발~도착 사이에 운전을 쉬는 시간이 끼는지.
 * 출발 시각만 보는 V01과 달리, 운행 중에 쉬는 시간에 들어가는 경우도 찾는다(V04-01의 사전 확인).
 */
object TripChecker {
    private val KST: ZoneId = ZoneId.of("Asia/Seoul")

    data class QuietConflict(val at: TimeOfDay, val atDeparture: Boolean)

    /** 겹치면 처음 겹치는 시각을 돌려준다. 15분 단위로 확인한다(조건이 15분 단위라 충분하다). */
    fun quietConflict(quiet: QuietHours?, departMillis: Long, durationSeconds: Long): QuietConflict? {
        quiet ?: return null
        val start = Instant.ofEpochMilli(departMillis).atZone(KST)
        val end = start.plusSeconds(durationSeconds.coerceAtLeast(0))
        var t: ZonedDateTime = start
        while (!t.isAfter(end)) {
            val tod = TimeOfDay.of(t.hour, t.minute)
            if (tod in quiet) return QuietConflict(tod, t == start)
            // 다음 15분 경계로
            val next = t.plusMinutes((15 - t.minute % 15).toLong()).withSecond(0).withNano(0)
            t = if (next.isAfter(t)) next else t.plusMinutes(15)
        }
        val endTod = TimeOfDay.of(end.hour, end.minute)
        return if (endTod in quiet) QuietConflict(endTod, false) else null
    }

    fun timeOf(millis: Long): TimeOfDay = Instant.ofEpochMilli(millis).atZone(KST).let { TimeOfDay.of(it.hour, it.minute) }
}
