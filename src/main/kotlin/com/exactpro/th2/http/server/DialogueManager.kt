/*
 * Copyright 2020-2022 Exactpro (Exactpro Systems Limited)
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.exactpro.th2.http.server

import java.io.Closeable
import java.net.Socket
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.ConcurrentHashMap
import mu.KotlinLogging
import rawhttp.core.RawHttpRequest

private val LOGGER = KotlinLogging.logger { }

/**
 * Trying to check socket status and removing it if closed
 */
class DialogueManager(socketDelayCheck: Long) : Closeable {

    private val dialogues = ConcurrentHashMap<String, Dialogue>()
    private val cleaner = Timer()

    val size
        get() = dialogues.size

    private val checkSockets = object : TimerTask() {
        override fun run() = dialogues.forEach { (key, value) ->
            value.socket.runCatching {
                if (isClosed && !isOutputShutdown) {
                    removeSocket(key)
                }
            }
        }
    }

    init {
        cleaner.schedule(checkSockets, socketDelayCheck * 1000, socketDelayCheck * 1000)
    }

    fun removeSocket(key: String) = dialogues.remove(key).apply { LOGGER.debug("Inactive socket [$key] was removed from store") }

    fun createDialogue(uuid: String, request: RawHttpRequest, socket: Socket, eventID: String): Dialogue = Dialogue(request, socket, eventID).also {
        this.dialogues[uuid] = it
        LOGGER.trace { "Dialogue was created and stored: $uuid" }
    }

    fun removeDialogue(uuid: String) = this.dialogues.remove(uuid)

    override fun close() {
        cleaner.cancel()
        dialogues.values.forEach { it.socket.runCatching(Socket::close) }
    }

}

data class Dialogue(val request: RawHttpRequest, val socket: Socket, val eventID: String)