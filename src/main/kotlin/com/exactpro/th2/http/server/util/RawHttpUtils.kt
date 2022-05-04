package com.exactpro.th2.http.server.util

import rawhttp.core.RawHttpResponse
import rawhttp.core.body.BodyReader

fun RawHttpResponse<*>.tryCloseBody() = body.ifPresent { it.runCatching(BodyReader::close)}