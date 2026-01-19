package com.aotuman.baobaoai

import android.app.Application
import com.aotuman.baobaoai.utils.AppMapper
import com.aotuman.baobaoai.utils.AppMatcher

class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppMapper.init(this)
        AppMatcher.init(AppMapper)
    }
}
