# Licencias de terceros / Third-party licenses

El `.jar` todo en uno incluye, **sin modificar**, estos mods. Cada uno conserva su propia licencia; el texto completo está en esta carpeta y también dentro del jar (`META-INF/sugarsv/licenses/`). La lista generada con versiones, autores y enlaces está en `META-INF/sugarsv/THIRD_PARTY.txt`.

| Mod | Versión | Licencia | Autor | Enlace | Texto |
|---|---|---|---|---|---|
| Fabric API | 0.166.2+26.3 | Apache-2.0 | FabricMC | https://fabricmc.net | [fabric-api_Apache-2.0.txt](fabric-api_Apache-2.0.txt) |
| Sodium | 0.9.2+mc26.3 | PolyForm Shield 1.0.0 | JellySquid (jellysquid3) | https://github.com/CaffeineMC/sodium | [sodium_Polyform-Shield-1.0.0.md](sodium_Polyform-Shield-1.0.0.md) |
| Aetherium | 0.1.3+mc26.3-fabric | LGPL-3.0-only | SpaceBod | _(el mod no declara enlace)_ | [aetherium_LGPL-3.0.txt](aetherium_LGPL-3.0.txt) |
| ImmediatelyFast | 1.17.1+26.3 | LGPL-3.0-or-later | RK_01 | https://github.com/RaphiMC/ImmediatelyFast | [immediatelyfast_LGPL-3.0.txt](immediatelyfast_LGPL-3.0.txt) |
| BadOptimizations | 2.4.1 | ISC (según su repositorio) | Thosea | https://github.com/imthosea/BadOptimizations | [badoptimizations_LICENSE.txt](badoptimizations_LICENSE.txt) |
| EntityCulling | 1.12.1 | tr7zw Protective License | tr7zw | https://github.com/tr7zw/EntityCulling | [entityculling_tr7zw-Protective-License.txt](entityculling_tr7zw-Protective-License.txt) |
| FerriteCore | 9.0.0 | MIT | malte0811 | https://github.com/malte0811/FerriteCore | [ferritecore_MIT.txt](ferritecore_MIT.txt) |
| Lithium | 0.26.3+mc26.3 | LGPL-3.0-only | JellySquid, 2No2Name | https://github.com/CaffeineMC/lithium-fabric | [lithium_LGPL-3.0.txt](lithium_LGPL-3.0.txt) |
| C2ME | 0.4.2-alpha.0.119+26.3 | LGPL-3.0-only | RelativityMC, ishland | https://github.com/RelativityMC/C2ME-fabric | [c2me_LGPL-3.0.txt](c2me_LGPL-3.0.txt) |
| ServerCore | 1.5.20+26.3 | MIT | Wesley1808 | https://github.com/Wesley1808/ServerCore | [servercore_MIT.md](servercore_MIT.md) |
| Clumps | 26.3.2 | MIT | Jaredlll08 | https://github.com/jaredlll08/Clumps | [clumps_MIT.txt](clumps_MIT.txt) |
| Alternate Current | 1.9.0 | MIT | Space Walker | https://github.com/SpaceWalkerRS/alternate-current | [alternate-current_MIT.txt](alternate-current_MIT.txt) |

También se incluyen los binarios oficiales del **AMD FidelityFX SDK 2.4.0** (solo DLL), con su licencia (`AMD_FidelityFX_SDK_license.md`) y aviso de terceros dentro del jar.

## Condiciones especiales

- **Sodium (PolyForm Shield 1.0.0)**: permite distribuir copias si el receptor recibe los términos de la licencia (incluidos aquí), pero prohíbe ofrecer un producto que compita con Sodium. Se distribuye sin modificar y como dependencia, no como sustituto.
- **EntityCulling (tr7zw Protective License)**: prohíbe usar el software para obtener ventaja comercial o compensación económica. Este proyecto es gratuito y sin monetización; si lo monetizas, quita este mod.
- **LGPL-3.0 (Aetherium, ImmediatelyFast, Lithium, C2ME)**: se distribuyen sin modificar como jars independientes anidados, de modo que se pueden sustituir por otra versión.
- **Aetherium y BadOptimizations**: la licencia de BadOptimizations no está en su `fabric.mod.json`; se tomó de su repositorio. Aetherium no declara enlace. Ambos puntos están pendientes de confirmar con sus autores.

Si eres autor de alguno y prefieres que no se redistribuya, abre un issue.
