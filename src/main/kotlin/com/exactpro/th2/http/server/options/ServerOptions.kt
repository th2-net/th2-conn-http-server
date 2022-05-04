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

package com.exactpro.th2.http.server.options

import com.exactpro.th2.http.server.response.CommonData
import mu.KotlinLogging
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
abstract class ServerOptions {

    protected val logger = KotlinLogging.logger { this.javaClass.simpleName }

    /**
     * Creates a server socket for a server to use
     *
     * @return a server socket
     * @throws IOException if an error occurs when binding the socket
     */
    @Throws(IOException::class)
    abstract fun createSocket(): ServerSocket

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
    abstract fun createExecutorService(): ExecutorService

    /**
     * Must be guaranteed to be thread-safe since it will be called from different threads
     *
     */
    open fun onRequest(request: RawHttpRequest, uuid: String, parentEventID: String) = Unit

    /**
     * Must be guaranteed to be thread-safe since it will be called from different threads
     *
     * @return response with specific changes or without them
     */
    open fun prepareResponse(request: RawHttpRequest, response: RawHttpResponse<CommonData>) = response

    open fun onResponse(response: RawHttpResponse<CommonData>) = Unit

    /**
     * @return client id as [String]
     */
    abstract fun onConnect(client: Socket) : String

    open fun onError(message: String, clientID: String? = null, exception: Throwable) = logger.error(exception) { "[ClientID: $clientID] $message" }
}