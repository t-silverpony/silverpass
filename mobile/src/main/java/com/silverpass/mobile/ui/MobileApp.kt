package com.silverpass.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import com.silverpass.core.model.TimeOfDay
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.silverpass.core.domain.CouponLedger
import com.silverpass.core.ui.BigButton
import com.silverpass.core.ui.Body
import com.silverpass.core.ui.ChoiceRow
import com.silverpass.core.ui.InfoBox
import com.silverpass.core.ui.SilverPassTheme
import com.silverpass.core.ui.SourceTag
import com.silverpass.core.ui.Sp
import com.silverpass.core.ui.Title
import com.silverpass.mobile.Dialog
import com.silverpass.mobile.MobileViewModel
import com.silverpass.mobile.Screen
import com.silverpass.mobile.UiState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun MobileApp(vm: MobileViewModel) {
    val s by vm.state.collectAsStateWithLifecycle()
    SilverPassTheme {
        Column(
            Modifier
                .fillMaxSize()
                .background(Color.White)
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 20.dp),
        ) {
            when (s.screen) {
                Screen.M00_START -> Start(s, vm)
                Screen.M01_SIGNUP -> Signup(s, vm)
                Screen.M03_VEHICLE -> Vehicles(s, vm)
                Screen.M07_PLAN -> PlanScreen(s, vm)
                Screen.M04_HOME -> Home(s, vm)
                Screen.M02_TIME -> TimeSetting(s, vm)
                Screen.M02_ROAD -> RoadSetting(s, vm)
                Screen.T01_COUPON -> Coupon(s, vm)
                Screen.T02_USE -> UseCoupon(vm)
                Screen.T03_DONE -> UseDone(s, vm)
                Screen.M05_HISTORY -> History(s, vm)
            }
        }
        Dialogs(s, vm)
    }
}

@Composable
private fun TopLabel(text: String) {
    Text(text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Sp.Sub)
    Spacer(Modifier.height(18.dp))
}

@Composable
private fun Buttons(content: @Composable ColumnScope.() -> Unit) {
    Spacer(Modifier.height(28.dp))
    Column(verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
}

// ---------- M00 시작 ----------

@Composable
private fun Start(s: UiState, vm: MobileViewModel) {
    TopLabel("실버패스")
    Title("안전한 운전,\n자유로운 이동", size = 34)
    Spacer(Modifier.height(10.dp))
    Body("나에게 맞는 운전 조건을\n확인하고 이동해요.")
    Spacer(Modifier.height(28.dp))
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        s.deviceAccounts.forEach { a -> ChoiceRow(a.name, "이 휴대전화에 저장된 계정", a.id == s.selectedId, { vm.select(a.id) }) }
    }
    Buttons {
        if (s.deviceAccounts.isNotEmpty()) BigButton("확인", vm::start, primary = true, enabled = !s.busy)
        BigButton("새로 시작하기", vm::openSignup, primary = s.deviceAccounts.isEmpty())
    }
    Message(s)
}

// ---------- M01 간편 가입 ----------

