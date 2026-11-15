package com.silverpass.vehicle.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.silverpass.core.model.TimeOfDay
import com.silverpass.core.ui.BigButton
import com.silverpass.core.ui.Popup
import com.silverpass.core.ui.Sp
import com.silverpass.core.ui.TimePickerPopup
import com.silverpass.core.domain.ConditionStatus
import com.silverpass.vehicle.AccountSource
import com.silverpass.vehicle.DemoDestination
import com.silverpass.vehicle.Dialog
import com.silverpass.vehicle.RouteMode
import com.silverpass.vehicle.car.NaviState
import com.silverpass.vehicle.UiState
import com.silverpass.vehicle.VehicleViewModel

@Composable
fun Dialogs(s: UiState, vm: VehicleViewModel) {
    val close = { vm.showDialog(Dialog.NONE) }
    when (s.dialog) {
        Dialog.NONE -> Unit

        // V02 '출발 미루기' → 출발 예정 시각을 고르면 그 시각으로 다시 확인(V03-01)
        Dialog.POSTPONE -> TimePickerPopup(
            title = "언제 출발하실 건가요?",
            initial = s.driver?.quietHours?.end ?: TimeOfDay.of(6),
            body = "고른 시각을 기준으로 운전 조건을 다시 확인해요.",
            onDismiss = close,
            onPick = vm::postpone,
        )

        // V02(B) → 내비게이션에서 일반도로 재탐색 안내. 1차는 경로 변경을 가정한 테스트 경로로 시연.
        Dialog.REROUTE_GUIDE -> Popup(
            "내비게이션에서 경로를 다시 찾아 주세요",
            "내비게이션 설정에서 '유료도로 우회'를 켜고 다시 찾으면 고속도로를 대부분 피할 수 있어요. 경로가 바뀌면 실버패스가 다시 확인해요.\n\n" +
                "(시연) 지금은 테스트 경로라, 아래 버튼은 경로를 바꾼 것으로 가정하고 일반도로 테스트 경로로 바꿔요.",
            close,
        ) {
            BigButton("닫기", close, Modifier.weight(1f), height = 56.dp)
            BigButton("경로를 바꿨어요", vm::applyLocalRouteForDemo, Modifier.weight(1.6f), primary = true, height = 56.dp)
        }

        // E02-02: 정보 누락을 확인 완료로 처리하지 않는다. 사유(목적지 없음·응답 없음 등)를 그대로 보여 준다.
        Dialog.ROUTE_UNKNOWN -> {
            val reason = s.report?.unknown?.firstOrNull()?.status?.let { (it as? ConditionStatus.Unknown)?.reason } ?: s.routeProblem
            Popup(
                "길을 확인할 수 없어요",
                if (reason.contains("목적지")) "$reason.\n내비게이션에서 목적지를 정하면 다시 확인할게요." else "$reason.\n잠시 후 다시 확인해 주세요.",
                close,
            ) {
                BigButton("닫기", close, Modifier.weight(1f), height = 56.dp)
                BigButton("다시 확인", { close(); vm.recheckNow() }, Modifier.weight(1.6f), primary = true, height = 56.dp)
            }
        }

        // V03-02: 무료도로 우선으로 재탐색 중
        Dialog.REROUTING -> Popup("일반도로 위주로 다시 찾는 중이에요", "내비게이션에 '무료도로 우선' 경로를 요청했어요. 잠시만 기다려 주세요.", {}) {
            BigButton("기다리는 중…", {}, Modifier.weight(1f), enabled = false, height = 56.dp)
        }

        Dialog.REROUTE_RESULT -> Popup("경로를 다시 확인해 주세요", s.rerouteMessage ?: "", close) {
            BigButton("확인", close, Modifier.weight(1f), primary = true, height = 56.dp)
        }

        Dialog.DEMO_TOOLS -> DemoTools(s, vm, close)
    }
}

