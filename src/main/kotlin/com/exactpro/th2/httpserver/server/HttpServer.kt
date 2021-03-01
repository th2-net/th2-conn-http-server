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


interface HttpServer {
    /**
     * Start the server using the provided router to route requests.
     *
     *
     * If this method is called multiple times, it must replace the previous router even if it was already running,
     * and start serving requests using the new router.
     *
     * @param router request router
     */
    fun start(responseManager: IResponseManager)

    /**
     * Stop the server.
     */
    fun stop()
}