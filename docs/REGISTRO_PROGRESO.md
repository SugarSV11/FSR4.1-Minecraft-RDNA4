# Registro de progreso — integración SugarSV (se actualiza tras cada paso)

## Si se fue la luz / se cortó la sesión
1. Comprobar que no queda un cliente abierto y volver a dejar los mods originales:
   `powershell -ExecutionPolicy Bypass -File C:\Users\Sugar\build\mcfsr4\scripts\windows\mod_profile.ps1 -Stage restore`
   (repetir hasta que `fabric\run\mods` tenga solo `aetherium-0.1.3` y `sodium-fabric-0.9.2`; `fabric\run\mod_profile.txt` dice la última etapa aplicada).
2. Leer este archivo y `docs/MOD_INTEGRATION.md`. Último estado guardado: `git log` en la rama `windows11-port`.

## 2026-11-14
- 13:3x Analizados los 27 JAR (ver `docs/MOD_INTEGRATION.md`). Creado `scripts/windows/mod_profile.ps1` (perfiles por etapas, reversibles).
  `bench_win.ps1` desactiva las 3 optimizaciones de memoria de Aetherium cuando FerriteCore está cargado.
- 13:50 **Línea base medida** (Aetherium 0.1.3 + Sodium 0.9.2, Better Voxels alto, renderScale 65, FSR 4.1 QUALITY, 1080p, sin VSync, 3x200 ticks):
  62.9 / 62.9 / 63.0 FPS reales; frametime p50 ~15.8 ms, p95 18.0–18.5 ms, p99 18.7–19.6 ms; 1 % low 44.7–49.2; FSR GPU 0.90 ms; FG apagado (0 generados).
- 13:51 **s1 (Sodium 0.9.3-beta.5) NO arranca**: `aetherium.client.mixins.json:chunks.ShaderChunkRendererMixin` falla (Sodium cambió el constructor de
  `ShaderChunkRenderer`). Consecuencia: MoreCulling (exige Sodium > 0.9.3-beta.1) queda fuera. s2/s3 rehechos sobre Sodium 0.9.2.
- 13:55 s2 (ImmediatelyFast, BadOptimizations, EntityCulling, Dynamic FPS) y s3 (+ FerriteCore, Lithium, C2ME, ServerCore, Clumps, Alternate Current):
  ambos cargan sin errores de mixin, pero dan 59.8–59.9 FPS con p50 16.6 ms en las 6 tandas = tope de 60. Causa: Dynamic FPS, estado `hovered`
  (ventana visible sin foco, como en el banco de pruebas) = 60 FPS por defecto; además fuerza su propio VSync. **Dynamic FPS eliminado** (el juego ya
  tiene `inactivityFpsLimit`). s3 p99 18.4–19.5 ms, 1 % low 39.7–53.2 (dentro del ruido de la base).
- 13:58 **s3 sin Dynamic FPS** (Aetherium, Sodium 0.9.2, ImmediatelyFast, BadOptimizations, EntityCulling, FerriteCore, Lithium, C2ME, ServerCore,
  Clumps, Alternate Current; optimizaciones mem.* de Aetherium desactivadas): 62.4 / 62.5 / 62.5 FPS reales (base 62.9: igual dentro del ruido, la GPU
  manda con el shader). Frametime más estable: p95 17.2 ms (base 18.0–18.5), p99 17.4–17.8 ms (base 18.7–19.6); 1 % low 44.5–54.5. Sin errores de mixin.
  => s3 es el perfil de trabajo.
- 14:00 **FSR 4.1 + AMD FG x2 (4.0.2) + VSync 100 Hz, perfil s3** — objetivo alcanzado en número: 50.0 reales + 50.0 generados = 100.0 presentados (6/6 tandas).
  - Sin pacing: latencia añadida 5.8–6.2 ms; intervalo de presentación media 10.0 ms, desv. 4.5–4.9, p99 15–19 ms, máx 22–35 ms (tirones aislados).
  - Pacing K=1.0: latencia añadida 13.5 ms; desv. 2.9, p99 13.1 ms, máx 13–15 ms (mucho más regular); espera activa 7.3 ms/frame de CPU.
  - Ojo: son tiempos de llamada a vkQueuePresent, no de la pantalla (este driver no da temporización de pantalla).
