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

package com.exactpro.th2.httpserver.server.options

import rawhttp.core.*
import java.io.IOException
import java.net.ServerSocket
import java.util.concurrent.ExecutorService

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

    fun <T:RawHttpResponse<*>> prepareResponse(request: RawHttpRequest, response: T): T = response

    fun onRequest(request: RawHttpRequest, id: String) = Unit

    fun <T:RawHttpResponse<*>> onResponse(request: RawHttpRequest, response: T) = Unit
}