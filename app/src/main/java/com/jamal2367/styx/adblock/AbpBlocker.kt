package com.jamal2367.styx.adblock

import android.app.Application
import android.net.Uri
import android.webkit.MimeTypeMap
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import androidx.core.util.PatternsCompat
import com.jamal2367.styx.BrowserApp
import com.jamal2367.styx.R
import com.jamal2367.styx.adblock.core.AbpLoader
import com.jamal2367.styx.adblock.core.ContentRequest
import com.jamal2367.styx.adblock.core.FilterContainer
import com.jamal2367.styx.adblock.filter.abp.ABP_PREFIX_ALLOW
import com.jamal2367.styx.adblock.filter.abp.ABP_PREFIX_DENY
import com.jamal2367.styx.adblock.filter.unified.getFilterDir
import com.jamal2367.styx.adblock.repository.abp.AbpDao
import com.jamal2367.styx.okhttp3.internal.publicsuffix.PublicSuffix
import com.jamal2367.styx.utils.ThemeUtils
import com.jamal2367.styx.utils.htmlColor
import com.jamal2367.styx.utils.isSpecialUrl
import kotlinx.coroutines.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.collections.HashMap

@Singleton
class AbpBlocker @Inject constructor(
    private val application: Application,
    abpListUpdater: AbpListUpdater,
    private val abpUserRules: AbpUserRules
) : AdBlocker {
    private lateinit var exclusionList: FilterContainer
    private lateinit var blockList: FilterContainer

    // if i want mining/malware block, it should be separate lists so they are not affected by ad-blocklist exclusions
    // TODO: any reason NOT to join those lists?
    private var miningList = FilterContainer() // copy from yuzu?
    private var malwareList = FilterContainer() // copy from smartcookieweb/styx?

    // store whether lists are loaded (and delay any request if loading is not finished)
    private var listsLoaded = false

    // store urls of entities, they will never be blocked
    private val entityUrls = mutableListOf<String>()

    /*    // TODO: element hiding
        //  not sure if this actually works (did not in a test - I think?), maybe it's crucial to inject the js at the right point
        //  tried onPageFinished, might be too late (try to implement onDomFinished from yuzu?)
    //    private var elementHideExclusionList: FilterContainer? = null
    //    private var elementHideList: ElementContainer? = null
        // both lists are actually inside the elementBlocker
        private var elementBlocker: CosmeticFiltering? = null
        var elementHide = userPreferences.elementHide
    */
    // cache for 3rd party check, allows significantly faster checks
    private val thirdPartyCache = mutableMapOf<String, Boolean>()
    private val thirdPartyCacheSize = 100

    private val dummyImage: ByteArray by lazy { readByte(application.resources.assets.open("blank.webp")) }
    private val dummyResponse by lazy { WebResourceResponse("text/plain", "UTF-8", EmptyInputStream()) }

    override fun isAd(url: String) = false // for now...

    init {
        // TODO: ideally we should call loadLists here (blocking) if the url in current tab (on opening browser) is not special
        //  because loadLists() here is sometimes significantly faster than inside the GlobalScope
        //  but generally no need to block UI, better just delay the first web requests
        //loadLists() // 320-430 ms on S4 mini plus / 550-650 ms on S4 mini -> always the fastest, but blocking

        // TODO: use of globalscope is discouraged, but all reasons I found were related to stuff that is no canceled if the related UI is closed
        //  I don't see how this would apply, a) the operations end after a few seconds anyway without needing to cancel, and b) blocker runs as log as the app runs
        GlobalScope.launch(Dispatchers.Default) { // IO for io-intensive stuff, but here we do some IO and are mostly limited by CPU... so Default should be better?
            // load lists here if not loaded above
            //stufftest()
            loadLists() // 400-450 ms on S4 mini plus / 1200-1700 ms on S4 mini -> good on plus
            //loadListsAsync() // 540-720 ms on S4 mini plus / 900-1200 ms on S4 mini -> best non-blocking on normal
            // why is async so much faster on a dual core phone? expectation is other way round

            // update all entities in AbpDao
            // may take a while depending on how many lists need update, and on internet connection
            if (abpListUpdater.updateAll(false)) // returns true if anything was updated
                loadLists() // update again if files have changed
        }
    }

    //----------------------- from yuzu adblocker (mostly AdBlockController) --------------------//

    // TODO: add info where exactly the code is taken from, should simplify handling in case yuzu code is updated
    private fun createDummy(uri: Uri): WebResourceResponse {
        val mimeType = getMimeType(uri.toString())
        return if (mimeType.startsWith("image/")) {
            WebResourceResponse("image/png", null, ByteArrayInputStream(dummyImage))
        } else {
            dummyResponse
        }
    }

    private fun createMainFrameDummy(uri: Uri, pattern: String): WebResourceResponse {
        val blocked = application.getString(R.string.ad_block_blocked_page)
        val filter = application.getString(R.string.ad_block_blocked_filter)
        val background = htmlColor(ThemeUtils.getSurfaceColor(BrowserApp.currentContext()))
        val background1 = htmlColor(ThemeUtils.getColor(BrowserApp.currentContext(),R.attr.trackColor))
        val text = htmlColor(ThemeUtils.getColor(BrowserApp.currentContext(),R.attr.colorOnPrimary))
        val builder = StringBuilder("<meta charset=utf-8>" +
                "<meta content=\"width=device-width,initial-scale=1,minimum-scale=1\"name=viewport>" +
                "<style>body{padding:5px 15px;background:$background}body,p{text-align:center;color:$text}p{margin:20px 0 0}" +
                "pre{margin:5px 0;padding:5px;background:$background1}}</style>")
            .append("<p>")
            .append(blocked)
            .append("<pre>")
            .append(uri)
            .append("</pre><p>")
            .append(filter)
            .append("<pre>")
            .append(pattern)
            .append("</pre>")

        return getNoCacheResponse("text/html", builder)
    }

    // TODO: remove if element hiding does not work
    override fun loadScript(uri: Uri): String? {
//        val cosmetic = elementBlocker ?: return null
//        return cosmetic.loadScript(uri)
        return null
    }

    // copied here to allow modified 3rd party detection
    private fun WebResourceRequest.getContentRequest(pageUri: Uri) =
        ContentRequest(url, pageUri, getContentType(pageUri), is3rdParty(url, pageUri))

    // modified to use cache for the slow part, decreases average time by 50-70%
    fun is3rdParty(url: Uri, pageUri: Uri): Boolean {
        val hostName = url.host ?: return true
        val pageHost = pageUri.host ?: return true

        if (hostName == pageHost) return false

        val cacheEntry = hostName + pageHost
        val cached = thirdPartyCache[cacheEntry]
        if (cached != null)
            return cached

        val ipPattern = PatternsCompat.IP_ADDRESS
        if (ipPattern.matcher(hostName).matches() || ipPattern.matcher(pageHost).matches())
            return cache3rdPartyResult(true, cacheEntry)

        val db = PublicSuffix.get()

        return cache3rdPartyResult(db.getEffectiveTldPlusOne(hostName) != db.getEffectiveTldPlusOne(pageHost), cacheEntry)
    }

    //----------------------- not from yuzu any more ------------------------//

    // TODO: probably remove
    fun loadListsAsync() {
        listsLoaded = false
        val abpLoader = AbpLoader(application.applicationContext.getFilterDir(), AbpDao(application.applicationContext).getAll())
        GlobalScope.launch {
            val el = async { FilterContainer().also { abpLoader.loadAll(ABP_PREFIX_ALLOW).forEach(it::addWithTag) } }
            val bl = async { FilterContainer().also { abpLoader.loadAll(ABP_PREFIX_DENY).forEach(it::addWithTag) } }
            exclusionList = el.await()
            blockList = bl.await()
            listsLoaded = true
        }
    }

    fun loadLists() {
        listsLoaded = false
        val entities = AbpDao(application.applicationContext).getAll()
        val abpLoader = AbpLoader(application.applicationContext.getFilterDir(), entities)
        exclusionList = FilterContainer().also { abpLoader.loadAll(ABP_PREFIX_ALLOW).forEach(it::addWithTag) }
        blockList = FilterContainer().also { abpLoader.loadAll(ABP_PREFIX_DENY).forEach(it::addWithTag) }

        /*if (elementHide) {
            val disableCosmetic = FilterContainer().also { abpLoader.loadAll(ABP_PREFIX_DISABLE_ELEMENT_PAGE).forEach(it::plusAssign) }
            val elementFilter = ElementContainer().also { abpLoader.loadAllElementFilter().forEach(it::plusAssign) }
            elementBlocker = CosmeticFiltering(disableCosmetic, elementFilter)
        }*/
        entityUrls.clear()
        entities.forEach { if (it.url.startsWith("http")) entityUrls.add(it.url) }

        listsLoaded = true
    }

    // cache 3rd party check result, and remove oldest entry if cache too large
    // TODO: this can trigger concurrentModificationException
    //   fix should not defeat purpose of cache (introduce slowdown)
    //   simply use try and don't catch anything?
    //    if something is not added to cache it doesn't matter
    //    in worst case it takes another 1-2 ms to create the same result again
    private fun cache3rdPartyResult(is3rdParty: Boolean, cacheEntry: String): Boolean {
        runCatching {
            thirdPartyCache[cacheEntry] = is3rdParty
            if (thirdPartyCache.size > thirdPartyCacheSize)
                thirdPartyCache.remove(thirdPartyCache.keys.first())
        }
        return is3rdParty
    }

    // returns null if not blocked, else some WebResourceResponse
    override fun shouldBlock(request: WebResourceRequest, pageUrl: String): WebResourceResponse? {
        // always allow special URLs
        // then check user lists (user allow should even override malware list)
        // then mining/malware (ad block allow should not override malware list)
        // then ads

        if (request.url.toString().isSpecialUrl() || entityUrls.contains(request.url.toString()))
            return null

        // create contentRequest
        // pageUrl can be "" (usually when opening something in a new tab)
        //  in this case everything gets blocked because of the pattern "|https://"
        //  this is blocked for some page domains, and apparently no page domain also means it's blocked
        //  so some workaround here (maybe do something else? check how UnifiedFilter handles domainMap stuff)
        // TODO: check if pageUrl could be wrong but not empty in some cases
        //  like opening bookmarks, or manually entering a url
        val contentRequest = request.getContentRequest(if (pageUrl == "") request.url else Uri.parse(pageUrl))

        // no need to supply pattern to block response
        //  pattern only used if it's for main frame
        //  and if it's for main frame and blocked by user, it's always because user chose to block entire domain
        abpUserRules.getResponse(contentRequest)?.let { response ->
            return if (response) getBlockResponse(request, application.resources.getString(R.string.ad_block_blocked_list_user, contentRequest.pageUrl.host ?: ""))
            else null
        }

        // wait until blocklists are loaded
        //  (web request stuff does not run on main thread, so thread.sleep is ok)
        // possible reduction of wait time before lists have loaded:
        //   load list with 'empty' tag first and check those
        //   for the rest the bloom filter could be used, so requests without matching tags are allowed immediately
        //   but: seems like a lot of work for maybe saving 0.5-2 seconds on browser start
        //    'maybe' saving because ca 20-50% of all requests will have matching tags, so there will (probably) still be a delay
        while (!listsLoaded) {
            Thread.sleep(50)
        }

        miningList[contentRequest]?.let { return getBlockResponse(request, application.resources.getString(R.string.ad_block_blocked_list_malware, it.pattern)) }
        malwareList[contentRequest]?.let { return getBlockResponse(request, application.resources.getString(R.string.ad_block_blocked_list_malware, it.pattern)) }
        exclusionList[contentRequest]?.let { return null }
        blockList[contentRequest]?.let { return getBlockResponse(request, application.resources.getString(R.string.ad_block_blocked_list_ad, it.pattern)) }

        return null
    }

    private fun getBlockResponse(request: WebResourceRequest, pattern: String): WebResourceResponse {
        return if (request.isForMainFrame)
            createMainFrameDummy(request.url, pattern)
        else
            createDummy(request.url)
    }

    // stuff from yuzu browser modules/core/.../utility
    // TODO: add info where exactly the code is taken from, should simplify handling in case yuzu code is updated
    companion object {
        private const val BUFFER_SIZE = 1024 * 8

        @Throws(IOException::class)
        fun readByte(inpputStream: InputStream): ByteArray {
            val buffer = ByteArray(BUFFER_SIZE)
            val bout = ByteArrayOutputStream()
            var n: Int
            while (inpputStream.read(buffer).also { n = it } >= 0) {
                bout.write(buffer, 0, n)
            }
            return bout.toByteArray()
        }

        private const val MIME_TYPE_UNKNOWN = "application/octet-stream"
        fun getMimeType(fileName: String): String {
            val lastDot = fileName.lastIndexOf('.')
            if (lastDot >= 0) {
                val extension = fileName.substring(lastDot + 1).lowercase(Locale.getDefault())
                return getMimeTypeFromExtension(extension)
            }
            return "application/octet-stream"
        }

        fun getMimeTypeFromExtension(extension: String): String {
            return when (extension) {
                "js" -> "application/javascript"
                "mhtml", "mht" -> "multipart/related"
                "json" -> "application/json"
                else -> {
                    val type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
                    if (type.isNullOrEmpty()) {
                        MIME_TYPE_UNKNOWN
                    } else {
                        type
                    }
                }
            }
        }

        fun getNoCacheResponse(mimeType: String, sequence: CharSequence): WebResourceResponse {
            return getNoCacheResponse(
                mimeType, ByteArrayInputStream(
                    sequence.toString().toByteArray(
                        StandardCharsets.UTF_8
                    )
                )
            )
        }

        private fun getNoCacheResponse(mimeType: String, stream: InputStream): WebResourceResponse {
            val response = WebResourceResponse(mimeType, "UTF-8", stream)
            response.responseHeaders =
                HashMap<String, String>().apply { put("Cache-Control", "no-cache") }
            return response
        }
    }
}
