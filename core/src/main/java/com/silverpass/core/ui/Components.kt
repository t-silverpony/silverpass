package com.silverpass.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.silverpass.core.model.TimeOfDay

/** 화면설계 시안의 회색조 팔레트. 고령 사용자 가독성을 위해 대비를 높게 둔다. */
object Sp {
    val Ink = Color(0xFF1F2024)
    val Sub = Color(0xFF5F6168)
    val Primary = Color(0xFF3E4047)
    val Surface = Color(0xFFF5F6F8)
    val Box = Color(0xFFEBECEF)
    val Line = Color(0xFFD9DADF)
    val Ok = Color(0xFF16A34A)
    val Warn = Color(0xFFD92D20)
    val Tag = Color(0xFFFFF4D6)
    val TagInk = Color(0xFF8A5A00)
}

private val colors: ColorScheme = lightColorScheme(
    primary = Sp.Primary, onPrimary = Color.White, background = Color.White, surface = Color.White,
    onSurface = Sp.Ink, onBackground = Sp.Ink,
)

@Composable
fun SilverPassTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = colors, content = content)

@Composable
fun Title(text: String, modifier: Modifier = Modifier, size: Int = 30) =
    Text(text, modifier, fontSize = size.sp, lineHeight = (size * 1.35).sp, fontWeight = FontWeight.Bold, color = Sp.Ink)

@Composable
fun Body(text: String, modifier: Modifier = Modifier, color: Color = Sp.Sub, size: Int = 18) =
    Text(text, modifier, fontSize = size.sp, lineHeight = (size * 1.45).sp, color = color)

/** 큰 버튼. primary는 진한 회색, 아니면 연한 회색. 높이 64dp로 정차 중 터치가 쉽게 한다. */
@Composable
fun BigButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    enabled: Boolean = true,
    height: Dp = 64.dp,
) {
    val bg = when {
        !enabled -> Sp.Box
        primary -> Sp.Primary
        else -> Sp.Box
    }
    val fg = when {
        !enabled -> Sp.Sub.copy(alpha = 0.6f)
        primary -> Color.White
        else -> Sp.Ink
    }
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(14.dp))
            .background(bg)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = fg, fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** 회색 상자: 작은 라벨 + 큰 값. 예) "운전을 쉬는 시간 · 오후 6시 ~ 다음 날 오전 6시" / "지금은 오후 6시 30분이에요" */
@Composable
fun InfoBox(label: String, value: String, modifier: Modifier = Modifier, valueColor: Color = Sp.Ink, onClick: (() -> Unit)? = null) {
    Column(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Sp.Box)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        Text(label, fontSize = 16.sp, color = Sp.Sub)
        Spacer(Modifier.height(6.dp))
        Text(value, fontSize = 23.sp, fontWeight = FontWeight.Bold, color = valueColor, lineHeight = 30.sp)
    }
}

/** "테스트 데이터", "시연용" 같은 출처 표시(E02-01). 모의 정보를 실제 연동으로 오해하지 않게 한다. */
@Composable
fun SourceTag(text: String) {
    Text(
        text,
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Sp.Tag)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        color = Sp.TagInk, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
    )
}

@Composable
fun Avatar(initial: String, size: Dp = 44.dp) {
    Box(Modifier.size(size).clip(RoundedCornerShape(12.dp)).background(Sp.Primary), contentAlignment = Alignment.Center) {
        Text(initial, color = Color.White, fontSize = (size.value * 0.45f).sp, fontWeight = FontWeight.Bold)
    }
}

/** 선택형 항목(라디오). 운전자 선택, 테스트 계정 선택에 쓴다. */
@Composable
fun ChoiceRow(title: String, subtitle: String, selected: Boolean, onClick: () -> Unit, enabled: Boolean = true) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Sp.Surface)
            .border(2.dp, if (selected) Sp.Ok else Color.Transparent, RoundedCornerShape(14.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(28.dp).clip(CircleShape).background(if (selected) Sp.Ok else Color.White)
                .border(2.dp, if (selected) Sp.Ok else Sp.Line, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) Box(Modifier.size(12.dp).clip(CircleShape).background(Color.White))
        }
        Spacer(Modifier.width(16.dp))
        Column {
            Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Sp.Ink)
            Text(subtitle, fontSize = 16.sp, color = Sp.Sub)
        }
    }
}

/** 현재 화면 위에 뜨는 팝업(5쪽 팝업 모음). 버튼은 다음에 할 행동을 그대로 적는다. */
@Composable
fun Popup(
    title: String,
    body: String,
    onDismiss: () -> Unit,
    content: (@Composable ColumnScope.() -> Unit)? = null,
    buttons: @Composable RowScope.() -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .widthIn(max = 460.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .background(Color.White)
                .padding(28.dp),
        ) {
            Title(title, size = 25)
            Spacer(Modifier.height(10.dp))
            Body(body)
            content?.let {
                Spacer(Modifier.height(16.dp))
                it()
            }
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), content = buttons)
        }
    }
}

/** 시·분 선택 팝업(M02-01). 15분 단위. 취소하면 이전 값을 유지한다. */
@Composable
fun TimePickerPopup(
    title: String,
    initial: TimeOfDay,
    onDismiss: () -> Unit,
    onPick: (TimeOfDay) -> Unit,
    body: String = "시간을 누른 뒤 '이 시간으로 선택'을 눌러 주세요.",
) {
    var hour by remember { mutableStateOf(initial.hour) }
    var minute by remember { mutableStateOf(initial.minute - initial.minute % 15) }
    Popup(
        title, body, onDismiss,
        content = {
            Row(Modifier.fillMaxWidth().height(240.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                WheelColumn((0 until 24).toList(), hour, { TimeOfDay.of(it).label() }, { hour = it }, Modifier.weight(1.4f))
                WheelColumn(listOf(0, 15, 30, 45), minute, { "%02d분".format(it) }, { minute = it }, Modifier.weight(1f))
            }
        },
    ) {
        BigButton("취소", onDismiss, Modifier.weight(1f), height = 56.dp)
        BigButton("이 시간으로 선택", { onPick(TimeOfDay.of(hour, minute)) }, Modifier.weight(1.6f), primary = true, height = 56.dp)
    }
}

@Composable
private fun WheelColumn(values: List<Int>, selected: Int, label: (Int) -> String, onSelect: (Int) -> Unit, modifier: Modifier) {
    val state = rememberLazyListState((values.indexOf(selected) - 2).coerceAtLeast(0))
    LazyColumn(modifier.clip(RoundedCornerShape(14.dp)).background(Sp.Surface), state = state) {
        items(values) { v ->
            val on = v == selected
            Text(
                label(v),
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
                    .background(if (on) Sp.Primary else Color.Transparent)
                    .clickable { onSelect(v) }
                    .padding(vertical = 13.dp),
                textAlign = TextAlign.Center, fontSize = 20.sp,
                fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                color = if (on) Color.White else Sp.Ink,
            )
        }
    }
}
