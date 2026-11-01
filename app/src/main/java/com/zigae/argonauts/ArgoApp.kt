package com.zigae.argonauts

import android.app.Application
import com.zigae.argonauts.engine.CrewEngine
import org.osmdroid.config.Configuration

class ArgoApp : Application() {
    lateinit var engine: CrewEngine
        private set

    override fun onCreate() {
        super.onCreate()
        Configuration.getInstance().apply {
            load(this@ArgoApp, getSharedPreferences("osmdroid", MODE_PRIVATE))
            userAgentValue = "Argonauts/1.0 ($packageName)"
        }
        engine = CrewEngine(this)
        engine.start()
    }
}
