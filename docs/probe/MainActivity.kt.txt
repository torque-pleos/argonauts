package com.zigae.argonauts

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** 검증 화면: 버튼으로 ProbeService 명령을 보내고 최근 로그를 보여준다. */
class MainActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var log: TextView

    private val refresh = object : Runnable {
        override fun run() {
            log.text = ProbeLog.snapshot().takeLast(40).reversed().joinToString("\n")
            main.postDelayed(this, 1_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.BLACK); setPadding(24, 24, 24, 24) }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun btn(label: String, action: String, lat: Double = 0.0, lon: Double = 0.0, name: String? = null) =
            row.addView(Button(this).apply {
                text = label
                setOnClickListener { send(action, lat, lon, name) }
            })
        btn("시작", "start")
        btn("목적지: 남이섬", "route", 37.7905, 127.5258, "남이섬")
        btn("경유지: 가평휴게소", "waypoint", 37.7266, 127.4428, "가평휴게소")
        btn("경유지 삭제", "removeWaypoints")
        btn("Trip", "trip", 37.7905, 127.5258, "남이섬")
        btn("즐겨찾기/최근", "places")
        btn("경로 취소", "cancel")
        root.addView(row)
        log = TextView(this).apply { setTextColor(Color.WHITE); textSize = 13f }
        root.addView(ScrollView(this).apply { addView(log) })
        setContentView(root)
        send("start")
    }

    private fun send(action: String, lat: Double = 0.0, lon: Double = 0.0, name: String? = null) {
        startForegroundService(Intent(this, ProbeService::class.java).setAction(action).putExtra("lat", lat).putExtra("lon", lon).putExtra("name", name))
    }

    override fun onResume() { super.onResume(); ProbeLog.add("activity resumed"); main.post(refresh) }
    override fun onPause() { ProbeLog.add("activity paused"); main.removeCallbacks(refresh); super.onPause() }
}
