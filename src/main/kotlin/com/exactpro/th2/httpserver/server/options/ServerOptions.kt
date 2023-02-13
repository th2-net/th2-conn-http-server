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

package com.exactpro.th2.httpserver.server.options

import com.exactpro.th2.common.grpc.EventID
import com.exactpro.th2.httpserver.server.responses.Th2Response
import rawhttp.core.RawHttp
import rawhttp.core.RawHttpOptions
import rawhttp.core.RawHttpRequest
import rawhttp.core.RawHttpResponse
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ExecutorService
import javax.annotation.concurrent.ThreadSafe

@ThreadSafe
interface ServerOptions {

    /**
     * Creates a server socket for a server to use
     *
     * @return a server socket
     * @throws IOException if an error occurs when binding the socket
     */
    @Throws(IOException::class)
    fun createSocket(): ServerSocket

    /**
     * @return the [RawHttp] instance to use to parse requests and responses
     */
    fun getRawHttp(): RawHttp {
        return RawHttp(RawHttpOptions.strict())
    }


    /**
     * @return executor service to use to run client-serving [Runnable]s. Each [Runnable] runs until
     * the connection with the client is closed or lost
     */
    fun createExecutorService(): ExecutorService

    /**
     * Must be guaranteed to be thread-safe since it will be called from different threads
     *
     */
    fun onRequest(request: RawHttpRequest, uuid: String, parentEventID: EventID)

    /**
     * Must be guaranteed to be thread-safe since it will be called from different threads
     *
     * @return response with specific changes or without them
     */
    fun prepareResponse(request: RawHttpRequest, response: RawHttpResponse<Th2Response>) = response

    fun onResponse(response: RawHttpResponse<Th2Response>)

    fun onConnect(client: Socket) : EventID
}