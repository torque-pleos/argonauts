package com.zigae.argonauts

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.zigae.argonauts.core.LatLon
import com.zigae.argonauts.core.Place
import com.zigae.argonauts.ui.AppRoot
import com.zigae.argonauts.ui.ArgoTheme

class MainActivity : ComponentActivity() {
    private val engine get() = (application as ArgoApp).engine

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        handleDemoExtras(intent)
        setContent { ArgoTheme { AppRoot(engine) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDemoExtras(intent)
    }

    /**
     * 시연 준비용 (에뮬레이터는 adb로 한글 입력이 안 됨):
     * `am start -n com.zigae.argonauts/.MainActivity --es name 건우 --es dest 남이섬 --ed lat 37.7905 --ed lon 127.5258`
     */
    private fun handleDemoExtras(i: Intent?) {
        if (i == null || !BuildConfig.DEBUG) return
        i.getStringExtra("name")?.let(engine::setName)
        val dest = i.getStringExtra("dest")
        if (dest != null && i.hasExtra("lat")) engine.navi.requestRoute(Place(dest, LatLon(i.getDoubleExtra("lat", 0.0), i.getDoubleExtra("lon", 0.0))))
    }
}
