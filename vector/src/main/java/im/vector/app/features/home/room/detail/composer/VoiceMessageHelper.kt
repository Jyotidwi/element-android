/*
 * Copyright (c) 2021 New Vector Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package im.vector.app.features.home.room.detail.composer

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.MediaRecorder
import androidx.core.content.FileProvider
import im.vector.app.BuildConfig
import im.vector.app.core.utils.CountUpTimer
import im.vector.app.features.home.room.detail.timeline.helper.VoiceMessagePlaybackTracker
import im.vector.lib.multipicker.entity.MultiPickerAudioType
import im.vector.lib.multipicker.utils.toMultiPickerAudioType
import org.matrix.android.sdk.api.extensions.orFalse
import org.matrix.android.sdk.api.extensions.tryOrNull
import timber.log.Timber
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.util.UUID
import javax.inject.Inject

/**
 * Helper class to record audio for voice messages.
 */
class VoiceMessageHelper @Inject constructor(
        private val context: Context,
        private val playbackTracker: VoiceMessagePlaybackTracker
) {
    private var mediaPlayer: MediaPlayer? = null
    private lateinit var mediaRecorder: MediaRecorder
    private val outputDirectory = File(context.cacheDir, "downloads")
    private var outputFile: File? = null
    private var lastRecordingFile: File? = null // In case of user pauses recording, plays another one in timeline

    private val amplitudeList = mutableListOf<Int>()

    private var amplitudeTimer: CountUpTimer? = null
    private var playbackTimer: CountUpTimer? = null

    init {
        if (!outputDirectory.exists()) {
            outputDirectory.mkdirs()
        }
    }

    private fun refreshMediaRecorder() {
        mediaRecorder = MediaRecorder().apply {
            setAudioSource(MediaRecorder.AudioSource.DEFAULT)
            setOutputFormat(MediaRecorder.OutputFormat.OGG)
            setAudioEncoder(MediaRecorder.AudioEncoder.OPUS)
            setAudioEncodingBitRate(24000)
            setAudioSamplingRate(48000)
        }
    }

    fun startRecording() {
        stopPlayback()
        playbackTracker.makeAllPlaybacksIdle()

        outputFile = File(outputDirectory, UUID.randomUUID().toString() + ".ogg")
        lastRecordingFile = outputFile
        amplitudeList.clear()
        FileOutputStream(outputFile).use { fos ->
            refreshMediaRecorder()
            mediaRecorder.setOutputFile(fos.fd)
            mediaRecorder.prepare()
            mediaRecorder.start()
            startRecordingAmplitudes()
        }
    }

    fun stopRecording(): MultiPickerAudioType? {
        internalStopRecording()
        try {
            outputFile?.let {
                val outputFileUri = FileProvider.getUriForFile(context, BuildConfig.APPLICATION_ID + ".fileProvider", it)
                return outputFileUri
                        ?.toMultiPickerAudioType(context)
                        ?.apply {
                            waveform = amplitudeList
                        }
            } ?: return null
        } catch (e: FileNotFoundException) {
            Timber.e(e, "Cannot stop voice recording")
            return null
        }
    }

    private fun internalStopRecording() {
        tryOrNull("Cannot stop media recording amplitude") {
            stopRecordingAmplitudes()
        }
        tryOrNull("Cannot stop media recorder!") {
            // Usually throws when the record is less than 1 second.
            releaseMediaRecorder()
        }
    }

    private fun releaseMediaRecorder() {
        mediaRecorder.stop()
        mediaRecorder.reset()
        mediaRecorder.release()
    }

    fun pauseRecording() {
        releaseMediaRecorder()
    }

    fun deleteRecording() {
        internalStopRecording()
        outputFile?.delete()
        outputFile = null
    }

    fun startOrPauseRecordingPlayback() {
        lastRecordingFile?.let {
            startOrPausePlayback(VoiceMessagePlaybackTracker.RECORDING_ID, it)
        }
    }

    fun startOrPausePlayback(id: String, file: File) {
        stopPlayback()
        if (playbackTracker.getPlaybackState(id) is VoiceMessagePlaybackTracker.Listener.State.Playing) {
            playbackTracker.stopPlayback(id)
        } else {
            playbackTracker.startPlayback(id)
            startPlayback(id, file)
        }
    }

    private fun startPlayback(id: String, file: File) {
        val currentPlaybackTime = playbackTracker.getPlaybackTime(id)

        FileInputStream(file).use { fis ->
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                        AudioAttributes.Builder()
                                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .build()
                )
                setDataSource(fis.fd)
                prepare()
                start()
                seekTo(currentPlaybackTime)
            }
        }
        startPlaybackTimer(id)
    }

    private fun stopPlayback() {
        mediaPlayer?.stop()
        stopPlaybackTimer()
    }

    private fun startRecordingAmplitudes() {
        amplitudeTimer?.stop()
        amplitudeTimer = CountUpTimer(100).apply {
            tickListener = object : CountUpTimer.TickListener {
                override fun onTick(milliseconds: Long) {
                    onAmplitudeTimerTick()
                }
            }
            resume()
        }
    }

    private fun onAmplitudeTimerTick() {
        try {
            val maxAmplitude = mediaRecorder.maxAmplitude
            amplitudeList.add(maxAmplitude)
            playbackTracker.updateCurrentRecording(VoiceMessagePlaybackTracker.RECORDING_ID, amplitudeList)
        } catch (e: IllegalStateException) {
            Timber.e(e, "Cannot get max amplitude. Amplitude recording timer will be stopped.")
            stopRecordingAmplitudes()
        } catch (e: RuntimeException) {
            Timber.e(e, "Cannot get max amplitude (native error). Amplitude recording timer will be stopped.")
            stopRecordingAmplitudes()
        }
    }

    private fun stopRecordingAmplitudes() {
        amplitudeTimer?.stop()
        amplitudeTimer = null
    }

    private fun startPlaybackTimer(id: String) {
        playbackTimer?.stop()
        playbackTimer = CountUpTimer().apply {
            tickListener = object : CountUpTimer.TickListener {
                override fun onTick(milliseconds: Long) {
                    onPlaybackTimerTick(id, false)
                }
            }
            resume()
        }
        onPlaybackTimerTick(id, true)
    }

    private fun onPlaybackTimerTick(id: String, firstCall: Boolean) {
        when {
            firstCall                        -> {
                playbackTracker.updateCurrentPlaybackTime(id, 0)
            }
            mediaPlayer?.isPlaying.orFalse() -> {
                val currentPosition = mediaPlayer?.currentPosition ?: 0
                playbackTracker.updateCurrentPlaybackTime(id, currentPosition)
            }
            else                             -> {
                playbackTracker.stopPlayback(id = id, rememberPlaybackTime = false)
                stopPlaybackTimer()
            }
        }
    }

    private fun stopPlaybackTimer() {
        playbackTimer?.stop()
        playbackTimer = null
    }

    fun stopAllVoiceActions() {
        stopRecording()
        stopPlayback()
        deleteRecording()
        playbackTracker.clear()
    }
}
