package com.shehab360.videoplayerpro

import android.app.PendingIntent
import android.content.Intent
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * প্লেয়ারের মালিক। Activity বন্ধ হলেও (Background Play চালু থাকলে) গান/ভিডিওর শব্দ চলতে থাকে,
 * নোটিফিকেশন ও লক-স্ক্রিন/ব্লুটুথ কন্ট্রোল Media3 নিজে সামলায়।
 * Resume position ও History এখানেই সেভ হয়, তাই ব্যাকগ্রাউন্ডেও কাজ করে।
 */
@UnstableApi
class PlaybackService : MediaSessionService() {

    companion object {
        /** একই প্রসেস, তাই Activity সরাসরি এই ExoPlayer ব্যবহার করে (MediaController শুধু সার্ভিস বেঁধে রাখে) */
        @Volatile
        var player: ExoPlayer? = null

        @Volatile
        var instance: PlaybackService? = null

        const val ACTION_OPEN_PLAYER = "com.shehab360.videoplayerpro.OPEN_PLAYER"
        const val EXTRA_AUDIO_FLAG = "audio_only"

        // +6 dB (600 mB) ≈ দ্বিগুণ amplitude = UI-র "200%"
        const val MAX_BOOST_MB = 600
        private const val SAVE_INTERVAL_MS = 5000L
        private const val RESUME_MIN_MS = 5000L
    }

    private var mediaSession: MediaSession? = null
    private val handler = Handler(Looper.getMainLooper())
    private val durations = HashMap<String, Long>()

    var loudnessEnhancer: LoudnessEnhancer? = null
        private set
    var equalizer: Equalizer? = null
        private set
    var bassBoost: BassBoost? = null
        private set
    var boostGainMb = 0
        private set

    private val sleepRunnable = Runnable { player?.pause() }

    /** Sleep timer: Activity বন্ধ থাকলেও (background play) কাজ করে। minutes = 0 হলে বন্ধ */
    fun setSleepTimer(minutes: Int) {
        handler.removeCallbacks(sleepRunnable)
        if (minutes > 0) handler.postDelayed(sleepRunnable, minutes * 60_000L)
    }

    private val saveRunnable = object : Runnable {
        override fun run() {
            saveCurrentPosition()
            handler.postDelayed(this, SAVE_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        val renderersFactory = DefaultRenderersFactory(this)
            .setEnableDecoderFallback(AppPrefs.decoderMode >= 1)
            .setMediaCodecSelector(buildCodecSelector())

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()

        val exo = ExoPlayer.Builder(this, renderersFactory)
            .setAudioAttributes(audioAttributes, /* handleAudioFocus = */ true) // কল/অন্য অ্যাপের অডিওতে নিজে থেকে pause
            .setHandleAudioBecomingNoisy(true)                                  // হেডফোন খুললে pause
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        exo.addListener(serviceListener)
        player = exo

        val openIntent = Intent(this, PlayerActivity::class.java).apply {
            action = ACTION_OPEN_PLAYER
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val sessionActivity = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        mediaSession = MediaSession.Builder(this, exo)
            .setSessionActivity(sessionActivity)
            .build()

        rebuildEffects(exo.audioSessionId)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    /** অ্যাপ recent থেকে সরালে: প্লে না হলে সার্ভিস বন্ধ, প্লে হলে (background play) চলতে থাকবে */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(saveRunnable)
        handler.removeCallbacks(sleepRunnable)
        saveCurrentPosition()
        releaseEffects()
        mediaSession?.let {
            it.player.removeListener(serviceListener)
            it.player.release()
            it.release()
        }
        mediaSession = null
        player = null
        instance = null
        super.onDestroy()
    }

    /** Activity বন্ধ হওয়ার পর প্লে না হলে সার্ভিস থামানো */
    fun shutdownIfIdle() {
        val p = player
        if (p == null || !p.isPlaying) stopSelf()
    }

    // ------------------------------------------------------------------
    // Decoder preference (HW / HW+ / SW)
    // ------------------------------------------------------------------
    private fun buildCodecSelector(): MediaCodecSelector {
        if (AppPrefs.decoderMode != 2) return MediaCodecSelector.DEFAULT
        // SW আগে: সফটওয়্যার ডিকোডারগুলো তালিকার শুরুতে
        return MediaCodecSelector { mimeType, requiresSecure, requiresTunneling ->
            MediaCodecSelector.DEFAULT
                .getDecoderInfos(mimeType, requiresSecure, requiresTunneling)
                .sortedByDescending { it.softwareOnly }
        }
    }

    // ------------------------------------------------------------------
    // Resume position + History (সার্ভিস লেভেলে, ব্যাকগ্রাউন্ডেও কাজ করে)
    // ------------------------------------------------------------------
    private fun currentUri(): String? =
        player?.currentMediaItem?.mediaId?.takeIf { it.isNotEmpty() }

    private fun saveCurrentPosition() {
        val p = player ?: return
        val uri = currentUri() ?: return
        if (p.playbackState == Player.STATE_IDLE) return
        val dur = p.duration
        if (dur == C.TIME_UNSET || dur <= 0L) return
        durations[uri] = dur
        PlaybackStore.savePosition(uri, p.currentPosition, dur)
    }

    private fun isAudioItem(item: MediaItem?): Boolean =
        item?.mediaMetadata?.extras?.getBoolean(EXTRA_AUDIO_FLAG, false) == true

    private val serviceListener = object : Player.Listener {

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val item = mediaItem ?: return
            if (!isAudioItem(item)) {
                PlaybackStore.recordHistory(item.mediaId, item.mediaMetadata.title?.toString() ?: "")
            }
            // পরের/আগের ভিডিওতে গেলে সেই ভিডিওর সেভ করা পজিশন থেকে শুরু
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO ||
                reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK
            ) {
                val saved = PlaybackStore.getPosition(item.mediaId)
                if (saved > RESUME_MIN_MS) player?.seekTo(saved)
            }
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int
        ) {
            // একই ভিডিওর ভেতরে seek হলে কিছু করার নেই; ভিডিও বদলালে পুরোনোটার পজিশন সেভ
            if (oldPosition.mediaItemIndex == newPosition.mediaItemIndex) return
            val oldId = oldPosition.mediaItem?.mediaId ?: return
            if (oldId.isEmpty()) return
            if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION) {
                PlaybackStore.clearPosition(oldId)   // শেষ পর্যন্ত দেখা হয়েছে
            } else {
                PlaybackStore.savePosition(oldId, oldPosition.positionMs, durations[oldId] ?: 0L)
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            handler.removeCallbacks(saveRunnable)
            if (isPlaying) handler.postDelayed(saveRunnable, SAVE_INTERVAL_MS)
            else saveCurrentPosition()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) {
                currentUri()?.let { PlaybackStore.clearPosition(it) }
            }
        }

        override fun onAudioSessionIdChanged(audioSessionId: Int) {
            rebuildEffects(audioSessionId)
        }
    }

