package com.nexgo.iptv

import android.os.Bundle
import android.view.LayoutInflater
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.nexgo.iptv.data.PlaylistRepository
import com.nexgo.iptv.model.Channel
import com.nexgo.iptv.ui.theme.NexGoTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            NexGoTheme {
                NexGoApp()
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NexGoApp() {
    val context = LocalContext.current
    val repository = remember { PlaylistRepository(context) }
    val scope = rememberCoroutineScope()

    var channels by remember { mutableStateOf<List<Channel>>(emptyList()) }
    var selectedChannel by remember { mutableStateOf<Channel?>(null) }
    var selectedGroup by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showAddPlaylistDialog by remember { mutableStateOf(false) }
    var playlistUrlField by remember { mutableStateOf("") }
    var isFullscreen by remember { mutableStateOf(false) }

    val activity = context as? ComponentActivity
    LaunchedEffect(isFullscreen) {
        val window = activity?.window ?: return@LaunchedEffect
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (isFullscreen) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    BackHandler(enabled = isFullscreen) { isFullscreen = false }

    LaunchedEffect(Unit) {
        val savedUrl = repository.getSavedPlaylistUrl()
        if (!savedUrl.isNullOrBlank()) {
            playlistUrlField = savedUrl
            isLoading = true
            try {
                val loaded = repository.loadChannels(savedUrl)
                channels = loaded
                selectedChannel = loaded.firstOrNull()
                selectedGroup = loaded.firstOrNull()?.group
            } catch (e: Exception) {
                errorMessage = e.message
            } finally {
                isLoading = false
            }
        } else {
            showAddPlaylistDialog = true
        }
    }

    fun loadPlaylist(url: String) {
        scope.launch {
            isLoading = true
            errorMessage = null
            try {
                val loaded = repository.loadChannels(url)
                channels = loaded
                selectedChannel = loaded.firstOrNull()
                selectedGroup = loaded.firstOrNull()?.group
                repository.savePlaylistUrl(url)
                showAddPlaylistDialog = false
            } catch (e: Exception) {
                errorMessage = "No se pudo cargar la lista. Revisa la URL o tu conexión."
            } finally {
                isLoading = false
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0B1220))
    ) {
        if (isFullscreen && selectedChannel != null) {
            Box(modifier = Modifier.fillMaxSize()) {
                VideoPlayer(streamUrl = selectedChannel!!.streamUrl)
            }
        } else {
            Column(modifier = Modifier.fillMaxSize()) {
                TopBar(onAddPlaylistClick = { showAddPlaylistDialog = true })

                val groups = remember(channels) { channels.map { it.group }.distinct() }
                if (groups.isNotEmpty()) {
                    CategoryTabs(
                        groups = groups,
                        selected = selectedGroup,
                        onSelect = { selectedGroup = it }
                    )
                }

                Row(modifier = Modifier.fillMaxSize()) {
                    ChannelList(
                        channels = channels.filter { selectedGroup == null || it.group == selectedGroup },
                        selectedChannel = selectedChannel,
                        onChannelSelected = { selectedChannel = it },
                        onChannelDoubleClick = { channel ->
                            selectedChannel = channel
                            isFullscreen = true
                        },
                        modifier = Modifier
                            .width(320.dp)
                            .fillMaxHeight()
                    )

                    PlayerPanel(
                        channel = selectedChannel,
                        isLoading = isLoading,
                        errorMessage = errorMessage,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                    )
                }
            }
        }
    }

    if (showAddPlaylistDialog) {
        AddPlaylistDialog(
            urlValue = playlistUrlField,
            onUrlChange = { playlistUrlField = it },
            onConfirm = { loadPlaylist(playlistUrlField) },
            onDismiss = { if (channels.isNotEmpty()) showAddPlaylistDialog = false }
        )
    }
}

@Composable
private fun TopBar(onAddPlaylistClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF131C2E))
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = "NexGo",
            color = Color(0xFF2FD3E0),
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold
        )
        TextButton(onClick = onAddPlaylistClick) {
            Text(stringResource(R.string.add_playlist), color = Color(0xFFF5F7FA))
        }
    }
}