- 14:05 **Pacing K=0.5 + VSync** (FSR_PACE_K=0.5): 50 reales + 50 generados = 100 presentados; latencia añadida 9.25 ms; intervalo desv. 1.41–1.49 ms
  (el más regular), p99 12.5–12.6 ms, pero máx 17–22 ms (picos aislados); espera de CPU 3.1–3.2 ms/frame. **Mejor equilibrio medido.**
  Resumen latencia/regularidad: sin pacing 6 ms / desv 4.7 · K=0.5 9.3 ms / desv 1.4 · K=1.0 13.5 ms / desv 2.9.

- 14:13 **En movimiento** (giro 60°/s + avance ~10 bloques/s cargando chunks, perfil s3):
  - Solo FSR: 59–61 FPS; intervalo desv. 3.1–7.7 ms, p99 28–44 ms, **máx 60–140 ms** (tirones de carga de chunks = CPU).
  - FG x2 + VSync sin pacing: 48–50 reales, 96.5–99.5 presentados, +5–6 ms; desv. 5.6–6.7, p99 20–25, máx 38–57 ms.
  - FG x2 + VSync K=0.5: 48.5–49.6 reales, 97–99 presentados, +9.3 ms; desv. 2.1–2.7, p99 14–19, máx 34–44 ms. **Confirma K=0.5 con VSync.**
- 14:16 **Pacing automático** implementado: `core/FgPacingPolicy` (VSync -> K 0.5, sin VSync -> K 1.0; FSR4_PACE_K sigue mandando), campo
  `fgPacingAuto` (por defecto true), botón del menú cicla Auto/Sí/No, log `FG pacing: ...`. `bench_win.ps1 -AutoPace`. Tests 38/38. Validando en el juego.

- 14:17 **Pacing automático validado en el juego** (movimiento, VSync): log `FG pacing: auto: vsync (K=0.5)`; 96.6–99.4 presentados, +9.3–9.5 ms,
  desv. 2.1–3.1 ms (igual que K=0.5 manual).
- 14:20–14:26 **Calidad del frame generado** (el usuario vio la cámara "vibrar" en la prueba con movimiento):
  - Giro puro (sweep 1.2°/frame real, de día y de noche): el generado está desplazado +8 px respecto al real en 7/7 pares = exactamente medio paso
    (13.5 px/° x 0.6°), dirección correcta; tras alinear, diferencia 0.3–4.2 de 255. **FG correcto al girar.**
  - Avance lateral 0.2 bloques/frame entre vegetación cercana (`motion 0 24 0 0 mvseq 0 0.2`, 24 reales + 24 generados): frames 2–10 bien
    (generado entre sus dos reales: 7.5 vs paso 11.4). Frames 14–15: generado **más oscuro y borroso** (brillo medio 33 vs 38–39 de los reales),
    suelo iluminado convertido en sombra. Intercalado cada 2 frames = parpadeo/vibración. En esos frames los reales también saltan el doble (25–27),
    y el script fuerza la posición sin interpolación: parte del problema es la prueba, pero el oscurecimiento del generado es real.
  - Sin verificar: si pasa jugando normal (ratón + teclado). Hipótesis a probar: sombras de Better Voxels cambiando entre frames, o vectores de
    movimiento de cámara al trasladarse.

- 14:30–14:34 **Prueba del usuario con play.ps1**: "se ve horrible, FPS terribles, igual con FSR/FG apagados". Log (`build/play_session.log`):
  14:31–14:32 AMD FG x2 + FSR 4.1 activos (bien configurado); 14:32:40 el usuario pasó el multiplicador a **x3 y luego x4 (generador propio
  EXPERIMENTAL, no AMD)** y se quedó en x4 hasta el final, también con FSR apagado (FG-only x4). Con VSync ese camino ya estaba medido en
  25 reales / 75 presentados -> explica los FPS malos y gran parte de la mala imagen. Además Aetherium renderiza al 65 % (1248x702) aunque se
  apague FSR (Aetherium usa entonces su FSR 1): por eso apagar FSR "va igual". options.txt quedó con enableVsync:false y renderDistance 12.
- 14:3x Petición: añadir ANR (github.com/apex-compute/ANR) como opción de FG. Investigado: beta binaria cerrada ("source code: coming soon"),
  sin licencia publicada (LICENSE 404), se instala sustituyendo `nvngx_dlssg.dll` vía OptiScaler (interfaz DLSS-G), sin SDK ni API para motores,
  sin cifras de rendimiento. No integrable hoy en fsr4linux sin el código; pendiente de decisión del usuario.

