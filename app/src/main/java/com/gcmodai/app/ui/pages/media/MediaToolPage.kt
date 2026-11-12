package com.gcmodai.app.ui.pages.media

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.gcmodai.app.R
import com.gcmodai.app.data.files.FileFolders
import com.gcmodai.app.data.files.FilesManager
import com.gcmodai.app.data.media.MediaProbeClient
import com.gcmodai.app.ui.components.nav.BackButton
import com.gcmodai.app.ui.context.LocalToaster
import com.gcmodai.app.ui.theme.CustomColors
import com.gcmodai.app.utils.FileExporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Music
import me.rerere.hugeicons.stroke.MusicNote01
import me.rerere.hugeicons.stroke.PlayCircle
import me.rerere.hugeicons.stroke.Video01
import org.koin.compose.koinInject
import java.io.File

/**
 * 音视频工具页。
 *
 * 定位：把服务器 ffmpeg 的能力暴露给用户 —— 丢一个视频/音频进来，
 * 立刻告诉你它的真实参数（时长、编码、分辨率、帧率、声道、采样率），
 * 还能抽帧（变成图片丢给不吃视频的模型）和抽音轨（单声道 mp3）。
 *
 * 为什么不做本地 ffmpeg：静态 ffmpeg 单 ABI 就 30MB+，三个 ABI 会把刚瘦身过的
 * APK 直接吹大一倍。服务器那台已经装了 ffmpeg 7.1，解析全在那边做，App 零体积增长。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaToolPage(
    mediaClient: MediaProbeClient = koinInject(),
    filesManager: FilesManager = koinInject(),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    var fileName by remember { mutableStateOf<String?>(null) }
    var mediaBytes by remember { mutableStateOf<ByteArray?>(null) }
    var info by remember { mutableStateOf<MediaProbeClient.MediaInfo?>(null) }
    var frames by remember { mutableStateOf<List<MediaProbeClient.Frame>>(emptyList()) }
    var audioTrack by remember { mutableStateOf<MediaProbeClient.AudioTrack?>(null) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }

    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            status = "读取文件…"
            runCatching {
                val name = context.displayNameOf(uri) ?: "media"
                val bytes = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                } ?: error("读不到文件内容")
                fileName = name
                mediaBytes = bytes
                frames = emptyList()
                audioTrack = null
                status = "上传到服务器解析（${bytes.size / 1024} KB）…"
                val result = mediaClient.probe(bytes, name).getOrThrow()
                info = result
                status = ""
            }.onFailure {
                status = ""
                info = null
                toaster.show("解析失败：${it.message}")
            }
            busy = false
        }
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("音视频工具") },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            HeroPanel(
                onPick = { picker.launch(arrayOf("video/*", "audio/*")) },
                fileName = fileName,
                busy = busy,
            )

            if (status.isNotBlank()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text(
                        text = "  $status",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            info?.let { media ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text("解析结果", style = MaterialTheme.typography.titleMedium)
                        InfoRow("类型", if (media.kind == "video") "视频" else if (media.kind == "audio") "音频" else "未知")
                        InfoRow("容器", media.container.ifBlank { "-" })
                        InfoRow("时长", media.durationText.ifBlank { "${media.durationSeconds}s" })
                        InfoRow("大小", "${media.sizeBytes / 1024} KB")
                        InfoRow("总码率", "${media.bitRateKbps} kbps")
                        media.video?.let { v ->
                            InfoRow("画面", "${v.width}x${v.height} @ ${v.fps}fps")
                            InfoRow("视频编码", "${v.codec} ${v.profile} ${v.pixFmt}".trim())
                            if (v.bitRateKbps > 0) InfoRow("视频码率", "${v.bitRateKbps} kbps")
                        }
                        media.audio?.let { a ->
                            InfoRow("声音", "${a.codec} / ${a.channels} 声道 / ${a.sampleRate}Hz")
                            if (a.bitRateKbps > 0) InfoRow("音频码率", "${a.bitRateKbps} kbps")
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(
                        enabled = !busy && media.kind == "video",
                        onClick = {
                            val bytes = mediaBytes ?: return@FilledTonalButton
                            val name = fileName ?: "video"
                            scope.launch {
                                busy = true
                                status = "服务器抽帧中…"
                                mediaClient.frames(bytes, name, 4)
                                    .onSuccess {
                                        frames = it.frames
                                        toaster.show("抽到 ${it.frames.size} 帧")
                                    }
                                    .onFailure { toaster.show("抽帧失败：${it.message}") }
                                status = ""
                                busy = false
                            }
                        },
                    ) {
                        Icon(HugeIcons.PlayCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text("  抽 4 帧")
                    }
                    FilledTonalButton(
                        enabled = !busy,
                        onClick = {
                            val bytes = mediaBytes ?: return@FilledTonalButton
                            val name = fileName ?: "media"
                            scope.launch {
                                busy = true
                                status = "服务器抽取音轨中…"
                                mediaClient.audio(bytes, name)
                                    .onSuccess {
                                        audioTrack = it
                                        toaster.show("音轨 ${it.bytes / 1024} KB")
                                    }
                                    .onFailure { toaster.show("抽音轨失败：${it.message}") }
                                status = ""
                                busy = false
                            }
                        },
                    ) {
                        Icon(HugeIcons.MusicNote01, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text("  抽音轨")
                    }
                }
            }

            if (frames.isNotEmpty()) {
                Text("抽帧结果", style = MaterialTheme.typography.titleMedium)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(frames) { frame ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            AsyncImage(
                                model = "data:image/jpeg;base64," + frame.jpegBase64,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(160.dp)
                                    .clip(RoundedCornerShape(12.dp)),
                            )
                            Text(
                                text = "${frame.atSeconds}s",
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }
                Button(onClick = {
                    val media = frames
                    val baseName = (fileName ?: "video").substringBeforeLast('.')
                    scope.launch {
                        var saved = 0
                        media.forEachIndexed { index, frame ->
                            val ok = runCatching {
                                filesManager.saveManagedFromBytes(
                                    folder = FileFolders.UPLOAD,
                                    bytes = android.util.Base64.decode(
                                        frame.jpegBase64, android.util.Base64.DEFAULT
                                    ),
                                    displayName = "${baseName}_frame${index + 1}.jpg",
                                    mimeType = "image/jpeg",
                                )
                            }.isSuccess
                            if (ok) saved++
                        }
                        toaster.show("已保存 $saved 帧到「聊天文件」")
                    }
                }) { Text("保存全部帧到存储") }
            }

            audioTrack?.let { track ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    ),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text("音轨已抽取", style = MaterialTheme.typography.titleSmall)
                        InfoRow("格式", "${track.format} / ${track.bitrate} / ${track.sampleRate}Hz 单声道")
                        InfoRow("大小", "${track.bytes / 1024} KB")
                        Button(onClick = {
                            runCatching {
                                val file = File(context.cacheDir, "gcmod_audio.mp3")
                                file.writeBytes(android.util.Base64.decode(track.audioBase64, android.util.Base64.DEFAULT))
                                FileExporter.exportToDownloads(context, file, "gcmod_audio.mp3")
                                file.delete()
                            }.onSuccess { toaster.show("已保存到 下载/GcmodAi") }
                                .onFailure { toaster.show("保存失败：${it.message}") }
                        }) { Text("保存 mp3 到手机") }
                    }
                }
            }
        }
    }
}

@Composable
private fun HeroPanel(onPick: () -> Unit, fileName: String?, busy: Boolean) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(
                Brush.linearGradient(
                    listOf(
                        MaterialTheme.colorScheme.primaryContainer,
                        MaterialTheme.colorScheme.tertiaryContainer,
                    )
                )
            )
            .padding(20.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = HugeIcons.Video01,
                    contentDescription = null,
                    modifier = Modifier.size(28.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    text = "  服务器 ffmpeg 解析",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Text(
                text = "选视频或音频，立刻看真实参数；不吃视频的模型会自动抽帧成图片。手机端零 ffmpeg 体积。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            fileName?.let {
                Text(
                    text = "已选择：$it",
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Button(onClick = onPick, enabled = !busy) {
                Icon(HugeIcons.Music, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("  选择文件")
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(width = 76.dp, height = 18.dp),
        )
        Text(text = value, style = MaterialTheme.typography.bodySmall)
    }
}

private fun Context.displayNameOf(uri: Uri): String? = runCatching {
    contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
    }
}.getOrNull()