@Composable
private fun CategoryTabs(
    groups: List<String>,
    selected: String?,
    onSelect: (String) -> Unit
) {
    ScrollableTabRow(
        selectedTabIndex = groups.indexOf(selected).coerceAtLeast(0),
        containerColor = Color(0xFF0B1220),
        contentColor = Color(0xFF2FD3E0),
        edgePadding = 16.dp
    ) {
        groups.forEach { group ->
            Tab(
                selected = group == selected,
                onClick = { onSelect(group) },
                text = { Text(group) }
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChannelList(
    channels: List<Channel>,
    selectedChannel: Channel?,
    onChannelSelected: (Channel) -> Unit,
    onChannelDoubleClick: (Channel) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.background(Color(0xFF0B1220))) {
        Text(
            text = stringResource(R.string.channels_title),
            color = Color(0xFF93A1B5),
            modifier = Modifier.padding(16.dp)
        )
        if (channels.isEmpty()) {
            Text(
                text = stringResource(R.string.no_channels),
                color = Color(0xFF93A1B5),
                modifier = Modifier.padding(16.dp)
            )
        } else {
            LazyColumn {
                items(channels, key = { it.id }) { channel ->
                    ChannelRow(
                        channel = channel,
                        isSelected = channel.id == selectedChannel?.id,
                        onClick = { onChannelSelected(channel) },
                        onDoubleClick = { onChannelDoubleClick(channel) }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChannelRow(
    channel: Channel,
    isSelected: Boolean,
    onClick: () -> Unit,
    onDoubleClick: () -> Unit
) {
    val background = if (isSelected) Color(0xFF2FD3E0).copy(alpha = 0.18f) else Color.Transparent
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(background)
            .combinedClickable(onClick = onClick, onDoubleClick = onDoubleClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF131C2E)),
            contentAlignment = Alignment.Center
        ) {
            Text(channel.name.take(1), color = Color(0xFF2FD3E0), fontWeight = FontWeight.Bold)
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(channel.name, color = Color(0xFFF5F7FA), fontWeight = FontWeight.Medium)
            Text(channel.group, color = Color(0xFF93A1B5), fontSize = 12.sp)
        }
    }
}

@Composable
private fun PlayerPanel(
    channel: Channel?,
    isLoading: Boolean,
    errorMessage: String?,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.padding(16.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0xFF131C2E)),
            contentAlignment = Alignment.Center
        ) {
            when {
                isLoading -> Text(stringResource(R.string.loading), color = Color(0xFF93A1B5))
                errorMessage != null -> Text(errorMessage, color = Color(0xFFFF6B6B))
                channel != null -> VideoPlayer(streamUrl = channel.streamUrl)
                else -> Text(stringResource(R.string.no_channels), color = Color(0xFF93A1B5))
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(channel?.name ?: "", color = Color(0xFFF5F7FA), fontWeight = FontWeight.Bold, fontSize = 18.sp)
                if (channel != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color(0xFFE03A3A))
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text(stringResource(R.string.live), color = Color.White, fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VideoPlayer(streamUrl: String) {
    val context = LocalContext.current
    val exoPlayer = remember(streamUrl) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(streamUrl))
            prepare()
            playWhenReady = true
        }
    }

    DisposableEffect(exoPlayer) {
        onDispose { exoPlayer.release() }
    }

    AndroidView(
        factory = { ctx ->
            val playerView = LayoutInflater.from(ctx).inflate(R.layout.exo_player_view, null) as PlayerView
            playerView.player = exoPlayer
            playerView
        },
        update = { playerView ->
            playerView.player = exoPlayer
        },
        modifier = Modifier.fillMaxSize()
    )
}

@Composable
private fun AddPlaylistDialog(
    urlValue: String,
    onUrlChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add_playlist)) },
        text = {
            OutlinedTextField(
                value = urlValue,
                onValueChange = onUrlChange,
                label = { Text(stringResource(R.string.playlist_url_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.load)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar") }
        }
    )
}
