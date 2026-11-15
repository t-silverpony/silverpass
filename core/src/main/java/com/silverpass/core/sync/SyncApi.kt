package com.silverpass.core.sync

import com.silverpass.core.BuildConfig
import com.silverpass.core.model.Driver
import com.silverpass.core.model.QuietHours
import com.silverpass.core.model.TimeOfDay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** 서버 계정(운전자) 한 명. 운전 조건과 이번 달 할인권 요약을 함께 받는다. */
data class RemoteAccount(
    val id: String,
    val name: String,
    val quietHours: QuietHours?,
    val avoidHighway: Boolean,
    val version: Int,
    val updatedAt: Long,
    val coupons: CouponSummary,
    /** 휴대전화에서 저장한 주행 계획(M07). 차량에서 내비에 바로 적용한다. */
    val plan: PlanView? = null,
) {
    fun toDriver() = Driver(id, name, quietHours, avoidHighway)
}

data class Place(val label: String, val lat: Double, val lon: Double)

/** 네이버 길찾기 옵션 하나. path는 지도에 그릴 [lat, lon] 목록(서버에서 줄인 것). */
data class RouteOptionView(
    val option: String,
    val label: String,
    val naviOption: String,
    val distanceM: Int,
    val durationS: Int,
    val tollFare: Int,
    val highways: List<String>,
    val path: List<Pair<Double, Double>>,
)

data class PlanView(
    val origin: Place,
    val destination: Place,
    val departAt: Long,
    val option: String,
    val optionLabel: String,
    val naviOption: String,
    val durationS: Int,
    val distanceM: Int,
    val tollFare: Int,
    val highways: List<String>,
)

data class CouponUseView(val requestId: String, val usedAt: Long, val amountWon: Int)

data class CouponSummary(val month: String, val amountWon: Int, val monthlyLimit: Int, val remaining: Int, val uses: List<CouponUseView>)

data class VehicleView(val id: String, val name: String)

/** 서버 호출 결과. 네트워크 실패와 서버가 거절한 경우를 나눠서 화면 안내를 다르게 한다. */
sealed interface ApiResult<out T> {
    data class Ok<T>(val value: T) : ApiResult<T>

    /** 서버가 응답했지만 거절함. [error]는 서버 오류 코드(version_conflict, expired_code 등). */
    data class Rejected(val status: Int, val error: String, val body: JSONObject) : ApiResult<Nothing>

    /** 연결 실패·시간 초과. 사용자가 다시 시도할 수 있다. */
    data class Offline(val reason: String) : ApiResult<Nothing>
}

/**
 * 실버패스 동기화 서버 클라이언트(server/README.md의 API). 의존성 없이 HttpURLConnection + org.json.
 * HTTPS만 쓴다(Android 기본 정책상 평문 HTTP 차단).
 */
class SyncApi(private val baseUrl: String = BuildConfig.SYNC_BASE_URL) {
    /** 시연 도구: 다음 요청 한 번을 연결 실패로 흉내 낸다(오류 팝업 시연). */
    @Volatile
    var failNext = false

    /** 시연 도구: 다음 쓰기(저장·사용) 요청 한 번만 연결 실패로 흉내 낸다. 화면을 열 때의 새로고침(GET)은 그대로 둔다. */
    @Volatile
    var failNextWrite = false

    // ---------- 휴대전화(계정 토큰) ----------
    suspend fun createAccount(name: String) =
        call("POST", "/v1/accounts", body = JSONObject().put("name", name)) { it.getString("token") to account(it.getJSONObject("account")) }

    suspend fun me(token: String) = call("GET", "/v1/me", token) { account(it) }

    suspend fun updateConditions(token: String, quietHours: QuietHours?, avoidHighway: Boolean, version: Int) =
        call(
            "PUT", "/v1/me/conditions", token,
            JSONObject()
                .put("quietHours", quietHours?.let { JSONObject().put("start", it.start.minutes).put("end", it.end.minutes) } ?: JSONObject.NULL)
                .put("avoidHighway", avoidHighway)
                .put("version", version),
        ) { account(it) }

