package com.hermes.chat

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class SessionManager(private val context: Context) {

    private val sessionsDir: File by lazy {
        File(context.filesDir, "sessions").also { it.mkdirs() }
    }

    private val dateFormat = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

    fun listSessions(): List<ChatSession> {
        val sessions = mutableListOf<ChatSession>()
        sessionsDir.listFiles()?.filter { it.extension == "json" }?.forEach { file ->
            try {
                val json = JSONObject(file.readText())
                sessions.add(parseSession(json))
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return sessions.sortedByDescending { it.updatedAt }
    }

    fun loadSession(sessionId: String): ChatSession? {
        val file = File(sessionsDir, "$sessionId.json")
        return if (file.exists()) {
            try {
                val json = JSONObject(file.readText())
                parseSession(json)
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        } else null
    }

    fun saveSession(session: ChatSession) {
        val file = File(sessionsDir, "${session.id}.json")
        val json = JSONObject().apply {
            put("id", session.id)
            put("title", session.title)
            put("createdAt", session.createdAt)
            put("updatedAt", session.updatedAt)
            put("messages", JSONArray().apply {
                session.messages.forEach { msg ->
                    put(JSONObject().apply {
                        put("role", msg.role)
                        put("content", msg.content)
                        put("isStreaming", msg.isStreaming)
                        put("timestamp", msg.timestamp)
                        if (msg.attachments.isNotEmpty()) {
                            put("attachments", JSONArray().apply {
                                msg.attachments.forEach { att ->
                                    put(JSONObject().apply {
                                        put("id", att.id)
                                        put("type", att.type.name)
                                        put("name", att.name)
                                        put("size", att.size)
                                        put("uploaded", att.uploaded)
                                        if (att.uri != null) put("uri", att.uri.toString())
                                        if (att.localPath.isNotEmpty()) put("localPath", att.localPath)
                                    })
                                }
                            })
                        }
                    })
                }
            })
        }
        file.writeText(json.toString(2))
    }

    fun deleteSession(sessionId: String) {
        File(sessionsDir, "$sessionId.json").delete()
    }

    fun createNewSession(): ChatSession {
        val session = ChatSession(
            id = UUID.randomUUID().toString().take(8),
            title = "新对话",
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        saveSession(session)
        return session
    }

    private fun parseSession(json: JSONObject): ChatSession {
        val messages = mutableListOf<ChatMessage>()
        val msgsArray = json.optJSONArray("messages") ?: JSONArray()
        for (i in 0 until msgsArray.length()) {
            val msgObj = msgsArray.getJSONObject(i)
            val attachments = mutableListOf<ChatAttachment>()
            val attArray = msgObj.optJSONArray("attachments")
            if (attArray != null) {
                for (j in 0 until attArray.length()) {
                    val attObj = attArray.getJSONObject(j)
                    attachments.add(ChatAttachment(
                        id = attObj.optString("id", ""),
                        type = try { AttachmentType.valueOf(attObj.optString("type", "FILE")) } catch (e: Exception) { AttachmentType.FILE },
                        name = attObj.optString("name", ""),
                        size = attObj.optInt("size", 0),
                        uploaded = attObj.optBoolean("uploaded", false),
                        uri = attObj.optString("uri", "").takeIf { it.isNotEmpty() }?.let { android.net.Uri.parse(it) },
                        localPath = attObj.optString("localPath", "")
                    ))
                }
            }
            messages.add(ChatMessage(
                role = msgObj.optString("role", "user"),
                content = msgObj.optString("content", ""),
                isStreaming = msgObj.optBoolean("isStreaming", false),
                timestamp = msgObj.optLong("timestamp", System.currentTimeMillis()),
                attachments = attachments
            ))
        }
        return ChatSession(
            id = json.optString("id", ""),
            title = json.optString("title", "新对话"),
            messages = messages,
            createdAt = json.optLong("createdAt", System.currentTimeMillis()),
            updatedAt = json.optLong("updatedAt", System.currentTimeMillis())
        )
    }

    fun formatTime(timestamp: Long): String {
        val now = System.currentTimeMillis()
        val diff = now - timestamp
        return when {
            diff < 60_000 -> "刚刚"
            diff < 3_600_000 -> "${diff / 60_000}分钟前"
            diff < 86_400_000 -> dateFormat.format(Date(timestamp))
            else -> dateFormat.format(Date(timestamp))
        }
    }
}
