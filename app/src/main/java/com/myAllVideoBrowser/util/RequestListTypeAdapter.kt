package com.myAllVideoBrowser.util

import com.google.gson.*
import com.google.gson.reflect.TypeToken
import okhttp3.Headers.Companion.toHeaders
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.lang.reflect.Type

class RequestListTypeAdapter : JsonSerializer<List<Request>>, JsonDeserializer<List<Request>> {

    override fun serialize(
        src: List<Request>,
        typeOfSrc: Type,
        context: JsonSerializationContext
    ): JsonElement {
        val array = JsonArray()
        for (request in src) {
            val obj = JsonObject()
            obj.addProperty("url", request.url.toString())
            obj.addProperty("method", request.method)
            if (request.body != null) {
                val buffer = okio.Buffer()
                request.body?.writeTo(buffer)
                obj.addProperty("body", buffer.readUtf8())
            }
            val headersObj = JsonObject()
            for (name in request.headers.names()) {
                headersObj.addProperty(name, request.headers[name])
            }
            obj.add("headers", headersObj)
            array.add(obj)
        }
        return array
    }

    override fun deserialize(
        json: JsonElement,
        typeOfT: Type,
        context: JsonDeserializationContext
    ): List<Request> {
        val list = mutableListOf<Request>()
        if (!json.isJsonArray) return list

        json.asJsonArray.forEach { element ->
            try {
                val obj = element.asJsonObject
                val url = obj.get("url")?.asString ?: return@forEach
                val method = obj.get("method")?.asString ?: "GET"
                val requestBody = obj.get("body")?.asString?.toRequestBody(null)
                    ?: "".toRequestBody(null)
                val headersMap = mutableMapOf<String, String>()
                obj.getAsJsonObject("headers")?.entrySet()?.forEach {
                    headersMap[it.key] = it.value.asString
                }

                val builder = Request.Builder()
                    .url(url)
                    .headers(headersMap.toHeaders())

                if (method != "GET" && method != "HEAD") {
                    builder.method(method, requestBody)
                } else {
                    builder.method(method, null)
                }
                list.add(builder.build())
            } catch (_: Throwable) {
                // Skip invalid entries
            }
        }
        return list
    }

    companion object {
        val TYPE: Type = object : TypeToken<List<Request>>() {}.type
    }
}
