package com.audacia.orion.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Cliente remoto para el Buzzer PWM del XIAO ESP32S3 (/buzzer en puerto 80).
 *
 * Implementa control de saturación no bloqueante: si una petición HTTP hacia el buzzer
 * aún está en vuelo, la nueva llamada se descarta para evitar encolar latencia.
 */
class BuzzerClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(500, TimeUnit.MILLISECONDS)
        .readTimeout(800, TimeUnit.MILLISECONDS)
        .build()
) {
    private val inFlight = AtomicBoolean(false)

    fun triggerBeep(
        endpoint: XiaoEndpoint,
        durationMs: Int = 150,
        frequencyHz: Int = 2000,
        duty: Int = 200
    ) {
        if (!inFlight.compareAndSet(false, true)) {
            // Ya hay una solicitud en curso, descartar para no acumular latencia
            return
        }

        val url = "${endpoint.controlUrl}/buzzer?beep=$durationMs&freq=$frequencyHz&duty=$duty"
        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                inFlight.set(false)
            }

            override fun onResponse(call: Call, response: Response) {
                response.close()
                inFlight.set(false)
            }
        })
    }
}
