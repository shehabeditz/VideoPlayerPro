package com.shehab360.videoplayerpro

import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.Icon
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.provider.Settings
import android.util.Rational
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.ScaleGestureDetector
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView
import androidx.media3.ui.TrackSelectionDialogBuilder
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import com.shehab360.videoplayerpro.databinding.ActivityPlayerBinding
import java.io.File
import java.io.OutputStream
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * প্লেয়ার স্ক্রিন। আসল ExoPlayer এর মালিক [PlaybackService]; এই Activity শুধু তার সাথে PlayerView জুড়ে দেয়।
 * তাই Back চাপলেও (Background Play চালু থাকলে) শব্দ চলতে থাকে, নোটিফিকেশন/লক-স্ক্রিন কন্ট্রোল পাওয়া যায়।
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_VIDEO_LIST = "VIDEO_LIST"
        const val EXTRA_VIDEO_TITLES = "VIDEO_TITLES"
        const val EXTRA_CURRENT_INDEX = "CURRENT_INDEX"
        const val EXTRA_VIDEO_URI = "VIDEO_URI"
        const val EXTRA_VIDEO_TITLE = "VIDEO_TITLE"
        const val EXTRA_AUDIO_ONLY = "AUDIO_ONLY"

        private const val ACTION_PIP_TOGGLE = "com.shehab360.videoplayerpro.PIP_TOGGLE"
        private const val SEEK_FULL_WIDTH_MS = 180_000f
        private const val RESUME_MIN_MS = 5000L
        private const val UNLOCK_VISIBLE_MS = 2500L
    }

    private lateinit var binding: ActivityPlayerBinding
    private var player: ExoPlayer? = null
    private var controllerFuture: com.google.common.util.concurrent.ListenableFuture<MediaController>? = null
    private var pendingIntent: Intent? = null

    private var audioOnly = false
    private var isLocked = false
    private var autoOrientationDoneFor: String? = null

    // ---------- Gesture / audio ----------
    private lateinit var audioManager: AudioManager
    private lateinit var gestureDetector: GestureDetector
    private lateinit var scaleDetector: ScaleGestureDetector
    private var maxVolume = 1
    private var volume = 0
    private var brightness = 0.5f
    private var boostUnits = 0
    private var videoScale = 1f

    private enum class GestureMode { NONE, SEEK, BRIGHTNESS, VOLUME }

    private var gestureMode = GestureMode.NONE
    private var seekStartPosition = 0L
    private var pendingSeekPosition = -1L
    private var isLongPressSpeed = false
    private var speedBeforeLongPress = 1f

    // ---------- A-B repeat ----------
    private var abA = -1L
    private var abB = -1L
    private val abHandler = Handler(Looper.getMainLooper())
    private val abRunnable = object : Runnable {
        override fun run() {
            val p = player
            if (p != null && abA >= 0 && abB > abA) {
                if (p.currentPosition >= abB) p.seekTo(abA)
                abHandler.postDelayed(this, 150)
            }
        }
    }

    private val uiHandler = Handler(Looper.getMainLooper())
    private val hideInfoRunnable = Runnable {
        binding.tvSwipeInfo.animate().alpha(0f).setDuration(300).withEndAction {
            binding.tvSwipeInfo.visibility = View.GONE
            binding.tvSwipeInfo.alpha = 1f
        }
    }
    private val hideUnlockRunnable = Runnable { binding.btnUnlock.visibility = View.GONE }
    private val restoreSeekParamsRunnable = Runnable { player?.setSeekParameters(SeekParameters.DEFAULT) }

    // ---------- Controller views ----------
    private lateinit var tvVideoTitle: TextView
    private lateinit var btnBack: ImageButton
    private lateinit var btnPlayPause: ImageButton
    private lateinit var btnPrevious: ImageButton
    private lateinit var btnNext: ImageButton
    private lateinit var btnMenu: ImageButton
    private lateinit var btnLock: ImageButton
    private lateinit var btnRotate: ImageButton
    private lateinit var btnSubtitle: ImageButton
    private lateinit var btnResizeScreen: ImageButton
    private lateinit var btnPip: ImageButton
    private lateinit var btnScreenshot: ImageButton

    private val notificationLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val subtitlePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) addExternalSubtitle(uri)
    }

    private val pipReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_PIP_TOGGLE) togglePlayPause()
        }
    }

    // ==========================================
    // LIFECYCLE
    // ==========================================
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupWindow()

        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)

        val pv = binding.playerView
        tvVideoTitle = pv.findViewById(R.id.tvVideoTitle)
        btnBack = pv.findViewById(R.id.btnBack)
        btnPlayPause = pv.findViewById(R.id.btnPlayPause)
        btnPrevious = pv.findViewById(R.id.btnPrevious)
        btnNext = pv.findViewById(R.id.btnNext)
        btnMenu = pv.findViewById(R.id.btnMenu)
        btnLock = pv.findViewById(R.id.btnLock)
        btnRotate = pv.findViewById(R.id.btnRotate)
        btnSubtitle = pv.findViewById(R.id.btnSubtitle)
        btnResizeScreen = pv.findViewById(R.id.btnResizeScreen)
        btnPip = pv.findViewById(R.id.btnPip)
        btnScreenshot = pv.findViewById(R.id.btnScreenshot)

        // notch / system bar এর জায়গা কন্ট্রোলারে ছাড়া
        pv.findViewById<View>(R.id.controllerRoot)?.applySystemBarsPadding()

        applyOrientationPreference()
        applySubtitleStyle()
        setupButtons()
        setupGestures()

        if (supportsPip()) {
            ContextCompat.registerReceiver(
                this, pipReceiver, IntentFilter(ACTION_PIP_TOGGLE), ContextCompat.RECEIVER_NOT_EXPORTED
            )
        } else {
            btnPip.visibility = View.GONE
        }

        pendingIntent = intent
        connectToService()
    }

    private fun setupWindow() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                else WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        hideSystemUI()
    }

    private fun hideSystemUI() {
        WindowInsetsControllerCompat(window, binding.root).let {
            it.hide(WindowInsetsCompat.Type.systemBars())
            it.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUI()
    }

    private fun connectToService() {
        val serviceIntent = Intent(this, PlaybackService::class.java)
        startService(serviceIntent)
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        controllerFuture = future
        future.addListener({
            if (isDestroyed || isFinishing) return@addListener
            val p = PlaybackService.player
            if (p == null) {
                Toast.makeText(this, R.string.player_start_failed, Toast.LENGTH_LONG).show()
                finish()
            } else {
                onPlayerReady(p)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun onPlayerReady(p: ExoPlayer) {
        player = p
        binding.playerView.player = p
        p.addListener(playerListener)
        p.repeatMode = AppPrefs.repeatMode
        p.shuffleModeEnabled = AppPrefs.shuffle
        applyPlayingUi()
        pendingIntent?.let { handleIntent(it) }
        pendingIntent = null
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (player == null) pendingIntent = intent else handleIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        setVideoTrackDisabled(false)
    }

    override fun onStop() {
        super.onStop()
        val p = player ?: return
        if (!AppPrefs.backgroundPlay) {
            // Home/স্ক্রিন অফ/PiP উইন্ডো "X" — সবখানেই থামে। (PiP চলাকালীন Activity stop হয় না)
            p.pause()
        } else if (p.isPlaying) {
            setVideoTrackDisabled(true)   // শুধু অডিও চলবে, ভিডিও ডিকোড বন্ধ রেখে ব্যাটারি বাঁচে
        }
    }

    override fun onDestroy() {
        uiHandler.removeCallbacksAndMessages(null)
        abHandler.removeCallbacksAndMessages(null)
        if (supportsPip()) runCatching { unregisterReceiver(pipReceiver) }

        val p = player
        p?.removeListener(playerListener)
        binding.playerView.player = null
        if (isLongPressSpeed) p?.setPlaybackSpeed(speedBeforeLongPress)
        setVideoTrackDisabled(false)

        if (!isChangingConfigurations && p != null) {
            val keepPlaying = AppPrefs.backgroundPlay && p.isPlaying
            if (!keepPlaying) {
                p.pause()               // পজিশন সেভ হয় (সার্ভিসের লিসেনার)
                p.clearMediaItems()
                PlaybackService.instance?.shutdownIfIdle()
            }
        }
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        player = null
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // স্ক্রিনের আকার প্রতিবার viewWidth()/viewHeight() থেকে সরাসরি পড়া হয়, তাই আলাদা আপডেট লাগে না
        hideSystemUI()
    }

    // ==========================================
    // INTENT → PLAYLIST ("Open with", Share, অ্যাপের লিস্ট, নোটিফিকেশন)
    // ==========================================
    private data class Parsed(val items: List<VideoItem>, val index: Int, val audio: Boolean)

    private fun handleIntent(src: Intent) {
        val p = player ?: return
        if (src.action == PlaybackService.ACTION_OPEN_PLAYER) {
            // নোটিফিকেশন থেকে ফেরা: চলমান সেশনই দেখানো
            if (p.mediaItemCount > 0) {
                audioOnly = p.currentMediaItem?.mediaMetadata?.extras?.getBoolean(PlaybackService.EXTRA_AUDIO_FLAG, false) == true
                applyAudioMode()
                updateTitle()
            } else {
                finish()
            }
            return
        }
        val parsed = parsePlaylist(src)
        if (parsed == null) {
            Toast.makeText(this, R.string.no_video_to_play, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        audioOnly = parsed.audio
        applyAudioMode()
        startPlaylist(parsed.items, parsed.index, parsed.audio)
    }

    private fun parsePlaylist(src: Intent): Parsed? {
        val audio = src.getBooleanExtra(EXTRA_AUDIO_ONLY, false)
        val uriList = src.getStringArrayListExtra(EXTRA_VIDEO_LIST)
        if (!uriList.isNullOrEmpty()) {
            val titles = src.getStringArrayListExtra(EXTRA_VIDEO_TITLES)
            val items = uriList.mapIndexed { i, u -> VideoItem(u, titles?.getOrNull(i) ?: titleFromUri(Uri.parse(u))) }
            val index = src.getIntExtra(EXTRA_CURRENT_INDEX, 0).coerceIn(0, items.size - 1)
            return Parsed(items, index, audio)
        }
        val external: Uri? = when (src.action) {
            Intent.ACTION_VIEW -> src.data
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(src, Intent.EXTRA_STREAM, Uri::class.java)
            else -> null
        } ?: src.getStringExtra(EXTRA_VIDEO_URI)?.let { Uri.parse(it) }
        external ?: return null
        val title = src.getStringExtra(EXTRA_VIDEO_TITLE) ?: titleFromUri(external)
        return Parsed(listOf(VideoItem(external.toString(), title)), 0, audio)
    }

    private fun titleFromUri(uri: Uri): String {
        if (uri.scheme == "content") {
            try {
                contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0) c.getString(idx)?.let { return it }
                    }
                }
            } catch (e: Exception) {
                // নাম না পেলে নিচের ফলব্যাক
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: getString(R.string.unknown_video)
    }

    private fun buildMediaItem(item: VideoItem, audio: Boolean, withSidecarSubs: Boolean): MediaItem {
        val meta = MediaMetadata.Builder().setTitle(item.title)
        if (audio) meta.setExtras(Bundle().apply { putBoolean(PlaybackService.EXTRA_AUDIO_FLAG, true) })
        val builder = MediaItem.Builder()
            .setUri(Uri.parse(item.uri))
            .setMediaId(item.uri)
            .setMediaMetadata(meta.build())
        if (withSidecarSubs && !audio) {
            val subs = findSidecarSubtitles(item.uri)
            if (subs.isNotEmpty()) builder.setSubtitleConfigurations(subs)
        }
        return builder.build()
    }

    /** ভিডিওর পাশের একই-নামের .srt/.vtt/.ass (শুধু যেসব ডিভাইসে ফাইল পড়ার অনুমতি আছে; নইলে ম্যানুয়াল "Load file") */
    private fun findSidecarSubtitles(uriString: String): List<MediaItem.SubtitleConfiguration> {
        val result = ArrayList<MediaItem.SubtitleConfiguration>()
        try {
            val uri = Uri.parse(uriString)
            if (uri.scheme != "content") return result
            @Suppress("DEPRECATION")
            val path = contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            } ?: return result
            val base = path.substringBeforeLast('.')
            for (ext in listOf("srt", "vtt", "ass", "ssa")) {
                val f = File("$base.$ext")
                if (f.exists() && f.canRead()) {
                    result.add(
                        MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(f))
                            .setMimeType(subtitleMime(ext))
                            .setLabel(f.name)
                            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                            .build()
                    )
                    break
                }
            }
        } catch (e: Exception) {
            // অনুমতি/কলাম নেই: সাবটাইটেল ছাড়াই চলবে
        }
        return result
    }

    private fun subtitleMime(ext: String): String = when (ext.lowercase()) {
        "vtt" -> MimeTypes.TEXT_VTT
        "ass", "ssa" -> MimeTypes.TEXT_SSA
        "ttml", "xml", "dfxp" -> MimeTypes.APPLICATION_TTML
        else -> MimeTypes.APPLICATION_SUBRIP
    }

    private fun startPlaylist(items: List<VideoItem>, index: Int, audio: Boolean) {
        val p = player ?: return
        val startUri = items[index].uri
        val saved = if (audio) 0L else PlaybackStore.getPosition(startUri)

        fun begin(startPos: Long) {
            val pl = player ?: return
            val mediaItems = items.mapIndexed { i, item -> buildMediaItem(item, audio, withSidecarSubs = i == index) }
            pl.setMediaItems(mediaItems, index, startPos)
            pl.prepare()
            pl.playWhenReady = true
        }

        if (saved > RESUME_MIN_MS) {
            if (AppPrefs.askResume) {
                p.pause()
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.resume_title)
                    .setMessage(getString(R.string.resume_message, Fmt.duration(saved)))
                    .setCancelable(false)
                    .setPositiveButton(R.string.resume) { _, _ -> begin(saved) }
                    .setNegativeButton(R.string.start_over) { _, _ -> begin(0L) }
                    .show()
            } else {
                begin(saved)
            }
        } else {
            begin(0L)
        }
    }

    private fun applyAudioMode() {
        if (audioOnly) {
            binding.playerView.defaultArtwork = ContextCompat.getDrawable(this, R.drawable.ic_music)
            binding.playerView.artworkDisplayMode = androidx.media3.ui.PlayerView.ARTWORK_DISPLAY_MODE_FIT
            btnScreenshot.visibility = View.GONE
            btnResizeScreen.visibility = View.GONE
        }
    }

    // ==========================================
    // BUTTONS
    // ==========================================
    private fun setupButtons() {
        btnBack.setOnClickListener { finish() }
        btnPlayPause.setOnClickListener { togglePlayPause() }

        btnNext.setOnClickListener {
            val p = player ?: return@setOnClickListener
            if (p.hasNextMediaItem()) p.seekToNextMediaItem()
            else Toast.makeText(this, R.string.last_video, Toast.LENGTH_SHORT).show()
        }
        btnPrevious.setOnClickListener {
            val p = player ?: return@setOnClickListener
            if (p.currentPosition > 3000L) p.seekTo(0)
            else if (p.hasPreviousMediaItem()) p.seekToPreviousMediaItem()
            else Toast.makeText(this, R.string.first_video, Toast.LENGTH_SHORT).show()
        }

        btnMenu.setOnClickListener { showPlayerMenu(it) }
        btnSubtitle.setOnClickListener { showSubtitleDialog() }
        btnResizeScreen.setOnClickListener { showResizeDialog() }
        btnRotate.setOnClickListener {
            // আসল ওরিয়েন্টেশন দেখে উল্টানো (নিজস্ব ফ্ল্যাগ নেই)
            val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
        btnLock.setOnClickListener { lockControls() }
        binding.btnUnlock.setOnClickListener { unlockControls() }
        btnPip.setOnClickListener { enterPiPMode() }
        btnScreenshot.setOnClickListener { captureScreenshot() }
    }

    private fun togglePlayPause() {
        val p = player ?: return
        if (p.playbackState == Player.STATE_ENDED) {
            p.seekTo(0)
            p.play()
        } else if (p.isPlaying) p.pause() else p.play()
    }

    private fun updatePlayPauseIcon() {
        val playing = player?.isPlaying == true
        btnPlayPause.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play_circle)
        btnPlayPause.contentDescription = getString(if (playing) R.string.pause else R.string.play)
    }

    private fun updateTitle() {
        val p = player ?: return
        val title = p.mediaMetadata.title?.toString()
            ?: p.currentMediaItem?.mediaMetadata?.title?.toString()
            ?: getString(R.string.unknown_video)
        tvVideoTitle.text = title
    }

    private fun applyPlayingUi() {
        updatePlayPauseIcon()
        updateTitle()
        if (player?.isPlaying == true) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    // ==========================================
    // PLAYER LISTENER
    // ==========================================
    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            applyPlayingUi()
            updatePipParams()
        }

        override fun onPlaybackStateChanged(playbackState: Int) = updatePlayPauseIcon()

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            updateTitle()
            clearAbRepeat()
        }

        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) = updateTitle()

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            applyAutoOrientation(videoSize)
            updatePipParams()
        }

        override fun onPlayerError(error: PlaybackException) {
            Toast.makeText(
                this@PlayerActivity,
                getString(R.string.cant_play_video, error.errorCodeName),
                Toast.LENGTH_LONG
            ).show()
            // প্লেলিস্টে পরের ভিডিও থাকলে সেটায় এগিয়ে যাওয়া (শেষ আইটেমে হলে লুপ এড়াতে থামা)
            val p = player ?: return
            if (p.currentMediaItemIndex < p.mediaItemCount - 1) {
                p.seekToNextMediaItem()
                p.prepare()
                p.play()
            }
        }
    }

    // ==========================================
    // ORIENTATION
    // ==========================================
    private fun applyOrientationPreference() {
        requestedOrientation = when (AppPrefs.orientationMode) {
            1 -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            2 -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            3 -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED   // 0 = ভিডিওর আকার জানার পর নিচে ঠিক হয়
        }
    }

    /** ভিডিওর আকার দেখে অটো landscape/portrait (প্রতি ভিডিওতে একবার) */
    private fun applyAutoOrientation(size: VideoSize) {
        if (AppPrefs.orientationMode != 0 || audioOnly) return
        if (size.width <= 0 || size.height <= 0) return
        if (isInPip()) return
        val id = player?.currentMediaItem?.mediaId ?: return
        if (autoOrientationDoneFor == id) return
        autoOrientationDoneFor = id
        val w = size.width * size.pixelWidthHeightRatio
        requestedOrientation = if (w >= size.height) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        else ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
    }

    // ==========================================
    // SAFE HELPERS
    // ==========================================
    private fun viewWidth(): Int =
        binding.playerView.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels

    private fun viewHeight(): Int =
        binding.playerView.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels

    /** লোড না হলে বা লাইভ স্ট্রিম হলে null (C.TIME_UNSET ঋণাত্মক, তাই coerceIn ক্র্যাশ করত) */
    private fun knownDuration(): Long? {
        val d = player?.duration ?: return null
        return if (d == C.TIME_UNSET || d <= 0L) null else d
    }

    @ChecksSdkIntAtLeast(api = Build.VERSION_CODES.O)
    private fun supportsPip(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    private fun isInPip(): Boolean = supportsPip() && isInPictureInPictureMode

    private fun currentBrightness(): Float {
        val b = window.attributes.screenBrightness
        if (b >= 0f) return b.coerceIn(0.01f, 1f)
        return try {
            (Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f).coerceIn(0.01f, 1f)
        } catch (e: Settings.SettingNotFoundException) {
            0.5f
        }
    }

    private fun setVideoTrackDisabled(disabled: Boolean) {
        val p = player ?: return
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, disabled)
            .build()
    }

    // ==========================================
    // VOLUME BOOST (সার্ভিসের LoudnessEnhancer)
    // ==========================================
    private fun currentBoostUnits(): Int {
        val gain = PlaybackService.instance?.boostGainMb ?: 0
        return (gain.toFloat() / PlaybackService.MAX_BOOST_MB * maxVolume).roundToInt()
    }

    private fun applyVolumeUnits(newVolume: Int) {
        val svc = PlaybackService.instance
        if (newVolume <= maxVolume) {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVolume, 0)
            boostUnits = 0
            svc?.setBoostGain(0)
        } else {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxVolume, 0)
            boostUnits = newVolume - maxVolume
            svc?.setBoostGain((boostUnits.toFloat() / maxVolume * PlaybackService.MAX_BOOST_MB).toInt())
        }
    }

    // ==========================================
    // GESTURES
    // ==========================================
    private fun setupGestures() {
        scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                if (isLocked || audioOnly) return false
                videoScale = (videoScale * detector.scaleFactor).coerceIn(1f, 4f)
                binding.playerView.videoSurfaceView?.let {
                    it.scaleX = videoScale
                    it.scaleY = videoScale
                }
                showSwipeInfo("🔍 ${(videoScale * 100).toInt()}%")
                return true
            }
        })

        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean {
                if (isLocked) return false
                gestureMode = GestureMode.NONE
                pendingSeekPosition = -1L
                val sysVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                boostUnits = currentBoostUnits()
                volume = if (boostUnits > 0) maxVolume + boostUnits else sysVol
                brightness = currentBrightness()
                seekStartPosition = player?.currentPosition ?: 0L
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                if (isLocked || !AppPrefs.longPressSpeed || scaleDetector.isInProgress) return
                val p = player ?: return
                if (!p.isPlaying) return
                speedBeforeLongPress = p.playbackParameters.speed
                isLongPressSpeed = true
                p.setPlaybackSpeed(2f)
                showSwipeInfo("⏩ 2x")
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (isLocked) return false
                val p = player ?: return false
                val sec = AppPrefs.doubleTapSeconds.coerceAtLeast(1)
                val ms = sec * 1000L
                if (e.x < viewWidth() / 2) {
                    p.seekTo((p.currentPosition - ms).coerceAtLeast(0))
                    flashSeek(true, sec)
                } else {
                    var target = p.currentPosition + ms
                    knownDuration()?.let { target = target.coerceAtMost(it) }
                    p.seekTo(target)
                    flashSeek(false, sec)
                }
                return true
            }

            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
                if (isLocked || e1 == null || !AppPrefs.gesturesEnabled) return false
                if (scaleDetector.isInProgress || isLongPressSpeed || e2.pointerCount > 1) return false

                val totalDx = e2.x - e1.x
                val totalDy = e1.y - e2.y

                // প্রথম নড়াচড়াতেই মোড লক হয়; টানের মাঝপথে বদলায় না
                if (gestureMode == GestureMode.NONE) {
                    gestureMode = when {
                        abs(totalDx) > abs(totalDy) -> GestureMode.SEEK
                        e1.x < viewWidth() / 2 -> GestureMode.BRIGHTNESS
                        else -> GestureMode.VOLUME
                    }
                }
                when (gestureMode) {
                    GestureMode.SEEK -> handleSeekGesture(totalDx)
                    GestureMode.BRIGHTNESS -> handleBrightnessGesture(totalDy)
                    GestureMode.VOLUME -> handleVolumeGesture(totalDy)
                    GestureMode.NONE -> Unit
                }
                return true
            }
        })

        binding.playerView.setOnTouchListener { _, event ->
            if (isLocked) {
                // লক অবস্থায় ট্যাপে Unlock বাটন কিছুক্ষণ দেখায়
                if (event.actionMasked == MotionEvent.ACTION_DOWN) showUnlockTemporarily()
                return@setOnTouchListener true
            }
            scaleDetector.onTouchEvent(event)
            gestureDetector.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_UP -> {
                    if (gestureMode == GestureMode.SEEK && pendingSeekPosition >= 0L) {
                        player?.seekTo(pendingSeekPosition)   // আঙুল তুললে একবারই seek
                    }
                    endTouch()
                }
                MotionEvent.ACTION_CANCEL -> endTouch()
            }
            false
        }
    }

    private fun endTouch() {
        gestureMode = GestureMode.NONE
        pendingSeekPosition = -1L
        if (isLongPressSpeed) {
            isLongPressSpeed = false
            player?.setPlaybackSpeed(speedBeforeLongPress)
        }
    }

    private fun handleSeekGesture(totalDx: Float) {
        val duration = knownDuration() ?: return
        val delta = (totalDx / viewWidth()) * SEEK_FULL_WIDTH_MS
        val target = (seekStartPosition + delta.toLong()).coerceIn(0L, duration)
        pendingSeekPosition = target
        val diffSec = ((target - seekStartPosition) / 1000).toInt()
        val sign = if (diffSec >= 0) "↷ +" else "↶ "
        showSwipeInfo("$sign$diffSec s\n${Fmt.duration(target)}")
    }

    private fun handleBrightnessGesture(totalDy: Float) {
        val newB = (brightness + totalDy / viewHeight()).coerceIn(0.01f, 1.0f)
        val lp = window.attributes
        lp.screenBrightness = newB
        window.attributes = lp
        showSwipeInfo("☀️ ${(newB * 100).toInt()}%")
    }

    private fun handleVolumeGesture(totalDy: Float) {
        val change = (totalDy / viewHeight()) * (maxVolume * 2)
        val newVolume = (volume + change).toInt().coerceIn(0, maxVolume * 2)
        applyVolumeUnits(newVolume)
        val percent = newVolume * 100 / maxVolume
        showSwipeInfo(if (newVolume == 0) "🔇 0%" else "🔊 $percent%")
    }

    private fun showSwipeInfo(text: String) {
        binding.tvSwipeInfo.animate().cancel()
        uiHandler.removeCallbacks(hideInfoRunnable)
        binding.tvSwipeInfo.apply { alpha = 1f; visibility = View.VISIBLE; this.text = text }
        uiHandler.postDelayed(hideInfoRunnable, 1500)
    }

    /** YouTube স্টাইল ডাবল-ট্যাপ অ্যানিমেশন */
    private fun flashSeek(left: Boolean, seconds: Int) {
        val tv = if (left) binding.tvSeekLeft else binding.tvSeekRight
        tv.text = if (left) "⏪ ${seconds}s" else "${seconds}s ⏩"
        tv.animate().cancel()
        tv.visibility = View.VISIBLE
        tv.alpha = 1f
        tv.animate().alpha(0f).setStartDelay(450).setDuration(300).withEndAction { tv.visibility = View.GONE }.start()
    }

    // ==========================================
    // LOCK / UNLOCK
    // ==========================================
    private fun lockControls() {
        isLocked = true
        binding.playerView.useController = false
        binding.playerView.hideController()
        showUnlockTemporarily()
        Toast.makeText(this, R.string.screen_locked, Toast.LENGTH_SHORT).show()
    }

    private fun showUnlockTemporarily() {
        binding.btnUnlock.visibility = View.VISIBLE
        uiHandler.removeCallbacks(hideUnlockRunnable)
        uiHandler.postDelayed(hideUnlockRunnable, UNLOCK_VISIBLE_MS)
    }

    private fun unlockControls() {
        isLocked = false
        uiHandler.removeCallbacks(hideUnlockRunnable)
        binding.btnUnlock.visibility = View.GONE
        binding.playerView.useController = true
        binding.playerView.showController()
    }

    // ==========================================
    // PICTURE IN PICTURE
    // ==========================================
    private fun buildPipParams(): PictureInPictureParams {
        val b = PictureInPictureParams.Builder()
        val size = player?.videoSize
        if (size != null && size.width > 0 && size.height > 0) {
            val ratio = (size.width.toFloat() / size.height).coerceIn(0.42f, 2.39f)
            b.setAspectRatio(Rational((ratio * 1000).toInt(), 1000))
        }
        val playing = player?.isPlaying == true
        val title = getString(if (playing) R.string.pause else R.string.play)
        val pi = PendingIntent.getBroadcast(
            this, 0, Intent(ACTION_PIP_TOGGLE).setPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val icon = Icon.createWithResource(this, if (playing) R.drawable.ic_pip_pause else R.drawable.ic_pip_play)
        b.setActions(listOf(RemoteAction(icon, title, title, pi)))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            b.setAutoEnterEnabled(playing && !AppPrefs.backgroundPlay && !audioOnly)
        }
        return b.build()
    }

    private fun updatePipParams() {
        if (!supportsPip()) return
        try {
            setPictureInPictureParams(buildPipParams())
        } catch (e: Exception) {
            // PiP এই মুহূর্তে সম্ভব নয়
        }
    }

    private fun enterPiPMode() {
        if (!supportsPip() || audioOnly) return
        try {
            enterPictureInPictureMode(buildPipParams())
        } catch (e: Exception) {
            Toast.makeText(this, R.string.pip_unavailable, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Android 12+ এ setAutoEnterEnabled সামলায়; তার নিচে (API 26–30) হাতে PiP
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && supportsPip() &&
            !AppPrefs.backgroundPlay && !audioOnly && player?.isPlaying == true
        ) enterPiPMode()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        binding.playerView.useController = !isInPictureInPictureMode && !isLocked
        if (isInPictureInPictureMode) {
            binding.btnUnlock.visibility = View.GONE
        } else {
            if (!isLocked) binding.playerView.showController() else showUnlockTemporarily()
            // PiP উইন্ডো "X" দিয়ে বন্ধ হলে Activity আর দৃশ্যমান নয়: শব্দ থামানো
            if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) && !AppPrefs.backgroundPlay) {
                player?.pause()
            }
        }
    }

    // ==========================================
    // MENU & DIALOGS
    // ==========================================
    private fun showPlayerMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menuInflater.inflate(R.menu.player_options_menu, popup.menu)
        val m = popup.menu
        m.findItem(R.id.menu_bg_play).title =
            getString(if (AppPrefs.backgroundPlay) R.string.background_play_on else R.string.background_play_off)
        m.findItem(R.id.menu_repeat).title = getString(
            when (player?.repeatMode ?: Player.REPEAT_MODE_OFF) {
                Player.REPEAT_MODE_ALL -> R.string.repeat_all
                Player.REPEAT_MODE_ONE -> R.string.repeat_one
                else -> R.string.repeat_off
            }
        )
        m.findItem(R.id.menu_shuffle).title =
            getString(if (player?.shuffleModeEnabled == true) R.string.shuffle_on else R.string.shuffle_off)
        m.findItem(R.id.menu_night).title =
            getString(if (binding.nightOverlay.visibility == View.VISIBLE) R.string.night_mode_on else R.string.night_mode)

        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.menu_bg_play -> toggleBackgroundPlay()
                R.id.menu_repeat -> cycleRepeat()
                R.id.menu_shuffle -> toggleShuffle()
                R.id.menu_info -> showVideoInfoDialog()
                R.id.menu_audio -> showTrackDialog(getString(R.string.audio_track), C.TRACK_TYPE_AUDIO)
                R.id.menu_subtitle -> showSubtitleDialog()
                R.id.menu_speed -> showSpeedDialog()
                R.id.menu_ab -> cycleAbRepeat()
                R.id.menu_bookmarks -> showBookmarksDialog()
                R.id.menu_frame_next -> stepFrame(true)
                R.id.menu_frame_prev -> stepFrame(false)
                R.id.menu_equalizer -> showEqualizerDialog()
                R.id.menu_night -> toggleNightMode()
                R.id.menu_sleep -> showSleepTimerDialog()
                R.id.menu_lock -> lockControls()
                R.id.menu_resize -> showResizeDialog()
                else -> return@setOnMenuItemClickListener false
            }
            true
        }
        popup.show()
    }

    private fun toggleBackgroundPlay() {
        AppPrefs.backgroundPlay = !AppPrefs.backgroundPlay
        if (AppPrefs.backgroundPlay && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        updatePipParams()
        Toast.makeText(
            this,
            getString(if (AppPrefs.backgroundPlay) R.string.background_play_on else R.string.background_play_off),
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun cycleRepeat() {
        val p = player ?: return
        val next = when (p.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
        p.repeatMode = next
        AppPrefs.repeatMode = next
        Toast.makeText(
            this,
            getString(when (next) {
                Player.REPEAT_MODE_ALL -> R.string.repeat_all
                Player.REPEAT_MODE_ONE -> R.string.repeat_one
                else -> R.string.repeat_off
            }),
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun toggleShuffle() {
        val p = player ?: return
        p.shuffleModeEnabled = !p.shuffleModeEnabled
        AppPrefs.shuffle = p.shuffleModeEnabled
        Toast.makeText(
            this,
            getString(if (p.shuffleModeEnabled) R.string.shuffle_on else R.string.shuffle_off),
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun showTrackDialog(title: String, trackType: @C.TrackType Int) {
        val p = player ?: return
        TrackSelectionDialogBuilder(this, title, p, trackType)
            .setShowDisableOption(true)
            .build()
            .show()
    }

    // ---------- Subtitles ----------
    private fun showSubtitleDialog() {
        val options = arrayOf(
            getString(R.string.subtitle_tracks),
            getString(R.string.subtitle_load_file),
            getString(R.string.subtitle_style)
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.subtitle)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showTrackDialog(getString(R.string.subtitle), C.TRACK_TYPE_TEXT)
                    1 -> subtitlePicker.launch(arrayOf("*/*"))
                    2 -> showSubtitleStyleDialog()
                }
            }
            .show()
    }

    private fun addExternalSubtitle(uri: Uri) {
        val p = player ?: return
        val cur = p.currentMediaItem ?: return
        val name = titleFromUri(uri)
        val ext = name.substringAfterLast('.', "srt")
        val sub = MediaItem.SubtitleConfiguration.Builder(uri)
            .setMimeType(subtitleMime(ext))
            .setLabel(name)
            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
            .build()
        val existing: List<MediaItem.SubtitleConfiguration> =
            cur.localConfiguration?.subtitleConfigurations ?: emptyList()
        val idx = p.currentMediaItemIndex
        val pos = p.currentPosition
        val wasPlaying = p.playWhenReady
        p.replaceMediaItem(idx, cur.buildUpon().setSubtitleConfigurations(existing + sub).build())
        p.seekTo(idx, pos)
        p.playWhenReady = wasPlaying
        Toast.makeText(this, getString(R.string.subtitle_loaded, name), Toast.LENGTH_SHORT).show()
    }

    private fun showSubtitleStyleDialog() {
        val items = arrayOf(
            getString(R.string.subtitle_size),
            getString(R.string.subtitle_color),
            getString(R.string.subtitle_background)
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.subtitle_style)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> {
                        val sizes = intArrayOf(75, 100, 125, 150, 200)
                        val labels = sizes.map { "$it%" }.toTypedArray()
                        MaterialAlertDialogBuilder(this).setTitle(R.string.subtitle_size)
                            .setSingleChoiceItems(labels, sizes.indexOf(AppPrefs.subtitleSizePercent)) { d, i ->
                                AppPrefs.subtitleSizePercent = sizes[i]
                                applySubtitleStyle()
                                d.dismiss()
                            }.show()
                    }
                    1 -> {
                        val colors = intArrayOf(Color.WHITE, Color.YELLOW, Color.GREEN, Color.CYAN)
                        val labels = arrayOf(
                            getString(R.string.color_white), getString(R.string.color_yellow),
                            getString(R.string.color_green), getString(R.string.color_cyan)
                        )
                        MaterialAlertDialogBuilder(this).setTitle(R.string.subtitle_color)
                            .setSingleChoiceItems(labels, colors.indexOf(AppPrefs.subtitleColor)) { d, i ->
                                AppPrefs.subtitleColor = colors[i]
                                applySubtitleStyle()
                                d.dismiss()
                            }.show()
                    }
                    2 -> {
                        AppPrefs.subtitleBackground = !AppPrefs.subtitleBackground
                        applySubtitleStyle()
                    }
                }
            }
            .show()
    }

    private fun applySubtitleStyle() {
        val sv: SubtitleView = binding.playerView.subtitleView ?: return
        sv.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * AppPrefs.subtitleSizePercent / 100f)
        sv.setApplyEmbeddedStyles(false)
        sv.setStyle(
            CaptionStyleCompat(
                AppPrefs.subtitleColor,
                if (AppPrefs.subtitleBackground) 0xAA000000.toInt() else Color.TRANSPARENT,
                Color.TRANSPARENT,
                CaptionStyleCompat.EDGE_TYPE_OUTLINE,
                Color.BLACK,
                null
            )
        )
    }

    // ---------- Info / speed / resize / sleep ----------
    private fun showVideoInfoDialog() {
        val p = player ?: return
        val title = p.mediaMetadata.title?.toString() ?: getString(R.string.unknown)
        val format = p.videoFormat
        val res = if (format != null && format.width > 0) "${format.width} × ${format.height}" else getString(R.string.unknown)
        val codec = format?.sampleMimeType?.substringAfter('/')?.uppercase() ?: getString(R.string.unknown)
        val durMs = knownDuration()
        val durStr = if (durMs != null) Fmt.duration(durMs) else getString(R.string.unknown)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.video_info)
            .setMessage(getString(R.string.video_info_format, title, res, codec, durStr))
            .setPositiveButton(R.string.close, null)
            .show()
    }

    private fun showSpeedDialog() {
        val p = player ?: return
        val view = layoutInflater.inflate(R.layout.dialog_speed, null)
        val tvSpeed = view.findViewById<TextView>(R.id.tvSpeedLabel)
        val tvPitch = view.findViewById<TextView>(R.id.tvPitchLabel)
        val sSpeed = view.findViewById<Slider>(R.id.sliderSpeed)
        val sPitch = view.findViewById<Slider>(R.id.sliderPitch)

        fun snap(v: Float, from: Float, to: Float) = ((v / 0.25f).roundToInt() * 0.25f).coerceIn(from, to)

        sSpeed.value = snap(p.playbackParameters.speed, 0.25f, 4f)
        sPitch.value = snap(p.playbackParameters.pitch, 0.5f, 2f)
        tvSpeed.text = getString(R.string.speed_label, sSpeed.value)
        tvPitch.text = getString(R.string.pitch_label, sPitch.value)

        fun apply() {
            p.playbackParameters = PlaybackParameters(sSpeed.value, sPitch.value)
            tvSpeed.text = getString(R.string.speed_label, sSpeed.value)
            tvPitch.text = getString(R.string.pitch_label, sPitch.value)
        }
        sSpeed.addOnChangeListener { _, _, fromUser -> if (fromUser) apply() }
        sPitch.addOnChangeListener { _, _, fromUser -> if (fromUser) apply() }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.playback_settings)
            .setView(view)
            .setPositiveButton(R.string.close, null)
            .setNeutralButton(R.string.reset) { _, _ -> p.playbackParameters = PlaybackParameters.DEFAULT }
            .show()
    }

    private fun showResizeDialog() {
        val modes = arrayOf(getString(R.string.resize_fit), getString(R.string.resize_fill), getString(R.string.resize_zoom))
        MaterialAlertDialogBuilder(this).setTitle(R.string.resize_aspect).setItems(modes) { _, which ->
            binding.playerView.resizeMode = when (which) {
                1 -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                2 -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
            }
        }.show()
    }

    private fun showSleepTimerDialog() {
        val minutes = intArrayOf(0, 15, 30, 45, 60)
        val labels = arrayOf(
            getString(R.string.off), getString(R.string.minutes_n, 15), getString(R.string.minutes_n, 30),
            getString(R.string.minutes_n, 45), getString(R.string.minutes_n, 60)
        )
        MaterialAlertDialogBuilder(this).setTitle(R.string.sleep_timer).setItems(labels) { _, which ->
            // টাইমার সার্ভিসে চলে, তাই Activity বন্ধ থাকলেও (background play) কাজ করে
            PlaybackService.instance?.setSleepTimer(minutes[which])
            Toast.makeText(
                this,
                if (minutes[which] == 0) getString(R.string.sleep_timer_off)
                else getString(R.string.sleep_timer_set, minutes[which]),
                Toast.LENGTH_SHORT
            ).show()
        }.show()
    }

    // ---------- Night mode ----------
    private fun toggleNightMode() {
        binding.nightOverlay.visibility =
            if (binding.nightOverlay.visibility == View.VISIBLE) View.GONE else View.VISIBLE
    }

    // ---------- A-B repeat ----------
    private fun cycleAbRepeat() {
        val p = player ?: return
        when {
            abA < 0 -> {
                abA = p.currentPosition
                Toast.makeText(this, getString(R.string.ab_a_set, Fmt.duration(abA)), Toast.LENGTH_SHORT).show()
            }
            abB < 0 -> {
                val b = p.currentPosition
                if (b <= abA) {
                    Toast.makeText(this, R.string.ab_invalid, Toast.LENGTH_SHORT).show()
                } else {
                    abB = b
                    Toast.makeText(this, getString(R.string.ab_running, Fmt.duration(abA), Fmt.duration(abB)), Toast.LENGTH_SHORT).show()
                    abHandler.removeCallbacks(abRunnable)
                    abHandler.post(abRunnable)
                }
            }
            else -> {
                clearAbRepeat()
                Toast.makeText(this, R.string.ab_cleared, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun clearAbRepeat() {
        abA = -1L
        abB = -1L
        abHandler.removeCallbacks(abRunnable)
    }

    // ---------- Frame by frame ----------
    private fun stepFrame(forward: Boolean) {
        val p = player ?: return
        p.pause()
        val fps = p.videoFormat?.frameRate?.takeIf { it > 0f } ?: 30f
        val step = (1000f / fps).toLong().coerceAtLeast(1L)
        p.setSeekParameters(SeekParameters.EXACT)
        var target = p.currentPosition + if (forward) step else -step
        target = target.coerceAtLeast(0L)
        knownDuration()?.let { target = target.coerceAtMost(it) }
        p.seekTo(target)
        uiHandler.removeCallbacks(restoreSeekParamsRunnable)
        uiHandler.postDelayed(restoreSeekParamsRunnable, 800)
    }

    // ---------- Bookmarks ----------
    private fun showBookmarksDialog() {
        val p = player ?: return
        val uri = p.currentMediaItem?.mediaId ?: return
        val marks = PlaybackStore.getBookmarks(uri)
        val labels = ArrayList<String>()
        labels.add(getString(R.string.bookmark_add_here, Fmt.duration(p.currentPosition)))
        marks.forEach { labels.add(Fmt.duration(it)) }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.bookmarks)
            .setItems(labels.toTypedArray()) { _, which ->
                if (which == 0) {
                    PlaybackStore.addBookmark(uri, p.currentPosition)
                    Toast.makeText(this, R.string.bookmark_added, Toast.LENGTH_SHORT).show()
                } else {
                    val pos = marks[which - 1]
                    MaterialAlertDialogBuilder(this)
                        .setTitle(Fmt.duration(pos))
                        .setPositiveButton(R.string.jump) { _, _ -> p.seekTo(pos) }
                        .setNegativeButton(R.string.delete) { _, _ -> PlaybackStore.removeBookmark(uri, pos) }
                        .setNeutralButton(R.string.cancel, null)
                        .show()
                }
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    // ---------- Equalizer / Bass boost ----------
    private fun showEqualizerDialog() {
        val svc = PlaybackService.instance
        val eq = svc?.equalizer
        if (svc == null || eq == null) {
            Toast.makeText(this, R.string.equalizer_unsupported, Toast.LENGTH_SHORT).show()
            return
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        val enable = MaterialSwitch(this).apply {
            text = getString(R.string.equalizer_enable)
            isChecked = AppPrefs.eqEnabled
            setOnCheckedChangeListener { _, checked ->
                AppPrefs.eqEnabled = checked
                svc.applyEqualizerPrefs()
            }
        }
        container.addView(enable)

        val range = eq.bandLevelRange
        val minL = range[0].toFloat()
        val maxL = range[1].toFloat()
        val levels = AppPrefs.eqLevels()
        if (maxL > minL) {
            for (i in 0 until eq.numberOfBands.toInt()) {
                val hz = eq.getCenterFreq(i.toShort()) / 1000
                container.addView(TextView(this).apply {
                    text = if (hz >= 1000) "${hz / 1000} kHz" else "$hz Hz"
                })
                container.addView(Slider(this).apply {
                    valueFrom = minL
                    valueTo = maxL
                    value = (levels.getOrNull(i) ?: 0).toFloat().coerceIn(minL, maxL)
                    addOnChangeListener { _, v, fromUser ->
                        if (fromUser) {
                            AppPrefs.setEqLevel(i, v.toInt())
                            svc.applyEqualizerPrefs()
                        }
                    }
                })
            }
        }
        container.addView(TextView(this).apply { text = getString(R.string.bass_boost) })
        container.addView(Slider(this).apply {
            valueFrom = 0f
            valueTo = 1000f
            value = AppPrefs.bassStrength.toFloat().coerceIn(0f, 1000f)
            addOnChangeListener { _, v, fromUser ->
                if (fromUser) {
                    AppPrefs.bassStrength = v.toInt()
                    svc.applyBassPrefs()
                }
            }
        })
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.equalizer)
            .setView(ScrollView(this).apply { addView(container) })
            .setPositiveButton(R.string.close, null)
            .show()
    }

    // ==========================================
    // SCREENSHOT / FRAME CAPTURE
    // ==========================================
    private fun captureScreenshot() {
        val view = binding.playerView.videoSurfaceView
        if (view == null || view.width <= 0 || view.height <= 0) {
            Toast.makeText(this, R.string.screenshot_unavailable, Toast.LENGTH_SHORT).show()
            return
        }
        when (view) {
            is SurfaceView -> {
                val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                PixelCopy.request(view, bitmap, { result ->
                    if (result == PixelCopy.SUCCESS) saveImageToGallery(bitmap)
                    else Toast.makeText(this, R.string.screenshot_failed, Toast.LENGTH_SHORT).show()
                }, Handler(Looper.getMainLooper()))
            }
            is TextureView -> view.bitmap?.let { saveImageToGallery(it) }
            else -> Toast.makeText(this, R.string.screenshot_unsupported, Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveImageToGallery(bitmap: Bitmap) {
        Thread {
            val filename = "VideoPlayerPro_${System.currentTimeMillis()}.jpg"
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/VideoPlayerPro")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
            }
            val resolver = contentResolver
            val imageUri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            var out: OutputStream? = null
            try {
                if (imageUri == null) throw IllegalStateException("insert failed")
                out = resolver.openOutputStream(imageUri)
                out?.let { bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it) }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    values.clear()
                    values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    resolver.update(imageUri, values, null, null)
                }
                runOnUiThread { Toast.makeText(this, R.string.screenshot_saved, Toast.LENGTH_SHORT).show() }
            } catch (e: Exception) {
                // ব্যর্থ হলে আধা-তৈরি (pending) রো মুছে ফেলা
                imageUri?.let { runCatching { resolver.delete(it, null, null) } }
                runOnUiThread { Toast.makeText(this, R.string.screenshot_error, Toast.LENGTH_SHORT).show() }
            } finally {
                runCatching { out?.close() }
            }
        }.start()
    }
}
