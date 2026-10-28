package dev.chandra.grabbit.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Message
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import dev.chandra.grabbit.BuildConfig
import dev.chandra.grabbit.R
import dev.chandra.grabbit.databinding.ActivityMainBinding
import dev.chandra.grabbit.sniffer.MediaItem
import dev.chandra.grabbit.sniffer.MediaRegistry
import dev.chandra.grabbit.sniffer.SnifferBridge
import dev.chandra.grabbit.sniffer.UrlSniffer

class MainActivity : AppCompatActivity() {
    private lateinit var b: ActivityMainBinding
    private lateinit var web: WebView
    val registry = MediaRegistry()
    private var snifferJs: String = ""
    private var desktopMode = false
    private var fullscreenView: View? = null
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null
    private val homeUrl = "file:///android_asset/home.html"
    /** Set when a link arrives from another app's Share sheet: open the media sheet as soon as media shows up. */
    private var autoOpenSheet = false
    private var autoOpenPosted = false
    private var lastClipboardOffered: String? = null
    private var sheet: MediaSheet? = null

    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val askStorage = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    /** Cached on the main thread; WebView settings must not be read from worker threads. */
    @Volatile var userAgent: String = ""
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        web = b.webView
        snifferJs = assets.open("sniffer.js").bufferedReader().readText()
        setupWebView()
        setupChrome()
        registry.addListener { items ->
            renderFab(items)
            if (autoOpenSheet && items.isNotEmpty() && !autoOpenPosted) {
                autoOpenPosted = true
                // Give the page a moment to surface the rest (HLS variants, posters), then pop the sheet.
                b.fab.postDelayed({ if (autoOpenSheet) { autoOpenSheet = false; openSheet() } }, 1500)
            }
        }

