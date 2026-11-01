package com.penflow.tablet.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.penflow.tablet.model.*
import com.penflow.tablet.ui.theme.*
import java.util.Locale

@Composable
fun SettingsDialog(
    settings: TabletSettings,
    onSettingsChanged: (TabletSettings) -> Unit,
    onStartFullScreenCalibration: () -> Unit = {},
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.90f)
                .clip(RoundedCornerShape(20.dp)),
            color = SurfaceDark
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "PenFlow Tablet Settings",
                        color = AccentCyan,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = TextSecondary
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // ==================== ACTIVE TABLET AREA (OSU! MODE) ====================
                Text(
                    text = "Active Tablet Area (osu! / Custom Area)",
                    color = AccentCyan,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Shrinks the active pen area for fast wrist aiming in osu!. Type exact dimensions or use presets.",
                    color = TextSecondary,
                    fontSize = 12.sp
                )
                Spacer(modifier = Modifier.height(12.dp))

                // Context display metrics for real millimeter calculations (full physical display size)
                val context = LocalContext.current
                val displayMetrics = context.resources.displayMetrics
                val windowManager = remember(context) {
                    context.getSystemService(android.content.Context.WINDOW_SERVICE) as? android.view.WindowManager
                }
                val (screenWidthPx, screenHeightPx) = remember(windowManager, displayMetrics) {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R && windowManager != null) {
                        val bounds = windowManager.currentWindowMetrics.bounds
                        bounds.width() to bounds.height()
                    } else {
                        displayMetrics.widthPixels to displayMetrics.heightPixels
                    }
                }
                val xdpi = if (displayMetrics.xdpi > 30f) displayMetrics.xdpi else displayMetrics.densityDpi.toFloat()
                val ydpi = if (displayMetrics.ydpi > 30f) displayMetrics.ydpi else displayMetrics.densityDpi.toFloat()
                val maxScreenWidthMm = (screenWidthPx / xdpi) * 25.4f
                val maxScreenHeightMm = (screenHeightPx / ydpi) * 25.4f

                // Max Screen Dimensions Card
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = SurfaceVariantDark.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "TABLET HARDWARE SIZE:",
                            color = AccentCyan,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = String.format(Locale.US, "%.1f x %.1f mm (%d x %d px)", maxScreenWidthMm, maxScreenHeightMm, screenWidthPx, screenHeightPx),
                            color = TextPrimary,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Direct Numeric Inputs: Width & Height in Millimeters
                // Number Sanitizer converting Arabic-Indic numerals (٠-٩) and commas (٫) to English ASCII (0-9) and '.'
                fun sanitizeNumber(input: String): String {
                    return input
                        .replace('٠', '0')
                        .replace('١', '1')
                        .replace('٢', '2')
                        .replace('٣', '3')
                        .replace('٤', '4')
                        .replace('٥', '5')
                        .replace('٦', '6')
                        .replace('٧', '7')
                        .replace('٨', '8')
                        .replace('٩', '9')
                        .replace('٫', '.')
                        .replace(',', '.')
                        .filter { it.isDigit() || it == '.' }
                }

                var widthText by remember {
                    mutableStateOf(String.format(Locale.US, "%.1f", settings.tabletArea.widthMm))
                }
                var heightText by remember {
                    mutableStateOf(String.format(Locale.US, "%.1f", settings.tabletArea.heightMm))
                }

                // Force Left-to-Right layout direction so numbers, cursor, and alignment never jump in Arabic locale
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // Width Input (mm)
                        OutlinedTextField(
                            value = widthText,
                            onValueChange = { raw ->
                                val clean = sanitizeNumber(raw)
                                widthText = clean
                                val typed = clean.toFloatOrNull()
                                if (typed != null && typed > 0f) {
                                    val newW = typed.coerceIn(15f, maxScreenWidthMm)
                                    val newH = if (settings.tabletArea.lockAspectRatio) {
                                        val calcH = newW * (9f / 16f)
                                        heightText = String.format(Locale.US, "%.1f", calcH)
                                        calcH.coerceIn(10f, maxScreenHeightMm)
                                    } else {
                                        settings.tabletArea.heightMm
                                    }
                                    onSettingsChanged(settings.copy(
                                        tabletArea = settings.tabletArea.copy(
                                            widthMm = newW,
                                            heightMm = newH,
                                            preset = OsuAreaPreset.CUSTOM,
                                            isFullScreen = false
                                        )
                                    ))
                                }
                            },
                            label = { Text("Width (mm)") },
                            supportingText = {
                                Text(
                                    text = String.format(Locale.US, "Max: %.1f mm", maxScreenWidthMm),
                                    color = TextSecondary,
                                    fontSize = 10.sp
                                )
                            },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            textStyle = LocalTextStyle.current.copy(
                                textAlign = TextAlign.Start,
                                textDirection = TextDirection.Ltr,
                                fontFamily = FontFamily.Monospace
                            ),
                            modifier = Modifier.weight(1f),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = AccentCyan,
                                unfocusedBorderColor = SurfaceVariantDark,
                                focusedLabelColor = AccentCyan,
                                unfocusedLabelColor = TextSecondary,
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary
                            )
                        )

                        // Height Input (mm)
                        OutlinedTextField(
                            value = heightText,
                            onValueChange = { raw ->
                                val clean = sanitizeNumber(raw)
                                heightText = clean
                                val typed = clean.toFloatOrNull()
                                if (typed != null && typed > 0f) {
                                    val newH = typed.coerceIn(10f, maxScreenHeightMm)
                                    val newW = if (settings.tabletArea.lockAspectRatio) {
                                        val calcW = newH * (16f / 9f)
                                        widthText = String.format(Locale.US, "%.1f", calcW)
                                        calcW.coerceIn(15f, maxScreenWidthMm)
                                    } else {
                                        settings.tabletArea.widthMm
                                    }
                                    onSettingsChanged(settings.copy(
                                        tabletArea = settings.tabletArea.copy(
                                            widthMm = newW,
                                            heightMm = newH,
                                            preset = OsuAreaPreset.CUSTOM,
                                            isFullScreen = false
                                        )
                                    ))
                                }
                            },
                            label = { Text("Height (mm)") },
                            supportingText = {
                                Text(
                                    text = String.format(Locale.US, "Max: %.1f mm", maxScreenHeightMm),
                                    color = TextSecondary,
                                    fontSize = 10.sp
                                )
                            },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            textStyle = LocalTextStyle.current.copy(
                                textAlign = TextAlign.Start,
                                textDirection = TextDirection.Ltr,
                                fontFamily = FontFamily.Monospace
                            ),
                            modifier = Modifier.weight(1f),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = AccentCyan,
                                unfocusedBorderColor = SurfaceVariantDark,
                                focusedLabelColor = AccentCyan,
                                unfocusedLabelColor = TextSecondary,
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                // Lock 16:9 Aspect Ratio Switch
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = "Lock 16:9 Monitor Ratio", color = TextPrimary, fontSize = 13.sp)
                        Text(text = "Keeps width and height matched to PC screen proportions.", color = TextSecondary, fontSize = 11.sp)
                    }
                    Switch(
                        checked = settings.tabletArea.lockAspectRatio,
                        onCheckedChange = { locked ->
                            val newH = if (locked) (settings.tabletArea.widthMm * (9f / 16f)).coerceIn(10f, maxScreenHeightMm) else settings.tabletArea.heightMm
                            heightText = String.format(Locale.US, "%.1f", newH)
                            onSettingsChanged(settings.copy(
                                tabletArea = settings.tabletArea.copy(
                                    lockAspectRatio = locked,
                                    heightMm = newH
                                )
                            ))
                        },
                        colors = SwitchDefaults.colors(checkedThumbColor = AccentCyan, checkedTrackColor = AccentBlue)
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Quick Presets (Real-world physical mm sizes)
                Text(text = "osu! Area Presets (True Physical mm):", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val presets = listOf(
                        OsuAreaPreset.STANDARD to "Standard\n85x48mm",
                        OsuAreaPreset.WRIST to "Wrist\n70x39mm",
                        OsuAreaPreset.SMALL to "Small\n55x31mm",
                        OsuAreaPreset.MICRO to "Micro\n40x22mm",
                        OsuAreaPreset.LARGE to "Large\n110x62mm",
                        OsuAreaPreset.FULL to "Full\nTablet"
                    )
                    presets.forEach { (preset, label) ->
                        val isSelected = settings.tabletArea.preset == preset
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (isSelected) AccentBlue else SurfaceVariantDark,
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    if (preset == OsuAreaPreset.FULL) {
                                        val targetW = maxScreenWidthMm
                                        val targetH = maxScreenHeightMm
                                        widthText = String.format(Locale.US, "%.1f", targetW)
                                        heightText = String.format(Locale.US, "%.1f", targetH)
                                        onSettingsChanged(settings.copy(
                                            aspectRatio = AspectRatioMode.FULL_SCREEN,
                                            tabletArea = settings.tabletArea.copy(
                                                widthMm = targetW,
                                                heightMm = targetH,
                                                preset = OsuAreaPreset.FULL,
                                                lockAspectRatio = false,
                                                isFullScreen = true
                                            )
                                        ))
                                    } else {
                                        val targetW = preset.widthMm.coerceIn(15f, maxScreenWidthMm)
                                        val targetH = preset.heightMm.coerceIn(10f, maxScreenHeightMm)
                                        widthText = String.format(Locale.US, "%.1f", targetW)
                                        heightText = String.format(Locale.US, "%.1f", targetH)
                                        onSettingsChanged(settings.copy(
                                            tabletArea = settings.tabletArea.copy(
                                                widthMm = targetW,
                                                heightMm = targetH,
                                                preset = preset,
                                                isFullScreen = false
                                            )
                                        ))
                                    }
                                }
                        ) {
                            Text(
                                text = label,
                                color = if (isSelected) Color.White else TextSecondary,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(vertical = 6.dp),
                                textAlign = TextAlign.Center,
                                lineHeight = 13.sp
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Width & Height Fine Sliders (in mm)
                Text(
                    text = String.format(Locale.US, "Width Slider: %.1f mm", settings.tabletArea.widthMm),
                    color = TextPrimary,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace
                )
                Slider(
                    value = settings.tabletArea.widthMm.coerceIn(20f, maxScreenWidthMm),
                    onValueChange = { newVal ->
                        val newH = if (settings.tabletArea.lockAspectRatio) {
                            (newVal * (9f / 16f)).coerceIn(10f, maxScreenHeightMm)
                        } else settings.tabletArea.heightMm
                        widthText = String.format(Locale.US, "%.1f", newVal)
                        heightText = String.format(Locale.US, "%.1f", newH)
                        onSettingsChanged(settings.copy(
                            tabletArea = settings.tabletArea.copy(
                                widthMm = newVal,
                                heightMm = newH,
                                preset = OsuAreaPreset.CUSTOM,
                                isFullScreen = false
                            )
                        ))
                    },
                    valueRange = 20f..maxScreenWidthMm,
                    colors = SliderDefaults.colors(thumbColor = AccentCyan, activeTrackColor = AccentBlue)
                )

                Text(
                    text = String.format(Locale.US, "Height Slider: %.1f mm", settings.tabletArea.heightMm),
                    color = TextPrimary,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace
                )
                Slider(
                    value = settings.tabletArea.heightMm.coerceIn(15f, maxScreenHeightMm),
                    onValueChange = { newVal ->
                        val newW = if (settings.tabletArea.lockAspectRatio) {
                            (newVal * (16f / 9f)).coerceIn(20f, maxScreenWidthMm)
                        } else settings.tabletArea.widthMm
                        widthText = String.format(Locale.US, "%.1f", newW)
                        heightText = String.format(Locale.US, "%.1f", newVal)
                        onSettingsChanged(settings.copy(
                            tabletArea = settings.tabletArea.copy(
                                widthMm = newW,
                                heightMm = newVal,
                                preset = OsuAreaPreset.CUSTOM,
                                isFullScreen = false
                            )
                        ))
                    },
                    valueRange = 15f..maxScreenHeightMm,
                    colors = SliderDefaults.colors(thumbColor = AccentCyan, activeTrackColor = AccentBlue)
                )

                // Position Offsets
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = "Area Placement & Position:", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = SurfaceVariantDark,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable {
                                onSettingsChanged(settings.copy(
                                    tabletArea = settings.tabletArea.copy(
                                        offsetXPercent = 0.5f,
                                        offsetYPercent = 0.5f
                                    )
                                ))
                            }
                    ) {
                        Text(
                            text = "Center",
                            color = AccentCyan,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Button: Drag Directly on Full Screen
                Button(
                    onClick = onStartFullScreenCalibration,
                    colors = ButtonDefaults.buttonColors(containerColor = AccentBlue),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(vertical = 10.dp)
                ) {
                    Text(
                        text = "🖐 Move Directly on Full Screen",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Interactive Mini-Tablet Drag Pad
                Text(text = "Interactive Drag Pad:", color = TextSecondary, fontSize = 11.sp)
                Spacer(modifier = Modifier.height(6.dp))
                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(SurfaceVariantDark.copy(alpha = 0.6f))
                        .border(1.dp, SurfaceVariantDark, RoundedCornerShape(12.dp))
                        .pointerInput(Unit) {
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                val padW = size.width.toFloat()
                                val padH = size.height.toFloat()
                                if (padW > 0 && padH > 0) {
                                    val newX = (settings.tabletArea.offsetXPercent + (dragAmount.x / (padW * 0.7f))).coerceIn(0f, 1f)
                                    val newY = (settings.tabletArea.offsetYPercent + (dragAmount.y / (padH * 0.7f))).coerceIn(0f, 1f)
                                    onSettingsChanged(settings.copy(
                                        tabletArea = settings.tabletArea.copy(
                                            offsetXPercent = newX,
                                            offsetYPercent = newY
                                        )
                                    ))
                                }
                            }
                        }
                ) {
                    val padW = maxWidth
                    val padH = maxHeight
                    val scaleW = (settings.tabletArea.widthMm / maxScreenWidthMm).coerceIn(0.15f, 1f)
                    val scaleH = (settings.tabletArea.heightMm / maxScreenHeightMm).coerceIn(0.15f, 1f)
                    val boxW = (padW * scaleW).coerceAtLeast(36.dp)
                    val boxH = (padH * scaleH).coerceAtLeast(24.dp)
                    val maxOffX = (padW - boxW).coerceAtLeast(0.dp)
                    val maxOffY = (padH - boxH).coerceAtLeast(0.dp)
                    val left = maxOffX * settings.tabletArea.offsetXPercent
                    val top = maxOffY * settings.tabletArea.offsetYPercent

                    Box(
                        modifier = Modifier
                            .offset(x = left, y = top)
                            .size(width = boxW, height = boxH)
                            .clip(RoundedCornerShape(6.dp))
                            .background(AccentCyan.copy(alpha = 0.35f))
                            .border(2.dp, AccentCyan, RoundedCornerShape(6.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "🖐 DRAG",
                            color = Color.White,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Quick 3x3 Snap Grid
                Text(text = "Quick Snap Locations:", color = TextSecondary, fontSize = 11.sp)
                Spacer(modifier = Modifier.height(6.dp))
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    // Row 1: Top row
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        PositionSnapButton("↖ Top-Left", 0.0f, 0.0f, settings, onSettingsChanged, Modifier.weight(1f))
                        PositionSnapButton("⬆ Top-Center", 0.5f, 0.0f, settings, onSettingsChanged, Modifier.weight(1f))
                        PositionSnapButton("↗ Top-Right", 1.0f, 0.0f, settings, onSettingsChanged, Modifier.weight(1f))
                    }
                    // Row 2: Mid row
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        PositionSnapButton("⬅ Left", 0.0f, 0.5f, settings, onSettingsChanged, Modifier.weight(1f))
                        PositionSnapButton("⏺ Center", 0.5f, 0.5f, settings, onSettingsChanged, Modifier.weight(1f))
                        PositionSnapButton("➡ Right", 1.0f, 0.5f, settings, onSettingsChanged, Modifier.weight(1f))
                    }
                    // Row 3: Bottom row
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        PositionSnapButton("↙ Bottom-Left", 0.0f, 1.0f, settings, onSettingsChanged, Modifier.weight(1f))
                        PositionSnapButton("⬇ Bottom-Center", 0.5f, 1.0f, settings, onSettingsChanged, Modifier.weight(1f))
                        PositionSnapButton("↘ Bottom-Right", 1.0f, 1.0f, settings, onSettingsChanged, Modifier.weight(1f))
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Fine Sliders
                Text(
                    text = String.format(Locale.US, "Horizontal X: %d%%", (settings.tabletArea.offsetXPercent * 100).toInt()),
                    color = TextSecondary,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
                Slider(
                    value = settings.tabletArea.offsetXPercent,
                    onValueChange = { newVal ->
                        onSettingsChanged(settings.copy(tabletArea = settings.tabletArea.copy(offsetXPercent = newVal)))
                    },
                    valueRange = 0f..1.0f,
                    colors = SliderDefaults.colors(thumbColor = AccentCyan, activeTrackColor = AccentBlue)
                )

                Text(
                    text = String.format(Locale.US, "Vertical Y: %d%%", (settings.tabletArea.offsetYPercent * 100).toInt()),
                    color = TextSecondary,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
                Slider(
                    value = settings.tabletArea.offsetYPercent,
                    onValueChange = { newVal ->
                        onSettingsChanged(settings.copy(tabletArea = settings.tabletArea.copy(offsetYPercent = newVal)))
                    },
                    valueRange = 0f..1.0f,
                    colors = SliderDefaults.colors(thumbColor = AccentCyan, activeTrackColor = AccentBlue)
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Toggle: Show Area Boundary Guides
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = "Show Active Area Visual Frame", color = TextPrimary, fontSize = 14.sp)
                        Text(text = "Renders glowing neon border & millimeter HUD over the active zone.", color = TextSecondary, fontSize = 11.sp)
                    }
                    Switch(
                        checked = settings.showAreaGuides,
                        onCheckedChange = { onSettingsChanged(settings.copy(showAreaGuides = it)) },
                        colors = SwitchDefaults.colors(checkedThumbColor = AccentCyan, checkedTrackColor = AccentBlue)
                    )
                }

                Spacer(modifier = Modifier.height(18.dp))
                HorizontalDivider(color = SurfaceVariantDark)
                Spacer(modifier = Modifier.height(16.dp))

                // ==================== ASPECT RATIO ====================
                Text(
                    text = "Display Aspect Ratio Lock",
                    color = TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "Locks tablet drawing canvas to match your PC monitor proportions.",
                    color = TextSecondary,
                    fontSize = 12.sp
                )
                Spacer(modifier = Modifier.height(8.dp))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    AspectRatioMode.values().forEach { mode ->
                        val isSelected = settings.aspectRatio == mode
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSelected) SurfaceVariantDark else Color.Transparent)
                                .clickable { onSettingsChanged(settings.copy(aspectRatio = mode)) }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = isSelected,
                                onClick = { onSettingsChanged(settings.copy(aspectRatio = mode)) },
                                colors = RadioButtonDefaults.colors(selectedColor = AccentCyan)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(text = mode.displayName, color = if (isSelected) TextPrimary else TextSecondary, fontSize = 14.sp)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))
                HorizontalDivider(color = SurfaceVariantDark)
                Spacer(modifier = Modifier.height(16.dp))

                // ==================== POLLING & SAMPLING RATE ====================
                Text(
                    text = "Digitizer Sampling / Polling Rate",
                    color = TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "Controls digitizer event frequency over USB. Set to 500Hz or 1000Hz for esports / competitive osu! tracking.",
                    color = TextSecondary,
                    fontSize = 12.sp
                )
                Spacer(modifier = Modifier.height(8.dp))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    PollingRatePreset.values().forEach { preset ->
                        val isSelected = settings.pollingRate == preset
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSelected) SurfaceVariantDark else Color.Transparent)
                                .clickable { onSettingsChanged(settings.copy(pollingRate = preset)) }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = isSelected,
                                onClick = { onSettingsChanged(settings.copy(pollingRate = preset)) },
                                colors = RadioButtonDefaults.colors(selectedColor = AccentCyan)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(text = preset.displayName, color = if (isSelected) TextPrimary else TextSecondary, fontSize = 14.sp)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))
                HorizontalDivider(color = SurfaceVariantDark)
                Spacer(modifier = Modifier.height(16.dp))

                // ==================== PRESSURE CURVE ====================
                Text(
                    text = "Pressure Sensitivity Curve",
                    color = TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "Calibrates digitizer pressure response for drawing & inking.",
                    color = TextSecondary,
                    fontSize = 12.sp
                )
                Spacer(modifier = Modifier.height(8.dp))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    PressureCurve.values().forEach { curve ->
                        val isSelected = settings.pressureCurve == curve
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSelected) SurfaceVariantDark else Color.Transparent)
                                .clickable { onSettingsChanged(settings.copy(pressureCurve = curve)) }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = isSelected,
                                onClick = { onSettingsChanged(settings.copy(pressureCurve = curve)) },
                                colors = RadioButtonDefaults.colors(selectedColor = AccentCyan)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(text = curve.displayName, color = if (isSelected) TextPrimary else TextSecondary, fontSize = 14.sp)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))
                HorizontalDivider(color = SurfaceVariantDark)
                Spacer(modifier = Modifier.height(16.dp))

                // ==================== HARDWARE PALM REJECTION ====================
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = "Strict Hardware Palm Rejection", color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text(text = "Completely ignores finger touch input to eliminate accidental wrist marks.", color = TextSecondary, fontSize = 12.sp)
                    }
                    Switch(
                        checked = settings.strictPalmRejection,
                        onCheckedChange = { onSettingsChanged(settings.copy(strictPalmRejection = it)) },
                        colors = SwitchDefaults.colors(checkedThumbColor = AccentCyan, checkedTrackColor = AccentBlue)
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Invert Barrel Button Toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = "Invert Stylus Side-Button", color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text(text = "Swaps primary and secondary stylus buttons.", color = TextSecondary, fontSize = 12.sp)
                    }
                    Switch(
                        checked = settings.invertStylusButton,
                        onCheckedChange = { onSettingsChanged(settings.copy(invertStylusButton = it)) },
                        colors = SwitchDefaults.colors(checkedThumbColor = AccentCyan, checkedTrackColor = AccentBlue)
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Local Ink Trail & Touch/Hover Cursor Indicator Toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = "Show Touch & Pen Position Indicator", color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text(text = "Draws touch dot, hover cursor ring, and pen trail on tablet screen (Off = zero UI redraw lag).", color = TextSecondary, fontSize = 12.sp)
                    }
                    Switch(
                        checked = settings.showLocalInkTrail,
                        onCheckedChange = { onSettingsChanged(settings.copy(showLocalInkTrail = it)) },
                        colors = SwitchDefaults.colors(checkedThumbColor = AccentCyan, checkedTrackColor = AccentBlue)
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                // ADB Setup Tip Box
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = SurfaceVariantDark,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Icon(imageVector = Icons.Default.Info, contentDescription = null, tint = AccentCyan)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "USB Connection: Ensure 'USB Debugging' is enabled in Developer Options. Connect via USB cable, launch PenFlow Server on PC, and it will automatically execute 'adb reverse tcp:9876 tcp:9876' for instant zero-latency link.",
                            color = TextSecondary,
                            fontSize = 12.sp,
                            lineHeight = 17.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PositionSnapButton(
    label: String,
    targetX: Float,
    targetY: Float,
    settings: TabletSettings,
    onSettingsChanged: (TabletSettings) -> Unit,
    modifier: Modifier = Modifier
) {
    val isSelected = Math.abs(settings.tabletArea.offsetXPercent - targetX) < 0.05f &&
            Math.abs(settings.tabletArea.offsetYPercent - targetY) < 0.05f

    Surface(
        shape = RoundedCornerShape(6.dp),
        color = if (isSelected) AccentBlue else SurfaceVariantDark,
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable {
                onSettingsChanged(settings.copy(
                    tabletArea = settings.tabletArea.copy(
                        offsetXPercent = targetX,
                        offsetYPercent = targetY
                    )
                ))
            }
    ) {
        Text(
            text = label,
            color = if (isSelected) Color.White else TextSecondary,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(vertical = 7.dp, horizontal = 2.dp),
            textAlign = TextAlign.Center,
            maxLines = 1
        )
    }
}

