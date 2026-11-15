package com.silverpass.vehicle.car

import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.NavigationStatus
import ai.pleos.playground.navi.constants.RouteOption
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.data.RouteStateInfo
import ai.pleos.playground.navi.data.TBTInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.content.Context
import com.silverpass.core.domain.NaviRouteInfo
import com.silverpass.core.domain.NaviTurn
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/** 내비에서 받은 경로 상태. */
sealed interface NaviState {
    /** 내비 서비스에 연결하지 못함(에뮬레이터 밖 등). */
    data class Unavailable(val reason: String) : NaviState

    data object NoDestination : NaviState

    /** 목적지는 있는데 경로를 못 받음(응답 없음·빈 경로·오류). 문제없음으로 처리하지 않는다. */
    data class Failed(val reason: String) : NaviState

    data class Ready(val info: NaviRouteInfo) : NaviState
}

/**
 * Pleos NaviHelper 래퍼. 콜백이 따로따로 오므로 요청마다 기다렸다가 한 묶음(NaviRouteInfo)으로 만든다.
 * 목적지 변경·경로 탐색·재탐색 이벤트가 오면 [onRouteEvent]로 알려 다시 확인하게 한다(V03-03).
 */
class NaviGateway(
    context: Context,
    private val log: (String, Boolean) -> Unit,
    private val onRouteEvent: (String) -> Unit,
) {
    private val navi = NaviHelper(context.applicationContext)
    @Volatile private var ready = false
    @Volatile private var dest: CompletableDeferred<DestinationInfo>? = null
    @Volatile private var tbt: CompletableDeferred<List<TBTInfo>>? = null
    @Volatile private var state: CompletableDeferred<RouteStateInfo>? = null
    @Volatile private var reroute: CompletableDeferred<String>? = null
    @Volatile private var lastStart: RouteStartInfo? = null

    private val listener = object : NaviHelperEventListener {
        override fun onDestinationInfo(destinationInfo: DestinationInfo) { dest?.complete(destinationInfo) }
        override fun onTBTInfo(list: List<TBTInfo>) { tbt?.complete(list) }
        override fun onRouteStateInfo(routeStateInfo: RouteStateInfo) { state?.complete(routeStateInfo) }
        override fun onRouteStarted(routeStartInfo: RouteStartInfo) {
            lastStart = routeStartInfo
            log("onRouteStarted ${routeStartInfo.distance / 1000}km 통행료 ${routeStartInfo.tollFare}원", true)
            reroute?.complete("onRouteStarted")
            onRouteEvent("경로 탐색 완료")
        }
        override fun onReRouteEnded() {
            log("onReRouteEnded", true)
            reroute?.complete("onReRouteEnded")
            onRouteEvent("재탐색 완료")
        }
        override fun onRouteOptionChanged(routeOption: RouteOption) {
            log("onRouteOptionChanged $routeOption", true)
            reroute?.complete("onRouteOptionChanged")
        }
        override fun onDestinationChanged() { log("onDestinationChanged", true); onRouteEvent("목적지 변경") }
        override fun onRouteCancelled() { log("onRouteCancelled", true); onRouteEvent("경로 취소") }
        override fun onRouteEnded() { log("onRouteEnded", true) }
        override fun onNavigationStatus(navigationStatus: NavigationStatus) { log("onNavigationStatus $navigationStatus", true) }
        override fun onError(errorCode: NaviErrorCode) {
            log("내비 오류 $errorCode", false)
            reroute?.complete("onError")
            onRouteEvent("내비 오류")
        }
    }

    fun start(onReady: () -> Unit) {
        try {
            navi.initialize({
                ready = true
                navi.addListener(listener)
                log("NaviHelper.initialize()", true)
                onReady()
            }, { e -> log("NaviHelper.initialize 실패: ${e.message}", false) })
        } catch (e: Exception) {
            log("NaviHelper 사용 불가: ${e.message}", false)
        }
    }

    fun release() {
        runCatching { navi.removeListener(listener) }
        runCatching { navi.release() }
    }

    /** 현재 목적지·회전 안내·정체 정보를 모아 온다. 5초 안에 오지 않으면 Failed. */
    suspend fun fetch(): NaviState {
        if (!ready) return NaviState.Unavailable("내비게이션에 연결하지 못했어요")
        val d = CompletableDeferred<DestinationInfo>().also { dest = it }
        val t = CompletableDeferred<List<TBTInfo>>().also { tbt = it }
        val s = CompletableDeferred<RouteStateInfo>().also { state = it }
        try {
            navi.getDestinationInfo()
            navi.getRouteStateInfo()
            navi.getTBTInfo()
        } catch (e: Exception) {
            log("내비 정보 요청 실패: ${e.message}", false)
            return NaviState.Failed("내비게이션이 응답하지 않아요")
        }
        val destination = withTimeoutOrNull(5_000) { d.await() }
            ?: return NaviState.Failed("내비게이션이 응답하지 않아요").also { log("getDestinationInfo 응답 없음", false) }
        val hasDestination = destination.latitude != 0.0 || destination.longitude != 0.0 || destination.name.isNotBlank()
        if (!hasDestination) {
            log("getDestinationInfo → 목적지 없음", true)
            return NaviState.NoDestination
        }
        val turns = withTimeoutOrNull(5_000) { t.await() }
        val routeState = withTimeoutOrNull(2_000) { s.await() }
        if (turns.isNullOrEmpty()) {
            log("getTBTInfo → ${if (turns == null) "응답 없음" else "빈 경로"}", false)
            return NaviState.Failed("경로 정보를 받지 못했어요")
        }
        val roads = routeState?.trafficStatus?.congestionItemList.orEmpty().mapNotNull { it.roadName.takeIf(String::isNotBlank) }
        val info = NaviRouteInfo(
            destinationName = destination.name,
            turns = turns.map { NaviTurn(it.type.name, it.description) },
            congestionRoadNames = roads,
            tollFare = destination.tollFare.takeIf { it > 0 } ?: lastStart?.tollFare ?: 0,
            distanceMeters = destination.remainDistance,
            durationSeconds = destination.remainTime,
        )
        log("내비 경로: ${destination.name} · 회전 안내 ${turns.size}개 · 정체 구간 도로 ${roads.distinct().size}개", true)
        return NaviState.Ready(info)
    }

    /**
     * 일반도로 쪽으로 다시 찾기. NaviHelper에 고속도로 회피 옵션이 없어 "무료도로 우선(FREE)"으로 바꾸고 재탐색한다.
     * 재탐색 이벤트(최대 15초)를 기다린 뒤 결과를 다시 가져온다. 결과에 고속도로가 남을 수 있다(무료 고속도로 등).
     */
    suspend fun rerouteFreeRoads(): NaviState {
        if (!ready) return NaviState.Unavailable("내비게이션에 연결하지 못했어요")
        val done = CompletableDeferred<String>().also { reroute = it }
        try {
            navi.changeRouteOption(RouteOption.FREE)
            navi.requestReRoute()
            log("changeRouteOption(FREE) + requestReRoute()", true)
        } catch (e: Exception) {
            log("재탐색 요청 실패: ${e.message}", false)
            return NaviState.Failed("내비게이션에 재탐색을 요청하지 못했어요")
        }
        val by = withTimeoutOrNull(15_000) { done.await() }
        log("재탐색 대기 결과: ${by ?: "15초 안에 이벤트 없음"}", by != null)
        reroute = null
        return fetch()
    }

    /** 내비에 목적지를 넣는다. 시연 도구와 휴대전화 주행 계획 적용(내비에 적용)에서 쓴다. */
    fun requestRoute(lat: Double, lon: Double, name: String, option: String = "RECOMMENDED"): Boolean {
        if (!ready) return false
        return runCatching {
            val opt = runCatching { RouteOption.valueOf(option) }.getOrDefault(RouteOption.RECOMMENDED)
            navi.requestRoute(RouteInfo(lat, lon, null, null, name, "", "", "", opt, "", false))
            log("requestRoute($name, $opt)", true)
        }.onFailure { log("requestRoute 실패: ${it.message}", false) }.isSuccess
    }

    fun cancelDemoRoute() {
        if (!ready) return
        runCatching { navi.cancelRoute(); log("cancelRoute() — 시연", true) }
    }
}