        if (savedInstanceState != null) web.restoreState(savedInstanceState) else if (!handleIntent(intent)) web.loadUrl(homeUrl)
        maybeAskPermissions()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?): Boolean {
        val shared = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.dataString
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)?.let { Regex("https?://\\S+").find(it)?.value }
            else -> null
        } ?: return false
        autoOpenSheet = true; autoOpenPosted = false
        lastClipboardOffered = shared
        go(shared); return true
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    private fun maybeAskPermissions() {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        if (Build.VERSION.SDK_INT < 29 && ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)
            askStorage.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(true)
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            allowFileAccess = true
            cacheMode = WebSettings.LOAD_DEFAULT
            // Chrome-like UA keeps sites from serving the "lite" player that hides media.
            userAgentString = userAgentString.replace("; wv", "").replace(Regex("Version/\\d+\\.\\d+ "), "")
        }
        userAgent = web.settings.userAgentString
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(web.settings, false)
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        web.addJavascriptInterface(SnifferBridge(registry), "GrabbitBridge")

        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                UrlSniffer.classify(request)?.let { registry.offer(it) }
                return null
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()
                if (url.startsWith("http") || url.startsWith("file:")) return false
                if (url.startsWith("intent:") || url.contains("://")) {
                    // External app links (e.g. youtube://): keep the user in Grabbit by trying the web fallback.
                    runCatching {
                        val i = Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
                        i.getStringExtra("browser_fallback_url")?.let { view.loadUrl(it) }
                    }
                }
                return true
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                registry.pageUrl = url
                registry.pageTitle = ""
                registry.clear()
                b.progress.visibility = View.VISIBLE
                if (!b.urlInput.hasFocus()) b.urlInput.setText(if (url == homeUrl) "" else url)
                updateNav()
            }

            override fun onPageCommitVisible(view: WebView, url: String) { inject(view) }

            override fun onPageFinished(view: WebView, url: String) {
                b.progress.visibility = View.GONE
                registry.pageTitle = view.title ?: ""
                inject(view)
                updateNav()
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                // SPAs (YouTube, Instagram) navigate without page loads; keep the list per "screen".
                if (registry.pageUrl != url) {
                    registry.pageUrl = url; registry.pageTitle = view.title ?: ""; registry.clear()
                    if (!b.urlInput.hasFocus()) b.urlInput.setText(if (url == homeUrl) "" else url)
                    view.postDelayed({ inject(view) }, 800)
                }
                updateNav()
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                b.progress.progress = newProgress
                b.progress.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
                if (newProgress > 60) inject(view)
            }
            override fun onReceivedTitle(view: WebView, title: String?) { registry.pageTitle = title ?: "" }

            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
                // target=_blank: load in the same tab.
                val temp = WebView(view.context)
                temp.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean {
                        go(request.url.toString()); temp.destroy(); return true
                    }
                }
                (resultMsg.obj as android.webkit.WebView.WebViewTransport).webView = temp
                resultMsg.sendToTarget()
                return true
            }

            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                fullscreenView = view; fullscreenCallback = callback
                b.fullscreenContainer.addView(view, FrameLayout.LayoutParams(-1, -1))
                b.fullscreenContainer.visibility = View.VISIBLE
                b.browserGroup.visibility = View.GONE
            }

            override fun onHideCustomView() {
                b.fullscreenContainer.removeAllViews()
                b.fullscreenContainer.visibility = View.GONE
                b.browserGroup.visibility = View.VISIBLE
                fullscreenCallback?.onCustomViewHidden()
                fullscreenView = null; fullscreenCallback = null
            }
        }

        web.setDownloadListener { url, ua, contentDisposition, mimetype, _ ->
            // The site itself offered a file: download it with Grabbit's engine.
            val name = android.webkit.URLUtil.guessFileName(url, contentDisposition, mimetype)
            val type = when { mimetype.startsWith("audio") -> dev.chandra.grabbit.sniffer.MediaType.AUDIO; mimetype.startsWith("image") -> dev.chandra.grabbit.sniffer.MediaType.IMAGE; else -> dev.chandra.grabbit.sniffer.MediaType.VIDEO }
            val sniffed = UrlSniffer.classify(Uri.parse(url))
            val item = MediaItem(sniffed?.url ?: url, sniffed?.type ?: type, web.url ?: "", name.substringBeforeLast('.'), mime = mimetype, isHls = sniffed?.isHls == true || mimetype.contains("mpegurl"), label = sniffed?.label)
            MediaSheet.enqueue(this, item, ua, if (item.isHls) name.substringBeforeLast('.') + ".ts" else name)
        }
    }

    private fun inject(view: WebView) { view.evaluateJavascript(snifferJs, null) }

    private fun setupChrome() {
        b.urlInput.setOnEditorActionListener { v, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_SEARCH || event?.keyCode == KeyEvent.KEYCODE_ENTER) {
                go(v.text.toString()); hideKeyboard(); true
            } else false
        }
        b.urlInput.setOnFocusChangeListener { v, hasFocus -> if (hasFocus) (v as android.widget.EditText).selectAll() }
        b.btnBack.setOnClickListener { if (web.canGoBack()) web.goBack() }
        b.btnForward.setOnClickListener { if (web.canGoForward()) web.goForward() }
        b.btnHome.setOnClickListener { web.loadUrl(homeUrl) }
        b.btnReload.setOnClickListener { web.reload() }
        b.btnDownloads.setOnClickListener { startActivity(Intent(this, DownloadsActivity::class.java)) }
        b.btnMore.setOnClickListener { showMenu(it) }
        b.fab.setOnClickListener { openSheet() }
        b.clipOpen.setOnClickListener { val u = b.clipBanner.tag as? String ?: return@setOnClickListener; hideClipBanner(); autoOpenSheet = true; autoOpenPosted = false; go(u) }
        b.clipClose.setOnClickListener { hideClipBanner() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    fullscreenView != null -> web.webChromeClient?.onHideCustomView()
                    web.canGoBack() -> web.goBack()
                    else -> { isEnabled = false; onBackPressedDispatcher.onBackPressed() }
                }
            }
        })
    }

    private fun openSheet() {
        inject(web)
        if (sheet?.isShowing == true) return
        sheet = MediaSheet(this, registry).also { it.show() }
    }

    /** Android only lets the focused app read the clipboard, so check when the window gains focus. */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) offerClipboardLink()
    }

    private fun offerClipboardLink() {
        val cm = getSystemService(android.content.ClipboardManager::class.java)
        val text = runCatching { cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString() }.getOrNull() ?: return
        val url = Regex("https?://\\S+").find(text)?.value?.trimEnd('.', ',', ')') ?: return
        if (url == lastClipboardOffered || url == web.url) return
        lastClipboardOffered = url
        val host = runCatching { Uri.parse(url).host }.getOrNull() ?: url
        b.clipTitle.text = getString(R.string.clip_title, host)
        b.clipUrl.text = url
        b.clipBanner.tag = url
        b.clipBanner.visibility = View.VISIBLE
        b.clipBanner.alpha = 0f; b.clipBanner.translationY = -40f
        b.clipBanner.animate().alpha(1f).translationY(0f).setDuration(220).start()
        b.clipBanner.postDelayed({ if (b.clipBanner.tag == url) hideClipBanner() }, 12_000)
    }

    private fun hideClipBanner() {
        b.clipBanner.animate().alpha(0f).translationY(-40f).setDuration(160).withEndAction { b.clipBanner.visibility = View.GONE }.start()
    }

    private fun showMenu(anchor: View) {
        val m = PopupMenu(this, anchor)
        m.menuInflater.inflate(R.menu.menu_main, m.menu)
        m.menu.findItem(R.id.action_desktop).isChecked = desktopMode
        m.setOnMenuItemClickListener {
            when (it.itemId) {
                R.id.action_desktop -> { desktopMode = !desktopMode; applyDesktop(); true }
                R.id.action_share -> { web.url?.let { u -> startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, u), null)) }; true }
                R.id.action_copy -> { web.url?.let { u -> getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(android.content.ClipData.newPlainText("url", u)); Toast.makeText(this, R.string.copy_link, Toast.LENGTH_SHORT).show() }; true }
                R.id.action_about -> { AlertDialog.Builder(this).setTitle(R.string.app_name).setMessage(getString(R.string.about_text, BuildConfig.VERSION_NAME)).setPositiveButton(android.R.string.ok, null).show(); true }
                else -> false
            }
        }
        m.show()
    }

    private fun applyDesktop() {
        val base = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        web.settings.userAgentString = if (desktopMode) base else WebSettings.getDefaultUserAgent(this).replace("; wv", "").replace(Regex("Version/\\d+\\.\\d+ "), "")
        userAgent = web.settings.userAgentString
        web.settings.useWideViewPort = true
        web.reload()
    }

    fun go(input: String) {
        val text = input.trim()
        if (text.isEmpty()) return
        val url = when {
            text.startsWith("http://") || text.startsWith("https://") || text.startsWith("file://") -> text
            text.contains(' ') || !text.contains('.') -> "https://www.google.com/search?q=" + Uri.encode(text)
            else -> "https://$text"
        }
        web.loadUrl(url)
    }

    private fun updateNav() {
        b.btnBack.alpha = if (web.canGoBack()) 1f else 0.35f
        b.btnForward.alpha = if (web.canGoForward()) 1f else 0.35f
    }

    private fun renderFab(items: List<MediaItem>) {
        val n = items.size
        b.fabBadge.visibility = if (n > 0) View.VISIBLE else View.GONE
        b.fabBadge.text = if (n > 99) "99+" else n.toString()
        b.fab.alpha = if (n > 0) 1f else 0.6f
        if (n > 0 && b.fab.scaleX == 1f) b.fab.animate().scaleX(1.12f).scaleY(1.12f).setDuration(120).withEndAction { b.fab.animate().scaleX(1f).scaleY(1f).setDuration(120).start() }.start()
    }

    private fun hideKeyboard() {
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(b.urlInput.windowToken, 0)
        b.urlInput.clearFocus(); web.requestFocus()
    }

    override fun onPause() { super.onPause(); web.onPause() }
    override fun onResume() { super.onResume(); web.onResume() }
    override fun onDestroy() { web.destroy(); super.onDestroy() }
}
