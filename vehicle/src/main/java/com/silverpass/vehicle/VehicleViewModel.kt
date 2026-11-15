package com.silverpass.vehicle

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.silverpass.core.data.DemoClock
import com.silverpass.core.data.DemoData
import com.silverpass.core.domain.CheckReport
import com.silverpass.core.domain.ConditionChecker
import com.silverpass.core.domain.CouponLedger
import com.silverpass.core.domain.HighwayDetector
import com.silverpass.core.model.Driver
import com.silverpass.core.model.Route
import com.silverpass.core.model.TimeOfDay
import com.silverpass.core.sync.ApiResult
import com.silverpass.core.sync.RemoteAccount
import com.silverpass.core.sync.SyncApi
import com.silverpass.core.sync.SyncCache
import com.silverpass.vehicle.car.CarGateway
import com.silverpass.vehicle.car.DriveState
import com.silverpass.vehicle.car.NaviGateway
import com.silverpass.vehicle.car.NaviState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalTime
import java.time.format.DateTimeFormatter

enum class Screen { SELECT_DRIVER, PAIRING, CHECK, TAXI_HANDOFF }

enum class Dialog { NONE, POSTPONE, REROUTE_GUIDE, REROUTING, REROUTE_RESULT, ROUTE_UNKNOWN, DEMO_TOOLS }

/** 경로를 어디서 받을지. 실제 내비가 기본이고, 테스트 경로는 시연·내비 없는 환경용이다. */
enum class RouteMode(val label: String) {
    NAVI("실제 내비"),
    TEST_HIGHWAY("테스트: 고속도로 경유"),
    TEST_LOCAL("테스트: 일반도로"),
    TEST_NONE("테스트: 경로 정보 없음"),
}

/** 운전자 목록을 어디서 가져왔는지. 서버에 못 붙으면 마지막 값(CACHE), 그것도 없으면 시연 계정(DEMO). */
enum class AccountSource { SERVER, CACHE, DEMO }

data class ApiLog(val time: String, val text: String, val ok: Boolean)

data class UiState(
    val accounts: List<RemoteAccount> = emptyList(),
    val source: AccountSource = AccountSource.DEMO,
    val lastSyncAt: Long? = null,
    val syncing: Boolean = true,
    val selectedId: String? = null,
    val driver: Driver? = null,
    val notice: String? = null,
    val screen: Screen = Screen.SELECT_DRIVER,
    val report: CheckReport? = null,
    /** 출발 미루기로 정한 출발 예정 시각(V03-01). null이면 지금 출발. */
    val plannedDeparture: TimeOfDay? = null,
    val routeMode: RouteMode = RouteMode.NAVI,
    val naviState: NaviState? = null,
    val rerouteMessage: String? = null,
    val pairingCode: String? = null,
    val pairingExpiresAt: Long? = null,
    val pairingError: String? = null,
    val driveState: DriveState = DriveState.UNKNOWN,
    val gearName: String? = null,
    /** 시연 도구: 기어 신호가 없을 때(에뮬레이터 밖) 정차로 가정 */
    val assumeParked: Boolean = false,
    val fixedTime: TimeOfDay? = DEMO_EVENING,
    val dialog: Dialog = Dialog.NONE,
    val logs: List<ApiLog> = emptyList(),
) {
    /** 정차가 확인될 때만 운전자 선택·상세 조작을 연다. */
    val canOperate get() = driveState == DriveState.PARKED || (driveState == DriveState.UNKNOWN && assumeParked)

    val drivers: List<Driver> get() = if (source == AccountSource.DEMO) DemoData.drivers else accounts.map { it.toDriver() }

    fun couponsLeft(id: String?) =
        accounts.firstOrNull { it.id == id }?.coupons?.remaining ?: if (source == AccountSource.DEMO) CouponLedger.MONTHLY_LIMIT else 0

    val route: Route?
        get() = when (routeMode) {
            RouteMode.TEST_HIGHWAY -> DemoData.highwayRoute
            RouteMode.TEST_LOCAL -> DemoData.localRoute
            RouteMode.TEST_NONE -> null
            RouteMode.NAVI -> (naviState as? NaviState.Ready)?.let { HighwayDetector.toRoute(it.info) }
        }

    val routeProblem: String
        get() = if (routeMode != RouteMode.NAVI) "현재 경로 정보를 받지 못했어요" else when (val n = naviState) {
            null -> "내비게이션 경로를 확인하는 중이에요"
            NaviState.NoDestination -> "내비게이션에 목적지가 설정되지 않았어요"
            is NaviState.Failed -> n.reason
            is NaviState.Unavailable -> n.reason
            is NaviState.Ready -> ""
        }

    companion object {
        val DEMO_EVENING = TimeOfDay.of(18, 30)
    }
}

