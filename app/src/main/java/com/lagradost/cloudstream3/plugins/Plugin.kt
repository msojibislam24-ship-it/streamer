package com.lagradost.cloudstream3.plugins

import android.content.Context
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.extractorApis

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class CloudstreamPlugin

abstract class Plugin {
    var __filename: String? = null
    var openSettings: ((context: Context) -> Unit)? = null

    open fun load(context: Context) {
        load()
    }

    open fun load() {}
    open fun beforeUnload() {}

    fun registerMainAPI(api: MainAPI) {
        synchronized(APIHolder.allProviders) {
            APIHolder.allProviders.add(api)
        }
    }

    fun registerExtractorAPI(extractor: ExtractorApi) {
        extractorApis.add(extractor)
    }
}
