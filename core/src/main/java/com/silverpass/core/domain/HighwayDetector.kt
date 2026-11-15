package com.silverpass.core.domain

import com.silverpass.core.model.RoadSegment
import com.silverpass.core.model.RoadType
import com.silverpass.core.model.Route

/** 내비 회전 안내 한 줄. [type]은 NaviHelper TurnType 이름(TURN_ENTER_IC_R 등). */
data class NaviTurn(val type: String, val description: String)

/** 내비에서 받은 경로 묶음. 도로 종류 필드가 없어 회전 안내·정체 구간 도로명으로 판단한다. */
data class NaviRouteInfo(
    val destinationName: String,
    val turns: List<NaviTurn>,
    val congestionRoadNames: List<String>,
    val tollFare: Int,
    val distanceMeters: Int,
    val durationSeconds: Int,
)

/**
 * 고속국도 포함 여부 판단. 팀 결정(2026-11-11): 고속국도만 "고속도로"로 보고 도시고속도로는 제외한다.
 * 근거로 쓰는 것(에뮬레이터 실측 경로 기준):
 *  - 회전 안내·정체 구간의 도로명 "○○고속도로"(단 "도시고속도로"는 제외)
 *  - 휴게소·졸음쉼터(TURN_ENTER_SERVICEAREA, TURN_RESTAREA): 고속국도에만 있다
 *  - IC 진입 뒤 톨게이트: 폐쇄식 고속국도 요금소. IC나 톨게이트 하나만으로는 판단하지 않는다(유료 터널·도시고속도로 IC가 있다).
 */
object HighwayDetector {
    private val NAME = Regex("""([가-힣0-9]+고속도로)""")

    data class Result(val names: List<String>, val evidence: List<String>) {
        val hasHighway get() = names.isNotEmpty() || evidence.isNotEmpty()
    }

    fun detect(info: NaviRouteInfo): Result {
        val names = linkedSetOf<String>()
        (info.turns.map { it.description } + info.congestionRoadNames).forEach { text ->
            NAME.findAll(text).map { it.value }.filterNot { "도시고속" in it }.forEach { names += it }
        }
        val evidence = mutableListOf<String>()
        val rest = info.turns.count { it.type == "TURN_ENTER_SERVICEAREA" || it.type == "TURN_RESTAREA" }
        if (rest > 0) evidence += "휴게소·졸음쉼터 ${rest}곳 경유"
        val firstIc = info.turns.indexOfFirst { it.type.startsWith("TURN_ENTER_IC") }
        if (firstIc >= 0 && info.turns.drop(firstIc + 1).any { it.type == "TURN_TOLL" }) {
            val ic = info.turns[firstIc].description.substringBefore(" - ").trim()
            evidence += "$ic 진입 후 요금소 통과"
        }
        return Result(names.toList(), evidence)
    }

    /** 조건 판정에 쓰는 Route로 바꾼다. 이름 없이 근거만 있으면 "고속도로 구간"으로 표시한다. */
    fun toRoute(info: NaviRouteInfo): Route {
        val r = detect(info)
        val segments = when {
            r.names.isNotEmpty() -> r.names.map { RoadSegment(it, RoadType.HIGHWAY) }
            r.evidence.isNotEmpty() -> listOf(RoadSegment("고속도로 구간", RoadType.HIGHWAY))
            else -> listOf(RoadSegment("일반도로", RoadType.GENERAL))
        }
        return Route(
            id = "navi",
            label = "내비 경로 · ${info.destinationName.ifBlank { "목적지" }}",
            segments = segments,
            isTestData = false,
            evidence = r.evidence,
            durationSeconds = info.durationSeconds,
        )
    }
}
