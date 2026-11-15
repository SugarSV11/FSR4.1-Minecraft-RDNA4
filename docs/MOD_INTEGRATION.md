# Integración de mods de optimización (SugarSV) — análisis de los 27 JAR

Fecha: 2026-11-14. Fuente: `Desktop\SugarSV-Mods` (27 JAR). Método: lectura de `fabric.mod.json` (dependencias, `breaks`) y de las clases mixin
(qué clases y métodos de Minecraft tocan) cruzadas con los mixins de `fsr4linux` y de Aetherium 0.1.3. **Nada de esto está medido todavía**:
las etapas se miden con `scripts/windows/mod_profile.ps1` + `bench_win.ps1`.

## Bloqueos duros (el juego no arranca o se rompe)
| JAR | Motivo verificado |
|---|---|
| iris-fabric-1.11.7 | Aetherium declara `breaks: iris *`; Sodium 0.9.3-beta.5 declara `breaks: iris <=1.12.0`. Aetherium es el motor de shaders: Iris sobra. |
| noisium 2.8.5+mc26.2-pre-2 | `depends minecraft ~26.2-`: no carga en 26.3. Su trabajo (generación de ruido) ya lo cubre C2ME. |
| modernfix 5.20.3+mc1.21.4 | Compilado para 1.21.4; sus mixins apuntan a clases de 1.21.x. |
| enhancedblockentities 0.11.3+1.21.4 | Compilado para 1.21.4; además sustituye los renderizadores de block entities, que Aetherium ya gestiona (`BlockEntitySubmitIdsMixin`). |
| sodium-extra 0.9.6-beta.4 | Exige `fabricloader >=0.20.1` (el cliente usa 0.19.5). Además su `adaptive_sync` cambia el modo de presentación de `GpuSurface`, que es lo mismo que controla el ritmo de FG de fsr4linux. Fuera hasta tener un único dueño de VSync. |

## Duplicados (dos sistemas optimizando lo mismo)
| Proceso | Candidatos | Dueño elegido | Motivo |
|---|---|---|---|
| Limitar FPS sin foco | FpsReducer2, Dynamic FPS | Dynamic FPS | FpsReducer2 cambia el limitador de FPS también con foco y rompería el ritmo de FG. Configurar Dynamic FPS para actuar solo sin foco/minimizado. |
| Caché de estados de bloque, `ThreadingDetector` de `PalettedContainer`, mapa de componentes | FerriteCore, Aetherium `mem.state_cache_dedup` / `mem.palette_lock` / `mem.component_patch` | FerriteCore | Mismos tres puntos. `bench_win.ps1` desactiva los de Aetherium en `aetherium.json` (`optimisations`) cuando FerriteCore está en `mods\`. |
| Reducir ticks de entidades lejanas | ServerCore, Immersive Optimization | ServerCore | Immersive Optimization es build 26.2. Vigilar el gestor dinámico de ServerCore: no debe bajar la distancia de visión (choca con "calidad alta"). |
| Generación de mundo | C2ME, Noisium | C2ME | Noisium no carga (ver arriba). |
| Arranque/fugas | ModernFix, MemoryLeakFix, Ksyxis | ninguno | ModernFix no es de 26.3; MemoryLeakFix (1.17+) corrige fugas antiguas ya resueltas; Ksyxis evita cargar los spawn chunks, que el juego ya no tiene. |
| Red | Krypton | ninguno | En un mundo local el servidor integrado no usa la pila de red real: sin beneficio y añade una librería nativa. |

## Perfil elegido (por etapas, reversible)
- **baseline**: Aetherium 0.1.3 + Sodium 0.9.2 (todas las mediciones anteriores).
- **s1**: Sodium 0.9.3-beta.5 (lo exige MoreCulling: `breaks sodium <=0.9.3-beta.1`). Aetherium pide `>=0.9.2`: a verificar.
- **s2**: + ImmediatelyFast (dibujo inmediato: HUD/texto), BadOptimizations (cachés de cliente), EntityCulling (oclusión de entidades, hilo propio), MoreCulling (caras de bloque), Cloth Config (dependencia), Dynamic FPS.
- **s3**: + FerriteCore (memoria), Lithium (lógica de servidor), C2ME (chunks multihilo: aprovecha los 8 núcleos del 5700), ServerCore, Clumps (menos entidades de XP), Alternate Current (redstone).
- `-Extras`: Almanac + Let Me Despawn (jugabilidad), FastQuit (salir sin esperar): no son de rendimiento.

## Puntos calientes compartidos con fsr4linux (riesgo de interferencia)
- `Minecraft.renderFrame`: fsr4linux redirige `GpuSurface.present()`; Dynamic FPS envuelve el método entero (`@WrapMethod`) y Sodium inyecta en él (HUD de depuración). Compatibles en teoría; comprobar que Dynamic FPS no salta frames con foco.
- `VulkanCommandEncoder` / `RenderScale`: solo Aetherium y fsr4linux. Ningún mod de la lista los toca.
- `vkQueuePresentKHR`: solo fsr4linux.

## Límites físicos honestos
- Aceleradores de IA de RDNA 4: el único código que los usa de verdad es el modelo de FSR 4.1 dentro de `amd_fidelityfx_upscaler_dx12.dll` (AMD). AMD FG 4.0.2 no es un modelo de IA. Ningún mod de la lista puede usarlos.
- Medido antes (1080p, Better Voxels alto, renderScale 65): Aetherium solo 66.6 FPS, FSR 4.1 ~62 FPS reales. Para 100 FPS **presentados** a 100 Hz la vía realista es FG x2 con 50 FPS reales fijos (100 = 50 reales + 50 generados). A verificar con VSync.
