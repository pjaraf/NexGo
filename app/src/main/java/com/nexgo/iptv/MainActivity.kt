package com.nexgo.iptv

import android.os.Bundle
import android.view.LayoutInflater
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
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
import com.nexgo.iptv.data.XtreamCredentials
import com.nexgo.iptv.data.XtreamRepository
import com.nexgo.iptv.model.Channel
import com.nexgo.iptv.ui.theme.NexGoTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installCrashLogger(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            NexGoTheme {
                NexGoApp()
            }
        }
    }
}

private fun crashLogFile(context: android.content.Context) = File(context.filesDir, "nexgo_last_crash.txt")

/**
 * Guarda cualquier error no controlado en un archivo interno en vez de dejar que
 * el sistema simplemente cierre la app sin explicación. La próxima vez que se
 * abra la app, se muestra ese error en pantalla para poder diagnosticarlo.
 */
private fun installCrashLogger(context: android.content.Context) {
    val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
        try {
            val sw = StringWriter()
            throwable.printStackTrace(PrintWriter(sw))
            crashLogFile(context).writeText(sw.toString())
        } catch (_: Exception) {
            // Si ni siquiera se puede guardar el log, no hay más remedio
        }
        previousHandler?.uncaughtException(thread, throwable)
    }
}

private enum class LoginMode { XTREAM, M3U }

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NexGoApp() {
    val context = LocalContext.current
    val m3uRepository = remember { PlaylistRepository(context) }
    val xtreamRepository = remember { XtreamRepository(context) }
    val scope = rememberCoroutineScope()

    var channels by remember { mutableStateOf<List<Channel>>(emptyList()) }
    var selectedChannel by remember { mutableStateOf<Channel?>(null) }
    var selectedGroup by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showAddPlaylistDialog by remember { mutableStateOf(false) }
    var isFullscreen by remember { mutableStateOf(false) }
    var lastCrashText by remember {
        mutableStateOf(
            crashLogFile(context).let { file -> if (file.exists()) file.readText() else null }
        )
    }

    // Campos del formulario de conexión
    var loginMode by remember { mutableStateOf(LoginMode.XTREAM) }
    var serverUrlField by remember { mutableStateOf("") }
    var usernameField by remember { mutableStateOf("") }
    var passwordField by remember { mutableStateOf("") }
    var m3uUrlField by remember { mutableStateOf("") }

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

    val exceptionHandler = remember {
        kotlinx.coroutines.CoroutineExceptionHandler { _, throwable ->
            errorMessage = "Error de conexión: ${throwable.message ?: throwable.javaClass.simpleName}"
            isLoading = false
        }
    }

    fun loadWithXtream(credentials: XtreamCredentials, save: Boolean) {
        scope.launch(exceptionHandler) {
            isLoading = true
            errorMessage = null
            try {
                val loaded = xtreamRepository.loadChannels(credentials)
                channels = loaded
                selectedChannel = loaded.firstOrNull()
                selectedGroup = loaded.firstOrNull()?.group
                if (save) xtreamRepository.saveCredentials(credentials)
                showAddPlaylistDialog = false
            } catch (e: Exception) {
                errorMessage = e.message ?: "No se pudo conectar. Revisa servidor, usuario y contraseña."
            } finally {
                isLoading = false
            }
        }
    }

    fun loadWithM3U(url: String, save: Boolean) {
        scope.launch(exceptionHandler) {
            isLoading = true
            errorMessage = null
            try {
                val loaded = m3uRepository.loadChannels(url)
                channels = loaded
                selectedChannel = loaded.firstOrNull()
                selectedGroup = loaded.firstOrNull()?.group
                if (save) m3uRepository.savePlaylistUrl(url)
                showAddPlaylistDialog = false
            } catch (e: Exception) {
                errorMessage = "No se pudo cargar la lista. Revisa la URL o tu conexión."
            } finally {
                isLoading = false
            }
        }
    }

    // Al iniciar, intenta reconectar con lo último guardado (primero Xtream, luego M3U)
    LaunchedEffect(Unit) {
        val savedXtream = xtreamRepository.getSavedCredentials()
        val savedM3u = m3uRepository.getSavedPlaylistUrl()
        when {
            savedXtream != null -> {
                loginMode = LoginMode.XTREAM
                serverUrlField = savedXtream.serverUrl
                usernameField = savedXtream.username
                passwordField = savedXtream.password
                loadWithXtream(savedXtream, save = false)
            }
            !savedM3u.isNullOrBlank() -> {
                loginMode = LoginMode.M3U
                m3uUrlField = savedM3u
                loadWithM3U(savedM3u, save = false)
            }
            else -> {
                showAddPlaylistDialog = true
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

    if (lastCrashText != null) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("La app se cerró la última vez") },
            text = {
                Column {
                    Text(
                        "Este es el error exacto. Puedes tomar captura de pantalla y compartirlo:",
                        color = Color(0xFF93A1B5),
                        fontSize = 13.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        lastCrashText ?: "",
                        color = Color(0xFFFF6B6B),
                        fontSize = 11.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 320.dp)
                            .verticalScroll(rememberScrollState())
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    crashLogFile(context).delete()
                    lastCrashText = null
                }) { Text("Entendido") }
            }
        )
    }

    if (showAddPlaylistDialog) {
        AddPlaylistDialog(
            mode = loginMode,
            onModeChange = { loginMode = it },
            serverUrl = serverUrlField,
            onServerUrlChange = { serverUrlField = it },
            username = usernameField,
            onUsernameChange = { usernameField = it },
            password = passwordField,
            onPasswordChange = { passwordField = it },
            m3uUrl = m3uUrlField,
            onM3uUrlChange = { m3uUrlField = it },
            isLoading = isLoading,
            errorMessage = errorMessage,
            onConfirm = {
                when (loginMode) {
                    LoginMode.XTREAM -> loadWithXtream(
                        XtreamCredentials(serverUrlField, usernameField, passwordField),
                        save = true
                    )
                    LoginMode.M3U -> loadWithM3U(m3uUrlField, save = true)
                }
            },
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
            Text("Conectar cuenta", color = Color(0xFFF5F7FA))
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
    var playerError by remember(streamUrl) { mutableStateOf<String?>(null) }

    val exoPlayer = remember(streamUrl) {
        try {
            ExoPlayer.Builder(context).build().apply {
                addListener(object : androidx.media3.common.Player.Listener {
                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                        playerError = "No se pudo reproducir este canal (${error.errorCodeName})."
                    }
                })
                setMediaItem(MediaItem.fromUri(streamUrl))
                prepare()
                playWhenReady = true
            }
        } catch (e: Exception) {
            playerError = "No se pudo iniciar el reproductor: ${e.message}"
            null
        }
    }

    DisposableEffect(exoPlayer) {
        onDispose { exoPlayer?.release() }
    }

    if (playerError != null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(playerError ?: "", color = Color(0xFFFF6B6B), modifier = Modifier.padding(16.dp))
        }
        return
    }

    if (exoPlayer == null) return

    AndroidView(
        factory = { ctx ->
            try {
                val playerView = LayoutInflater.from(ctx)
                    .inflate(R.layout.exo_player_view, null) as PlayerView
                playerView.player = exoPlayer
                playerView
            } catch (e: Exception) {
                // Respaldo si el layout no cargó bien: reproductor básico sin ese XML,
                // así la app no se cierra aunque el video se vea sin recorte de esquinas.
                PlayerView(ctx).apply {
                    player = exoPlayer
                    useController = true
                }
            }
        },
        update = { playerView ->
            playerView.player = exoPlayer
        },
        modifier = Modifier.fillMaxSize()
    )
}

