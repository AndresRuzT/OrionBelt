package com.audacia.orion.ui

import android.graphics.Bitmap
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.audacia.orion.R
import com.audacia.orion.feedback.Alert
import com.audacia.orion.feedback.FeedbackSettings
import com.audacia.orion.vision.Action
import com.audacia.orion.vision.ConnectionStatus
import com.audacia.orion.vision.InferenceBackend
import com.audacia.orion.vision.VisionState
import com.audacia.orion.vision.Zone
import com.audacia.orion.vision.ZoneResult

@Composable
fun HudScreen(
    viewModel: HudViewModel,
    modifier: Modifier = Modifier
) {
    val state by viewModel.state.collectAsState()
    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0B0F19))
            .padding(14.dp)
            .verticalScroll(scrollState),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // --- 1. BARRA SUPERIOR: ESTADO Y BACKEND ---
        TopHudHeader(
            state = state,
            onBackendToggle = {
                val next = if (state.activeBackend == InferenceBackend.GPU) InferenceBackend.CPU else InferenceBackend.GPU
                viewModel.switchBackend(next)
            }
        )

        Spacer(modifier = Modifier.height(10.dp))

        // --- 2. BANNER DE NAVEGACIÓN Y ALERTA (FORWARD / STOP) ---
        ActionAlertBanner(
            zoneResult = state.zoneResult,
            alert = state.activeAlert
        )

        Spacer(modifier = Modifier.height(10.dp))

        // --- 3. TELEMETRÍA (FPS, INFERENCIA, LATENCIA) ---
        TelemetryRow(state = state)

        Spacer(modifier = Modifier.height(12.dp))

        // --- 4. VISUALES: STREAM DE CÁMARA Y MAPA MAGMA ---
        VisualStreamsSection(
            cameraBitmap = state.cameraBitmap,
            depthBitmap = state.depthBitmap
        )

        Spacer(modifier = Modifier.height(12.dp))

        // --- 5. BARRAS ESPACIALES POR ZONAS (IZQ, CENTRO, DER) ---
        SpatialZonesCard(zoneResult = state.zoneResult)

        Spacer(modifier = Modifier.height(12.dp))

        // --- 6. RETROALIMENTACIÓN SENSORIAL (TIRA COMPACTA PROFESIONAL) ---
        SensoryFeedbackCard(
            settings = state.feedbackSettings,
            onUpdateSettings = { viewModel.updateFeedbackSettings(it) }
        )

        Spacer(modifier = Modifier.height(14.dp))

        // --- 7. CONTROLES DE ENLACE ---
        ControlButtonsSection(
            status = state.connectionStatus,
            onToggleAutoSearch = {
                if (state.connectionStatus == ConnectionStatus.SEARCHING ||
                    state.connectionStatus == ConnectionStatus.CONNECTING ||
                    state.connectionStatus == ConnectionStatus.STREAMING
                ) {
                    viewModel.stop()
                } else {
                    viewModel.startAutoDiscovery()
                }
            },
            onRefreshSearch = { viewModel.startAutoDiscovery() },
            onDisconnect = { viewModel.stop() }
        )

        Spacer(modifier = Modifier.height(10.dp))

        // Estado textual del sistema
        Text(
            text = state.statusMessage,
            color = Color(0xFF64748B),
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
fun TopHudHeader(
    state: VisionState,
    onBackendToggle: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val statusDotColor = when (state.connectionStatus) {
                ConnectionStatus.STREAMING -> Color(0xFF10B981) // Verde
                ConnectionStatus.SEARCHING -> Color(0xFFF59E0B) // Ámbar
                ConnectionStatus.CONNECTING -> Color(0xFF6366F1) // Índigo
                ConnectionStatus.ERROR -> Color(0xFFEF4444) // Rojo
                ConnectionStatus.IDLE, ConnectionStatus.DEMO_MODE -> Color(0xFF64748B) // Gris
            }

            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(statusDotColor, CircleShape)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column {
                Text(
                    text = "ORION v6",
                    color = Color.White,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
                val statusLabel = when (state.connectionStatus) {
                    ConnectionStatus.STREAMING -> "STREAM EN VIVO"
                    ConnectionStatus.SEARCHING -> "BUSCANDO CÁMARA..."
                    ConnectionStatus.CONNECTING -> "CONECTANDO..."
                    ConnectionStatus.ERROR -> "ERROR DE ENLACE"
                    ConnectionStatus.IDLE -> "EN ESPERA"
                    ConnectionStatus.DEMO_MODE -> "MODO DEMO"
                }
                Text(
                    text = statusLabel,
                    color = statusDotColor,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace
                )
            }
        }

        // Chip selector de Backend (GPU / CPU)
        val isGpu = state.activeBackend == InferenceBackend.GPU
        val backendColor = if (isGpu) Color(0xFF10B981) else Color(0xFFF59E0B)
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(backendColor.copy(alpha = 0.15f))
                .border(1.dp, backendColor.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                .clickable { onBackendToggle() }
                .padding(horizontal = 10.dp, vertical = 5.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(5.dp)
                        .background(backendColor, CircleShape)
                )
                Spacer(modifier = Modifier.width(5.dp))
                Text(
                    text = if (isGpu) "GPU ACCEL" else "CPU (4H)",
                    color = backendColor,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

@Composable
fun ActionAlertBanner(
    zoneResult: ZoneResult?,
    alert: Alert?
) {
    val isStop = zoneResult?.action == Action.STOP
    val bgColor by animateColorAsState(
        targetValue = if (isStop) Color(0xFFDC2626) else Color(0xFF047857),
        animationSpec = tween(durationMillis = 200),
        label = "alert_bg_anim"
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = bgColor)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp, horizontal = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    painter = painterResource(if (isStop) R.drawable.ic_alert_stop else R.drawable.ic_forward),
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = if (isStop) "STOP DETECTADO" else "AVANZAR",
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.ExtraBold,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = if (isStop) {
                            if (alert != null) {
                                "Nivel ${alert.level} (${alert.intervalMs} ms) • Zona ${alert.zone.name}"
                            } else {
                                val zoneName = zoneResult?.nearestZone?.name ?: "OBSTÁCULO FRONTAL"
                                "Zona crítica: $zoneName"
                            }
                        } else {
                            "Trayectoria libre de obstáculos"
                        },
                        color = Color.White.copy(alpha = 0.85f),
                        fontSize = 11.sp
                    )
                }
            }

            if (isStop) {
                val pct = ((zoneResult?.intensity ?: 0f) * 100).toInt()
                Text(
                    text = "$pct%",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

@Composable
fun TelemetryRow(state: VisionState) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        MetricCard(
            modifier = Modifier.weight(1f),
            label = "VELOCIDAD",
            value = String.format("%.1f FPS", state.fps),
            color = Color(0xFF38BDF8)
        )
        MetricCard(
            modifier = Modifier.weight(1f),
            label = "INFERENCIA",
            value = "${state.inferenceMs} ms",
            color = Color(0xFFA78BFA)
        )
        MetricCard(
            modifier = Modifier.weight(1.2f),
            label = "TOTAL LATENCIA",
            value = "${state.totalLatencyMs} ms",
            color = Color(0xFF34D399)
        )
    }
}

@Composable
fun MetricCard(
    modifier: Modifier = Modifier,
    label: String,
    value: String,
    color: Color
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF131C31))
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(text = label, color = Color(0xFF64748B), fontSize = 9.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = value,
                color = color,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

@Composable
fun VisualStreamsSection(
    cameraBitmap: Bitmap?,
    depthBitmap: Bitmap?
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Vista de Cámara
        StreamCard(
            modifier = Modifier.weight(1f),
            title = "CÁMARA (XIAO)",
            bitmap = cameraBitmap
        )

        // Vista de Mapa de Profundidad (Magma)
        StreamCard(
            modifier = Modifier.weight(1f),
            title = "PROFUNDIDAD (MAGMA)",
            bitmap = depthBitmap
        )
    }
}

@Composable
fun StreamCard(
    modifier: Modifier = Modifier,
    title: String,
    bitmap: Bitmap?
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF131C31))
    ) {
        Column(
            modifier = Modifier.padding(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = title,
                color = Color(0xFF94A3B8),
                fontSize = 9.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace
            )
            Spacer(modifier = Modifier.height(4.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFF090D16)),
                contentAlignment = Alignment.Center
            ) {
                if (bitmap != null && !bitmap.isRecycled) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = title,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Text(
                        text = "Sin señal",
                        color = Color(0xFF475569),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}

@Composable
fun SpatialZonesCard(zoneResult: ZoneResult?) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF131C31))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "SEGMENTACIÓN ESPACIAL POR ZONAS",
                color = Color(0xFF94A3B8),
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
            Spacer(modifier = Modifier.height(8.dp))

            val left = zoneResult?.left ?: 0.0f
            val center = zoneResult?.center ?: 0.0f
            val right = zoneResult?.right ?: 0.0f

            ZoneProgressBar(name = "IZQUIERDA", value = left, isNearest = zoneResult?.nearestZone == Zone.LEFT)
            Spacer(modifier = Modifier.height(5.dp))
            ZoneProgressBar(name = "CENTRO", value = center, isNearest = zoneResult?.nearestZone == Zone.CENTER)
            Spacer(modifier = Modifier.height(5.dp))
            ZoneProgressBar(name = "DERECHA", value = right, isNearest = zoneResult?.nearestZone == Zone.RIGHT)
        }
    }
}

