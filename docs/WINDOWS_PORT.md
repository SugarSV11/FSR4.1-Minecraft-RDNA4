# Port nativo a Windows 11 (rama `windows11-port`)

Estado: **capa nativa portada y probada en una RX 9060 XT (Windows 11)**; la parte Java/Fabric **compila** (Gradle, JDK 25) pero **no se ha ejecutado dentro de Minecraft** (no hay Minecraft/Fabric instalado en la maquina de pruebas).

## Que cambia respecto a Linux
- Ya no hay Wine/Proton/VKD3D: `fsr4_bridge.exe` es un proceso Windows normal que carga las DLL oficiales de AMD (SDK 2.4.0, proveedor FSR 4.1.2).
- Memoria compartida cliente<->helper: archivo temporal + `CreateFileMapping` (antes `/dev/shm` + `mmap`).
- Texturas compartidas: el helper publica cada recurso D3D12 con un **nombre NT** (`Local\fsr4b-<pid>-<gen>-p<i>`), el cliente lo abre con `ID3D12Device::OpenSharedHandleByName` y lo importa en Vulkan (`VK_KHR_external_memory_win32`, tipo `D3D12_RESOURCE`). Se eliminan los hacks de dma-buf / `pidfd_getfd`.
- Fence compartido: fence D3D12 con nombre importado como semaforo timeline (`VK_KHR_external_semaphore_win32`, tipo `D3D12_FENCE`). El semaforo se crea SIN flag de exportacion (con el flag, el driver AMD devuelve `VK_ERROR_UNKNOWN`).
- Protocolo: `Response.shareGen` nuevo (generacion de nombres).

## Medido (esta maquina, 720p -> 1440p, 120 dispatches con `tests/win_smoke.cpp`)
- 4 texturas compartidas importadas y verificadas por CRC; fence compartido OK.
- Proveedor activo `4.1.2` (SDK 2.4.0); dispatch GPU: media 1.836 ms (min 1.712, max 2.017).
- Los planos contienen un patron de prueba, no una escena real: es un tiempo de coste del dispatch, no un FPS del juego.

## Java/Fabric (hecho y probado en el juego, ver mas abajo)
- `VulkanBackendMixin`: en Windows pide `VK_KHR_external_memory(_win32)` y `VK_KHR_external_semaphore(_win32)` en lugar de las variantes fd/dma-buf.
- `FsrBootstrap`: en Windows no descarga GE-Proton ni extrae nada; comprueba que existan en el directorio de runtime (`FSR4_RUNTIME_DIR`, por defecto `<config>/fsr4linux-runtime`): `lib/fsr4linux.dll`, `bridge/fsr4_bridge.exe`, `bridge/amd_fidelityfx_loader_dx12.dll`, `bridge/amd_fidelityfx_upscaler_dx12.dll`.
- `FsrRuntime`: carga `fsr4linux.dll`, sin Proton/prefijo, y arranca el helper con proveedor "mejor disponible".
- Proveedores: el SDK oficial 2.4.0 ofrece `4.1.2`/`3.1.6` (no `4.1.1`/`3.1.5`). `FsrRuntime.sameProvider` y el helper aceptan la misma familia (`4.1.x`).
- `fabric/build.gradle`: `remapJar` ya no existe en el Loom actual (hook condicional) y el empaquetado Linux (bash + manifiesto) se omite en Windows. NOTA: el repo original no compilaba con ese Loom por `remapJar`.
- Tests unitarios: 32 de 33 pasan. Falla `UpscaleControllerTest.toggleOnAndOff` (espera "FSR ACTIVADO" y recibe "FSR ACTIVANDO..."); esta en `core/`, codigo que NO se ha tocado, asi que es un fallo previo del repo.

