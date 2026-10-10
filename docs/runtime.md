# Runtime: enhancing plugins and dependency injection

VersatiaCore interprets the descriptors that VersatiaAPI's KSP processor generates into plugin
JARs. This document covers how a plugin reaches the runtime, how its descriptor is dispatched to
processors, and how the bean processors create objects. The compile-time side (annotations,
generated code, compile-time checks) is documented in VersatiaAPI (`docs/enhancements.md`).

The runtime is deliberately simple. It reads a descriptor, loads the processors it names, hands
each one its elements and keeps whatever state the processors build for the plugin. Every feature
lives in its processors; the classes of the root package know none of them.

## From `onEnable` to processors

1. `VersatiaCore.onEnable` creates a `VersatiaRuntime` and registers it in Bukkit's
   `ServicesManager` under the `VersatiaEngine` interface from VersatiaAPI. That interface is the
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
5. `PluginEnhancer` keeps the elements whose phase applies to the current event, groups them by
   `processorClass`, has `ProcessorLoader` load every one of those classes before any processor
   runs (so a missing processor leaves the plugin untouched), hands each group to its processor,
   and finally runs the actions processors registered with `context.onPluginEnhanced { }`.
6. When a plugin is disabled, the runtime runs its `PLUGIN_STOP` elements and then drops the
   plugin's state, closing every entry that implements `AutoCloseable`, last created first.

Errors surface as `EnhancementException` thrown out of the plugin's `onEnable`, so Bukkit logs them
and disables that plugin only.

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
| `RELOAD` | not wired yet (Versatia has no reload command)         | `PLUGIN_RELOAD_ONLY`, `PLUGIN_RELOAD_OR_START` |
| `STOP`   | `VersatiaPlugin.onDisable`, `VersatiaEngine.disable`   | `PLUGIN_STOP`                                  |

`EnhancementContext.event` tells a processor which event it is serving. The bean scopes run at
`PLUGIN_START_ONLY`.

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
from the attributes, and each constructor `Parameter` becomes a `Dependency` of its type and the
`@Qualifier` on it.

`PluginBeanEnhancementProcessor` receives the descriptor's `PluginTarget`: the plugin's main
class, sent there by `@PluginBean` on `VersatiaPlugin`. `BeanDefinition.plugin` adopts the `Plugin` from the context under the fixed name `plugin`
(`BeanNames.PLUGIN`) with an instantiator that returns it, so the instance Bukkit created is
injectable under its own type and every supertype, is never exposed, and nothing is constructed for
it. The scope processors accept only `ClassTarget`s, and a descriptor carrying the main class more
than once is refused.

### Resolution rules

For every `Dependency` (a type `T` and an optional qualifier) of a bean or an `@AutoInvoke` function:

1. Take the beans of the same plugin whose assignable types include `T`; with a qualifier, only
   the one whose name matches.
2. If that set is empty, take the exposed beans of plugins enhanced earlier, filtered the same way.
   Zero candidates: `no bean provides T, required by X`.
3. One candidate is injected. Among several, the single `@Primary` one is; otherwise the error lists
   the candidates by name, class and owning plugin, with the hint to add `@Qualifier` or `@Primary`.

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
| `@ExposePrototype`   | a factory is registered; it creates a **new instance per consuming plugin**, reusing the constructor arguments resolved when the owner started, except that a parameter the owner's plugin bean satisfied receives the consuming plugin (checked with `isInstance` against the parameter type, since the compile-time step only sees `VersatiaPlugin`s) |

A prototype used by two beans (or functions) of the same plugin is created once for that plugin.
The owning plugin's own instance is never handed out.

### Lifetime

A plugin's bean session, and the beans it exposed, live exactly as long as the plugin: the session
is created when the plugin starts and closed when its state is dropped after its `PLUGIN_STOP`
elements ran, which withdraws its entries from `ExposedBeanRegistry`. Nothing is tied to server
load, so a plugin enabled later (for example through a plugin manager) sees the exposed beans of
the plugins running at that moment, and re-enabling a plugin replaces its beans. Beans are never
re-created on reload; reload-phase functions receive the retained instances.

## Auto-invoked functions (`lifecycle`)

`AutoInvokeEnhancementProcessor` handles `FunctionTarget`s. It injects through the plugin's
`BeanResolver` (`context.state[BeanResolver.KEY]`), the same contract any other processor would
use, and never touches the session class. It registers a completion action, so functions run
after every other processor of the event has finished, in particular after the beans of a starting
plugin exist. Functions run in descriptor order (class name, then function name). For each function
the processor:

1. picks the receiver by `FunctionOwner`: the plugin instance from the context, the `INSTANCE` of a
   Kotlin `object`, or (`FunctionOwner.INSTANCE`) the bean of that class from the plugin's bean
   session;
2. resolves the parameters through the bean session with the same rules as constructor
   dependencies (local beans first, then exposed beans; one prototype instance per plugin);
3. calls the `Invoker`. Any exception is wrapped in an `EnhancementException` naming the function
   and the event, with the original as cause.

Because the plugin's beans are kept for its whole lifetime (above), this works at every phase, not
only at start.

## Plugin resources (`resources`)

`ExtractResourcesEnhancementProcessor` receives the `PluginTarget` produced by `@ExtractResources`
on `VersatiaPlugin`. It locates the plugin's code through the main class
(`Class.forName(className, false, loader).protectionDomain.codeSource`), which is the plugin JAR
on a server and a class directory in tests, and copies every file under its `resources/` entry
into `plugin.dataFolder`, keeping the paths below that entry. A destination that already exists
is skipped; a path that would escape the data folder is refused. The work is done in `process`
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
  isolation of local beans, singleton sharing, prototype semantics, ambiguity, lifetime bound to the
  plugin state, reflective instantiation of a private class, the plugin instance as a bean, phases,
  missing processors.
- `AutoInvokeTest` covers the receivers (plugin, object, bean), parameter injection from local and
  exposed beans and of the plugin itself, reload and stop phases, reflection and error wrapping.
- `DescriptorLoaderTest` and `DescriptorCompatibilityTest` cover lookup by main class and the
  format-version rule.
- `DependencyInjectionIntegrationTest` is end to end: it compiles three sample plugins with the
  real VersatiaAPI KSP processor (`kotlin-compile-testing`, `dev.zacsweers.kctfork`), builds
  class loaders that delegate the way Bukkit's plugin loaders do, and runs the runtime over the
  generated descriptors at start, reload and stop. Sample beans and functions report to a
  `Recorder` on the test classpath. Nothing in these tests needs a running server.
