package com.silverpass.mobile.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.silverpass.core.sync.RouteOptionView
import com.silverpass.core.ui.BigButton
import com.silverpass.core.ui.Body
import com.silverpass.core.ui.InfoBox
import com.silverpass.core.ui.SourceTag
import com.silverpass.core.ui.Sp
import com.silverpass.core.ui.Title
import com.silverpass.mobile.BuildConfig
import com.silverpass.mobile.DemoPlace
import com.silverpass.mobile.Dialog
import com.silverpass.mobile.MobileViewModel
import com.silverpass.mobile.PlanField
import com.silverpass.mobile.UiState
import kotlin.math.cos

// ---------- M07 주행 계획 ----------

@Composable
fun PlanScreen(s: UiState, vm: MobileViewModel) {
    Text("주행 계획", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Sp.Sub)
    Spacer(Modifier.height(18.dp))
    Title("언제 어디로\n가실 건가요?")
    Spacer(Modifier.height(8.dp))
    Body("출발 전에 운전 조건과 맞는 길을 미리 찾아 드려요.")
    Spacer(Modifier.height(18.dp))

    // 출발 일시 (M07-01)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("오늘" to 0, "내일" to 1).forEach { (label, off) -> DayChip(label, s.planDayOffset == off) { vm.setPlanDay(off) } }
    }
    Spacer(Modifier.height(10.dp))
    InfoBox(
        "출발 시각 · ${if (s.planDayOffset == 0) "오늘" else "내일"}", s.planTime.label(), Modifier.fillMaxWidth(),
        valueColor = if (s.planInPast) Sp.Warn else Sp.Ink, onClick = { vm.showDialog(Dialog.PICK_DEPART) },
    )
    if (s.planInPast) Body("이미 지난 시각이에요. '내일'을 고르거나 출발 시각을 바꿔 주세요.", color = Sp.Warn, size = 16)
    Spacer(Modifier.height(10.dp))
    InfoBox("출발지", s.planOrigin?.label ?: "눌러서 주소 입력", Modifier.fillMaxWidth(), onClick = { vm.editPlanField(PlanField.ORIGIN) })
    if (s.planEditing == PlanField.ORIGIN) AddressInput(s, vm, listOf(DemoPlace.HOME))
    Spacer(Modifier.height(10.dp))
    InfoBox("목적지", s.planDestination?.label ?: "눌러서 주소 입력", Modifier.fillMaxWidth(), onClick = { vm.editPlanField(PlanField.DESTINATION) })
    if (s.planEditing == PlanField.DESTINATION) AddressInput(s, vm, listOf(DemoPlace.BUSAN, DemoPlace.MORAN))
    Spacer(Modifier.height(18.dp))
    BigButton("길 찾기", vm::findRoutes, primary = s.planRoutes.isEmpty(), enabled = s.planOrigin != null && s.planDestination != null && !s.busy && s.online && !s.planInPast)
    s.planMessage?.let {
        Spacer(Modifier.height(10.dp))
        Body(it, color = if (s.planOk) Sp.Ok else Sp.Warn, size = 17)
    }

    // 경로 옵션별 결과 (M07-02)
    if (s.planRoutes.isNotEmpty()) {
        Spacer(Modifier.height(22.dp))
        Text("경로 ${s.planRoutes.size}가지", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Sp.Ink)
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Body("예상 경로 · 네이버 길찾기", size = 14)
            Spacer(Modifier.padding(4.dp))
            SourceTag("실제 주행 경로는 차에서 다시 확인")
        }
        Spacer(Modifier.height(10.dp))
        // 고른 경로를 실제 지도로 본다(네이버 지도). 키가 없거나 인증이 실패하면 카드 안 경로 그림만 쓴다.
        val selected = s.planRoutes.firstOrNull { it.option == s.planSelected }
        var mapError by rememberSaveable { mutableStateOf<String?>(null) }
        if (selected != null && BuildConfig.HAS_NAVER_MAP && mapError == null) {
            Text("지도 · ${selected.label}", fontSize = 16.sp, color = Sp.Sub, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            RouteMapView(selected, Modifier.fillMaxWidth().height(240.dp).clip(RoundedCornerShape(14.dp))) { mapError = it }
            Spacer(Modifier.height(12.dp))
        } else if (mapError != null) {
            Body("지도를 불러오지 못해 경로 그림으로 보여 드려요. (지도 인증 오류 $mapError)", size = 14)
            Spacer(Modifier.height(8.dp))
        }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            s.planRoutes.forEach { r -> RouteCard(s, r, s.planSelected == r.option) { vm.selectRoute(r.option) } }
        }
        Spacer(Modifier.height(18.dp))
        val allBad = s.planRoutes.none { s.evaluate(it).ok }
        if (allBad) {
            // M07-04: 모든 길이 조건에 걸리면 출발 시간을 바꾸거나 택시를 고르게 한다.
            Body("모든 길이 운전 조건과 맞지 않아요. 출발 시각을 바꾸거나 택시를 이용해 보세요.", color = Sp.Warn, size = 17)
            Spacer(Modifier.height(10.dp))
            BigButton("택시 할인권 보기", vm::openCoupon)
            Spacer(Modifier.height(10.dp))
        }
        BigButton("이 경로대로 주행 계획하기", vm::savePlan, primary = true, enabled = s.planSelected != null && !s.busy && s.online && !s.planInPast)
    }
    Spacer(Modifier.height(10.dp))
    BigButton("홈으로", vm::home)
}

