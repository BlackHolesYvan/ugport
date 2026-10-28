package com.ugport

import android.app.Application
import com.ugport.di.AppContainer

class UgPortApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (::container.isInitialized) {
            container.diagnostics.detail("app: trimMemory=$level")
        }
    }
}
