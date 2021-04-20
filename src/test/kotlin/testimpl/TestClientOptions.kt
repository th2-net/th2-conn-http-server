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

package testimpl

import mu.KotlinLogging
import rawhttp.core.RawHttpRequest
import rawhttp.core.RawHttpResponse
import rawhttp.core.client.TcpRawHttpClient
import java.net.Socket
import java.net.URI
import java.time.Instant
import java.util.concurrent.ExecutorService

class TestClientOptions(private val https: Boolean = false) : TcpRawHttpClient.DefaultOptions() {
    private val logger = KotlinLogging.logger {}
    private val socketExpirationTimes = mutableMapOf<Socket, Long>()


    override fun onRequest(httpRequest: RawHttpRequest): RawHttpRequest {
        val request = httpRequest.eagerly()
        logger.info { "Sent request: \n$request" }
        return super.onRequest(request)
    }

    override fun onResponse(socket: Socket, uri: URI, httpResponse: RawHttpResponse<Void>): RawHttpResponse<Void> {
        val response = httpResponse.eagerly()
        logger.info { "Received response: \n$response" }
        return super.onResponse(socket, uri, response)
    }

    override fun createSocket(useHttps: Boolean, host: String, port: Int): Socket {
        return super.createSocket(https, host, port)
    }

    override fun getSocket(uri: URI): Socket = super.getSocket(uri).let { socket ->
        val currentTime = System.currentTimeMillis()

        socketExpirationTimes[socket]?.let { expirationTime ->
            if (currentTime > expirationTime) {
                logger.debug { "Removing inactive socket: $socket (expired at: ${Instant.ofEpochMilli(expirationTime)})" }
                socketExpirationTimes -= socket
                socket.runCatching { close() }
                removeSocket(socket)
                return getSocket(uri)
            }
        }

        socketExpirationTimes[socket] = currentTime + 20000
        socket.apply { soTimeout = 15000 }
    }

    override fun removeSocket(socket: Socket) {
        socketExpirationTimes -= socket
    }
}