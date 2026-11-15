package com.silverpass.core.domain

import com.silverpass.core.model.Driver
import com.silverpass.core.model.Route
import com.silverpass.core.model.TimeOfDay
import com.silverpass.core.model.withCopula
import java.time.LocalDate
import java.time.ZoneId

enum class ConditionKind { QUIET_HOURS, HIGHWAY }

sealed interface ConditionStatus {
    /** 사용자가 이 조건을 정하지 않았다. */
    data object NotSet : ConditionStatus

    data object Ok : ConditionStatus

    /** 자제 조건에 해당한다. [detail]은 화면에 보여 줄 현재 상황(예: "경부고속도로 포함됨"). */
    data class Applies(val detail: String) : ConditionStatus

    /** 판단에 필요한 정보가 없다. 정보 누락을 "문제없음"으로 처리하지 않는다(E02-02, V01-03). */
    data class Unknown(val reason: String) : ConditionStatus
}

data class ConditionResult(val kind: ConditionKind, val status: ConditionStatus)

data class CheckReport(
    val driver: Driver,
    val at: TimeOfDay,
    val route: Route?,
    val results: List<ConditionResult>,
) {
    val applied get() = results.filter { it.status is ConditionStatus.Applies }
    val unknown get() = results.filter { it.status is ConditionStatus.Unknown }

    /** V01-04: 하나라도 해당하면 경고 화면으로 간다. */
    val shouldWarn get() = applied.isNotEmpty()

    fun of(kind: ConditionKind) = results.first { it.kind == kind }.status
}

/** 출발 전 조건 확인(V01). 순수 함수라 시각·경로를 바꿔 다시 부르면 된다(V03-01 출발 시간 변경). */
object ConditionChecker {
    private val KST: ZoneId = ZoneId.of("Asia/Seoul")

    /** [route]가 null이면 [routeProblem]을 확인 불가 사유로 보여 준다(목적지 없음, 내비 응답 없음 등). */
    fun check(
        driver: Driver,
        at: TimeOfDay,
        route: Route?,
        routeProblem: String = "현재 경로 정보를 받지 못했어요",
    ): CheckReport {
        val departMillis = LocalDate.now(KST).atTime(at.hour, at.minute).atZone(KST).toInstant().toEpochMilli()
        val durationS = route?.durationSeconds?.toLong() ?: 0L

        val quiet = when (val q = driver.quietHours) {
            null -> ConditionStatus.NotSet
            else -> {
                val conflict = TripChecker.quietConflict(q, departMillis, durationS)
                when {
                    conflict == null -> ConditionStatus.Ok
                    conflict.atDeparture -> ConditionStatus.Applies(withCopula("지금은 ${at.label()}"))
                    else -> ConditionStatus.Applies("운전 중 ${conflict.at.label()}부터 운전을 쉬는 시간이에요")
                }
            }
        }
        val highway = when {
            !driver.avoidHighway -> ConditionStatus.NotSet
            route == null -> ConditionStatus.Unknown(routeProblem)
            route.highways.isNotEmpty() ->
                ConditionStatus.Applies(route.highways.take(3).joinToString(", ") { it.name } + " 포함됨")
            else -> ConditionStatus.Ok
        }
        return CheckReport(
            driver, at, route,
            listOf(ConditionResult(ConditionKind.QUIET_HOURS, quiet), ConditionResult(ConditionKind.HIGHWAY, highway)),
        )
    }
}
