package app.gamenative.utils

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import app.gamenative.PluviaApp
import app.gamenative.PrefManager
import timber.log.Timber

/**
 * Listens for incoming phone calls and audio focus loss to pause active game sessions.
 * Once paused, the game remains suspended until the user manually presses Resume.
 */
object PhoneCallObserver {

    private var audioManager: AudioManager? = null
    private var telephonyManager: TelephonyManager? = null
    private var isRegistered = false

    private var audioFocusRequest: AudioFocusRequest? = null

    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK,
            AudioManager.AUDIOFOCUS_LOSS -> {
                Timber.i("[PhoneCallObserver]: Audio focus lost (focusChange=%d)", focusChange)
                handlePhoneCallOrAudioLoss()
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                Timber.d("[PhoneCallObserver]: Audio focus regained")
            }
        }
    }

    private fun handlePhoneCallOrAudioLoss() {
        if (PluviaApp.xEnvironment != null) {
            Timber.i("[PhoneCallObserver]: Pausing game session due to incoming phone call / audio focus loss")
            PluviaApp.xEnvironment?.onPause()
            PluviaApp.isOverlayPaused = true
        }
    }

    private var telephonyCallback: Any? = null
    private var phoneStateListener: PhoneStateListener? = null

    fun start(context: Context) {
        if (isRegistered || !PrefManager.pauseOnPhoneCall) return
        val appContext = context.applicationContext
        audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        telephonyManager = appContext.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager

        // 1. Audio focus listener
        audioManager?.let { am ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_GAME)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    .setOnAudioFocusChangeListener(audioFocusChangeListener)
                    .build()
                audioFocusRequest = focusRequest
                am.requestAudioFocus(focusRequest)
            } else {
                @Suppress("DEPRECATION")
                am.requestAudioFocus(
                    audioFocusChangeListener,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
                )
            }
        }

        // 2. Telephony call state listener
        telephonyManager?.let { tm ->
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val callback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                        override fun onCallStateChanged(state: Int) {
                            handleCallState(state)
                        }
                    }
                    telephonyCallback = callback
                    appContext.mainExecutor.let { executor ->
                        tm.registerTelephonyCallback(executor, callback)
                    }
                } else {
                    @Suppress("DEPRECATION")
                    val listener = object : PhoneStateListener() {
                        @Deprecated("Deprecated in Java")
                        override fun onCallStateChanged(state: Int, incomingNumber: String?) {
                            handleCallState(state)
                        }
                    }
                    phoneStateListener = listener
                    @Suppress("DEPRECATION")
                    tm.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
                }
            } catch (e: Exception) {
                Timber.w(e, "[PhoneCallObserver]: Could not register telephony call state listener")
            }
        }

        isRegistered = true
        Timber.i("[PhoneCallObserver]: Started observing phone calls and audio focus")
    }

    private fun handleCallState(state: Int) {
        when (state) {
            TelephonyManager.CALL_STATE_RINGING,
            TelephonyManager.CALL_STATE_OFFHOOK -> {
                Timber.i("[PhoneCallObserver]: Call state changed to RINGING/OFFHOOK (%d)", state)
                handlePhoneCallOrAudioLoss()
            }
            TelephonyManager.CALL_STATE_IDLE -> {
                Timber.d("[PhoneCallObserver]: Call state changed to IDLE")
            }
        }
    }

    fun stop() {
        if (!isRegistered) return

        audioManager?.let { am ->
            audioFocusRequest?.let { am.abandonAudioFocusRequest(it) }
        }
        audioFocusRequest = null

        telephonyManager?.let { tm ->
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    (telephonyCallback as? TelephonyCallback)?.let {
                        tm.unregisterTelephonyCallback(it)
                    }
                } else {
                    phoneStateListener?.let {
                        @Suppress("DEPRECATION")
                        tm.listen(it, PhoneStateListener.LISTEN_NONE)
                    }
                }
            } catch (e: Exception) {
                Timber.w(e, "[PhoneCallObserver]: Could not unregister telephony listener")
            }
        }
        telephonyCallback = null
        phoneStateListener = null

        isRegistered = false
        Timber.i("[PhoneCallObserver]: Stopped observing phone calls")
    }
}
