# Build configuration

This document explains the non-obvious parts of `build.gradle.kts`. The build itself is a plain
Kotlin/JVM project; everything below is about how it adapts to each developer's machine.

## Toolchain

- Paper 1.21.4 requires Java 21, so both `java` and `kotlin` blocks target JVM 21.
  Any JDK 21 or newer can compile the project; no Gradle toolchain download is configured.
- Gradle comes from the wrapper (`gradlew`), so nothing has to be installed globally.
- Versions come from the shared catalog `VersatiaCatalog` (published by VersatiaAPI), imported
  as `libs` in `settings.gradle.kts` at the `versatiaVersion` from `gradle.properties`.
- `kotlin-stdlib` and `kotlin-reflect` are not bundled into the JAR. `plugin.yml` lists them under
  `libraries:` and Paper downloads them at server start. The version placeholder is filled from the
  Kotlin Gradle plugin version during `processResources`, so the versions can never drift apart.
  `kotlin-reflect` is needed because VersatiaAPI's `util.reflect` package depends on it at runtime.
- `kotlinx-coroutines` is not bundled either. `plugin.yml` lists
  `org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:${coroutinesVersion}`, the JVM artifact
  because Paper's Maven resolver reads POMs only and cannot pick the JVM variant of the
  multiplatform `kotlinx-coroutines-core`; `processResources` fills the placeholder from the
  catalog's `coroutines` version, the one VersatiaAPI exports with a strict version, so the
  `libraries:` line always matches what plugins compile against. VersatiaCore is the only plugin
  that lists it.

## Dependency on VersatiaAPI

The main production dependency is `com.github.marcoral.versatia:VersatiaAPI`; the only other one
is CommandAPI, described [below](#commandapi). VersatiaAPI exports the Paper API (compile
classpath only), the Kotlin standard library, `kotlin-reflect` and `kotlinx-coroutines-core`, so
none of them is declared here.
Three things cannot be inherited through a dependency and therefore stay in this build:

- the Kotlin Gradle plugin, because plugins are not dependencies,
- the Paper repository, because Gradle never inherits repositories,
- the Java 21 target settings.

VersatiaAPI is resolved from the local Maven repository (`mavenLocal()`, filtered to the
`com.github.marcoral.versatia` group; all repositories are declared in `settings.gradle.kts`), where its own build
publishes it with `./gradlew publishToMavenLocal`. The two builds share nothing else; from this
project's point of view VersatiaAPI is an ordinary external library.

### VersatiaCore enhances itself

The KSP plugin and `VersatiaAPI-ksp` are applied to the main source set exactly as in a plugin
build, so `com.github.marcoral.versatia.VersatiaDescriptor` is generated for VersatiaCore's own
main class. That is how the `/versatia` command is declared: as an auto-invoked function, not as
code in `onEnable`.

### CommandAPI

The CommandAPI plugin is a `softdepend:`, so `commandapi-paper-core` is `compileOnly`: the
classes come from the CommandAPI plugin on the server, when it is there. Its Kotlin DSL
(`commandapi-kotlin-paper` and what it pulls in) is a few extension functions that CommandAPI
does not ship in its plugin, so they are bundled into the JAR like the VersatiaAPI classes. The
DSL only references CommandAPI's API, and the command object is loaded only when the plugin is
present, so no mapping or class-loading issue arises without it. The DSL is compiled against
CommandAPI 12.x; when the installed release is not compatible, registering the command fails,
VersatiaCore logs one warning and keeps running without `/versatia`.

### VersatiaAPI classes at runtime

The `jar` task unpacks the `VersatiaAPI-*` and `commandapi-kotlin-*` JARs from the runtime
classpath into the plugin JAR (`kotlin_module` files excluded). VersatiaAPI is not published to a remote repository, so
Paper's `libraries:` mechanism cannot fetch it; `kotlin-stdlib`, `kotlin-reflect` and
`kotlinx-coroutines-core-jvm` are on the runtime classpath too but left to `libraries:`. Plugins declare `depend: [VersatiaCore]`, which makes Bukkit's plugin class
loader resolve VersatiaAPI classes from this JAR, so plugins must not bundle their own copy.
The same holds for Kotlin itself: Paper loads each plugin's `libraries:` into a separate class
loader, so a dependent plugin that listed `kotlin-stdlib` would get a second `Function1` class and
the JVM would reject the descriptor lambdas with a `LinkageError`. VersatiaCore is the only plugin
that lists the Kotlin libraries.
`zipTree` goes through an injected `ArchiveOperations` service so the task stays compatible with
the configuration cache.

## Tests

`kotlin("test")` with `useJUnitPlatform()` and `junit-platform-launcher` as `testRuntimeOnly`, as
in VersatiaAPI. Three test-only dependencies make the end-to-end test possible:

- `VersatiaAPI-ksp`, the real KSP processor, resolved from the local Maven repository like the
  API,
- `dev.zacsweers.kctfork:ksp`, which runs the Kotlin compiler with KSP in-process. It is built
  against exact Kotlin and KSP versions, so bump it together with the Kotlin plugin (all three in the catalog),
- `paper-api` as `testImplementation`: the tests stand in for a plugin with a `java.lang.reflect.Proxy`
  implementing `org.bukkit.plugin.Plugin`, which needs the interface at runtime. VersatiaAPI
  exports it for compilation only, so a plugin's own tests need the same declaration.

See [runtime.md](runtime.md#tests) for what the tests cover.

## Machine-specific settings

The project is shared via git and every developer has a server in a different place, so no absolute
path lives in a committed file. The only machine-specific value today is `pluginsDir`.

Resolution order (first match wins):

1. Command-line property: `./gradlew build -PpluginsDir="D:/server/plugins"`
2. `~/.gradle/gradle.properties` entry `pluginsDir=...` (applies to all projects on the machine)
3. `local.properties` in the project root (gitignored; template in `local.properties.example`)
4. Not set: the `deployPlugin` task is skipped and the build still succeeds.

Forward slashes work on every OS and avoid escaping issues in `.properties` files.

## The `deployPlugin` task

`deployPlugin` is a `Copy` task that runs after `build` (`finalizedBy`) and copies the JAR produced
by `jar` into `pluginsDir`. When `pluginsDir` is absent the task is skipped with an explanatory
`onlyIf` reason that shows up in `--info` output.

## Configuration cache

`org.gradle.configuration-cache=true` is enabled in `gradle.properties`, so Gradle serializes the
task graph and skips the configuration phase on unchanged builds. This imposes two rules on the
script, both of which the current code follows:

- Values must be read through *providers*, never by touching the filesystem directly at
  configuration time. `local.properties` is read with `providers.fileContents(...)`, which also
  registers the file as a build input. Editing or deleting it invalidates the cache automatically.
- Lambdas inside task configuration (`onlyIf`, `doLast`, ...) must not capture fields of the build
  script object. That is why the task copies the provider into a local `val` before using it.

If you add a new machine-specific setting, follow the same pattern: read it with a provider,
give it an `orElse` fallback, and never reference the script-level `val` from inside a task lambda.