class VehicleViewModel(app: Application) : AndroidViewModel(app) {
    private val api = SyncApi()
    private val cache = SyncCache(app, "silverpass-vehicle")
    private val clock = DemoClock().apply { fixed = UiState.DEMO_EVENING }
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val car = CarGateway(app, ::log)
    private val navi = NaviGateway(app, ::log) { event -> onNaviEvent(event) }
    private val naviLock = Mutex()
    private val syncLock = Mutex()
    private var pairingJob: Job? = null

    init {
        if (car.initialize()) {
            car.watchGear { s, g -> _state.update { it.copy(driveState = s, gearName = g?.name) } }
        }
        navi.start { viewModelScope.launch { refreshRoute() } }
        cache.cachedAccounts(CACHE_KEY)?.let { (list, at) -> _state.update { it.copy(accounts = list, source = AccountSource.CACHE, lastSyncAt = at) } }
        viewModelScope.launch {
            syncAccounts()
            _state.update { it.copy(selectedId = it.selectedId ?: it.drivers.firstOrNull()?.id) }
        }
        // 휴대전화에서 바꾼 조건을 반영하고, 실제 시각 모드에서는 "지금"이 멈추지 않게 주기적으로 다시 확인한다.
        viewModelScope.launch {
            while (true) {
                delay(15_000)
                syncAccounts()
                val s = _state.value
                if (s.screen == Screen.CHECK && !clock.isFixed && s.plannedDeparture == null) recheck(showUnknownPopup = false)
            }
        }
    }

    override fun onCleared() {
        car.release()
        navi.release()
    }

    // ---------- 서버 동기화 ----------

    private suspend fun vehicleToken(): String? {
        cache.vehicleToken?.let { return it }
        return when (val r = api.registerVehicle("실버패스 차량")) {
            is ApiResult.Ok -> r.value.first.also { cache.vehicleToken = it; log("차량 등록 완료", true) }
            else -> null.also { log("차량 등록 실패: ${r.describe()}", false) }
        }
    }

    /** 이 차량에 연결된 계정을 받아 온다. 실패하면 마지막 값 → 시연 계정 순으로 보여 준다. */
    suspend fun syncAccounts() = syncLock.withLock {
        val token = vehicleToken()
        val r = token?.let { api.linkedAccounts(it) }
        if (r is ApiResult.Rejected && r.status == 401) {
            // 서버에서 차량이 지워졌으면 다시 등록한다(연결은 다시 해야 한다).
            cache.vehicleToken = null
            log("차량 토큰이 무효라 다시 등록해야 해요", false)
        }
        if (r is ApiResult.Ok) {
            val now = System.currentTimeMillis()
            cache.saveAccounts(CACHE_KEY, r.value, now)
            applyAccounts(r.value, AccountSource.SERVER, now)
        } else {
            val cached = cache.cachedAccounts(CACHE_KEY)
            if (cached != null) applyAccounts(cached.first, AccountSource.CACHE, cached.second)
            else _state.update { it.copy(source = AccountSource.DEMO, syncing = false) }
        }
    }

