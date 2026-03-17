# Background Media Playback - Task Breakdown

## Overview

When playing media, playback stops when the app enters background. The goal is to enable background playback with a MediaStyle notification for audio/video, while keeping voice messages behaving as they do today (background playback, no notification).

---

## Detailed Requirements

### General Behavior

- Only **one media file plays at a time**. Starting a new file silently stops the current one.
- Playback continues in background for both audio and video files.
- Videos play **audio-only** in background (video surface detaches, audio continues).
- When playback ends naturally (or reaches the last file boundary), the service stops and the notification is dismissed **immediately**.
- If the user **swipes the notification away**, playback fully stops.

### MediaStyle Notification

- **Content title**: filename (fallback: room name if no filename)
- **Content text**: sender's display name (user's own display name for self-sent media)
- **Large icon**:
  - Video files -> video thumbnail
  - Audio files -> room's profile picture (avatar)
- **Tap action**: navigates back to the media viewer (re-creates it if it was closed/destroyed)
- **Transport controls**: play/pause, seek, previous/next

### Skip (Next / Previous)

- Skips to the next/previous **audio or video file** in the room timeline (chronological, oldest->newest).
- **Skips voice messages** — only regular audio/video files count.
- At the **boundary** (no more files in that direction): stop playback entirely, dismiss notification, and exit player.
- If the media viewer is open when skipping via notification, it syncs and shows the new file.
- If the media viewer is **not** open, don't open it — just update the notification.

### In-App Player Changes

- **New stop button**: in the top app bar, to the right of the back button. Completely stops playback and the foreground service.
- When navigating **back from a re-created media viewer**, the room timeline should scroll to the message of the currently playing file.

### Voice Messages

- Voice messages **do not** get a MediaStyle notification.
- Voice messages continue to play in background as they do today.
- If a voice message starts while media is playing, the voice message **requests audio focus**, pausing the media. When the voice message ends, the media **resumes automatically**.

### Audio Focus

- An incoming **Element Call** behaves the same as voice messages: pauses media, resumes after call ends.
- Standard Android audio focus protocol applies.

### State Synchronization

- Seek position and play/pause state are synchronized bidirectionally between the in-app player UI and the MediaStyle notification.
- If the user pauses from the notification, the in-app player reflects it (and vice versa).

---

## Task Dependency Graph

```
Task 1 (Service + MediaSession)          <--- DONE (PR #6351)
  |
  +-- Task 2 (MediaStyle notification)
  |     +-- Task 5 (State sync)
  |     +-- Task 6 (Skip next/prev)
  |
  +-- Task 3 (Video background audio)     <--- PARTIALLY DONE (Task 1 removed ON_PAUSE, but video surface detach not yet handled)
  +-- Task 4 (Voice message interaction)
  +-- Task 7 (Stop button + navigation)
```

**Suggested order**: Task 1 first, then Tasks 2/3/4/7 (independent, any order), then Tasks 5/6 (need Task 2).

---

## TASK 1: Foreground Service + MediaSession (Foundation)

> **Status: DONE** — PR #6351 (branch: `feature/media-playback-foreground-service`)
> All other tasks depend on this.

### What was implemented

We chose **Option C** (MediaSession + MediaController pattern) — the service owns the ExoPlayer + MediaSession, and the UI communicates via `MediaController` which implements the `Player` interface.

### Files created

| File | Location | Purpose |
|---|---|---|
| **MediaPlaybackService.kt** | `libraries/mediaviewer/impl/.../local/player/` | `MediaSessionService` subclass. Owns ExoPlayer with `handleAudioFocus=true` and `handleAudioBecomingNoisy=true`. Auto-manages foreground notification. Stops on playback end or when app swiped from recents while paused. |
| **MediaPlaybackServiceConnection.kt** | `libraries/mediaviewer/impl/.../local/player/` | `rememberMediaServicePlayer()` composable. Connects to service via `MediaController.Builder.buildAsync()`, returns `Player?` (null while connecting ~10-50ms). Handles preview mode with `ExoPlayerForPreview`. |
| **AndroidManifest.xml** | `libraries/mediaviewer/impl/src/main/` | Declares service with `foregroundServiceType="mediaPlayback"`, `exported="true"` + intent-filter (required by media3), and `FOREGROUND_SERVICE`/`FOREGROUND_SERVICE_MEDIA_PLAYBACK` permissions. |

### Files modified

| File | Change |
|---|---|
| `gradle/libs.versions.toml` | Added `androidx_media3_session` dependency |
| `libraries/mediaviewer/impl/build.gradle.kts` | Added `implementation(libs.androidx.media3.session)` |
| `ExoPlayerExtensions.kt` | Changed extension receivers from `ExoPlayer` to `Player` interface |
| `MediaVideoView.kt` | Replaced `rememberExoPlayer()` with `rememberMediaServicePlayer()`, null-guarded player, removed `ON_PAUSE` lifecycle handler and `exoPlayer.release()`, guarded `setMediaItem` with `isDisplayed`, added `player.prepare()` after setting media item, threaded `audioFocus` through to `MediaPlayerControllerView` |
| `MediaAudioView.kt` | Same pattern as video: replaced player, removed `OnLifecycleEvent` block (prepare/pause/release), replaced with `DisposableEffect` for listener add/remove, threaded `audioFocus` through |

### Key architectural decisions

1. **Service placed in `mediaviewer/impl` module** (not `mediaplayer`). The `mediaplayer` module is for voice messages only — keeping them separate avoids coupling.

2. **`MediaSessionService` used instead of raw `Service`**. This auto-handles foreground promotion, notification, and service lifecycle. No manual `ServiceCompat.startForeground()` or `NotificationIdProvider` changes needed for Task 1.

3. **`handleAudioFocus=true` on ExoPlayer in the service**. This handles Android system-level audio focus automatically. However, the app-internal `AudioFocus` system (used by voice messages) is still passed through the UI chain to `MediaPlayerControllerView` — see lesson learned below.

4. **`ExoPlayerForPreview` still implements `ExoPlayer`** (not just `Player`). This is fine because `rememberMediaServicePlayer()` returns it in preview mode, and `ExoPlayer extends Player`.

5. **`rememberExoPlayer()` / `ExoPlayerFactory.kt` kept** but no longer used by video/audio views. Can be removed later if nothing else references them.

### Lessons learned (IMPORTANT for future tasks)

#### Dual audio focus systems — DO NOT remove app-internal AudioFocus

The project has **two audio focus systems** that must coexist:

1. **Android system audio focus** — handled by ExoPlayer's `handleAudioFocus=true` in the service. Coordinates with other apps.
2. **App-internal `AudioFocus`** — custom abstraction in `libraries/audio/api`. Coordinates between voice messages (`DefaultMediaPlayer` uses `AudioFocusRequester.VoiceMessage`) and media viewer (`MediaPlayerControllerView` uses `AudioFocusRequester.MediaViewer`).

