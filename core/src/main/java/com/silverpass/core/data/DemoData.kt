package com.silverpass.core.data

import com.silverpass.core.model.Driver
import com.silverpass.core.model.QuietHours
import com.silverpass.core.model.RoadSegment
import com.silverpass.core.model.RoadType
import com.silverpass.core.model.Route
import com.silverpass.core.model.TimeOfDay

/**
 * 1차 접수용 시연 계정과 테스트 경로. 가상 인물이다.
 * 김실버 = 시나리오 A(운전을 쉬는 시간), 박이동 = 시나리오 B(고속도로 피하기).
 */
object DemoData {
    val drivers = listOf(
        Driver("kim", "김실버", QuietHours(TimeOfDay.of(18), TimeOfDay.of(6)), avoidHighway = false),
        Driver("park", "박이동", quietHours = null, avoidHighway = true),
    )

    val highwayRoute = Route(
        "test-highway", "병원 가는 길 · 고속도로 경유",
        listOf(RoadSegment("동부간선도로", RoadType.GENERAL), RoadSegment("경부고속도로", RoadType.HIGHWAY)),
        isTestData = true,
    )

    val localRoute = Route(
        "test-local", "병원 가는 길 · 일반도로",
        listOf(RoadSegment("동부간선도로", RoadType.GENERAL), RoadSegment("국도 3호선", RoadType.GENERAL)),
        isTestData = true,
    )

    /** null = 경로 정보를 받지 못한 상황(E02 팝업 시연) */
    val routes: List<Route?> = listOf(highwayRoute, localRoute, null)
}
