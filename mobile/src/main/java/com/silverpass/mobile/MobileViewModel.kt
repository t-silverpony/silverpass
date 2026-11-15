package com.silverpass.mobile

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.silverpass.core.data.DemoClock
import com.silverpass.core.model.QuietHours
import com.silverpass.core.model.TimeOfDay
import com.silverpass.core.sync.ApiResult
import com.silverpass.core.sync.CouponUseView
import com.silverpass.core.sync.DeviceAccount
import com.silverpass.core.sync.RemoteAccount
import com.silverpass.core.sync.SyncApi
import com.silverpass.core.sync.SyncCache
import com.silverpass.core.sync.VehicleView
import com.silverpass.core.sync.Place
import com.silverpass.core.sync.RouteOptionView
import com.silverpass.core.domain.TripChecker
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

/** 화면 ID는 화면설계 문서와 같게 둔다. */
enum class Screen { M00_START, M01_SIGNUP, M04_HOME, M02_TIME, M02_ROAD, M03_VEHICLE, M07_PLAN, T01_COUPON, T02_USE, T03_DONE, M05_HISTORY }

enum class Dialog { NONE, PICK_START, PICK_END, PICK_DEPART, SAVE_FAILED, SAVE_CONFLICT, COUPON_EMPTY, REDEEM_FAILED, UNLINK_CONFIRM, DEMO_TOOLS }

/** 주행 계획에서 주소를 고르는 칸 */
enum class PlanField { ORIGIN, DESTINATION }

data class UiState(
    val deviceAccounts: List<DeviceAccount> = emptyList(),
    val selectedId: String? = null,
    val account: RemoteAccount? = null,
    /** 서버에 마지막으로 붙은 결과. false면 마지막 저장값을 보여 주고 수정은 막는다(E02-03). */
    val online: Boolean = true,
    val lastSyncAt: Long? = null,
    val busy: Boolean = false,
    val message: String? = null,
    val stack: List<Screen> = listOf(Screen.M00_START),
    val signupName: String = "",
    /** 설정 화면에서 고치는 중인 값. '저장'을 눌러야 서버에 반영된다. */
    val draftStart: TimeOfDay = TimeOfDay.of(18),
    val draftEnd: TimeOfDay = TimeOfDay.of(6),
    val draftQuietOn: Boolean = true,
    val draftAvoidHighway: Boolean = false,
    val now: TimeOfDay = TimeOfDay.of(18, 30),
    /** T02에 들어올 때 만든 사용 건 ID. 완료 입력을 여러 번 눌러도 서버가 한 번만 차감한다. */
    val pendingRequestId: String? = null,
    val lastUse: CouponUseView? = null,
    /** 시간 조건에 해당하지 않을 때, 차량에서 자제 안내를 받았다고 사용자가 직접 확인한 경우 */
    val confirmedVehicleNotice: Boolean = false,
    val vehicles: List<VehicleView> = emptyList(),
    val pairingInput: String = "",
    val pairingMessage: String? = null,
    val pairingOk: Boolean = false,
    val unlinkTarget: VehicleView? = null,
    val fixedTime: TimeOfDay? = TimeOfDay.of(18, 30),
    val dialog: Dialog = Dialog.NONE,
    // ----- 주행 계획(M07) -----
    val planDayOffset: Int = 0,
    val planTime: TimeOfDay = TimeOfDay.of(10),
    val planOrigin: Place? = null,
    val planDestination: Place? = null,
    val planEditing: PlanField? = null,
    val planQuery: String = "",
    val planCandidates: List<Place> = emptyList(),
    val planRoutes: List<RouteOptionView> = emptyList(),
    val planSelected: String? = null,
    val planMessage: String? = null,
    val planOk: Boolean = false,
) {
    val screen get() = stack.last()
    val couponsLeft get() = account?.coupons?.remaining ?: 0
    val history get() = account?.coupons?.uses.orEmpty()

    /** T01-01: 확인 가능한 데이터(1차는 시간)로 자제 조건 해당 여부를 판단한다. */
    val inQuietHours get() = account?.quietHours?.let { now in it } == true

    val canUseCoupon get() = online && couponsLeft > 0 && (inQuietHours || confirmedVehicleNotice)

    /** 계획한 출발 시각(한국 시각, epoch ms). 오늘/내일 + 시각. */
    val planDepartAt: Long
        get() = LocalDate.now(KST).plusDays(planDayOffset.toLong()).atTime(planTime.hour, planTime.minute).atZone(KST).toInstant().toEpochMilli()

    /** 이미 지난 출발 시각(실제 시각 기준, 1분 여유). 서버도 지난 시각 계획은 받지 않는다. */
    val planInPast: Boolean get() = planDepartAt < System.currentTimeMillis() - 60_000

    /** 경로 옵션마다 운전 조건과 맞는지(M07-02). */
    fun evaluate(r: RouteOptionView): RouteEvaluation {
        val a = account
        val quiet = TripChecker.quietConflict(a?.quietHours, planDepartAt, r.durationS.toLong())
        val highway = a?.avoidHighway == true && r.highways.isNotEmpty()
        return RouteEvaluation(quiet, highway, TripChecker.timeOf(planDepartAt + r.durationS * 1000L), crossesDay(planDepartAt, r.durationS))
    }

    private fun crossesDay(depart: Long, durationS: Int): Boolean {
        val d0 = java.time.Instant.ofEpochMilli(depart).atZone(KST).toLocalDate()
        val d1 = java.time.Instant.ofEpochMilli(depart + durationS * 1000L).atZone(KST).toLocalDate()
        return d1 != d0
    }

    companion object {
        val KST: ZoneId = ZoneId.of("Asia/Seoul")
    }
}