- 14:38–14:43 **Nitidez vs FPS reales** (solo FSR 4.1, perfil s3, de día, cámara quieta, 1080p):
  renderScale 65 (1248x702) 63.2–63.9 FPS p99 17.3–17.9 ms · 75 (1440x810) 60.8 FPS p99 17.9–18.1 ms · 85 (1632x918) 56.1–56.3 FPS p99 19.1–19.4 ms.
  Las tres dan >= 50 reales (lo que necesita FG x2 para 100 presentados) con la cámara quieta; en movimiento la 65 ya bajaba a ~48.
  Las pruebas con FG a 75/85 se pararon a petición del usuario (14:44) para buscar el binario de ANR; mods restaurados.
- 14:4x ANR: `ANR-main.zip` (Descargas) es solo el código de nr-record/nr-replay, sin el generador. Camino viable: la DLL de ANR FG
  (`nvngx_dlssg.dll`, paquete `anr-fg-addon` de Releases) expone la interfaz DLSS-G NGX D3D12 (Init_Ext, CreateFeature, EvaluateFeature, ...);
  se puede llamar desde fsr4_bridge.exe como un proveedor más. Esperando el binario.
- 14:5x **ANR FG V.B16 addon** descargado por el usuario (Descargas). SHA-256 verificado = 764f1e18...477d641d (coincide con la release).
  Extraído (sin ejecutar) en `C:/Users/Sugar/build/anr_fg_B16`. `nvngx_dlssg.dll` (firmada "apex", sha c2ba7807...) exporta la interfaz
  DLSS-G NGX para **D3D12 y Vulkan** (NVSDK_NGX_VULKAN_Init_Ext/Ext2, CreateFeature/1, EvaluateFeature, GetFeatureRequirements, ...) +
  `NRFG_PresentationControl`; solo importa USER32/KERNEL32. Para RDNA 4 (0x7550-0x759f) su orden medido es vulkan-portable-linalg >
  vulkan-portable-dp4a > vulkan-portable > vulkan-coopmat. Modelo "fn-pc8s1-s0", hasta x6.
  => Integración prevista: directamente sobre el VkDevice de Minecraft (sin puente DX12). En modo "provider" el host implementa
  NVSDK_NGX_Parameter y pasa backbuffer, hudless, ui, depth, mvecs, matrices de cámara y OutputInterpolated.
  Falta: cabeceras NGX (NVIDIA/DLSS v310.9.16, MIT) -> pendiente de permiso de descarga.
