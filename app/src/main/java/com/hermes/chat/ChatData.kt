package com.hermes.chat

import android.net.Uri

data class ChatAttachment(
    val id: String = "",
    val type: AttachmentType = AttachmentType.IMAGE,
    val uri: Uri? = null,
    val localPath: String = "",
    val name: String = "",
    val size: Int = 0,
    val uploaded: Boolean = false,
    val uploadProgress: Float = 0f
)

enum class AttachmentType {
    IMAGE,
    FILE
}

data class ChatMessage(
    val role: String,
    val content: String,
    val attachments: List<ChatAttachment> = emptyList(),
    val isStreaming: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
)

data class ChatSession(
    val id: String = "",
    val title: String = "新对话",
    val messages: List<ChatMessage> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

// 服务器会话（从 Bridge /v1/sessions 获取）
data class ServerSession(
    val id: String,
    val updatedAt: String = ""
)

data class ChatState(
    val sessionId: String = "",
    val sessionTitle: String = "新对话",
    val messages: List<ChatMessage> = emptyList(),
    val inputText: String = "",
    val isLoading: Boolean = false,
    val error: String? = null,
    val pendingAttachments: List<ChatAttachment> = emptyList(),
    val serverSessions: List<ServerSession> = emptyList()
)
