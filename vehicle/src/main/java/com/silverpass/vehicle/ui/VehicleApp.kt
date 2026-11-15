package com.silverpass.vehicle.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.silverpass.core.domain.ConditionKind
import com.silverpass.core.domain.ConditionStatus
import com.silverpass.core.model.Driver
import com.silverpass.core.model.withCopula
import com.silverpass.core.ui.Avatar
import com.silverpass.core.ui.BigButton
import com.silverpass.core.ui.Body
import com.silverpass.core.ui.ChoiceRow
import com.silverpass.core.ui.InfoBox
import com.silverpass.core.ui.SilverPassTheme
import com.silverpass.core.ui.SourceTag
import com.silverpass.core.ui.Sp
import com.silverpass.core.ui.Title
import com.silverpass.vehicle.AccountSource
import com.silverpass.vehicle.Dialog
import com.silverpass.vehicle.RouteMode
import com.silverpass.vehicle.Screen
import com.silverpass.vehicle.UiState
import com.silverpass.vehicle.VehicleViewModel
import com.silverpass.vehicle.car.DriveState

@Composable
fun VehicleApp(vm: VehicleViewModel) {
    val s by vm.state.collectAsStateWithLifecycle()
    SilverPassTheme {
        Box(Modifier.fillMaxSize().background(Color.White), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier
                    .widthIn(max = 760.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 32.dp, vertical = 28.dp),
            ) {
                // 정차가 확인되지 않으면 조작 화면 대신 짧은 안내만 보여 준다(V04-04).
                if (!s.canOperate && s.screen != Screen.SELECT_DRIVER) {
                    DrivingView(s)
                } else {
                    when (s.screen) {
                        Screen.SELECT_DRIVER -> SelectDriver(s, vm)
                        Screen.PAIRING -> PairingView(s, vm)
                        Screen.CHECK -> CheckView(s, vm)
                        Screen.TAXI_HANDOFF -> TaxiHandoff(s, vm)
                    }
                }
                Spacer(Modifier.height(28.dp))
                DemoBar(s) { vm.showDialog(Dialog.DEMO_TOOLS) }
            }
        }
        Dialogs(s, vm)
    }
}

// ---------- V00 운전자 선택 ----------

@Composable
private fun SelectDriver(s: UiState, vm: VehicleViewModel) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        AppMark()
        Spacer(Modifier.padding(6.dp))
        Text("실버패스", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Sp.Ink)
    }
    Spacer(Modifier.height(20.dp))
    s.notice?.let {
        Body(it, color = Sp.Ok, size = 18)
        Spacer(Modifier.height(8.dp))
    }
    Title("누가 운전하시나요?")
    Body("휴대전화에서 설정한 운전 자제 조건을 불러옵니다.")
    Spacer(Modifier.height(20.dp))
    val empty = s.drivers.isEmpty()
    if (empty) {
        InfoBox(
            if (s.syncing) "확인 중" else "연결된 휴대전화 없음",
            if (s.syncing) "연결된 계정을 불러오는 중이에요" else "아직 연결된 계정이 없어요",
            Modifier.fillMaxWidth(),
        )
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        s.drivers.forEach { d ->
            ChoiceRow(d.name, d.summary(), d.id == s.selectedId, { vm.select(d.id) }, enabled = s.canOperate)
        }
    }
    Spacer(Modifier.height(20.dp))
    if (!empty) {
        BigButton("이 운전자로 확인", vm::confirmDriver, primary = true, enabled = s.canOperate && s.selectedId != null)
        Spacer(Modifier.height(10.dp))
    }
    BigButton("휴대전화 연결하기", vm::openPairing, primary = empty, enabled = s.canOperate)
    Spacer(Modifier.height(10.dp))
    Body(
        if (!s.canOperate) "차가 멈춘 뒤(P단) 선택할 수 있어요. 지금 상태: ${s.driveState.label}"
        else when (s.source) {
            AccountSource.SERVER -> "휴대전화와 연결된 계정이에요 · 차가 멈춰 있을 때 선택하세요."
            AccountSource.CACHE -> "서버에 연결하지 못해 마지막 동기화(${hm(s.lastSyncAt)}) 정보를 보여 드려요."
            AccountSource.DEMO -> "서버에 연결하지 못해 시연 계정을 보여 드려요."
        },
        size = 16,
        color = if (!s.canOperate || s.source != AccountSource.SERVER) Sp.Warn else Sp.Sub,
    )
}

// ---------- 휴대전화 연결(차량 쪽 연결번호) ----------

