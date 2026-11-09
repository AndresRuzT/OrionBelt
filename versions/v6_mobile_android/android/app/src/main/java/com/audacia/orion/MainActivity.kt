package com.audacia.orion

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.audacia.orion.ui.theme.ORIONTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val modelAssetExists = try {
            assets.open("midas_small_256_fp16.tflite").use { true }
        } catch (e: Exception) {
            false
        }

        setContent {
            ORIONTheme {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = Color(0xFF0F172A)
                ) { innerPadding ->
                    OrionInitialScreen(
                        modifier = Modifier.padding(innerPadding),
                        modelLoaded = modelAssetExists
                    )
                }
            }
        }
    }
}

@Composable
fun OrionInitialScreen(modifier: Modifier = Modifier, modelLoaded: Boolean) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "ORION v6",
            color = Color(0xFF38BDF8),
            fontSize = 36.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )
        Text(
            text = "Optical Recognition & Intelligent Obstacle Navigation",
            color = Color(0xFF94A3B8),
            fontSize = 13.sp,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(32.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = "ESTADO DEL ENTORNO MÓVIL",
                    color = Color(0xFF64748B),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(12.dp))

                InfoRow("Dispositivo", "${Build.MANUFACTURER.uppercase()} ${Build.MODEL}")
                InfoRow("Android", "API ${Build.VERSION.SDK_INT} (${Build.VERSION.RELEASE})")
                InfoRow("Arquitectura", Build.SUPPORTED_ABIS.firstOrNull() ?: "Desconocida")

                Spacer(modifier = Modifier.height(12.dp))

                val statusColor = if (modelLoaded) Color(0xFF4ADE80) else Color(0xFFF87171)
                val statusText = if (modelLoaded) "CARGADO (33.5 MB FP16)" else "NO ENCONTRADO"
                InfoRow("Core IA MiDaS", statusText, valueColor = statusColor)
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Box(
            modifier = Modifier
                .background(Color(0xFF0284C7).copy(alpha = 0.15f), RoundedCornerShape(12.dp))
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            Text(
                text = "✓ Fase 3 lista. Esperando inicio de streaming (Fase 4)",
                color = Color(0xFF38BDF8),
                fontSize = 13.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
fun InfoRow(label: String, value: String, valueColor: Color = Color.White) {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Text(text = label, color = Color(0xFF94A3B8), fontSize = 12.sp)
        Text(
            text = value,
            color = valueColor,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = FontFamily.Monospace
        )
    }
}
