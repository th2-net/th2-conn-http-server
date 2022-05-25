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

package com.exactpro.th2.http.server.util

import com.exactpro.th2.common.event.Event
import com.exactpro.th2.common.event.EventUtils
import com.exactpro.th2.common.grpc.MessageID

fun createErrorEvent(message: String, exception: Throwable, messageIDs: List<MessageID> = emptyList()): Event = Event.start().apply {
    endTimestamp()
    name(message)
    type("Error")
    status(Event.Status.FAILED )

    generateSequence(exception, Throwable::cause).forEach { error ->
        bodyData(EventUtils.createMessageBean(error.message))
    }

    messageIDs.forEach(this::messageID)
}