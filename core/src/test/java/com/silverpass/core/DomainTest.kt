package com.silverpass.core

import com.silverpass.core.data.DemoData
import com.silverpass.core.domain.ConditionChecker
import com.silverpass.core.domain.ConditionKind
import com.silverpass.core.domain.ConditionStatus
import com.silverpass.core.domain.CouponLedger
import com.silverpass.core.domain.CouponRepository
import com.silverpass.core.domain.RedeemResult
import com.silverpass.core.model.CouponUse
import com.silverpass.core.model.QuietHours
import com.silverpass.core.model.TimeOfDay
import com.silverpass.core.model.withCopula
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class QuietHoursTest {
    private val night = QuietHours(TimeOfDay.of(18), TimeOfDay.of(6))

    @Test fun `자정을 넘는 구간 - 시작 포함 끝 제외`() {
        assertFalse(TimeOfDay.of(17, 59) in night)
        assertTrue(TimeOfDay.of(18, 0) in night)
        assertTrue(TimeOfDay.of(23, 59) in night)
        assertTrue(TimeOfDay.of(0, 0) in night)
        assertTrue(TimeOfDay.of(5, 59) in night)
        assertFalse(TimeOfDay.of(6, 0) in night)
        assertFalse(TimeOfDay.of(10, 0) in night)
    }

    @Test fun `같은 날 구간`() {
        val lunch = QuietHours(TimeOfDay.of(12), TimeOfDay.of(14))
        assertFalse(TimeOfDay.of(11, 59) in lunch)
        assertTrue(TimeOfDay.of(12, 0) in lunch)
        assertFalse(TimeOfDay.of(14, 0) in lunch)
    }

    @Test fun `표기`() {
        assertEquals("오후 6시 ~ 다음 날 오전 6시", night.label())
        assertEquals("오후 6시 30분", TimeOfDay.of(18, 30).label())
        assertEquals("오전 12시", TimeOfDay.of(0).label())
        assertEquals("오후 12시", TimeOfDay.of(12).label())
        assertEquals("지금은 오후 6시예요", withCopula("지금은 ${TimeOfDay.of(18).label()}"))
        assertEquals("지금은 오후 6시 30분이에요", withCopula("지금은 ${TimeOfDay.of(18, 30).label()}"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `시작과 끝이 같으면 거절`() {
        QuietHours(TimeOfDay.of(6), TimeOfDay.of(6))
    }
}

class ConditionCheckerTest {
    private val kim = DemoData.drivers[0]
    private val park = DemoData.drivers[1]

    @Test fun `시나리오 A - 오후 6시 30분이면 경고`() {
        val r = ConditionChecker.check(kim, TimeOfDay.of(18, 30), DemoData.highwayRoute)
        assertTrue(r.shouldWarn)
        assertTrue(r.of(ConditionKind.QUIET_HOURS) is ConditionStatus.Applies)
        // 김실버는 고속도로 조건을 정하지 않았으므로 경로에 고속도로가 있어도 해당 없음
        assertEquals(ConditionStatus.NotSet, r.of(ConditionKind.HIGHWAY))
    }

    @Test fun `시나리오 A - 출발을 다음 날 오전 6시로 미루면 문제없음`() {
        assertFalse(ConditionChecker.check(kim, TimeOfDay.of(6), null).shouldWarn)
    }

    @Test fun `시나리오 B - 고속도로 포함 경로면 경고, 일반도로면 문제없음`() {
        val bad = ConditionChecker.check(park, TimeOfDay.of(10), DemoData.highwayRoute)
        assertTrue(bad.shouldWarn)
        assertEquals("경부고속도로 포함됨", (bad.of(ConditionKind.HIGHWAY) as ConditionStatus.Applies).detail)
        val good = ConditionChecker.check(park, TimeOfDay.of(10), DemoData.localRoute)
        assertFalse(good.shouldWarn)
        assertEquals(ConditionStatus.Ok, good.of(ConditionKind.HIGHWAY))
    }

    @Test fun `경로 정보가 없으면 문제없음이 아니라 확인 불가`() {
        val r = ConditionChecker.check(park, TimeOfDay.of(10), null)
        assertFalse(r.shouldWarn)
        assertTrue(r.of(ConditionKind.HIGHWAY) is ConditionStatus.Unknown)
        assertEquals(1, r.unknown.size)
    }
}

class CouponLedgerTest {
    private class MemRepo : CouponRepository {
        val list = mutableListOf<CouponUse>()
        var fail = false
        override fun all() = list.toList()
        override fun add(use: CouponUse) = if (fail) false else list.add(use)
    }

    private val zone = ZoneId.of("Asia/Seoul")
    private fun at(y: Int, m: Int, d: Int, h: Int = 18) = ZonedDateTime.of(y, m, d, h, 30, 0, 0, zone).toInstant().toEpochMilli()

    @Test fun `월 2회 한도와 소진`() {
        val ledger = CouponLedger(MemRepo())
        val now = at(2026, 11, 10)
        assertEquals(2, ledger.remaining("kim", now))
        assertEquals(1, (ledger.redeem("kim", "r1", now) as RedeemResult.Done).remaining)
        assertEquals(0, (ledger.redeem("kim", "r2", now) as RedeemResult.Done).remaining)
        assertEquals(RedeemResult.LimitReached, ledger.redeem("kim", "r3", now))
    }

    @Test fun `같은 사용 건을 두 번 눌러도 한 번만 차감`() {
        val repo = MemRepo()
        val ledger = CouponLedger(repo)
        val now = at(2026, 11, 10)
        ledger.redeem("kim", "r1", now)
        assertTrue(ledger.redeem("kim", "r1", now) is RedeemResult.AlreadyDone)
        assertEquals(1, repo.list.size)
        assertEquals(1, ledger.remaining("kim", now))
    }

    @Test fun `저장 실패면 차감하지 않고 재시도 가능`() {
        val repo = MemRepo().apply { fail = true }
        val ledger = CouponLedger(repo)
        val now = at(2026, 11, 10)
        assertEquals(RedeemResult.SaveFailed, ledger.redeem("kim", "r1", now))
        assertEquals(2, ledger.remaining("kim", now))
        repo.fail = false
        assertTrue(ledger.redeem("kim", "r1", now) is RedeemResult.Done)
    }

    @Test fun `다음 달에는 이월 없이 기본 한도`() {
        val ledger = CouponLedger(MemRepo())
        ledger.redeem("kim", "r1", at(2026, 11, 30, 23))
        assertEquals(1, ledger.remaining("kim", at(2026, 11, 30, 23)))
        assertEquals(2, ledger.remaining("kim", at(2026, 12, 1, 0)))
    }

    @Test fun `계정별로 따로 센다`() {
        val ledger = CouponLedger(MemRepo())
        val now = at(2026, 11, 10)
        ledger.redeem("kim", "r1", now)
        assertEquals(2, ledger.remaining("park", now))
    }
}
