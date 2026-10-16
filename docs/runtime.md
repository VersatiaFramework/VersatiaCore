# Runtime: enhancing plugins and dependency injection

VersatiaCore interprets the descriptors that VersatiaAPI's KSP processor generates into plugin
JARs. This document covers how a plugin reaches the runtime, how its descriptor is dispatched to
processors, and how the bean processors create objects. The compile-time side (annotations,
generated code, compile-time checks) is documented in VersatiaAPI (`docs/enhancements.md`).

The runtime is deliberately simple. It reads a descriptor, loads the processors it names, hands
each one its elements and keeps whatever state the processors build for the plugin. Every feature
lives in its processors; the classes of the root package know none of them.

## From `onEnable` to processors

1. `VersatiaCore.onEnable` creates a `VersatiaRuntime`, registers it in Bukkit's
   `ServicesManager` under the `VersatiaEngine` interface from VersatiaAPI and enhances
   VersatiaCore itself: the main class carries `@PluginBean`, the build runs the KSP processor,
   and the generated descriptor is what hooks the `/versatia` command in. That interface is the
   only link between a plugin and the runtime; plugins never compile against VersatiaCore.
2. A plugin's main class extends `VersatiaPlugin`. Its final `onEnable` looks the engine up and
   calls `enhance(this)`; `onDisable` calls `disable(this)`. There is nothing to override: start-up
   code is a function marked `@AutoInvoke`. Because the plugin lists `VersatiaCore` under
   `depend:`, the service is already registered.
3. `VersatiaRuntime` builds a `DefaultEnhancementContext` from the `Plugin`, the event and the
   plugin's state store and the runtime's (`PluginStates` keeps both) and asks `PluginEnhancer`
   to enhance the plugin.
4. `DescriptorLoader` derives the descriptor class name from `main:` in `plugin.yml`
   (`<package of main class>.VersatiaDescriptor`), loads it through the plugin's class loader,
   reads the Kotlin `object` instance and checks its format version (below). Nothing is scanned.
5. `PluginEnhancer` keeps the elements whose phase applies to the current event and whose
   `@ConditionalOnPlugin` plugins were enabled when the plugin started (below), groups them by
   `processorClass`, has `ProcessorLoader` load every one of those classes before any processor
   runs (so a missing processor leaves the plugin untouched), hands each group to its processor,
   and finally runs the actions processors registered with `context.onPluginEnhanced { }`. At
   `STOP` this is best-effort: every processor, completion action and stop function runs even when
   another fails, and the first failure is thrown at the end with the others suppressed.