@Composable
fun ZoneProgressBar(
    name: String,
    value: Float,
    isNearest: Boolean
) {
    val barColor = when {
        isNearest -> Color(0xFFEF4444)
        value > 0.60f -> Color(0xFFF59E0B)
        else -> Color(0xFF38BDF8)
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = name,
            color = if (isNearest) Color(0xFFEF4444) else Color(0xFF94A3B8),
            fontSize = 10.sp,
            fontWeight = if (isNearest) FontWeight.Bold else FontWeight.Normal,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(80.dp)
        )

        LinearProgressIndicator(
            progress = { value.coerceIn(0f, 1f) },
            modifier = Modifier
                .weight(1f)
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp)),
            color = barColor,
            trackColor = Color(0xFF1E293B)
        )

        Spacer(modifier = Modifier.width(8.dp))

        Text(
            text = "${(value * 100).toInt()}%",
            color = Color.White,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(32.dp),
            textAlign = TextAlign.End
        )
    }
}

@Composable
fun SensoryFeedbackCard(
    settings: FeedbackSettings,
    onUpdateSettings: (FeedbackSettings) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF131C31)),
        border = BorderStroke(1.dp, Color(0xFF1E293B))
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "RETROALIMENTACIÓN SENSORIAL",
                    color = Color(0xFF94A3B8),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
                val activeCount = listOf(
                    settings.hapticEnabled,
                    settings.audioEnabled,
                    settings.buzzerEnabled,
                    settings.voiceEnabled
                ).count { it }
                Text(
                    text = "$activeCount/4 ACTIVOS",
                    color = Color(0xFF38BDF8),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                CompactFeedbackToggle(
                    modifier = Modifier.weight(1f),
                    drawableRes = R.drawable.ic_vibrate,
                    label = "Háptica",
                    enabled = settings.hapticEnabled,
                    onToggle = { onUpdateSettings(settings.copy(hapticEnabled = !settings.hapticEnabled)) }
                )
                CompactFeedbackToggle(
                    modifier = Modifier.weight(1f),
                    drawableRes = R.drawable.ic_audio,
                    label = "Audio",
                    enabled = settings.audioEnabled,
                    onToggle = { onUpdateSettings(settings.copy(audioEnabled = !settings.audioEnabled)) }
                )
                CompactFeedbackToggle(
                    modifier = Modifier.weight(1f),
                    drawableRes = R.drawable.ic_buzzer,
                    label = "Buzzer",
                    enabled = settings.buzzerEnabled,
                    onToggle = { onUpdateSettings(settings.copy(buzzerEnabled = !settings.buzzerEnabled)) }
                )
                CompactFeedbackToggle(
                    modifier = Modifier.weight(1f),
                    drawableRes = R.drawable.ic_voice,
                    label = "Voz",
                    enabled = settings.voiceEnabled,
                    onToggle = { onUpdateSettings(settings.copy(voiceEnabled = !settings.voiceEnabled)) }
                )
            }
        }
    }
}

