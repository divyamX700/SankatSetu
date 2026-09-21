package com.sankatsetu.app

import android.app.Application
import com.sankatsetu.app.debug.SosSimulator
import com.sankatsetu.app.di.AppContainer

class SankatSetuApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)

        // Never present in a release build -- see SosSimulator's own doc
        // for why this exists: two-phone BLE mesh delivery can't be tested
        // without a second physical device, so this exercises the real
        // decode/store/render pipeline for an incoming SOS from the BLE
        // boundary inward instead.
        if (BuildConfig.DEBUG) {
            SosSimulator(
                messageRouter = container.messageRouter,
                peerDao = container.database.peerDao(),
                sosDao = container.database.sosDao(),
                scope = container.appScope
            ).register(this)
        }
    }
}
