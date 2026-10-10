# VersatiaCore

Core of the **Versatia framework**: a Paper **1.21.4** plugin written in Kotlin and built with
Gradle.

VersatiaCore is the runtime of the framework. It implements the API of VersatiaAPI, a library
developed in parallel in its own repository, and interprets the descriptors that VersatiaAPI's
compile-time step generates into plugins. The runtime does not scan anything: it locates each
plugin's descriptor by name and hands its elements to enhancement processors. See
[docs/architecture.md](docs/architecture.md) for the overall design and
[docs/runtime.md](docs/runtime.md) for how plugins are enhanced.

The current version (`0.0.1`) provides dependency injection and lifecycle hooks: `@LocalBean`,
`@ExposeSingleton` and `@ExposePrototype` beans are created in dependency order when a plugin
enables and shared across plugins according to their scope; `@AutoInvoke` functions run at the
declared phase with their parameters injected from those beans; the files a plugin ships under
`resources/` are extracted into its data folder when it starts, and its Bukkit listeners are
registered.

## Requirements

- JDK 21 or newer
- VersatiaAPI published to your local Maven repository: run `./gradlew publishToMavenLocal`
  in its checkout once, and again after every change there (it publishes both `VersatiaAPI`
  and `VersatiaAPI-ksp`, the latter is used by this project's tests)
- Nothing else. Gradle is downloaded automatically by the wrapper (`gradlew`).

## Building

```bash
./gradlew build
```

Runs the tests and produces `build/libs/VersatiaCore-<version>.jar`, which bundles the
VersatiaAPI classes so that plugins find them on the server.

## Automatic copy to the server (optional)

Everyone keeps their server in a different place, so the path is not part of the repository.

1. Copy `local.properties.example` to `local.properties`.
2. Set `pluginsDir` in it to your server's `plugins/` directory.

From now on `./gradlew build` copies the JAR to the server. Without `local.properties` the build
still works and the copy step is skipped. The path can also be passed for a single run:

```bash
./gradlew build -PpluginsDir="D:/server/plugins"
```

## Project layout

- `src/main/kotlin/com/github/marcoral/versatia/` – the runtime itself: `VersatiaCore` (main
  plugin class, registers the engine service), `VersatiaRuntime`, `PluginEnhancer`,
  `ProcessorLoader`, the plugin state; the packages mirror VersatiaAPI's
- `src/main/kotlin/.../engine/descriptor/` – descriptor loading and the format-version check
- `src/main/kotlin/.../di/` – the bean scope processors and the per-plugin bean sessions
- `src/main/kotlin/.../lifecycle/` – the `@AutoInvoke` processor
- `src/main/kotlin/.../resources/` – the `@ExtractResources` processor
- `src/main/kotlin/.../event/` – the `@RegisterListeners` processor
- `src/test/kotlin/` – unit tests and the end-to-end dependency injection test
- `src/main/resources/plugin.yml` – plugin metadata (version and Kotlin version are filled in by Gradle)
- `local.properties.example` – template for local settings
- `docs/architecture.md` – how VersatiaAPI and VersatiaCore cooperate
- `docs/runtime.md` – enhancement pipeline and dependency injection at runtime
- `docs/build.md` – build configuration details (dependency on VersatiaAPI, settings layers, configuration cache)