    suspend fun redeem(token: String, requestId: String) =
        call("POST", "/v1/me/coupons/redeem", token, JSONObject().put("requestId", requestId)) { coupons(it.getJSONObject("coupons")) }

    // ---------- 주행 계획(M07) ----------
    suspend fun geocode(token: String, query: String) =
        call("POST", "/v1/plan/geocode", token, JSONObject().put("query", query)) { j ->
            j.getJSONArray("results").objects().map { Place(it.getString("address"), it.getDouble("lat"), it.getDouble("lon")) }
        }

    suspend fun routes(token: String, start: Place, goal: Place) =
        call(
            "POST", "/v1/plan/routes", token,
            JSONObject().put("start", JSONObject().put("lat", start.lat).put("lon", start.lon))
                .put("goal", JSONObject().put("lat", goal.lat).put("lon", goal.lon)),
        ) { j -> j.getJSONArray("routes").objects().map(::routeOption) }

    suspend fun savePlan(token: String, origin: Place, destination: Place, departAt: Long, route: RouteOptionView) =
        call(
            "PUT", "/v1/me/plan", token,
            JSONObject().put("origin", placeJson(origin)).put("destination", placeJson(destination)).put("departAt", departAt)
                .put("option", route.option).put("durationS", route.durationS).put("distanceM", route.distanceM)
                .put("tollFare", route.tollFare).put("highways", JSONArray(route.highways)),
        ) { j -> plan(j.getJSONObject("plan")) }

    suspend fun deletePlan(token: String) = call("DELETE", "/v1/me/plan", token) { }

    suspend fun myVehicles(token: String) =
        call("GET", "/v1/me/vehicles", token) { j -> j.getJSONArray("vehicles").objects().map { VehicleView(it.getString("id"), it.getString("name")) } }

    suspend fun claimPairing(token: String, code: String) =
        call("POST", "/v1/pairings/claim", token, JSONObject().put("code", code)) { j ->
            j.getJSONObject("vehicle").let { VehicleView(it.getString("id"), it.getString("name")) }
        }

    suspend fun unlinkVehicle(token: String, vehicleId: String) = call("DELETE", "/v1/me/vehicles/$vehicleId", token) { }

    // ---------- 차량(차량 토큰) ----------
    suspend fun registerVehicle(name: String) =
        call("POST", "/v1/vehicles", body = JSONObject().put("name", name)) { it.getString("token") to it.getJSONObject("vehicle").getString("id") }

    suspend fun issuePairingCode(token: String) =
        call("POST", "/v1/vehicle/pairing-codes", token) { it.getString("code") to it.getLong("expiresAt") }

    suspend fun linkedAccounts(token: String) =
        call("GET", "/v1/vehicle/accounts", token) { j -> j.getJSONArray("accounts").objects().map(::account) }

