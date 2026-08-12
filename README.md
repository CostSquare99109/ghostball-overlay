# Ghost Ball Overlay

> **Overlay flotante para billar 8-ball.** Marca la bola blanca y la objetivo sobre **cualquier app** (juego, videollamada apuntando a una mesa real) y dibuja automáticamente la trayectoria más fácil hacia una de las 6 troneras — tiro directo o a banda — usando el **método de la bola fantasma** + **método del espejo** para bandas.

![Ghost Ball Overlay](screenshots/preview.png)

---

## Características

| Función | Descripción |
|---------|-------------|
| 🎯 **Bola fantasma** | Calcula el punto de mira exacto para que la bola objetivo caiga en la tronera elegida. |
| 🔁 **Tiros a banda** | Refleja la tronera sobre cada banda (espejo), encuentra el punto de impacto y dibuja la trayectoria. |
| 📐 **Score inteligente** | Ángulo de corte + 10° de penalización para bandas → prioriza directos cuando son casi iguales. |
| 📱 **Overlay transparente** | `TYPE_APPLICATION_OVERLAY` — dibuja sobre cualquier app sin root. |
| 🔘 **Botón flotante** | Arrastrable, activa/desactiva el modo dibujo para no bloquear el uso normal del celular. |
| 🎱 **Definir mesa** | Marca 6 puntos en pantalla = troneras reales de TU mesa; se guardan en `SharedPreferences`. |
| 🧮 **Geometría pura** | `ShotCalculator.kt` sin deps Android — testeable con JUnit en JVM. |
| 📦 **Sin cámara / sin ML** | Coordenadas en píxeles de pantalla. No hay homografía ni visión artificial en v1. |

---

## Requisitos

- Android **8.0 (API 26)** o superior.
- Permiso **“Mostrar sobre otras apps”** (`SYSTEM_ALERT_WINDOW`).
- Android 13+: permiso **Notificaciones** (`POST_NOTIFICATIONS`).

---

## Instalación

### Opción A — Descargar APK desde GitHub Actions (recomendado)

1. Ve a la pestaña **Actions** del repo → último workflow `Build GhostBallOverlay APK`.
2. Descarga el artifact **`ghostball-debug-apk`**.
3. Descomprime y transfiere `app-debug.apk` al móvil.
4. Instala (permite “Instalar apps desconocidas” si te lo pide).

### Opción B — Compilar localmente (Linux/macOS/Windows con Android SDK)

```bash
git clone https://github.com/CostSquare99109/ghostball-overlay.git
cd ghostball-overlay
./gradlew assembleDebug
# APK en: app/build/outputs/apk/debug/app-debug.apk
```
---

## Uso

1. **Abre la app** → pulsa **“Conceder permiso de overlay”** → se abre Ajustes → activa “Permitir mostrar sobre otras apps” para Ghost Ball Overlay → vuelve.
2. Pulsa **“Iniciar overlay”**. La app se cierra y aparece un **botón flotante** (círculo pequeño) en el borde izquierdo.
3. **Toca el botón flotante** → se pone en ✓ (modo dibujo activado). Ahora la pantalla completa captura toques.
4. **Primer toque** = bola blanca (B). **Segundo toque** = bola objetivo (O).
5. La app calcula y dibuja:
   - Línea **blanca→fantasma** (discontinua, color claro).
   - Línea **objetivo→tronera** (o →banda→tronera, naranja discontinua).
   - Punto de impacto en banda = punto dorado.
   - Etiqueta flotante con **ángulo de corte** y tipo de tiro.
6. **Toca la esquina superior-izquierda** (zona de 96×96 dp) para **resetear** ambas bolas.
7. **Mantén el botón flotante** para **arrastrarlo** a donde no estorbe.
8. **Toca el botón flotante otra vez** (◎) → modo dibujo desactivado → los toques vuelven a pasar a la app de abajo.
9. Para **detener el overlay del todo**: arrastra la notificación persistente → “Detener”.

---

## Modo “Definir mesa” (troneras reales)

Por defecto la app usa los **4 bordes + centros de pantalla** como referencia de bandas/troneras. Para usar **troneras reales de tu mesa**:

1. En `OverlayView` (la pantalla de dibujo), las troneras se marcan tocando 6 veces (no hay UI dedicada aún — pendiente v1.1).
2. Cuando tengas 6 puntos guardados, `TableConfig` los persiste en `SharedPreferences` y la app los usa en cálculos posteriores.
3. Para borrar: `TableConfig.clearPockets()` (pendiente botón en UI).

> **Limitación v1.0:** no hay pantalla visual para “marcar 6 troneras” todavía. Se guardan programáticamente o añadiendo un flujo en `MainActivity` (ver sección *Pendientes*).

---

## Limitaciones importantes (honestidad brutal)

