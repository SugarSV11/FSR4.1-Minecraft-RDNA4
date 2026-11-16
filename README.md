<div align="center">

# 🎮 FSR 4.1 Minecraft — RDNA 4

### AMD FSR 4.1 + Frame Generation x2 en Minecraft Java, hecho para tarjetas **Radeon RDNA 4**

![Minecraft](https://img.shields.io/badge/Minecraft-26.3-62B47A?style=for-the-badge&logo=minecraft&logoColor=white)
![Fabric](https://img.shields.io/badge/Fabric-0.19.5-DBD0B4?style=for-the-badge)
![GPU](https://img.shields.io/badge/GPU-RDNA%204-ED1C24?style=for-the-badge&logo=amd&logoColor=white)
![Windows](https://img.shields.io/badge/SO-Windows-0078D4?style=for-the-badge&logo=windows&logoColor=white)
![Estado](https://img.shields.io/badge/estado-BETA%201.0.0--beta.2-orange?style=for-the-badge)
![Licencia](https://img.shields.io/badge/c%C3%B3digo-MIT-blue?style=for-the-badge)

**[⬇️ Descargar el .jar (Releases)](../../releases/latest)**

🇪🇸 Español · [🇬🇧 English](README.en.md)

### 🐧 ¿Usas Linux? → [Minecraft-FSR4-Linux](https://github.com/SugarSV11/Minecraft-FSR4-Linux)

</div>

---

> 🚧 **Versión BETA.** Puede tener fallos y cambiar entre versiones.
> 🪟 **Solo Windows 10/11** (usa DirectX 12 + Vulkan). Para Linux hay un repo hermano: [Minecraft-FSR4-Linux](https://github.com/SugarSV11/Minecraft-FSR4-Linux).

## ✨ ¿Qué es esto?

Un mod para **Minecraft Java** que te da **más FPS y una imagen más nítida** usando las tecnologías oficiales de AMD:

| Función | Qué hace, en cristiano |
|---|---|
| 🔍 **FSR 4.1** | El juego se dibuja a menor resolución y la IA de AMD lo reescala a tu pantalla. Se ve nítido y rinde mejor. |
| 🎞️ **AMD Frame Generation x2** | Por cada imagen real, la tarjeta genera otra intermedia. Con 50 FPS reales ves **100 en pantalla** (ver aclaración abajo). |
| ⏱️ **Pacing automático + VSync** | Reparte los fotogramas de forma pareja para un movimiento fluido y sin tirones. |
| 📊 **Contador de FPS** | Arriba a la derecha: `N FPS reales \| M en pantalla`. |
| 📦 **Todo en uno** | Un solo `.jar` que ya trae Fabric API, Sodium, Lithium, C2ME y otros mods de optimización, coordinados entre sí. |

> ⚠️ **Hecho para RDNA 4** (Radeon RX 9000) y solo para Windows. FSR 4 usa las unidades de IA de RDNA 4, así que **no funciona en RDNA 3 o anteriores, ni en NVIDIA/Intel**.
> ✅ **Probado en RX 9060 XT.**

---

## 📈 Rendimiento medido

Probado en una **RX 9060 XT**, 1080p, shaders Better Voxels:

| Escenario | FPS reales | FPS en pantalla | Sin el mod (nativo) |
|---|:---:|:---:|:---:|
| Cliente de pruebas | 50 | **100** | Pendiente |
| Girando la cámara (60°/s) | ~48 | **96–97** | Pendiente |
| Instalación nueva solo con este jar | 47–48 | **94–97** | Pendiente |

> ℹ️ **Frame Generation x2 duplica los FPS _mostrados_, no los _reales_.** El juego sigue calculando los mismos fotogramas reales; el frame generation inserta uno intermedio entre cada par. Además **añade unos 9 ms de latencia**. Los resultados dependen de tu equipo y de tus ajustes.
> La columna «Sin el mod (nativo)» está **pendiente de medir**.

---

## 🚀 Instalación paso a paso

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

> ℹ️ Los archivos internos conservan el nombre `fsr4linux` (carpeta `config/fsr4linux-runtime`, `config/fsr4linux.json`) para no romper configuraciones ya guardadas.

---

## 🧩 ¿Qué lleva dentro el .jar?

El mod principal (este proyecto) más estos mods **sin modificar**. Cada uno conserva su licencia:

| Mod | Versión | Licencia | Autor |
|---|---|---|---|
| Fabric API | 0.166.2+26.3 | Apache-2.0 | FabricMC |
| Sodium | 0.9.2+mc26.3 | PolyForm Shield 1.0.0 | JellySquid |
| Aetherium | 0.1.3 | LGPL-3.0 | SpaceBod |
| ImmediatelyFast | 1.17.1+26.3 | LGPL-3.0+ | RK_01 |
| BadOptimizations | 2.4.1 | ISC | Thosea |
| EntityCulling | 1.12.1 | tr7zw Protective License | tr7zw |
| FerriteCore | 9.0.0 | MIT | malte0811 |
| Lithium | 0.26.3+mc26.3 | LGPL-3.0 | JellySquid, 2No2Name |
| C2ME | 0.4.2-alpha.0.119 | LGPL-3.0 | RelativityMC, ishland |
| ServerCore | 1.5.20+26.3 | MIT | Wesley1808 |
| Clumps | 26.3.2 | MIT | Jaredlll08 |
| Alternate Current | 1.9.0 | MIT | Space Walker |

📜 Textos de licencia completos y condiciones especiales (Sodium, EntityCulling): [`THIRD_PARTY_LICENSES/`](THIRD_PARTY_LICENSES/). La lista con enlaces va dentro del jar en `META-INF/sugarsv/THIRD_PARTY.txt`.

Se dejaron fuera por incompatibles o duplicados: Iris, Noisium, ModernFix, Sodium Extra, MoreCulling, Dynamic FPS, Krypton, Ksyxis y otros.

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
- Los mods incluidos en el jar son de sus respectivos autores y conservan **sus propias licencias** (ver [`THIRD_PARTY_LICENSES/`](THIRD_PARTY_LICENSES/)). Si eres autor de alguno y prefieres que no se redistribuya, abre un issue y se retirará.

<div align="center">

Hecho con ❤️ por **SugarSV** para la comunidad Radeon.

</div>