    // ---------- 공통 ----------
    private suspend fun <T> call(
        method: String,
        path: String,
        token: String? = null,
        body: JSONObject? = null,
        parse: (JSONObject) -> T,
    ): ApiResult<T> = withContext(Dispatchers.IO) {
        if (failNext || (failNextWrite && method != "GET")) {
            if (failNext) failNext = false else failNextWrite = false
            return@withContext ApiResult.Offline("시연: 연결 실패")
        }
        val conn = (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 6_000
            readTimeout = 8_000
            useCaches = false
            setRequestProperty("Accept", "application/json")
            token?.let { setRequestProperty("Authorization", "Bearer $it") }
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }
        try {
            body?.let { conn.outputStream.use { o -> o.write(it.toString().toByteArray()) } }
            val status = conn.responseCode
            val text = (if (status >= 400) conn.errorStream else conn.inputStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            val json = if (text.isBlank()) JSONObject() else JSONObject(text)
            if (status in 200..299) ApiResult.Ok(parse(json)) else ApiResult.Rejected(status, json.optString("error", "http_$status"), json)
        } catch (e: IOException) {
            ApiResult.Offline(e.message ?: e.javaClass.simpleName)
        } catch (e: org.json.JSONException) {
            ApiResult.Offline("응답 형식 오류")
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        fun account(j: JSONObject): RemoteAccount {
            val c = j.getJSONObject("conditions")
            val q = c.optJSONObject("quietHours")
            return RemoteAccount(
                id = j.getString("id"),
                name = j.getString("name"),
                quietHours = q?.let { QuietHours(TimeOfDay(it.getInt("start")), TimeOfDay(it.getInt("end"))) },
                avoidHighway = c.getBoolean("avoidHighway"),
                version = j.getInt("version"),
                updatedAt = j.optLong("updatedAt"),
                coupons = coupons(j.getJSONObject("coupons")),
                plan = j.optJSONObject("plan")?.let(::plan),
            )
        }

        private fun place(j: JSONObject) = Place(j.getString("label"), j.getDouble("lat"), j.getDouble("lon"))

        private fun placeJson(p: Place) = JSONObject().put("label", p.label).put("lat", p.lat).put("lon", p.lon)

        fun plan(j: JSONObject) = PlanView(
            origin = place(j.getJSONObject("origin")),
            destination = place(j.getJSONObject("destination")),
            departAt = j.getLong("departAt"),
            option = j.getString("option"),
            optionLabel = j.getString("optionLabel"),
            naviOption = j.getString("naviOption"),
            durationS = j.getInt("durationS"),
            distanceM = j.getInt("distanceM"),
            tollFare = j.getInt("tollFare"),
            highways = j.getJSONArray("highways").let { a -> (0 until a.length()).map { a.getString(it) } },
        )

        private fun planJson(p: PlanView) = JSONObject()
            .put("origin", placeJson(p.origin)).put("destination", placeJson(p.destination)).put("departAt", p.departAt)
            .put("option", p.option).put("optionLabel", p.optionLabel).put("naviOption", p.naviOption)
            .put("durationS", p.durationS).put("distanceM", p.distanceM).put("tollFare", p.tollFare).put("highways", JSONArray(p.highways))

        fun routeOption(j: JSONObject) = RouteOptionView(
            option = j.getString("option"),
            label = j.getString("label"),
            naviOption = j.getString("naviOption"),
            distanceM = j.getInt("distanceM"),
            durationS = j.getInt("durationS"),
            tollFare = j.getInt("tollFare"),
            highways = j.getJSONArray("highways").let { a -> (0 until a.length()).map { a.getString(it) } },
            path = j.getJSONArray("path").let { a -> (0 until a.length()).map { a.getJSONArray(it).let { p -> p.getDouble(0) to p.getDouble(1) } } },
        )

        fun coupons(j: JSONObject) = CouponSummary(
            month = j.getString("month"),
            amountWon = j.getInt("amountWon"),
            monthlyLimit = j.getInt("monthlyLimit"),
            remaining = j.getInt("remaining"),
            uses = j.getJSONArray("uses").objects().map { CouponUseView(it.getString("requestId"), it.getLong("usedAt"), it.getInt("amountWon")) },
        )

        fun accountJson(a: RemoteAccount): JSONObject = JSONObject()
            .put("id", a.id).put("name", a.name).put("version", a.version).put("updatedAt", a.updatedAt)
            .put(
                "conditions",
                JSONObject()
                    .put("quietHours", a.quietHours?.let { JSONObject().put("start", it.start.minutes).put("end", it.end.minutes) } ?: JSONObject.NULL)
                    .put("avoidHighway", a.avoidHighway),
            )
            .put(
                "coupons",
                JSONObject().put("month", a.coupons.month).put("amountWon", a.coupons.amountWon)
                    .put("monthlyLimit", a.coupons.monthlyLimit).put("remaining", a.coupons.remaining)
                    .put("uses", JSONArray(a.coupons.uses.map { JSONObject().put("requestId", it.requestId).put("usedAt", it.usedAt).put("amountWon", it.amountWon) })),
            )
            .apply { a.plan?.let { put("plan", planJson(it)) } }
    }
}

internal fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
