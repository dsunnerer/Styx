package com.jamal2367.styx.search.engine

import com.jamal2367.styx.R

/**
 * The DuckDuckGo search engine.
 *
 * See https://duckduckgo.com/assets/logo_homepage.normal.v101.png for the icon.
 */
class DuckSearch : BaseSearchEngine(
    "file:///android_asset/duckduckgo.png",
    "https://duckduckgo.com/?t=styx&q=",
    R.string.search_engine_duckduckgo
)
