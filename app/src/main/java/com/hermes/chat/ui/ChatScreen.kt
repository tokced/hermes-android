package com.hermes.chat.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
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
                if (sessions.isEmpty()) {
                    Box(modifier = Modifier.fillMaxWidth().height(100.dp), contentAlignment = Alignment.Center) {
                        Text("暂无会话", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.heightIn(max = 400.dp)
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
                        val clip = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clip.setPrimaryClip(ClipData.newPlainText("error", error))
                        Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
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
            Card(colors = CardDefaults.cardColors(containerColor = backgroundColor), modifier = Modifier.widthIn(max = 280.dp)) {
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
