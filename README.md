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
| 🖼️ **Detección automática (v4)** | Captura 1 frame con `MediaProjection`, detecta bolas por color+forma (Kotlin puro, sin OpenCV/NDK). |
| 🎯 **Multi-objetivo (v6)** | Tras la detección, **todas** las bolas detectadas (menos la blanca) son objetivos simultáneos: cada una en su color, con su mejor tiro calculado y una recomendación global destacada. Sin tocar la pantalla. |
| 🎯 **Calibración de bola** | 2 toques (centro + borde) definen el radio de bola en px — una vez por juego/resolución. |
| 🧮 **Geometría pura** | `ShotCalculator.kt` sin deps Android — testeable con JUnit en JVM. |
| 📦 **Sin cámara / sin ML** | Coordenadas en píxeles de pantalla. No hay homografía ni visión artificial en v1. |

---

## Requisitos

- Android **8.0 (API 26)** o superior.
- Permiso **“Mostrar sobre otras apps”** (`SYSTEM_ALERT_WINDOW`).
- Android 13+: permiso **Notificaciones** (`POST_NOTIFICATIONS`).
- Detección automática (v4): en la **primera** pulsación de `⌖`, Android pide consentimiento de captura de pantalla. Solo se vuelve a pedir si detienes el servicio o revocas el permiso.

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

## Detección automática de bolas (v4)

**Escenario soportado:** juego de billar digital en pantalla (no cámara física). La app captura la pantalla bajo demanda, recorta al rectángulo de mesa definido y localiza las bolas por color+forma, todo en **Kotlin puro** (sin OpenCV, sin NDK — el pipeline de CI sigue siendo JVM).

### Flujo de uso

1. **Define la mesa** (botón `M`) — igual que en v3.
2. **Calibra el radio de bola** (botón `⊙`): toca el *centro* de una bola visible y luego su *borde*. Se guarda asociado a la resolución actual.
3. Pulsa **`⌖` Detectar**:
   - La primera vez pedirá el permiso de captura de pantalla (`MediaProjection`). Se concede **una vez por sesión** del servicio, no en cada detección.
   - Se captura **un único frame**, se recorta a la mesa y se corre el detector.
4. Todas las bolas detectadas se dibujan con un **color propio** (paleta fija: rojo, naranja, amarillo, lima, cian, azul, violeta, magenta, rosa, teal — el blanco queda reservado para la bola blanca).
5. **Sin tocar nada**, cada bola objetivo tiene calculado su mejor tiro (directo o banda) y se dibuja como línea fina semi-transparente en su color. La **línea gruesa "MEJOR OPCIÓN"** es la recomendación global: la bola cuyo tiro tiene el menor score (menor ángulo de corte + penalización de banda).
6. **Opcional:** toca una bola objetivo para **aislar su línea** (oculta las demás); tócala otra vez o pulsa **✶** en el menú para "ver todas". Si el detector no logró clasificar la bola blanca, toca la blanca entre las detectadas y el pipeline sigue normal.
7. Si una bola detectada quedó mal ubicada, **arrástrala** para corregirla (misma lógica de drag del modo manual).

> El **modo manual sigue intacto**: sin detectar nada, los toques dentro de la mesa colocan B y O como siempre. La detección es aditiva, no reemplaza nada.

### Límites reales (honestidad brutal)

| La detección NO es infalible | Por qué |
|------------------------------|---------|
| 🎨 **Depende del skin del juego** | Colores/tamaños de bola varían entre apps. El detector usa umbrales configurados en `DetectorConfig` (el primero a ajustar ante falsos positivos es `fillMin`/`fillMax`). |
| 🧩 **Bolas pegadas o tapadas por UI** | Marcador/botones del juego encima de la mesa pueden generar falsos positivos o bolas fusionadas. El detector es deliberadamente **conservador**: prefiere no detectar antes que fabricar bolas falsas. |
| ⚙️ **Requiere calibración** | El radio de bola debe calibrarse por juego/resolución. Sin calibrar, `⌖` queda deshabilitado (no falla en silencio). |
| 🔄 **Blanca sin clasificar** | Si ninguna bola pasa el umbral de "blanca", la detección no aborta: se dibujan todas las bolas en sus colores y tocas la blanca manualmente — de ahí en adelante el flujo multi-objetivo corre normal. |
| 🎨 **Colores del overlay ≠ colores reales** | El color asignado a cada bola en el overlay (paleta fija) solo sirve para distinguirlas visualmente; **no** intenta identificar el color real de la bola en el juego. Eso sería un paso adicional de clasificación por color que no está implementado. |
| 📐 **Orientación fija** | Si rotas la pantalla, el rectángulo de mesa se invalida y hay que re-definirlo/calibrar (igual que en v3). |

**Por eso el modo manual no es un adorno: sigue siendo el respaldo real.**

---

## Limitaciones importantes (honestidad brutal)

