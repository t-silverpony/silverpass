package com.silverpass.core

import com.silverpass.core.domain.TripChecker
import com.silverpass.core.model.QuietHours
import com.silverpass.core.model.TimeOfDay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class TripCheckerTest {
    private val night = QuietHours(TimeOfDay.of(18), TimeOfDay.of(6))
    private fun kst(h: Int, m: Int = 0) = ZonedDateTime.of(2026, 11, 12, h, m, 0, 0, ZoneId.of("Asia/Seoul")).toInstant().toEpochMilli()

    @Test fun `낮에 출발해 낮에 도착하면 겹치지 않음`() {
        assertNull(TripChecker.quietConflict(night, kst(10), 4 * 3600))
    }

    @Test fun `낮에 출발했지만 운행 중 쉬는 시간에 들어감`() {
        val c = TripChecker.quietConflict(night, kst(14), 4 * 3600 + 18 * 60)!! // 18:18 도착
        assertEquals(TimeOfDay.of(18), c.at)
        assertTrue(!c.atDeparture)
    }

    @Test fun `출발부터 쉬는 시간`() {
        val c = TripChecker.quietConflict(night, kst(18, 30), 600)!!
        assertTrue(c.atDeparture)
    }

    @Test fun `끝 경계 - 오전 6시에 출발하면 괜찮음`() {
        assertNull(TripChecker.quietConflict(night, kst(6), 3600))
    }

    @Test fun `조건이 없으면 겹침 없음`() {
        assertNull(TripChecker.quietConflict(null, kst(23), 3600))
    }

    @Test fun `분 단위 출발도 다음 15분 경계에서 확인`() {
        val c = TripChecker.quietConflict(night, kst(17, 50), 15 * 60)!! // 18:05 도착
        assertEquals(TimeOfDay.of(18), c.at)
    }
}