6. After the start event, `VersatiaRuntime` registers the plugin in `EnhancedPlugins`, its
   `PluginRegistry`, under its friendly name, the `@FriendlyName` attribute recorded on the
   `PluginTarget` or the plugin's name, in start order. `PluginRegistryBean` (`@ExposeSingleton`,
   delegating to that registry) is part of VersatiaCore's own descriptor, so every plugin can
   inject a `PluginRegistry`.
   A name another running plugin uses is an `EnhancementException`, so the plugin stays disabled.
   A registered plugin is a running one. `reload(plugin)` runs the `RELOAD` event for a registered
   plugin and keeps the state; for any other it throws `Plugin X is not running`, from inside the
   plugin's own start `Plugin X is still starting; it can be reloaded once its start has
   completed`, and from inside its own reload `Plugin X is already reloading; a reload cannot start
   another one`. Enhancing a plugin that is running or starting throws `Plugin X is already running`.
7. When a plugin is disabled, the runtime runs its `PLUGIN_STOP` elements, drops the plugin's
   state, closing every entry that implements `AutoCloseable`, last created first, and forgets the
   registration.

Errors surface as `EnhancementException` thrown out of the plugin's `onEnable`, so Bukkit logs them
and disables that plugin only. Bukkit then calls `onDisable`, and the runtime sees a plugin whose
start did not complete: it skips the `STOP` event, so no stop function runs and no bean is created
during the disable, and only drops the state the start built. A plugin that disables itself during
its start, typically `server.pluginManager.disablePlugin(this)` from a start function, is handled
the same way: the processors, completion actions and `@AutoInvoke` functions not yet run are
skipped, whatever they stored is closed, and the plugin is never registered. One that disables
itself during a reload stops as usual, with its `STOP` event, and the rest of the reload is skipped
the same way.

## Processors

`ProcessorLoader` resolves `processorClass` with `Class.forName` through the enhanced plugin's
class loader. Bukkit's plugin loaders delegate to the loaders of the plugins listed under
`depend:`, so a processor may live in VersatiaCore (bundled with the API classes) or in any plugin
the enhanced one depends on; a plugin can ship its own annotations and processors without touching
VersatiaCore. One instance per class is created, with the no-argument constructor or by taking the
instance of a Kotlin `object`, and kept for the lifetime of the runtime. A class that cannot be
found, is not an `EnhancementProcessor` or cannot be created is reported with the plugin's name:

```
Plugin MyPlugin needs the enhancement processor com.example.MyProcessor, which is not available. Is the plugin providing it installed and listed under depend:?
```

Processors keep nothing per plugin in their fields. `EnhancementContext.state` is a `StateStore`:
entries are created on first use with `getOrPut`, kept across `START`, `RELOAD` and `STOP`, shared
between processors, and dropped when the plugin is disabled. `EnhancementContext.runtimeState` is
a second store that lives as long as VersatiaCore, for what spans plugins; `VersatiaCore.onDisable`
closes it. Entries implementing `AutoCloseable` are closed when their store goes. This is how the
bean session reaches the auto-invoke processor (published as `BeanResolver`), how exposed beans
are kept between plugins (`ExposedBeanRegistry` in the runtime state), and how they disappear with
their owner (the session is `AutoCloseable`).

## Descriptor format compatibility

Every descriptor carries the `major.minor` version of the format it was generated with; this
runtime supports the version of the VersatiaAPI it bundles (`DescriptorLoader.supportedFormat`).
The two numbers are read through plain getters *before* the descriptor is cast to
`VersatiaPluginDescriptor` and before `elements` is touched (the generator makes it a getter), so
even a descriptor that references element kinds this runtime does not have produces the version
message rather than a `ClassCastException` or `NoClassDefFoundError`. `DescriptorCompatibility`
accepts exactly the supported version; any other is an error naming the plugin, both versions and
the fix (rebuild the plugin, or update VersatiaCore). The plugin whose descriptor is rejected stays
disabled; others are unaffected.

## Lifecycle events and phases

Every element carries the phase its enhancement declared (`EventType`). The runtime walks a
plugin's descriptor at `LifecycleEvent`s and dispatches only the elements whose phase applies:

| Event    | Triggered by                                           | Phases dispatched                              |
|----------|--------------------------------------------------------|------------------------------------------------|
| `START`  | `VersatiaPlugin.onEnable`, `VersatiaEngine.enhance`    | `PLUGIN_START_ONLY`, `PLUGIN_RELOAD_OR_START`  |
| `RELOAD` | `/versatia reload`, `VersatiaEngine.reload` (behind `plugin.versatiaReload()` and the static `VersatiaEngine.reload(name)`) | `PLUGIN_RELOAD_ONLY`, `PLUGIN_RELOAD_OR_START` |
| `STOP`   | `VersatiaPlugin.onDisable`, `VersatiaEngine.disable`   | `PLUGIN_STOP`                                  |

`EnhancementContext.event` tells a processor which event it is serving. The bean scopes run at
`PLUGIN_START_ONLY`.

### Conditions

`@ConditionalOnPlugin` is the second generic filter of `PluginEnhancer`, next to the phase: an
element whose target records the attribute is dispatched only when every plugin it names is
enabled (`PluginManager.isPluginEnabled`). The decision is taken once, when the plugin starts, and
kept in the plugin's state, so an element skipped then stays skipped at every reload and at stop,
like the beans it would have made, even when the required plugin is enabled later. A skipped bean
does not exist for resolution: a `@Primary` on it counts only while its condition holds, and
another provider of the type is injected instead. For a `FunctionTarget` the `classAnnotations` recorded
from its declaring class count too, so a conditional bean or `object` takes its `@AutoInvoke`
functions with it. A condition written through an annotation class meta-annotated with
`@ConditionalOnPlugin` arrives the same way: the compile-time step records the inherited
occurrence next to it. The skipped classes and the plugins they wait for are logged at `INFO` when the plugin starts
(`Skipped VersatiaCommand: CommandAPI is not installed`), so an administrator sees why a command is missing. The filter reads the recorded attribute and knows
no feature; the class behind a skipped element is never loaded, which is what lets it reference
an optional plugin's classes.

## Commands (`engine.command`)

VersatiaCore's commands are objects in `engine.command`, written with the CommandAPI Kotlin DSL,
carrying `@ConditionalOnPlugin("CommandAPI")`, with `register` at `@AutoInvoke(PLUGIN_START_ONLY)`
and `unregister` at `PLUGIN_STOP`; the CommandAPI plugin is a `softdepend:` and only its DSL
ships in the JAR (see build.md). `VersatiaCommand` provides `/versatia reload [plugin]` behind the
permission `versatia.core.command.reload`: it reads the runtime's registry, runs
`reload` for every selected plugin, reports each failure to the sender and the console and
continues, whatever the plugin threw short of a VM error. The sender sees
`<name> failed to reload: <reason>`, where the reason joins the messages of the exception and its
causes, without the repeated `Plugin X:` prefix and without a message the previous one already
contains. A configuration mistake (a wrong value, broken YAML or a missing file) is logged as one
`WARNING` with that text and no stack trace, anything else at `SEVERE` with the trace. If
CommandAPI fails to register or unregister the command, typically because the installed release is
not compatible with the 12.x one VersatiaCore is built against, VersatiaCore logs one warning,
`/versatia could not be registered. The installed CommandAPI <version> may not be compatible ...`,
and keeps running without the command; an unknown name answers with the registered ones, which tab completion offers too.
VersatiaCore itself is in the registry, so a full reload includes it (it has no reload elements).

## Bean processors (`di`)

`LocalBeanEnhancementProcessor`, `ExposeSingletonEnhancementProcessor` and
`ExposePrototypeEnhancementProcessor` share one base, `BeanScopeEnhancementProcessor`. They do not
create anything themselves: each turns its elements into `BeanDefinition`s and declares them in
the plugin's `PluginBeanSession`, which lives in the plugin state under `BeanResolver.KEY`
(`PluginBeanSession.of(context)`) and creates everything once, in the completion action of the
start event. That is why all three scopes can depend on each other inside one plugin regardless of
the order in which their processors ran. Declaring beans at any other event is rejected with an
`EnhancementException`; the compile-time step already refuses a scope that would try.

`BeanDefinition.of` is where the descriptor's facts become DI concepts: the bean name is the
`@Qualifier` attribute on the class or the default (`BeanNames.defaultFor`), `@Primary` is read
from the attributes, and each constructor `Parameter` becomes a `Dependency` of its type, the
`@Qualifier` on it, whether it is `optional` and which processor it is `suppliedBy`, if any.

`PluginBeanEnhancementProcessor` receives the descriptor's `PluginTarget`: the plugin's main
class, sent there by `@PluginBean` on `VersatiaPlugin`. `BeanDefinition.plugin` adopts the `Plugin` from the context under the fixed name `plugin`
(`BeanNames.PLUGIN`) with an instantiator that returns it, so the instance Bukkit created is
injectable under its own type and every supertype, is never exposed, and nothing is constructed for
it. The scope processors accept only `ClassTarget`s, and a descriptor carrying the main class more
than once is refused. The element's phase is `PLUGIN_RELOAD_OR_START`: at the reload event the
processor calls `PluginBeanSession.reload()` instead, which is how reloadable beans get their turn
(below).

### Reloadable beans

The session *adopts* every instance it makes or obtains: its own beans and the prototype instances
made for its plugin. An adopted instance implementing `Reloadable` is remembered in creation
order and reloaded in the completion action of the start event, right after `instantiateAll`,
so once every bean exists and after `ValueEnhancementProcessor` set the plugin's `@Value`
properties in `process`; a `reload()` may therefore read them, which is the point of the order: a
`reload()` may depend on a value, a value depends on nothing, so the smaller operation goes first.
The price is that a dependent's constructor no longer sees a loaded `Reloadable`, which the
constructors-only-wire rule already forbids relying on. At the reload event
`PluginBeanEnhancementProcessor` registers `PluginBeanSession.reload()` as a completion action,
again after the values; both actions precede the auto-invoke processor's, so reload-phase
functions always see reloaded beans. The session remembers that the first pass ran (`loaded`),
and an instance adopted afterwards, a prototype first obtained by a function at a reload say,
is reloaded as soon as it is adopted. A throwing `reload()` is wrapped as
`Plugin X: <class> failed to reload`. Exposed singletons are adopted by their owner only, so a
consumer's reload never touches them.

### Resolution rules

For every `Dependency` (a type `T` and an optional qualifier) of a bean or an `@AutoInvoke` function:

1. Take the beans of the same plugin whose assignable types include `T`; with a qualifier, only
   the one whose name matches.
2. If that set is empty, take the exposed beans of plugins enhanced earlier, filtered the same way.
   Zero candidates: `no bean provides T, required by X`.
3. One candidate is injected. Among several, the single `@Primary` one is; otherwise the error lists
   the candidates by name, class and owning plugin, with the hint to add `@Qualifier` or `@Primary`.

An optional constructor parameter (one with a default value) with zero candidates resolves to
`DefaultArgument`, which `Instantiator.WithDefaults` leaves out of its `callBy`, so the default
applies; a prototype made for a consumer reuses the owner's default. A parameter with `suppliedBy`
is not resolved: the session passes what that processor handed to `supply` (an index of the
constructor) before the beans were created. A missing argument fails the creation with
`Plugin X: <class> expects <processor> to supply parameter <n>, which it has not`, a `supply` after
creation fails with `Plugin X: the argument of parameter <n> of <class> was supplied after the
beans had been created`, and `resolve` refuses a supplied parameter, which only bean constructors
can take. Supplied arguments of a prototype are the owner's for every consumer.

The qualifier filters before the local-first preference, so a named request may reach an exposed
bean past a local bean of the same type. The compile-time step applies the same rules to the local
beans, so at runtime only exposed beans can still produce these errors.

Instances are created depth-first, so dependencies always exist before their dependents, and each
definition is instantiated once per plugin. A cycle that slipped past the compile-time check is
still detected and reported.

### Scopes at runtime

| Scope                | After the owning plugin has started                                           |
|----------------------|-------------------------------------------------------------------------------|
| `@LocalBean`         | kept in the plugin's session, injectable only into that plugin                |
| the main class       | a local bean backed by the existing plugin instance; same visibility as above |
| `@ExposeSingleton`   | the instance is registered in `ExposedBeanRegistry` and handed to every later plugin that asks |
| `@ExposePrototype`   | a factory is registered; it creates a **new instance per consuming plugin** from the constructor arguments resolved when the owner started, except for the arguments that depend on the consumer (below) |

A prototype used by two beans (or functions) of the same plugin is created once for that plugin.
The owning plugin's own instance is never handed out.

When the owner starts, `PluginBeanSession.sourceOf` classifies each constructor parameter of a
prototype once; the factory then builds an instance for a consumer from:

| The owner resolved the parameter to   | The consumer's instance receives                                    |
|---------------------------------------|---------------------------------------------------------------------|
| its plugin bean                       | the consuming plugin, checked with `isInstance` against the parameter type (the compile-time step only sees `VersatiaPlugin`s) |
| a prototype, its own or an exposed one | the instance of that prototype made for the consumer, resolved by the consumer's session with its own rules, so the consumer's beans share it |
| anything else                         | the owner's argument                                                |

The factory receives the consumer's resolution as a function, so a prototype built from other
prototypes (a `Messages` taking a `Text`, say) forms a chain made entirely for the consumer.

### Lifetime

A plugin's bean session, and the beans it exposed, live exactly as long as the plugin: the session
is created when the plugin starts and closed when its state is dropped after its `PLUGIN_STOP`
elements ran, which withdraws its entries from `ExposedBeanRegistry` and closes every
`AutoCloseable` instance the session adopted: its own beans and the prototype instances made for
it, never a singleton exposed by another plugin. They close in reverse adoption order, and since a
bean is adopted after its dependencies, dependents close first; every instance is closed even when
one fails, and the failures surface as one `EnhancementException` (`Plugin X: <class> failed to
close`) with the others suppressed. `VersatiaRuntime.disable` still unregisters the plugin when
closing fails. Nothing is tied to server
load, so a plugin enabled later (for example through a plugin manager) sees the exposed beans of
the plugins running at that moment, and re-enabling a plugin replaces its beans. Beans are never
re-created on reload; `Reloadable` ones are reloaded and reload-phase functions receive the
retained instances.

## Auto-invoked functions (`lifecycle`)

`AutoInvokeEnhancementProcessor` handles `FunctionTarget`s. It injects through the plugin's
`BeanResolver` (`context.state[BeanResolver.KEY]`), the same contract any other processor would
use, and never touches the session class. It registers a completion action, so functions run
after every other processor of the event has finished, in particular after the beans of a starting
plugin exist. Functions run in descriptor order (class name, then function name). For each function
the processor:

1. picks the receiver by `MemberOwner` (`engine.descriptor.receiverOf`, shared with the value
   processor): the plugin instance from the context, the `INSTANCE` of a Kotlin `object`, or
   (`MemberOwner.INSTANCE`) the bean of that class from the plugin's bean session;
2. resolves the parameters through the bean session with the same rules as constructor
   dependencies (local beans first, then exposed beans; one prototype instance per plugin);
3. calls the `Invoker`. Any exception, and a `LinkageError` such as a `NoSuchMethodError`, is
   wrapped in an `EnhancementException` naming the function and the event, with the original as
   cause.

Because the plugin's beans are kept for its whole lifetime (above), this works at every phase, not
only at start.

## Configuration values (`config`)

`ValueEnhancementProcessor` handles the `PropertyTarget`s that `@Value` produced. It works in
`process` itself, like resource extraction, so the values are in place before any completion
action: before the bean session reloads its `Reloadable` beans and before the auto-invoke
processor runs the functions. The generator lists property elements after the bean elements, so
by then every scope has declared its beans and the resources are extracted; a property of a bean
asks the `BeanResolver` for the instance, and `instanceOf` makes the session create all beans at
that point (the later instantiation action finds them created). The processor loads every file
the elements name once (`loadYamlOrThrow`, relative to the data folder), reads every value, and only then assigns them, so a broken file
changes nothing. `ValueReader` turns the recorded `TypeReference` into a read: the scalar and
Bukkit types map to the `OrThrow` getters of `util.config`, an enum is loaded through the plugin's
class loader (trying the binary names a nested class may have) and matched by constant name
ignoring case, `List` and `Set` use the strict list getters (`getStringListOrThrow` and the like,
so a scalar where a list belongs or an element of the wrong type is an error), `Map` reads a
section child by child. With
a mapper recorded in the annotation (any class but `NoMapper`), the reader creates it, the
`object` instance or a new one from the no-argument constructor, and calls `map(section, key)`
instead; a missing key is handled before, by the property's nullability. A `PreviousValueTracking<T>`
is read as its `T`, the setter storing into the cell. At start, a value whose `PropertyTarget`
records a `constructorParameter` is not set: it goes to `BeanResolver.supply` for that constructor
parameter, wrapped in a new, filled `PreviousValueTracking` for a tracked one, after every value
has been read and before any bean exists; at a reload it is set like any other, which fills the
cell the constructor received. Whatever the read
throws becomes `Plugin X: <class>.<property> could not be read from <file>: <reason>`; the
receiver is picked like for a function, and the `Setter` assigns, directly or through `util.reflect`.
A missing file is worded for whoever can fix it. When the plugin JAR carries a default copy under
`resources/`, the admin reads `Plugin X: <file> is missing from the data folder; put it back, or
reload the plugin to restore the default copy from the plugin JAR` (extraction runs at start and at
every reload, before the values are read, so this appears only when extraction is switched off);
otherwise `Plugin X: <file> is
missing from the data folder and the plugin JAR has no default copy; create it, or, as the plugin's
developer, ship it as resources/<file> in the JAR, which is extracted at start and reload unless
@DisableVersatiaFeatures(extractResources = true) is set`.

## Plugin resources (`resources`)

`ExtractResourcesEnhancementProcessor` receives the `PluginTarget` produced by `@ExtractResources`
on `VersatiaPlugin`. It locates the plugin's code through the main class
(`Class.forName(className, false, loader).protectionDomain.codeSource`), which is the plugin JAR
on a server and a class directory in tests, and copies every file under its `resources/` entry
into `plugin.dataFolder`, keeping the paths below that entry. A destination that already exists
is skipped, a symbolic link too, dangling or not, so a file the admin links to a shared copy is
left alone; the file is created with `CREATE_NEW`, never written through an existing entry. A path
that would escape the data folder, checked lexically on the normalized path, is refused. The work is done in `process`
itself, not in a completion action, so the files exist before the bean session creates anything.
When the JAR has no `resources/` entry the data folder is not even touched.

## Event listeners (`event`)

`RegisterListenersEnhancementProcessor` receives the elements `@RegisterListeners` on
`VersatiaPlugin` produced for every `Listener` of the plugin. In the completion action of the
start event, so after the bean session created the beans, it obtains an instance per element: a
`PluginTarget` is the plugin itself, a `ClassTarget` is the bean of that class when the
`BeanResolver` has one and otherwise a new instance created through the element's instantiator
with the constructor parameters resolved by the `BeanResolver` (an `EnhancementException` when the
plugin has no beans but the constructor needs some). Each instance is registered through
`plugin.server.pluginManager.registerEvents`; Bukkit unregisters them on disable, so nothing is
kept in the plugin state.

## Tests

- `ProcessorLoaderTest` covers loading by name: one instance per class, Kotlin objects, and the
  errors for a missing class, a class that is no processor and one without a usable constructor.
- `BeanLoadingTest` drives `PluginEnhancer` with hand-written descriptors: creation order,
  isolation of local beans, singleton sharing, prototype semantics including the consumer's plugin
  and prototype chains, `Reloadable` after creation, at reload and on late adoption, ambiguity, lifetime bound to the
  plugin state with `AutoCloseable` beans closed dependents first, reflective instantiation of a private class, the plugin instance as a bean, phases,
  missing processors.
- `AutoInvokeTest` covers the receivers (plugin, object, bean), parameter injection from local and
  exposed beans and of the plugin itself, reload and stop phases, reflection and error wrapping.
- `ValueTest` covers the receivers for properties, values set before reloadable beans and
  functions, reload and a broken file leaving values untouched, nullable and missing values, named
  and missing files, enums, lists, sets and maps, mappers (objects, nested objects, classes, their
  failures), tracked cells with their previous value, and reflective setters for private and
  inline-class properties;
  `ValueIntegrationTest` compiles a plugin with `@Value` properties and runs it at start and reload.
- `BeanLoadingTest` also covers optional constructor parameters (local, exposed and default) and
  supplied ones with their two errors; `ConstructorParameterIntegrationTest` compiles plugins with a
  `@Value` constructor `var` and tracking parameter (start, reload, failed reload), an optional
  parameter, and a provider conditional through a meta-annotation that falls back while its plugin
  is missing.
- `DescriptorLoaderTest` and `DescriptorCompatibilityTest` cover lookup by main class and the
  format-version rule.
- `DependencyInjectionIntegrationTest` is end to end: it compiles three sample plugins with the
  real VersatiaAPI KSP processor (`kotlin-compile-testing`, `dev.zacsweers.kctfork`), builds
  class loaders that delegate the way Bukkit's plugin loaders do, and runs the runtime over the
  generated descriptors at start, reload and stop. Sample beans and functions report to a
  `Recorder` on the test classpath. Nothing in these tests needs a running server.

`VersatiaRuntimeTest` drives the runtime over a hand-written descriptor in the `sample` test
package: the registry and its start order, `@FriendlyName`, the duplicate-name error, the reload
event and the stop event, a failed or self-cancelled start followed by a disable, a self-cancelled reload, and the reload
guards. `PluginEnhancerTest` covers `@ConditionalOnPlugin` on a bean, on its function and on an
object's function, the start decision kept at reload, a conditional `@Primary` falling back to the
other provider, and best-effort stop functions. The command needs a server and is not unit-tested.
