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
}
