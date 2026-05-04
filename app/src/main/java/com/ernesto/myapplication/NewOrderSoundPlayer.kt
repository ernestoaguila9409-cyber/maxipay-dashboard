package com.ernesto.myapplication

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log

/**
 * POS-friendly chime: many devices (e.g. Landi C20 Pro) route **music** to the speaker while
 * notification/sonification streams stay silent. We try [MediaPlayer] on [USAGE_MEDIA] first,
 * then fall back to [ToneGenerator] on [STREAM_MUSIC], then default notification ringtone.
 *
 * Add `res/raw/new_order.mp3` for a custom tone; if missing, fallback paths still make noise.
 */
class NewOrderSoundPlayer(context: Context) {

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    fun playNewOrderChime() {
        val now = SystemClock.elapsedRealtime()
        synchronized(NewOrderSoundPlayer::class.java) {
            if (now - lastGlobalChimeAt < GLOBAL_DEBOUNCE_MS) return
            lastGlobalChimeAt = now
        }
        val resId = appContext.resources.getIdentifier("new_order", "raw", appContext.packageName)
        if (resId != 0) {
            if (tryPlayRawResource(resId)) return
        } else {
            Log.w(TAG, "res/raw/new_order.mp3 not found; using fallback tones")
        }
        if (tryPlayToneGenerator()) return
        tryPlayDefaultNotification()
    }

    /**
     * @return true if playback started (or will complete asynchronously).
     */
    private fun tryPlayRawResource(resId: Int): Boolean {
        return try {
            val afd = appContext.resources.openRawResourceFd(resId) ?: return false
            val player = MediaPlayer()
            player.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            afd.close()
            // Landi / many POS terminals: music stream is audible; NOTIFICATION_EVENT often is not.
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            player.setVolume(1f, 1f)
            player.setOnCompletionListener { mp -> mp.release() }
            player.setOnErrorListener { mp, _, _ ->
                mp.release()
                false
            }
            player.prepare()
            player.start()
            true
        } catch (e: Exception) {
            Log.w(TAG, "MediaPlayer raw chime failed", e)
            false
        }
    }

    private fun tryPlayToneGenerator(): Boolean {
        return try {
            val tg = ToneGenerator(AudioManager.STREAM_MUSIC, 90)
            val started = tg.startTone(ToneGenerator.TONE_PROP_ACK, 450)
            if (!started) {
                tg.release()
                return false
            }
            mainHandler.postDelayed({
                try {
                    tg.release()
                } catch (_: Exception) {
                }
            }, 520L)
            true
        } catch (e: Exception) {
            Log.w(TAG, "ToneGenerator fallback failed", e)
            false
        }
    }

    private fun tryPlayDefaultNotification() {
        try {
            val uri = android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION)
            val ringtone = android.media.RingtoneManager.getRingtone(appContext, uri) ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                ringtone.audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            }
            ringtone.play()
            mainHandler.postDelayed({
                try {
                    ringtone.stop()
                } catch (_: Exception) {
                }
            }, 1_800L)
        } catch (e: Exception) {
            Log.e(TAG, "Ringtone fallback failed", e)
        }
    }

    fun release() {
        // MediaPlayer instances are one-shot released on completion; nothing held here.
    }

    private companion object {
        private const val TAG = "NewOrderSoundPlayer"
        private const val GLOBAL_DEBOUNCE_MS = 900L
        @Volatile
        private var lastGlobalChimeAt = 0L
    }
}