| Qué NO hace v1.0 | Por qué |
|------------------|---------|
| ❌ No detecta la mesa automáticamente | La mesa se define manualmente (botón `M`). Las bolas sí se detectan (v4), con los límites de la sección anterior. |
| ❌ La detección no reconoce colores ni troneras | Solo localiza bolas por forma/tamaño/color dominante. No distingue "roja" vs "amarilla" ni lee números. |
| ❌ No corrige perspectiva / homografía | Si grabas la mesa en ángulo, las distancias en píxeles ≠ distancias reales. El ángulo de corte en pantalla **no es** el ángulo real sobre el paño. |
| ❌ No simula efectos (retroceso, seguimiento, masa) | Solo geometría de choque central (bola fantasma). |
| ❌ No guarda “perfiles de mesa” con nombres | Solo un set de 6 troneras en `SharedPreferences`. |
| ❌ No exporta / comparte tiros | Solo dibujo en pantalla. |

**Úsalo como ayuda visual de “hacia dónde apuntar” en pantalla, no como sustituto de saber jugar.** Para precisión real necesitas calibrar con homografía (v2.0).

---

## Arquitectura

```
app/src/main/java/com/johan/ghostball/
├── MainActivity.kt                    // Permiso overlay + lanza servicio
├── OverlayService.kt                  // Foreground service + WindowManager + orquestación v4
├── OverlayView.kt                     // Canvas + touch + dibujo (manual + bolas detectadas + calibración)
├── ShotCalculator.kt                  // Geometría PURA (sin Android deps) — JUnit testeable
├── TargetRecommender.kt               // Recomendación multi-objetivo PURA (v6) — JUnit testeable
├── TargetPalette.kt                   // Paleta de colores fija para distinguir bolas objetivo (v6)
├── BallDetector.kt                    // Detección PURA (sin Android deps) — JUnit testeable
├── ScreenCapture.kt                   // MediaProjection: 1 frame por llamada, sesión persistente
├── MediaProjectionPermissionActivity.kt // Activity translúcida para consentimiento de captura
└── TableConfig.kt                     // SharedPreferences: mesa + troneras + radio de bola
```

- **ShotCalculator** y **BallDetector** son las únicas clases con lógica matemática/visión. Cero dependencias de Android → corren tests en JVM pura (`./gradlew testDebugUnitTest`).
- **BallDetector** trabaja sobre `BallImage` (IntArray propio) en vez de `Bitmap` — los tests JVM sintetizan píxeles sin Robolectric. `ScreenCapture` adapta `Bitmap` → `BallImage` en runtime.
- **MediaProjection**: la sesión se pide 1 vez (activity translúcida), se mantiene viva mientras el servicio corre, y cada `⌖` captura un único frame (ImageReader + VirtualDisplay liberados al instante).
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
| `whiteBallOnly_isDetectedAndClassifiedAsCue` | Bola blanca sola → 1 detección, marcada cue, centro correcto. |
| `whitePlusColored_twoBalls_exactlyOneCue` | Blanca + roja → 2 bolas, solo la blanca es cue. |
| `noBalls_returnsEmptyList` | Paño limpio → lista vacía, sin crash. |
| `feltColorAutoSampledFromCenter_whenNotSupplied` | Fieltro inferido del centro de la captura. |
| `rectangularBlob_rejectedByShape` | Iconos/marcadores rectangulares → filtrados. |
| `tinyNoiseBlob_rejectedByArea` / `oversizedBlob_rejectedByArea` | Ruido y blobs fuera de tamaño → filtrados. |
| `detect_downscalesLargerThanMaxAnalysisDim` | 800×600 con radio 40 → detecta y mapea centro a coords originales. |
| `singleTarget_isGlobalBest` | 1 objetivo → es automáticamente la recomendación global. |
| `threeTargetsKnownAngles_globalBestIsLowestScore` | 3 objetivos con cortes {0°, 11°, 43°} → gana el de 0°. |
| `equalScores_tieBrokenByLowerColorIndex` | Empate de score → gana el de menor orden de detección. |
| `cueEqualsTarget_skippedButOthersWork` / `allDegenerate_returnsNullGlobalBest` | Objetivos degenerados se saltan, sin crash; todos degenerados → sin recomendación. |
| `noTargets_returnsEmptyWithoutCrash` / `emptyPockets_returnsNullGlobalBest` | Casos vacíos → sin crash, null global. |
| `bankPenalty_prefersDirectWithinPenalty` | TargetRecommender hereda la penalización de banda de ShotCalculator. |

---

## Licencia

**MIT License** — ver [LICENSE](LICENSE).

> Libre para usar, modificar, distribuir. Atribución apreciada.

---
---

## Pendientes / Roadmap

- [ ] Pantalla “Definir mesa” con 6 toques guiados + labels visuales.
- [ ] Exportar/importar perfiles de mesa (JSON).
- [x] Detección automática de bolas por captura de pantalla (v4 — ver sección arriba).
- [x] Calibración de radio de bola por juego/resolución (v4, 2 toques).
- [ ] Ajuste fino de umbrales de detección desde la UI (hoy: constantes en `DetectorConfig`).
- [ ] Modo “calibración con homografía” usando 4 esquinas conocidas (v2.0).
- [ ] Tema claro/oscuro automático en overlay.
- [ ] Vibración háptica al colocar bolas.
- [ ] Tests de integración con Robolectric (OverlayView + Service).
- [ ] Limpiar docs: el README dice "+10° de penalización para bandas" pero el código usa `BANK_PENALTY = 20f` — el texto está desactualizado, el cálculo real no cambió.

---