package dev.chandra.grabbit.player

import android.app.PictureInPictureParams
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.util.Rational
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.TrackSelectionDialogBuilder
import com.google.common.util.concurrent.ListenableFuture
import dev.chandra.grabbit.R
import dev.chandra.grabbit.databinding.ActivityPlayerBinding
import kotlin.math.abs

/**
 * Full-screen video / audio player. Gestures: tap toggles controls, double-tap left/right seeks
 * 10 s, vertical swipe on the left adjusts brightness and on the right adjusts volume.
 */
@OptIn(UnstableApi::class)
class PlayerActivity : AppCompatActivity() {
    private lateinit var b: ActivityPlayerBinding
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var pendingItems: List<MediaItem>? = null
    private var pendingIndex = 0
    private var inPip = false
    private lateinit var audio: AudioManager
    private val hideIndicator = Runnable { b.indicator.visibility = View.GONE }

    private val playerListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) { updateTitle(mediaItem) }
        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) { updateTitle(controller?.currentMediaItem) }
        override fun onIsPlayingChanged(isPlaying: Boolean) { b.root.keepScreenOn = isPlaying }
        override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) { refreshAudioArt() }
        override fun onTracksChanged(tracks: androidx.media3.common.Tracks) { refreshAudioArt() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(b.root)
        audio = getSystemService(AudioManager::class.java)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(b.topBar) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(bars.left + dp(6), bars.top + dp(6), bars.right + dp(6), dp(24))
            // Keep the controller's bottom bar above the gesture / navigation bar.
            b.playerView.findViewById<View?>(androidx.media3.ui.R.id.exo_bottom_bar)?.setPadding(bars.left, 0, bars.right, bars.bottom)
            insets
        }
        b.btnBack.setOnClickListener { finish() }
        b.btnRotate.setOnClickListener { toggleOrientation() }
        b.btnPip.setOnClickListener { enterPip() }
        b.btnQuality.setOnClickListener { showQuality() }
        b.playerView.setFullscreenButtonClickListener { toggleOrientation() }
        b.playerView.setControllerVisibilityListener(androidx.media3.ui.PlayerView.ControllerVisibilityListener { v ->
            b.topBar.visibility = if (inPip) View.GONE else v
            if (v == View.VISIBLE) showSystemBars() else hideSystemBars()
        })
        b.playerView.controllerShowTimeoutMs = 3500
        setupGestures()
        if (savedInstanceState == null) parseIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        parseIntent(intent)
        controller?.let { applyPending(it) }
    }

    private fun parseIntent(intent: Intent) {
        if (intent.action == ACTION_RESUME) { pendingItems = null; return }
        val items = ArrayList<MediaItem>()
        val uris = intent.getStringArrayListExtra(EXTRA_URIS)
        if (uris != null) {
            val titles = intent.getStringArrayListExtra(EXTRA_TITLES) ?: arrayListOf()
            val mimes = intent.getStringArrayListExtra(EXTRA_MIMES) ?: arrayListOf()
            val referer = intent.getStringExtra(EXTRA_REFERER)
            val ua = intent.getStringExtra(EXTRA_UA)
            uris.forEachIndexed { i, u ->
                if (u.startsWith("http")) PlaybackHeaders.register(u, referer, ua)
                items.add(build(u, titles.getOrNull(i) ?: Uri.parse(u).lastPathSegment ?: "Media", mimes.getOrNull(i)))
            }
            pendingIndex = intent.getIntExtra(EXTRA_INDEX, 0).coerceIn(0, (items.size - 1).coerceAtLeast(0))
        } else if (intent.action == Intent.ACTION_VIEW && intent.data != null) {
            val u = intent.data!!
            items.add(build(u.toString(), u.lastPathSegment ?: "Media", intent.type))
            pendingIndex = 0
        }
        pendingItems = items.takeIf { it.isNotEmpty() }
    }

    private fun build(uri: String, title: String, mime: String?): MediaItem {
        val b = MediaItem.Builder().setUri(uri).setMediaMetadata(MediaMetadata.Builder().setTitle(title).build())
        val m = mime?.substringBefore(';')
        when {
            uri.contains(".m3u8") || m == "application/x-mpegURL" || m == "application/vnd.apple.mpegurl" || m == "video/mp2t" && uri.contains("m3u8") -> b.setMimeType(MimeTypes.APPLICATION_M3U8)
            m != null && !m.endsWith("/*") -> b.setMimeType(m)
        }
        return b.build()
    }

    override fun onStart() {
        super.onStart()
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val f = MediaController.Builder(this, token).buildAsync()
        controllerFuture = f
        f.addListener({
            val c = runCatching { f.get() }.getOrNull() ?: return@addListener
            controller = c
            c.addListener(playerListener)
            b.playerView.player = c
            applyPending(c)
            updateTitle(c.currentMediaItem)
            refreshAudioArt()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun applyPending(c: MediaController) {
        val items = pendingItems ?: return
        pendingItems = null
        c.setMediaItems(items, pendingIndex, 0)
        c.prepare()
        c.play()
    }

    override fun onStop() {
        super.onStop()
        val c = controller
        // Video stops when the screen goes away (unless in PiP); audio keeps playing via the service.
        if (c != null && !inPip && !isAudioOnly()) c.pause()
        b.playerView.player = null
        c?.removeListener(playerListener)
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controller = null
    }

    private fun isAudioOnly(): Boolean {
        val c = controller ?: return false
        val mime = c.currentMediaItem?.localConfiguration?.mimeType
        if (mime != null && mime.startsWith("audio/")) return true
        val tracks = c.currentTracks
        return tracks.groups.isNotEmpty() && tracks.groups.none { it.type == C.TRACK_TYPE_VIDEO }
    }

    private fun refreshAudioArt() {
        val audioOnly = isAudioOnly()
        b.audioArt.visibility = if (audioOnly) View.VISIBLE else View.GONE
        b.btnQuality.visibility = if (audioOnly) View.GONE else View.VISIBLE
        b.btnPip.visibility = if (audioOnly) View.GONE else View.VISIBLE
    }

    private fun updateTitle(item: MediaItem?) {
        val t = item?.mediaMetadata?.title?.toString() ?: ""
        b.title.text = t
        b.audioTitle.text = t
    }

    private fun showQuality() {
        val c = controller ?: return
        TrackSelectionDialogBuilder(this, getString(R.string.quality), c, C.TRACK_TYPE_VIDEO)
            .setShowDisableOption(false)
            .setAllowAdaptiveSelections(true)
            .build().show()
    }

    private fun toggleOrientation() {
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }

    private fun enterPip() {
        if (isAudioOnly()) return
        val size = controller?.videoSize
        val ratio = if (size != null && size.width > 0 && size.height > 0) Rational(size.width, size.height) else Rational(16, 9)
        val safe = ratio.toFloat().coerceIn(0.42f, 2.39f)
        val params = PictureInPictureParams.Builder().setAspectRatio(Rational((safe * 1000).toInt(), 1000)).build()
        runCatching { enterPictureInPictureMode(params) }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (controller?.isPlaying == true && !isAudioOnly()) enterPip()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip = isInPictureInPictureMode
        b.playerView.useController = !isInPictureInPictureMode
        b.topBar.visibility = if (isInPictureInPictureMode) View.GONE else b.topBar.visibility
        if (isInPictureInPictureMode) b.playerView.hideController()
    }

    // ---- gestures ----
    private fun setupGestures() {
        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            private var startBrightness = -1f
            private var startVolume = -1
            private var mode = 0 // 0 none, 1 brightness, 2 volume

            override fun onDown(e: MotionEvent): Boolean { mode = 0; return true }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (b.playerView.isControllerFullyVisible) b.playerView.hideController() else b.playerView.showController()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                val c = controller ?: return true
                val forward = e.x > b.root.width / 2f
                if (forward) c.seekForward() else c.seekBack()
                indicate(if (forward) "+10 s  ▶▶" else "◀◀  -10 s")
                return true
            }

            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
                e1 ?: return false
                val totalDy = e1.y - e2.y
                if (mode == 0) {
                    if (abs(totalDy) < 24f || abs(e1.x - e2.x) > abs(totalDy)) return false
                    mode = if (e1.x < b.root.width / 2f) 1 else 2
                    startBrightness = window.attributes.screenBrightness.takeIf { it >= 0f } ?: 0.5f
                    startVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                }
                val fraction = totalDy / (b.root.height * 0.6f)
                if (mode == 1) {
                    val v = (startBrightness + fraction).coerceIn(0.02f, 1f)
                    window.attributes = window.attributes.apply { screenBrightness = v }
                    indicate("☀  ${(v * 100).toInt()}%")
                } else {
                    val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    val v = (startVolume + fraction * max).toInt().coerceIn(0, max)
                    audio.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0)
                    indicate((if (v == 0) "🔇  " else "🔊  ") + (v * 100 / max) + "%")
                }
                return true
            }
        })
        // PlayerView.performClick() toggles the controller itself, so we must not call it here:
        // the gesture detector owns tap handling (single tap toggles, double tap seeks).
        b.playerView.setOnTouchListener { _, e -> detector.onTouchEvent(e); true }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun indicate(text: String) {
        b.indicator.text = text
        b.indicator.visibility = View.VISIBLE
        b.indicator.removeCallbacks(hideIndicator)
        b.indicator.postDelayed(hideIndicator, 700)
    }

    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, b.root).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun showSystemBars() { WindowInsetsControllerCompat(window, b.root).show(WindowInsetsCompat.Type.systemBars()) }

    companion object {
        const val ACTION_RESUME = "dev.chandra.grabbit.player.RESUME"
        private const val EXTRA_URIS = "uris"
        private const val EXTRA_TITLES = "titles"
        private const val EXTRA_MIMES = "mimes"
        private const val EXTRA_INDEX = "index"
        private const val EXTRA_REFERER = "referer"
        private const val EXTRA_UA = "ua"

        fun start(context: Context, uris: List<String>, titles: List<String>, mimes: List<String>, index: Int, referer: String? = null, userAgent: String? = null) {
            val i = Intent(context, PlayerActivity::class.java)
                .putStringArrayListExtra(EXTRA_URIS, ArrayList(uris))
                .putStringArrayListExtra(EXTRA_TITLES, ArrayList(titles))
                .putStringArrayListExtra(EXTRA_MIMES, ArrayList(mimes))
                .putExtra(EXTRA_INDEX, index)
                .putExtra(EXTRA_REFERER, referer)
                .putExtra(EXTRA_UA, userAgent)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.startActivity(i)
        }
    }
}
