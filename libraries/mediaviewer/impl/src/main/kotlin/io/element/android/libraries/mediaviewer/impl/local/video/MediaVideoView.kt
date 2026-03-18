/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2024, 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.mediaviewer.impl.local.video

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Bundle
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.Player.STATE_READY
import androidx.media3.common.Timeline
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.toBitmap
import io.element.android.compound.theme.ElementTheme
import io.element.android.libraries.core.data.tryOrNull
import io.element.android.libraries.designsystem.preview.ElementPreview
import io.element.android.libraries.designsystem.preview.PreviewsDayNight
import io.element.android.libraries.designsystem.text.toDp
import io.element.android.libraries.designsystem.theme.components.Text
import io.element.android.libraries.designsystem.utils.KeepScreenOn
import io.element.android.libraries.matrix.ui.media.MediaRequestData
import io.element.android.libraries.mediaviewer.api.local.LocalMedia
import io.element.android.libraries.mediaviewer.impl.local.LocalMediaViewState
import io.element.android.libraries.mediaviewer.impl.local.PlayableState
import io.element.android.libraries.mediaviewer.impl.local.player.LocalMediaPlaybackContext
import io.element.android.libraries.mediaviewer.impl.local.player.MediaPlayerControllerState
import io.element.android.libraries.mediaviewer.impl.local.player.MediaPlayerControllerView
import io.element.android.libraries.mediaviewer.impl.local.player.rememberMediaServicePlayer
import io.element.android.libraries.mediaviewer.impl.local.player.seekToEnsurePlaying
import io.element.android.libraries.mediaviewer.impl.local.player.togglePlay
import io.element.android.libraries.mediaviewer.impl.local.rememberLocalMediaViewState
import kotlinx.coroutines.delay
import me.saket.telephoto.zoomable.zoomable
import timber.log.Timber
import java.io.ByteArrayOutputStream
import kotlin.time.Duration.Companion.seconds

@SuppressLint("UnsafeOptInUsageError")
@Composable
fun MediaVideoView(
    isDisplayed: Boolean,
    localMediaViewState: LocalMediaViewState,
    bottomPaddingInPixels: Int,
    localMedia: LocalMedia?,
    autoplay: Boolean,
    modifier: Modifier = Modifier,
) {
    val player = rememberMediaServicePlayer()
    if (player != null) {
        ServicePlayerMediaVideoView(
            isDisplayed = isDisplayed,
            localMediaViewState = localMediaViewState,
            bottomPaddingInPixels = bottomPaddingInPixels,
            player = player,
            localMedia = localMedia,
            autoplay = autoplay,
            modifier = modifier,
        )
    }
}

