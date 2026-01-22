package com.aotuman.baobaoai

import android.app.Application
import com.aotuman.baobaoai.utils.AppMapper
import com.aotuman.baobaoai.utils.AppMatcher

class MyApplication : Application() {
    companion object {
        lateinit var instance: MyApplication
            private set
    }
    
    override fun onCreate() {
        super.onCreate()
        instance = this
        AppMapper.init(this)
        AppMatcher.init(AppMapper)
    }
}