@Composable
private fun AddressInput(s: UiState, vm: MobileViewModel, demos: List<DemoPlace>) {
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = s.planQuery, onValueChange = vm::setPlanQuery, modifier = Modifier.fillMaxWidth(),
        textStyle = TextStyle(fontSize = 20.sp), singleLine = true,
        placeholder = { Text("장소 이름이나 주소 (예: 부산역, 서울아산병원)", fontSize = 16.sp) },
    )
    Spacer(Modifier.height(6.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        BigButton("찾기", { vm.searchAddress() }, Modifier.weight(1f), height = 52.dp, enabled = s.planQuery.isNotBlank() && !s.busy)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        demos.forEach { d -> SmallText(d.label) { vm.useDemoPlace(d) } }
    }
    s.planCandidates.forEach { p ->
        Text(
            p.label,
            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { vm.chooseAddress(p) }.padding(vertical = 12.dp, horizontal = 8.dp),
            fontSize = 18.sp, color = Sp.Ink,
        )
    }
}

@Composable
private fun RouteCard(s: UiState, r: RouteOptionView, selected: Boolean, onClick: () -> Unit) {
    val e = s.evaluate(r)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Sp.Surface)
            .border(2.dp, if (selected) Sp.Ok else Color.Transparent, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(r.label, fontSize = 21.sp, fontWeight = FontWeight.Bold, color = Sp.Ink, modifier = Modifier.weight(1f))
            if (selected) Text("선택됨", fontSize = 15.sp, color = Sp.Ok, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "약 ${duration(r.durationS)} · ${"%.0f".format(r.distanceM / 1000.0)}km · 통행료 ${"%,d".format(r.tollFare)}원",
            fontSize = 16.sp, color = Sp.Sub,
        )
        Text("도착 예정 ${if (e.arrivesNextDay) "다음 날 " else ""}${e.arrival.label()}", fontSize = 17.sp, color = Sp.Ink)
        Spacer(Modifier.height(8.dp))
        RouteMap(r, Modifier.fillMaxWidth().height(110.dp))
        Spacer(Modifier.height(8.dp))
        Check(
            e.quiet == null,
            when {
                s.account?.quietHours == null -> "운전을 쉬는 시간: 정하지 않았어요"
                e.quiet == null -> "운전을 쉬는 시간과 겹치지 않아요"
                e.quiet.atDeparture -> "출발 시각이 운전을 쉬는 시간이에요"
                else -> "운전 중 ${e.quiet.at.label()}부터 운전을 쉬는 시간이에요"
            },
        )
        Check(
            !e.highwayConflict,
            when {
                r.highways.isEmpty() -> "고속도로 없음"
                s.account?.avoidHighway == true -> "고속도로 포함: ${r.highways.take(3).joinToString(", ")}"
                else -> "고속도로 포함(피하기로 정하지 않음): ${r.highways.take(2).joinToString(", ")}"
            },
        )
    }
}

@Composable
private fun Check(ok: Boolean, text: String) {
    Text((if (ok) "✓ " else "✕ ") + text, fontSize = 16.sp, color = if (ok) Sp.Ok else Sp.Warn, fontWeight = FontWeight.SemiBold)
}

/** 지도 타일 없이 경로 모양만 그린다(경로 시각화). 출발 초록 점, 도착 빨간 점. */
@Composable
private fun RouteMap(r: RouteOptionView, modifier: Modifier) {
    val pts = r.path
    Canvas(modifier.clip(RoundedCornerShape(12.dp)).background(Color(0xFFE9EEF3))) {
        if (pts.size < 2) return@Canvas
        val midLat = pts.map { it.first }.average()
        val k = cos(Math.toRadians(midLat))   // 경도 1도는 위도 1도보다 짧다
        val xs = pts.map { it.second * k }
        val ys = pts.map { it.first }
        val minX = xs.min(); val maxX = xs.max(); val minY = ys.min(); val maxY = ys.max()
        val pad = 12.dp.toPx()
        val scale = minOf((size.width - 2 * pad) / (maxX - minX).coerceAtLeast(1e-6), (size.height - 2 * pad) / (maxY - minY).coerceAtLeast(1e-6))
        val ox = (size.width - (maxX - minX) * scale) / 2
        val oy = (size.height - (maxY - minY) * scale) / 2
        fun at(i: Int) = Offset((ox + (xs[i] - minX) * scale).toFloat(), (oy + (maxY - ys[i]) * scale).toFloat())
        val path = Path().apply {
            moveTo(at(0).x, at(0).y)
            for (i in 1 until pts.size) lineTo(at(i).x, at(i).y)
        }
        drawPath(path, Color(0xFF3E6FD8), style = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round))
        drawCircle(Color(0xFF16A34A), 7.dp.toPx(), at(0))
        drawCircle(Color(0xFFD92D20), 7.dp.toPx(), at(pts.size - 1))
    }
}

@Composable
private fun DayChip(label: String, on: Boolean, onClick: () -> Unit) {
    Text(
        label,
        Modifier.clip(RoundedCornerShape(20.dp)).background(if (on) Sp.Primary else Sp.Box).clickable(onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 10.dp),
        color = if (on) Color.White else Sp.Ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun SmallText(text: String, onClick: () -> Unit) {
    Text(text, Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(vertical = 10.dp, horizontal = 4.dp), fontSize = 16.sp, color = Sp.Sub)
}

private fun duration(s: Int): String {
    val h = s / 3600
    val m = (s % 3600) / 60
    return if (h > 0) "${h}시간 ${m}분" else "${m}분"
}
