package com.silverpass.core.sync

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** 기기에 저장한 계정 토큰. 휴대전화에는 여러 계정(가족·시연)을 둘 수 있다. */
data class DeviceAccount(val id: String, val name: String, val token: String)

/**
 * 토큰과 마지막으로 받은 서버 데이터를 기기에 저장한다(앱 전용 SharedPreferences).
 * 연결이 끊기면 마지막 값을 보여 주고 "마지막 동기화 시각"을 함께 표시한다(E02-03).
 */
class SyncCache(context: Context, name: String) {
    private val prefs = context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE)

    // ---------- 휴대전화: 계정 목록 ----------
    fun deviceAccounts(): List<DeviceAccount> = runCatching {
        JSONArray(prefs.getString("accounts", "[]")).objects().map { DeviceAccount(it.getString("id"), it.getString("name"), it.getString("token")) }
    }.getOrDefault(emptyList())

    fun addDeviceAccount(a: DeviceAccount) {
        val list = deviceAccounts().filter { it.id != a.id } + a
        prefs.edit().putString("accounts", JSONArray(list.map { JSONObject().put("id", it.id).put("name", it.name).put("token", it.token) }).toString()).commit()
    }

    var currentAccountId: String?
        get() = prefs.getString("current", null)
        set(v) { prefs.edit().putString("current", v).apply() }

    // ---------- 차량: 차량 토큰 ----------
    var vehicleToken: String?
        get() = prefs.getString("vehicleToken", null)
        set(v) { prefs.edit().putString("vehicleToken", v).commit() }

    // ---------- 마지막 서버 데이터 ----------
    fun saveAccounts(key: String, accounts: List<RemoteAccount>, at: Long) {
        prefs.edit()
            .putString("cache:$key", JSONArray(accounts.map(SyncApi::accountJson)).toString())
            .putLong("cacheAt:$key", at)
            .apply()
    }

    fun cachedAccounts(key: String): Pair<List<RemoteAccount>, Long>? {
        val raw = prefs.getString("cache:$key", null) ?: return null
        return runCatching { JSONArray(raw).objects().map(SyncApi::account) to prefs.getLong("cacheAt:$key", 0) }.getOrNull()
    }

    fun clear() { prefs.edit().clear().commit() }
}
