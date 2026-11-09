// ============================================================================
// XIAO_Camera_Orion_Mobile.ino
// Firmware de Alta Velocidad (30+ FPS Sostenidos) para Seeed Studio XIAO ESP32S3
// Versión: ORION v6 Mobile (Soporte Multi-Red con Prioridad y Faro Doble Broadcast)
// ============================================================================

#include "esp_camera.h"
#include "esp_log.h"
#include "esp_wifi.h"
#include <WiFi.h>
#include <WiFiUdp.h>
#include "camera_pins.h"
#include "secrets.h"
#include "xiao_stream_httpd.h"

// ----------------------------------------------------------------------------
// Configuración de Faro UDP (Para descubrimiento automático desde App Móvil/PC)
// ----------------------------------------------------------------------------
WiFiUDP udp;
const int UDP_BEACON_PORT = 9999;
unsigned long lastBeaconTime = 0;
const unsigned long BEACON_INTERVAL_MS = 1000; // 1 segundo para enlace rápido

unsigned long lastDisconnectCheck = 0;
int activeNetworkIndex = -1;

void sendBeacon() {
    if (WiFi.status() != WL_CONNECTED) return;
    String beaconMsg = "ORION_XIAO_CAM:" + WiFi.localIP().toString() + ":81";

    // 1. Broadcast global limitado (255.255.255.255)
    udp.beginPacket(IPAddress(255, 255, 255, 255), UDP_BEACON_PORT);
    udp.write((const uint8_t*)beaconMsg.c_str(), beaconMsg.length());
    udp.endPacket();

    // 2. Broadcast dirigido a la subred (localIP | ~subnetMask)
    // Esencial para Android: muchos modelos filtran el broadcast 255.255.255.255 en Hotspot
    IPAddress ip = WiFi.localIP();
    IPAddress mask = WiFi.subnetMask();
    IPAddress subnetBroadcast;
    for (int i = 0; i < 4; i++) {
        subnetBroadcast[i] = ip[i] | (~mask[i]);
    }

    if (subnetBroadcast != IPAddress(255, 255, 255, 255)) {
        udp.beginPacket(subnetBroadcast, UDP_BEACON_PORT);
        udp.write((const uint8_t*)beaconMsg.c_str(), beaconMsg.length());
        udp.endPacket();
    }
}

// ----------------------------------------------------------------------------
// Gestor de Conexión Wi-Fi con Prioridad Estricta
// ----------------------------------------------------------------------------
bool attemptConnection(int index, unsigned long timeoutMs) {
    if (index < 0 || index >= (int)NUM_KNOWN_NETWORKS) return false;
    const char* targetSsid = KNOWN_NETWORKS[index].ssid;
    const char* targetPass = KNOWN_NETWORKS[index].password;

    if (strlen(targetSsid) == 0) return false;

    Serial.printf("\n📡 [WIFI] Probando [%s] (%s)...", targetSsid, KNOWN_NETWORKS[index].description);

#if ORION_USE_STATIC_IP
    IPAddress staticIP(ORION_STATIC_IP);
    IPAddress gateway(ORION_GATEWAY);
    IPAddress subnet(ORION_SUBNET);
    WiFi.config(staticIP, gateway, subnet);
#endif

    WiFi.disconnect();
    delay(100);
    WiFi.begin(targetSsid, targetPass);

    unsigned long startWait = millis();
    while (WiFi.status() != WL_CONNECTED && (millis() - startWait < timeoutMs)) {
        delay(250);
        Serial.print(".");
    }

    if (WiFi.status() == WL_CONNECTED) {
        activeNetworkIndex = index;
        Serial.println("\n✅ [WIFI] ¡Conectado con éxito!");
        Serial.printf("   📍 Red activa:   %s (%s)\n", targetSsid, KNOWN_NETWORKS[index].description);
        Serial.printf("   📍 Dirección IP: http://%s\n", WiFi.localIP().toString().c_str());
        Serial.printf("   📹 URL Stream:   http://%s:81/stream\n", WiFi.localIP().toString().c_str());
        Serial.printf("   ⚙️ URL Control:  http://%s/status\n", WiFi.localIP().toString().c_str());
        Serial.printf("   📶 Intensidad:   %d dBm\n", WiFi.RSSI());

        // Desactivar ahorro de energía (Modem-Sleep) para máxima estabilidad de frames
        esp_wifi_set_ps(WIFI_PS_NONE);
        // Potencia equilibrada (17 dBm): previene recalentamiento del sensor OV2640
        WiFi.setTxPower(WIFI_POWER_17dBm);
        return true;
    }

    Serial.println("\n⏳ [WIFI] No disponible o tiempo agotado.");
    return false;
}

