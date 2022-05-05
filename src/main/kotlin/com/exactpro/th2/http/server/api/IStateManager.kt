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

package com.exactpro.th2.http.server.api

import com.exactpro.th2.common.event.Event
import com.exactpro.th2.http.server.RawHttpServer
import com.exactpro.th2.http.server.util.LinkedData
import rawhttp.core.RawHttpRequest
import rawhttp.core.RawHttpResponse

interface IStateManager : AutoCloseable {

    fun init(value: StateManagerContext)

    fun onRequest(request: RawHttpRequest, uuid: String)

    fun prepareResponse(request: RawHttpRequest, response: RawHttpResponse<LinkedData>): RawHttpResponse<LinkedData>

    data class StateManagerContext(
        val server: RawHttpServer,
        val onEvent: (event: Event, parentEventID: String?) -> String
    )
}