- 14:5x Cabeceras NGX descargadas (solo include/*.h + LICENSE). **Corrección: NO son MIT**, son "NVIDIA RTX SDKs LICENSE" (propietaria,
  LicenseRef-NvidiaProprietary). El usuario la aceptó para uso local y privado; `native/third_party/ngx/` está en .gitignore (nunca se sube).
- 14:55 **Prueba aislada `tests/anr_smoke.cpp`** (Vulkan propio, fuera de Minecraft) en la RX 9060 XT:
  Init_Ext OK, PopulateParameters: FrameGeneration.Available=1, MultiFrameCountMax=5 (hasta x6), CreateFeature1 OK (3.8 s la primera vez:
  compila 30 pipelines), EvaluateFeature OK. **GPU 1.98–2.06 ms por frame generado a 1080p** (mediana de 60, p95 2.09 ms).
  ANR eligió la ruta `portable` (srgb8) porque "no conoce las extensiones del dispositivo" (Vulkan 1.0 sin extensiones): no usa todavía
  linalg/coopmat (unidades IA). Calidad: el frame generado NO sale desplazado a mitad de camino (0 px en vez de 8; error 41/255 con ruido
  sintético = un fundido A/B; 12/255 con una captura real de Minecraft desplazada 16 px). Pendiente averiguar qué parámetro falta.
- 14:57 Windows **Smart App Control (activado, estado 1)** bloqueó la tercera compilación de anr_smoke.exe (CodeIntegrity 3077/3033:
  binario sin firma ni reputación). Las dos primeras sí corrieron. Afecta a todo binario nuevo que compilemos (fsr4linux.dll,
  fsr4_bridge.exe). No se ha tocado la configuración de seguridad.
- 15:0x El usuario desactivó Smart App Control (estado 0 verificado). **Interpolación de ANR arreglada en la prueba aislada**: ANR espera
  `DLSSG.MvecScale` = 1 (vectores en píxeles), no 1/tamaño. Con escala 1 el frame generado queda a 8 px de A (mitad exacta de 16) con error
  0.12/255; con 1/tamaño salía un fundido A/B (error 1.6 respecto a la mezcla). Ruta rápida: ANR lee un parámetro propio sin documentar
  `NRFG_VulkanDeviceInfo` (no aparece en su código publicado); sin él usa `portable` (~2 ms/frame a 1080p). No se hace ingeniería inversa.
- 15:0x **ANR integrado en el mod** (commits 73bf7ab, 99a1c57): `native/src/anr_pass.cpp` (anfitrión NGX sobre el VkDevice de Minecraft,
  compilación opcional con FSR4_WITH_ANR si existen las cabeceras), las rutas `fsr4_x3_*` lo usan cuando `fgGenerator = "ANR"`; Java:
  `Fsr4Config.fgGenerator`, botón "Generador FG" en el menú, `bench_win.ps1 -Gen ANR`. ANR instalado en `fsr4linux-runtime/anr/`.
  Primer intento: ANR rechazó el dispositivo ("older than Vulkan 1.3"): Minecraft pide apiVersion 1.2 -> `VulkanInstanceMixin` la sube a 1.3
  (FSR4_VK12=1 lo desactiva). La DLL de ANR ya no se descarga al cerrar (sus hilos fallaban al salir).
- 15:12 **ANR x2 dentro de Minecraft** (FSR 4.1 + Better Voxels alto + VSync + pacing auto, s3): 2605 frames generados, 0 errores;
  GPU 1.6 ms por frame generado (pair 0.83 + phase 0.67 + out 0.07); 50.0 reales + 50.0 generados = 100 presentados (r2/r3),
  +9.3 ms, desv. intervalo 1.7–1.8 ms, máx 15–16 ms (r1 con un tirón de 140 ms al arrancar).
  **Calidad aún mal**: al girar, el generado sale con 0 px de desplazamiento (debería 8) y se ve como doble exposición borrosa
  (fundido). En su log aparecen "exception 0xc0000005" que muy probablemente son las del propio JVM (comprobaciones de nulo implícitas,
  que ANR anota con su manejador global); el juego siguió generando. A vigilar.
- 15:2x Diagnóstico en curso: A) HUD-less = frame final, B) además vectores x1.538 (píxeles de salida), C) eje Y invertido.
- 15:2x Variantes A/B/C: ninguna arregla (0 px). Volcado de vectores (FSR4_X3_DUMP): tras el prep pass media 14.9 px y 100 % no nulos,
  pero **al presentar 0.38 px y 2.6 % no nulos**. Causa: en el camino de Aetherium, `recordHandColor` (pase de la mano: "profundidad > 0 =
  píxel de mano -> movimiento 0") veía la profundidad de Aetherium SIN borrar (97.4 % no nula) y ponía a cero los vectores de todo el mundo.
  Ese pase va ANTES del dispatch de FSR 4.1: **FSR 4.1, AMD FG, x3/x4 y ANR llevaban todos sin vectores de movimiento con Better Voxels**
  (explica gran parte de la imagen borrosa que vio el usuario). Arreglo (commit 4aefd61): copia de color sin el pase de la mano en ese camino
  (`fsr4_record_color`; FSR4_AE_HANDMV=1 vuelve a lo anterior).
- 15:27 Tras el arreglo: vectores al presentar = 14.985 px, 100 % (idénticos al prep pass). **ANR interpola bien**: generado desplazado
  8 px (9 una vez) en 10/10 frames girando 1.2°/frame (mitad exacta), diferencia residual 3.5–4.6/255; a ojo nítido y en posición intermedia.
- 15:29–15:36 **AMD FG x2 vs ANR x2** (vectores ya corregidos; FSR 4.1 + Better Voxels alto RS65 + VSync 100 Hz + pacing auto, s3, 1080p):
  - Cámara quieta: AMD 50/50/100, +9.2 ms, desv. 1.5, p99 13.0, máx 14.5 ms · ANR 49.8–50/…/99.6–100, +9.2 ms, desv. 1.8–2.3, p99 14.3,
    máx 15.2 ms (un pico de 41 ms).
  - Girando 60°/s: AMD 48.1–48.6 reales / 96.3–97.2 presentados, +8.5 ms, desv. 2.6, p99 15.8–16.1, máx 17–19 ms ·
    ANR 48.8–50.0 / 97.6–100, +8.6–9.0 ms, desv. 2.5–5.1, p99 15.7–16.6, máx 17–147 ms (picos aislados de 147 y 35 ms).
  => Rendimiento y latencia equivalentes; AMD más regular (sin picos grandes). ANR queda como opción ("Generador FG" en el menú).
- 15:40–15:45 **Prueba del usuario jugando** (play.ps1 tras el arreglo de vectores): primero probó ANR x3/x4 sin VSync (muchos fallos,
  esperable); con **AMD FG x2 + VSync** dice que "se siente superfluido". El contador del mod solo mostraba los FPS reales (50) ->
  ahora muestra "N FPS reales | M en pantalla" cuando FG está activo (ventanas de 1 s de FgStats).
  El usuario lo considera candidato a beta. Pendiente antes de una beta (ver abajo).

- 15:55–16:10 **Jar todo en uno** (`gradlew allInOneJar` -> `SugarSV-<versión>-todo-en-uno.jar`, 65.7 MB): el mod + 12 mods como jars
  anidados de Fabric (Fabric API 0.166.2, Sodium 0.9.2, Aetherium 0.1.3, ImmediatelyFast, BadOptimizations, EntityCulling, FerriteCore,
  Lithium, C2ME, ServerCore, Clumps, Alternate Current) + runtime nativo (fsr4linux.dll, fsr4_bridge.exe, DLL de AMD + licencia).
  `SugarSvPreLaunch` (entrypoint preLaunch): instala el runtime en config/fsr4linux-runtime (SHA-256), primera vez: options.txt con Vulkan +
  VSync (y la línea `version:`; sin ella Minecraft reseteaba el motor a OpenGL), fsr4linux.json (FSR 4.1 QUALITY + AMD FG x2 + pacing auto),
  aetherium.json con Better Voxels a renderScale 65; cada arranque: FerriteCore dueño de las 3 optimizaciones de memoria de Aetherium.
  **Probado en producción** (Fabric 0.19.5 + Minecraft 26.3 de las librerías del launcher oficial, carpeta de juego nueva con solo el jar
  y Better Voxels, `scripts/windows/launch_prod.py`): todo se configuró solo; Vulkan, FSR 4.1 sobre Aetherium 1248x702->1920x1080,
  AMD FG 4.0.2 x2, pacing auto vsync K=0.5; 46.8–48.4 reales / 93.7–96.8 en pantalla, +9.5–9.7 ms (mundo recién creado: C2ME generando
  terreno durante la medición). Nota: lanzado desde la app de Claude (MSIX) AppData está redirigido y Fabric cargaba Mixin dos veces:
  launch_prod.py copia las librerías fuera de AppData (--libcopy); no afecta al launcher normal.
- Versión 1.0.0-beta.2 (solo cambia el número respecto al jar probado). Etiqueta git `beta-candidata-2`.

## Antes de una beta (no hecho)
- Empaquetado: mod + runtime instalables fuera del cliente de desarrollo (las DLL de AMD solo en binario con su aviso; las cabeceras NGX
  NO se pueden redistribuir; ANR sin licencia publicada: no se puede incluir, solo detectar si el usuario lo instala).
- La mano en primera persona con Aetherium recibe el movimiento de la cámara (antes tenía 0): posible estela en la mano.
- x3/x4 propios: siguen malos con VSync. ANR: picos aislados de 35–147 ms.
- Pruebas no hechas: carga de mundo y compilación de shaders con todo activo, 1440p/4K, sesiones largas, sin Aetherium.
- Smart App Control desactivado en este PC (decisión del usuario); en otros PC los binarios sin firmar pueden bloquearse.

## Configuración recomendada (provisional, experimental)
Perfil s3 + Better Voxels alto + Aetherium renderScale 65 + FSR 4.1 QUALITY + AMD FG x2 + VSync + fgPacing con FSR4_PACE_K=0.5, 1080p.

## Pendiente
- La escena de prueba tiene la cámara quieta: falta medir con movimiento (ritmo y calidad del frame generado en giros rápidos).
- Calidad visual del frame generado: sin verificar a ojo.
- Gestor de ritmo automático: elegir K / pacing según FPS reales vs refresco (ahora es manual).
- Picos aislados de 17–22 ms con K=0.5: localizar el origen (fgseg_acquire ~5 ms es la espera del swapchain).

## Pendiente
- Resultados s2/s3 y comparación con la base.
- FSR + AMD FG x2 + VSync 100 Hz (hipótesis: 50 reales + 50 generados = 100 presentados).
- Limitador de FPS reales = refresco / multiplicador de FG (gestor de ritmo).