@Composable
private fun Signup(s: UiState, vm: MobileViewModel) {
    TopLabel("간편 가입")
    Title("어떻게 불러 드릴까요?")
    Spacer(Modifier.height(8.dp))
    Body("차에서 운전자를 고를 때 이 이름이 보여요.")
    Spacer(Modifier.height(20.dp))
    OutlinedTextField(
        value = s.signupName,
        onValueChange = vm::setSignupName,
        modifier = Modifier.fillMaxWidth(),
        textStyle = TextStyle(fontSize = 24.sp),
        singleLine = true,
        placeholder = { Text("이름 또는 별칭", fontSize = 22.sp) },
    )
    Spacer(Modifier.height(12.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("김실버", "박이동").forEach { n -> SmallLink("시연: $n") { vm.setSignupName(n) } }
    }
    Buttons {
        BigButton("시작하기", vm::signup, primary = true, enabled = s.signupName.isNotBlank() && !s.busy)
        BigButton("취소", { vm.back() })
    }
    Message(s)
    Spacer(Modifier.height(8.dp))
    Body("휴대전화번호 인증은 다음 단계에서 제공해요. 계정은 이 휴대전화에 저장돼요.", size = 15)
}

@Composable
private fun Message(s: UiState) {
    s.message?.let {
        Spacer(Modifier.height(12.dp))
        Body(it, color = Sp.Warn, size = 17)
    }
}

@Composable
private fun OfflineBanner(s: UiState) {
    if (!s.online) {
        Body("인터넷에 연결되지 않아 마지막 저장(${hm(s.lastSyncAt)}) 정보를 보여 드려요. 바꾼 내용은 연결된 뒤에 저장할 수 있어요.", color = Sp.Warn, size = 15)
        Spacer(Modifier.height(10.dp))
    }
}

// ---------- M03 차량 연결 ----------

@Composable
private fun Vehicles(s: UiState, vm: MobileViewModel) {
    TopLabel("차량 연결")
    Title("차 화면의 연결번호를\n입력해 주세요")
    Spacer(Modifier.height(8.dp))
    Body("차에서 [휴대전화 연결하기]를 누르면 6자리 번호가 보여요.")
    Spacer(Modifier.height(20.dp))
    OutlinedTextField(
        value = s.pairingInput,
        onValueChange = vm::setPairingInput,
        modifier = Modifier.fillMaxWidth(),
        textStyle = TextStyle(fontSize = 34.sp, letterSpacing = 8.sp, textAlign = TextAlign.Center),
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        placeholder = { Text("000000", fontSize = 34.sp, letterSpacing = 8.sp, color = Sp.Line) },
    )
    Buttons { BigButton("연결하기", vm::claimPairing, primary = true, enabled = s.pairingInput.length == 6 && !s.busy) }
    s.pairingMessage?.let {
        Spacer(Modifier.height(12.dp))
        Body(it, color = if (s.pairingOk) Sp.Ok else Sp.Warn, size = 17)
    }
    Spacer(Modifier.height(24.dp))
    Text("연결된 차량", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Sp.Ink)
    Spacer(Modifier.height(8.dp))
    if (s.vehicles.isEmpty()) Body("아직 연결된 차량이 없어요.", size = 16)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        s.vehicles.forEach { v ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                InfoBox("연결됨", v.name, Modifier.weight(1f))
                Spacer(Modifier.padding(4.dp))
                SmallLink("연결 해제") { vm.askUnlink(v) }
            }
        }
    }
    Buttons { BigButton("홈으로", vm::home) }
}

// ---------- M04 홈 ----------