## Probado dentro de Minecraft 26.3 (RX 9060 XT, Adrenalin, cliente de desarrollo Loom + `TestHarness`)
- Backend Vulkan (`preferredGraphicsBackend:"vulkan"` en `options.txt`; con OpenGL el mod no hace nada).
- FSR 4.1 activo (`executed=4.1.2`, 1707x960 -> 2560x1440 y 2560x1440 -> 3840x2160), coste GPU ~2 ms (1440p) / ~5 ms (4K).
- Frame generation (FG-only, proveedor `4.0.2`): activo, presenta el doble de frames de los renderizados (p. ej. 139 renderizados / 278 presentados a 1080p).
- Correcciones necesarias en Windows (todas en este commit): comparacion de proveedor por familia (`4.1.x`, `4.0.x`), DLL de frame generation opcional para solo-upscaling, espera de `fgalloc` de 60 s (la primera creacion del contexto tarda ~5 s), fence compartido D3D12<->Vulkan como modo de sincronizacion por defecto (FG lo exige).
- FPS: en escenas ligeras vanilla (>300 FPS, limitadas por CPU) FSR/FG no suben los FPS (nativo ~400 vs FSR ~175 a 1440p; el coste fijo de FSR supera al del frame). Falta medir una escena pesada.
- Escena pesada (1440p, distancia de render 32, ~320 ovejas, mundo reiniciado, 3x200 ticks): nativo ~213 FPS; FSR 4.1 (QUALITY) ~115 FPS; FG-only ~65 renderizados / ~130 presentados (+6 ms de latencia); FSR+FG ~58 renderizados / ~117 presentados (+7 ms). Ninguna combinacion supera al nativo: con >200 FPS nativos el juego esta limitado por CPU/driver, no por la GPU, y el coste fijo de FSR (~2.1 ms GPU) y de FG (~3.3 ms CPU + ~2.7 ms GPU) es mayor que lo que ahorran. FSR ya solo cuesta 0.34 ms en el hilo de render.
- Los 3 modos solo pueden subir FPS si el juego nativo corre por debajo de ~60-100 FPS por culpa de la GPU (4K con shaders pesados). En vanilla sobre una RX 9060 XT eso no ocurre.
- Tests unitarios: 33/33 (se corrigio `UpscaleControllerTest.toggleOnAndOff`, que esperaba un texto antiguo).
- Cuidado al medir: el mundo de pruebas `FSRTest` se guarda entre ejecuciones; si el guion crea entidades hay que matarlas al empezar (`cmd kill @e[type=sheep]`) o la poblacion crece geometricamente y falsea los resultados.
- Compilar sin CMake: `scripts/windows/build.cmd` (DLL del mod, helper y smoke test). Guion de medicion: `scripts/windows/bench_win.ps1` (modos `native`, `fsr`, `fg`, `fsr_fg`).

## Pendiente (no hecho)
- Medir con un shader pack pesado (o 4K) donde el nativo caiga por debajo de ~60 FPS: es el unico caso donde FSR/FG pueden dar mas FPS.
- Portar `tests/ipc_test`, `gpu_ipc_test` y los demas scripts de benchmark (siguen siendo Linux).
- Empaquetado: las DLL de AMD no se redistribuyen.

## Expectativa de FPS
Los propios benchmarks del repo (docs/BENCHMARKS.md) muestran que en la escena CPU-bound probada FSR 4.1 NO sube los FPS (nativo 1134 vs FSR 537 a 1080p). Solo ayuda cuando la GPU es el cuello de botella (4K, shaders pesados, distancia de render alta). El port a Windows quita el coste de IPC/Wine, pero no cambia esa fisica.

## Compilar (MSVC + CMake)
Vulkan-Headers en `%USERPROFILE%\.cache\fsr4-windows\vk-headers`, FidelityFX SDK 2.4.0 en `-DFFX_SDK_DIR=...`.
Poner junto a `fsr4_bridge.exe`: `amd_fidelityfx_loader_dx12.dll` y `amd_fidelityfx_upscaler_dx12.dll` del SDK oficial (no incluidas en el repo).

