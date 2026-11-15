package com.silverpass.core.model

/** 하루 중 시각(분 단위). 날짜와 무관한 비교에 쓴다. */
@JvmInline
value class TimeOfDay(val minutes: Int) : Comparable<TimeOfDay> {
    init {
        require(minutes in 0 until 24 * 60) { "0..1439 범위가 아님: $minutes" }
    }

    val hour get() = minutes / 60
    val minute get() = minutes % 60

    override fun compareTo(other: TimeOfDay) = minutes.compareTo(other.minutes)

    /** "오후 6시", "오전 6시 30분" — 고령 사용자에게 24시간제보다 익숙한 표기 */
    fun label(): String {
        val ampm = if (hour < 12) "오전" else "오후"
        val h12 = when (val h = hour % 12) { 0 -> 12; else -> h }
        return if (minute == 0) "$ampm ${h12}시" else "$ampm ${h12}시 ${minute}분"
    }

    fun plusMinutes(delta: Int) = TimeOfDay(Math.floorMod(minutes + delta, 24 * 60))

    companion object {
        fun of(hour: Int, minute: Int = 0) = TimeOfDay(hour * 60 + minute)
    }
}

/** "오후 6시" → "오후 6시예요", "오후 6시 30분" → "오후 6시 30분이에요". 받침 유무로 고른다. */
fun withCopula(word: String): String {
    val c = word.lastOrNull() ?: return word
    val hasBatchim = c in '가'..'힣' && (c - '가') % 28 != 0
    return word + if (hasBatchim) "이에요" else "예요"
}

/**
 * 운전을 쉬는 시간. 시작 포함·끝 제외([start, end)). start > end면 자정을 넘는 구간(예: 18:00~06:00).
 * start == end는 의미가 모호해 저장하지 않는다(설정 화면에서 막음).
 */
data class QuietHours(val start: TimeOfDay, val end: TimeOfDay) {
    init {
        require(start != end) { "시작과 끝이 같을 수 없음" }
    }

    val crossesMidnight get() = start > end

    operator fun contains(t: TimeOfDay): Boolean =
        if (crossesMidnight) t >= start || t < end else t >= start && t < end

    fun label(): String = "${start.label()} ~ ${if (crossesMidnight) "다음 날 " else ""}${end.label()}"
}

/** 사용자가 직접 정한 운전 자제 조건. 법적 면허 조건이 아니다(V02-04). */
data class Driver(
    val id: String,
    val name: String,
    val quietHours: QuietHours?,
    val avoidHighway: Boolean,
) {
    val initial get() = name.take(1)
}

enum class RoadType { HIGHWAY, GENERAL }

data class RoadSegment(val name: String, val type: RoadType)

/**
 * 경로 정보. 1차는 내비게이션 연동 없이 테스트 경로로 시연하므로 [isTestData]로 출처를 화면에 표시한다(E02-01).
 */
data class Route(
    val id: String,
    val label: String,
    val segments: List<RoadSegment>,
    val isTestData: Boolean,
    /** 실제 내비 경로일 때 고속도로로 본 근거(예: "휴게소 3곳 경유"). 화면에 출처와 함께 보여 준다. */
    val evidence: List<String> = emptyList(),
    val durationSeconds: Int = 0,
) {
    val highways get() = segments.filter { it.type == RoadType.HIGHWAY }
}

/** 모의 할인권 사용 1건. [requestId]가 같으면 같은 사용 건이다(중복 차감 방지). */
data class CouponUse(
    val requestId: String,
    val driverId: String,
    val month: String,
    val usedAtMillis: Long,
    val amountWon: Int,
)