void connectWifiPriority() {
    WiFi.mode(WIFI_STA);
    WiFi.setAutoReconnect(true);

    // 1. FAST-PROBE: Probar primero la Red 0 (Celular principal) por 2.5 segundos
    // Si el punto de acceso está activo, el enlace se logra en ~800 ms sin demoras de escaneo.
    if (NUM_KNOWN_NETWORKS > 0 && strlen(KNOWN_NETWORKS[0].ssid) > 0) {
        Serial.println("⚡ [WIFI] Fast-Probe: Verificando Red de Máxima Prioridad (Celular)...");
        if (attemptConnection(0, 2500)) {
            return;
        }
    }

    // 2. PRIORITY-SCAN: Si la Red 0 no respondió, escanear el espectro
    Serial.println("🔍 [WIFI] Escaneando redes 2.4 GHz disponibles en el entorno...");
    int n = WiFi.scanNetworks(false, false);
    Serial.printf("   Encontradas %d redes en el aire.\n", n);

    if (n > 0) {
        // Evaluar en orden de prioridad estricta: índice 0, 1, 2...
        for (size_t prio = 0; prio < NUM_KNOWN_NETWORKS; prio++) {
            if (strlen(KNOWN_NETWORKS[prio].ssid) == 0) continue;

            // Verificar si el SSID de prioridad 'prio' fue visto en el escaneo
            bool foundInAir = false;
            for (int i = 0; i < n; i++) {
                if (WiFi.SSID(i) == KNOWN_NETWORKS[prio].ssid) {
                    foundInAir = true;
                    break;
                }
            }

            if (foundInAir) {
                Serial.printf("🎯 [WIFI] Red prioritaria detectada en el aire: '%s'\n", KNOWN_NETWORKS[prio].ssid);
                if (attemptConnection(prio, 8000)) {
                    WiFi.scanDelete();
                    return;
                }
            }
        }
        WiFi.scanDelete();
    }

    // 3. Si ninguna conocida fue detectada, fallback cíclico cada 3 segundos
    Serial.println("⚠️ [WIFI] Ninguna red conocida disponible. Reintentando en 3 segundos...");
}

