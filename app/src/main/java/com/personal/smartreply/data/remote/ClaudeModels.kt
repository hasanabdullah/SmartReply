package com.personal.smartreply.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ClaudeRequest(
    val model: String,
    @SerialName("max_tokens") val maxTokens: Int = 300,
    val system: String? = null,
    val messages: List<ClaudeMessage>,
    val stream: Boolean = false
)

@Serializable
data class ClaudeMessage(
    val role: String,
    val content: String
)

@Serializable
data class ClaudeResponse(
    val id: String = "",
    val type: String = "",
    val content: List<ContentBlock> = emptyList(),
    @SerialName("stop_reason") val stopReason: String? = null,
    val usage: Usage? = null
)

@Serializable
data class ContentBlock(
    val type: String = "",
    val text: String = ""
)

@Serializable
data class Usage(
    @SerialName("input_tokens") val inputTokens: Int = 0,
    @SerialName("output_tokens") val outputTokens: Int = 0
)

// SSE streaming event types
@Serializable
data class StreamEvent(
    val type: String = ""
)

@Serializable
data class ContentBlockDelta(
    val type: String = "",
    val index: Int = 0,
    val delta: Delta = Delta()
)

@Serializable
data class Delta(
    val type: String = "",
    val text: String = ""
)

@Serializable
data class MessageDelta(
    val type: String = "",
    val delta: MessageDeltaBody = MessageDeltaBody()
)

@Serializable
data class MessageDeltaBody(
    @SerialName("stop_reason") val stopReason: String? = null
)