**The voice message player (`DefaultMediaPlayer`) does NOT use Android system audio focus.** It uses the app-internal `AudioFocus` exclusively. If you remove the `audioFocus` parameter from the media viewer chain, voice messages and media playback won't coordinate (starting a video won't pause a voice message).

We initially removed `audioFocus` to fix detekt unused-parameter warnings, then had to revert because it broke voice message coordination. The fix was to **thread `audioFocus` all the way through** to `MediaPlayerControllerView` where it's actually used.

**For Task 4 (Voice Message Interaction)**: The `DefaultMediaPlayer` will need to either (a) also use Android system audio focus, or (b) the app-internal `AudioFocus` system needs to bridge with the service's `MediaSession`. Currently both systems work in parallel — the app-internal one handles voice message <-> media viewer coordination, while the service's ExoPlayer handles external app coordination.

#### HorizontalPager pre-loading guard

`beyondViewportPageCount = 1` means adjacent pages exist in composition. We guard `setMediaItem` calls with `isDisplayed` to prevent pre-loaded pages from overriding the active page's media on the shared service player.

#### MediaController connection is async

`rememberMediaServicePlayer()` returns `Player?`. It's null for ~10-50ms while `MediaController.Builder.buildAsync()` connects. The UI renders nothing (or loading) until the player is available. This is fine because `MediaSessionService` auto-starts when a controller connects.

#### Guava dependency

`MediaController.Builder.buildAsync()` returns `ListenableFuture`. Uses `MoreExecutors.directExecutor()` from Guava, which is transitively provided by `media3-session`.

### Build verification

- `./gradlew :libraries:mediaviewer:impl:assembleDebug` — PASSES
- `./gradlew :libraries:mediaviewer:impl:testDebugUnitTest` — PASSES
- `./gradlew :libraries:mediaviewer:impl:ktlintCheck` — PASSES
- `./gradlew :libraries:mediaviewer:impl:detekt` — PASSES
- `./gradlew :app:assembleGplayDebug` (full app APK) — PASSES

### What still needs testing on a device

- Background audio: play audio in media viewer -> Home -> verify playback continues + notification
- Background video audio: play video -> Home -> verify audio continues
- Voice messages unaffected: play voice message -> verify no foreground service
- Service lifecycle: play -> finish naturally -> verify service stops
- App swipe: while playing -> swipe -> verify continues; while paused -> swipe -> verify stops
- Pager navigation: swipe between items -> only current plays

---

## TASK 2: MediaStyle Notification (depends on Task 1)

> **Status: DONE**
> Implemented: notification metadata (title + subtitle), artwork (video thumbnail / room avatar), tap-to-foreground intent, dismiss behavior.

Enrich the auto-generated `MediaSessionService` notification with metadata, a large icon, and a tap-to-navigate intent.

### What the notification currently does (after Task 1)

The `MediaSessionService` auto-creates a basic notification with play/pause transport controls. But:
- Title and subtitle are empty (no metadata set on the `MediaItem`)
- No large icon / artwork
- Tapping the notification does nothing (no session activity `PendingIntent` set)

### Sub-feature 1: Notification metadata (title + subtitle)

**Current code** (both `MediaVideoView.kt:168` and `MediaAudioView.kt:190`):
```kotlin
val mediaItem = MediaItem.fromUri(localMedia.uri)
```
This creates a `MediaItem` with no metadata → notification title/subtitle are blank.

**Fix**: Replace with `MediaItem.Builder()` + `MediaMetadata.Builder()`:
```kotlin
val metadata = MediaMetadata.Builder()
    .setTitle(localMedia.info.filename)      // → notification title
    .setArtist(localMedia.info.senderName)   // → notification subtitle
    .build()
val mediaItem = MediaItem.Builder()
    .setUri(localMedia.uri)
    .setMediaMetadata(metadata)
    .build()
```

Media3's `MediaSessionService` automatically reads `MediaMetadata.title` and `MediaMetadata.artist` for the notification. No custom `MediaNotification.Provider` needed.

**Data availability** (verified against source):
- `MediaVideoView` receives `localMedia: LocalMedia?` → `localMedia.info.filename` and `localMedia.info.senderName` are available
- `MediaAudioView` receives both `localMedia: LocalMedia?` AND `info: MediaInfo?` (passed separately by `LocalMediaView` at line 71). Use `info` with fallback to `localMedia.info`

### Sub-feature 2: Notification large icon (artwork)

**Goal**: Show a thumbnail/artwork as the notification's large icon:
- **Video files** → video thumbnail (from `thumbnailSource`)
- **Audio files** → room's profile picture (from `room.info().avatarUrl`)

**How media3 uses artwork**: `MediaMetadata.setArtworkData(bytes, PICTURE_TYPE_FRONT_COVER)` — media3's `MediaSessionService` auto-uses this as the notification's large icon. No custom `MediaNotification.Provider` needed.

**Data sources** (verified against source):
- **Video thumbnail**: `MediaViewerPageData.MediaViewerData.thumbnailSource: MediaSource?` (line 50 of `MediaViewerState.kt`). This is a matrix mxc:// URI pointing to the video's thumbnail image.
- **Room avatar**: `room.info().avatarUrl: String?` (from `RoomInfo.avatarUrl`, line 29 of `RoomInfo.kt`). This is a matrix mxc:// URI for the room's profile picture. Available via `room: JoinedRoom` → `room.roomInfoFlow.value.avatarUrl`. Add this to `MediaViewerState` (alongside `sessionId`/`roomId`).

**Loading the artwork**: Both URLs are mxc:// URIs that require the Matrix media pipeline to download. The project already has Coil configured with custom fetchers for Matrix media:
- `MediaRequestData(source, Kind.Thumbnail(width, height))` — wraps a `MediaSource` for Coil
- `CoilMediaFetcher` (in `libraries/matrixmedia/impl`) — implements Coil's `Fetcher`, uses `MatrixMediaLoader` to download
- `MediaRequestDataFetcherFactory` + `MediaRequestDataKeyer` — registered on the singleton `ImageLoader`
- The singleton `ImageLoader` is set per-session in `LoggedInAppScopeFlowNode` via `SingletonImageLoader.setUnsafe()`

**How to load in composables**: Use `context.imageLoader.execute(ImageRequest)` inside the `LaunchedEffect` where the `MediaItem` is built:

```kotlin
// In MediaVideoView / MediaAudioView, inside LaunchedEffect(localMedia.uri):
val artworkBytes = artworkSource?.let { source ->
    val request = ImageRequest.Builder(context)
        .data(MediaRequestData(source, MediaRequestData.Kind.Thumbnail(256, 256)))
        .build()
    val result = imageLoader.execute(request)
    (result.image as? BitmapImage)?.bitmap?.let { bitmap ->
        ByteArrayOutputStream().use { stream ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
            stream.toByteArray()
        }
    }
}
val metadata = MediaMetadata.Builder()
    .setTitle(...)
    .setArtist(...)
    .apply { artworkBytes?.let { setArtworkData(it, MediaMetadata.PICTURE_TYPE_FRONT_COVER) } }
    .setExtras(extras)
    .build()
```