void setup() {
    Serial.begin(115200);
    delay(600);

    Serial.println("\n==================================================");
    Serial.println("🚀  Iniciando XIAO ESP32S3 Sense - ORION v6 Mobile");
    Serial.println("==================================================");

    // ------------------------------------------------------------------------
    // 1. INICIALIZACIÓN INMEDIATA DE PSRAM (8 MB OPI)
    // ------------------------------------------------------------------------
    if (!psramFound()) {
        psramInit();
    }
    size_t psram_size = ESP.getPsramSize();
    bool has_psram = psramFound() || (psram_size > 0);

    if (has_psram) {
        Serial.printf("✅ [PSRAM] PSRAM activa (%d MB). Doble/Triple buffer habilitado.\n",
                      (int)(psram_size > 0 ? (psram_size / (1024 * 1024)) : 8));
    } else {
        Serial.println("⚠️ [PSRAM] PSRAM no detectada. Verifica en Arduino IDE: Tools > PSRAM: 'OPI PSRAM'.");
    }

    // ------------------------------------------------------------------------
    // 2. CONEXIÓN WI-FI CON PRIORIDAD
    // ------------------------------------------------------------------------
    connectWifiPriority();

    // ------------------------------------------------------------------------
    // 3. INICIALIZACIÓN DE LA CÁMARA (OV2640 - ESTABILIDAD 16 MHz Y TRIPLE BÚFER)
    // ------------------------------------------------------------------------
    camera_config_t config;
    config.ledc_channel = LEDC_CHANNEL_0;
    config.ledc_timer   = LEDC_TIMER_0;
    config.pin_d0       = Y2_GPIO_NUM;
    config.pin_d1       = Y3_GPIO_NUM;
    config.pin_d2       = Y4_GPIO_NUM;
    config.pin_d3       = Y5_GPIO_NUM;
    config.pin_d4       = Y6_GPIO_NUM;
    config.pin_d5       = Y7_GPIO_NUM;
    config.pin_d6       = Y8_GPIO_NUM;
    config.pin_d7       = Y9_GPIO_NUM;
    config.pin_xclk     = XCLK_GPIO_NUM;
    config.pin_pclk     = PCLK_GPIO_NUM;
    config.pin_vsync    = VSYNC_GPIO_NUM;
    config.pin_href     = HREF_GPIO_NUM;
    config.pin_sccb_sda = SIOD_GPIO_NUM;
    config.pin_sccb_scl = SIOC_GPIO_NUM;
    config.pin_pwdn     = PWDN_GPIO_NUM;
    config.pin_reset    = RESET_GPIO_NUM;

    // 16 MHz: Frecuencia óptima recomendada por Seeed Studio para máxima estabilidad térmica
    config.xclk_freq_hz = 16000000;
    config.pixel_format = PIXFORMAT_JPEG;

    // Resolución QVGA (320x240): Ultra ligera (~2.5 KB por frame con quality=20),
    // reduce consumo de ancho de banda y latencia hacia el teléfono móvil.
    config.frame_size   = FRAMESIZE_QVGA;
    config.jpeg_quality = 20;

    if (has_psram) {
        config.fb_count     = 3;                  // Triple buffer DMA en PSRAM
        config.grab_mode    = CAMERA_GRAB_LATEST; // Siempre entrega el fotograma más fresco sin retraso
        config.fb_location  = CAMERA_FB_IN_PSRAM;
    } else {
        config.fb_count     = 2;
        config.grab_mode    = CAMERA_GRAB_LATEST;
        config.fb_location  = CAMERA_FB_IN_DRAM;
    }

    esp_err_t err = esp_camera_init(&config);
    if (err != ESP_OK) {
        Serial.printf("❌ [CAM] Error al inicializar cámara: 0x%x\n", err);
        return;
    }
    Serial.println("✅ [CAM] Sensor OV2640 activo en QVGA (320x240) a 16 MHz.");

    // Ajustes finos de imagen
    sensor_t *s = esp_camera_sensor_get();
    if (s != NULL) {
        s->set_brightness(s, 1);
        s->set_contrast(s, 1);
        s->set_saturation(s, 0);
        s->set_whitebal(s, 1);
        s->set_awb_gain(s, 1);
        s->set_exposure_ctrl(s, 1);
    }

    // ------------------------------------------------------------------------
    // 4. INICIAR SERVIDORES HTTP Y FARO UDP
    // ------------------------------------------------------------------------
    startCameraServer();

    udp.begin(UDP_BEACON_PORT);
    for (int i = 0; i < 3; i++) {
        sendBeacon();
        delay(40);
    }
    Serial.println("📻 [BEACON] Faro de anuncio UDP activo en puerto 9999.");

    Serial.println("\n==================================================");
    Serial.println("🎉  XIAO ESP32S3 Mobile listo y transmitiendo stream");
    Serial.println("==================================================\n");
}

void loop() {
    unsigned long now = millis();

    // 1. Watchdog de conexión Wi-Fi: si se desconecta, reintentar conexión con prioridad
    if (WiFi.status() != WL_CONNECTED) {
        if (now - lastDisconnectCheck >= 3000) {
            lastDisconnectCheck = now;
            Serial.println("⚠️ [WIFI] Conexión perdida. Intentando reconexión por prioridad...");
            connectWifiPriority();
        }
        delay(50);
        return;
    }

    // 2. Silenciar faro UDP mientras haya un cliente consumiendo video para preservar ancho de banda
    if (!isClientStreaming() && (now - lastBeaconTime >= BEACON_INTERVAL_MS)) {
        lastBeaconTime = now;
        sendBeacon();
    }

    delay(20);
}
