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
import java.util.Optional
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import kotlin.Exception
import kotlin.NoSuchElementException

private val LOGGER = KotlinLogging.logger { }

internal class Th2HttpServer(
    private val eventStore: ((String, String, Throwable?) -> Event),
    private val options: ServerOptions,
    private val terminationTime: Long,
    socketDelayCheck: Long
) : HttpServer {

    @Volatile
    private var listen: Boolean = true
    private val dialogManager: DialogueManager = DialogueManager(socketDelayCheck)

    private var socket: ServerSocket = options.createSocket()
    private val executorService: ExecutorService = options.createExecutorService()
    private val additionalExecutors: ExecutorService = options.createExecutorService()
    private val http: RawHttp = options.getRawHttp()



    override fun start() {
        Thread({
            while (listen) {
                try {
                    val client = socket.accept()

                    // thread waiting to accept socket before continue
                    executorService.submit { handle(client) }
                } catch (e: SocketException) {
                    serverError("Broken or closed socket!", e)
                    recreateSocket()
                } catch (e: IOException) {
                    serverError("Failed to accept client socket", e)
                    if (socket.isClosed) recreateSocket()
                }
            }
        }, "th2-conn-http-server").start()
        dialogManager.startCleaner()
    }

    private fun recreateSocket() {
        if (!listen) return
        options.runCatching { socket = createSocket() }.onFailure { e -> LOGGER.error(e) { "Can't recreate socket!" } }
    }

    private fun handle(client: Socket) {
        var request: RawHttpRequest
        var isClosing = false
        while (!isClosing) {
            try {
                request = http.parseRequest(
                    client.getInputStream(),
                    (client.remoteSocketAddress as InetSocketAddress).address
                )
                val requestEagerly = request.eagerly().apply { LOGGER.debug("Received request: \n$this\n") }

                val uuid = UUID.randomUUID().toString()
                additionalExecutors.submit { options.onRequest(requestEagerly, uuid) }

                // Check if we need to close connection.
                // Points of closing:
                // 1) Close in connection property of request
                // 2) Keep alive property is not present
                request.headers.getFirst("Connection").let {
                    isClosing = it.map { string: String? -> "close".equals(string, ignoreCase = true) }.orElse(false)
                    isClosing = isClosing || !keepAlive(request.startLine.httpVersion, it)
                }

                if (!isClosing) {
                    dialogManager.dialogues[uuid] = Dialogue(requestEagerly, client)
                    LOGGER.debug("Connection is persist. Stored dialog: $uuid")
                }
            } catch (e: Exception) {
                when(e) {
                    is SocketException -> serverWarn("Socket closed", e)
                    else -> serverError("Failed to handle request.", e)
                }
                isClosing = true // cannot keep listening anymore
            } finally {
                if (isClosing) client.runCatching(Socket::close)
            }
        }
    }

    fun handleResponse(response: RawHttpResponse<Th2Response>) {
        try {
            val uuid: String = response.libResponse.get().uuid
            LOGGER.debug { "Message processing for $uuid has been started " }
            dialogManager.dialogues[uuid]?.let {
                options.prepareResponse(it.request, response).writeTo(it.socket.getOutputStream())
                LOGGER.debug("Response: \n$response\nwas send to client")
            } ?: run {
                serverError(
                    "Failed to handle response, no matching client found. Response: /n$response",
                    null
                )
            }
        } catch (e: SocketException) {
            serverError(
                "Failed to handle response, socket is broken. Response: /n$response",
                e
            )
        } catch (e: NoSuchElementException) {
            serverError(
                "Response is broken, please check api realization. Need to provide Th2Response object inside response",
                e
            )
        } finally {
            closeBodyOf(response)
        }
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
            executorService.shutdown()
            additionalExecutors.shutdown()
            if (!executorService.awaitTermination(terminationTime, TimeUnit.SECONDS)) {
                LOGGER.warn { "Socket Executors didn't turn off on specified time" }
                executorService.shutdownNow()
            }
            if (!additionalExecutors.isTerminated) {
                LOGGER.warn { "Additional Executors didn't turn off on specified time" }
                additionalExecutors.shutdown()
            }
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

    private fun keepAlive(httpVersion: HttpVersion, connectionOption: Optional<String>): Boolean {
        // https://tools.ietf.org/html/rfc7230#section-6.3
        // If the received protocol is HTTP/1.1 (or later)
        // OR
        // If the received protocol is HTTP/1.0, the "keep-alive" connection
        // option is present
        // THEN the connection will persist
        // OTHERWISE close the connection
        return !httpVersion.isOlderThan(HttpVersion.HTTP_1_1) || httpVersion == HttpVersion.HTTP_1_0
                && connectionOption.map { option: String? -> "keep-alive".equals(option, ignoreCase = true) }.orElse(false)
    }

    private fun serverWarn(msg: String, throwable: Throwable?) {
        eventStore("WARN: $msg", "Passed", throwable)
        LOGGER.warn(throwable) { msg }
    }

    private fun serverError(msg: String, throwable: Throwable?) {
        if (!listen) {
            LOGGER.warn(throwable) { "Closed server: $msg" }
        } else {
            eventStore(msg, "Error", throwable)
            LOGGER.error(throwable) { msg }
        }
    }

}
