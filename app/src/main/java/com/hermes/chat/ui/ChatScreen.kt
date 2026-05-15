package com.hermes.chat.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import com.hermes.chat.ChatAttachment
import com.hermes.chat.ChatMessage
import com.hermes.chat.ChatSession
import com.hermes.chat.HermesApiService
import com.hermes.chat.SessionManager
import com.hermes.chat.SettingsManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    sessionManager: SessionManager,
    onNavigateToSettings: () -> Unit
) {
    val context = LocalContext.current
    val settingsManager = remember { SettingsManager(context) }
    val settings = remember { settingsManager.loadSettings() }
    val apiService = remember { HermesApiService(baseUrl = settings.apiBaseUrl, apiKey = settings.apiKey) }
    val viewModel = remember { ChatViewModel(apiService, settingsManager, sessionManager) }

    val sessions by viewModel.sessions.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    // Session sheet state
    var showSessionSheet by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState()

    // Auto-scroll
    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) {
            listState.animateScrollToItem(state.messages.size - 1)
        }
    }

    // 连接状态：null=未检测, true=已连接, false=未连接
    var connectionOk by remember { mutableStateOf<Boolean?>(null) }

    // 启动时自动检测连接（异步，不阻塞 UI）
    LaunchedEffect(Unit) {
        if (settings.apiBaseUrl.isNotBlank()) {
            var retries = 0
            suspend fun attempt(): Boolean = suspendCancellableCoroutine { cont ->
                apiService.pingAsync { ok ->
                    if (cont.isActive) cont.resume(ok) {}
                }
            }
            suspend fun attemptWithTimeout(): Boolean {
                return withTimeoutOrNull(4000L) { attempt() } ?: false
            }
            // 首次快速检测
            if (attemptWithTimeout()) { connectionOk = true; return@LaunchedEffect }
            // 未连接则每 5 秒重试，最多 3 次
            while (retries < 3) {
                delay(5000)
                if (attemptWithTimeout()) { connectionOk = true; return@LaunchedEffect }
                retries++
            }
            connectionOk = false
        }
    }

    // 打开会话面板时加载服务器会话
    LaunchedEffect(showSessionSheet) {
        if (showSessionSheet) {
            viewModel.loadServerSessions()
        }
    }

    // File pickers
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { viewModel.addAttachment(context, it, "image_${System.currentTimeMillis()}.jpg", "image/*", 0) }
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { viewModel.addAttachment(context, it, "file_${System.currentTimeMillis()}", "application/octet-stream", 0) }
    }

    // Session sheet
    if (showSessionSheet) {
        ModalBottomSheet(
            sheetState = sheetState,
            onDismissRequest = { showSessionSheet = false }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 32.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("历史对话", style = MaterialTheme.typography.headlineSmall)
                    FilledTonalButton(onClick = {
                        viewModel.createNewSession()
                        showSessionSheet = false
                    }) {
                        Icon(Icons.Default.Add, null)
                        Spacer(Modifier.width(4.dp))
                        Text("新建")
                    }
                }
                Spacer(Modifier.height(16.dp))

                // ===== 本地会话 =====
                Text("本机", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 4.dp))
                if (sessions.isEmpty()) {
                    Box(modifier = Modifier.fillMaxWidth().height(60.dp), contentAlignment = Alignment.Center) {
                        Text("无", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.heightIn(max = 200.dp)
                    ) {
                        items(sessions, key = { it.id }) { session ->
                            SessionItem(
                                session = session,
                                isSelected = session.id == state.sessionId,
                                timeStr = sessionManager.formatTime(session.updatedAt),
                                onClick = {
                                    viewModel.loadSession(session.id)
                                    showSessionSheet = false
                                },
                                onDelete = { viewModel.deleteSession(session.id) }
                            )
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))

                // ===== 服务器会话 =====
                Text("服务器 (Hermes)", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 4.dp))
                if (state.serverSessions.isEmpty()) {
                    Box(modifier = Modifier.fillMaxWidth().height(60.dp), contentAlignment = Alignment.Center) {
                        Text("无", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.heightIn(max = 200.dp)
                    ) {
                        items(state.serverSessions, key = { it.id }) { serverSession ->
                            ServerSessionItem(
                                sessionId = serverSession.id,
                                updatedAt = serverSession.updatedAt,
                                onClick = {
                                    // TODO: 加载服务器会话继续聊天
                                    showSessionSheet = false
                                },
                                onDelete = { viewModel.deleteServerSession(serverSession.id) }
                            )
                        }
                    }
                }
            }
        }
    }

    // Main content
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        // Title bar
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { showSessionSheet = true }) {
                    Icon(Icons.Default.Menu, "会话列表")
                }
                Spacer(Modifier.width(4.dp))
                Text(state.sessionTitle, style = MaterialTheme.typography.titleLarge, maxLines = 1)
                Spacer(Modifier.width(8.dp))
                // 连接状态指示灯
                Box(
                    modifier = Modifier.size(10.dp).clip(RoundedCornerShape(5.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    val dotColor = when (connectionOk) {
                        true -> Color(0xFF4CAF50)
                        false -> Color(0xFFF44336)
                        null -> Color(0xFF9E9E9E)
                    }
                    Surface(modifier = Modifier.size(8.dp), shape = RoundedCornerShape(4.dp), color = dotColor) {}
                }
            }
            Row {
                IconButton(onClick = { viewModel.clearMessages() }) {
                    Icon(Icons.Default.DeleteSweep, "清空")
                }
                IconButton(onClick = onNavigateToSettings) {
                    Icon(Icons.Default.Settings, "设置")
                }
            }
        }

        // Messages
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(state.messages) { message ->
                MessageBubble(message = message, onImageClick = {})
            }
            if (state.isLoading && state.messages.lastOrNull()?.role == "user") {
                item { LoadingIndicator() }
            }
        }

        // Error
        state.error?.let { error ->
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                modifier = Modifier.padding(vertical = 8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(error, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.weight(1f))
                    IconButton(onClick = {
                        val clip = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        if (clip != null) {
                            clip.setPrimaryClip(ClipData.newPlainText("error", error))
                            Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
                        }
                    }) { Icon(Icons.Default.ContentCopy, "复制", tint = MaterialTheme.colorScheme.onErrorContainer) }
                    TextButton(onClick = { viewModel.clearError() }) { Text("关闭") }
                }
            }
        }

        // Attachments preview
        if (state.pendingAttachments.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                state.pendingAttachments.forEach { att ->
                    AttachmentChip(attachment = att, onRemove = { viewModel.removeAttachment(att.id) })
                }
            }
        }

        // Input area
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            IconButton(onClick = { filePicker.launch("*/*") }, enabled = !state.isLoading) {
                Icon(Icons.Default.AttachFile, "发送文件")
            }
            IconButton(onClick = { imagePicker.launch("image/*") }, enabled = !state.isLoading) {
                Icon(Icons.Default.Image, "发送图片")
            }
            OutlinedTextField(
                value = state.inputText,
                onValueChange = { viewModel.updateInput(it) },
                modifier = Modifier.weight(1f),
                placeholder = { Text("输入消息...") },
                enabled = !state.isLoading,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { viewModel.sendMessage(context) }),
                singleLine = true
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = { viewModel.sendMessage(context) },
                enabled = !state.isLoading && (state.inputText.isNotBlank() || state.pendingAttachments.isNotEmpty())
            ) {
                Text("发送")
            }
        }
    }
}

