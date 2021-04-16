package com.exactpro.th2.httpserver.server

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
        override fun run() {
            dialogues.forEach { (key, value) ->
                value.socket.runCatching {
                    if(isClosed) {
                        dialogues.remove(key).apply { LOGGER.debug("inactive socket [$key] was removed from store") }
                    }
                }.onFailure {
                    dialogues.remove(key).apply { LOGGER.debug("inactive socket [$key] was removed from store") }
                }
            }
        }
    }

    fun startCleaner() {
        cleaner.schedule(checkSockets, socketDelayCheck*1000, socketDelayCheck*1000)
    }

    override fun close() {
        cleaner.cancel()
        dialogues.values.forEach { value ->
            value.socket.runCatching {
                close()
            }
        }
    }

}

data class Dialogue(val request: RawHttpRequest, val socket: Socket)