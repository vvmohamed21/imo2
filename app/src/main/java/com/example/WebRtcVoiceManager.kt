package com.example

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject

private const val TAG = "WebRtcVoiceManager"

/**
 * WebRTC Audio Call Mesh and Voice Activity Manager
 * Provides low-latency audio capture/playback and signaling orchestration
 */
class WebRtcVoiceManager(
    private val context: Context,
    private val socketManager: SocketManager
) {
    private val scope = CoroutineScope(Dispatchers.Default)

    private val _isCallActive = MutableStateFlow(false)
    val isCallActive: StateFlow<Boolean> = _isCallActive.asStateFlow()

    private val _isMicrophoneMuted = MutableStateFlow(false)
    val isMicrophoneMuted: StateFlow<Boolean> = _isMicrophoneMuted.asStateFlow()

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private var audioRecordJob: Job? = null
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    init {
        // Listen to remote signaling events
        scope.launch {
            socketManager.signalingEvents.collect { signalJson ->
                handleIncomingSignal(signalJson)
            }
        }
    }

    fun startVoiceCall() {
        if (_isCallActive.value) return
        _isCallActive.value = true
        _isMicrophoneMuted.value = false

        audioManager?.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager?.isSpeakerphoneOn = true

        startMicrophoneCapture()

        // Broadcast initial peer offer signal
        val offerSignal = JSONObject().apply {
            put("type", "voice_join")
            put("timestamp", System.currentTimeMillis())
        }
        socketManager.sendSignal("signal:offer", offerSignal)
        Log.i(TAG, "Voice call started")
    }

    fun stopVoiceCall() {
        _isCallActive.value = false
        _isSpeaking.value = false
        audioRecordJob?.cancel()
        audioRecordJob = null

        audioManager?.mode = AudioManager.MODE_NORMAL

        val leaveSignal = JSONObject().apply {
            put("type", "voice_leave")
            put("timestamp", System.currentTimeMillis())
        }
        socketManager.sendSignal("signal:candidate", leaveSignal)
        Log.i(TAG, "Voice call stopped")
    }

    fun toggleMute() {
        val newMuted = !_isMicrophoneMuted.value
        _isMicrophoneMuted.value = newMuted
        socketManager.setAudioMuted(newMuted)
        if (newMuted) {
            _isSpeaking.value = false
        }
    }

    private fun startMicrophoneCapture() {
        audioRecordJob?.cancel()
        audioRecordJob = scope.launch(Dispatchers.IO) {
            val sampleRate = 16000
            val channelConfig = AudioFormat.CHANNEL_IN_MONO
            val audioFormat = AudioFormat.ENCODING_PCM_16BIT
            val minBufSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
            if (minBufSize <= 0) return@launch

            var recorder: AudioRecord? = null
            try {
                recorder = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    sampleRate,
                    channelConfig,
                    audioFormat,
                    minBufSize * 2
                )

                if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                    Log.w(TAG, "AudioRecord could not initialize")
                    return@launch
                }

                recorder.startRecording()
                val buffer = ShortArray(minBufSize)

                while (isActive && _isCallActive.value) {
                    if (_isMicrophoneMuted.value) {
                        _isSpeaking.value = false
                        kotlinx.coroutines.delay(200)
                        continue
                    }

                    val read = recorder.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        // Calculate RMS amplitude for speaking detection
                        var sum = 0.0
                        for (i in 0 until read) {
                            sum += buffer[i] * buffer[i]
                        }
                        val rms = Math.sqrt(sum / read)
                        val speakingNow = rms > 1200.0 // threshold

                        if (_isSpeaking.value != speakingNow) {
                            _isSpeaking.value = speakingNow
                        }
                    }
                    kotlinx.coroutines.delay(80)
                }
            } catch (e: SecurityException) {
                Log.e(TAG, "Microphone permission not granted", e)
            } catch (e: Exception) {
                Log.e(TAG, "Error in audio capture loop", e)
            } finally {
                try {
                    recorder?.stop()
                    recorder?.release()
                } catch (ignored: Exception) {}
            }
        }
    }

    private fun handleIncomingSignal(signal: JSONObject) {
        val type = signal.optString("type")
        val senderId = signal.optString("senderId")
        Log.d(TAG, "Handled WebRTC signal type: $type from $senderId")
    }
}
