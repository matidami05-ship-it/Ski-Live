package com.ski.live

import android.app.Application
import com.ski.live.data.Store
import com.ski.live.island.Pill
import com.ski.live.live.LiveNotifier
import com.ski.live.sports.Logos

class SkiLiveApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Store.init(this)
        Logos.cacheDir = cacheDir
        LiveNotifier.createChannels(this)
        Pill.refresh(this)
    }
}