    private fun applyAccounts(list: List<RemoteAccount>, source: AccountSource, at: Long) {
        val before = _state.value
        _state.update { it.copy(accounts = list, source = source, lastSyncAt = at, syncing = false) }
        val cur = before.driver ?: return
        if (before.source == AccountSource.DEMO) return
        val now = list.firstOrNull { it.id == cur.id }
        if (now == null && source == AccountSource.SERVER) {
            // 휴대전화에서 연결을 끊은 계정은 더 이상 보여 주지 않는다(E01-03).
            _state.update { it.copy(driver = null, screen = Screen.SELECT_DRIVER, report = null, notice = "${cur.name}님 계정의 연결이 해제됐어요") }
            return
        }
        if (now != null && now.toDriver() != cur) {
            log("휴대전화에서 바뀐 ${now.name}님 조건을 반영했어요", true)
            _state.update { it.copy(driver = now.toDriver()) }
            if (_state.value.screen == Screen.CHECK) recheck(showUnknownPopup = false)
        }
    }

    // ---------- V00 운전자 선택 · 휴대전화 연결 ----------

    fun select(id: String) = _state.update { it.copy(selectedId = id) }

    fun confirmDriver() {
        val s = _state.value
        if (!s.canOperate) return
        val d = s.drivers.firstOrNull { it.id == s.selectedId } ?: return
        log("운전자 선택: ${d.name} (${if (s.source == AccountSource.DEMO) "시연 계정" else "연결된 계정"})", true)
        _state.update { it.copy(driver = d, screen = Screen.CHECK, plannedDeparture = null, notice = null) }
        recheckNow()
    }

    fun changeDriver() {
        if (!_state.value.canOperate) return
        _state.update { it.copy(screen = Screen.SELECT_DRIVER, report = null, plannedDeparture = null) }
        viewModelScope.launch { syncAccounts() }
    }

    /** 연결번호를 받아 크게 보여 주고, 휴대전화에서 입력해 새 계정이 붙을 때까지 3초마다 확인한다. */
    fun openPairing() {
        if (!_state.value.canOperate) return
        _state.update { it.copy(screen = Screen.PAIRING, pairingCode = null, pairingError = null, notice = null) }
        newPairingCode()
    }

    fun newPairingCode() {
        pairingJob?.cancel()
        pairingJob = viewModelScope.launch {
            val token = vehicleToken()
            val r = token?.let { api.issuePairingCode(it) }
            if (r !is ApiResult.Ok) {
                _state.update { it.copy(pairingError = "서버에 연결하지 못했어요. 잠시 후 다시 시도해 주세요.") }
                return@launch
            }
            val (code, expiresAt) = r.value
            log("연결번호 발급 (5분)", true)
            _state.update { it.copy(pairingCode = code, pairingExpiresAt = expiresAt, pairingError = null) }
            val known = _state.value.accounts.map { it.id }.toSet()
            while (System.currentTimeMillis() < expiresAt) {
                delay(3_000)
                syncAccounts()
                val added = _state.value.accounts.firstOrNull { it.id !in known && _state.value.source == AccountSource.SERVER }
                if (added != null) {
                    log("${added.name}님 휴대전화 연결됨", true)
                    _state.update { it.copy(screen = Screen.SELECT_DRIVER, selectedId = added.id, pairingCode = null, notice = "${added.name}님이 연결됐어요") }
                    return@launch
                }
            }
            _state.update { it.copy(pairingCode = null, pairingError = "연결번호가 만료됐어요. 새 번호를 받아 주세요.") }
        }
    }

    fun closePairing() {
        pairingJob?.cancel()
        _state.update { it.copy(screen = Screen.SELECT_DRIVER, pairingCode = null) }
    }

    // ---------- 경로 (실제 내비) ----------

    private var followUp: Job? = null

    /**
     * 내비 이벤트 직후에는 경로가 아직 바뀌기 전일 수 있고, 앱이 뒤에 있는 동안 Android가 앱을 멈춰 이벤트를 놓칠 수도 있다.
     * 그래서 1초·4초·9초 뒤에 다시 읽어 최신 경로로 맞춘다(마지막 요청만 유지).
     */
    private fun onNaviEvent(event: String) {
        followUp?.cancel()
        followUp = viewModelScope.launch {
            for (wait in longArrayOf(1_000, 3_000, 5_000)) {
                delay(wait)
                refreshRoute()
                if (_state.value.screen == Screen.CHECK) recheck(showUnknownPopup = false)
            }
        }
    }

    private suspend fun refreshRoute() = naviLock.withLock {
        if (_state.value.routeMode != RouteMode.NAVI) return@withLock
        val n = navi.fetch()
        _state.update { it.copy(naviState = n) }
    }

