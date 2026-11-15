package com.silverpass.core

import com.silverpass.core.data.DemoData
import com.silverpass.core.domain.ConditionChecker
import com.silverpass.core.domain.ConditionKind
import com.silverpass.core.domain.ConditionStatus
import com.silverpass.core.domain.HighwayDetector
import com.silverpass.core.domain.NaviRouteInfo
import com.silverpass.core.domain.NaviTurn
import com.silverpass.core.model.TimeOfDay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HighwayDetectorTest {
    private fun info(turns: List<Pair<String, String>>, roads: List<String> = emptyList()) =
        NaviRouteInfo("목적지", turns.map { NaviTurn(it.first, it.second) }, roads, 0, 0, 0)

    /** 에뮬레이터 실측: 성남 수정구 → 부산역 경로(spike-navihelper/route-busan-log.txt)에서 발췌 */
    private val busan = info(
        listOf(
            "TURN_LEFT" to "기업지원허브 방면",
            "TURN_RIGHT" to "판교IC 방면",
            "TURN_ENTER_IC_R" to "판교IC - 부산 방면",
            "TURN_ENTER_SERVICEAREA" to "하이패스센터",
            "TURN_TOLL" to "서울톨게이트",
            "TURN_RESTAREA" to "용인졸음쉼터",
            "TURN_DEVIDE_MERGE" to "영동고속도로",
            "TURN_ENTER_SERVICEAREA" to "용인휴게소",
            "TURN_TOLL" to "수정터널톨게이트",
            "TURN_EXIT_IC_C" to "좌천삼거리 - 부산세관,부산항국제여객터미널 방면",
            "TURN_ARRIVE" to "도착",
        ),
        roads = listOf("대왕판교로", "경부고속도로", "중앙고속도로", "관문대로"),
    )

    @Test fun `실측 부산 경로 - 고속도로 이름과 근거`() {
        val r = HighwayDetector.detect(busan)
        assertTrue(r.hasHighway)
        assertEquals(listOf("영동고속도로", "경부고속도로", "중앙고속도로"), r.names)
        assertTrue(r.evidence.any { "휴게소" in it })
        assertTrue(r.evidence.any { it == "판교IC 진입 후 요금소 통과" })
    }

    @Test fun `시내 경로 - 유료 터널 요금소만으로는 고속도로가 아님`() {
        val city = info(
            listOf(
                "TURN_RIGHT" to "부산세관 방면",
                "TURN_ENTER_OVER_L" to "수정터널 방면",
                "TURN_TOLL" to "수정터널톨게이트",
                "TURN_LEFT" to "중앙대로248번길",
                "TURN_ARRIVE" to "도착",
            ),
            roads = listOf("관문대로"),
        )
        assertFalse(HighwayDetector.detect(city).hasHighway)
    }

    @Test fun `도시고속도로는 제외(팀 결정)`() {
        val r = HighwayDetector.detect(info(listOf("TURN_KEEP_R" to "부산도시고속도로"), roads = listOf("올림픽대로")))
        assertFalse(r.hasHighway)
    }

    @Test fun `이름이 없어도 IC 진입 후 요금소면 고속도로`() {
        val r = HighwayDetector.detect(info(listOf("TURN_ENTER_IC_R" to "판교IC - 서울 방면", "TURN_TOLL" to "서울톨게이트")))
        assertTrue(r.hasHighway)
        assertEquals(listOf("고속도로 구간"), HighwayDetector.toRoute(info(listOf("TURN_ENTER_IC_R" to "판교IC - 서울 방면", "TURN_TOLL" to "서울톨게이트"))).highways.map { it.name })
    }

    @Test fun `내비 경로로 박이동 조건 판정`() {
        val park = DemoData.drivers[1]
        val warn = ConditionChecker.check(park, TimeOfDay.of(10), HighwayDetector.toRoute(busan))
        assertEquals("영동고속도로, 경부고속도로, 중앙고속도로 포함됨", (warn.of(ConditionKind.HIGHWAY) as ConditionStatus.Applies).detail)
        val none = ConditionChecker.check(park, TimeOfDay.of(10), null, routeProblem = "내비게이션에 목적지가 없어요")
        assertEquals(ConditionStatus.Unknown("내비게이션에 목적지가 없어요"), none.of(ConditionKind.HIGHWAY))
    }
}
