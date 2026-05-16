package com.hermes.chat.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.hermes.chat.AppSettings
import com.hermes.chat.SettingsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val releaseNotes: String,
    val minVersionCode: Int,
    val isUpdateAvailable: Boolean
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val settingsManager = remember { SettingsManager(context) }
    val settings = remember { settingsManager.loadSettings() }

    var apiBaseUrl by remember { mutableStateOf(settings.apiBaseUrl) }
    var apiKey by remember { mutableStateOf(settings.apiKey) }
    var darkMode by remember { mutableStateOf(settings.darkMode) }
    var streamResponse by remember { mutableStateOf(settings.streamResponse) }
    var saveSessions by remember { mutableStateOf(settings.saveSessions) }

    var showApiKey by remember { mutableStateOf(false) }
    var saveSuccess by remember { mutableStateOf(false) }
    var testStatus by remember { mutableStateOf("idle") }
    var testMessage by remember { mutableStateOf("") }

    // Update checker state
    var checkUpdateStatus by remember { mutableStateOf("idle") } // idle, checking, available, no_update, error
    var updateInfo by remember { mutableStateOf<UpdateInfo?>(null) }
    var downloadProgress by remember { mutableStateOf(0) }
    var downloadStatus by remember { mutableStateOf("idle") } // idle, downloading, ready, error
    var pendingInstallFile by remember { mutableStateOf<File?>(null) } // 记录已下载待安装的 APK

    val scope = rememberCoroutineScope()

    // Get current version
    val versionCode = remember {
        try {
            val pkgInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pkgInfo.longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION")
                pkgInfo.versionCode
            }
        } catch (e: Exception) { 0 }
    }
    val versionName = remember {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
        } catch (e: Exception) { "?" }
    }

    // SSL client (same as before)
    val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun getAcceptedIssuers() = arrayOf<X509Certificate>()
    })
    val sslContext = remember {
        SSLContext.getInstance("TLS").apply { init(null, trustAllCerts, SecureRandom()) }
    }
    val client = remember {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
            .hostnameVerifier { _, _ -> true }
            .build()
    }

    // 专用于连接测试的短超时 client（3秒）
    val shortClient = remember {
        client.newBuilder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .build()
    }

    // Permission launcher for install - Android 8+ 需要跳转设置页面
    val installPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        // 用户从设置页面返回后，检查是否获得了权限
        pendingInstallFile?.let { file ->
            if (file.exists()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    if (context.packageManager.canRequestPackageInstalls()) {
                        installApk(context, file)
                    } else {
                        Toast.makeText(context, "未获得安装权限", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    installApk(context, file)
                }
            }
        }
    }

    fun requestInstallPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = Uri.parse("package:${context.packageName}")
            }
            installPermissionLauncher.launch(intent)
        }
    }

    // File picker for manual APK (fallback)
    val filePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let { installFromUri(context, it) }
    }

    fun checkForUpdate() {
        if (apiBaseUrl.isBlank()) {
            Toast.makeText(context, "请先配置 API 地址", Toast.LENGTH_SHORT).show()
            return
        }
        checkUpdateStatus = "checking"
        updateInfo = null
        scope.launch {
            try {
                // 通过 Gitee API 列出 /apk/ 目录，从文件名解析版本号
                val giteeApiUrl = "https://gitee.com/api/v5/repos/tokce/hermes-android/contents/apk?ref=master"
                val request = Request.Builder()
                    .url(giteeApiUrl)
                    .get()
                    .build()
                val resp = withContext(Dispatchers.IO) {
                    client.newCall(request).execute()
                }
                if (resp.isSuccessful) {
                    val body = resp.body?.string() ?: throw Exception("空响应")
                    val files = org.json.JSONArray(body)
                    var latestVersionCode = 0
                    var latestVersionName = ""
                    var latestFileName = ""
                    for (i in 0 until files.length()) {
                        val file = files.getJSONObject(i)
                        val name = file.getString("name")
                        // 文件名格式: hermes-X.XX.apk
                        val match = Regex("hermes-(\\d+)\\.(\\d+)\\.apk").find(name)
                        if (match != null) {
                            val code = match.groupValues[1].toInt() * 100 + match.groupValues[2].toInt()
                            if (code > latestVersionCode) {
                                latestVersionCode = code
                                // 还原 versionName 格式 X.XX
                                latestVersionName = "${match.groupValues[1]}.${match.groupValues[2]}"
                                latestFileName = name
                            }
                        }
                    }
                    if (latestVersionCode == 0) {
                        checkUpdateStatus = "error"
                        return@launch
                    }
                    val isUpdateAvailable = latestVersionCode > versionCode
                    // APK 下载地址: raw URL
                    val apkUrl = "https://gitee.com/tokce/hermes-android/raw/master/apk/${latestFileName}"
                    val info = UpdateInfo(
                        versionCode = latestVersionCode,
                        versionName = latestVersionName,
                        apkUrl = apkUrl,
                        releaseNotes = "新版本 v${latestVersionName}",
                        minVersionCode = 0,
                        isUpdateAvailable = isUpdateAvailable
                    )
                    updateInfo = info
                    checkUpdateStatus = if (info.isUpdateAvailable) "available" else "no_update"
                } else {
                    checkUpdateStatus = "error"
                }
            } catch (e: Exception) {
                checkUpdateStatus = "error"
            }
        }
    }

    fun downloadAndInstall() {
        val info = updateInfo ?: return
        if (apiBaseUrl.isBlank()) return

        val apkFile = File(File(context.cacheDir, "updates"), "hermes-${info.versionName}.apk")

        // 如果已经下载完成且待安装，直接触发安装流程
        if (apkFile.exists() && downloadStatus == "ready") {
            pendingInstallFile = apkFile
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (context.packageManager.canRequestPackageInstalls()) {
                    installApk(context, apkFile)
                } else {
                    requestInstallPermission()
                }
            } else {
                installApk(context, apkFile)
            }
            return
        }

        // 已经下载中或已完成（但不是 ready 状态），忽略
        if (downloadStatus == "downloading") return

        downloadStatus = "downloading"
        downloadProgress = 0

        scope.launch {
            try {
                val updateDir = File(context.cacheDir, "updates")
                updateDir.mkdirs()

                val downloadUrl = if (info.apkUrl.startsWith("/")) {
                    "${apiBaseUrl.trim()}${info.apkUrl}"
                } else {
                    info.apkUrl
                }
                val request = Request.Builder()
                    .url(downloadUrl)
                    .apply {
                        // 仅当下载路径为相对路径（走 Bridge）时才加 API Key
                        if (info.apkUrl.startsWith("/")) {
                            addHeader("x-api-key", apiKey.trim())
                        }
                    }
                    .get()
                    .build()

                withContext(Dispatchers.IO) {
                    val response = client.newCall(request).execute()
                    if (!response.isSuccessful) throw Exception("下载失败: ${response.code}")

                    val body = response.body ?: throw Exception("空响应")
                    val totalBytes = body.contentLength()
                    var downloadedBytes = 0L

                    apkFile.outputStream().use { fos ->
                        body.byteStream().use { fis ->
                            val buffer = ByteArray(8192)
                            var bytesRead: Int
                            while (fis.read(buffer).also { bytesRead = it } != -1) {
                                fos.write(buffer, 0, bytesRead)
                                downloadedBytes += bytesRead
                                if (totalBytes > 0) {
                                    downloadProgress = ((downloadedBytes * 100) / totalBytes).toInt()
                                }
                            }
                        }
                    }
                }

                downloadStatus = "ready"
                downloadProgress = 100
                pendingInstallFile = apkFile

                // Trigger install
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    if (context.packageManager.canRequestPackageInstalls()) {
                        installApk(context, apkFile)
                    } else {
                        requestInstallPermission()
                    }
                } else {
                    installApk(context, apkFile)
                }
            } catch (e: Exception) {
                downloadStatus = "error"
                Toast.makeText(context, "下载失败: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // API 设置区
            Text(
                text = "API 连接",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )

            OutlinedTextField(
                value = apiBaseUrl,
                onValueChange = { apiBaseUrl = it },
                label = { Text("API 地址") },
                placeholder = { Text("http://127.0.0.1:8000") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                leadingIcon = {
                    Icon(Icons.Default.Link, contentDescription = null)
                }
            )

            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                label = { Text("API Key") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                leadingIcon = {
                    Icon(Icons.Default.Key, contentDescription = null)
                },
                trailingIcon = {
                    IconButton(onClick = { showApiKey = !showApiKey }) {
                        Icon(
                            if (showApiKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = if (showApiKey) "隐藏" else "显示"
                        )
                    }
                },
                visualTransformation = if (showApiKey) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
            )

            // 打开时自动测试一次连接
            LaunchedEffect(apiBaseUrl) {
                if (apiBaseUrl.isBlank()) return@LaunchedEffect
                testStatus = "testing"
                testMessage = ""
                try {
                    val url = "${apiBaseUrl.trim()}/ping"
                    val request = Request.Builder()
                        .url(url)
                        .get()
                        .build()
                    val resp = withContext(Dispatchers.IO) {
                        shortClient.newCall(request).execute()
                    }
                    if (resp.isSuccessful) {
                        testStatus = "success"
                        testMessage = "连接成功 (${resp.code})"
                    } else {
                        testStatus = "error"
                        testMessage = "连接失败: ${resp.code}"
                    }
                } catch (e: Exception) {
                    testStatus = "error"
                    testMessage = "连接失败: ${e.message}"
                }
            }

            // 连接测试（手动重试）
            OutlinedButton(
                onClick = {
                    scope.launch {
                        testStatus = "testing"
                        testMessage = ""
                        try {
                            val url = "${apiBaseUrl.trim()}/ping"
                            val request = Request.Builder()
                                .url(url)
                                .get()
                                .build()
                            val resp = withContext(Dispatchers.IO) {
                                shortClient.newCall(request).execute()
                            }
                            if (resp.isSuccessful) {
                                testStatus = "success"
                                testMessage = "连接成功 (${resp.code})"
                            } else {
                                testStatus = "error"
                                testMessage = "连接失败: ${resp.code}"
                            }
                        } catch (e: Exception) {
                            testStatus = "error"
                            testMessage = "连接失败: ${e.message}"
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = apiBaseUrl.isNotBlank()
            ) {
                if (testStatus == "testing") {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.NetworkCheck, contentDescription = null)
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    when (testStatus) {
                        "success" -> "连接成功"
                        "error" -> "重试连接"
                        else -> "测试连接"
                    }
                )
            }

            if (testMessage.isNotEmpty()) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (testStatus == "success")
                            MaterialTheme.colorScheme.primaryContainer
                        else
                            MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Text(
                        text = testMessage,
                        modifier = Modifier.padding(12.dp),
                        color = if (testStatus == "success")
                            MaterialTheme.colorScheme.onPrimaryContainer
                        else
                            MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }

            HorizontalDivider()

            // 功能设置区
            Text(
                text = "功能",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("流式响应", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "实时显示 AI 回复（打字效果）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = streamResponse,
                    onCheckedChange = { streamResponse = it }
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("保存会话", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "退出后保留聊天历史",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = saveSessions,
                    onCheckedChange = { saveSessions = it }
                )
            }

            HorizontalDivider()

            // 软件更新区
            Text(
                text = "软件更新",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )

            // 当前版本
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("当前版本", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("v$versionName ($versionCode)",
                            style = MaterialTheme.typography.bodyLarge)
                    }
                    Icon(
                        Icons.Default.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // 检查更新按钮
            OutlinedButton(
                onClick = { checkForUpdate() },
                modifier = Modifier.fillMaxWidth(),
                enabled = checkUpdateStatus != "checking" && apiBaseUrl.isNotBlank()
            ) {
                if (checkUpdateStatus == "checking") {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.Update, contentDescription = null)
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    when (checkUpdateStatus) {
                        "checking" -> "检查中..."
                        else -> "检查更新"
                    }
                )
            }

            // 更新状态卡片
            when (checkUpdateStatus) {
                "available" -> {
                    updateInfo?.let { info ->
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer
                            )
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Default.SystemUpdate,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        "发现新版本 v${info.versionName}",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    info.releaseNotes,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                                Spacer(Modifier.height(12.dp))

                                // 下载进度
                                if (downloadStatus == "downloading") {
                                    LinearProgressIndicator(
                                        progress = { downloadProgress / 100f },
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        "下载中 $downloadProgress%",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                } else if (downloadStatus == "ready") {
                                    Button(
                                        onClick = { downloadAndInstall() },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Icon(Icons.Default.Update, contentDescription = null)
                                        Spacer(Modifier.width(8.dp))
                                        Text("安装更新")
                                    }
                                } else {
                                    Button(
                                        onClick = { downloadAndInstall() },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Icon(Icons.Default.Download, contentDescription = null)
                                        Spacer(Modifier.width(8.dp))
                                        Text("下载并安装")
                                    }
                                }

                                Spacer(Modifier.height(8.dp))
                                TextButton(
                                    onClick = { checkUpdateStatus = "idle"; updateInfo = null },
                                    modifier = Modifier.align(Alignment.End)
                                ) {
                                    Text("稍后")
                                }
                            }
                        }
                    }
                }
                "no_update" -> {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("已是最新版本 (v$versionName)")
                        }
                    }
                }
                "error" -> {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Error,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("检查更新失败，请检查网络和 API 配置")
                        }
                    }
                }
            }

            HorizontalDivider()

            // 外观设置区
            Text(
                text = "外观",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("深色模式", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "切换深色/浅色主题",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = darkMode,
                    onCheckedChange = { darkMode = it }
                )
            }

            HorizontalDivider()

            // 保存按钮
            Button(
                onClick = {
                    val newSettings = AppSettings(
                        apiBaseUrl = apiBaseUrl.trim(),
                        apiKey = apiKey.trim(),
                        darkMode = darkMode,
                        streamResponse = streamResponse,
                        saveSessions = saveSessions
                    )
                    settingsManager.saveSettings(newSettings)
                    saveSuccess = true
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Save, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("保存设置")
            }

            // 保存成功提示
            if (saveSuccess) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("设置已保存")
                    }
                }
            }

            Spacer(Modifier.height(32.dp))

            // 版本信息
            Text(
                text = "Hermes Chat v$versionName",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
        }
    }
}

private fun installApk(context: Context, apkFile: File) {
    try {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        Toast.makeText(context, "安装失败: ${e.message}", Toast.LENGTH_LONG).show()
    }
}

private fun installFromUri(context: Context, uri: Uri) {
    try {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        Toast.makeText(context, "安装失败: ${e.message}", Toast.LENGTH_LONG).show()
    }
}
