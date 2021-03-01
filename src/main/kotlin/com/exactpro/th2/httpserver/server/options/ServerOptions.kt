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
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

interface ServerOptions {

    /**
     * Create a server socket for the server to use.
     *
     * @return a server socket
     * @throws IOException if an error occurs when binding the socket
     */
    @Throws(IOException::class)
    fun getServerSocket(): ServerSocket

    /**
     * @return the [RawHttp] instance to use to parse requests and responses
     */
    fun getRawHttp(): RawHttp {
        return RawHttp(RawHttpOptions.strict())
    }


    /**
     * @return executor service to use to run client-serving [Runnable]s. Each [Runnable] runs until
     * the connection with the client is closed or lost.
     */
    fun createExecutorService(): ExecutorService {
        val threadCount = AtomicInteger(1)
        return Executors.newFixedThreadPool(25) { runnable: Runnable? ->
            val t = Thread(runnable)
            t.isDaemon = true
            t.name = "th2-http-server-" + threadCount.incrementAndGet()
            t
        }
    }

    fun prepareResponse(request: RawHttpRequest, response: RawHttpResponse<*>): RawHttpResponse<*> {
        return response
    }

    fun onRequest(request: RawHttpRequest) {

    }

    fun onResponse(request: RawHttpRequest, response: RawHttpResponse<*>) {

    }
}