**Passing artwork sources to the views via CompositionLocal**:

The `MediaPlaybackContext` CompositionLocal (created for sub-feature 3 below) carries all context. Extend it with artwork:
```kotlin
data class MediaPlaybackContext(
    val sessionId: String = "",
    val roomId: String = "",
    val eventId: String = "",
    val thumbnailSource: MediaSource? = null,  // video thumbnail (per-page, from data.thumbnailSource)
    val roomAvatarUrl: String? = null,         // room avatar (same for all pages, from state.roomAvatarUrl)
)
```

In `MediaVideoView`: use `playbackContext.thumbnailSource` as the artwork source.
In `MediaAudioView`: use `playbackContext.roomAvatarUrl?.let { MediaSource(it) }` as the artwork source.

**New field on `MediaViewerState`**: `roomAvatarUrl: String?` (populated by presenter from `room.info().avatarUrl`).

### Sub-feature 3: Notification tap intent (navigate to room)

**Goal**: Tapping the notification opens the app and navigates to the room where media is from.

**Mechanism**: `mediaSession.setSessionActivity(pendingIntent)` — media3 API that sets the `PendingIntent` invoked when the notification is tapped.

**Challenge**: The service (`MediaPlaybackService`) has no knowledge of `sessionId`, `roomId`, or `eventId`. These live in the UI layer.

**Solution (2 parts)**:

**Part A — Get context from UI to service via `MediaMetadata.extras`**:
1. Create a `CompositionLocal` (`LocalMediaPlaybackContext`) holding `sessionId`, `roomId`, `eventId`, `thumbnailSource`, `roomAvatarUrl`
2. Provide it at the `MediaViewerView` level (wrapping HorizontalPager pages)
3. Read it in `MediaVideoView`/`MediaAudioView` and put values into a `Bundle` on `MediaMetadata.extras`
4. The service's `Player.Listener.onMediaMetadataChanged()` reads the extras

Where the data comes from (verified):
- `sessionId` → `MediaViewerPresenter` has `room: JoinedRoom` (line 60) → `room.sessionId.value`
- `roomId` → same `room: JoinedRoom` → `room.roomId.value`
- `eventId` → `MediaViewerPageData.MediaViewerData.eventId` (line 47 of `MediaViewerState.kt`)
- `thumbnailSource` → `MediaViewerPageData.MediaViewerData.thumbnailSource` (line 50 of `MediaViewerState.kt`)
- `roomAvatarUrl` → `room.info().avatarUrl` (from `RoomInfo`, line 29)

New fields needed on `MediaViewerState`: `sessionId: String`, `roomId: String`, and `roomAvatarUrl: String?` (populated by presenter). The `eventId` and `thumbnailSource` are per-page, provided via the `CompositionLocal` at the pager level.

**Part B — Service builds `PendingIntent` from extras**:

```kotlin
// In MediaPlaybackService, in Player.Listener:
override fun onMediaMetadataChanged(metadata: MediaMetadata) {
    val extras = metadata.extras ?: return
    val sessionId = extras.getString("sessionId") ?: return
    val roomId = extras.getString("roomId") ?: return
    val deepLinkUri = "elementx://open/$sessionId/$roomId".toUri()
    val intent = Intent(Intent.ACTION_VIEW, deepLinkUri).apply {
        setPackage(packageName)
    }
    val pendingIntent = PendingIntent.getActivity(this, 0, intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    mediaSession?.setSessionActivity(pendingIntent)
}
```

The deep link format `elementx://open/{sessionId}/{roomId}` is the same one used by push notifications. `MainActivity` (declared `singleTask` in manifest) handles it via `onNewIntent` → `IntentResolver` → `RootFlowNode.navigateTo()`.

### Sub-feature 4: Notification dismiss

Already handled by media3. No code needed:
- While playing → notification is non-dismissible (foreground service)
- While paused → service exits foreground → notification dismissible → swiping triggers `onDestroy()`

### Implementation gotchas (learned from failed attempt)

**DO NOT** add `deeplink` module as dependency to `mediaviewer/impl` — construct the URI string directly.

**DO NOT** forget to update `aMediaViewerState()` in `MediaViewerStateProvider.kt` when adding `sessionId`/`roomId`/`roomAvatarUrl` to `MediaViewerState`. Every test and preview that uses this helper will break.

**DO NOT** use parentheses around sub-conditions in `||` chains — detekt flags `UnnecessaryParentheses`.

**Artwork loading may fail** (network error, no thumbnail available, etc.). This is fine — just skip `setArtworkData()` and the notification renders without a large icon. Don't crash or block on this.

### Files to modify

| File | Change |
|------|--------|
| `MediaVideoView.kt` | Replace `MediaItem.fromUri()` with `MediaItem.Builder()` + `MediaMetadata` (title, artist, artworkData from thumbnail, extras Bundle) |
| `MediaAudioView.kt` | Same as video, but artwork from room avatar instead of thumbnail |
| `MediaPlaybackService.kt` | Add `onMediaMetadataChanged` listener → `updateSessionActivity()` with `PendingIntent` |
| `MediaViewerView.kt` | Wrap pager pages with `CompositionLocalProvider(LocalMediaPlaybackContext provides ...)` |
| `MediaViewerState.kt` | Add `sessionId: String`, `roomId: String`, and `roomAvatarUrl: String?` fields |
| `MediaViewerPresenter.kt` | Populate `sessionId` / `roomId` / `roomAvatarUrl` from `room` |
| `MediaViewerStateProvider.kt` | Update `aMediaViewerState()` with dummy `sessionId` / `roomId` / `roomAvatarUrl` |
| **New**: `MediaPlaybackContext.kt` | `data class MediaPlaybackContext` + `val LocalMediaPlaybackContext = compositionLocalOf { ... }` |

### Implementation order

1. **Sub-feature 1** (title + subtitle): Simplest — just change `MediaItem` construction in both views
2. **Sub-feature 3** (tap intent): Create `MediaPlaybackContext`, add state fields, provide CompositionLocal, update service
3. **Sub-feature 2** (large icon): Load artwork via Coil in `LaunchedEffect`, set on `MediaMetadata`
4. **Sub-feature 4** (dismiss): Already works, verify only

---

## TASK 3: Video Background Audio-Only (depends on Task 1)

> **Status: PARTIALLY DONE**

Task 1 removed the `ON_PAUSE` handler that killed playback on background. However, the video surface behavior needs attention.

### What was already done in Task 1

- Removed `ON_PAUSE -> exoPlayer.pause()` from `MediaVideoView`
- ExoPlayer now lives in the service, so it survives backgrounding
- `PlayerView.player = controller` works for video rendering via direct connection

