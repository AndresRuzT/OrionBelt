# ORION v6: Mobile Android (en desarrollo)

ORION corriendo en un celular Android: el XIAO ESP32S3 Sense se conecta a la Zona Wi-Fi del teléfono, la app recibe el video, estima la profundidad con MiDaS en el propio celular y avisa de obstáculos con vibración, audio estéreo y el buzzer del cinturón, también con la pantalla apagada.

Plan completo: [`docs/superpowers/plans/2026-11-04-orion-v6-mobile-android.md`](../../docs/superpowers/plans/2026-11-04-orion-v6-mobile-android.md)

## Estructura

| Carpeta | Contenido | Fase |
| :--- | :--- | :--- |
| `tools/` | Herramientas Python de PC: descarga del modelo, pipeline de referencia, fixtures y benchmark | 1 |
| `firmware/XIAO_Camera_Orion_Mobile/` | Firmware del XIAO para la Zona Wi-Fi del celular | 2 |
| `android/` | Proyecto de Android Studio (Kotlin) | 3–7 |

## Archivos que no se versionan

- `firmware/**/secrets.h`: SSID y contraseña de la Zona Wi-Fi. Se copia desde `secrets.example.h`.
- `android/app/src/main/assets/*.tflite` y `tools/models/`: el modelo (33 MB) se descarga con `tools/fetch_model.py`.
- `tools/.venv/`: entorno Python de las herramientas.

## Entorno de herramientas (Windows)

```powershell
cd versions/v6_mobile_android/tools
python -m venv .venv
.\.venv\Scripts\python -m pip install -r requirements-tools.txt
```
