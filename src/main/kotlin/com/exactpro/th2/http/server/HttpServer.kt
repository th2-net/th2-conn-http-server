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

import com.exactpro.th2.http.server.options.ServerOptions
import com.exactpro.th2.http.server.util.LinkedData
import mu.KotlinLogging
import rawhttp.core.HttpVersion
import rawhttp.core.RawHttp
import rawhttp.core.RawHttpRequest
import rawhttp.core.RawHttpResponse
import rawhttp.core.body.BodyReader
import java.io.IOException
import java.lang.IllegalStateException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class HttpServer(
    private val options: ServerOptions,
    private val terminationTime: Long,
    socketDelayCheck: Long
) : RawHttpServer {

    @Volatile
    private var active: AtomicBoolean = AtomicBoolean(false)
    private val dialogManager: DialogueManager = DialogueManager(socketDelayCheck)

    private var socket: ServerSocket = options.createSocket()
    private val executorService: ExecutorService = options.createExecutorService()
    private val additionalExecutors: ExecutorService = options.createExecutorService()
    private val http: RawHttp = options.getRawHttp()

    override fun start() {
        if (active.get()) {
            LOGGER.warn { "Server already started" }
            return
        }
        active.set(true)

        Thread({
            LOGGER.info { "Server started" }
            listen()
        }, "th2-conn-http-server").start()

        dialogManager.startCleaner()
    }

    private fun listen() {
        while (active.get()) {
            socket.runCatching(ServerSocket::accept)
                .onFailure {
                    if (active.get()) {
                        when (it) {
                            is SocketException -> {
                                options.onError("Broken or closed server socket!", exception = it)
                                recreateSocket()
                            }
                            else -> {
                                options.onError("Failed to accept client socket", exception = it)
                                if (socket.isClosed) recreateSocket()
                            }
                        }
                    } else {
                        LOGGER.warn(it) { "Server was stopped that leaded to errors: " }
                    }
                }.onSuccess { client ->
                    executorService.submit {
                        val eventId = options.onConnect(client)
                        runCatching {
                            handle(client, eventId)
                        }.onFailure {
                            options.onError("Failed to handle client socket", exception = it)
                        }
                    }
                }
        }
        LOGGER.debug { "Socket listener was stopped" }
    }

    private fun recreateSocket() {
        if (!active.get()) {
            LOGGER.debug { "Socket wasn't recreated due non active server" }
            return
        }
        options.runCatching { socket = createSocket() }.onFailure { e -> LOGGER.error(e) { "Can't recreate socket!" } }
    }

    /**
     * All rules of closing and keep alive mechanisms have been found in specs
     * https://datatracker.ietf.org/doc/html/rfc7230#section-6.3
     * https://www.w3.org/Protocols/HTTP/1.1/draft-ietf-http-v11-spec-01#Connection
     * Please use it as reference in discussions and logic reworks
     */
    private fun handle(client: Socket, parentEventId: String) {
        if (!client.isConnected || client.isClosed || client.isInputShutdown) {
            options.onError("Cannot handle socket [closed]: $socket", parentEventId, IllegalStateException())
            return
        }

        val request: RawHttpRequest = try {
            http.parseRequest(client.getInputStream(), (client.remoteSocketAddress as InetSocketAddress).address)
                .eagerly()
        } catch (e: SocketException) {
            options.onError("Socket exception: $socket", parentEventId, e)
            return
        } catch (e: Exception) {
            options.onError("Failed to handle request. Socket keep-alive: ${client.keepAlive}", parentEventId, e)
            client.runCatching(Socket::close)
            return
        }

        val uuid = UUID.randomUUID().toString()

        LOGGER.debug { "Request from socket: $client was received: \ngenerated uuid: $uuid \n$request" }

        var keepAlive = false
        when {
            request.startLine.httpVersion.isOlderThan(HttpVersion.HTTP_1_1) -> {
                request.headers.getFirst("Connection").ifPresent {
                    keepAlive = it.equals("keep-alive", true)
                }
            }
            else -> {
                request.headers.getFirst("Connection").let {
                    keepAlive = !(it.isPresent && it.get().equals("close", true))
                }
            }
        }

        client.keepAlive = keepAlive
        Dialogue(request, client, parentEventId).also {
            dialogManager.dialogues[uuid] = it
            LOGGER.trace { "Dialogue was created and stored: $uuid" }
        }

        additionalExecutors.submit {
            options.runCatching {
                onRequest(request, uuid, parentEventId)
            }.onFailure {
                LOGGER.error(it) { "Cannot execute options.onRequest hook" }
            }
        }
    }

    override fun handleResponse(response: RawHttpResponse<LinkedData>) {
        try {
            val linkedData: LinkedData = response.runCatching { libResponse.get() }.onFailure {
                options.onError("Can't handle response without linked uuid information", exception = it)
            }.getOrThrow()
            val uuid = linkedData.uuid

            dialogManager.dialogues.remove(uuid)?.let { dialogue ->
                try {
                    val finalResponse = options.prepareResponse(dialogue.request, response).also { it.writeTo(dialogue.socket.getOutputStream()) }

                    when {
                        dialogue.socket.keepAlive && !finalResponse.headers.getFirst("Connection").map { it.equals("close", true) }.orElse(false) -> {
                            executorService.submit { handle(dialogue.socket, dialogue.eventID) }
                        }
                        else -> {
                            LOGGER.debug { "Closing socket (${dialogue.socket.inetAddress}) from UUID: $uuid due last response." }
                            dialogue.socket.runCatching(Socket::close)
                        }
                    }

                    options.onResponse(finalResponse)
                } catch (e: SocketException) {
                    options.onError("Failed to handle response, socket is broken. $socket", dialogue.eventID, e)
                } catch (e: Exception) {
                    options.onError("Cannot handle response due exception", dialogue.eventID, e)
                }
            } ?: throw IllegalArgumentException("No dialogue were found by uuid: $uuid")
        } finally {
            response.body.ifPresent { it.runCatching(BodyReader::close) }
        }
    }

    override fun stop() {
        LOGGER.debug("Server is shutting down")
        active.set(false)
        dialogManager.close()
        try {
            socket.close()
        } catch (e: IOException) {
            LOGGER.warn(e) { "Failed to close Server socket" }
        } finally {
            executorService.awaitShutdown(terminationTime) { LOGGER.warn { "Sockets Executor service didn't turn off on specified time" } }
            additionalExecutors.awaitShutdown(0L) { LOGGER.warn { "Additional Executor service didn't turn off on specified time" } }
            LOGGER.debug { "Server stopped" }
        }
    }

    private fun ExecutorService.awaitShutdown(terminationTime: Long, onTimeout: () -> Unit) {
        shutdown()
        if (!isTerminated) {
            if (terminationTime > 0 && !awaitTermination(terminationTime, TimeUnit.SECONDS)) {
                shutdownNow()
            } else {
                shutdownNow()
            }
            onTimeout()
        }

    }

    companion object {
        private val LOGGER = KotlinLogging.logger { }
    }

}


