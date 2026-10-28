package dev.chandra.grabbit.player

import android.app.PictureInPictureParams
import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Rational
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.TextureView
import android.view.View
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
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
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.TrackSelectionDialogBuilder
import com.google.common.util.concurrent.ListenableFuture
import dev.chandra.grabbit.R
import dev.chandra.grabbit.databinding.ActivityPlayerBinding
import kotlin.math.abs

/**
 * Full-screen video / audio player.
 * Gestures: tap toggles controls · double-tap left/right seeks 10 s · swipe left edge = brightness ·
 * swipe right edge = volume · horizontal swipe = scrub · long-press = 2x speed while held.
 * Extras: lock, aspect ratio, quality / audio / subtitle tracks, external subtitle file, sleep timer,
 * screenshot, PiP, rotate, resume where you left off, auto-landscape for wide videos.
 */
@OptIn(UnstableApi::class)
class PlayerActivity : AppCompatActivity() {
    private lateinit var b: ActivityPlayerBinding
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var pendingItems: List<MediaItem>? = null
    private var pendingIndex = 0
    private var inPip = false
    private var locked = false
    private var userRotated = false
    private var autoRotatedFor: String? = null
    private var sleepRunnable: Runnable? = null
    private lateinit var audio: AudioManager
    private val hideIndicator = Runnable { b.indicator.visibility = View.GONE }
    private val positions by lazy { getSharedPreferences("positions", Context.MODE_PRIVATE) }
    private val aspectModes = listOf(
        AspectRatioFrameLayout.RESIZE_MODE_FIT to "Fit",
        AspectRatioFrameLayout.RESIZE_MODE_ZOOM to "Crop to fill",
        AspectRatioFrameLayout.RESIZE_MODE_FILL to "Stretch",
    )
    private var aspectIndex = 0