| Qué NO hace v1.0 | Por qué |
|------------------|---------|
| ❌ No detecta la mesa ni las bolas automáticamente | Sin cámara, sin ML, sin visión artificial. Coordenadas = toques del usuario en píxeles de pantalla. |
| ❌ No corrige perspectiva / homografía | Si grabas la mesa en ángulo, las distancias en píxeles ≠ distancias reales. El ángulo de corte en pantalla **no es** el ángulo real sobre el paño. |
| ❌ No simula efectos (retroceso, seguimiento, masa) | Solo geometría de choque central (bola fantasma). |
| ❌ No guarda “perfiles de mesa” con nombres | Solo un set de 6 troneras en `SharedPreferences`. |
| ❌ No exporta / comparte tiros | Solo dibujo en pantalla. |

**Úsalo como ayuda visual de “hacia dónde apuntar” en pantalla, no como sustituto de saber jugar.** Para precisión real necesitas calibrar con homografía (v2.0).

---

## Arquitectura

```
app/src/main/java/com/johan/ghostball/
├── MainActivity.kt        // Permiso overlay + lanza servicio
├── OverlayService.kt      // Foreground service + WindowManager (2 ventanas)
├── OverlayView.kt         // Canvas + touch + dibujo de trayectoria
├── ShotCalculator.kt      // Geometría PURA (sin Android deps) — JUnit testeable
└── TableConfig.kt         // SharedPreferences para troneras usuario
```

- **ShotCalculator** es la única clase con lógica matemática. Cero dependencias de Android → corre tests en JVM pura (`./gradlew testDebugUnitTest`).
- **OverlayService** mantiene dos `WindowManager` views:
  1. `triggerView` (56 dp, `FLAG_NOT_FOCUSABLE`, arrastrable).
  2. `overlayView` (full-screen, `FLAG_NOT_TOUCHABLE` por defecto; se quita al activar modo dibujo).

---

## Build & CI

- **Gradle 8.7** + **AGP 8.5.2** + **Kotlin 1.9.24** + **JDK 17 (Temurin)**.
- `build.gradle.kts` (Kotlin DSL) en todo el proyecto.
- GitHub Actions: `ubuntu-latest` → `./gradlew testDebugUnitTest` → `assembleDebug` → sube APK como artifact `ghostball-debug-apk`.
- Cache de `~/.gradle/caches` + `~/.gradle/wrapper` entre runs.

```yaml
# .github/workflows/build.yml
jobs:
  build:
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: 17, cache: gradle }
      - run: ./gradlew testDebugUnitTest --no-daemon
      - run: ./gradlew assembleDebug --no-daemon
      - uses: actions/upload-artifact@v4
        with: { name: ghostball-debug-apk, path: app/build/outputs/apk/debug/*.apk }
```

---

## Tests

```bash
./gradlew testDebugUnitTest
```

Casos cubiertos:

| Test | Qué verifica |
|------|--------------|
| `directShot_basicGeometry` | Tiro recto → corte 0°, ghost ball 1 diámetro atrás. |
| `directShot_cutAngleCorrect` | Cue→obj en X, obj→pocket en Y → 90°. |
| `bankShot_singleRail` | Banda superior → punto de impacto en y≈0. |
| `bankShot_prefersDirectWhenWithinPenalty` | Penalización +10° hace ganar al directo. |
| `extremeCutAngle_over78_degrees` | Corte ~80° sigue devolviendo shot válido. |
| `multiplePockets_returnsSortedByScore` | Ordena por score (menor = mejor). |
| `bankImpactInsideTableBounds` | Impacto dentro del rectángulo de mesa. |
| `noShotsWhenCueEqualsObject` | Degenerado → lista vacía. |

---

## Licencia

**MIT License** — ver [LICENSE](LICENSE).

> Libre para usar, modificar, distribuir. Atribución apreciada.

---

## Autor

**Jhon Fredy Montalvo Cuadrado** (17 años, Carepa, Antioquia)  
Estudiante Uniminuto — Proyecto de Vida NRC 1516/1520  
GitHub: [@CostSquare99109](https://github.com/CostSquare99109)

---

## Pendientes / Roadmap

- [ ] Pantalla “Definir mesa” con 6 toques guiados + labels visuales.
- [ ] Exportar/importar perfiles de mesa (JSON).
- [ ] Modo “calibración con homografía” usando 4 esquinas conocidas (v2.0).
- [ ] Ajuste de `BALL_RADIUS` en UI (slider) para mesas de distintos tamaños en pantalla.
- [ ] Tema claro/oscuro automático en overlay.
- [ ] Vibración háptica al colocar bolas.
- [ ] Tests de integración con Robolectric (OverlayView + Service).

---

## Agradecimientos

- Geometría de bola fantasma + espejo: billar clásico, implementación validada en prototipo HTML/JS previo.
- Android `TYPE_APPLICATION_OVERLAY` + Foreground Service pattern.
- CI inspirado en workflows previos del mismo autor (calculadora APK, descargador video).