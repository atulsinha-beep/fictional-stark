package com.example.pulsar.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import com.example.pulsar.R
import kotlin.random.Random

object ResponseEngine {

    private var player: MediaPlayer? = null
    private var focusRequest: AudioFocusRequest? = null

    enum class ResponseType {
        SYSTEM_READY, SYSTEM_F, WAKE, OPEN_OK, CLOSE_OK,
        NOT_FOUND, RETRY, STANDBY, CANCEL, ACCESS
    }

    // (Your existing mapOf responses goes here - kept short for brevity)
    private val responses = mapOf(
        ResponseType.SYSTEM_READY to listOf(R.raw.system_ready_1, R.raw.system_ready_2, R.raw.system_ready_3),
        ResponseType.SYSTEM_F to listOf(R.raw.system_fail_1, R.raw.system_fail_2),
        ResponseType.WAKE to listOf(R.raw.wake_1, R.raw.wake_2, R.raw.wake_3, R.raw.wake_4),
        ResponseType.OPEN_OK to listOf(R.raw.open_1),
        ResponseType.CLOSE_OK to listOf(R.raw.close_1),
        ResponseType.NOT_FOUND to listOf(R.raw.not_found_1),
        ResponseType.RETRY to listOf(R.raw.retry_1, R.raw.retry_2),
        ResponseType.STANDBY to listOf(R.raw.standby_1),
        ResponseType.CANCEL to listOf(R.raw.cancel_1),
        ResponseType.ACCESS to listOf(R.raw.access_1, R.raw.access_2)
    )

    fun play(context: Context, type: ResponseType, onDone: (() -> Unit)? = null) {
        val list = responses[type] ?: return
        val sound = list[Random.nextInt(list.size)]

        stop(context) // Ensure cleanup before new play

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        // FIX: Use modern focus request with "MAY_DUCK" so we don't kill the Mic service
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()

            focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(attributes)
                .setAcceptsDelayedFocusGain(false)
                .setOnAudioFocusChangeListener { /* No-op */ }
                .build()

            audioManager.requestAudioFocus(focusRequest!!)
        }

        player = MediaPlayer.create(context, sound).apply {
            setOnCompletionListener {
                stop(context) // Release focus when done
                onDone?.invoke()
            }
            start()
        }
    }

    fun stop(context: Context? = null) {
        try {
            player?.stop()
            player?.release()
            player = null

            // Abandon focus so the Mic can take over cleanly
            if (context != null && focusRequest != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                am.abandonAudioFocusRequest(focusRequest!!)
                focusRequest = null
            }
        } catch (e: Exception) {
            // Ignore stop errors
        }
    }
}