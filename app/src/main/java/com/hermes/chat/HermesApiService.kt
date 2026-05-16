package com.hermes.chat

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class HermesApiService(
    private val baseUrl: String,
    private val apiKey: String
) {
    // 临时方案：信任所有证书（仅用于测试 SakuraFrp 自签名证书）
    // 生产环境应使用正式域名 + Let's Encrypt 证书
    private val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun getAcceptedIssuers() = arrayOf<X509Certificate>()
    })
    private val sslContext = SSLContext.getInstance("TLS").apply {
        init(null, trustAllCerts, SecureRandom())
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
        .hostnameVerifier { _, _ -> true }
        .build()

    private val jsonMediaType = "application/json".toMediaType()

    // 连接检测（无认证，轻量 keepalive），5秒超时，防止阻塞 UI
    fun ping(): Boolean {
        return try {
            val request = Request.Builder()
                .url("$baseUrl/ping")
                .get()
                .build()
            client.newCall(request).execute().use { it.isSuccessful }
        } catch (_: Exception) { false }
    }

    // 异步连接检测，3秒超时，回调在主线程
    fun pingAsync(onResult: (Boolean) -> Unit) {
        val shortClient = client.newBuilder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .build()
        Thread {
            try {
                val request = Request.Builder()
                    .url("$baseUrl/ping")
                    .get()
                    .build()
                val ok = shortClient.newCall(request).execute().use { it.isSuccessful }
                onResult(ok)
            } catch (_: Exception) {
                onResult(false)
            }
        }.start()
    }

    // 上传文件，返回 file_id
    fun uploadFile(
        fileBytes: ByteArray,
        filename: String,
        mimeType: String,
        onSuccess: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        val body = fileBytes.toRequestBody(mimeType.toMediaType())
        val request = Request.Builder()
            .url("$baseUrl/v1/upload")
            .addHeader("x-api-key", apiKey)
            .post(
                MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("file", filename, body)
                    .build()
            )
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                onError("上传失败: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) {
                    onError("上传失败: ${response.code}")
                    return
                }
                try {
                    val body = response.body?.string() ?: throw Exception("空响应")
                    // 简单解析 {"file_id": "xxx"}
                    val regex = """"file_id"\s*:\s*"([^"]+)"""".toRegex()
                    val match = regex.find(body)
                    val fileId = match?.groupValues?.get(1)
                    if (fileId != null) {
                        onSuccess(fileId)
                    } else {
                        onError("解析响应失败")
                    }
                } catch (e: Exception) {
                    onError("解析失败: ${e.message}")
                }
            }
        })
    }

    // 发送消息（支持图片/附件）
    fun sendMessage(
        sessionId: String,
        messages: List<Map<String, String>>,
        attachments: List<Map<String, String>>, // [{"file_id": "xxx", "type": "image"}]
        onChunk: (String) -> Unit,
        onComplete: () -> Unit,
        onError: (String) -> Unit
    ) {
        val hasAttachments = attachments.isNotEmpty()

        val messagesArray = JSONArray()
        messages.forEach { msg ->
            val msgObj = JSONObject()
            msgObj.put("role", msg["role"])
            msgObj.put("content", msg["content"])
            messagesArray.put(msgObj)
        }

        val requestBody = JSONObject().apply {
            put("messages", messagesArray)
            put("stream", true)
            put("session_id", sessionId)
            if (hasAttachments) {
                val attArray = JSONArray()
                attachments.forEach { att ->
                    val attObj = JSONObject()
                    attObj.put("file_id", att["file_id"])
                    attObj.put("type", att["type"])
                    attArray.put(attObj)
                }
                put("attachments", attArray)
            }
        }

        val body = requestBody.toString().toRequestBody(jsonMediaType)
        val request = Request.Builder()
            .url("$baseUrl/v1/chat/completions")
            .addHeader("Content-Type", "application/json")
            .addHeader("x-api-key", apiKey)
            .addHeader("Accept", "text/event-stream")
            .post(body)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                onError("连接失败: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) {
                    onError("服务器错误: ${response.code}")
                    return
                }

                try {
                    val stream = response.body?.byteStream() ?: throw Exception("空响应")
                    val reader = stream.bufferedReader()

                    try {
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.startsWith("data: ")) {
                                val data = line.removePrefix("data: ").trim()
                                if (data == "[DONE]") break
                                if (data.startsWith("{")) {
                                    val json = JSONObject(data)
                                    val done = json.optBoolean("done", false)
                                    val chunk = json.optString("chunk", "")
                                    if (chunk.isNotEmpty()) {
                                        onChunk(chunk)
                                    }
                                    if (done) break
                                }
                            }
                        }
                    } catch (e: Exception) {
                        // Stream interrupted
                    } finally {
                        reader.close()
                    }
                    onComplete()
                } catch (e: Exception) {
                    onError("解析错误: ${e.message}")
                }
            }
        })
    }

    // 非流式发送（备用）
    fun sendMessageNonStream(
        messages: List<Map<String, String>>,
        attachments: List<Map<String, String>> = emptyList(),
        onComplete: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        val hasAttachments = attachments.isNotEmpty()

        val messagesArray = JSONArray()
        messages.forEach { msg ->
            val msgObj = JSONObject()
            msgObj.put("role", msg["role"])
            msgObj.put("content", msg["content"])
            messagesArray.put(msgObj)
        }

        val requestBody = JSONObject().apply {
            put("messages", messagesArray)
            put("stream", false)
            if (hasAttachments) {
                val attArray = JSONArray()
                attachments.forEach { att ->
                    val attObj = JSONObject()
                    attObj.put("file_id", att["file_id"])
                    attObj.put("type", att["type"])
                    attArray.put(attObj)
                }
                put("attachments", attArray)
            }
        }

        val body = requestBody.toString().toRequestBody(jsonMediaType)
        val request = Request.Builder()
            .url("$baseUrl/v1/chat/completions")
            .addHeader("Content-Type", "application/json")
            .addHeader("x-api-key", apiKey)
            .post(body)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                onError("连接失败: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) {
                    onError("服务器错误: ${response.code}")
                    return
                }

                try {
                    val respBody = response.body?.string() ?: throw Exception("空响应")
                    val content = extractContent(respBody)
                    if (content.isNotEmpty()) {
                        onComplete(content)
                    } else {
                        onError("解析失败: 未能提取内容")
                    }
                } catch (e: Exception) {
                    onError("解析错误: ${e.message}")
                }
            }
        })
    }

    private fun extractContent(json: String): String {
        // 流式用 "chunk"，非流式用 "content"
        val chunkRegex = """\"chunk\"\s*:\s*\"([^\"\\]*(?:\\.[^\"\\]*)*)\"""".toRegex()
        val contentRegex = """\"content\"\s*:\s*\"([^\"\\]*(?:\\.[^\"\\]*)*)\"""".toRegex()
        val match = chunkRegex.find(json) ?: contentRegex.find(json)
        return match?.groupValues?.get(1)
            ?.replace("\\n", "\n")
            ?.replace("\\\"", "\"")
            ?: ""
    }

    // 列出服务器会话
    fun listServerSessions(onSuccess: (List<Map<String, String>>) -> Unit, onError: (String) -> Unit) {
        val request = Request.Builder()
            .url("$baseUrl/v1/sessions")
            .addHeader("x-api-key", apiKey)
            .get()
            .build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { onError(e.message ?: "网络错误") }
            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) { onError("错误: ${response.code}"); return }
                try {
                    val body = response.body?.string() ?: throw Exception("空响应")
                    val json = JSONObject(body)
                    val sessions = json.getJSONArray("sessions")
                    val result = mutableListOf<Map<String, String>>()
                    for (i in 0 until sessions.length()) {
                        val s = sessions.getJSONObject(i)
                        result.add(mapOf("id" to s.getString("id"), "updated_at" to s.optString("updated_at", "")))
                    }
                    onSuccess(result)
                } catch (e: Exception) { onError(e.message ?: "解析失败") }
            }
        })
    }

    // 删除服务器会话
    fun deleteServerSession(sessionId: String, onSuccess: () -> Unit, onError: (String) -> Unit) {
        val request = Request.Builder()
            .url("$baseUrl/v1/sessions/$sessionId")
            .addHeader("x-api-key", apiKey)
            .delete()
            .build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { onError(e.message ?: "网络错误") }
            override fun onResponse(call: Call, response: Response) {
                if (response.isSuccessful) { onSuccess() }
                else { onError("错误: ${response.code}") }
            }
        })
    }

    // 获取服务器会话历史消息
    fun getServerSessionHistory(sessionId: String, onSuccess: (List<Map<String, String>>) -> Unit, onError: (String) -> Unit) {
        val request = Request.Builder()
            .url("$baseUrl/v1/sessions/$sessionId/history")
            .addHeader("x-api-key", apiKey)
            .get()
            .build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { onError(e.message ?: "网络错误") }
            override fun onResponse(call: Call, response: Response) {
                if (response.isSuccessful) {
                    try {
                        val body = response.body?.string() ?: throw Exception("空响应")
                        val json = JSONObject(body)
                        val messages = json.getJSONArray("messages")
                        val result = mutableListOf<Map<String, String>>()
                        for (i in 0 until messages.length()) {
                            val m = messages.getJSONObject(i)
                            result.add(mapOf(
                                "role" to m.getString("role"),
                                "content" to m.getString("content")
                            ))
                        }
                        onSuccess(result)
                    } catch (e: Exception) { onError(e.message ?: "解析失败") }
                } else { onError("错误: ${response.code}") }
            }
        })
    }

    // 将手机 session 关联到服务器 Hermes session（用于加载后继续对话）
    fun linkServerSession(sessionId: String, hermesSessionId: String, onSuccess: () -> Unit, onError: (String) -> Unit) {
        val formBody = FormBody.Builder()
            .add("hermes_session_id", hermesSessionId)
            .build()
        val request = Request.Builder()
            .url("$baseUrl/v1/sessions/$sessionId/link")
            .addHeader("x-api-key", apiKey)
            .post(formBody)
            .build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { onError(e.message ?: "网络错误") }
            override fun onResponse(call: Call, response: Response) {
                if (response.isSuccessful) { onSuccess() }
                else { onError("错误: ${response.code}") }
            }
        })
    }
}
