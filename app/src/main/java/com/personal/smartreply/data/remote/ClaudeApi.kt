package com.personal.smartreply.data.remote

import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST

interface ClaudeApi {
    @POST("v1/messages")
    suspend fun createMessage(
        @Header("x-api-key") apiKey: String,
        @Header("anthropic-version") version: String = "2023-06-01",
        @Body request: ClaudeRequest
    ): ClaudeResponse
}
