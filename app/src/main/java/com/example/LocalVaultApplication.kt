package com.example

import android.app.Application
import android.util.Log

class LocalVaultApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Log.i("LocalVault", "Local Vault Application Initialized - Zero Internet Policy Active")
    }
}
