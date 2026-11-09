package com.audacia.orion.ui

import android.graphics.Bitmap
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    var showIpDialog by remember { mutableStateOf(false) }
    var manualIp by remember { mutableStateOf("192.168.43.100") }
    var showBenchmarkDialog by remember { mutableStateOf(false) }

    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0B0F19))
            .padding(16.dp)
            .verticalScroll(scrollState),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // --- 1. BARRA SUPERIOR: ESTADO Y BACKEND ---
        TopHudHeader(
            state = state,
            onBackendToggle = {
                val next = if (state.activeBackend == InferenceBackend.GPU) InferenceBackend.CPU else InferenceBackend.GPU
                viewModel.switchBackend(next)
            },
            onRunBenchmark = {
                viewModel.runBenchmark {
                    showBenchmarkDialog = true
                }
            }
        )

        Spacer(modifier = Modifier.height(12.dp))

        // --- 2. BANNER DE ALERTA ESPACIAL (FORWARD / STOP) ---
        ActionAlertBanner(zoneResult = state.zoneResult)

        Spacer(modifier = Modifier.height(12.dp))

        // --- 3. TELEMETRÍA (FPS, LATENCIA, ENDPOINT) ---
        TelemetryRow(state = state)

        Spacer(modifier = Modifier.height(14.dp))

        // --- 4. VISUALES: CÁMARA EN VIVO Y MAPA MAGMA DE PROFUNDIDAD ---
        VisualStreamsSection(
            cameraBitmap = state.cameraBitmap,
            depthBitmap = state.depthBitmap
        )

        Spacer(modifier = Modifier.height(14.dp))

        // --- 5. BARRAS ESPACIALES POR ZONAS (IZQ, CENTRO, DER) ---
        SpatialZonesCard(zoneResult = state.zoneResult)

        Spacer(modifier = Modifier.height(16.dp))

        // --- 6. CONTROLES DE CONEXIÓN Y DEMO ---
        ControlButtonsSection(
            status = state.connectionStatus,
            onAutoDiscover = { viewModel.startAutoDiscovery() },
            onStartDemo = { viewModel.startDemo() },
            onDirectIp = { showIpDialog = true },
            onStop = { viewModel.stop() }
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Estado textual / debug footer
        Text(
            text = state.statusMessage,
            color = Color(0xFF64748B),
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.Center
        )
    }

    // Diálogo de IP manual
    if (showIpDialog) {
        AlertDialog(
            onDismissRequest = { showIpDialog = false },
            title = { Text("Conexión Directa al XIAO") },
            text = {
                Column {
                    Text(
                        "Introduce la dirección IPv4 del XIAO ESP32S3 conectada a la Zona Wi-Fi:",
                        fontSize = 13.sp,
                        color = Color(0xFFCBD5E1)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = manualIp,
                        onValueChange = { manualIp = it },
                        label = { Text("IP de la Cámara") },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showIpDialog = false
                        viewModel.connectDirect(manualIp)
                    }
                ) {
                    Text("Conectar")
                }
            },
            dismissButton = {
                TextButton(onClick = { showIpDialog = false }) {
                    Text("Cancelar")
                }
            }
        )
    }

    // Diálogo de resultados del Benchmark
    if (showBenchmarkDialog && state.benchmarkSummary != null) {
        AlertDialog(
            onDismissRequest = { showBenchmarkDialog = false },
            title = { Text("⚡ Benchmark TFLite (50 iteraciones)") },
            text = {
                Text(
                    text = state.benchmarkSummary ?: "",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    color = Color(0xFFE2E8F0)
                )
            },
            confirmButton = {
                Button(onClick = { showBenchmarkDialog = false }) {
                    Text("Cerrar")
                }
            }
        )
    }
}