    private val pickSubtitle = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { addSubtitle(it) } }

    private val playerListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            updateTitle(mediaItem)
            // Playlist changes carry their own start position (see applyPending); only restore on next/prev/auto.
            if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) b.root.post { restorePosition(mediaItem) }
        }
        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) { updateTitle(controller?.currentMediaItem) }
        override fun onIsPlayingChanged(isPlaying: Boolean) { b.root.keepScreenOn = isPlaying; if (!isPlaying) savePosition() }
        override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) { refreshAudioArt(); maybeAutoRotate(videoSize.width, videoSize.height) }
        override fun onTracksChanged(tracks: androidx.media3.common.Tracks) { refreshAudioArt() }
        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            Toast.makeText(this@PlayerActivity, "Cannot play: ${error.errorCodeName}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(b.root)
        audio = getSystemService(AudioManager::class.java)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(b.topBar) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(bars.left + dp(6), bars.top + dp(6), bars.right + dp(6), dp(24))
            b.playerView.findViewById<View?>(androidx.media3.ui.R.id.exo_bottom_bar)?.setPadding(bars.left, 0, bars.right, bars.bottom)
            insets
        }
        b.btnBack.setOnClickListener { finish() }
        b.btnRotate.setOnClickListener { userRotated = true; toggleOrientation() }
        b.btnPip.setOnClickListener { enterPip() }
        b.btnQuality.setOnClickListener { showTracks(C.TRACK_TYPE_VIDEO, getString(R.string.quality)) }
        b.btnLock.setOnClickListener { setLocked(true) }
        b.btnUnlock.setOnClickListener { setLocked(false) }
        b.btnAspect.setOnClickListener { cycleAspect() }
        b.btnMore.setOnClickListener { showMore(it) }
        b.playerView.setFullscreenButtonClickListener { userRotated = true; toggleOrientation() }
        b.playerView.setControllerVisibilityListener(androidx.media3.ui.PlayerView.ControllerVisibilityListener { v ->
            b.topBar.visibility = if (inPip || locked) View.GONE else v
            if (v == View.VISIBLE && !locked) showSystemBars() else hideSystemBars()
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
        val b = MediaItem.Builder().setUri(uri).setMediaId(uri).setMediaMetadata(MediaMetadata.Builder().setTitle(title).build())
        val m = mime?.substringBefore(';')
        when {
            uri.contains(".m3u8") || m == "application/x-mpegURL" || m == "application/vnd.apple.mpegurl" -> b.setMimeType(MimeTypes.APPLICATION_M3U8)
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
        val startId = items.getOrNull(pendingIndex)?.mediaId
        val startPos = startId?.let { positions.getLong(it, 0L) }?.takeIf { it > 5_000 } ?: 0L
        c.setMediaItems(items, pendingIndex, startPos)
        c.prepare()
        c.play()
        if (startPos > 0) indicate(getString(R.string.resume_from, fmt(startPos)), 1500)
    }

    override fun onStop() {
        super.onStop()
        savePosition()
        val c = controller
        if (c != null && !inPip && !isAudioOnly()) c.pause()
        b.playerView.player = null
        c?.removeListener(playerListener)
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controller = null
    }

    // ---- resume positions ----
    private fun savePosition() {
        val c = controller ?: return
        val id = c.currentMediaItem?.mediaId ?: return
        val pos = c.currentPosition; val dur = c.duration
        if (dur <= 0 || pos < 5_000) return
        if (dur - pos < 8_000) positions.edit().remove(id).apply() else positions.edit().putLong(id, pos).apply()
    }

    private fun restorePosition(item: MediaItem?) {
        val c = controller ?: return
        val id = item?.mediaId ?: return
        val saved = positions.getLong(id, 0L)
        if (saved > 5_000) {
            c.seekTo(saved)
            indicate(getString(R.string.resume_from, fmt(saved)), 1200)
        }
    }

    private fun maybeAutoRotate(w: Int, h: Int) {
        if (userRotated || w <= 0 || h <= 0) return
        val id = controller?.currentMediaItem?.mediaId ?: return
        if (autoRotatedFor == id) return
        autoRotatedFor = id
        val wide = w > h
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        if (wide && !landscape) requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        else if (!wide && landscape) requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
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
        b.btnAspect.visibility = if (audioOnly) View.GONE else View.VISIBLE
    }

    private fun updateTitle(item: MediaItem?) {
        val t = item?.mediaMetadata?.title?.toString() ?: ""
        b.title.text = t
        b.audioTitle.text = t
    }

    // ---- menus ----
    private fun showMore(anchor: View) {
        val m = PopupMenu(this, anchor)
        m.menu.add(0, 1, 0, R.string.audio_track)
        m.menu.add(0, 2, 1, R.string.subtitles)
        m.menu.add(0, 3, 2, R.string.load_subtitle)
        m.menu.add(0, 4, 3, R.string.sleep_timer)
        m.menu.add(0, 5, 4, R.string.screenshot)
        m.menu.add(0, 6, 5, R.string.speed)
        m.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> showTracks(C.TRACK_TYPE_AUDIO, getString(R.string.audio_track))
                2 -> showTracks(C.TRACK_TYPE_TEXT, getString(R.string.subtitles), allowDisable = true)
                3 -> pickSubtitle.launch(arrayOf("*/*"))
                4 -> showSleepTimer()
                5 -> screenshot()
                6 -> showSpeed()
            }
            true
        }
        m.show()
    }

    private fun showTracks(type: Int, title: String, allowDisable: Boolean = false) {
        val c = controller ?: return
        TrackSelectionDialogBuilder(this, title, c, type)
            .setShowDisableOption(allowDisable)
            .setAllowAdaptiveSelections(type == C.TRACK_TYPE_VIDEO)
            .build().show()
    }

    private fun showSpeed() {
        val c = controller ?: return
        val speeds = floatArrayOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f)
        val labels = speeds.map { if (it == 1f) "Normal" else "${it}x" }.toTypedArray()
        val current = speeds.indexOfFirst { abs(it - c.playbackParameters.speed) < 0.01f }.coerceAtLeast(3)
        AlertDialog.Builder(this).setTitle(R.string.speed).setSingleChoiceItems(labels, current) { d, i -> c.setPlaybackSpeed(speeds[i]); indicate(labels[i]); d.dismiss() }.show()
    }

    private fun showSleepTimer() {
        val options = arrayOf("Off", "15 min", "30 min", "45 min", "60 min", "End of this video")
        AlertDialog.Builder(this).setTitle(R.string.sleep_timer).setItems(options) { _, i ->
            sleepRunnable?.let { b.root.removeCallbacks(it) }; sleepRunnable = null
            val c = controller ?: return@setItems
            val delay = when (i) { 1 -> 15; 2 -> 30; 3 -> 45; 4 -> 60; 5 -> -1; else -> 0 }
            if (delay == 0) { indicate("Sleep timer off"); return@setItems }
            val ms = if (delay > 0) delay * 60_000L else (c.duration - c.currentPosition).coerceAtLeast(1000)
            val r = Runnable { controller?.pause(); indicate("Sleep timer: paused", 1500) }
            sleepRunnable = r
            b.root.postDelayed(r, ms)
            indicate(if (delay > 0) "Sleep in $delay min" else "Pause at end of video")
        }.show()
    }

    private fun addSubtitle(uri: Uri) {
        val c = controller ?: return
        val current = c.currentMediaItem ?: return
        runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        val name = uri.lastPathSegment?.lowercase() ?: ""
        val mime = when {
            name.endsWith(".vtt") -> MimeTypes.TEXT_VTT
            name.endsWith(".ass") || name.endsWith(".ssa") -> MimeTypes.TEXT_SSA
            name.endsWith(".ttml") || name.endsWith(".xml") -> MimeTypes.APPLICATION_TTML
            else -> MimeTypes.APPLICATION_SUBRIP
        }
        val sub = MediaItem.SubtitleConfiguration.Builder(uri).setMimeType(mime).setLanguage("und").setLabel("External").setSelectionFlags(C.SELECTION_FLAG_DEFAULT).build()
        val pos = c.currentPosition
        val replaced = current.buildUpon().setSubtitleConfigurations(listOf(sub)).build()
        c.replaceMediaItem(c.currentMediaItemIndex, replaced)
        c.trackSelectionParameters = c.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).setPreferredTextLanguage("und").build()
        c.seekTo(pos)
        indicate("Subtitle loaded")
    }

    private fun screenshot() {
        val tv = b.playerView.videoSurfaceView as? TextureView
        val bmp: Bitmap = tv?.bitmap ?: run { Toast.makeText(this, "No frame to capture", Toast.LENGTH_SHORT).show(); return }
        val name = "Grabbit_${System.currentTimeMillis()}.jpg"
        runCatching {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                if (android.os.Build.VERSION.SDK_INT >= 29) put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Grabbit")
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: error("insert failed")
            contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
            indicate(getString(R.string.screenshot_saved), 1500)
        }.onFailure { Toast.makeText(this, "Screenshot failed: ${it.message}", Toast.LENGTH_SHORT).show() }
    }

    private fun cycleAspect() {
        aspectIndex = (aspectIndex + 1) % aspectModes.size
        b.playerView.resizeMode = aspectModes[aspectIndex].first
        indicate(aspectModes[aspectIndex].second)
    }

    private fun setLocked(lock: Boolean) {
        locked = lock
        b.btnUnlock.visibility = if (lock) View.VISIBLE else View.GONE
        b.btnLock.setImageResource(if (lock) R.drawable.ic_lock else R.drawable.ic_lock_open)
        b.playerView.useController = !lock
        if (lock) { b.playerView.hideController(); b.topBar.visibility = View.GONE; hideSystemBars(); indicate(getString(R.string.locked), 1500) }
        else b.playerView.showController()
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
        b.playerView.useController = !isInPictureInPictureMode && !locked
        b.topBar.visibility = if (isInPictureInPictureMode) View.GONE else b.topBar.visibility
        b.btnUnlock.visibility = if (isInPictureInPictureMode || !locked) View.GONE else View.VISIBLE
        if (isInPictureInPictureMode) b.playerView.hideController()
    }

    // ---- gestures ----
    private fun setupGestures() {
        var mode = 0 // 0 none, 1 brightness, 2 volume, 3 scrub
        var startBrightness = -1f
        var startVolume = -1
        var scrubStartPos = 0L
        var scrubTarget = 0L
        var boosted = false
        var speedBefore = 1f

        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean { mode = 0; return true }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (locked) { b.btnUnlock.visibility = View.VISIBLE; b.btnUnlock.postDelayed({ if (locked) b.btnUnlock.visibility = View.VISIBLE }, 0); indicate(getString(R.string.locked), 900); return true }
                if (b.playerView.isControllerFullyVisible) b.playerView.hideController() else b.playerView.showController()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (locked) return true
                val c = controller ?: return true
                val forward = e.x > b.root.width / 2f
                if (forward) c.seekForward() else c.seekBack()
                indicate(if (forward) "+10 s  ▶▶" else "◀◀  -10 s")
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                if (locked) return
                val c = controller ?: return
                if (!c.isPlaying) return
                speedBefore = c.playbackParameters.speed
                c.setPlaybackSpeed(2f); boosted = true
                indicate("2x ▶▶", 60_000)
            }

            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
                e1 ?: return false
                if (locked || boosted) return false
                val c = controller
                val totalDy = e1.y - e2.y
                val totalDx = e2.x - e1.x
                if (mode == 0) {
                    if (abs(totalDy) < 24f && abs(totalDx) < 24f) return false
                    mode = if (abs(totalDx) > abs(totalDy)) 3 else if (e1.x < b.root.width / 2f) 1 else 2
                    startBrightness = window.attributes.screenBrightness.takeIf { it >= 0f } ?: 0.5f
                    startVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                    scrubStartPos = c?.currentPosition ?: 0L
                }
                when (mode) {
                    1 -> {
                        val v = (startBrightness + totalDy / (b.root.height * 0.6f)).coerceIn(0.02f, 1f)
                        window.attributes = window.attributes.apply { screenBrightness = v }
                        indicate("☀  ${(v * 100).toInt()}%")
                    }
                    2 -> {
                        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                        val v = (startVolume + totalDy / (b.root.height * 0.6f) * max).toInt().coerceIn(0, max)
                        audio.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0)
                        indicate((if (v == 0) "🔇  " else "🔊  ") + (v * 100 / max) + "%")
                    }
                    3 -> {
                        c ?: return true
                        val dur = c.duration.takeIf { it > 0 } ?: return true
                        // Full-width swipe = 90 s; fine control near the start of the gesture.
                        val delta = (totalDx / b.root.width * 90_000f).toLong()
                        scrubTarget = (scrubStartPos + delta).coerceIn(0, dur)
                        val sign = if (delta >= 0) "+" else "-"
                        indicate("$sign${fmt(abs(delta))}   ${fmt(scrubTarget)} / ${fmt(dur)}", 60_000)
                    }
                }
                return true
            }
        })
        // PlayerView.performClick() toggles the controller itself, so we must not call it here.
        b.playerView.setOnTouchListener { _, e ->
            detector.onTouchEvent(e)
            if (e.action == MotionEvent.ACTION_UP || e.action == MotionEvent.ACTION_CANCEL) {
                if (boosted) { controller?.setPlaybackSpeed(speedBefore); boosted = false; indicate("${if (speedBefore == 1f) "Normal" else "${speedBefore}x"} speed", 600) }
                if (mode == 3) { controller?.seekTo(scrubTarget); indicate(fmt(scrubTarget), 600) }
                mode = 0
            }
            true
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun fmt(ms: Long): String {
        val s = ms / 1000; val h = s / 3600; val m = (s % 3600) / 60; val r = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, r) else "%d:%02d".format(m, r)
    }

    private fun indicate(text: String, hideAfterMs: Long = 700) {
        b.indicator.text = text
        b.indicator.visibility = View.VISIBLE
        b.indicator.removeCallbacks(hideIndicator)
        b.indicator.postDelayed(hideIndicator, hideAfterMs)
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
