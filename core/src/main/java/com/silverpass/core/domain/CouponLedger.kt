package com.silverpass.core.domain

import com.silverpass.core.model.CouponUse
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 모의 할인권 사용 기록을 보관하는 곳. 1차는 기기 로컬, 2차는 서버로 바꾼다. */
interface CouponRepository {
    fun all(): List<CouponUse>

    /** 저장에 실패하면 false. 호출한 쪽은 같은 requestId로 다시 확인한다(T02 실패 팝업). */
    fun add(use: CouponUse): Boolean
}

sealed interface RedeemResult {
    data class Done(val use: CouponUse, val remaining: Int) : RedeemResult

    /** 같은 requestId로 이미 처리됨. 다시 차감하지 않고 처음 결과를 돌려준다(T03-01). */
    data class AlreadyDone(val use: CouponUse, val remaining: Int) : RedeemResult

    data object LimitReached : RedeemResult

    data object SaveFailed : RedeemResult
}

/**
 * 회당 3,000원, 월 2회. 한도는 달마다 새로 정하고 이월하지 않는다(T03-03).
 * 운전 횟수·주행거리로 한도를 늘리지 않는다.
 */
class CouponLedger(
    private val repo: CouponRepository,
    private val zone: ZoneId = ZoneId.of("Asia/Seoul"),
) {
    companion object {
        const val AMOUNT_WON = 3_000
        const val MONTHLY_LIMIT = 2
        private val MONTH = DateTimeFormatter.ofPattern("yyyy-MM")
    }

    fun monthOf(millis: Long): String = MONTH.format(Instant.ofEpochMilli(millis).atZone(zone))

    fun uses(driverId: String, month: String) = repo.all().filter { it.driverId == driverId && it.month == month }

    fun remaining(driverId: String, nowMillis: Long) =
        (MONTHLY_LIMIT - uses(driverId, monthOf(nowMillis)).size).coerceAtLeast(0)

    fun find(requestId: String) = repo.all().firstOrNull { it.requestId == requestId }

    fun redeem(driverId: String, requestId: String, nowMillis: Long): RedeemResult {
        find(requestId)?.let { return RedeemResult.AlreadyDone(it, remaining(it.driverId, it.usedAtMillis)) }
        if (remaining(driverId, nowMillis) == 0) return RedeemResult.LimitReached
        val use = CouponUse(requestId, driverId, monthOf(nowMillis), nowMillis, AMOUNT_WON)
        if (!repo.add(use)) return RedeemResult.SaveFailed
        return RedeemResult.Done(use, remaining(driverId, nowMillis))
    }
}