### What still needs to be done

- Verify that when the app backgrounds, the `PlayerView` surface detaches gracefully and audio continues
- If the `PlayerView` is disposed while backgrounded, ensure re-attaching when foregrounded works (the `AndroidView` factory creates a new `PlayerView` and sets `player = controller`)
- Test edge case: background during video -> come back -> video surface re-renders

### Key consideration from Task 1

In same-process `MediaController` connections (our case), media3 uses direct binding. `PlayerView.setPlayer(controller)` renders video frames directly, no IPC overhead. The surface should detach/reattach cleanly.

---

## TASK 4: Voice Message Interaction (depends on Task 1)

> **Status: NOT STARTED — do after Task 1**

Voice messages must not trigger the notification, and must properly interact with media playback via audio focus.

### What needs to change

- **Modify**: `VoiceMessagePlayer.kt` (`libraries/voiceplayer/impl/.../VoiceMessagePlayer.kt`) — distinguish from regular media; add audio focus request that pauses media and resumes on completion
- **Modify**: Service / notification logic — skip notification for voice message playback
- **Modify**: `DefaultAudioFocus.kt` (`libraries/audio/impl/.../DefaultAudioFocus.kt`) — handle the pause-and-resume-after pattern for voice message -> media handoff

### CORRECTED finding (from audio focus bug investigation)

**`DefaultAudioFocus` IS Android system audio focus** — it wraps `AudioManager.requestAudioFocus()`. It is NOT a separate "app-internal" system. See Knowledge Base section "Audio Focus: CORRECTED Understanding" for full details.

**Current state after Task 1 fix**: The `audioFocus` parameter has been completely removed from the media viewer chain. `MediaPlayerControllerView` receives `audioFocus = null` from both video and audio views. The service's ExoPlayer handles all audio focus via `handleAudioFocus=true`.

**Voice message coordination already works** because:
1. `DefaultMediaPlayer.play()` calls `DefaultAudioFocus.requestAudioFocus(VoiceMessage)` → `AudioManager.requestAudioFocus(USAGE_VOICE_COMMUNICATION)`
2. Service's ExoPlayer loses `USAGE_MEDIA` focus → auto-pauses
3. When voice message ends, `DefaultMediaPlayer` calls `audioFocus.releaseAudioFocus()` → `AudioManager.abandonAudioFocus()`

**Open question for Task 4**: After voice message ends and abandons focus, does ExoPlayer auto-resume? With `handleAudioFocus=true`:
- On `AUDIOFOCUS_LOSS` (permanent): `playWhenReady = false` → won't resume
- On `AUDIOFOCUS_LOSS_TRANSIENT`: `playWhenReady` stays `true` → will resume on `AUDIOFOCUS_GAIN`

The voice message requests `AUDIOFOCUS_GAIN` (newer APIs) which causes permanent loss. Media may NOT auto-resume. This needs investigation/testing for Task 4.

---

## TASK 5: State Synchronization (depends on Tasks 1 + 2)

> **Status: NOT STARTED — do after Tasks 1 and 2**

Bidirectional sync of seek position and play/pause between in-app UI and notification.

### What needs to change

- **Modify**: `MediaPlayerControllerView.kt` (`libraries/mediaviewer/impl/.../player/MediaPlayerControllerView.kt`) — observe `MediaSession` transport state
- **Modify**: `MediaAudioView.kt` — delegate lifecycle to service instead of pausing locally
- **Modify**: `DefaultAudioFocus.kt` — coordinate with `MediaSession`'s built-in focus support

### Key advantage from Task 1

Since we used `MediaController` (which implements `Player`), state sync is largely automatic. The `MediaController` already reflects the service's ExoPlayer state. The `Player.Listener` callbacks in the UI already fire when the notification controls change playback state. The `LaunchedEffect(player.isPlaying)` blocks in both views already react to state changes from any source.

**Remaining work**: Verify that notification seek/pause actions propagate to the UI's `mediaPlayerControllerState` correctly. May need to ensure the progress polling `LaunchedEffect` restarts when playback resumes from notification.

---

## TASK 6: Skip Next/Previous in Room Timeline (depends on Tasks 1 + 2)

> **Status: NOT STARTED — do after Tasks 1 and 2**
> **Risk: HIGH** — requires the service layer to query the room's media timeline

### What needs to change

- **New logic**: Query the room's media timeline for audio/video files (excluding voice messages), ordered chronologically
- **Modify**: Service layer — needs access to the room's media timeline to resolve next/previous
- **Modify**: `MediaSession` callback — wire next/previous actions to timeline navigation
- **Modify**: Media viewer — if open, sync to the newly playing file
- **Boundary behavior**: at last/first file, stop playback and dismiss

### Key consideration from Task 1

The `MediaPlaybackService` currently has no knowledge of the room timeline or which media items exist. It only knows about the single `MediaItem` set by the UI. For skip to work, either:
- The service needs a reference to the timeline data source
- Or the UI (if open) handles skip commands and sets the next media item on the controller

---

## TASK 7: In-App Player Stop Button + Navigation (depends on Task 1)

> **Status: PARTIALLY DONE**
> Sub-feature 3 (notification tap → media viewer) is DONE. Stop button and service cleanup remain.

### Sub-features

1. **Stop button in top bar**: In `navigationIcon` slot via `Row { BackButton; StopButton }`. Use `context.stopService()` NOT `player.stop()`. See Knowledge Base "Stopping the Service from UI".
2. **Service stops on cleared items**: Add `STATE_IDLE + mediaItemCount == 0` check to `onPlaybackStateChanged`.
3. **~~Navigation from re-created media viewer~~**: **DONE** — Tapping the notification now deep links with `?media=true`, which navigates to the room timeline focused on the event and auto-opens the media viewer. Back button returns to the timeline scrolled to that event. See Knowledge Base "Notification Tap Intent: Deep Link to Media Viewer".

### Files to modify

| File | Change |
|------|--------|
| `MediaViewerView.kt` | Stop button in top bar, `context.stopService()` |
| `MediaPlaybackService.kt` | `STATE_IDLE` + empty items handling |
| **New**: `strings_temporary.xml` | Stop button content description |

---

## Summary

| | Count |
|---|---|
| **Total tasks** | 7 |
| **New files** | 3-5 |
| **Modified files** | ~15-18 across ~7 modules |

