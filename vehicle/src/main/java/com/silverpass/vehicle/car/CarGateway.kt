package com.silverpass.vehicle.car

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import android.content.Context

/** 화면 조작 범위를 정하는 차량 상태(V01-05, V04-04). */
enum class DriveState(val label: String) {
    PARKED("정차 중 (P)"),
    MOVING("주행 중"),

    /** 신호를 못 받음. 안전을 위해 정차로 간주하지 않는다. */
    UNKNOWN("확인 불가"),
}

/**
 * Pleos Vehicle SDK 래퍼. 실버패스는 기어(P/R/N/D)로 정차·주행을 구분한다.
 * 모든 호출은 [log]로 남겨 시연 도구의 "차량 API 기록"에 보여 준다.
 */
class CarGateway(context: Context, private val log: (String, Boolean) -> Unit) {
    private val vehicle = Vehicle(context.applicationContext)
    private var gearListener: GearSelectionListener? = null

    fun initialize(): Boolean = try {
        vehicle.initialize()
        log("Vehicle.initialize()", true)
        true
    } catch (e: Exception) {
        log("Vehicle.initialize() 실패: ${e.message}", false)
        false
    }

    fun release() {
        gearListener?.let { l -> runCatching { vehicle.getGear().unregisterGearSelection(l) } }
        gearListener = null
        runCatching { vehicle.release() }
    }

    /** 현재 기어를 한 번 읽고, 이후 바뀔 때마다 [onChange]로 알린다. */
    fun watchGear(onChange: (DriveState, VehicleGear?) -> Unit) {
        val gear = vehicle.getGear()
        try {
            gear.getCurrentGearSelectionState(
                { g: VehicleGear? ->
                    log("getCurrentGearSelectionState → ${g?.name}", g != null)
                    onChange(stateOf(g), g)
                },
                { e: Exception? ->
                    log("getCurrentGearSelectionState 실패: ${e?.message}", false)
                    onChange(DriveState.UNKNOWN, null)
                },
            )
        } catch (e: Exception) {
            log("getCurrentGearSelectionState 예외: ${e.message}", false)
            onChange(DriveState.UNKNOWN, null)
        }
        val listener = object : GearSelectionListener {
            override fun onSelected(g: VehicleGear?) {
                log("onSelected(기어) → ${g?.name}", g != null)
                onChange(stateOf(g), g)
            }

            override fun onFailed(e: Exception) {
                log("기어 수신 오류: ${e.message}", false)
                onChange(DriveState.UNKNOWN, null)
            }
        }
        try {
            gear.registerGearSelection(listener)
            gearListener = listener
            log("registerGearSelection", true)
        } catch (e: Exception) {
            log("registerGearSelection 실패: ${e.message}", false)
        }
    }

    private fun stateOf(g: VehicleGear?) = when (g) {
        null, VehicleGear.GEAR_UNKNOWN -> DriveState.UNKNOWN
        VehicleGear.GEAR_PARK -> DriveState.PARKED
        // N도 굴러갈 수 있으므로 주행으로 본다
        else -> DriveState.MOVING
    }
}
