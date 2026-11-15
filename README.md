<div align="center">

# 🎮 FSR 4.1 Minecraft — RDNA 4

### AMD FSR 4.1 + Frame Generation x2 en Minecraft Java, hecho para tarjetas **Radeon RDNA 4**

![Minecraft](https://img.shields.io/badge/Minecraft-26.3-62B47A?style=for-the-badge&logo=minecraft&logoColor=white)
![Fabric](https://img.shields.io/badge/Fabric-0.19.5-DBD0B4?style=for-the-badge)
![GPU](https://img.shields.io/badge/GPU-solo%20RDNA%204-ED1C24?style=for-the-badge&logo=amd&logoColor=white)
![Windows](https://img.shields.io/badge/SO-solo%20Windows-0078D4?style=for-the-badge&logo=windows&logoColor=white)
![Estado](https://img.shields.io/badge/estado-BETA%201.0.0--beta.2-orange?style=for-the-badge)
![Licencia](https://img.shields.io/badge/c%C3%B3digo-MIT-blue?style=for-the-badge)

**[⬇️ Descargar el .jar (Releases)](../../releases/latest)**

</div>

---

> 🚧 **Versión BETA.** Puede tener fallos y cambiar entre versiones.
> 🪟 **Solo Windows 10/11** (usa DirectX 12 + Vulkan). Linux no está soportado en este jar.

## ✨ ¿Qué es esto?

Un mod para **Minecraft Java** que te da **más FPS y una imagen más nítida** usando las tecnologías oficiales de AMD:

| Función | Qué hace, en cristiano |
|---|---|
| 🔍 **FSR 4.1** | El juego se dibuja a menor resolución y la IA de AMD lo reescala a tu pantalla. Resultado: se ve nítido y rinde mucho mejor. |
| 🎞️ **AMD Frame Generation x2** | Por cada imagen real, la tarjeta inventa otra intermedia. Si tienes 50 FPS reales, ves **100 en pantalla**. |
| ⏱️ **Pacing automático + VSync** | Reparte los fotogramas de forma pareja para que el movimiento sea fluido y sin tirones. |
| 📊 **Contador de FPS** | Arriba a la derecha: `N FPS reales \| M en pantalla`. |
| 📦 **Todo en uno** | Un solo `.jar` que ya trae Fabric API, Sodium, Lithium, C2ME y otros mods de optimización, coordinados entre sí. |

> ⚠️ **Solo para RDNA 4 y solo en Windows** (Radeon **RX 9000**: 9060 XT, 9070, 9070 XT…).
> FSR 4 usa las unidades de IA de RDNA 4, así que **no funciona en RDNA 3 o anteriores, ni en NVIDIA/Intel**.
> Desarrollado y probado en una **RX 9060 XT**.

---

## 📈 Rendimiento medido

Probado en una **RX 9060 XT**, 1080p, shaders Better Voxels:

| Escenario | FPS reales | FPS en pantalla |
|---|:---:|:---:|
| Cliente de pruebas | 50 | **100** |
| Girando la cámara (60°/s) | ~48 | **96–97** |
| Instalación nueva solo con este jar | 47–48 | **94–97** |

Coste añadido por el frame generation: ~9 ms de latencia. Los resultados dependen de tu equipo y de tus ajustes.

---

## 🚀 Instalación (3 pasos)

1. **Instala Minecraft 26.3 con Fabric Loader 0.19.5** (te crea el perfil `fabric-loader-0.19.5-26.3` en el launcher).
2. **Descarga el `.jar`** desde [Releases](../../releases/latest) y ponlo en tu carpeta `mods/`.
   - Pon **solo ese jar**: Sodium, Fabric API y el resto ya van dentro. Quita cualquier otro jar de `mods/`.
3. **Abre el juego.** La primera vez el mod configura todo solo:
   - instala sus archivos en `config/fsr4linux-runtime`,
   - cambia el motor gráfico a **Vulkan** y activa **VSync**,
   - activa **FSR 4.1 calidad + Frame Generation x2 + pacing automático**.

   FSR y FG tardan unos segundos en arrancar la primera vez. Después respeta lo que cambies tú.

> 💡 Opcional: el shaderpack *Better Voxels* va en `shaderpacks/`.

### ⌨️ Teclas

| Tecla | Acción |
|:---:|---|
| `F10` | Activar / desactivar FSR |
| `F7` | Activar / desactivar Frame Generation |

En el menú del mod puedes elegir generador, multiplicador y pacing. **Deja el multiplicador en x2**: x3 y x4 son experimentales y van mal con VSync.

---

## 🧩 ¿Qué lleva dentro el .jar?

El mod principal (este proyecto) más estos mods, sin modificar:

`Fabric API` · `Sodium` · `Aetherium` · `ImmediatelyFast` · `BadOptimizations` · `EntityCulling` · `FerriteCore` · `Lithium` · `C2ME` · `ServerCore` · `Clumps` · `Alternate Current`

Se dejaron fuera por incompatibles o duplicados: Iris, Noisium, ModernFix, Sodium Extra, MoreCulling, Dynamic FPS, Krypton, Ksyxis y otros.
La lista completa con licencias está dentro del jar en `META-INF/sugarsv/THIRD_PARTY.txt`.

---

## 🛠️ Compilar desde el código

```bash
# Windows
scripts\windows\build.cmd              # parte nativa
cd fabric && gradlew.bat allInOneJar   # jar todo en uno
```

Más detalle técnico (arquitectura, diseño, benchmarks) en [`docs/`](docs/) y en [`docs/README_TECNICO.md`](docs/README_TECNICO.md).

---

## ⚠️ Limitaciones conocidas

- Posible estela en la mano en primera persona con Aetherium.
- Frame Generation x3/x4 funciona mal con VSync.
- Sin probar a 1440p / 4K ni en sesiones muy largas.
- Los binarios no están firmados: *Smart App Control* de Windows puede bloquearlos en otros PC.
- **ANR FG** (generador alternativo de una beta cerrada) no está incluido.

---

## 📜 Licencias y créditos

- El **código de este repositorio** es [MIT](LICENSE).
- **AMD FidelityFX / FSR** pertenece a AMD y se usa a través de sus librerías oficiales.
- Los mods incluidos en el jar todo en uno son de sus respectivos autores y conservan **sus propias licencias** (ver `THIRD_PARTY.txt`). Si eres autor de alguno y prefieres que no se redistribuya, abre un issue y se retirará.

<div align="center">

Hecho con ❤️ por **SugarSV** para la comunidad Radeon.

</div>