@SuppressLint("UnsafeOptInUsageError")
@Composable
private fun ServicePlayerMediaVideoView(
    isDisplayed: Boolean,
    localMediaViewState: LocalMediaViewState,
    bottomPaddingInPixels: Int,
    player: Player,
    localMedia: LocalMedia?,
    autoplay: Boolean,
    modifier: Modifier = Modifier,
) {
    var mediaPlayerControllerState: MediaPlayerControllerState by remember {
        mutableStateOf(
            MediaPlayerControllerState(
                isVisible = true,
                isPlaying = false,
                isReady = false,
                progressInMillis = 0,
                durationInMillis = 0,
                canMute = true,
                isMuted = false,
            )
        )
    }

    val playableState: PlayableState.Playable by remember {
        derivedStateOf {
            PlayableState.Playable(
                isShowingControls = mediaPlayerControllerState.isVisible,
            )
        }
    }

    localMediaViewState.playableState = playableState

    val playerListener = remember {
        object : Player.Listener {
            override fun onRenderedFirstFrame() {
                localMediaViewState.isReady = true
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                mediaPlayerControllerState = mediaPlayerControllerState.copy(
                    isPlaying = isPlaying,
                )
            }

            override fun onVolumeChanged(volume: Float) {
                mediaPlayerControllerState = mediaPlayerControllerState.copy(
                    isMuted = volume == 0f,
                )
            }

            override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                if (reason == Player.TIMELINE_CHANGE_REASON_SOURCE_UPDATE) {
                    player.duration.takeIf { it >= 0 }
                        ?.let {
                            mediaPlayerControllerState = mediaPlayerControllerState.copy(
                                durationInMillis = it,
                            )
                        }
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                mediaPlayerControllerState = mediaPlayerControllerState.copy(
                    isReady = playbackState == STATE_READY,
                )
            }
        }
    }

    var autoHideController by remember { mutableIntStateOf(0) }

    LaunchedEffect(autoHideController) {
        delay(5.seconds)
        if (player.isPlaying) {
            mediaPlayerControllerState = mediaPlayerControllerState.copy(
                isVisible = false,
            )
        }
    }

    val playbackContext = LocalMediaPlaybackContext.current
    val context = LocalContext.current
    if (localMedia?.uri != null && isDisplayed) {
        LaunchedEffect(localMedia.uri) {
            val artworkBytes = playbackContext.thumbnailSource?.let { source ->
                tryOrNull {
                    val request = ImageRequest.Builder(context)
                        .data(MediaRequestData(source, MediaRequestData.Kind.Thumbnail(256, 256)))
                        .build()
                    val result = context.imageLoader.execute(request)
                    result.image?.toBitmap()?.let { bitmap ->
                        ByteArrayOutputStream().use { stream ->
                            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
                            stream.toByteArray()
                        }
                    }
                }
            }
            val extras = Bundle().apply {
                putString("sessionId", playbackContext.sessionId)
                putString("roomId", playbackContext.roomId)
                putString("eventId", playbackContext.eventId)
            }
            val metadata = MediaMetadata.Builder()
                .setTitle(localMedia.info.filename)
                .setArtist(localMedia.info.senderName)
                .apply { artworkBytes?.let { setArtworkData(it, MediaMetadata.PICTURE_TYPE_FRONT_COVER) } }
                .setExtras(extras)
                .build()
            val mediaItem = MediaItem.Builder()
                .setUri(localMedia.uri)
                .setMediaMetadata(metadata)
                .build()
            val currentEventId = player.currentMediaItem?.mediaMetadata?.extras?.getString("eventId")
            if (currentEventId == playbackContext.eventId && player.playbackState != Player.STATE_IDLE) {
                // Same item already loaded/playing — don't reset
            } else {
                player.setMediaItem(mediaItem)
                player.prepare()
            }
        }
    } else if (!isDisplayed) {
        // Don't clear media items when not displayed - just don't set new ones
    } else {
        player.setMediaItems(emptyList())
    }
    KeepScreenOn(mediaPlayerControllerState.isPlaying)
    Box(
        modifier = modifier
            .background(ElementTheme.colors.bgSubtlePrimary),
    ) {
        if (LocalInspectionMode.current) {
            Text(
                modifier = Modifier
                    .background(ElementTheme.colors.bgSubtlePrimary)
                    .align(Alignment.Center),
                text = "A Video Player will render here",
            )
        } else {
            AndroidView(
                modifier = Modifier
                    .fillMaxSize()
                    .zoomable(
                        state = localMediaViewState.zoomableState,
                        onClick = {
                            autoHideController++
                            mediaPlayerControllerState = mediaPlayerControllerState.copy(
                                isVisible = !mediaPlayerControllerState.isVisible,
                            )
                        }
                    ),
                factory = {
                    PlayerView(context).apply {
                        this.player = player
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
                        useController = false
                    }
                },
                onRelease = { playerView ->
                    playerView.player = null
                },
            )
        }
        MediaPlayerControllerView(
            state = mediaPlayerControllerState,
            onTogglePlay = {
                autoHideController++
                player.togglePlay()
            },
            onSeekChange = {
                autoHideController++
                player.seekToEnsurePlaying(it.toLong())
            },
            onToggleMute = {
                autoHideController++
                player.volume = if (player.volume == 1f) 0f else 1f
            },
            // Pass null: the service's ExoPlayer handles audio focus via handleAudioFocus=true.
            // Passing audioFocus here would cause a second AudioManager.requestAudioFocus() call
            // that conflicts with ExoPlayer's internal focus request, instantly pausing playback.
            audioFocus = null,
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(bottom = bottomPaddingInPixels.toDp()),
        )
    }

    LaunchedEffect(player.isPlaying) {
        if (player.isPlaying) {
            while (true) {
                mediaPlayerControllerState = mediaPlayerControllerState.copy(
                    progressInMillis = player.currentPosition,
                )
                delay(200)
            }
        } else {
            // Ensure we render the final state
            mediaPlayerControllerState = mediaPlayerControllerState.copy(
                progressInMillis = player.currentPosition,
            )
        }
    }

    PlayerLifecycleHelper(
        player = player,
        autoplay = autoplay,
        isDisplayed = isDisplayed,
        playerListener = playerListener,
        mediaPlayerControllerState = mediaPlayerControllerState,
    )
}

@Composable
private fun PlayerLifecycleHelper(
    player: Player,
    autoplay: Boolean,
    isDisplayed: Boolean,
    playerListener: Player.Listener,
    mediaPlayerControllerState: MediaPlayerControllerState,
) {
    // Add and remove listener with the composable lifecycle
    DisposableEffect(Unit) {
        Timber.d("ServicePlayerMediaVideoView DisposableEffect: adding listener")
        player.addListener(playerListener)

        onDispose {
            Timber.d("Disposing player listener")
            player.removeListener(playerListener)
        }
    }

    var needsAutoPlay by remember { mutableStateOf(autoplay) }
    LaunchedEffect(needsAutoPlay, isDisplayed, mediaPlayerControllerState.isReady) {
        val isReadyAndNotPlaying = mediaPlayerControllerState.isReady && !mediaPlayerControllerState.isPlaying
        if (needsAutoPlay && isDisplayed && isReadyAndNotPlaying) {
            // When displayed, start autoplaying
            player.play()
            needsAutoPlay = false
        } else if (!isDisplayed && mediaPlayerControllerState.isPlaying) {
            // If not displayed, make sure to pause the video
            player.pause()
        }
    }
}

@PreviewsDayNight
@Composable
internal fun MediaVideoViewPreview() = ElementPreview {
    MediaVideoView(
        isDisplayed = true,
        modifier = Modifier.fillMaxSize(),
        bottomPaddingInPixels = 0,
        localMediaViewState = rememberLocalMediaViewState(),
        localMedia = null,
        autoplay = false,
    )
}