| Task | Depends on | Risk | Status |
|---|---|---|---|
| 1 - Foreground Service + MediaSession | — | Medium | **DONE** (PR #6351) |
| 2 - MediaStyle Notification | Task 1 | Low | **DONE** (metadata, artwork, tap intent, dismiss) |
| 3 - Video Background Audio | Task 1 | Low | Partially done |
| 4 - Voice Message Interaction | Task 1 | Low-Medium | Not started |
| 5 - State Synchronization | Tasks 1, 2 | Medium | Not started |
| 6 - Skip Next/Previous | Tasks 1, 2 | **High** | Not started |
| 7 - Stop Button + Navigation | Task 1 | Low | **PARTIALLY DONE** (notification tap → media viewer done; stop button remains) |

---

## CRITICAL KNOWLEDGE BASE (learned from Tasks 1, 2, 7 implementation attempts)

### Audio Focus: CORRECTED Understanding

**IMPORTANT CORRECTION**: The Task 1 section above describes `DefaultAudioFocus` as an "app-internal" system separate from Android system audio focus. **This is WRONG.** `DefaultAudioFocus` (in `libraries/audio/impl`) is a wrapper around `AudioManager.requestAudioFocus()` — it IS Android system audio focus.

This means:
- **ExoPlayer in the service** (`handleAudioFocus=true`) calls `AudioManager.requestAudioFocus(USAGE_MEDIA)` internally
- **`DefaultAudioFocus`** in `MediaPlayerControllerView` ALSO calls `AudioManager.requestAudioFocus(USAGE_MEDIA)`
- **Two separate `AudioFocusRequest` objects for the same `USAGE_MEDIA`** fight each other
- When the second request fires, Android tells the first holder (ExoPlayer) it lost focus → ExoPlayer pauses → **instant pause bug**

**The fix**: Pass `audioFocus = null` to `MediaPlayerControllerView` from both `MediaVideoView` and `MediaAudioView`. The service's ExoPlayer handles all audio focus. The entire `audioFocus` parameter chain was removed from the media viewer hierarchy (MediaVideoView, MediaAudioView, LocalMediaView, MediaViewerView, MediaViewerNode, DefaultLocalMediaRenderer, and the `audio:test` dependency).

**Voice messages still work** because `DefaultMediaPlayer` requests focus with `AudioFocusRequester.VoiceMessage` → `USAGE_VOICE_COMMUNICATION`, which causes ExoPlayer to lose `USAGE_MEDIA` focus automatically via Android's focus arbitration. No app-internal plumbing needed.

### Data Flow: State → Views → Player (updated after Task 2)

```
MediaViewerNode (has sessionId: SessionId injected, is in RoomScope)
  ↓
MediaViewerPresenter (has room: JoinedRoom → room.sessionId, room.roomId, room.info().avatarUrl)
  ↓ produces
MediaViewerState
  ├─ listData: ImmutableList<MediaViewerPageData>
  ├─ sessionId: String          (from room.sessionId.value)
  ├─ roomId: String             (from room.roomId.value)
  └─ roomAvatarUrl: String?     (from room.info().avatarUrl)
  ↓
MediaViewerView
  ↓ HorizontalPager with beyondViewportPageCount=1
  ↓ CompositionLocalProvider(LocalMediaPlaybackContext provides ...)
MediaViewerPageData.MediaViewerData
  ├─ eventId: EventId?
  ├─ mediaInfo: MediaInfo (filename, senderName, mimeType, duration, etc.)
  ├─ mediaSource: MediaSource
  ├─ thumbnailSource: MediaSource?
  └─ downloadedMedia: State<AsyncData<LocalMedia>>
      ↓
  MediaViewerPage (private composable)
      ↓
  LocalMediaView (router — dispatches by mimeType)
      ↓
  MediaVideoView / MediaAudioView
      ├─ localMedia: LocalMedia? (has .uri and .info: MediaInfo)
      ├─ info: MediaInfo? (audio only, passed explicitly)
      ├─ player: Player? (from rememberMediaServicePlayer())
      └─ playbackContext: MediaPlaybackContext (from LocalMediaPlaybackContext.current)
            ├─ sessionId, roomId, eventId     → Bundle extras on MediaMetadata
            ├─ thumbnailSource                → artwork for video notification
            └─ roomAvatarUrl                  → artwork for audio notification
```

**MediaPlaybackContext (CompositionLocal)** — carries navigation context and artwork sources from `MediaViewerView` down to `MediaVideoView`/`MediaAudioView` without threading parameters through `LocalMediaView`. Provided per-page in the HorizontalPager. The `sessionId`, `roomId`, `roomAvatarUrl` come from `MediaViewerState` (room-level). The `eventId` and `thumbnailSource` come from `MediaViewerPageData.MediaViewerData` (per-page).

### MediaInfo Fields (available at MediaItem construction point)

```kotlin
data class MediaInfo(
    val filename: String,           // "photo.jpg" — use as notification title
    val caption: String?,
    val mimeType: String,           // "video/mp4", "audio/mpeg", etc.
    val fileSize: Long?,
    val formattedFileSize: String,
    val fileExtension: String,
    val senderId: UserId?,
    val senderName: String?,        // "Alice" — use as notification artist/subtitle
    val senderAvatar: String?,      // Avatar URL (matrix mxc:// URI)
    val dateSent: String?,
    val dateSentFull: String?,
    val waveform: List<Float>?,
    val duration: String?,
)
```

Located at: `libraries/mediaviewer/api/src/main/kotlin/.../MediaInfo.kt`

### How to Set Notification Metadata

Replace `MediaItem.fromUri(uri)` with:
```kotlin
val metadata = MediaMetadata.Builder()
    .setTitle(localMedia.info.filename)       // notification title
    .setArtist(localMedia.info.senderName)    // notification subtitle
    .setExtras(bundle)                        // custom data (sessionId, roomId, eventId)
    .build()
val mediaItem = MediaItem.Builder()
    .setUri(localMedia.uri)
    .setMediaMetadata(metadata)
    .build()
```

Media3's `MediaSessionService` automatically picks up `title` and `artist` from `MediaMetadata` for the notification. No custom `MediaNotification.Provider` needed for basic metadata.

**Artwork/large icon**: Use `MediaMetadata.Builder().setArtworkData(bytes, PICTURE_TYPE_FRONT_COVER)`. Load artwork in the same `LaunchedEffect` via Coil's `ImageLoader.execute()`:
- **Video**: Load `thumbnailSource` (from `MediaPlaybackContext.thumbnailSource`)
- **Audio**: Load room avatar (from `MediaPlaybackContext.roomAvatarUrl` → wrap in `MediaSource`)
- Use `MediaRequestData(source, Kind.Thumbnail(256, 256))` as Coil request data
- Convert result bitmap to `ByteArray` via `bitmap.compress(PNG)`
- If loading fails, skip artwork — notification renders without large icon

### How to Pass Navigation Context to the Service

**Problem**: The service needs `sessionId`, `roomId`, `eventId` to build a notification tap intent, but it has no DI and no access to room data.

**Solution**: Use `MediaMetadata.extras` Bundle on the `MediaItem`. The UI sets extras when calling `setMediaItem()`, and the service reads them in `onMediaMetadataChanged()`.

**To get context into the views**: Use a `CompositionLocal` (`LocalMediaPlaybackContext`) provided at the `MediaViewerView` level (wrapping the pager pages). This avoids threading many new parameters through `LocalMediaView → MediaVideoView/MediaAudioView`.

```kotlin
data class MediaPlaybackContext(
    val sessionId: String = "",
    val roomId: String = "",
    val eventId: String = "",
    val thumbnailSource: MediaSource? = null,  // video thumbnail (per-page)
    val roomAvatarUrl: String? = null,         // room profile picture (same for all pages)
)
val LocalMediaPlaybackContext = compositionLocalOf { MediaPlaybackContext() }
```

Provide it in `MediaViewerView` wrapping the `MediaViewerPageData.MediaViewerData` case in the HorizontalPager. Read it in `MediaVideoView`/`MediaAudioView` before the `LaunchedEffect`.

**In `MediaViewerPresenter`**: `room.sessionId.value`, `room.roomId.value`, and `room.info().avatarUrl` (from injected `room: JoinedRoom`) go into `MediaViewerState`. The `eventId` and `thumbnailSource` come from `dataForPage.eventId?.value` and `dataForPage.thumbnailSource` respectively (per-page data).

### Coil Integration for Artwork Loading

The project has a custom Coil pipeline for loading Matrix media (mxc:// URIs):
- `MediaRequestData(source: MediaSource?, kind: Kind)` — wraps `MediaSource` for Coil (in `libraries/matrixmedia/api`)
- `CoilMediaFetcher` — implements Coil's `Fetcher`, uses `MatrixMediaLoader` (in `libraries/matrixmedia/impl`)
- `MediaRequestDataFetcherFactory` + `MediaRequestDataKeyer` — registered on the singleton `ImageLoader`
- `AvatarDataFetcherFactory` — handles `AvatarData` with mxc:// URL support
- Singleton `ImageLoader` set per-session via `SingletonImageLoader.setUnsafe()` in `LoggedInAppScopeFlowNode`

To load artwork programmatically in a `LaunchedEffect`:
```kotlin
val context = LocalContext.current
// context.imageLoader is the Coil singleton, already configured with Matrix fetchers

val artworkBytes = artworkSource?.let { source ->
    tryOrNull {  // NOT runCatching — detekt bans it, use tryOrNull from libraries.core.data
        val request = ImageRequest.Builder(context)
            .data(MediaRequestData(source, MediaRequestData.Kind.Thumbnail(256, 256)))
            .build()
        val result = context.imageLoader.execute(request)
        result.image?.toBitmap()?.let { bitmap ->  // coil3.toBitmap() — NOT toBitmapImage()
            ByteArrayOutputStream().use { stream ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
                stream.toByteArray()
            }
        }
    }
}
```

Key: wrap in `tryOrNull` — if loading fails (network error, no thumbnail), just skip artwork. Do NOT use `runCatching` — detekt bans it. Import `io.element.android.libraries.core.data.tryOrNull`.

### Notification Tap Intent: Deep Link to Media Viewer

**Current implementation**: The notification builds a deep link with `?media=true` that navigates directly to the room timeline AND auto-opens the media viewer for the playing event. Back button returns to the timeline scrolled to that event.

**Deep link format**: `elementx://open/{sessionId}/{roomId}//{eventId}?media=true`
- The `//` is an empty threadId (already handled by parser)
- The `?media=true` query param signals "auto-open media viewer after navigating to room"

**Full navigation chain** (10 steps):

```
1. User taps notification
2. MediaPlaybackService builds PendingIntent with deep link URI from MediaMetadata.extras
3. MainActivity (singleTask) receives intent via onNewIntent
4. IntentResolver → DefaultDeeplinkParser parses URI
5. DeeplinkData.Room(sessionId, roomId, eventId, openMedia=true)
6. RootFlowNode.navigateTo() → uses RoomNavigationTarget.MediaViewer(eventId) instead of Root
7. JoinedRoomLoadedFlowNode → NavTarget.Messages(focusedEventId=eventId, openMediaForEventId=eventId)
8. MessagesFlowNode → MessagesNode.Inputs(focusedEventId, openMediaForEventId)
9. MessagesNode: focusedEventId triggers FocusOnEvent → timeline scrolls to event
10. MessagesNode: LaunchedEffect watches timelineItems, finds event → callback.handleEventClick → overlay.show(MediaViewer)
```

**Files modified (9 files across 4 layers)**:

| Layer | File | Change |
|-------|------|--------|
| Deep Link | `DeeplinkData.kt` | Added `openMedia: Boolean = false` to `Room` |
| Deep Link | `DefaultDeeplinkParser.kt` | Parse `?media=true` via `getBooleanQueryParameter` |
| Deep Link | `MediaPlaybackService.kt` | Build `elementx://open/...?media=true` URI from extras |
| Navigation | `RoomNavigationTarget.kt` | Added `MediaViewer(eventId)` variant |
| Navigation | `RootFlowNode.kt` | Route `openMedia` deep links to `MediaViewer` target |
| Navigation | `JoinedRoomLoadedFlowNode.kt` | Handle `MediaViewer` → `Messages(focusedEventId, openMediaForEventId)` |
| Messages | `MessagesEntryPoint.kt` | Added `openMediaForEventId` to `InitialTarget.Messages` |
| Messages | `DefaultMessagesEntryPoint.kt` + `MessagesFlowNode.kt` | Thread `openMediaForEventId` through |
| Messages | `MessagesNode.kt` | `LaunchedEffect` auto-opens media viewer when event appears in timeline |

**Auto-open mechanism in MessagesNode** (the key piece):

```kotlin
var openMediaForEventId by rememberSaveable { mutableStateOf(inputs.openMediaForEventId) }
LaunchedEffect(openMediaForEventId, state.timelineState.timelineItems) {
    val eventId = openMediaForEventId ?: return@LaunchedEffect
    val event = state.timelineState.timelineItems
        .filterIsInstance<TimelineItem.Event>()
        .firstOrNull { it.eventId == eventId }
    if (event != null) {
        val timelineMode = if (state.timelineState.isLive) {
            timelineController.mainTimelineMode()
        } else {
            timelineController.detachedTimelineMode() ?: return@LaunchedEffect
        }
        callback.handleEventClick(timelineMode, event)
        openMediaForEventId = null  // one-shot
    }
}
```

This watches `timelineItems` because the event may not be loaded yet when the composable first renders (timeline needs to scroll to it first via `FocusOnEvent`). Once the event appears, it triggers `handleEventClick` which calls `MessagesFlowNode.processEventClick()` → `overlay.show(MediaViewer)`. The `rememberSaveable` + null-after-use pattern ensures it's one-shot and survives configuration changes.

**Why deep link instead of `getLaunchIntentForPackage`**: The previous approach just brought the app to foreground, which only worked if the media viewer was still on the nav stack. If the user navigated away (e.g., went to another room), tapping the notification did nothing useful. The deep link approach always navigates to the correct room and opens the media viewer, regardless of current app state.

**DO NOT add `deeplink` module as a dependency** to `mediaviewer/impl` — the service constructs the URI string directly.

### Deep Link System Architecture

**Format**: `elementx://open/{sessionId}/{roomId}/{threadId}/{eventId}?media=true`
- Scheme: `elementx`, Host: `open` (defined in `Constants.kt`)
- Path segments are URL-encoded, parsed by `DefaultDeeplinkParser`
- Query params: `media=true` (new, for auto-opening media viewer)
- Trailing empty segments are stripped by `DefaultDeepLinkCreator`

**Key files**:
| File | Path | Purpose |
|------|------|---------|
| `DeeplinkData.kt` | `libraries/deeplink/api/` | Sealed interface: `Root(sessionId)`, `Room(sessionId, roomId, threadId?, eventId?, openMedia)` |
| `DeeplinkParser.kt` | `libraries/deeplink/api/` | Interface: `getFromIntent(Intent): DeeplinkData?` |
| `DefaultDeeplinkParser.kt` | `libraries/deeplink/impl/` | Parses `elementx://open/...` URIs from `ACTION_VIEW` intents |
| `DefaultDeepLinkCreator.kt` | `libraries/deeplink/impl/` | Builds `elementx://open/...` URI strings (used by push notifications) |
| `Constants.kt` | `libraries/deeplink/impl/` | `SCHEME = "elementx"`, `HOST = "open"` |
| `IntentResolver.kt` | `appnav/` | Routes intents to `ResolvedIntent.Navigation(deeplinkData)` |
| `RootFlowNode.kt` | `appnav/` | `navigateTo(deeplinkData)` → attaches session → attaches room |

**Navigation target hierarchy**:
```
RoomNavigationTarget (sealed interface, Parcelable)
├── Root(eventId?)                    → Messages timeline, optionally focused on event
├── MediaViewer(eventId)              → Messages timeline + auto-open media viewer (NEW)
├── Details                           → Room details screen
└── NotificationSettings              → Room notification settings
```

`RoomNavigationTarget` flows through: `RootFlowNode` → `LoggedInFlowNode.attachRoom()` → `RoomFlowNode` → `JoinedRoomLoadedFlowNode.Inputs.initialElement` → `initialElement()` function → `NavTarget.Messages`.

### Stopping the Service from UI

**DO NOT use `rememberMediaServicePlayer()` in `MediaViewerView`**. It causes `NullPointerException` in Robolectric tests because `SessionToken` → `MediaControllerImplBase$SessionServiceConnection.onServiceConnected()` gets a null `ComponentName` from Robolectric's shadow service binding.

**Instead**: Use `context.stopService(Intent(context, MediaPlaybackService::class.java))`. This directly stops the service, which triggers `onDestroy()` → player released → notification dismissed. Much simpler and test-safe.

`rememberMediaServicePlayer()` should ONLY be used in `MediaVideoView` and `MediaAudioView` (where it already works and isn't tested in isolation).

### Service Lifecycle Details

The service needs to stop itself in these cases:
1. **Playback ends naturally**: `onPlaybackStateChanged(Player.STATE_ENDED)` → `stopSelf()`
2. **Stop button / media items cleared**: `onPlaybackStateChanged(Player.STATE_IDLE)` + `player.mediaItemCount == 0` → `stopSelf()`
3. **App swiped from recents while paused**: `onTaskRemoved()` + `!player.playWhenReady` → `stopSelf()`
4. **Notification dismissed while paused**: Media3's `MediaSessionService` auto-handles this — service exits foreground when paused, notification becomes dismissible, swiping triggers `onDestroy()`

**While playing**: notification is NON-dismissible (foreground service requirement).

### MediaViewerTopBar Structure

Located in `MediaViewerView.kt` (private composable). Uses Material3 `TopAppBar`:
- `navigationIcon`: `BackButton(onClick = onBackClick)` — left side
- `title`: Column with `senderName` + `dateSent`
- `actions`: `OpenWith` button + `Info` button (if `canShowInfo`) — right side
- Background: `bgCanvasWithTransparency` (semi-transparent)
- Wrapped in `AnimatedVisibility` controlled by `showOverlay`

**To add stop button**: Put it in the `navigationIcon` slot using a `Row { BackButton(); if (showStopButton) { IconButton(...) } }`. Show it when `mimeType.isMimeTypeVideo() || mimeType.isMimeTypeAudio()`.

**Available icons**: `CompoundIcons.Close()` and `CompoundIcons.Stop()` both exist in `libraries/compound/.../CompoundIcons.kt`.

### MediaMetadata Leaks Into In-App Player UI (CRITICAL GOTCHA)

**Problem discovered in Task 2**: Setting `MediaMetadata` (title, artist, artworkData) on the `MediaItem` for the notification ALSO affects the in-app audio player UI. The `onMediaMetadataChanged` listener in `MediaAudioView` updates a `metadata` state variable, which is used by:

1. **`metadata.hasArtwork()`** — controls whether the `PlayerView` is visible (shows album art) and whether the default audio icon is shown. Located in `MediaMetadata.kt` (`libraries/mediaviewer/impl/.../local/audio/MediaMetadata.kt`). Returns `true` if `artworkData != null || artworkUri != null`.

2. **`metadata.buildInfo()`** — builds a "Artist - Title - Year" string shown in `AudioInfoView` below the player. Also in `MediaMetadata.kt`.

**What went wrong**: We set `setTitle(filename)`, `setArtist(senderName)`, and `setArtworkData(roomAvatar)` on the MediaMetadata for the notification. This caused:
- The audio player to show the room avatar as embedded album art (replacing the default audio icon)
- An extra "SenderName - Filename" subtitle to appear in the in-app player below the icon
- Visual flickering as metadata arrives asynchronously after the LaunchedEffect completes

**The fix**: In `onMediaMetadataChanged`, check if the metadata contains our custom extras key (`sessionId`). If yes, it's our notification metadata → skip updating the UI state. Only update `metadata` for genuine file-embedded metadata (ID3 tags, etc.):

```kotlin
override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
    // Only update UI metadata from file-embedded metadata (e.g. ID3 tags),
    // not from our notification metadata which has custom extras.
    if (mediaMetadata.extras?.containsKey("sessionId") != true) {
        metadata = mediaMetadata
    }
}
```

**General rule**: Any data set on `MediaMetadata` for the notification will be visible to ALL `Player.Listener.onMediaMetadataChanged()` callbacks — including those in the UI. Always filter by checking for custom extras keys to distinguish notification metadata from file-embedded metadata.

**This does NOT affect `MediaVideoView`** because the video player doesn't have a `metadata` state or `AudioInfoView`. The video `PlayerView` renders video frames directly.

### Detekt / KtLint Gotchas

1. **Import ordering**: KtLint requires lexicographic order, no empty lines between imports. Use `./gradlew ktlintFormat` to auto-fix.
2. **UnusedParameter**: Detekt flags unused function parameters. If you add a parameter to a public composable, make sure it's actually used or the entire chain will need updating.
3. **UnnecessaryParentheses**: Detekt doesn't like `(a && b)` inside `if (x || (a && b))`. Remove the inner parens even though they aid readability.
4. **Block comments before code on same line**: KtLint rejects `/* comment */ value` — use `// comment\nvalue` instead.
5. **`runCatching` is BANNED** (`RunCatchingNotAllowed` rule). Use `tryOrNull` from `io.element.android.libraries.core.data.tryOrNull` (returns nullable, rethrows `CancellationException`). Or use `runCatchingExceptions` from `io.element.android.libraries.core.extensions.runCatchingExceptions`. Never use `runCatching` or `mapCatching`.
6. **Coil 3 API**: Use `coil3.toBitmap()` to convert `Image` to `Bitmap`. NOT `toBitmapImage()` — that doesn't exist. Import: `import coil3.toBitmap`.
7. **Max line length = 160**: When adding indentation (e.g., wrapping in `CompositionLocalProvider`), existing comments may exceed the limit. Break them into multiple lines.

### Testing Constraints

1. **Robolectric + MediaController = NPE**: `rememberMediaServicePlayer()` crashes in Robolectric because the service isn't registered. Never call it in composables that are tested directly (like `MediaViewerView`). Only use it in `MediaVideoView`/`MediaAudioView` which aren't tested in `MediaViewerViewTest`.
2. **`aMediaViewerState()`**: Helper in `MediaViewerStateProvider.kt` — update it whenever `MediaViewerState` gets new fields.
3. **`FakeAudioFocus`**: Located in `libraries/mediaplayer/test/`. Was used in `DefaultMediaViewerEntryPointTest.kt` but removed when `audioFocus` was removed from the chain.
4. **String resources**: New strings go in temporary XML files (e.g., `strings_temporary.xml` in module's `res/values/`). Note in PR for Localazy integration.

### Module Dependencies (mediaviewer/impl)

Key dependencies in `build.gradle.kts`:
- `implementation(libs.androidx.media3.exoplayer)` — ExoPlayer
- `implementation(libs.androidx.media3.ui)` — PlayerView
- `implementation(libs.androidx.media3.session)` — MediaSession, MediaController, MediaSessionService
- `implementation(projects.libraries.audio.api)` — AudioFocus interface (still needed for MediaPlayerControllerView, even though we pass null)
- `testImplementation(projects.libraries.dateformatter.test)` — test utilities
- **Removed**: `testImplementation(projects.libraries.audio.test)` — no longer needed

The module does NOT depend on: `deeplink`, `push`, `mediaplayer` (voice messages), `voiceplayer`.

### Key File Locations

| File | Path | Purpose |
|------|------|---------|
| MediaPlaybackService | `libraries/mediaviewer/impl/.../local/player/MediaPlaybackService.kt` | Service owning ExoPlayer + MediaSession. Handles tap intent via `updateSessionActivity()`. |
| MediaPlaybackServiceConnection | `libraries/mediaviewer/impl/.../local/player/MediaPlaybackServiceConnection.kt` | `rememberMediaServicePlayer()` composable |
| MediaPlaybackContext | `libraries/mediaviewer/impl/.../local/player/MediaPlaybackContext.kt` | `data class` + `LocalMediaPlaybackContext` CompositionLocal for navigation context + artwork |
| ExoPlayerExtensions | `libraries/mediaviewer/impl/.../local/player/ExoPlayerExtensions.kt` | `Player.togglePlay()`, `Player.seekToEnsurePlaying()` |
| MediaPlayerControllerView | `libraries/mediaviewer/impl/.../local/player/MediaPlayerControllerView.kt` | Play/pause/seek/mute UI controls |
| MediaMetadata extensions | `libraries/mediaviewer/impl/.../local/audio/MediaMetadata.kt` | `hasArtwork()` and `buildInfo()` — check artworkData/artworkUri and build "Artist - Title" string |
| MediaVideoView | `libraries/mediaviewer/impl/.../local/video/MediaVideoView.kt` | Video player composable |
| MediaAudioView | `libraries/mediaviewer/impl/.../local/audio/MediaAudioView.kt` | Audio player composable |
| LocalMediaView | `libraries/mediaviewer/impl/.../local/LocalMediaView.kt` | Router — dispatches to video/audio/image/pdf |
| MediaViewerView | `libraries/mediaviewer/impl/.../viewer/MediaViewerView.kt` | Full viewer with pager, top bar, bottom bar |
| MediaViewerNode | `libraries/mediaviewer/impl/.../viewer/MediaViewerNode.kt` | Appyx node — DI, navigation, presenter |
| MediaViewerPresenter | `libraries/mediaviewer/impl/.../viewer/MediaViewerPresenter.kt` | MVI presenter — produces MediaViewerState |
| MediaViewerState | `libraries/mediaviewer/impl/.../viewer/MediaViewerState.kt` | State data class + MediaViewerPageData |
| MediaViewerEvents | `libraries/mediaviewer/impl/.../viewer/MediaViewerEvents.kt` | Sealed interface of UI events |
| MediaViewerStateProvider | `libraries/mediaviewer/impl/.../viewer/MediaViewerStateProvider.kt` | Preview data + `aMediaViewerState()` helper |
| DefaultAudioFocus | `libraries/audio/impl/.../DefaultAudioFocus.kt` | Wraps `AudioManager.requestAudioFocus()` |
| DefaultMediaPlayer | `libraries/mediaplayer/impl/.../DefaultMediaPlayer.kt` | Voice message ExoPlayer (separate pipeline) |
| MediaInfo | `libraries/mediaviewer/api/.../MediaInfo.kt` | Parcelable with filename, sender, mimeType, etc. |
| LocalMedia | `libraries/mediaviewer/api/.../local/LocalMedia.kt` | `data class LocalMedia(uri: Uri, info: MediaInfo)` |
| AndroidManifest | `libraries/mediaviewer/impl/src/main/AndroidManifest.xml` | Service declaration |

---

## Dev Environment Setup Notes

- **JDK**: `brew install openjdk@21`, set `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home`
- **Android SDK**: `brew install --cask android-commandlinetools`, then `sdkmanager --sdk_root=$HOME/Library/Android/sdk "platform-tools" "platforms;android-35" "build-tools;35.0.0"`
- **local.properties**: `sdk.dir=/Users/svetoslavmitov/Library/Android/sdk`
- **Fork remote**: `git remote add fork https://github.com/svetoslav-sportinno/element-x-android.git`
- **Collaborator**: `bxdxnn` has write access to the fork
- **CLA**: Must be signed at https://cla-assistant.io/element-hq/element-x-android before PR can be merged
