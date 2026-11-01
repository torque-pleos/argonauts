package com.zigae.argonauts.vehicle

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class Gear { PARK, REVERSE, NEUTRAL, DRIVE, UNKNOWN }

/**
 * 기어로 주행/정차를 판단한다 (Vehicle State 조회만 사용).
 * 정차(P) = 지도 전체 조작·입력 허용, 그 외 = 주행 중 간결 모드.
 */
class GearMonitor(context: Context, private val scope: CoroutineScope) {
    private val vehicle = Vehicle(context.applicationContext)
    private val _gear = MutableStateFlow(Gear.UNKNOWN)
    val gear: StateFlow<Gear> = _gear.asStateFlow()
    private var job: Job? = null
    private var registered = false

    private val listener = object : GearSelectionListener {
        override fun onSelected(gear: VehicleGear?) = publish(gear)
        override fun onFailed(e: Exception) {
            Log.w(TAG, "gear listener: ${e.message}")
        }
    }

    fun start() {
        vehicle.initialize()
        job = scope.launch {
            delay(1_000)
            while (isActive) {
                @Suppress("DEPRECATION")
                runCatching { vehicle.getGear().getCurrentGearSelectionState(::publish) { Log.w(TAG, "gear: ${it.message}") } }
                if (!registered) registered = runCatching { vehicle.getGear().registerGearSelection(listener) }.isSuccess
                delay(3_000)
            }
        }
    }

    fun stop() {
        job?.cancel()
        runCatching { if (registered) vehicle.getGear().unregisterGearSelection(listener) }
        runCatching { vehicle.release() }
    }

    private fun publish(g: VehicleGear?) {
        val gear = when (g) {
            VehicleGear.GEAR_PARK -> Gear.PARK
            VehicleGear.GEAR_REVERSE -> Gear.REVERSE
            VehicleGear.GEAR_NEUTRAL -> Gear.NEUTRAL
            null, VehicleGear.GEAR_UNKNOWN -> Gear.UNKNOWN
            else -> Gear.DRIVE
        }
        if (gear != _gear.value) Log.i(TAG, "gear ${_gear.value} -> $gear")
        _gear.value = gear
    }

    private companion object {
        const val TAG = "Argo/Gear"
    }
}
