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

import com.exactpro.th2.common.event.Event
import com.exactpro.th2.common.grpc.EventID
import com.exactpro.th2.httpserver.server.options.ServerOptions
import com.exactpro.th2.httpserver.server.responses.Th2Response
import mu.KotlinLogging
import rawhttp.core.HttpVersion
import rawhttp.core.RawHttp
import rawhttp.core.RawHttpRequest
import rawhttp.core.RawHttpResponse
import rawhttp.core.body.BodyReader
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

private val LOGGER = KotlinLogging.logger { }

internal class Th2HttpServer(
    private val eventStore: ((String, String, eventId: EventID?, Throwable?) -> Event),
    private val options: ServerOptions,
    private val terminationTime: Long,
    socketDelayCheck: Long
) : HttpServer {

    @Volatile private var listen: Boolean = true
    private val dialogManager: DialogueManager = DialogueManager(socketDelayCheck)

    private var socket: ServerSocket = options.createSocket()
    private val executorService: ExecutorService = options.createExecutorService()
    private val additionalExecutors: ExecutorService = options.createExecutorService()
    private val http: RawHttp = options.getRawHttp()



    override fun start() {
        Thread({
            while (listen) {
                runCatching {
                    val client = socket.accept()

                    // thread waiting to accept socket before continue
                    executorService.submit { handle(client) }
                }.onFailure {
                    if (listen) {
                        when (it) {
                            is SocketException -> {
                                onError("Broken or closed socket!", null, it)
                                recreateSocket()
                            }
                            else -> {
                                onError("Failed to accept client socket", null, it)
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
    private fun handle(client: Socket) {
        var request: RawHttpRequest
        var isClosing = false
        while (!isClosing) {
            runCatching {
                request = http.parseRequest(
                    client.getInputStream(),
                    (client.remoteSocketAddress as InetSocketAddress).address
                )
                val uuid = UUID.randomUUID().toString()
                val requestEagerly = request.eagerly().apply { LOGGER.debug("Received request: \n$this\nGenerated UUID: $uuid") }


                additionalExecutors.submit { options.onRequest(requestEagerly, uuid) }

                when(request.startLine.httpVersion) {
                    HttpVersion.HTTP_1_1 -> {
                        request.headers.getFirst("Connection").let {
                            isClosing = it.isPresent && it.get().equals("close", true)
                        }
                    }
                    else -> {
                        isClosing=true
                        request.headers.getFirst("Connection").ifPresent {
                            isClosing = !it.equals("keep-alive", true)
                        }
                    }
                }

                client.keepAlive = !isClosing
                dialogManager.dialogues[uuid] = Dialogue(requestEagerly, client)
                LOGGER.debug("Connection is persist: ${!isClosing}. Stored dialog: $uuid")
            }.onFailure {
                isClosing = true
                when(it) {
                    is SocketException -> onError("Socket closed", null, it)
                    else -> onError("Failed to handle request. Socket is closed: ${!client.isConnected && client.isClosed}", null, it)
                }
                client.runCatching(Socket::close)
            }
        }
    }

    fun handleResponse(response: RawHttpResponse<Th2Response>) {
        val th2Response: Th2Response = response.runCatching { libResponse.get() }.onFailure {
            onError("Can't handle response without th2 information", null, it)
        }.getOrThrow()
        val uuid = th2Response.uuid

        runCatching {
            dialogManager.dialogues.remove(uuid)?.let {
                val finalResponse = options.prepareResponse(it.request, response)
                finalResponse.writeTo(it.socket.getOutputStream())
                onInfo("Response with UUID: $uuid was sent to client\n$finalResponse\n", th2Response.eventId)
                if (!it.socket.keepAlive) {
                    LOGGER.debug { "Response with UUID: $uuid was sent. Closing socket due last response." }
                    it.socket.close()
                }
            } ?: run {
                throw NullPointerException("No matching client in store. $uuid")
            }
        }.onFailure {
            when (it) {
                is SocketException -> onError("Failed to handle response with UUID: $uuid, socket is broken. Response: /n$response", th2Response.eventId, it)
                else -> onError("Can't handle response with UUID: $uuid. Response: /n$response", th2Response.eventId, it)
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
            executorService.awaitShutdown(terminationTime) { LOGGER.warn {"Sockets Executor service didn't turn off on specified time"} }
            additionalExecutors.awaitShutdown(0L) { LOGGER.warn {"Additional Executor service didn't turn off on specified time"} }
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

    private fun onInfo(message: String, eventId: EventID?) {
        LOGGER.info(message)
        eventStore(message, "Passed", eventId, null)
    }

    private fun onError(msg: String, eventId: EventID?, throwable: Throwable?) {
        eventStore(msg, "Error", eventId, throwable)
        if (!listen) {
            LOGGER.warn(throwable) { msg }
        } else {
            LOGGER.error(throwable) { msg }
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

}