    // ---------- V01/V02 조건 확인 ----------

    /** "다시 확인하기": 내비 경로를 새로 받아 판정한다. 확인할 수 없는 조건이 있으면 팝업(E02). */
    fun recheckNow() {
        viewModelScope.launch {
            refreshRoute()
            recheck(showUnknownPopup = true)
        }
    }

    fun recheck(showUnknownPopup: Boolean = true) {
        val s = _state.value
        val d = s.driver ?: return
        val at = s.plannedDeparture ?: clock.timeOfDay()
        val report = ConditionChecker.check(d, at, s.route, s.routeProblem)
        log("조건 확인: ${at.label()} · ${s.route?.label ?: s.routeProblem} → ${if (report.shouldWarn) "자제 조건 해당 ${report.applied.size}건" else "해당 없음"}", true)
        _state.update {
            it.copy(
                report = report,
                dialog = if (showUnknownPopup && report.unknown.isNotEmpty() && it.dialog == Dialog.NONE) Dialog.ROUTE_UNKNOWN else it.dialog,
            )
        }
    }

    /** V03-01: 출발 시간을 바꿔 그 시각 기준으로 다시 판정한다. 이전 결과를 재사용하지 않는다. */
    fun postpone(to: TimeOfDay) {
        _state.update { it.copy(plannedDeparture = to, dialog = Dialog.NONE) }
        recheck()
    }

    fun departNow() {
        _state.update { it.copy(plannedDeparture = null) }
        recheck()
    }

    /**
     * "일반도로로 다시 찾기". 실제 내비면 무료도로 우선으로 재탐색하고 다시 판정한다(V03-02).
     * 테스트 경로면 안내 팝업에서 경로를 바꾼 것으로 가정한다.
     */
    fun findLocalRoads() {
        if (_state.value.routeMode != RouteMode.NAVI) {
            showDialog(Dialog.REROUTE_GUIDE)
            return
        }
        _state.update { it.copy(dialog = Dialog.REROUTING) }
        viewModelScope.launch {
            // 재탐색을 요청하면 Pleos 내비가 앞으로 나오고, 뒤로 간 앱은 Android가 멈춘다(이벤트를 못 받음). 곧바로 다시 앞으로 온다.
            launch { delay(1_500); bringToFront() }
            val n = naviLock.withLock { navi.rerouteFreeRoads() }
            _state.update { it.copy(naviState = n, dialog = Dialog.NONE) }
            recheck(showUnknownPopup = false)
            val r = _state.value.report ?: return@launch
            val still = r.applied.any { it.kind == com.silverpass.core.domain.ConditionKind.HIGHWAY }
            val msg = when {
                n !is NaviState.Ready -> "다시 찾은 경로를 받지 못했어요. 내비게이션에서 경로를 확인해 주세요."
                still -> "무료도로 우선으로 다시 찾았지만 아직 고속도로가 있어요.\n내비게이션 설정에서 '유료도로 우회'를 켜거나 경유지를 바꿔 주세요."
                else -> null
            }
            if (msg != null) _state.update { it.copy(rerouteMessage = msg, dialog = Dialog.REROUTE_RESULT) }
        }
    }

    /** 테스트 경로 시연: 내비에서 일반도로로 다시 찾았다고 가정한다. */
    fun applyLocalRouteForDemo() {
        _state.update { it.copy(routeMode = RouteMode.TEST_LOCAL, dialog = Dialog.NONE) }
        log("테스트 경로 변경 → ${DemoData.localRoute.label} (내비 재탐색 가정)", true)
        recheck()
    }

