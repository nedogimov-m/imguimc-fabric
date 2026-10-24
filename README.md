# ImGuiMC — Fabric 26.3

Native ImGui Minecraft bindings for Fabric, RenderPearl and SDL3 on Minecraft 26.3. Upstream revision and source attribution: `UPSTREAM.json`; license: `LICENSE`.

## Build

Use JDK 25 and run `./gradlew assemble --no-configuration-cache`. Output: `build/libs/imguimc-fabric-*.jar`. Binding and platform-native dependencies are resolved from Maven and nested by the build.

Veil includes this project through a Gradle composite build and bundles its runtime. Only the Fabric implementation and shared source are present. The Fabric 26.3 assembly and native client initialization passed.
