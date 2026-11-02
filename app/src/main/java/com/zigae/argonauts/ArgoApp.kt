package com.zigae.argonauts

import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import com.zigae.argonauts.engine.CrewEngine
import com.zigae.argonauts.service.CrewNotifier
import org.osmdroid.config.Configuration

class ArgoApp : Application() {
    lateinit var engine: CrewEngine
        private set

    /** 앱 화면이 보이는 중인지 (전체 화면이든 내비 옆 화면 분할이든). */
    @Volatile private var started = 0

    override fun onCreate() {
        super.onCreate()
        Configuration.getInstance().apply {
            load(this@ArgoApp, getSharedPreferences("osmdroid", MODE_PRIVATE))
            userAgentValue = "Argonauts/1.0 ($packageName)"
        }
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(a: Activity) { started++ }
            override fun onActivityStopped(a: Activity) { started-- }
            override fun onActivityCreated(a: Activity, b: Bundle?) = Unit
            override fun onActivityResumed(a: Activity) = Unit
            override fun onActivityPaused(a: Activity) = Unit
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) = Unit
            override fun onActivityDestroyed(a: Activity) = Unit
        })
        engine = CrewEngine(this)
        engine.start()
        CrewNotifier(this, engine) { started > 0 }.start()
        if (BuildConfig.DEBUG) registerDemoReceiver()
    }

    /**
     * 시연 준비용 (디버그 빌드): 앱을 앞으로 띄우지 않고 시연 스크립트 실행 → 내비 위 알림 확인.
     * `adb shell am broadcast -a com.zigae.argonauts.DEMO --es run jiyoung|minsu|arrive`
     */
    private fun registerDemoReceiver() {
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                when (i.getStringExtra("run")) {
                    "jiyoung" -> engine.demoJiyoungRestStop()
                    "minsu" -> engine.demoMinsuRequest()
                    "arrive" -> engine.demoArriveAll()
                }
            }
        }
        registerReceiver(r, IntentFilter("com.zigae.argonauts.DEMO"), Context.RECEIVER_EXPORTED)
    }
}
