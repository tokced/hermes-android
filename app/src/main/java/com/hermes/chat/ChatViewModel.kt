package com.hermes.chat.ui

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.chat.ChatAttachment
import com.hermes.chat.ChatMessage
import com.hermes.chat.ChatSession
import com.hermes.chat.ChatState
import com.hermes.chat.HermesApiService
import com.hermes.chat.SettingsManager
import com.hermes.chat.SessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class ChatViewModel(
    private val apiService: HermesApiService,
    private val settingsManager: SettingsManager,
    private val sessionManager: SessionManager
) : ViewModel() {

    private val _state = MutableStateFlow(ChatState())
    val state: StateFlow<ChatState> = _state.asStateFlow()

    private val _sessions = MutableStateFlow<List<ChatSession>>(emptyList())
    val sessions: StateFlow<List<ChatSession>> = _sessions.asStateFlow()

    init {
        loadSessions()
        // Auto-create first session if none
        if (_sessions.value.isEmpty()) {
            createNewSession()
        } else {
            // Load most recent session
            val recent = _sessions.value.first()
            loadSessionInternal(recent.id)
        }
    }

    fun loadSessions() {
        _sessions.value = sessionManager.listSessions()
    }

    fun createNewSession() {
        val session = sessionManager.createNewSession()
        loadSessions()
        loadSessionInternal(session.id)
    }

    fun loadSession(sessionId: String) {
        loadSessionInternal(sessionId)
    }

    private fun loadSessionInternal(sessionId: String) {
        val session = sessionManager.loadSession(sessionId)
        if (session != null) {
            _state.value = ChatState(
                sessionId = session.id,
                sessionTitle = session.title,
                messages = session.messages
            )
        }
    }

    fun deleteSession(sessionId: String) {
        sessionManager.deleteSession(sessionId)
        loadSessions()
        // If deleted current session, switch to most recent or create new
        if (_state.value.sessionId == sessionId) {
            if (_sessions.value.isNotEmpty()) {
                loadSessionInternal(_sessions.value.first().id)
            } else {
                createNewSession()
            }
        }
    }

    fun updateInput(text: String) {
        _state.value = _state.value.copy(inputText = text)
    }

    fun addAttachment(context: Context, uri: Uri, name: String, mimeType: String, size: Int) {
        val type = if (mimeType.startsWith("image/")) {
            com.hermes.chat.AttachmentType.IMAGE
        } else {
            com.hermes.chat.AttachmentType.FILE
        }
        val attachment = ChatAttachment(
            id = System.currentTimeMillis().toString(),
            type = type,
            uri = uri,
            name = name,
            size = size
        )
        _state.value = _state.value.copy(
            pendingAttachments = _state.value.pendingAttachments + attachment
        )
    }

    fun removeAttachment(id: String) {
        _state.value = _state.value.copy(
            pendingAttachments = _state.value.pendingAttachments.filter { it.id != id }
        )
    }

    fun sendMessage(context: Context) {
        val currentState = _state.value
        val userText = currentState.inputText.trim()
        val attachments = currentState.pendingAttachments

        if (userText.isEmpty() && attachments.isEmpty()) return

        // Auto-title from first user message
        val title = if (currentState.messages.isEmpty() && userText.isNotEmpty()) {
            userText.take(20).let { if (it.length < userText.length) "$it..." else it }
        } else {
            currentState.sessionTitle
        }

        val userMessage = ChatMessage(
            role = "user",
            content = userText,
            attachments = attachments.map { att -> att.copy(uploaded = false) }
        )
        val updatedMessages = currentState.messages + userMessage
        val assistantMessage = ChatMessage(role = "assistant", content = "", isStreaming = true)

        _state.value = currentState.copy(
            sessionTitle = title,
            messages = updatedMessages + assistantMessage,
            inputText = "",
            pendingAttachments = emptyList(),
            isLoading = true,
            error = null
        )

        // Save immediately after user sends
        saveCurrentSession()

        viewModelScope.launch {
            // Upload attachments
            val uploadedAttachments = mutableListOf<Map<String, String>>()
            var uploadError: String? = null

            withContext(Dispatchers.IO) {
                for (att in attachments) {
                    val uri = att.uri ?: continue
                    try {
                        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                            ?: throw Exception("无法读取文件")
                        val fileId = uploadFileSuspend(bytes, att.name, att.type.name.lowercase())
                        uploadedAttachments.add(mapOf("file_id" to fileId, "type" to att.type.name.lowercase()))
                    } catch (e: Exception) {
                        uploadError = "上传失败: ${e.message}"
                        break
                    }
                }
            }

            if (uploadError != null) {
                _state.value = _state.value.copy(isLoading = false, error = uploadError)
                return@launch
            }

            val messagesToSend = listOf(mapOf("role" to "user", "content" to userText))

            withContext(Dispatchers.IO) {
                apiService.sendMessage(
                    messages = messagesToSend,
                    attachments = uploadedAttachments,
                    onChunk = { chunk ->
                        // 切回主线程更新 UI
                        viewModelScope.launch {
                            _state.value = _state.value.copy(
                                messages = _state.value.messages.map {
                                    if (it == assistantMessage) it.copy(content = it.content + chunk)
                                    else it
                                }
                            )
                        }
                    },
                    onComplete = {
                        viewModelScope.launch {
                            _state.value = _state.value.copy(isLoading = false)
                            saveCurrentSession()
                        }
                    },
                    onError = { errorMsg ->
                        viewModelScope.launch {
                            _state.value = _state.value.copy(isLoading = false, error = errorMsg)
                        }
                    }
                )
            }
        }
    }

    fun clearMessages() {
        _state.value = _state.value.copy(
            messages = emptyList(),
            sessionTitle = "新对话"
        )
        saveCurrentSession()
    }

    private fun saveCurrentSession() {
        val session = ChatSession(
            id = _state.value.sessionId,
            title = _state.value.sessionTitle,
            messages = _state.value.messages,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        sessionManager.saveSession(session)
        loadSessions()
    }

    private suspend fun uploadFileSuspend(bytes: ByteArray, filename: String, type: String): String {
        return suspendCancellableCoroutine { cont ->
            apiService.uploadFile(
                fileBytes = bytes,
                filename = filename,
                mimeType = if (type == "image") "image/*" else "application/octet-stream",
                onSuccess = { fileId -> cont.resume(fileId) },
                onError = { msg -> cont.resumeWithException(Exception(msg)) }
            )
        }
    }

    fun clearError() {
        _state.value = _state.value.copy(error = null)
    }
}