@Composable
fun TopHudHeader(
    state: VisionState,
    onBackendToggle: () -> Unit,
    onRunBenchmark: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val statusDotColor = when (state.connectionStatus) {
                ConnectionStatus.STREAMING -> Color(0xFF10B981) // Verde
                ConnectionStatus.DEMO_MODE -> Color(0xFF38BDF8) // Azul
                ConnectionStatus.SEARCHING -> Color(0xFFF59E0B) // Ámbar
                ConnectionStatus.CONNECTING -> Color(0xFF6366F1) // Índigo
                ConnectionStatus.ERROR -> Color(0xFFEF4444) // Rojo
                ConnectionStatus.IDLE -> Color(0xFF64748B) // Gris
            }

            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(statusDotColor, CircleShape)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "ORION v6",
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            // Chip de Backend (GPU / CPU)
            val backendColor = if (state.activeBackend == InferenceBackend.GPU) Color(0xFF10B981) else Color(0xFFF59E0B)
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(backendColor.copy(alpha = 0.18f))
                    .border(1.dp, backendColor, RoundedCornerShape(8.dp))
                    .clickable { onBackendToggle() }
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text(
                    text = state.activeBackend.name,
                    color = backendColor,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Botón rápido de Benchmark
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF1E293B))
                    .border(1.dp, Color(0xFF334155), RoundedCornerShape(8.dp))
                    .clickable { onRunBenchmark() }
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text(
                    text = "⚡ TEST",
                    color = Color(0xFF38BDF8),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
fun ActionAlertBanner(zoneResult: ZoneResult?) {
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
                .padding(vertical = 12.dp, horizontal = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = if (isStop) "🚨 STOP DETECTADO" else "✓ AVANZAR",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.ExtraBold,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = if (isStop) {
                        val zoneName = zoneResult?.nearestZone?.name ?: "OBSTÁCULO FRONTAL"
                        "Zona crítica: $zoneName"
                    } else {
                        "Trayectoria libre de obstáculos"
                    },
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 12.sp
                )
            }

            if (isStop) {
                val pct = (zoneResult.intensity * 100).toInt()
                Text(
                    text = "$pct%",
                    color = Color.White,
                    fontSize = 24.sp,
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
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF131C31))
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(text = label, color = Color(0xFF64748B), fontSize = 10.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = value,
                color = color,
                fontSize = 14.sp,
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
            title = "CAMARA (XIAO)",
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
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF131C31))
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = title,
                color = Color(0xFF94A3B8),
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace
            )
            Spacer(modifier = Modifier.height(6.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(8.dp))
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
                        fontSize = 12.sp,
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
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = "SEGMENTACIÓN ESPACIAL POR ZONAS",
                color = Color(0xFF94A3B8),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
            Spacer(modifier = Modifier.height(10.dp))

            val left = zoneResult?.left ?: 0.0f
            val center = zoneResult?.center ?: 0.0f
            val right = zoneResult?.right ?: 0.0f

            ZoneProgressBar(name = "IZQUIERDA", value = left, isNearest = zoneResult?.nearestZone == Zone.LEFT)
            Spacer(modifier = Modifier.height(6.dp))
            ZoneProgressBar(name = "CENTRO", value = center, isNearest = zoneResult?.nearestZone == Zone.CENTER)
            Spacer(modifier = Modifier.height(6.dp))
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
            fontSize = 11.sp,
            fontWeight = if (isNearest) FontWeight.Bold else FontWeight.Normal,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(90.dp)
        )

        LinearProgressIndicator(
            progress = { value.coerceIn(0f, 1f) },
            modifier = Modifier
                .weight(1f)
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp)),
            color = barColor,
            trackColor = Color(0xFF1E293B)
        )

        Spacer(modifier = Modifier.width(8.dp))

        Text(
            text = "${(value * 100).toInt()}%",
            color = Color.White,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(36.dp),
            textAlign = TextAlign.End
        )
    }
}

@Composable
fun ControlButtonsSection(
    status: ConnectionStatus,
    onAutoDiscover: () -> Unit,
    onStartDemo: () -> Unit,
    onDirectIp: () -> Unit,
    onStop: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = onAutoDiscover,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text("📡 Auto Faro", fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }

            Button(
                onClick = onStartDemo,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4F46E5)),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text("🎮 Probar IA", fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = onDirectIp,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text("🔌 IP Directa", fontSize = 13.sp)
            }

            if (status != ConnectionStatus.IDLE) {
                Button(
                    onClick = onStop,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF475569)),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("⏹ Detener", fontSize = 13.sp)
                }
            }
        }
    }
}