    // ------------------------------------------------------------------
    // Volume boost / Equalizer / Bass boost
    // ------------------------------------------------------------------
    private fun rebuildEffects(sessionId: Int) {
        releaseEffects()
        if (sessionId == C.AUDIO_SESSION_ID_UNSET || sessionId == 0) return
        try {
            loudnessEnhancer = LoudnessEnhancer(sessionId)
            applyBoost()
        } catch (e: Exception) {
            loudnessEnhancer = null   // কিছু ডিভাইসে ইফেক্ট সাপোর্ট নেই: বুস্ট ছাড়াই চলবে
        }
        try {
            equalizer = Equalizer(0, sessionId)
            applyEqualizerPrefs()
        } catch (e: Exception) {
            equalizer = null
        }
        try {
            bassBoost = BassBoost(0, sessionId)
            applyBassPrefs()
        } catch (e: Exception) {
            bassBoost = null
        }
    }

    private fun releaseEffects() {
        runCatching { loudnessEnhancer?.release() }
        runCatching { equalizer?.release() }
        runCatching { bassBoost?.release() }
        loudnessEnhancer = null
        equalizer = null
        bassBoost = null
    }

    fun setBoostGain(mb: Int) {
        boostGainMb = mb.coerceIn(0, MAX_BOOST_MB)
        applyBoost()
    }

    private fun applyBoost() {
        val enhancer = loudnessEnhancer ?: return
        try {
            enhancer.setTargetGain(boostGainMb)
            enhancer.setEnabled(boostGainMb > 0)
        } catch (e: Exception) {
            // ইফেক্ট ব্যর্থ হলে উপেক্ষা
        }
    }

    fun applyEqualizerPrefs() {
        val eq = equalizer ?: return
        try {
            val levels = AppPrefs.eqLevels()
            val range = eq.bandLevelRange
            for (i in 0 until eq.numberOfBands.toInt()) {
                val lv = (levels.getOrNull(i) ?: 0).coerceIn(range[0].toInt(), range[1].toInt())
                eq.setBandLevel(i.toShort(), lv.toShort())
            }
            eq.setEnabled(AppPrefs.eqEnabled)
        } catch (e: Exception) {
            // উপেক্ষা
        }
    }

    fun applyBassPrefs() {
        val bass = bassBoost ?: return
        try {
            if (bass.strengthSupported) {
                bass.setStrength(AppPrefs.bassStrength.coerceIn(0, 1000).toShort())
                bass.setEnabled(AppPrefs.bassStrength > 0)
            }
        } catch (e: Exception) {
            // উপেক্ষা
        }
    }
}
