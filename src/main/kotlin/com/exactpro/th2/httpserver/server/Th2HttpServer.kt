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
 */

package com.exactpro.th2.httpserver.server

import com.exactpro.th2.common.event.Event
import com.exactpro.th2.httpserver.server.options.ServerOptions
import com.exactpro.th2.httpserver.server.responses.Th2Response
import mu.KotlinLogging
import rawhttp.core.*
import rawhttp.core.body.BodyReader
import rawhttp.core.errors.InvalidHttpRequest
import java.io.IOException
import java.lang.Exception
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import kotlin.reflect.KFunction2
import java.util.concurrent.TimeUnit
import java.util.logging.Logger


private val LOGGER = KotlinLogging.logger { }

internal class Th2HttpServer(
    private val eventStore: ((String, String, Throwable?) -> Event)?,
    private val options: ServerOptions
) : HttpServer {


    init {
        if (eventStore == null) {
            LOGGER.warn { "Event router is null, messages will not be send to event store!" }
        }
    }

    private var socket: ServerSocket = options.createSocket()
    private val executorService: ExecutorService = options.createExecutorService()
    private val http: RawHttp = options.getRawHttp()
    private var listen: Boolean = true
    private val dialogs = ConcurrentHashMap<String, Dialog>()


    override fun start() {
        Thread({
            while (listen) {
                try {
                    val client = socket.accept()
                    // thread waiting to accept socket before continue
                    executorService.submit { handle(client) }
                } catch (e: SocketException) {
                    serverError("Broken or closed socket!", "Failed to handle socket connection", e)
                    recreateSocket()
                } catch (e: IOException) {
                    serverError("Failed to accept client socket", "Failed to handle socket connection", e)
                    if (socket.isClosed) recreateSocket()
                }
            }
        }, "th2-conn-http-server").start()
    }

    private fun recreateSocket() {
        if (!listen) return
        options.runCatching { socket = createSocket() }.onFailure { e -> LOGGER.error(e) { "Can't recreate socket!" } }
    }

    private fun handle(client: Socket) {
        var request: RawHttpRequest
        var serverWillCloseConnection = false
        while (!serverWillCloseConnection) {
            try {
                request = http.parseRequest(
                    client.getInputStream(),
                    (client.remoteSocketAddress as InetSocketAddress).address
                )

                val requestEagerly = request.eagerly().apply { LOGGER.debug("Received request: \n$this\n") }
                val uuid = UUID.randomUUID().toString()
                options.onRequest(requestEagerly, uuid)

                val connectionOption = request.headers.getFirst("Connection")
                serverWillCloseConnection =
                    connectionOption.map { string: String? -> "close".equals(string, ignoreCase = true) }.orElse(false)
                if (!serverWillCloseConnection) serverWillCloseConnection =
                    !keepAlive(request.startLine.httpVersion, connectionOption)


                if (!serverWillCloseConnection) {
                    dialogs[uuid] = Dialog(requestEagerly, client)
                    LOGGER.debug("Stored dialog: $uuid")
                }

            } catch (e: Exception) {
                if (e !is SocketException && e is InvalidHttpRequest && e.lineNumber == 0) {
                    LOGGER.info(e) { "Client closed connection, socket isn't open" }
                } else {
                    serverError("Failed to handle request, broken socket", "Failed to handle request", e)
                }
                serverWillCloseConnection = true // cannot keep listening anymore
            } finally {
                if (serverWillCloseConnection) client.runCatching(Socket::close)
            }
        }
    }

    fun handleResponse(response: Th2Response) {
        LOGGER.debug { "Message processing for ${response.uuid} has been started " }
        try {
            val dialog = dialogs.remove(response.uuid)
            dialog?.let {
                options.prepareResponse(it.request, response).apply {
                    writeTo(it.socket.getOutputStream())
                    options.onResponse(it.request, response)
                }
                LOGGER.debug("Response: \n$response\nwas send to client")
            } ?: run {
                serverError(
                    "Failed to handle response, no matching client found. Response: /n$response",
                    "Failed to handle response, no matching client found",
                    null
                )
            }
        } catch (e: SocketException) {
            serverError(
                "Failed to handle response, socket is broken. Response: /n$response",
                "Failed to handle response, socket is broken",
                e
            )
        } finally {
            closeBodyOf(response)
        }
    }

    override fun stop() {
        LOGGER.debug("\nThe Server is shutting down\n")
        listen = false
        try {
            socket.close()
        } catch (e: IOException) {
            LOGGER.warn(e) { "Failed to close socket" }
        } finally {
            executorService.shutdown()
            if (!executorService.awaitTermination(30, TimeUnit.SECONDS)) {
                LOGGER.warn { "Executor didn't turn off on specified time" }
                executorService.shutdownNow()
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
                && connectionOption.map { option: String? -> "keep-alive".equals(option, ignoreCase = true) }
            .orElse(false)
    }

    private fun serverError(log: String, event: String, throwable: Throwable?) {
        if (!listen) {
            LOGGER.warn(throwable) { "Closed server: $log" }
        } else {
            eventStore?.invoke(event, "Error", throwable)
            LOGGER.error(throwable) { log }
        }
    }

    data class Dialog(val request: RawHttpRequest, val socket: Socket)

}
