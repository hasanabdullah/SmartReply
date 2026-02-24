package com.personal.smartreply.data.remote

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ClaudeStreamParser @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val json: Json
) {
    fun stream(apiKey: String, request: ClaudeRequest): Flow<String> = callbackFlow {
        val streamRequest = request.copy(stream = true)
        val jsonBody = json.encodeToString(ClaudeRequest.serializer(), streamRequest)

        val httpRequest = Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .addHeader("x-api-key", apiKey)
            .addHeader("anthropic-version", "2023-06-01")
            .addHeader("content-type", "application/json")
            .post(jsonBody.toRequestBody("application/json".toMediaType()))
            .build()

        val call = okHttpClient.newCall(httpRequest)
        call.enqueue(object : Callback {
            override fun onResponse(call: Call, response: Response) {
                try {
                    if (!response.isSuccessful) {
                        val errorBody = response.body?.string() ?: "HTTP ${response.code}"
                        close(IOException("API error: $errorBody"))
                        return
                    }

                    val source = response.body?.source()
                    if (source == null) {
                        close(IOException("Empty response body"))
                        return
                    }

                    while (!source.exhausted()) {
                        val line = source.readUtf8Line() ?: break
                        if (!line.startsWith("data: ")) continue
                        val data = line.removePrefix("data: ").trim()
                        if (data.isEmpty()) continue

                        try {
                            val event = json.decodeFromString(StreamEvent.serializer(), data)
                            if (event.type == "content_block_delta") {
                                val delta = json.decodeFromString(ContentBlockDelta.serializer(), data)
                                if (delta.delta.type == "text_delta" && delta.delta.text.isNotEmpty()) {
                                    trySend(delta.delta.text)
                                }
                            }
                        } catch (_: Exception) {
                            // Skip unparseable events
                        }
                    }

                    close()
                } catch (e: Exception) {
                    close(e)
                }
            }

            override fun onFailure(call: Call, e: IOException) {
                close(e)
            }
        })

        awaitClose { call.cancel() }
    }
}