@Composable
fun CompactFeedbackToggle(
    modifier: Modifier = Modifier,
    drawableRes: Int,
    label: String,
    enabled: Boolean,
    onToggle: () -> Unit
) {
    val activeColor = Color(0xFF10B981)
    val inactiveBorder = Color(0xFF1E293B)
    val bgColor = if (enabled) activeColor.copy(alpha = 0.12f) else Color(0xFF0F172A)
    val borderColor = if (enabled) activeColor.copy(alpha = 0.6f) else inactiveBorder
    val iconColor = if (enabled) activeColor else Color(0xFF64748B)
    val textColor = if (enabled) Color.White else Color(0xFF64748B)

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bgColor)
            .border(1.dp, borderColor, RoundedCornerShape(8.dp))
            .clickable { onToggle() }
            .padding(vertical = 6.dp, horizontal = 2.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painter = painterResource(drawableRes),
                    contentDescription = label,
                    tint = iconColor,
                    modifier = Modifier.size(15.dp)
                )
                if (enabled) {
                    Spacer(modifier = Modifier.width(3.dp))
                    Box(
                        modifier = Modifier
                            .size(4.dp)
                            .background(activeColor, CircleShape)
                    )
                }
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = label,
                color = textColor,
                fontSize = 10.sp,
                fontWeight = if (enabled) FontWeight.Bold else FontWeight.Medium,
                fontFamily = FontFamily.Monospace,
                maxLines = 1
            )
            Spacer(modifier = Modifier.height(1.dp))
            Text(
                text = if (enabled) "ON" else "OFF",
                color = if (enabled) activeColor else Color(0xFF475569),
                fontSize = 8.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

@Composable
fun ControlButtonsSection(
    status: ConnectionStatus,
    onToggleAutoSearch: () -> Unit,
    onRefreshSearch: () -> Unit,
    onDisconnect: () -> Unit
) {
    val isAutoActive = status == ConnectionStatus.SEARCHING ||
            status == ConnectionStatus.CONNECTING ||
            status == ConnectionStatus.STREAMING

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // 1. Botón de Búsqueda Automática (Activo por defecto)
            val autoBgColor = if (isAutoActive) Color(0xFF0284C7) else Color(0xFF1E293B)
            val autoBorderColor = if (isAutoActive) Color(0xFF38BDF8) else Color(0xFF334155)
            Button(
                onClick = onToggleAutoSearch,
                modifier = Modifier
                    .weight(1.15f)
                    .height(42.dp),
                colors = ButtonDefaults.buttonColors(containerColor = autoBgColor),
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, autoBorderColor)
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_radar),
                    contentDescription = null,
                    tint = if (isAutoActive) Color.White else Color(0xFF94A3B8),
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (isAutoActive) "Búsqueda: ON" else "Búsqueda: OFF",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1
                )
            }

            // 2. Botón Reintentar Autoconexión (Refresh)
            OutlinedButton(
                onClick = onRefreshSearch,
                modifier = Modifier
                    .weight(1f)
                    .height(42.dp),
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, Color(0xFF334155)),
                colors = ButtonDefaults.outlinedButtonColors(containerColor = Color(0xFF131C31))
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_refresh),
                    contentDescription = null,
                    tint = Color(0xFF38BDF8),
                    modifier = Modifier.size(15.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Reintentar",
                    fontSize = 11.sp,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1
                )
            }
        }

        // 3. Botón Desconectar (visible cuando está conectado/streaming o conectando)
        if (status == ConnectionStatus.STREAMING || status == ConnectionStatus.CONNECTING) {
            Button(
                onClick = onDisconnect,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(38.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E293B)),
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, Color(0xFF475569))
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_disconnect),
                    contentDescription = null,
                    tint = Color(0xFFF87171),
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Desconectar",
                    fontSize = 11.sp,
                    color = Color(0xFFF87171),
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}