data class RouteEvaluation(
    val quiet: TripChecker.QuietConflict?,
    val highwayConflict: Boolean,
    val arrival: TimeOfDay,
    val arrivesNextDay: Boolean,
) {
    val ok get() = quiet == null && !highwayConflict
}

/**
 * 시연용 입력. 한글 입력이 어려운 시연·E2E에서 누르면 검색까지 한다.
 * [showList]면 후보 목록을 보여 주고 사용자가 고르게 한다(장소 이름 검색 시연).
 */
enum class DemoPlace(val label: String, val query: String?, val place: Place?, val showList: Boolean = false) {
    HOME("시연: 성남시 수정구 시흥동", "성남시 수정구 시흥동", null),
    BUSAN("시연: '부산역' 검색", "부산역", null, showList = true),
    MORAN("시연: 모란역", null, Place("모란역", 37.4321, 127.1290)),
}

class MobileViewModel(app: Application) : AndroidViewModel(app) {
    private val api = SyncApi()
    private val cache = SyncCache(app, "silverpass-mobile")
    private val clock = DemoClock().apply { fixed = TimeOfDay.of(18, 30) }
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        val list = cache.deviceAccounts()
        _state.update { it.copy(deviceAccounts = list, selectedId = cache.currentAccountId ?: list.firstOrNull()?.id) }
    }

    private val token get() = _state.value.deviceAccounts.firstOrNull { it.id == _state.value.account?.id }?.token

    // ---------- 이동 ----------

    private fun push(s: Screen) = _state.update { it.copy(stack = it.stack + s, message = null) }

    fun back(): Boolean {
        val s = _state.value
        if (s.dialog != Dialog.NONE) {
            showDialog(Dialog.NONE)
            return true
        }
        if (s.stack.size <= 1) return false
        _state.update { it.copy(stack = it.stack.dropLast(1), message = null) }
        refresh()
        return true
    }

    fun home() {
        _state.update { it.copy(stack = listOf(Screen.M00_START, Screen.M04_HOME), pendingRequestId = null, message = null) }
        refresh()
    }

    fun showDialog(d: Dialog) = _state.update { it.copy(dialog = d) }

    // ---------- M00 시작 · M01 간편 가입 ----------

    fun select(id: String) = _state.update { it.copy(selectedId = id) }

    fun start() {
        val a = _state.value.deviceAccounts.firstOrNull { it.id == _state.value.selectedId } ?: return
        cache.currentAccountId = a.id
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            if (load(a)) {
                _state.update { it.copy(confirmedVehicleNotice = false) }
                push(Screen.M04_HOME)
            }
            _state.update { it.copy(busy = false) }
        }
    }

    fun openSignup() {
        _state.update { it.copy(signupName = "") }
        push(Screen.M01_SIGNUP)
    }

    fun setSignupName(v: String) = _state.update { it.copy(signupName = v.take(20), message = null) }

    fun signup() {
        val name = _state.value.signupName.trim()
        if (name.isEmpty()) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, message = null) }
            when (val r = api.createAccount(name)) {
                is ApiResult.Ok -> {
                    val (token, acc) = r.value
                    cache.addDeviceAccount(DeviceAccount(acc.id, acc.name, token))
                    cache.currentAccountId = acc.id
                    remember(acc)
                    _state.update {
                        it.copy(deviceAccounts = cache.deviceAccounts(), selectedId = acc.id, account = acc, online = true,
                            stack = listOf(Screen.M00_START, Screen.M04_HOME), confirmedVehicleNotice = false)
                    }
                }
                is ApiResult.Rejected -> _state.update {
                    it.copy(message = when (r.status) {
                        429 -> "가입 요청이 너무 많아요. 잠시 후 다시 시도해 주세요."
                        503 -> "지금은 새 계정을 만들 수 없어요."
                        else -> "이름을 확인해 주세요(20자 이내, 특수 문자 제외)."
                    })
                }
                is ApiResult.Offline -> _state.update { it.copy(message = "인터넷 연결을 확인해 주세요.") }
            }
            _state.update { it.copy(busy = false) }
        }
    }

    fun switchAccount() = _state.update { it.copy(stack = listOf(Screen.M00_START), account = null, message = null) }

    /** 서버에서 계정을 받아 온다. 연결이 안 되면 마지막 저장값으로 보여 준다. */
    private suspend fun load(a: DeviceAccount): Boolean {
        when (val r = api.me(a.token)) {
            is ApiResult.Ok -> {
                remember(r.value)
                _state.update { it.copy(account = r.value, online = true, lastSyncAt = System.currentTimeMillis(), now = clock.timeOfDay()) }
                return true
            }
            is ApiResult.Rejected -> {
                _state.update { it.copy(message = "이 기기의 ${a.name} 계정 정보가 서버에 없어요. 새로 시작해 주세요.") }
                return false
            }
            is ApiResult.Offline -> {
                val cached = cache.cachedAccounts("acct:${a.id}")
                if (cached == null) {
                    _state.update { it.copy(message = "인터넷 연결을 확인해 주세요.") }
                    return false
                }
                _state.update { it.copy(account = cached.first.first(), online = false, lastSyncAt = cached.second, now = clock.timeOfDay()) }
                return true
            }
        }
    }

    private fun remember(acc: RemoteAccount) = cache.saveAccounts("acct:${acc.id}", listOf(acc), System.currentTimeMillis())

    fun refresh() {
        val a = _state.value.deviceAccounts.firstOrNull { it.id == _state.value.account?.id } ?: return
        viewModelScope.launch { load(a) }
    }

    // ---------- M02 운전 조건 ----------

    fun openTimeSetting() {
        val q = _state.value.account?.quietHours
        _state.update { it.copy(draftStart = q?.start ?: TimeOfDay.of(18), draftEnd = q?.end ?: TimeOfDay.of(6), draftQuietOn = true) }
        push(Screen.M02_TIME)
    }

    fun openRoadSetting() {
        _state.update { it.copy(draftAvoidHighway = it.account?.avoidHighway == true) }
        push(Screen.M02_ROAD)
    }

    fun pickStart(t: TimeOfDay) = _state.update { it.copy(draftStart = t, dialog = Dialog.NONE) }

    fun pickEnd(t: TimeOfDay) = _state.update { it.copy(draftEnd = t, dialog = Dialog.NONE) }

    fun setDraftQuietOn(on: Boolean) = _state.update { it.copy(draftQuietOn = on) }

    fun setDraftAvoidHighway(on: Boolean) = _state.update { it.copy(draftAvoidHighway = on) }

    fun saveTime() {
        val s = _state.value
        if (s.draftQuietOn && s.draftStart == s.draftEnd) return
        val acc = s.account ?: return
        save(if (s.draftQuietOn) QuietHours(s.draftStart, s.draftEnd) else null, acc.avoidHighway)
    }

    fun saveRoad() {
        val acc = _state.value.account ?: return
        save(acc.quietHours, _state.value.draftAvoidHighway)
    }

    /**
     * 서버에 저장하고 성공하면 차량에도 반영된다(M02-04). 다른 기기에서 먼저 바꿨으면(409) 최신 값을 불러와 알린다.
     * 저장에 실패하면 고친 값은 그대로 두고 팝업으로 다시 저장하게 한다.
     */
    private fun save(quiet: QuietHours?, avoidHighway: Boolean) {
        val acc = _state.value.account ?: return
        val t = token ?: return
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            when (val r = api.updateConditions(t, quiet, avoidHighway, acc.version)) {
                is ApiResult.Ok -> {
                    remember(r.value)
                    _state.update { it.copy(account = r.value, online = true, dialog = Dialog.NONE) }
                    back()
                }
                is ApiResult.Rejected -> if (r.error == "version_conflict") {
                    val cur = SyncApi.account(r.body.getJSONObject("current"))
                    remember(cur)
                    _state.update {
                        it.copy(
                            account = cur, dialog = Dialog.SAVE_CONFLICT,
                            draftStart = cur.quietHours?.start ?: it.draftStart, draftEnd = cur.quietHours?.end ?: it.draftEnd,
                            draftQuietOn = cur.quietHours != null, draftAvoidHighway = cur.avoidHighway,
                        )
                    }
                } else {
                    _state.update { it.copy(dialog = Dialog.SAVE_FAILED) }
                }
                is ApiResult.Offline -> _state.update { it.copy(dialog = Dialog.SAVE_FAILED, online = false) }
            }
            _state.update { it.copy(busy = false) }
        }
    }

    fun retrySave() {
        _state.update { it.copy(dialog = Dialog.NONE) }
        when (_state.value.screen) {
            Screen.M02_TIME -> saveTime()
            Screen.M02_ROAD -> saveRoad()
            else -> Unit
        }
    }

    // ---------- M03 차량 연결 ----------

    fun openVehicles() {
        _state.update { it.copy(pairingInput = "", pairingMessage = null, pairingOk = false) }
        push(Screen.M03_VEHICLE)
        loadVehicles()
    }

    private fun loadVehicles() {
        val t = token ?: return
        viewModelScope.launch {
            when (val r = api.myVehicles(t)) {
                is ApiResult.Ok -> _state.update { it.copy(vehicles = r.value, online = true) }
                else -> _state.update { it.copy(online = r !is ApiResult.Offline && it.online) }
            }
        }
    }

    fun setPairingInput(v: String) = _state.update { it.copy(pairingInput = v.filter(Char::isDigit).take(6), pairingMessage = null) }

    fun claimPairing() {
        val code = _state.value.pairingInput
        val t = token ?: return
        if (code.length != 6) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            val (msg, ok) = when (val r = api.claimPairing(t, code)) {
                is ApiResult.Ok -> "'${r.value.name}'과 연결됐어요. 이제 차에서 이 계정을 고를 수 있어요." to true
                is ApiResult.Rejected -> when (r.status) {
                    404 -> "번호가 맞지 않아요. 차량 화면의 번호를 다시 확인해 주세요."
                    410 -> "번호가 만료됐어요. 차량에서 [새 번호 받기]를 누른 뒤 다시 입력해 주세요."
                    429 -> "여러 번 틀렸어요. 10분 뒤에 다시 시도해 주세요."
                    else -> "연결하지 못했어요. 다시 시도해 주세요."
                } to false
                is ApiResult.Offline -> "인터넷 연결을 확인해 주세요." to false
            }
            _state.update { it.copy(busy = false, pairingMessage = msg, pairingOk = ok, pairingInput = if (ok) "" else it.pairingInput) }
            if (ok) loadVehicles()
        }
    }

    fun askUnlink(v: VehicleView) = _state.update { it.copy(unlinkTarget = v, dialog = Dialog.UNLINK_CONFIRM) }

    /** 연결 해제: 그 차량에서는 이 계정의 조건·기록을 더 볼 수 없다(E01-03). */
    fun unlink() {
        val v = _state.value.unlinkTarget ?: return
        val t = token ?: return
        viewModelScope.launch {
            val r = api.unlinkVehicle(t, v.id)
            _state.update {
                it.copy(dialog = Dialog.NONE, unlinkTarget = null,
                    pairingMessage = if (r is ApiResult.Ok) "'${v.name}' 연결을 해제했어요." else "연결을 해제하지 못했어요. 다시 시도해 주세요.",
                    pairingOk = r is ApiResult.Ok)
            }
            loadVehicles()
        }
    }

    // ---------- M07 주행 계획 ----------

    fun openPlan() {
        val p = _state.value.account?.plan
        val now = clock.timeOfDay()
        // 처음에는 지금(시연 시각)에서 가까운 15분 단위로 맞춘다.
        val start = TimeOfDay(((now.minutes + 14) / 15 * 15) % (24 * 60))
        _state.update {
            it.copy(
                planOrigin = p?.origin ?: it.planOrigin, planDestination = p?.destination ?: it.planDestination,
                planTime = start, planDayOffset = 0, planRoutes = emptyList(), planSelected = null,
                planMessage = null, planOk = false, planEditing = null, planCandidates = emptyList(),
            )
        }
        push(Screen.M07_PLAN)
    }

    fun setPlanDay(offset: Int) = _state.update { it.copy(planDayOffset = offset, planMessage = null) }

    fun pickDepart(t: TimeOfDay) = _state.update { it.copy(planTime = t, dialog = Dialog.NONE, planMessage = null) }

    fun editPlanField(f: PlanField) = _state.update { it.copy(planEditing = f, planQuery = "", planCandidates = emptyList(), planMessage = null) }

    fun setPlanQuery(q: String) = _state.update { it.copy(planQuery = q.take(80)) }

    fun searchAddress(query: String = _state.value.planQuery) {
        val t = token ?: return
        if (query.isBlank()) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, planMessage = null) }
            when (val r = api.geocode(t, query.trim())) {
                is ApiResult.Ok -> _state.update {
                    it.copy(planCandidates = r.value, planMessage = if (r.value.isEmpty()) "찾지 못했어요. 장소 이름이나 도로명 주소로 다시 입력해 주세요(예: 부산역, 부산광역시 동구 중앙대로 206)." else null)
                }
                is ApiResult.Rejected -> _state.update { it.copy(planMessage = planError(r)) }
                is ApiResult.Offline -> _state.update { it.copy(planMessage = "인터넷 연결을 확인해 주세요.") }
            }
            _state.update { it.copy(busy = false) }
        }
    }

    fun chooseAddress(p: Place) {
        _state.update {
            when (it.planEditing) {
                PlanField.ORIGIN -> it.copy(planOrigin = p)
                PlanField.DESTINATION -> it.copy(planDestination = p)
                null -> it
            }.copy(planEditing = null, planCandidates = emptyList(), planRoutes = emptyList(), planSelected = null)
        }
    }

    /** 시연 주소 버튼: 좌표가 있으면 바로 쓰고, 없으면 주소 검색 후 첫 결과를 쓴다. */
    fun useDemoPlace(d: DemoPlace) {
        d.place?.let { return chooseAddress(it) }
        if (d.showList) {
            _state.update { it.copy(planQuery = d.query!!) }
            return searchAddress(d.query!!)
        }
        val t = token ?: return
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            val r = api.geocode(t, d.query!!)
            _state.update { it.copy(busy = false) }
            if (r is ApiResult.Ok && r.value.isNotEmpty()) chooseAddress(r.value.first())
            else _state.update { it.copy(planMessage = if (r is ApiResult.Rejected) planError(r) else "주소를 찾지 못했어요.") }
        }
    }

    fun findRoutes() {
        val s = _state.value
        if (s.planInPast) return _state.update { it.copy(planMessage = PAST_MESSAGE, planOk = false) }
        val t = token ?: return
        val o = s.planOrigin ?: return
        val d = s.planDestination ?: return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, planMessage = null, planRoutes = emptyList(), planSelected = null) }
            when (val r = api.routes(t, o, d)) {
                is ApiResult.Ok -> _state.update {
                    it.copy(
                        planRoutes = r.value,
                        // 조건에 맞는 첫 경로를 미리 골라 둔다.
                        planSelected = r.value.firstOrNull { x -> it.evaluate(x).ok }?.option ?: r.value.firstOrNull()?.option,
                        planMessage = if (r.value.isEmpty()) "경로를 찾지 못했어요. 출발지·목적지를 확인해 주세요." else null,
                    )
                }
                is ApiResult.Rejected -> _state.update { it.copy(planMessage = planError(r)) }
                is ApiResult.Offline -> _state.update { it.copy(planMessage = "인터넷 연결을 확인해 주세요.") }
            }
            _state.update { it.copy(busy = false) }
        }
    }

    fun selectRoute(option: String) = _state.update { it.copy(planSelected = option, planMessage = null) }

    /** "이 경로대로 주행 계획하기": 서버에 저장하면 연결된 차량에서 바로 내비에 적용할 수 있다. */
    fun savePlan() {
        val s = _state.value
        if (s.planInPast) return _state.update { it.copy(planMessage = PAST_MESSAGE, planOk = false) }
        val t = token ?: return
        val route = s.planRoutes.firstOrNull { it.option == s.planSelected } ?: return
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            when (val r = api.savePlan(t, s.planOrigin!!, s.planDestination!!, s.planDepartAt, route)) {
                is ApiResult.Ok -> {
                    val acc = s.account?.copy(plan = r.value)
                    acc?.let(::remember)
                    _state.update { it.copy(account = acc, planOk = true, planMessage = "주행 계획을 저장했어요. 차에서 [내비에 적용]을 누르면 바로 안내를 시작해요.") }
                }
                is ApiResult.Rejected -> _state.update {
                    it.copy(planOk = false, planMessage = if (r.body.optString("field") == "departAt") PAST_MESSAGE else planError(r))
                }
                is ApiResult.Offline -> _state.update { it.copy(planOk = false, planMessage = "저장하지 못했어요. 인터넷 연결을 확인해 주세요.") }
            }
            _state.update { it.copy(busy = false) }
        }
    }

    fun clearPlan() {
        val t = token ?: return
        viewModelScope.launch {
            if (api.deletePlan(t) is ApiResult.Ok) {
                val acc = _state.value.account?.copy(plan = null)
                acc?.let(::remember)
                _state.update { it.copy(account = acc) }
            }
        }
    }

    private companion object {
        const val PAST_MESSAGE = "이미 지난 시각이에요. '내일'을 고르거나 출발 시각을 바꿔 주세요."
    }

    private fun planError(r: ApiResult.Rejected) = when (r.status) {
        429 -> "검색을 너무 많이 했어요. 10분 뒤에 다시 시도해 주세요."
        503 -> "지금은 길찾기를 쓸 수 없어요."
        502 -> "길찾기 서비스가 응답하지 않아요. 잠시 후 다시 시도해 주세요."
        else -> "요청을 처리하지 못했어요. 입력을 확인해 주세요."
    }

    // ---------- T01~T03, M05 모의 할인권 ----------

    fun openCoupon() {
        _state.update { it.copy(now = clock.timeOfDay()) }
        push(Screen.T01_COUPON)
        if (_state.value.couponsLeft == 0) showDialog(Dialog.COUPON_EMPTY)
        refresh()
    }

    fun confirmVehicleNotice(on: Boolean) = _state.update { it.copy(confirmedVehicleNotice = on) }

    fun chooseCoupon() {
        val s = _state.value
        if (s.couponsLeft == 0) return showDialog(Dialog.COUPON_EMPTY)
        if (!s.canUseCoupon) return
        _state.update { it.copy(pendingRequestId = UUID.randomUUID().toString()) }
        push(Screen.T02_USE)
    }

    /** '이용 완료 입력': 서버가 월 한도와 중복을 확인하고 차감한다(T02-03). 같은 requestId로 다시 보내도 한 번만 차감된다. */
    fun completeUse() {
        val req = _state.value.pendingRequestId ?: return
        val t = token ?: return
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            when (val r = api.redeem(t, req)) {
                is ApiResult.Ok -> {
                    val use = r.value.uses.firstOrNull { it.requestId == req }
                    val acc = _state.value.account?.copy(coupons = r.value)
                    acc?.let(::remember)
                    _state.update {
                        it.copy(
                            account = acc, lastUse = use, pendingRequestId = null, dialog = Dialog.NONE, online = true,
                            stack = it.stack.dropLast(1).dropLastWhile { s -> s == Screen.T01_COUPON } + Screen.T03_DONE,
                        )
                    }
                }
                is ApiResult.Rejected -> if (r.error == "limit_reached") showDialog(Dialog.COUPON_EMPTY) else showDialog(Dialog.REDEEM_FAILED)
                is ApiResult.Offline -> _state.update { it.copy(dialog = Dialog.REDEEM_FAILED) }
            }
            _state.update { it.copy(busy = false) }
        }
    }

    /** 실패 팝업 '다시 확인': 같은 사용 건 ID로 다시 보낸다. 서버가 이미 처리했으면 그 결과를 돌려준다. */
    fun recheckUse() {
        showDialog(Dialog.NONE)
        completeUse()
    }

    fun openHistory() {
        push(Screen.M05_HISTORY)
        refresh()
    }

    // ---------- 시연 도구 ----------

    fun setFixedTime(t: TimeOfDay?) {
        clock.fixed = t
        _state.update { it.copy(fixedTime = t, now = clock.timeOfDay()) }
    }

    fun failNextRequest() {
        api.failNextWrite = true
        _state.update { it.copy(dialog = Dialog.NONE) }
    }

    /** 이 기기에 저장한 계정 목록만 지운다(서버 계정은 남는다). */
    fun resetDevice() {
        cache.clear()
        clock.fixed = TimeOfDay.of(18, 30)
        _state.update { UiState() }
    }
}