@Composable
private fun PairingView(s: UiState, vm: VehicleViewModel) {
    Title("휴대전화 연결하기")
    Spacer(Modifier.height(8.dp))
    Body("휴대전화 실버패스 앱에서 [차량 연결]을 누르고 아래 번호를 입력하세요.", color = Sp.Ink)
    Spacer(Modifier.height(20.dp))
    val code = s.pairingCode
    if (code != null) {
        var left by remember(s.pairingExpiresAt) { mutableStateOf(0L) }
        LaunchedEffect(s.pairingExpiresAt) {
            while (true) {
                left = ((s.pairingExpiresAt ?: 0L) - System.currentTimeMillis()).coerceAtLeast(0) / 1000
                kotlinx.coroutines.delay(1000)
            }
        }
        Text(
            code.chunked(3).joinToString(" "),
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Sp.Box).padding(vertical = 22.dp),
            textAlign = TextAlign.Center, fontSize = 56.sp, fontWeight = FontWeight.Bold, color = Sp.Ink, letterSpacing = 6.sp,
        )
        Spacer(Modifier.height(10.dp))
        Body("남은 시간 ${left / 60}분 ${"%02d".format(left % 60)}초 · 한 번만 쓸 수 있어요", size = 16)
        Body("연결되면 이 화면이 자동으로 넘어가요.", size = 16)
    } else {
        Body(s.pairingError ?: "연결번호를 받는 중이에요…", color = if (s.pairingError != null) Sp.Warn else Sp.Sub)
    }
    Spacer(Modifier.height(20.dp))
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        BigButton("새 번호 받기", vm::newPairingCode)
        BigButton("이전 화면", vm::closePairing)
    }
}

private fun hm(ms: Long?) = ms?.let {
    java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneId.of("Asia/Seoul")).toLocalTime()
        .let { t -> com.silverpass.core.model.TimeOfDay.of(t.hour, t.minute).label() }
} ?: "-"

private fun Driver.summary(): String {
    val parts = buildList {
        quietHours?.let { add("운전을 쉬는 시간 ${it.label()}") }
        if (avoidHighway) add("고속도로 운전 피하기")
    }
    return parts.joinToString(" · ").ifEmpty { "설정한 조건 없음" }
}

// ---------- V01 문제없음 / V02 운전 자제 안내 ----------

@Composable
private fun DriverHeader(d: Driver, vm: VehicleViewModel) {
    Row(
        Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = vm::changeDriver).padding(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(d.initial)
        Spacer(Modifier.padding(6.dp))
        Text("${d.name}님 ▾", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = Sp.Ink)
    }
    Spacer(Modifier.height(18.dp))
}

