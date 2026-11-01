package com.penflow.tablet.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.penflow.tablet.model.PacketConstants
import com.penflow.tablet.model.SettingsRepository
import com.penflow.tablet.model.TabletSettings
import com.penflow.tablet.network.ConnectionStatus
import com.penflow.tablet.network.UsbSocketManager
import com.penflow.tablet.ui.theme.*
import kotlinx.coroutines.delay

@Composable
fun TabletScreen(
    socketManager: UsbSocketManager,
    settings: TabletSettings,
    isHudVisible: Boolean = true,
    onHudVisibleChanged: (Boolean) -> Unit = {},
    onSettingsChanged: (TabletSettings) -> Unit
) {
    val context = LocalContext.current

    var showSettingsDialog by remember { mutableStateOf(false) }
    var isPositionCalibrationMode by remember { mutableStateOf(false) }
    var showHiddenHintBanner by remember { mutableStateOf(false) }

    // Live tool indicator (only updates when tool type actually switches)
    var currentTool by remember { mutableStateOf(PacketConstants.TOOL_STYLUS) }

    // Allow Android Back gesture / Back button to restore HUD when hidden
    BackHandler(enabled = !isHudVisible) {
        onHudVisibleChanged(true)
    }

    LaunchedEffect(isHudVisible) {
        if (!isHudVisible) {
            showHiddenHintBanner = true
            delay(2500)
            showHiddenHintBanner = false
        } else {
            showHiddenHintBanner = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
    ) {
        // High-Precision Hardware Stylus Canvas
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                StylusCaptureView(ctx).apply {
                    this.socketManager = socketManager
                    this.settings = settings
                    this.isHudVisibleState = isHudVisible
                    this.onRequestShowHud = {
                        onHudVisibleChanged(true)
                    }
                    this.onStylusTelemetry = { _, _, _, tool, _ ->
                        currentTool = tool
                    }
                    this.onAreaPositionChanged = { newX, newY ->
                        onSettingsChanged(settings.copy(
                            tabletArea = settings.tabletArea.copy(
                                offsetXPercent = newX,
                                offsetYPercent = newY
                            )
                        ))
                    }
                }
            },
            update = { view ->
                view.settings = settings
                view.socketManager = socketManager
                view.isHudVisibleState = isHudVisible
                view.onRequestShowHud = {
                    onHudVisibleChanged(true)
                }
                view.isCalibratingPosition = isPositionCalibrationMode
                view.onAreaPositionChanged = { newX, newY ->
                    onSettingsChanged(settings.copy(
                        tabletArea = settings.tabletArea.copy(
                            offsetXPercent = newX,
                            offsetYPercent = newY
                        )
                    ))
                }
            }
        )

        // Floating HUD Status Bar (Isolated in its own Composable so 1Hz telemetry never recomposes AndroidView)
        AnimatedVisibility(
            visible = isHudVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(top = 12.dp, start = 12.dp, end = 12.dp)
        ) {
            HudStatusBar(
                socketManager = socketManager,
                currentTool = currentTool,
                onOpenSettings = { showSettingsDialog = true },
                onHideHud = { onHudVisibleChanged(false) }
            )
        }

        // Brief 2.5s non-interactive hint when HUD is hidden (completely disappears so nothing remains on screen!)
        AnimatedVisibility(
            visible = showHiddenHintBanner && !isHudVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 14.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = SurfaceDark.copy(alpha = 0.85f),
                border = BorderStroke(1.dp, AccentCyan.copy(alpha = 0.5f))
            ) {
                Text(
                    text = "HUD Hidden • Double-tap top-left corner with finger or press Back / Volume to show",
                    color = TextPrimary,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                )
            }
        }

        // Full-screen Drag Calibration Top Bar (Appears when user chooses to freely drag on full screen)
        AnimatedVisibility(
            visible = isPositionCalibrationMode,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 18.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = SurfaceDark.copy(alpha = 0.96f),
                border = BorderStroke(2.dp, AccentCyan),
                shadowElevation = 10.dp
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = "🖐 Drag the active box anywhere to suit your hand position",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Button(
                        onClick = {
                            isPositionCalibrationMode = false
                            SettingsRepository.save(context, settings)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = StatusGreen),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = "Save & Lock Position ✓",
                            color = Color.Black,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }

        // Settings Dialog
        if (showSettingsDialog) {
            SettingsDialog(
                settings = settings,
                onSettingsChanged = onSettingsChanged,
                onStartFullScreenCalibration = {
                    showSettingsDialog = false
                    isPositionCalibrationMode = true
                },
                onDismiss = { showSettingsDialog = false }
            )
        }
    }
}

@Composable
private fun HudStatusBar(
    socketManager: UsbSocketManager,
    currentTool: Byte,
    onOpenSettings: () -> Unit,
    onHideHud: () -> Unit
) {
    val connectionStatus by socketManager.connectionStatus.collectAsState()
    val samplingRateHz by socketManager.samplingRateHz.collectAsState()
    val latencyMs by socketManager.latencyMs.collectAsState()

    Surface(
        shape = RoundedCornerShape(24.dp),
        color = SurfaceDark.copy(alpha = 0.92f),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, SurfaceVariantDark, RoundedCornerShape(24.dp))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Left: Connection Status Indicator Dot + Label
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                val statusColor = when (connectionStatus) {
                    ConnectionStatus.CONNECTED -> StatusGreen
                    ConnectionStatus.CONNECTING -> StatusOrange
                    ConnectionStatus.DISCONNECTED -> StatusRed
                }
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(statusColor)
                )
                Text(
                    text = when (connectionStatus) {
                        ConnectionStatus.CONNECTED -> "USB LIVE"
                        ConnectionStatus.CONNECTING -> "CONNECTING"
                        ConnectionStatus.DISCONNECTED -> "OFFLINE"
                    },
                    color = statusColor,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            }

            // Middle: Rate & Ping & Tool
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "${samplingRateHz}Hz",
                    color = AccentCyan,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = "${latencyMs}ms",
                    color = if (latencyMs < 5) StatusGreen else StatusOrange,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
                val toolLabel = when (currentTool) {
                    PacketConstants.TOOL_ERASER -> "ERASER"
                    PacketConstants.TOOL_STYLUS -> "PEN"
                    else -> "TOUCH"
                }
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = if (currentTool == PacketConstants.TOOL_ERASER) StatusOrange.copy(alpha = 0.2f) else AccentCyan.copy(alpha = 0.2f)
                ) {
                    Text(
                        text = toolLabel,
                        color = if (currentTool == PacketConstants.TOOL_ERASER) StatusOrange else AccentCyan,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            // Right: Settings & Hide Buttons
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                IconButton(
                    onClick = onOpenSettings,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Settings",
                        tint = TextPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                }
                IconButton(
                    onClick = onHideHud,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.VisibilityOff,
                        contentDescription = "Hide HUD",
                        tint = TextSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}
