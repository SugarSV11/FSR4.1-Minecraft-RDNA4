<div align="center">

# 🎮 FSR 4.1 Minecraft — RDNA 4

### AMD FSR 4.1 + Frame Generation x2 for Minecraft Java, made for **Radeon RDNA 4** cards

![Minecraft](https://img.shields.io/badge/Minecraft-26.3-62B47A?style=for-the-badge&logo=minecraft&logoColor=white)
![Fabric](https://img.shields.io/badge/Fabric-0.19.5-DBD0B4?style=for-the-badge)
![GPU](https://img.shields.io/badge/GPU-RDNA%204-ED1C24?style=for-the-badge&logo=amd&logoColor=white)
![Windows](https://img.shields.io/badge/OS-Windows-0078D4?style=for-the-badge&logo=windows&logoColor=white)
![Status](https://img.shields.io/badge/status-BETA%201.0.0--beta.2-orange?style=for-the-badge)
![License](https://img.shields.io/badge/code-MIT-blue?style=for-the-badge)

**[⬇️ Download the .jar (Releases)](../../releases/latest)**

[🇪🇸 Español](README.md) · 🇬🇧 English

### 🐧 On Linux? → [Minecraft-FSR4-Linux](https://github.com/SugarSV11/Minecraft-FSR4-Linux)

</div>

---

> 🚧 **BETA.** It may have bugs and change between versions.
> 🪟 **Windows 10/11 only** (DirectX 12 + Vulkan). For Linux see the sibling repo [Minecraft-FSR4-Linux](https://github.com/SugarSV11/Minecraft-FSR4-Linux).

## ✨ What is this?

A **Minecraft Java** mod that gives you **more FPS and a sharper image** with AMD's official technologies:

| Feature | In plain words |
|---|---|
| 🔍 **FSR 4.1** | The game renders at a lower resolution and AMD's AI upscales it to your screen. |
| 🎞️ **AMD Frame Generation x2** | For every real frame the GPU generates an in-between one: 50 real FPS shows as **100 on screen** (see note below). |
| ⏱️ **Automatic pacing + VSync** | Spreads frames evenly for smooth motion. |
| 📊 **FPS counter** | Top right: `N real FPS \| M on screen`. |
| 📦 **All in one** | A single `.jar` that already includes Fabric API, Sodium, Lithium, C2ME and other optimisation mods. |

> ⚠️ **Made for RDNA 4** (Radeon RX 9000) and Windows only. FSR 4 uses RDNA 4's AI units: **it does not work on RDNA 3 or older, or on NVIDIA/Intel.**
> ✅ **Tested on RX 9060 XT.**

## 📈 Measured performance

RX 9060 XT, 1080p, Better Voxels shaders:

| Scenario | Real FPS | On-screen FPS | Without the mod (native) |
|---|:---:|:---:|:---:|
| Test client | 50 | **100** | Pending |
| Turning the camera (60°/s) | ~48 | **96–97** | Pending |
| Fresh install with only this jar | 47–48 | **94–97** | Pending |

> ℹ️ **Frame Generation x2 doubles the _displayed_ FPS, not the _real_ ones**, and **adds about 9 ms of latency**. Results depend on your hardware and settings. The «without the mod» column is **pending measurement**.

## 🚀 Installation

1. Install **Minecraft 26.3 with Fabric Loader 0.19.5**.
2. Download the `.jar` from [Releases](../../releases/latest) and put it in `mods/`. Use **only this jar** (Sodium, Fabric API and the rest are inside).
3. Launch the game. The first run configures everything: installs its files in `config/fsr4linux-runtime`, switches the graphics backend to **Vulkan**, enables **VSync**, and turns on **FSR 4.1 quality + Frame Generation x2 + automatic pacing**.

| Key | Action |
|:---:|---|
| `F10` | Toggle FSR |
| `F7` | Toggle Frame Generation |

Keep the multiplier at x2: x3 and x4 are experimental and misbehave with VSync. Internal files keep the `fsr4linux` name so existing configs keep working.

## 🧩 Bundled mods

Unmodified, each under its own licence: Fabric API (Apache-2.0), Sodium (PolyForm Shield 1.0.0), Aetherium (LGPL-3.0), ImmediatelyFast (LGPL-3.0+), BadOptimizations (ISC), EntityCulling (tr7zw Protective License), FerriteCore (MIT), Lithium (LGPL-3.0), C2ME (LGPL-3.0), ServerCore (MIT), Clumps (MIT), Alternate Current (MIT).
Full texts, authors, links and special conditions: [`THIRD_PARTY_LICENSES/`](THIRD_PARTY_LICENSES/) (also inside the jar at `META-INF/sugarsv/`).

## 🛠️ Building

```bash
scripts\windows\build.cmd              # native part
cd fabric && gradlew.bat allInOneJar   # all-in-one jar
```

## ⚠️ Known limitations

- Possible hand trail in first person with Aetherium.
- Frame Generation x3/x4 misbehaves with VSync.
- Not tested at 1440p / 4K or in very long sessions.
- Binaries are unsigned: Windows *Smart App Control* may block them on other PCs.

## 📜 Licences

Code in this repository: [MIT](LICENSE). AMD FidelityFX / FSR belongs to AMD. Bundled mods keep their own licences.