@Composable
private fun CheckView(s: UiState, vm: VehicleViewModel) {
    val d = s.driver ?: return
    val r = s.report ?: return
    DriverHeader(d, vm)
    if (s.source == AccountSource.CACHE) {
        Body("서버 연결이 끊겨 마지막 동기화(${hm(s.lastSyncAt)}) 기준 조건으로 확인했어요.", size = 15, color = Sp.Warn)
        Spacer(Modifier.height(8.dp))
    }
    PlanCard(s, vm)
    val quiet = r.of(ConditionKind.QUIET_HOURS)
    val highway = r.of(ConditionKind.HIGHWAY)
    val quietApplies = quiet is ConditionStatus.Applies
    val quietDetail = (quiet as? ConditionStatus.Applies)?.detail ?: ""
    val isInTripQuiet = quietDetail.startsWith("운전 중")
    val timeLabel = if (s.plannedDeparture != null) "출발 예정 시각은 ${r.at.label()}" else "지금은 ${r.at.label()}"

    if (r.shouldWarn) {
        Title(
            when {
                quietApplies && highway is ConditionStatus.Applies -> "🚨 운전을 자제하기로 한 조건이 2개 있어요"
                quietApplies -> when {
                    isInTripQuiet -> "🚨 운전 중 쉬는 시간에 들어가게 돼요"
                    s.plannedDeparture != null -> "🚨 그때도 운전을 쉬기로 한 시간이에요"
                    else -> "🚨 운전을 쉬기로 한 시간이에요"
                }
                else -> "🚨 이 길에는 고속도로가 있어요"
            },
        )
        Spacer(Modifier.height(16.dp))
        if (quietApplies) {
            val quietText = if (isInTripQuiet) quietDetail else withCopula(timeLabel)
            InfoBox("운전을 쉬는 시간 · ${d.quietHours!!.label()}", quietText, Modifier.fillMaxWidth())
            Spacer(Modifier.height(10.dp))
        }
        if (highway is ConditionStatus.Applies) {
            InfoBox("선호 도로 설정 · 고속도로 피하기", highway.detail, Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            RouteSource(s)
            Spacer(Modifier.height(10.dp))
        }
        if (s.plannedDeparture != null && quiet !is ConditionStatus.Applies) {
            Body("출발 예정 ${r.at.label()} 기준으로 확인했어요.", color = Sp.Ink, size = 17)
        }
        Body("직접 정하신 조건에 맞춰 알려드려요. 법으로 정해진 운전 제한은 아니에요.", size = 16)
        Spacer(Modifier.height(18.dp))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (highway is ConditionStatus.Applies) {
                BigButton("일반도로로 다시 찾기", vm::findLocalRoads, primary = true)
                BigButton("할인 받고 택시 이용하기", vm::openTaxiHandoff)
            }
            if (quiet is ConditionStatus.Applies) {
                BigButton("택시 할인권 보기", vm::openTaxiHandoff, primary = highway !is ConditionStatus.Applies)
                BigButton("출발 미루기", { vm.showDialog(Dialog.POSTPONE) })
            }
            BigButton("운전자 바꾸기", vm::changeDriver)
        }
        Spacer(Modifier.height(6.dp))
        TextLink("다시 확인하기") { vm.recheckNow() }
    } else {
        val hasUnknown = r.unknown.isNotEmpty()
        Title(
            when {
                hasUnknown -> "확인하지 못한 조건이 있어요"
                s.plannedDeparture != null -> "${r.at.label()}에 출발하시면 괜찮아요\n그때 다시 확인해 주세요"
                else -> "이대로 운전하셔도 괜찮아요\n오늘도 안전운전하세요 😄"
            },
        )
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            InfoBox(
                "선호 도로 설정 · ${if (d.avoidHighway) "고속도로 피하기" else "없음"}",
                when (highway) {
                    ConditionStatus.Ok -> "고속도로 포함되지 않음"
                    ConditionStatus.NotSet -> "확인할 도로 조건 없음"
                    is ConditionStatus.Unknown -> "경로 확인 불가"
                    is ConditionStatus.Applies -> highway.detail
                },
                Modifier.weight(1f),
                valueColor = if (highway is ConditionStatus.Unknown) Sp.Warn else Sp.Ink,
            )
            InfoBox(
                "운전을 쉬는 시간 · ${d.quietHours?.label() ?: "없음"}",
                if (s.plannedDeparture != null) "출발 예정 ${r.at.label()}" else "현재 시각 ${r.at.label()}",
                Modifier.weight(1f),
            )
        }
        if (d.avoidHighway) {
            Spacer(Modifier.height(8.dp))
            RouteSource(s)
        }
        Spacer(Modifier.height(10.dp))
        Body(
            if (hasUnknown) "${(highway as? ConditionStatus.Unknown)?.reason ?: "경로 정보를 받지 못했어요"}. 고속도로 포함 여부를 확인하지 못했어요."
            else "목적지나 경로를 바꾸면 '다시 확인하기'를 눌러 주세요.",
            size = 16,
        )
        Spacer(Modifier.height(18.dp))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            BigButton("다시 확인하기", vm::recheckNow, primary = true)
            if (s.plannedDeparture != null) BigButton("지금 출발 기준으로 보기", vm::departNow)
            BigButton("운전자 바꾸기", vm::changeDriver)
        }
    }
}

/** 휴대전화에서 저장한 주행 계획. 누르면 내비에 목적지·경로 옵션을 넣고 그 출발 시각으로 다시 판정한다. */
@Composable
private fun PlanCard(s: UiState, vm: VehicleViewModel) {
    val plan = s.accounts.firstOrNull { it.id == s.driver?.id }?.plan ?: return
    val dep = java.time.Instant.ofEpochMilli(plan.departAt).atZone(java.time.ZoneId.of("Asia/Seoul"))
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0xFFE8F1FF)).padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("휴대전화 주행 계획 · ${plan.optionLabel}", fontSize = 15.sp, color = Sp.Sub)
            Text(
                "${plan.destination.label} · ${dep.monthValue}월 ${dep.dayOfMonth}일 ${com.silverpass.core.model.TimeOfDay.of(dep.hour, dep.minute).label()} 출발",
                fontSize = 19.sp, fontWeight = FontWeight.Bold, color = Sp.Ink,
            )
        }
        Spacer(Modifier.padding(6.dp))
        BigButton("내비에 적용", vm::applyPlan, Modifier.widthIn(max = 200.dp), primary = true, height = 52.dp, enabled = s.canOperate)
    }
    Spacer(Modifier.height(12.dp))
}