@Composable
private fun Home(s: UiState, vm: MobileViewModel) {
    val d = s.account ?: return
    TopLabel("실버패스")
    OfflineBanner(s)
    Title("${d.name}님이 설정하신\n운전 조건이에요")
    Spacer(Modifier.height(18.dp))
    InfoBox("운전을 쉬는 시간", d.quietHours?.label() ?: "정하지 않았어요", Modifier.fillMaxWidth(), onClick = vm::openTimeSetting)
    Spacer(Modifier.height(10.dp))
    InfoBox("선호 도로 설정", if (d.avoidHighway) "고속도로 피하기" else "정하지 않았어요", Modifier.fillMaxWidth(), onClick = vm::openRoadSetting)
    Spacer(Modifier.height(8.dp))
    Body("조건을 누르면 바꿀 수 있어요.", size = 15)
    Spacer(Modifier.height(18.dp))
    InfoBox("택시 할인권 · 시연용", "이번 달 ${s.couponsLeft}번 남음", Modifier.fillMaxWidth())
    d.plan?.let { p ->
        Spacer(Modifier.height(10.dp))
        InfoBox("주행 계획 · ${p.optionLabel}", "${p.destination.label} · ${dateTime(p.departAt)} 출발", Modifier.fillMaxWidth(), onClick = vm::openPlan)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { SmallLink("계획 지우기", vm::clearPlan) }
    }
    Buttons {
        BigButton("택시 할인권 보기", vm::openCoupon, primary = true)
        BigButton("사용 내역 보기", vm::openHistory)
        BigButton("주행 계획", vm::openPlan)
        BigButton("차량 연결", vm::openVehicles)
    }
    Spacer(Modifier.height(18.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        SmallLink("계정 바꾸기", vm::switchAccount)
        SmallLink("시연 도구") { vm.showDialog(Dialog.DEMO_TOOLS) }
    }
}

// ---------- M02 운전 조건 설정 ----------

@Composable
private fun TimeSetting(s: UiState, vm: MobileViewModel) {
    TopLabel("운전 자제 시간 설정")
    Title("운전을 쉬고 싶은\n시간을 정해 주세요")
    Spacer(Modifier.height(8.dp))
    Body("정하신 시간에 맞춰 알려드려요.")
    Spacer(Modifier.height(20.dp))
    if (s.draftQuietOn) {
        InfoBox("쉬기 시작하는 시간", s.draftStart.label(), Modifier.fillMaxWidth(), onClick = { vm.showDialog(Dialog.PICK_START) })
        Spacer(Modifier.height(10.dp))
        val nextDay = if (s.draftStart > s.draftEnd) "다음 날 " else ""
        InfoBox("쉬는 시간이 끝나는 때", nextDay + s.draftEnd.label(), Modifier.fillMaxWidth(), onClick = { vm.showDialog(Dialog.PICK_END) })
        Spacer(Modifier.height(8.dp))
        if (s.draftStart == s.draftEnd) {
            Body("시작과 끝을 다르게 골라 주세요.", size = 15, color = Sp.Warn)
        } else {
            Body("시간을 누르면 바꿀 수 있어요.", size = 15)
        }
    } else {
        InfoBox("운전을 쉬는 시간", "정하지 않음", Modifier.fillMaxWidth())
    }
    Buttons {
        BigButton("저장", vm::saveTime, primary = true, enabled = !s.draftQuietOn || s.draftStart != s.draftEnd)
        BigButton("취소", { vm.back() })
    }
    Spacer(Modifier.height(12.dp))
    SmallLink(if (s.draftQuietOn) "쉬는 시간 정하지 않기" else "쉬는 시간 다시 정하기") { vm.setDraftQuietOn(!s.draftQuietOn) }
}

@Composable
private fun RoadSetting(s: UiState, vm: MobileViewModel) {
    TopLabel("선호하는 도로 설정")
    Title("운전을 피하고 싶은\n도로 종류를 정해 주세요")
    Spacer(Modifier.height(8.dp))
    Body("경로에 포함되면 알려드려요.")
    Spacer(Modifier.height(20.dp))
    ChoiceRow("고속도로", if (s.draftAvoidHighway) "피하기로 했어요" else "누르면 피하기로 정해요", s.draftAvoidHighway, {
        vm.setDraftAvoidHighway(!s.draftAvoidHighway)
    })
    Buttons {
        BigButton("저장", vm::saveRoad, primary = true)
        BigButton("취소", { vm.back() })
    }
}

// ---------- T01~T03 모의 할인권 ----------

@Composable
private fun Coupon(s: UiState, vm: MobileViewModel) {
    val d = s.account ?: return
    TopLabel("택시 혜택")
    Title(if (s.couponsLeft > 0) "이번 달 택시 할인은\n${s.couponsLeft}번 남았어요" else "이번 달 택시 할인을\n모두 썼어요")
    Spacer(Modifier.height(18.dp))
    InfoBox("택시 할인권 · 시연용", "${won(CouponLedger.AMOUNT_WON)}", Modifier.fillMaxWidth())
    Spacer(Modifier.height(8.dp))
    Body("이번 달 ${s.couponsLeft}번 남음 · 한 달에 ${CouponLedger.MONTHLY_LIMIT}번까지", size = 16)
    Spacer(Modifier.height(16.dp))
    // T01-01: 운전 자제 조건에 해당할 때 쓰는 혜택. 확인할 수 없는 조건을 해당으로 처리하지 않는다.
    if (s.inQuietHours) {
        InfoBox("지금 · ${s.now.label()}", "운전을 쉬기로 한 시간이에요", Modifier.fillMaxWidth())
    } else {
        InfoBox(
            "지금 · ${s.now.label()}",
            if (d.quietHours != null) "운전을 쉬는 시간이 아니에요" else "운전을 쉬는 시간을 정하지 않았어요",
            Modifier.fillMaxWidth(),
        )
        // 남은 할인권이 없으면 쓸 수 없으니 확인 항목도 보이지 않는다.
        if (s.couponsLeft > 0) {
            Spacer(Modifier.height(10.dp))
            ChoiceRow(
                "차량에서 운전 자제 안내를 받았어요", "고속도로 포함 경로 등 차량에서 받은 안내",
                s.confirmedVehicleNotice, { vm.confirmVehicleNotice(!s.confirmedVehicleNotice) },
            )
        }
    }
    Buttons {
        BigButton("이 할인권 사용하기", vm::chooseCoupon, primary = true, enabled = s.canUseCoupon || s.couponsLeft == 0)
        BigButton("홈으로", vm::home)
    }
    Spacer(Modifier.height(12.dp))
    Body("시연용 할인권이에요. 실제 택시 요금은 결제되지 않아요.", size = 15)
    if (!s.online) Body("인터넷에 연결되면 할인권을 쓸 수 있어요.", size = 15, color = Sp.Warn)
}

@Composable
private fun UseCoupon(vm: MobileViewModel) {
    TopLabel("모의 할인권 사용")
    Title("선택한 할인권을\n확인해 주세요")
    Spacer(Modifier.height(18.dp))
    InfoBox("택시 할인권 · 시연용", "${won(CouponLedger.AMOUNT_WON)} 할인", Modifier.fillMaxWidth())
    Spacer(Modifier.height(12.dp))
    Body("택시 이용을 마쳤다고 입력하면\n할인권 1장이 사용돼요.", color = Sp.Ink)
    Buttons {
        BigButton("이용 완료 입력", vm::completeUse, primary = true)
        BigButton("취소", { vm.back() })
    }
    Spacer(Modifier.height(12.dp))
    Body("시연용 화면이에요. 취소하면 할인권은 그대로 남아요.", size = 15)
}

@Composable
private fun UseDone(s: UiState, vm: MobileViewModel) {
    TopLabel("모의 할인권 사용")
    Title("할인권 1장을\n사용했어요")
    Spacer(Modifier.height(18.dp))
    InfoBox("사용한 할인 금액", won(s.lastUse?.amountWon ?: CouponLedger.AMOUNT_WON), Modifier.fillMaxWidth())
    Spacer(Modifier.height(10.dp))
    InfoBox("이번 달 남은 할인권", "${s.couponsLeft}장", Modifier.fillMaxWidth())
    Spacer(Modifier.height(8.dp))
    Body("이번 달에 남은 할인권은 다음 달에 쓸 수 없어요.", size = 15)
    Buttons {
        BigButton("사용 내역 보기", vm::openHistory, primary = true)
        BigButton("홈으로", vm::home)
    }
}

// ---------- M05 사용 내역 ----------

@Composable
private fun History(s: UiState, vm: MobileViewModel) {
    TopLabel("혜택 사용 내역")
    Title(if (s.history.isEmpty()) "이번 달에는\n쓴 할인권이 없어요" else "이번 달 할인권\n${s.history.size}장 사용")
    Spacer(Modifier.height(18.dp))
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        s.history.forEach { u ->
            InfoBox(dateTime(u.usedAt), "${won(u.amountWon)} 할인 · 시연용", Modifier.fillMaxWidth())
        }
    }
    Spacer(Modifier.height(10.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Body("남은 할인권 ${s.couponsLeft}장", color = Sp.Ink)
        Spacer(Modifier.padding(4.dp))
        SourceTag("시연용 내역")
    }
    Buttons { BigButton("홈으로", vm::home, primary = true) }
}

// ---------- 공통 ----------

@Composable
private fun SmallLink(text: String, onClick: () -> Unit) {
    Box(Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(horizontal = 6.dp, vertical = 10.dp)) {
        Text(text, fontSize = 16.sp, color = Sp.Sub, textAlign = TextAlign.Center)
    }
}

private fun hm(ms: Long?) = ms?.let {
    java.time.Instant.ofEpochMilli(it).atZone(ZoneId.of("Asia/Seoul")).toLocalTime().let { t -> TimeOfDay.of(t.hour, t.minute).label() }
} ?: "-"

private fun won(n: Int) = "%,d원".format(n)

private val DATE = DateTimeFormatter.ofPattern("M월 d일 · HH:mm")

private fun dateTime(millis: Long) = DATE.format(Instant.ofEpochMilli(millis).atZone(ZoneId.of("Asia/Seoul")))
