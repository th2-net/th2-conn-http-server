/*
 * Copyright 2020-2021 Exactpro (Exactpro Systems Limited)
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
class DialogueManager(private val socketDelayCheck: Long) : Closeable {

    val dialogues = ConcurrentHashMap<String, Dialogue>()
    private val cleaner = Timer()

    private val checkSockets = object : TimerTask() {
        override fun run() = dialogues.forEach { (key, value) ->
            value.socket.runCatching {
                if (isClosed) {
                    removeSocket(key)
                }
            }.onFailure {
                removeSocket(key)
            }
        }
    }

    fun removeSocket(key: String) = dialogues.remove(key).apply { LOGGER.debug("Inactive socket [$key] was removed from store") }

    fun startCleaner() = cleaner.schedule(checkSockets, socketDelayCheck * 1000, socketDelayCheck * 1000)

    override fun close() {
        cleaner.cancel()
        dialogues.values.forEach { it.socket.runCatching(Socket::close) }
    }

}

data class Dialogue(val request: RawHttpRequest, val socket: Socket, val eventID: String)