@Composable
private fun RouteSource(s: UiState) {
    val r = s.route
    Row(verticalAlignment = Alignment.CenterVertically) {
        Body(r?.label ?: s.routeProblem.ifBlank { "경로 정보 없음" }, size = 15)
        Spacer(Modifier.padding(4.dp))
        when {
            r?.isTestData == true || (r == null && s.routeMode != RouteMode.NAVI) -> SourceTag("테스트 데이터")
            s.routeMode == RouteMode.NAVI -> SourceTag("실제 내비 · 고속국도 기준")
        }
    }
    if (r != null && r.evidence.isNotEmpty()) Body("판단 근거: " + r.evidence.joinToString(", "), size = 14)
}

// ---------- V02 → 휴대전화 할인권 안내 ----------

@Composable
private fun TaxiHandoff(s: UiState, vm: VehicleViewModel) {
    val d = s.driver ?: return
    DriverHeader(d, vm)
    Title("휴대전화에서 택시 할인권을 확인하세요")
    Spacer(Modifier.height(16.dp))
    InfoBox(
        "택시 할인권 · 시연용",
        if (s.couponsLeft(d.id) > 0) "3,000원 할인 · 이번 달 ${s.couponsLeft(d.id)}번 남음" else "이번 달 할인권을 모두 썼어요",
        Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(12.dp))
    Body("휴대전화에서 실버패스를 열고 '택시 할인권 보기'를 누르세요.", color = Sp.Ink)
    Spacer(Modifier.height(18.dp))
    BigButton("이전 화면", vm::backToCheck)
    Spacer(Modifier.height(10.dp))
    Body("차를 안전하게 주차한 뒤 휴대전화를 이용하세요. 시연용 할인권이며 실제 택시 요금은 결제되지 않아요.", size = 16)
    if (s.source != AccountSource.SERVER) Body("서버에 연결하지 못해 남은 횟수가 최신이 아닐 수 있어요.", size = 15, color = Sp.Warn)
}

// ---------- 주행 중 ----------

@Composable
private fun DrivingView(s: UiState) {
    val d = s.driver
    if (d != null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(d.initial)
            Spacer(Modifier.padding(6.dp))
            Text("${d.name}님", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = Sp.Ink)
        }
        Spacer(Modifier.height(18.dp))
    }
    Title(if (s.driveState == DriveState.MOVING) "운전 중이에요" else "차량 상태를 확인하지 못했어요")
    Spacer(Modifier.height(12.dp))
    val warn = s.report?.shouldWarn == true
    InfoBox(
        "출발 전 확인 결과",
        if (warn) "자제하기로 한 조건이 있었어요" else "확인한 조건에 해당하지 않아요",
        Modifier.fillMaxWidth(),
        valueColor = if (warn) Sp.Warn else Sp.Ink,
    )
    Spacer(Modifier.height(12.dp))
    Body("설정 변경과 휴대전화 이용은 안전하게 주차한 뒤(P단) 할 수 있어요.", color = Sp.Ink)
}

// ---------- 공통 ----------

@Composable
private fun AppMark() {
    Box(Modifier.clip(RoundedCornerShape(12.dp)).background(Sp.Primary).padding(horizontal = 8.dp, vertical = 6.dp)) {
        Text("Silver\nPass", color = Color.White, fontSize = 12.sp, lineHeight = 13.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
    }
}

@Composable
private fun TextLink(text: String, onClick: () -> Unit) {
    Text(
        text,
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(vertical = 14.dp),
        textAlign = TextAlign.Center, fontSize = 19.sp, color = Sp.Sub, fontWeight = FontWeight.SemiBold,
    )
}

/** 화면 맨 아래 작은 시연 표시줄: 기준 시각·기어 상태. 누르면 시연 도구가 열린다. */
@Composable
private fun DemoBar(s: UiState, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Sp.Surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "시연 도구 · 시각 ${s.fixedTime?.label()?.let { "$it(고정)" } ?: "실제"} · 차량 ${s.driveState.label} · 경로 ${s.routeMode.label} · 서버 ${if (s.source == AccountSource.SERVER) "연결됨" else "끊김"}" +
                if (s.driveState == DriveState.UNKNOWN && s.assumeParked) " → 정차로 가정" else "",
            fontSize = 14.sp, color = Sp.Sub,
        )
    }
}
