package com.silverpass.mobile.ui

import android.graphics.Color as AColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.naver.maps.geometry.LatLng
import com.naver.maps.geometry.LatLngBounds
import com.naver.maps.map.CameraUpdate
import com.naver.maps.map.MapView
import com.naver.maps.map.NaverMap
import com.naver.maps.map.NaverMapSdk
import com.naver.maps.map.overlay.Marker
import com.naver.maps.map.overlay.PathOverlay
import com.silverpass.core.sync.RouteOptionView

/** 지도 위에 올린 것들. 경로가 바뀌면 같은 오버레이를 옮겨 쓴다. */
private class RouteOverlays {
    var map: NaverMap? = null
    val path = PathOverlay()
    val start = Marker()
    val end = Marker()
}

/**
 * 네이버 지도(Dynamic Map)에 선택한 경로를 그린다. Client ID는 빌드 때 manifest에 들어간다.
 * 인증이 실패하면(패키지 미등록·키 오류) [onAuthFailed]로 알려 경로 그림으로 돌아가게 한다.
 */
@Composable
fun RouteMapView(route: RouteOptionView, modifier: Modifier, onAuthFailed: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val holder = remember { RouteOverlays() }
    val mapView = remember {
        NaverMapSdk.getInstance(context).onAuthFailedListener =
            NaverMapSdk.OnAuthFailedListener { e ->
                android.util.Log.w("SilverPassMap", "네이버 지도 인증 실패: code=${e.errorCode} ${e.message}")
                onAuthFailed(e.errorCode ?: e.message ?: "인증 실패")
            }
        MapView(context).also { it.onCreate(null) }
    }
    DisposableEffect(lifecycle) {
        // 이미 RESUMED면 addObserver가 ON_START·ON_RESUME을 바로 보내 준다.
        val observer = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            mapView.onPause()
            mapView.onStop()
            mapView.onDestroy()
        }
    }
    AndroidView(factory = { mapView }, modifier = modifier) { mv ->
        mv.getMapAsync { map ->
            holder.map = map
            map.locale = java.util.Locale.KOREA   // 기기 언어와 상관없이 지명을 한국어로
            map.uiSettings.isRotateGesturesEnabled = false
            map.uiSettings.isTiltGesturesEnabled = false
            draw(holder, route)
        }
    }
}

private fun draw(h: RouteOverlays, r: RouteOptionView) {
    val map = h.map ?: return
    val pts = r.path.map { LatLng(it.first, it.second) }
    if (pts.size < 2) return
    h.path.coords = pts
    h.path.width = 14
    h.path.outlineWidth = 3
    h.path.color = AColor.rgb(0x3E, 0x6F, 0xD8)
    h.path.outlineColor = AColor.WHITE
    h.path.map = map
    h.start.position = pts.first()
    h.start.captionText = "출발"
    h.start.map = map
    h.end.position = pts.last()
    h.end.captionText = "도착"
    h.end.map = map
    val bounds = LatLngBounds.Builder().include(pts).build()
    map.moveCamera(CameraUpdate.fitBounds(bounds, 60))
}
