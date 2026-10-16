# VersatiaCore

Core of the **Versatia framework**: a Paper **1.21.4** plugin written in Kotlin and built with
Gradle.

VersatiaCore is the runtime of the framework. It implements the API of VersatiaAPI, a library
developed in parallel in its own repository, and interprets the descriptors that VersatiaAPI's
compile-time step generates into plugins. The runtime does not scan anything: it locates each
plugin's descriptor by name and hands its elements to enhancement processors. See
[docs/architecture.md](docs/architecture.md) for the overall design and
[docs/runtime.md](docs/runtime.md) for how plugins are enhanced.

## A private repository

Unlike VersatiaAPI, the documentation and the examples, this repository is **private**: the
runtime is the part of Versatia that is protected and may be offered commercially in the future.
Access to the sources is granted individually by the Versatia team; everybody else uses VersatiaCore
as a plugin JAR, which is all a server or a plugin author needs. Nothing a plugin compiles against
lives here, so the public VersatiaAPI and the documentation describe the runtime's behaviour in full.

The current version (`0.0.1`) provides:

- dependency injection: `@LocalBean`, `@ExposeSingleton` and `@ExposePrototype` beans are created
  in dependency order when a plugin enables and shared across plugins according to their scope,
  with `@Qualifier`, `@Primary` and optional constructor parameters; `Reloadable` beans reload with
  their plugin and `AutoCloseable` ones close with it; a `PluginRegistry` bean lists the running
  Versatia plugins by their `@FriendlyName`;
- lifecycle hooks: `@AutoInvoke` functions run at the declared phase with their parameters
  injected from those beans;
- configuration values: `@Value` properties are read from the plugin's YAML files at start and
  at every reload, before the beans reload and the functions run, all or nothing;
- per-plugin features of every `VersatiaPlugin`, each switchable with `@DisableVersatiaFeatures`:
  the files it ships under `resources/` are extracted into its data folder, and its Bukkit
  listeners are registered;
- reload: `VersatiaEngine.reload` and, with the [CommandAPI](https://commandapi.dev/) plugin
  installed, `/versatia reload [plugin]` run the reload phase of every Versatia plugin or of one,
  then of the plugins holding its beans with `@Value` properties;
- `@ConditionalOnPlugin`: elements are skipped for the plugin's lifetime when the plugins they need
  were not enabled at its start.

## Requirements

- JDK 21 or newer
- VersatiaAPI published to your local Maven repository: run `./gradlew publishToMavenLocal`
  in its checkout once, and again after every change there (it publishes both `VersatiaAPI`
  and `VersatiaAPI-ksp`, the latter is used by this project's tests)
- Nothing else. Gradle is downloaded automatically by the wrapper (`gradlew`). On the server, the
  CommandAPI plugin is optional; it enables `/versatia reload`.

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
  `ProcessorLoader`, the plugin state, the registry of enhanced plugins (`EnhancedPlugins`, exposed
  as a bean by `PluginRegistryBean`), `ReloadOutcome` (what a reload reports); the packages
  mirror VersatiaAPI's
- `src/main/kotlin/.../engine/descriptor/` – descriptor loading and the format-version check
- `src/main/kotlin/.../di/` – the bean scope processors, the per-plugin bean sessions and the
  registry of beans exposed between plugins
- `src/main/kotlin/.../config/` – the `@Value` processor and `ValueReader`
- `src/main/kotlin/.../lifecycle/` – the `@AutoInvoke` processor
- `src/main/kotlin/.../resources/` – the `@ExtractResources` processor
- `src/main/kotlin/.../event/` – the `@RegisterListeners` processor
- `src/main/kotlin/.../engine/command/` – `/versatia`, written with the CommandAPI Kotlin DSL
- `src/test/kotlin/` – unit tests per processor and for the runtime, and end-to-end tests that
  compile sample plugins with the real KSP processor (dependency injection, constructor
  parameters, values across plugins); see the Tests section of [docs/runtime.md](docs/runtime.md)
- `src/main/resources/plugin.yml` – plugin metadata (version and Kotlin version are filled in by Gradle)
- `local.properties.example` – template for local settings
- `docs/architecture.md` – how VersatiaAPI and VersatiaCore cooperate
- `docs/runtime.md` – enhancement pipeline and dependency injection at runtime
- `docs/build.md` – build configuration details (dependency on VersatiaAPI, settings layers, configuration cache)
