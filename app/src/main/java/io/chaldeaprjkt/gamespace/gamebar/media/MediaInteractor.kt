/*
 * Copyright (C) 2025-2026 AxionOS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.chaldeaprjkt.gamespace.gamebar.media

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import io.chaldeaprjkt.gamespace.gamebar.DanmakuServiceListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MediaInteractor @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "MediaInteractor"
    }

    private val mediaSessionManager =
        context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _mediaData = MutableStateFlow(MediaData())
    val mediaData: StateFlow<MediaData> = _mediaData.asStateFlow()

    private var activeController: MediaController? = null
    private var isListening = false

    private val controllerCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            mainHandler.post { updatePlaybackState(state) }
        }

        override fun onMetadataChanged(metadata: MediaMetadata?) {
            mainHandler.post { updateMetadata(metadata) }
        }

        override fun onSessionDestroyed() {
            mainHandler.post { refreshControllers() }
        }
    }

    private val sessionsChangedListener =
        MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
            mainHandler.post { updateActiveControllerFromList(controllers) }
        }

    fun start() {
        if (isListening) return
        isListening = true

        val notificationListener = ComponentName(context, DanmakuServiceListener::class.java)
        try {
            mediaSessionManager.addOnActiveSessionsChangedListener(
                sessionsChangedListener,
                null,
                mainHandler
            )
        } catch (e: Exception) {
            try {
                mediaSessionManager.addOnActiveSessionsChangedListener(
                    sessionsChangedListener,
                    notificationListener,
                    mainHandler
                )
            } catch (e2: Exception) {
                Log.w(TAG, "Failed to register active sessions listener", e2)
            }
        }
        refreshControllers()
    }

    fun stop() {
        if (!isListening) return
        isListening = false
        try {
            mediaSessionManager.removeOnActiveSessionsChangedListener(sessionsChangedListener)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to remove active sessions listener", e)
        }
        activeController?.unregisterCallback(controllerCallback)
        activeController = null
        _mediaData.value = MediaData()
    }

    fun refreshControllers() {
        val notificationListener = ComponentName(context, DanmakuServiceListener::class.java)
        val controllers = try {
            mediaSessionManager.getActiveSessions(null)
        } catch (e: Exception) {
            try {
                mediaSessionManager.getActiveSessions(notificationListener)
            } catch (e2: Exception) {
                Log.w(TAG, "Failed to get active sessions", e2)
                emptyList()
            }
        }
        updateActiveControllerFromList(controllers)
    }

    private fun updateActiveControllerFromList(controllers: List<MediaController>?) {
        val candidate = controllers?.firstOrNull {
            it.playbackState?.state == PlaybackState.STATE_PLAYING
        } ?: controllers?.firstOrNull {
            val state = it.playbackState?.state
            state == PlaybackState.STATE_PAUSED || state == PlaybackState.STATE_BUFFERING
        } ?: controllers?.firstOrNull()

        if (candidate?.sessionToken != activeController?.sessionToken) {
            activeController?.unregisterCallback(controllerCallback)
            activeController = candidate
            activeController?.registerCallback(controllerCallback, mainHandler)
        }

        if (activeController != null) {
            extractDataFromController(activeController!!)
        } else {
            _mediaData.value = MediaData()
        }
    }

    private fun extractDataFromController(controller: MediaController) {
        val metadata = controller.metadata
        val state = controller.playbackState
        val pkg = controller.packageName

        var appName = ""
        var appIcon: Drawable? = null
        try {
            val pm = context.packageManager
            val appInfo = pm.getApplicationInfo(pkg, 0)
            appName = pm.getApplicationLabel(appInfo).toString()
            appIcon = pm.getApplicationIcon(appInfo)
        } catch (e: Exception) {
            appName = pkg
        }

        val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: metadata?.description?.title?.toString()
            ?: ""
        val artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadata?.description?.subtitle?.toString()
            ?: ""
        val album = metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM)
            ?: metadata?.description?.description?.toString()
            ?: ""

        val artwork: Bitmap? = metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata?.description?.iconBitmap

        val durationMs = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L

        val isPlaying = state?.state == PlaybackState.STATE_PLAYING
        val positionMs = state?.position ?: 0L
        val actions = state?.actions ?: 0L
        val lastUpdateTime = state?.lastPositionUpdateTime ?: SystemClock.elapsedRealtime()
        val playbackSpeed = state?.playbackSpeed ?: 1f

        val canSkipPrev = (actions and PlaybackState.ACTION_SKIP_TO_PREVIOUS) != 0L
        val canSkipNext = (actions and PlaybackState.ACTION_SKIP_TO_NEXT) != 0L
        val canSeek = (actions and PlaybackState.ACTION_SEEK_TO) != 0L

        val hasActiveMedia = title.isNotBlank() || isPlaying || (state != null && state.state != PlaybackState.STATE_NONE)

        _mediaData.value = MediaData(
            title = title,
            artist = artist,
            album = album,
            artwork = artwork,
            appIcon = appIcon,
            appName = appName,
            packageName = pkg,
            isPlaying = isPlaying,
            positionMs = positionMs,
            durationMs = durationMs,
            canSkipPrev = canSkipPrev,
            canSkipNext = canSkipNext,
            canSeek = canSeek,
            hasActiveMedia = hasActiveMedia,
            lastUpdateTime = lastUpdateTime,
            playbackSpeed = playbackSpeed,
        )
    }

    private fun updatePlaybackState(state: PlaybackState?) {
        val current = _mediaData.value
        if (state == null) {
            _mediaData.value = current.copy(isPlaying = false)
            return
        }
        val isPlaying = state.state == PlaybackState.STATE_PLAYING
        val actions = state.actions
        _mediaData.value = current.copy(
            isPlaying = isPlaying,
            positionMs = state.position,
            lastUpdateTime = state.lastPositionUpdateTime,
            playbackSpeed = state.playbackSpeed,
            canSkipPrev = (actions and PlaybackState.ACTION_SKIP_TO_PREVIOUS) != 0L,
            canSkipNext = (actions and PlaybackState.ACTION_SKIP_TO_NEXT) != 0L,
            canSeek = (actions and PlaybackState.ACTION_SEEK_TO) != 0L,
            hasActiveMedia = current.title.isNotBlank() || isPlaying
        )
    }

    private fun updateMetadata(metadata: MediaMetadata?) {
        activeController?.let { extractDataFromController(it) }
    }

    fun playPause() {
        val controller = activeController ?: return
        val isPlaying = _mediaData.value.isPlaying
        if (isPlaying) {
            controller.transportControls.pause()
        } else {
            controller.transportControls.play()
        }
    }

    fun skipToPrevious() {
        activeController?.transportControls?.skipToPrevious()
    }

    fun skipToNext() {
        activeController?.transportControls?.skipToNext()
    }

    fun seekTo(positionMs: Long) {
        activeController?.transportControls?.seekTo(positionMs)
    }

    fun openApp() {
        val pkg = _mediaData.value.packageName
        if (pkg.isBlank()) return
        try {
            val intent = context.packageManager.getLaunchIntentForPackage(pkg)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (intent != null) {
                context.startActivity(intent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch $pkg", e)
        }
    }
}
