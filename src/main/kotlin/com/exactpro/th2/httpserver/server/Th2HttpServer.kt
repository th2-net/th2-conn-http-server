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
import com.exactpro.th2.common.event.EventUtils
import com.exactpro.th2.common.grpc.EventBatch
import com.exactpro.th2.common.schema.message.MessageRouter
import com.exactpro.th2.common.schema.message.QueueAttribute
import com.exactpro.th2.httpserver.server.options.ServerOptions
import com.exactpro.th2.httpserver.server.responses.Th2Response
import mu.KotlinLogging
import rawhttp.core.*
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
    private val eventStore: (name: String, message: HttpMessage?, eventId: String?, uuid: String?, throwable: Throwable?)->Unit,
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
                    onInfo("Connected client: ${client.inetAddress}")
                    // thread waiting to accept socket before continue
                    executorService.submit { handle(client) }
                }.onFailure {
                    if (listen) {
                        when (it) {
                            is SocketException -> {
                                onError("Broken or closed socket!", throwable = it)
                                recreateSocket()
                            }
                            else -> {
                                onError("Failed to accept client socket", throwable= it)
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
                onInfo("Stored dialog from client: ${client.inetAddress}", message = requestEagerly, uuid = uuid)
            }.onFailure {
                isClosing = true
                when(it) {
                    is SocketException -> onError("Socket closed", throwable =  it)
                    else -> onError("Failed to handle request. Socket keep-alive: ${client.keepAlive}", throwable =  it)
                }
                client.runCatching(Socket::close)
            }
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
                onInfo("Response was sent to client", response, th2Response.eventId.id, uuid)
                if (!it.socket.keepAlive) {
                    LOGGER.debug { "Closing socket (${it.socket.inetAddress}) from UUID: $uuid due last response." }
                    it.socket.close()
                }
            } ?: run {
                throw NullPointerException("No matching client in store.")
            }
        }.onFailure {
            when (it) {
                is SocketException -> onError("Failed to handle response, socket is broken.", response, th2Response.eventId.id, uuid, it)
                else -> onError("Can't handle response", response, th2Response.eventId.id, uuid, it)
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

    private fun onInfo(name: String, message: HttpMessage? = null, eventId: String? = null, uuid: String? = null) {
        val info = "${uuid.orEmpty()} $name"
        eventStore(info, message, eventId, uuid, null)
        LOGGER.info(info)
        message?.toString().run(LOGGER::debug)
    }

    private fun onError(name: String, message: HttpMessage? = null, eventId: String? = null, uuid: String? = null, throwable: Throwable) {
        val info = "${uuid.orEmpty()} $name"
        eventStore(info, message, eventId, uuid, throwable)
        if (!listen) {
            LOGGER.warn(throwable) { info }
        } else {
            LOGGER.error(throwable) { info }
        }
        message?.toString().run(LOGGER::debug)
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


