#!/bin/bash
# Builds the mod against Prism's libraries (NeoForge 21.1.252 / FML 4.0.44 / LWJGL 3.3.3) and produces the jar.
set -e
cd "$(dirname "$0")"
L="${PRISM_LIBS:-C:/Users/mim/AppData/Roaming/PrismLauncher/libraries}"
CP="$L/net/neoforged/fancymodloader/loader/4.0.44/loader-4.0.44.jar;$L/net/neoforged/fancymodloader/earlydisplay/4.0.44/earlydisplay-4.0.44.jar;$L/org/slf4j/slf4j-api/2.0.16/slf4j-api-2.0.16.jar"
for m in lwjgl lwjgl-glfw lwjgl-opengl lwjgl-stb; do CP="$CP;$L/org/lwjgl/$m/3.3.3/$m-3.3.3.jar"; done
rm -rf build && mkdir -p build/classes
javac -nowarn -d build/classes -cp "$CP" src/pondering/loading/*.java
cp -r resources/META-INF resources/pmm-icon.png build/classes/
jar --create --file build/PMM-1.0.jar --manifest resources/manifest.mf -C build/classes .
echo "$CP" > build/classpath.txt
echo "OK build/PMM-1.0.jar"