@Composable
private fun SessionItem(
    session: ChatSession,
    isSelected: Boolean,
    timeStr: String,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(session.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1,
                    color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.height(4.dp))
                Row {
                    Text("${session.messages.size}条", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(" · ", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(timeStr, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(Icons.Default.MoreVert, "更多",
                        tint = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("删除", color = MaterialTheme.colorScheme.error) },
                        onClick = { showMenu = false; onDelete() },
                        leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) }
                    )
                }
            }
        }
    }
}

@Composable
fun AttachmentChip(attachment: ChatAttachment, onRemove: () -> Unit) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.height(48.dp)) {
        Row(modifier = Modifier.padding(start = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (attachment.type == com.hermes.chat.AttachmentType.IMAGE && attachment.uri != null) {
                AsyncImage(model = attachment.uri, contentDescription = null,
                    modifier = Modifier.size(32.dp).clip(RoundedCornerShape(4.dp)), contentScale = ContentScale.Crop)
            } else {
                Icon(Icons.AutoMirrored.Filled.InsertDriveFile, null, modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSecondaryContainer)
            }
            Text(attachment.name.take(12), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.padding(horizontal = 4.dp))
            IconButton(onClick = onRemove, modifier = Modifier.size(24.dp)) {
                Icon(Icons.Default.Close, "移除", modifier = Modifier.size(14.dp))
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageBubble(message: ChatMessage, onImageClick: (Uri) -> Unit) {
    val isUser = message.role == "user"
    val backgroundColor = if (isUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer
    val textColor = if (isUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer

    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = if (isUser) Alignment.End else Alignment.Start) {
        if (message.attachments.isNotEmpty()) {
            Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 4.dp),
                horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start) {
                message.attachments.forEach { att ->
                    if (att.type == com.hermes.chat.AttachmentType.IMAGE && att.uri != null) {
                        Card(modifier = Modifier.size(120.dp).clickable { onImageClick(att.uri) }, shape = RoundedCornerShape(8.dp)) {
                            AsyncImage(model = att.uri, contentDescription = null,
                                modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        }
                    } else {
                        Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.padding(end = 4.dp)) {
                            Row(modifier = Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.AutoMirrored.Filled.InsertDriveFile, null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(att.name, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
        if (message.content.isNotEmpty() || message.isStreaming) {
            val context = LocalContext.current
            Card(
                colors = CardDefaults.cardColors(containerColor = backgroundColor),
                modifier = Modifier.widthIn(max = 280.dp).combinedClickable(
                    onClick = {},
                    onLongClick = {
                        val clip = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        if (clip != null && message.content.isNotEmpty()) {
                            clip.setPrimaryClip(ClipData.newPlainText("message", message.content))
                            Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
                        }
                    }
                )
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(message.content.ifEmpty { "正在思考..." }, color = textColor)
                    if (message.isStreaming && message.content.isEmpty()) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 4.dp), color = textColor)
                    }
                }
            }
        }
    }
}

@Composable
fun LoadingIndicator() {
    Row(modifier = Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.Start) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
            Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("Hermes 正在回复...", color = MaterialTheme.colorScheme.onSecondaryContainer)
            }
        }
    }
}

@Composable
fun ServerSessionItem(
    sessionId: String,
    updatedAt: String,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    // 简单显示 session id 前8位
    val displayId = if (sessionId.length > 8) sessionId.take(8) else sessionId
    // 格式化时间
    val timeStr = try {
        if (updatedAt.isNotEmpty()) {
            val dt = updatedAt.replace("T", " ").take(16)
            dt
        } else { "" }
    } catch (_: Exception) { updatedAt.take(16) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = displayId,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
                if (timeStr.isNotEmpty()) {
                    Text(
                        text = timeStr,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.6f)
                    )
                }
            }
            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(Icons.Default.MoreVert, "更多",
                        tint = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.6f))
                }
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("删除", color = MaterialTheme.colorScheme.error) },
                        onClick = { showMenu = false; onDelete() },
                        leadingIcon = {
                            Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error)
                        }
                    )
                }
            }
        }
    }
}
