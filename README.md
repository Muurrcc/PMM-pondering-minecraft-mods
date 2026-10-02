# PMM — Pondering Minecraft Mods

Pantalla de carga temprana para **NeoForge 1.21.1** con el estilo "Pondering…": fondo crema, un glifo que se funde entre frames y un verbo que cambia con efecto de escritura. Mantiene las barras de progreso reales de NeoForge.

*English: a drop-in NeoForge early-loading-screen replacement (spinner glyph + cycling verbs + real progress bars). Windows only.*

![PMM loading screen](docs/pmm-loading.gif)

| | | |
|---|---|---|
| ![](docs/screenshot-1.png) | ![](docs/screenshot-2.png) | ![](docs/screenshot-3.png) |

## Qué hace

- Sustituye la pantalla roja del zorro que sale mientras arranca Minecraft.
- Glifo `· ✢ * ✶ ✻ ✽` (y vuelta) con fundido de 170 ms entre frames.
- 82 verbos que rotan (2 s, 3 s, 5 s…) con cascada de escritura de 40 ms por paso (`▌` → `. _` → letra).
- Barras de progreso de NeoForge (hasta 2) redondeadas, en naranja `#c6613f` sobre crema `#fffbf5`, con su texto debajo.
- Sin música, sin hora, sin logo de Mojang.

## Instalación

1. Copia [`dist/PMM-1.0.jar`](dist/PMM-1.0.jar) a la carpeta `mods` de tu instancia.
2. En `config/fml.toml` pon:
   ```toml
   earlyWindowProvider = "pondering"
   ```
3. Arranca el juego.

Para quitarlo: `earlyWindowProvider = "fmlearlywindow"` (y borra el jar).

**Requisitos:** NeoForge 21.1.x (probado con 21.1.252 / FML 4.0.44), Windows (usa `segoeui.ttf` y `seguisym.ttf` de `C:\Windows\Fonts`).

## Tecnología

- **Java 21**, OpenGL 3.2 core (LWJGL 3.3.3: GLFW, OpenGL, stb_truetype). Sin dependencias extra.
- **Proveedor de ventana temprana**: `PonderingWindow` hereda de `DisplayWindow` de NeoForge (el overlay de carga de NeoForge exige ese tipo) y se registra como `ImmediateWindowProvider` con el nombre `pondering` mediante `META-INF/services`. Al estar en `mods/` con ese servicio, FML lo carga en la capa SERVICE.
- **Sustitución de elementos**: tras `initialize`, un hilo cambia por reflexión la lista privada `elements` por un único `PonderingElement`. Como el constructor de `RenderElement` pide un tipo no público, se instancia con `Unsafe.allocateInstance`.
- **Render propio** (`Scene`): shader GLSL 150 con dos modos (texto desde atlas de `stb_truetype` y rectángulos redondeados por SDF), atlas con oversampling 2×2, glifos centrados por su caja real.
- **Colores**: se parchean por reflexión `ColourScheme.RED/BLACK` para que el fondo y el fundido final usen el crema.
- **Compatibilidad de módulos**: `Module.addReads` solo funciona sobre el propio módulo del llamador, así que `updateModuleReads` se reimplementa en el mod.
- **Seguridad**: si algo falla al montar la pantalla, se queda la de NeoForge; los errores van a `logs/pondering-trace.log` (los pasos, solo con `-Dpondering.trace=true`).

## Compilar

```bash
./build.sh        # genera build/PMM-1.0.jar (usa las librerías de Prism Launcher)
```

`build.sh` compila contra las librerías de Prism (`PRISM_LIBS` para cambiar la ruta). Prueba visual sin Minecraft: `test/Harness.java` abre una ventana 854×480 y guarda capturas PNG.

## Créditos

Estilo inspirado en el spinner de "pensando" de los asistentes de IA en editores. Proyecto independiente, sin relación con Mojang, NeoForged ni Anthropic. Licencia MIT.
