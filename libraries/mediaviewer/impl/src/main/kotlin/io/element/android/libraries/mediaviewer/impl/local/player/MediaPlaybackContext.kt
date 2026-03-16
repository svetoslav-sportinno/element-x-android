/*
 * Copyright (c) 2025 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.mediaviewer.impl.local.player

import androidx.compose.runtime.compositionLocalOf

data class MediaPlaybackContext(
    val sessionId: String = "",
    val roomId: String = "",
    val eventId: String = "",
)

val LocalMediaPlaybackContext = compositionLocalOf { MediaPlaybackContext() }
