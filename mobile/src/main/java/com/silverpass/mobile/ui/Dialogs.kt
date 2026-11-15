package com.silverpass.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.silverpass.core.model.TimeOfDay
import com.silverpass.core.ui.BigButton
import com.silverpass.core.ui.ChoiceRow
import com.silverpass.core.ui.Popup
import com.silverpass.core.ui.TimePickerPopup
import com.silverpass.mobile.Dialog
import com.silverpass.mobile.MobileViewModel
import com.silverpass.mobile.Screen
import com.silverpass.mobile.UiState

@Composable
fun Dialogs(s: UiState, vm: MobileViewModel) {
    val close = { vm.showDialog(Dialog.NONE) }
    when (s.dialog) {
        Dialog.NONE -> Unit

        Dialog.PICK_START -> TimePickerPopup("쉬기 시작하는 시간", s.draftStart, close, vm::pickStart)
        Dialog.PICK_END -> TimePickerPopup("쉬는 시간이 끝나는 때", s.draftEnd, close, vm::pickEnd)
        Dialog.PICK_DEPART -> TimePickerPopup("출발 시각", s.planTime, close, vm::pickDepart)

        Dialog.SAVE_FAILED -> Popup(
            if (s.screen == Screen.M02_TIME) "시간을 저장하지 못했어요" else "설정을 저장하지 못했어요",
            "선택한 값은 그대로 있어요.\n다시 저장해 주세요.",
            close,
        ) {
            BigButton("닫기", close, Modifier.weight(1f), height = 56.dp)
            BigButton("다시 저장", vm::retrySave, Modifier.weight(1.4f), primary = true, height = 56.dp)
        }

        // 다른 기기(가족 휴대전화 등)에서 먼저 바꾼 경우: 최신 값을 불러와 다시 확인하게 한다.
        Dialog.SAVE_CONFLICT -> Popup(
            "다른 기기에서 먼저 바꿨어요",
            "최신 설정을 불러왔어요.\n화면의 값을 확인한 뒤 다시 저장해 주세요.",
            close,
        ) {
            BigButton("확인", close, Modifier.weight(1f), primary = true, height = 56.dp)
        }

        Dialog.UNLINK_CONFIRM -> Popup(
            "차량 연결을 해제할까요?",
            "'${s.unlinkTarget?.name ?: "차량"}'에서 이 계정의 운전 조건과 할인권 정보를 더 이상 볼 수 없어요.",
            close,
        ) {
            BigButton("취소", close, Modifier.weight(1f), height = 56.dp)
            BigButton("연결 해제", vm::unlink, Modifier.weight(1.4f), primary = true, height = 56.dp)
        }

        Dialog.COUPON_EMPTY -> Popup(
            "이번 달 할인권을 모두 썼어요",
            "한 달에 2번 사용할 수 있어요.\n다음 달에 다시 이용해 주세요.",
            close,
        ) {
            BigButton("확인", close, Modifier.weight(1f), primary = true, height = 56.dp)
        }

        Dialog.REDEEM_FAILED -> Popup(
            "사용 결과를 확인하지 못했어요",
            "'다시 확인'을 눌러\n할인권 사용 결과를 확인해 주세요.",
            close,
        ) {
            BigButton("닫기", close, Modifier.weight(1f), height = 56.dp)
            BigButton("다시 확인", vm::recheckUse, Modifier.weight(1.4f), primary = true, height = 56.dp)
        }

        Dialog.DEMO_TOOLS -> DemoTools(s, vm, close)
    }
}

/** 시연용: 기준 시각 고정, 저장 실패 흉내, 데이터 초기화. */
@Composable
private fun DemoTools(s: UiState, vm: MobileViewModel, close: () -> Unit) {
    var custom by remember { mutableStateOf(false) }
    if (custom) {
        TimePickerPopup("기준 시각 고정", s.fixedTime ?: TimeOfDay.of(18, 30), { custom = false }, { vm.setFixedTime(it); custom = false })
        return
    }
    Popup("시연 도구", "시연 화면을 만들기 위한 설정이에요.", close, content = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ChoiceRow("실제 시각 사용", "기기 시계", s.fixedTime == null, { vm.setFixedTime(null) })
            ChoiceRow("시각 고정", s.fixedTime?.label() ?: "누르면 고를 수 있어요", s.fixedTime != null, { custom = true })
            ChoiceRow("다음 저장·사용 요청 실패시키기", "연결 실패 팝업 시연(한 번)", false, { vm.failNextRequest() })
        }
    }) {
        BigButton("이 기기 초기화", { vm.resetDevice() }, Modifier.weight(1f), height = 52.dp)
        BigButton("닫기", close, Modifier.weight(1f), primary = true, height = 52.dp)
    }
}