/** 심사·시연용 도구: 기준 시각, 테스트 경로, 차량 신호, Vehicle SDK 호출 기록. 사용자 화면이 아니다. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DemoTools(s: UiState, vm: VehicleViewModel, close: () -> Unit) {
    var custom by remember { mutableStateOf(false) }
    if (custom) {
        TimePickerPopup("기준 시각 고정", s.fixedTime ?: TimeOfDay.of(18, 30), { custom = false }, { vm.setFixedTime(it); custom = false })
        return
    }
    Popup("시연 도구", "시연 화면을 만들기 위한 설정이에요. 실제 사용자에게는 보이지 않아요.", close, content = {
        Section("기준 시각")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("실제 시각", s.fixedTime == null) { vm.setFixedTime(null) }
            Chip("오후 6시 30분", s.fixedTime == TimeOfDay.of(18, 30)) { vm.setFixedTime(TimeOfDay.of(18, 30)) }
            Chip("오전 10시", s.fixedTime == TimeOfDay.of(10)) { vm.setFixedTime(TimeOfDay.of(10)) }
            Chip("직접 고르기", false) { custom = true }
        }
        Section("경로 출처")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            RouteMode.entries.forEach { m -> Chip(m.label, s.routeMode == m) { vm.setRouteMode(m) } }
        }
        Section("내비 목적지 (시연용으로 내비에 직접 설정)")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            DemoDestination.entries.forEach { d -> Chip(d.label, false) { vm.demoDestination(d) } }
            Chip("목적지 취소", false) { vm.demoDestination(null) }
        }
        Text(
            when (val n = s.naviState) {
                is NaviState.Ready -> "내비: ${n.info.destinationName} · 회전 안내 ${n.info.turns.size}개 · 통행료 ${n.info.tollFare}원"
                NaviState.NoDestination -> "내비: 목적지 없음"
                is NaviState.Failed -> "내비: ${n.reason}"
                is NaviState.Unavailable -> "내비: ${n.reason}"
                null -> "내비: 확인 중"
            },
            fontSize = 15.sp, color = Sp.Ink,
        )
        Section("동기화 서버")
        Text(
            when (s.source) {
                AccountSource.SERVER -> "연결됨 · 연결된 계정 ${s.accounts.size}명"
                AccountSource.CACHE -> "끊김 · 마지막 동기화 값 사용 중"
                AccountSource.DEMO -> "끊김 · 시연 계정 사용 중"
            },
            fontSize = 15.sp, color = Sp.Ink,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("다음 서버 요청 실패시키기", false) { vm.failNextRequest() }
        }
        Section("차량 신호 (Vehicle SDK · Gear)")
        Text("현재 ${s.driveState.label}${s.gearName?.let { " · $it" } ?: ""}", fontSize = 16.sp, color = Sp.Ink)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("신호 없으면 정차로 가정", s.assumeParked) { vm.setAssumeParked(!s.assumeParked) }
        }
        Section("차량 API 기록")
        LazyColumn(Modifier.fillMaxWidth().height(160.dp).clip(RoundedCornerShape(10.dp)).background(Sp.Surface).padding(10.dp)) {
            items(s.logs) { l ->
                Text("${l.time} ${if (l.ok) "✓" else "✕"} ${l.text}", fontSize = 13.sp, color = if (l.ok) Sp.Ink else Sp.Warn)
            }
        }
    }) {
        BigButton("시연 초기화", { vm.resetDemo(); close() }, Modifier.weight(1f), height = 52.dp)
        BigButton("닫기", close, Modifier.weight(1f), primary = true, height = 52.dp)
    }
}

@Composable
private fun Section(text: String) {
    Text(text, Modifier.padding(top = 14.dp, bottom = 8.dp), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Sp.Sub)
}

@Composable
private fun Chip(text: String, on: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(if (on) Sp.Primary else Sp.Box)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
    ) {
        Text(text, fontSize = 15.sp, color = if (on) Color.White else Sp.Ink)
    }
}