    /**
     * 휴대전화에서 저장한 주행 계획을 내비에 바로 적용한다(M07 → 차량). 계획한 출발 시각이 앞이면 그 시각으로 판정한다.
     * 경로 옵션은 네이버 옵션과 가장 가까운 내비 옵션으로 옮긴다(자동차 전용도로 피하기·무료도로 우선 → 무료도로 우선).
     */
    fun applyPlan() {
        val s = _state.value
        if (!s.canOperate) return
        val plan = s.accounts.firstOrNull { it.id == s.driver?.id }?.plan ?: return
        if (!navi.requestRoute(plan.destination.lat, plan.destination.lon, plan.destination.label, plan.naviOption)) {
            _state.update { it.copy(notice = "내비게이션에 연결하지 못했어요.") }
            return
        }
        val departTod = com.silverpass.core.domain.TripChecker.timeOf(plan.departAt)
        _state.update {
            it.copy(routeMode = RouteMode.NAVI, plannedDeparture = if (plan.departAt > System.currentTimeMillis()) departTod else null)
        }
        log("휴대전화 주행 계획 적용: ${plan.destination.label} · ${plan.optionLabel} · ${departTod.label()} 출발", true)
        viewModelScope.launch { delay(1_500); bringToFront(); onNaviEvent("주행 계획 적용") }
    }

    fun openTaxiHandoff() {
        if (!_state.value.canOperate) return
        _state.update { it.copy(screen = Screen.TAXI_HANDOFF) }
        viewModelScope.launch { syncAccounts() }
    }

    fun backToCheck() = _state.update { it.copy(screen = Screen.CHECK) }

    fun showDialog(d: Dialog) = _state.update { it.copy(dialog = d) }

    // ---------- 시연 도구 ----------

    fun setFixedTime(t: TimeOfDay?) {
        clock.fixed = t
        _state.update { it.copy(fixedTime = t) }
        if (_state.value.screen == Screen.CHECK) recheck(showUnknownPopup = false)
    }

    fun setRouteMode(m: RouteMode) {
        _state.update { it.copy(routeMode = m) }
        viewModelScope.launch {
            refreshRoute()
            if (_state.value.screen == Screen.CHECK) recheck(showUnknownPopup = false)
        }
    }

    /** 시연용으로 내비에 목적지를 넣거나 지운다. 결과는 내비 이벤트로 돌아와 자동으로 다시 확인된다. */
    fun demoDestination(dest: DemoDestination?) {
        if (dest == null) navi.cancelDemoRoute() else navi.requestRoute(dest.lat, dest.lon, dest.label)
        viewModelScope.launch { delay(1_500); bringToFront(); onNaviEvent("시연 목적지") }
    }

    /** 내비가 앞으로 나온 뒤 실버패스 화면을 다시 띄운다(같은 작업을 앞으로 가져오기만 한다). */
    private fun bringToFront() {
        val app = getApplication<Application>()
        runCatching {
            app.startActivity(
                android.content.Intent(app, VehicleActivity::class.java)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            )
        }.onFailure { log("실버패스 화면을 다시 띄우지 못했어요: ${it.message}", false) }
    }

    fun setAssumeParked(on: Boolean) = _state.update { it.copy(assumeParked = on) }

    fun failNextRequest() {
        api.failNext = true
        log("시연: 다음 서버 요청을 실패시킴", true)
    }

    fun resetDemo() {
        pairingJob?.cancel()
        cache.clear()
        clock.fixed = UiState.DEMO_EVENING
        _state.update {
            UiState(driveState = it.driveState, gearName = it.gearName, assumeParked = it.assumeParked, naviState = it.naviState, logs = it.logs)
        }
        log("시연 초기화: 차량을 새로 등록합니다(기존 휴대전화 연결은 끊김)", true)
        viewModelScope.launch { syncAccounts() }
    }

    private fun log(text: String, ok: Boolean) {
        val t = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
        _state.update { it.copy(logs = (listOf(ApiLog(t, text, ok)) + it.logs).take(80)) }
    }

    private companion object {
        const val CACHE_KEY = "vehicle"
    }
}

/** 시연 도구에서 내비에 넣는 목적지. 현재 위치(에뮬레이터 기본: 성남 수정구) 기준. */
enum class DemoDestination(val label: String, val lat: Double, val lon: Double) {
    BUSAN("부산역 (고속도로 경유)", 35.1151, 129.0422),
    MORAN("모란역 (시내 도로)", 37.4321, 127.1290),
}

private fun ApiResult<*>.describe() = when (this) {
    is ApiResult.Ok -> "ok"
    is ApiResult.Rejected -> "$status $error"
    is ApiResult.Offline -> "연결 실패($reason)"
}
