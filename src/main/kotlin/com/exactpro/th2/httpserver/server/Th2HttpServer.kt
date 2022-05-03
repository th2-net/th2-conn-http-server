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

package com.exactpro.th2.httpserver.server

import com.exactpro.th2.httpserver.server.options.ServerOptions
import com.exactpro.th2.httpserver.server.responses.Th2Response
import com.google.protobuf.TextFormat
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

class Th2HttpServer(private val eventStore: (name: String, eventId: String?, throwable: Throwable?) -> String, private val options: ServerOptions, private val terminationTime: Long, socketDelayCheck: Long) : HttpServer {

    @Volatile
    private var listen: Boolean = true
    private val dialogManager: DialogueManager = DialogueManager(socketDelayCheck)

    private var socket: ServerSocket = options.createSocket()
    private val executorService: ExecutorService = options.createExecutorService()
    private val additionalExecutors: ExecutorService = options.createExecutorService()
    private val http: RawHttp = options.getRawHttp()

    override fun start() {
        Thread({
            LOGGER.trace { "Server started" }
            while (listen) {
                runCatching {
                    val client = socket.accept()
                    val eventId = options.onConnect(client)
                    // thread waiting to accept socket before continue
                    executorService.submit {
                        runCatching {
                            handle(client, eventId)
                        }.onFailure {
                            onError("Failed to handle client socket", throwable = it)
                        }
                    }
                }.onFailure { exception ->
                    if (listen) {
                        when (exception) {
                            is SocketException -> {
                                onError("Broken or closed server socket!", throwable = exception)
                                recreateSocket()
                            }
                            else -> {
                                onError("Failed to accept client socket", throwable = exception)
                                if (socket.isClosed) recreateSocket()
                            }
                        }
                    }
                }
            }
        }, "th2-conn-http-server").start()
        dialogManager.startCleaner()
    }

    private fun recreateSocket() {
        if (!listen) return
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
            onError("Cannot handle socket [closed]: $socket", parentEventId, IllegalStateException())
            return
        }

        val request: RawHttpRequest = try {
            http.parseRequest(client.getInputStream(), (client.remoteSocketAddress as InetSocketAddress).address)
                .eagerly()
        } catch (e: Exception) {
            when (e) {
                is SocketException -> onError("Socket closed: $socket", parentEventId, e)
                else -> onError("Failed to handle request. Socket keep-alive: ${client.keepAlive}", parentEventId, e)
            }
            client.runCatching(Socket::close)
            return
        }

        val uuid = UUID.randomUUID().toString()

        LOGGER.debug { "Request from socket: $client was received: \ngenerated uuid: $uuid \n$request" }

        additionalExecutors.submit {
            options.runCatching {
                onRequest(request, uuid, parentEventId)
            }.onFailure {
                LOGGER.error(it) { "Cannot execute options.onRequest hook" }
            }
        }

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
    }

    fun handleResponse(response: RawHttpResponse<Th2Response>) {
        val th2Response: Th2Response = response.runCatching { libResponse.get() }.onFailure {
            onError("Can't handle response without th2 information", throwable = it)
        }.getOrThrow()
        val uuid = th2Response.uuid

        runCatching {
            dialogManager.dialogues.remove(uuid)?.let {
                val finalResponse = options.prepareResponse(it.request, response)
                finalResponse.writeTo(it.socket.getOutputStream())

                options.onResponse(finalResponse)

                if (!it.socket.keepAlive) {
                    LOGGER.debug { "Closing socket (${it.socket.inetAddress}) from UUID: $uuid due last response." }
                    it.socket.close()
                } else {
                    executorService.submit { handle(it.socket, it.eventID) }
                }
            } ?: throw NullPointerException("No dialogue were found by uuid: $uuid in messages: ${th2Response.messagesId.joinToString(", ") { TextFormat.shortDebugString(it) }}")
        }.onFailure {
            when (it) {
                is SocketException -> onError("Failed to handle response uuid: $uuid, socket is broken. $socket", th2Response.eventId.id, it)
                else -> onError("Can't handle response uuid: $uuid", th2Response.eventId.id, it)
            }
        }
        closeBodyOf(response)
    }

    override fun stop() {
        LOGGER.debug("Server is shutting down")
        listen = false
        dialogManager.close()
        try {
            socket.close()
        } catch (e: IOException) {
            LOGGER.warn(e) { "Failed to close Server socket" }
        } finally {
            executorService.awaitShutdown(terminationTime) { LOGGER.warn { "Sockets Executor service didn't turn off on specified time" } }
            additionalExecutors.awaitShutdown(0L) { LOGGER.warn { "Additional Executor service didn't turn off on specified time" } }
        }
    }

    private fun closeBodyOf(response: RawHttpResponse<*>) {
        response.body.ifPresent { b: BodyReader ->
            try {
                b.close()
            } catch (e: IOException) {
                LOGGER.warn(e) { "Body of message may be already closed" }
            }
        }
    }

    private fun onError(name: String, eventId: String? = null, throwable: Throwable): String {
        if (!listen) {
            LOGGER.warn(throwable) { "$eventId: $name" }
        } else {
            LOGGER.error(throwable) { "$eventId: $name" }
        }
        return eventStore(name, eventId, throwable)
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


