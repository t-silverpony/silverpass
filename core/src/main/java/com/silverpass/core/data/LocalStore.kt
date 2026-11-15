package com.silverpass.core.data

import android.content.Context
import com.silverpass.core.domain.CouponRepository
import com.silverpass.core.model.CouponUse
import com.silverpass.core.model.Driver
import com.silverpass.core.model.QuietHours
import com.silverpass.core.model.TimeOfDay
import org.json.JSONArray
import org.json.JSONObject

/**
 * 1차 접수 범위의 로컬 저장소(SharedPreferences + JSON). 앱마다 따로 저장한다.
 * 2차에서 같은 계정의 설정·내역을 서버로 동기화할 때 이 클래스를 서버 구현으로 바꾼다.
 */
class LocalStore(context: Context) : CouponRepository {
    private val prefs = context.applicationContext.getSharedPreferences("silverpass", Context.MODE_PRIVATE)

    /** 시연 도구에서 켜면 다음 저장 한 번을 실패시킨다(실패 팝업 시연용). */
    @Volatile
    var failNextSave = false

    fun drivers(): List<Driver> {
        val raw = prefs.getString(KEY_DRIVERS, null) ?: return DemoData.drivers
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { driverFrom(arr.getJSONObject(it)) }
        }.getOrDefault(DemoData.drivers)
    }

    fun driver(id: String) = drivers().firstOrNull { it.id == id }

    /** 같은 id의 운전자를 바꾼다. 다른 계정의 설정은 건드리지 않는다. */
    fun saveDriver(driver: Driver): Boolean {
        if (consumeFailure()) return false
        val list = drivers().map { if (it.id == driver.id) driver else it }
        return prefs.edit().putString(KEY_DRIVERS, JSONArray(list.map(::driverJson)).toString()).commit()
    }

    var currentDriverId: String?
        get() = prefs.getString(KEY_CURRENT, null)
        set(v) {
            prefs.edit().putString(KEY_CURRENT, v).apply()
        }

    override fun all(): List<CouponUse> {
        val raw = prefs.getString(KEY_COUPONS, null) ?: return emptyList()
        val arr = JSONArray(raw)
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            CouponUse(o.getString("req"), o.getString("driver"), o.getString("month"), o.getLong("at"), o.getInt("won"))
        }
    }

    override fun add(use: CouponUse): Boolean {
        if (consumeFailure()) return false
        val arr = JSONArray(prefs.getString(KEY_COUPONS, "[]"))
        arr.put(
            JSONObject().put("req", use.requestId).put("driver", use.driverId).put("month", use.month)
                .put("at", use.usedAtMillis).put("won", use.amountWon),
        )
        return prefs.edit().putString(KEY_COUPONS, arr.toString()).commit()
    }

    fun reset() {
        prefs.edit().clear().commit()
    }

    private fun consumeFailure(): Boolean {
        if (!failNextSave) return false
        failNextSave = false
        return true
    }

    private fun driverJson(d: Driver) = JSONObject()
        .put("id", d.id).put("name", d.name).put("avoidHighway", d.avoidHighway)
        .apply { d.quietHours?.let { put("quietStart", it.start.minutes).put("quietEnd", it.end.minutes) } }

    private fun driverFrom(o: JSONObject) = Driver(
        id = o.getString("id"),
        name = o.getString("name"),
        quietHours = if (o.has("quietStart")) {
            QuietHours(TimeOfDay(o.getInt("quietStart")), TimeOfDay(o.getInt("quietEnd")))
        } else {
            null
        },
        avoidHighway = o.getBoolean("avoidHighway"),
    )

    private companion object {
        const val KEY_DRIVERS = "drivers"
        const val KEY_CURRENT = "currentDriver"
        const val KEY_COUPONS = "coupons"
    }
}
