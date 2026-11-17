# Publicar en Modrinth / Modrinth publishing kit

> No se ha publicado nada. Este documento deja todo listo para subirlo a mano.
> Nothing has been published. This file has everything ready for a manual upload.

## ⚠️ Antes de subir: licencias / Before uploading: licensing

El jar todo-en-uno incluye mods de terceros **sin modificar**. Estas licencias pueden **no permitir** redistribuirlos dentro de otro jar:

| Mod | Licencia | Problema |
|---|---|---|
| Sodium | PolyForm Shield 1.0.0 | Redistribuirlo empaquetado necesita confirmación del autor |
| EntityCulling | tr7zw Protective License | Restringe la redistribución; necesita permiso del autor |
| BadOptimizations | ISC (por confirmar) | Confirmar con el autor que la licencia es ISC |

Los mensajes para los autores están en `MENSAJES_LICENCIAS.md` (fuera de los repos). **Hasta tener respuesta**, la opción segura es publicar un jar *sin* esos mods y pedir al usuario que los instale desde Modrinth (ver dependencias).

## Datos del proyecto / Project data

| Campo | Valor |
|---|---|
| Nombre | FSR 4.1 Minecraft RDNA4 |
| Slug sugerido | `fsr4-minecraft-rdna4` |
| Tipo | Mod |
| Loader | Fabric |
| Minecraft | 26.3 |
| Entorno | Solo cliente / Client only |
| Licencia del código | MIT |
| Código fuente | https://github.com/SugarSV11/FSR4.1-Minecraft-RDNA4 |
| Issues | https://github.com/SugarSV11/FSR4.1-Minecraft-RDNA4/issues |
| Versión | 1.0.0-beta.2 (canal: beta) |
| Categorías | Optimization, Utility |
| Plataforma | Windows 10/11 con GPU Radeon RDNA 4 |

## Dependencias / Dependencies

| Proyecto | Tipo | Nota |
|---|---|---|
| Fabric Loader ≥ 0.19.5 | requerido | |
| Java ≥ 25 | requerido | |
| Fabric API | incluido en el jar todo-en-uno | En un jar ligero, marcar como requerido |
| Sodium, Lithium, C2ME, FerriteCore, ImmediatelyFast, etc. | incluidos | Ver tabla de licencias arriba |

## Resumen (summary)

- **ES:** FSR 4.1 y Frame Generation x2 de AMD para Minecraft Java en tarjetas Radeon RDNA 4 (Windows). Beta.
- **EN:** AMD FSR 4.1 and Frame Generation x2 for Minecraft Java on Radeon RDNA 4 cards (Windows). Beta.

## Descripción (pegar en Modrinth) / Description

````markdown
# 🎮 FSR 4.1 Minecraft — RDNA 4

**ES** · Mod de Minecraft Java que añade el escalado **AMD FSR 4.1** y **Frame Generation x2** para tarjetas **Radeon RDNA 4** (RX 9000) en **Windows 10/11**.

> 🚧 **Beta.** Puede tener fallos y cambiar entre versiones.
> ⚠️ Solo RDNA 4: no funciona en RDNA 3 o anteriores, ni en NVIDIA/Intel.

## Qué hace
- **FSR 4.1**: el juego renderiza a menor resolución y la IA de AMD lo escala.
- **Frame Generation x2**: genera un fotograma intermedio por cada fotograma real. Duplica los FPS *mostrados*, no los reales, y añade latencia.
- **Ritmo automático + VSync** para un movimiento fluido.
- **Contador de FPS** arriba a la derecha.

## Uso
Instala Minecraft 26.3 con Fabric Loader 0.19.5 y pon el jar en `mods/`. Teclas: `F10` activa/desactiva FSR, `F7` Frame Generation.

## Rendimiento
Las mediciones están en el repositorio. La comparación contra el juego sin el mod está **pendiente de medir**.

## Licencias
Código del mod: MIT. AMD FidelityFX/FSR pertenece a AMD. Los mods incluidos conservan su licencia (ver el repositorio).

---

**EN** · A Minecraft Java mod adding **AMD FSR 4.1** upscaling and **Frame Generation x2** for **Radeon RDNA 4** cards (RX 9000) on **Windows 10/11**.

> 🚧 **Beta.** It may have bugs and change between versions.
> ⚠️ RDNA 4 only: it does not work on RDNA 3 or older, or on NVIDIA/Intel.

## What it does
- **FSR 4.1**: the game renders at a lower resolution and AMD's AI upscales it.
- **Frame Generation x2**: one in-between frame per real frame. It doubles the *displayed* FPS, not the real ones, and adds latency.
- **Automatic pacing + VSync** for smooth motion.
- **FPS counter** in the top right.

## Usage
Install Minecraft 26.3 with Fabric Loader 0.19.5 and put the jar in `mods/`. Keys: `F10` toggles FSR, `F7` toggles Frame Generation.

## Performance
Measurements are in the repository. The comparison against the game without the mod is **pending measurement**.

## Licences
Mod code: MIT. AMD FidelityFX/FSR belongs to AMD. Bundled mods keep their own licences (see the repository).
````

## Pasos / Steps

1. Resolver licencias (arriba) o preparar el jar ligero.
2. modrinth.com → Create a project → pegar datos y descripción.
3. Subir capturas propias a la galería.
4. Subir el `.jar` de la Release: versión `1.0.0-beta.2`, canal Beta, loader Fabric, MC 26.3.
5. Enviar a revisión (lo hace el autor).
