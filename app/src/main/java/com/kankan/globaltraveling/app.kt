package com.kankan.globaltraveling

import android.app.Application
import com.kankan.globaltraveling.utils.AMapSearchManager

class App : Application() {
    companion object {
        lateinit var instance: App
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        AMapSearchManager.init(this)
    }
}