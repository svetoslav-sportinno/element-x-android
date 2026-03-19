/*
 * Copyright (c) 2025 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.mediaviewer.impl.local.player

import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player

class SkipEnabledForwardingPlayer(
    player: Player,
    private val onSkipToNext: () -> Unit,
    private val onSkipToPrevious: () -> Unit,
) : ForwardingPlayer(player) {
    var canSkipNext: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                listeners.forEach { it.onAvailableCommandsChanged(availableCommands) }
            }
        }

    var canSkipPrev: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                listeners.forEach { it.onAvailableCommandsChanged(availableCommands) }
            }
        }

    private val listeners = mutableListOf<Player.Listener>()

    override fun addListener(listener: Player.Listener) {
        listeners.add(listener)
        super.addListener(listener)
    }

    override fun removeListener(listener: Player.Listener) {
        listeners.remove(listener)
        super.removeListener(listener)
    }

    override fun getAvailableCommands(): Player.Commands {
        val commands = super.getAvailableCommands()
        val builder = commands.buildUpon()
        if (canSkipNext) {
            builder.add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
            builder.add(Player.COMMAND_SEEK_TO_NEXT)
        } else {
            builder.remove(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
            builder.remove(Player.COMMAND_SEEK_TO_NEXT)
        }
        if (canSkipPrev) {
            builder.add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
            builder.add(Player.COMMAND_SEEK_TO_PREVIOUS)
        } else {
            builder.remove(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
            builder.remove(Player.COMMAND_SEEK_TO_PREVIOUS)
        }
        return builder.build()
    }

    override fun isCommandAvailable(command: Int): Boolean {
        return when (command) {
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, Player.COMMAND_SEEK_TO_NEXT -> canSkipNext
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM, Player.COMMAND_SEEK_TO_PREVIOUS -> canSkipPrev
            else -> super.isCommandAvailable(command)
        }
    }

    override fun hasNextMediaItem(): Boolean = canSkipNext

    override fun hasPreviousMediaItem(): Boolean = canSkipPrev

    override fun seekToNext() {
        onSkipToNext()
    }

    override fun seekToNextMediaItem() {
        onSkipToNext()
    }

    override fun seekToPrevious() {
        onSkipToPrevious()
    }

    override fun seekToPreviousMediaItem() {
        onSkipToPrevious()
    }
}