## Frame generation y shaders (Aetherium 0.1.3 + Sodium 0.9.2 + Better Voxels v1.8, RX 9060 XT, 1440p)
- Aetherium (shaders sobre el renderizador Vulkan de 26.3) convive con este mod; `config/aetherium.json` -> `{"shaders":{"pack":"...zip","enabled":true,"renderScale":50}}` (renderScale 50-100) y las opciones del pack van en `shaderpacks/<pack>.zip.txt`.
- El coste del shader en calidad alta es ~12 ms fijos (no baja con resolucion ni distancia de render) + ~13 ms que si escalan con los pixeles; el perfil `B_LOW` del pack baja el nativo de 39.5 a ~67 FPS.
- Causa del cuello de botella de FG: `VulkanCommandEncoder.submit()` espera al envio de hace 2 (`MAX_SUBMITS_IN_FLIGHT = 2`, 2 command pools). FG metia 3 envios por frame y el CPU quedaba bloqueado por la GPU. Arreglo: el envio del frame generado usa un codificador propio (`ensureFgEncoder`, desactivable con `FSR4_FG_OWN_ENC=0`).
- Ritmo (`fgPacing`): `avgIntervalNs` excluye la propia espera, con GPU-bound el factor 0.5 centraba el frame real a 1/3 del periodo. `FSR4_PACE_K` (defecto 1.0) lo centra a 1/2.
- Medido (perfil B_LOW + renderScale 50 + FG x2): sin pacing 122-134 FPS mostrados; con `fgPacing` y K=1.0 144-150 FPS, intervalo p99 de presentacion 9.1-10.1 ms (>= 100 FPS en el 1 % peor), con algun salto aislado de 18-25 ms.
- Sin verificar: calidad visual del frame generado (la escena de prueba es nocturna y sale casi negra) y su comportamiento en escenas limitadas por CPU (alli K=1.0 puede alargar el frame; usar `FSR4_PACE_K=0.5`).

## FSR 4.1 dentro de Aetherium (sesion 2026-11-14)
- `AetheriumRenderScaleMixin` (`@Pseudo`, sin dependencia de compilacion ni tocar el jar de Aetherium) engancha justo DESPUES de `RenderScale.restore(...)` en `RenderScale.endWorld` y, si FSR esta activo, sustituye el upscale espacial de Aetherium (EASU+RCAS) por FSR 4.1 y cancela el resto del metodo. `AetheriumBridge` lee por reflexion `percent()`, `scaledColor/Depth`, `scaledWidth/Height`, `fullWidth/Height`. Si algo falla, el upscale de Aetherium sigue corriendo.
- Aetherium cambia el tamano del RenderTarget principal (reporta el tamano reducido) ANTES del `beginWorld` de este mod: el tamano real sale de `fullWidth/fullHeight`. El prep pass lee la profundidad reducida de Aetherium (`fsr4_set_depth`, nueva en la DLL).
- Primer frame tras reservar planos: timeout de 20 s (crear el contexto FFX); 2 s despues.
- Medido (1080p, Better Voxels en calidad alta, renderScale 65): Aetherium solo 66.6 FPS; FSR 4.1 sobre Aetherium ~62 FPS (FSR ~1.1 ms GPU).
- Causa de la caida a ~39 FPS: `VulkanCommandEncoder.submit()` espera al envio de hace 2 (MAX_SUBMITS_IN_FLIGHT = 2). El flush a mitad de frame que necesita el helper (copy-in + senal W) contaba como envio extra y el CPU perdia su adelanto. `FsrRuntime.flushNoRotate` cierra el envio abierto por reflexion SIN avanzar el indice de Minecraft; imprescindible llamar tambien a `VulkanTransientMemory.endSubmit()/beginSubmit()` (el primer comando del envio es un command buffer de staging que hay que cerrar: sin eso el envio es invalido y la senal nunca sale). `FSR4_NOROTATE_FLUSH=0` lo desactiva. Aplicado tambien al flush de FG y al envio por imagen de x3/x4 sin lotes.
- Descartado como causa (medido): jitter, prep pass, prioridad/tipo de cola del helper (`FSR4_QPRIO=high`, `FSR4_QTYPE=compute`: sin efecto).
- Diagnosticos: `FSR4_AE_NOJITTER/NOPREP/PASSTHRU/FLUSHONLY=1`, `FSR4_HELPER_TRACE=1`.
- FSR + FG x3 propio (sin VSync): ~59 FPS renderizados, ~178 presentaciones/s, pero en rafagas. Con VSync (FIFO) el camino sin lotes se queda en 25 renderizados / 75 presentados; `FSR4_FIFO_BATCH=1` (+ `FSR4_FIFO_EXTRA`) habilita lotes bajo FIFO, pero NO se pudo verificar lo que la pantalla muestra (la sonda `FSR4_PRESENT_PROBE` necesita temporizacion de pantalla que este driver no da). Pendiente.
