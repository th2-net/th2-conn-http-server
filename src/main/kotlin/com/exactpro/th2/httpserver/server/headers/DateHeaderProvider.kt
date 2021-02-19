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

package com.exactpro.th2.httpserver.server.headers

import rawhttp.core.RawHttpHeaders
import java.time.Duration
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

class DateHeaderProvider(maxCacheDuration: Duration, private val createHeader: () -> RawHttpHeaders) {

    constructor(maxCacheDuration: Duration) : this(maxCacheDuration, {createDateHeader()})

    private val maxCacheDurationMillis : Long = maxCacheDuration.toMillis()

    private val currentDateHeaderInSecondsResolution : ThreadLocal<RawHttpHeaders> = ThreadLocal.withInitial {createDateHeader()};

    private val lastDateAccess = ThreadLocal.withInitial { 0L }



    fun getHeader() : RawHttpHeaders {
        val now = System.currentTimeMillis()
        val dateHeader: RawHttpHeaders
        if (lastDateAccess.get() < now - maxCacheDurationMillis) {
            dateHeader = createHeader()
            lastDateAccess.set(now)
            currentDateHeaderInSecondsResolution.set(dateHeader)
        } else {
            dateHeader = currentDateHeaderInSecondsResolution.get()
        }
        return dateHeader
    }
}

private fun createDateHeader(): RawHttpHeaders {
    return RawHttpHeaders.newBuilderSkippingValidation()
        .with("Date", DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.now(ZoneOffset.UTC)))
        .build()
}