@Composable
private fun AddPlaylistDialog(
    mode: LoginMode,
    onModeChange: (LoginMode) -> Unit,
    serverUrl: String,
    onServerUrlChange: (String) -> Unit,
    username: String,
    onUsernameChange: (String) -> Unit,
    password: String,
    onPasswordChange: (String) -> Unit,
    m3uUrl: String,
    onM3uUrlChange: (String) -> Unit,
    isLoading: Boolean,
    errorMessage: String?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Conectar cuenta IPTV") },
        text = {
            Column {
                Row(modifier = Modifier.fillMaxWidth()) {
                    FilterChip(
                        selected = mode == LoginMode.XTREAM,
                        onClick = { onModeChange(LoginMode.XTREAM) },
                        label = { Text("Usuario y contraseña") }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    FilterChip(
                        selected = mode == LoginMode.M3U,
                        onClick = { onModeChange(LoginMode.M3U) },
                        label = { Text("Lista M3U") }
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                if (mode == LoginMode.XTREAM) {
                    OutlinedTextField(
                        value = serverUrl,
                        onValueChange = onServerUrlChange,
                        label = { Text("URL del servidor (ej: http://servidor.com:8080)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = username,
                        onValueChange = onUsernameChange,
                        label = { Text("Usuario") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = password,
                        onValueChange = onPasswordChange,
                        label = { Text("Contraseña") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    OutlinedTextField(
                        value = m3uUrl,
                        onValueChange = onM3uUrlChange,
                        label = { Text(stringResource(R.string.playlist_url_hint)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                if (errorMessage != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(errorMessage, color = Color(0xFFFF6B6B), fontSize = 13.sp)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !isLoading) {
                Text(if (isLoading) "Conectando…" else "Conectar")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar") }
        }
    )
}
