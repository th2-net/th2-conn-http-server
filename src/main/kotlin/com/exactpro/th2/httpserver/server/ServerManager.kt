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

import com.exactpro.th2.httpserver.api.IResponseManager
import com.exactpro.th2.httpserver.server.options.ServerOptions
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
import java.util.concurrent.ExecutorService


internal class ServerManager (private val responseManager: IResponseManager, private val options: ServerOptions) {
    private val socket: ServerSocket = options.getServerSocket()
    private val executorService: ExecutorService = options.createExecutorService()
    private val http: RawHttp = options.getRawHttp()

    private fun start() {
        Thread({
            var failedAccepts = 0
            while (true) {
                try {
                    val client = socket.accept()
                    executorService.submit { handle(client) }
                    failedAccepts = 0
                } catch (e: SocketException) {
                    break // server socket was closed or got broken
                } catch (e: IOException) {
                    failedAccepts++
                    e.printStackTrace()
                    if (failedAccepts > 10) {
                        break // give up, too many accept failures
                    }
                }
            }
        }, "th2-http-server").start()
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

                val requestEagerly = request.eagerly()
                options.onRequest(requestEagerly)

                val connectionOption = request.headers.getFirst("Connection")

                serverWillCloseConnection = connectionOption.map { string: String? -> "close".equals(string, ignoreCase = true) }.orElse(false)

                if (!serverWillCloseConnection) {
                    serverWillCloseConnection = !keepAlive(request.startLine.httpVersion, connectionOption)
                }

                responseManager.handleRequest(requestEagerly) { res: RawHttpResponse<*> ->
                    val response = options.prepareResponse(requestEagerly, res)
                    response.writeTo(client.getOutputStream())
                    options.onResponse(requestEagerly, response)
                    closeBodyOf(response)
                }
            } catch (e: Exception) {
                if (e !is SocketException) {
                    // only print stack trace if this is not due to a client closing the connection
                    val clientClosedConnection = e is InvalidHttpRequest &&
                            e.lineNumber == 0
                    if (!clientClosedConnection) {
                        e.printStackTrace()
                    }
                    try {
                        client.close()
                    } catch (ignore: IOException) {
                        // we wanted to forget the client, so this is fine
                    }
                }
                serverWillCloseConnection = true // cannot keep listening anymore
            } finally {
                if (serverWillCloseConnection) {
                    try {
                        client.close()
                    } catch (e: IOException) {
                        // not a problem
                    }
                }
            }
        }
    }

    fun stop() {
        try {
            socket.close()
        } catch (e: IOException) {
            throw RuntimeException(e)
        } finally {
            executorService.shutdown()
        }
    }

    private fun closeBodyOf(response: RawHttpResponse<*>?) {
        response?.body?.ifPresent { b: BodyReader ->
            try {
                b.close()
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }
    }

    private fun keepAlive(httpVersion : HttpVersion, connectionOption: Optional<String>) : Boolean {
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

    init {
        start()
    }
}
