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
   VersatiaCore itself: the main class carries `@PluginBean` and `@ExtractResources`, the build
   runs the KSP processor, and the generated descriptor is what hooks the `/versatia` command and
   VersatiaCore's own `config.yml` in (shipped as `resources/config.yml`, its `debug` key read by a
   `@Value` property of the main class; see Debug mode below). That interface is the
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
   The class must be defined by the plugin's own class loader: a descriptor reached through a
   plugin it depends on is refused, and so is one that fails to link (`Plugin X: descriptor ...
   cannot be loaded (...); rebuild the plugin against this VersatiaAPI`).
5. `PluginEnhancer` keeps the elements whose phase applies to the current event and whose
   `@ConditionalOnPlugin` plugins were enabled when the plugin started (below), groups them by
   `processorClass`, has `ProcessorLoader` load every one of those classes before any processor
   runs (so a missing processor leaves the plugin untouched), hands each group to its processor,
   in the order of the group's first element in the descriptor, and finally runs the actions
   processors registered with `context.onPluginEnhanced { }`, in registration order; an action
   registered by another action runs after the ones already queued, before the event ends. At
   `STOP` this is best-effort: every processor, completion action and stop function runs even when
   another fails, and the first failure is thrown at the end with the others suppressed.
6. After the start event, `VersatiaRuntime` registers the plugin in `EnhancedPlugins`, its
   `PluginRegistry`, under its friendly name, the `@FriendlyName` attribute recorded on the
   `PluginTarget` or the plugin's name, in start order. `PluginRegistryBean` (`@ExposeSingleton`,
   delegating to that registry) is part of VersatiaCore's own descriptor, so every plugin can
   inject a `PluginRegistry`; see [Beans every plugin has](#beans-every-plugin-has) for the others.
   A name another running plugin uses is an `EnhancementException`, so the plugin stays disabled.
   A registered plugin is a running one. `reload(plugin)` runs the `RELOAD` event for a registered
   plugin and keeps the state; for any other it throws `Plugin X is not running`, from inside the
   plugin's own start `Plugin X is still starting; it can be reloaded once its start has
   completed`, and from inside its own reload `Plugin X is already reloading; a reload cannot start
   another one`. Enhancing a plugin that is running or starting throws `Plugin X is already running`.
   A successful reload cascades: `reload(requested)` then reloads every running plugin that holds a
   bean the reloaded one exposes (a prototype instance made for it or an injected singleton) of a
   class marked to follow its owner's reloads, as `ExposedBeanRegistry.consumersReloadedWith`
   records; the `@Value` processor marks every bean class with `@Value` properties, the session
   records which of the owner's beans each bean's constructor takes (`recordDependencies`), and a
   bean follows when its class is marked or it takes, transitively, an owner's bean that follows
   (a singleton wrapping a `PriceList` with values cascades). A plugin holding only beans without
   values, directly or through what they take, such as VersatiaCore's `PluginRegistry`, is not reloaded. It goes on transitively, each once per reload and always the
   next one in start order, so owners go before their consumers and a cycle ends at a plugin
   already reloaded. A plugin whose reload failed passes nothing on; a consumer's failure is an
   outcome (`ReloadOutcome`), never undoes the reload before it, and reads `<name> failed to reload
   after <owner>, whose beans it uses: <reason>. <owner> stays reloaded; fix <name> and reload it`.
   `VersatiaEngine.reload(plugin)`, behind `plugin.versatiaReload()` and `VersatiaEngine.reload(name)`,
   throws the requested plugin's failure and logs on that plugin's logger each consumer's failure
   and the summary `Reloaded <consumers>, which use beans of <owners>.` A reload asked for while
   one runs, from a reload function say, joins it; a plugin it already went through is refused
   with `Plugin X has already been reloaded by the reload under way; a plugin reloads at most once
   per reload`.
7. When a plugin is disabled, the runtime runs its `PLUGIN_STOP` elements (the
   `PluginScopeEnhancementProcessor` in `process`, so the plugin's coroutines are cancelled and
   awaited before the stop functions run in their completion action; see Tasks below), drops the
   plugin's state, closing every entry that implements `AutoCloseable`, last created first, and
   forgets the registration.

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
VersatiaCore. The class is loaded without being initialised and checked to implement
`EnhancementProcessor` before anything of it runs. One instance per class is created, with the
no-argument constructor or by taking the instance of a Kotlin `object`, and kept in a `ClassValue`,
so it lives as long as its class: a processor of a plugin that is gone does not keep that plugin's
class loader alive. A class that cannot be found or linked, is not an `EnhancementProcessor` or
cannot be created is reported with the plugin's name:

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

Every element carries the phase its enhancement declared (`Phase`). The runtime walks a
plugin's descriptor at `LifecycleEvent`s and dispatches only the elements whose phase applies:

| Event    | Triggered by                                           | Phases dispatched                              |
|----------|--------------------------------------------------------|------------------------------------------------|
| `START`  | `VersatiaPlugin.onEnable`, `VersatiaEngine.enhance`    | `PLUGIN_START_ONLY`, `PLUGIN_RELOAD_OR_START`  |
| `RELOAD` | `/versatia reload`, `VersatiaEngine.reload` (behind `plugin.versatiaReload()` and the static `VersatiaEngine.reload(name)`), and the cascade to the plugins holding beans with `@Value` properties of a reloaded one | `PLUGIN_RELOAD_ONLY`, `PLUGIN_RELOAD_OR_START` |
| `STOP`   | `VersatiaPlugin.onDisable`, `VersatiaEngine.disable`   | `PLUGIN_STOP`                                  |

`EnhancementContext.event` tells a processor which event it is serving. The bean scopes run at
`PLUGIN_START_ONLY`.

### Main thread

Every event touches Bukkit state (listeners, commands, beans other plugins hold), so
`VersatiaRuntime.enhance`, both `reload` overloads and `disable` first ask the plugin's server
whether they run on the main thread and otherwise throw before anything runs: `Plugin X: reload
must run on the server's main thread; schedule it with plugin.runTask { ... }` (`start` and `stop`
likewise). The API's `VersatiaEngine.dispatchConsoleCommand` checks the same through
`VersatiaEngine.checkPrimaryThread(action)`, an `IllegalStateException`. Pure helpers check nothing.

### Debug mode

With `debug: true` in VersatiaCore's `config.yml` (read at start and at every reload of
VersatiaCore, so `/versatia reload VersatiaCore` switches it), every step of each plugin's events
is logged by VersatiaCore at `INFO`, as `[debug] <plugin> <EVENT>: <step>`:

| Step | Written by | Line |
|---|---|---|
| descriptor | `PluginEnhancer` | `descriptor format 1.6, 12 elements, 7 for this event` |
| skipped element | `PluginEnhancer` | `skipped VaultBridge: @ConditionalOnPlugin(Vault) is not met` (at every event, unlike the `INFO` line at start) |
| dispatch | `PluginEnhancer` | `AutoInvokeEnhancementProcessor receives Reports.report(), Shop.open()` |
| beans | `PluginBeanSession` | `created 3 beans: "plugin" (...), ...` and `exposes "prices" (...) as exposed prototype` |
| values | `ValueEnhancementProcessor` | `set 3 values, config.yml: base, rate, tax` (keys only, never the values) |
| cascade | `VersatiaRuntime` | `goes on to Shop, holding beans that follow its reloads` or `no running plugin holds a bean that follows its reloads` |

The `DebugLog` lives in the runtime state; a processor writes through the internal
`EnhancementContext.debug { }`, whose message is not even built while the switch is off, so debug
mode off costs one boolean read per step and writes nothing.

### Conditions

`@ConditionalOnPlugin` is the second generic filter of `PluginEnhancer`, next to the phase: an
element whose target records the attribute is dispatched only when every plugin it names is
enabled (`PluginManager.isPluginEnabled`). The decision is taken once, when the plugin starts, and
kept in the plugin's state, so an element skipped then stays skipped at every reload and at stop,
like the beans it would have made, even when the required plugin is enabled later. A skipped bean
does not exist for resolution: a `@Primary` on it counts only while its condition holds, and
another provider of the type is injected instead. For a `FunctionTarget` or a `PropertyTarget` the `classAnnotations` recorded
from its declaring class count too, so a conditional bean or `object` takes its `@AutoInvoke`
functions with it. A condition written through an annotation class meta-annotated with
`@ConditionalOnPlugin` arrives the same way: the compile-time step records the inherited
occurrence next to it. The skipped classes are logged at `INFO` when the plugin starts, one line
per missing plugin, so an administrator sees why a command is missing: `Skipped VersatiaCommand:
CommandAPI is not installed`, or, for a plugin that is installed but was not enabled yet,
`Skipped VaultBridge: Vault is installed but was not enabled before MyPlugin; list it under
softdepend: in plugin.yml`. A function or property skipped by a condition of its own, not its
class's, is named as the member (`Skipped JoinBonus.vaultFound(): Vault is not installed`), since
its class is still loaded. The filter reads the recorded attribute and knows
no feature; the class behind a skipped element is never loaded, which is what lets it reference
an optional plugin's classes.

## Commands (`engine.command`)

VersatiaCore's commands are objects in `engine.command`, written with the CommandAPI Kotlin DSL,
carrying `@ConditionalOnPlugin("CommandAPI")`, with `register` at `@AutoInvoke(PLUGIN_START_ONLY)`
and `unregister` at `PLUGIN_STOP`; the CommandAPI plugin is a `softdepend:` and only its DSL
ships in the JAR (see build.md). `VersatiaCommand` provides `/versatia reload [plugin]` behind the
permission `versatia.core.command.reload`: it reads the runtime's registry, runs one cascading
`reload` over the selected plugins (so a full reload still reloads each plugin once), reports each
failure to the sender and the console and continues, whatever the plugin threw short of a VM
error. The reply ends with `Reloaded <names>; then <consumers>, which use beans of <owners>.`,
which is logged too when the reload cascaded. The sender sees
`<name> failed to reload: <reason>` (or the cascade wording above), where the reason joins the messages of the exception and its
causes, without the repeated `Plugin X:` prefix and without a message the previous one already
contains. A configuration mistake (a wrong value, broken YAML or a missing file) is logged as one
`WARNING` with that text and no stack trace, anything else at `SEVERE` with the trace. If
CommandAPI fails to register or unregister the command, typically because the installed release is
not compatible with the 12.x one VersatiaCore is built against, VersatiaCore logs one warning,
`/versatia could not be registered. The installed CommandAPI <version> may not be compatible ...`,
and keeps running without the command; an unknown name answers with the registered ones, which tab completion offers too.
VersatiaCore itself is in the registry, so a full reload includes it: it restores a deleted
`config.yml` and reads `debug` again.

`/versatia info [plugin]`, behind `versatia.core.command.info` (default op), is read-only.
Without an argument it lists the running Versatia plugins, one line each:
`shop (Shop 1.2.0), descriptor format 1.6`. With a friendly name it adds the plugin's beans
(`  "prices" com.example.PriceList, exposed prototype`), `Exposed beans used by others: "prices" by
Auction`, `Reloads cascade to: Auction` and `@Value files: config.yml`, each `none` when empty.
`PluginInfo` gathers the lines without CommandAPI types: the registry, the plugin's bean session
(`PluginBeanSession.beans`), the exposed-bean registry (`ownedBy`, `consumersReloadedWith`) and the
descriptor, read again through `DescriptorLoader.load(plugin)` for its format and `@Value` files.

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

An exposed instance is checked against the type as the consumer's class loader sees it before it is
injected: two plugins that load separate copies of a class (one bundling the API classes, or a
provider reloaded on its own by a plugin manager) cannot share instances, and the consumer reads
`Plugin X: "name" (class from Owner) cannot be injected as T: the two plugins load separate copies
of that class. ...` instead of a failed cast. A bean constructor that throws is reported as
`Plugin X: bean "name" (class) could not be created: <reason>`, with ` for Y` when the instance was
being made for consumer `Y` of a prototype of `X`.

An optional constructor parameter (one with a default value) with zero candidates resolves to
`DefaultArgument`, which `Instantiator.WithDefaults` leaves out of its `callBy`, so the default
applies; a prototype made for a consumer reuses the owner's default. A parameter with `suppliedBy`
is not resolved: the session passes what that processor handed to `supply` (an index of the
constructor) before the beans were created. A missing argument fails the creation with
`Plugin X: <class> expects <processor> to supply parameter <n>, which it has not`, a `supply` after
creation fails with `Plugin X: the argument of parameter <n> of <class> was supplied after the
beans had been created`, and `resolve` refuses a supplied parameter, which only bean constructors
can take. `supply` takes a function, called for every instance the constructor makes, the
owner's and each prototype instance made for a consumer, so each gets the argument current at its
creation. `onInstanceCreated(class, action)` runs an action on every instance of a bean class
right after its constructor (at once on those that exist; false when the class is no bean of the
plugin), and `instancesOf(class)` lists the live instances without creating any: the plugin's own
and those its prototype made for plugins still running.

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
| a supplied parameter                  | what the supplying processor's function returns now                 |
| anything else                         | the owner's argument                                                |

The factory receives the consumer's resolution as a function, so a prototype built from other
prototypes (a `Messages` taking a `Text`, say) forms a chain made entirely for the consumer.

### Beans every plugin has

Besides its own main class (the bean `plugin`), every plugin can inject the beans VersatiaCore's
own descriptor exposes, which take nothing but the plugin they are made for. They are ordinary
exposed beans: nothing in KSP or the pipeline knows their types, and the resolution rules above
apply to them unchanged. A class exposing a value of the plugin is named `Plugin<Type>Bean` after
the type it is injected as.

| Class (root package unless noted) | Scope            | Injected as                                  | Delegates to            |
|-----------------------------------|------------------|----------------------------------------------|-------------------------|
| `PluginRegistryBean`              | exposed singleton | `PluginRegistry`                            | the runtime's registry  |
| `task.PluginScopeBean`            | exposed prototype | `PluginScope` (and `CoroutineScope`)        | a scope of its own, see [`PluginScopeBean`](#pluginscopebean) |
| `PluginLoggerBean`                | exposed prototype | `java.util.logging.Logger`                  | `plugin.logger`         |
| `PluginComponentLoggerBean`       | exposed prototype | `ComponentLogger` (and `org.slf4j.Logger`)  | `plugin.componentLogger` |

Each prototype takes the consuming `Plugin`, so a module prototype that takes a `Logger` gets the
logger of the plugin it is made for (row "a prototype" of the table above), and the consumer's
own rules decide: a consumer with a local `Logger` bean gives that one. A local bean of the same
type wins over these, as over any exposed bean; two local ones need `@Primary` or `@Qualifier`.
The injected instance is not `plugin.logger` itself but a delegate: `PluginLoggerBean` extends
`Logger` under the plugin logger's name and forwards records, level, handlers, filter and
`useParentHandlers`, so it logs exactly as the plugin does; `PluginComponentLoggerBean` delegates
every interface method. `Server`, the scheduler, the data folder and the plugin's metadata are not
beans: they go through `VersatiaEngine` or the plugin (`plugin.pluginMeta`).

### Lifetime

A plugin's bean session, and the beans it exposed, live exactly as long as the plugin: the session
is created when the plugin starts and closed when its state is dropped after its `PLUGIN_STOP`
elements ran, which withdraws its entries from `ExposedBeanRegistry`, makes the exposed beans it
obtained forget it (so the owner neither lists its prototype instances nor reloads it any more), and closes every
`AutoCloseable` instance the session adopted: its own beans and the prototype instances made for
it, never a singleton exposed by another plugin. They close in reverse adoption order, and since a
bean is adopted after its dependencies, dependents close first; every instance is closed even when
one fails, and the failures surface as one `EnhancementException` (`Plugin X: <class> failed to
close`) with the others suppressed. `VersatiaRuntime.disable` still unregisters the plugin when
closing fails. Nothing is tied to server
load, so a plugin enabled later (for example through a plugin manager) sees the exposed beans of
the plugins running at that moment, and re-enabling a plugin replaces its beans. Beans are never
re-created on reload; `Reloadable` ones are reloaded and reload-phase functions receive the
retained instances. When VersatiaCore is disabled, `PluginStates.close` drops the plugins' stores,
last started first, and then the runtime's, closing every one even when another fails.

## Auto-invoked functions (`lifecycle`)

`AutoInvokeEnhancementProcessor` handles `FunctionTarget`s. It injects through the plugin's
`BeanResolver` (`context.state[BeanResolver.KEY]`), the same contract any other processor would
use, and never touches the session class. It registers a completion action, so functions run
after every other processor of the event has finished, in particular after the beans of a starting
plugin exist. Functions run in descriptor order (class name, then function name). For each function
the processor:

1. picks the receiver by `MemberOwner` (`context.receiverOf`, a VersatiaAPI helper shared with the
   value processor and custom processors): the plugin instance from the context, the `INSTANCE` of a Kotlin `object`, or
   (`MemberOwner.INSTANCE`) the bean of that class from the plugin's bean session;
2. resolves the parameters through the bean session (`context.inject`) with the same rules as constructor
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
by then every scope has declared its beans and the resources are extracted. The properties of the
main class and of objects are set at once. Those of beans are not set on one instance but on every
instance of the class, the plugin's own and each one its `@ExposePrototype` made for another
plugin, whose values come from the owner's files: at start the processor keeps the values read
in the plugin state and hands them to the bean session, a constructor property through `supply`
(a function returning the kept value, wrapped in a new filled `PreviousValueTracking` for a
tracked one, so no two instances share a cell) and the body properties through
`onInstanceCreated` (set right after the constructor, before the instance is injected anywhere).
No bean is created in `process` itself; the session creates them in its completion action, and
each instance, the owner's or one made for a consumer that starts much later, starts with the
values kept at that moment. A bean constructor thus sees the values of the main class, of
objects, its own constructor values and those of the beans it takes, and the placeholders of its
own body. At a reload the kept values are replaced and set on every instance `instancesOf` lists,
then the runtime's cascade reloads the consumers holding them (the processor marks these
classes with `ExposedBeanRegistry.followOwnerReloads` at start), which run their `PLUGIN_RELOAD_OR_START`
functions with the new values. A `SUBCLASS_INSTANCE` property, of a plain `Listener` or
`BackgroundTask`, goes the same way through `SubclassInstances` instead of the bean session: at
start a constructor property is supplied and the body properties registered with
`onInstanceCreated` (`X was created before its @Value constructor property X.p was read; a
processor creating it must wait for onPluginEnhanced` when a processor made the instance in
`process` already); at a reload the values are set on the instance when it exists, before the
completion action restarts the tasks. Such an instance serves its own plugin only, so no cascade
follows. The processor loads every file
the elements name once (`loadYamlOrThrow`, relative to the data folder; a path that leaves the
data folder once normalized is refused with `Plugin X: the @Value file "<file>" lies outside the
data folder; ...`, which the compile-time step already rules out for paths written in the source),
reads every value, resolves every receiver and every bean instance, and only then assigns them, so
a broken file or a missing receiver changes nothing. `ValueReader` turns the recorded `TypeReference` into a read: the scalar and
Bukkit types map to the `OrThrow` getters of `util.config` (`getSecretOrThrow` resolves the
`${env:NAME}` and `${file:PATH}` of a `Secret` there, at every read), an enum is loaded through the plugin's
class loader (trying the binary names a nested class may have) and matched by constant name
ignoring case, `List` and `Set` use the strict list getters (`getStringListOrThrow` and the like,
so a scalar where a list belongs or an element of the wrong type is an error), `Map` reads a
section child by child. With
a mapper recorded in the annotation (any class but `NoMapper`), the reader checks that the class
implements `ValueMapper`, creates it once per event, the `object` instance or a new one from the
no-argument constructor, and calls `map(section, key)`
instead; a missing key is handled before, by the property's nullability. A `PreviousValueTracking<T>`
is read as its `T`, the setter storing into the cell; at a reload a constructor property is set
like a body one, which fills the cell each instance's constructor received. Whatever the read
throws, a mapper that cannot be loaded or created included, becomes `Plugin X:
<class>.<property> could not be read from <file>: <reason>` (the exception's class when it has no
message), and a failing setter `Plugin X: <class>.<property> could not be set: <reason>`; the
receiver is picked like for a function (a bean with no instance is `Plugin X: no bean <class> exists
to set <class>.<property> on`), and the `Setter` assigns, directly or through `util.reflect`.
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
left alone. The content is written to a temporary file next to the destination and then moved
into place without replacing anything, so a failed copy never leaves half a file and an entry
created meanwhile is kept. A path
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
plugin has no beans but the constructor needs some). The instance comes from
`engine.descriptor.subclassInstanceOf`, which keeps a created instance per plugin and class in
`SubclassInstances` in the plugin state, so a plain class that is a `Listener` and a
`BackgroundTask` is created once and shared by both processors. A constructor parameter whose
`suppliedBy` names a processor takes the argument that processor supplied (`X expects
ValueEnhancementProcessor to supply parameter N, which it has not` otherwise), and the actions
registered with `onInstanceCreated` run right after the constructor, before the instance is
returned, so before it is registered or first runs. Each instance is registered through
`plugin.server.pluginManager.registerEvents`; Bukkit unregisters them on disable.

## Tasks (`task`)

The coroutines of every plugin run on one loop on the server thread. VersatiaAPI's `task` package
defines `BackgroundTask`, `PluginScope`, `Condition` and the helpers; this package runs them.

### `TickLoop` and `ServerTicker`

`PluginScopeBean.perPlayer` makes a `PluginPlayerValues`: a `ConcurrentHashMap` by UUID, a
`PlayerQuitEvent` listener at MONITOR (after the plugin's own handlers) that removes the value at
once or, with `keepAfterQuit` (rounded up to ticks), in a coroutine of the scope waiting
`delayTicks`, and a `PlayerJoinEvent` listener at LOWEST that cancels that coroutine. When the
scope's job completes at the stop, both listeners are unregistered and the map is cleared.
`PlayerValuesTest` covers the quit, the delayed removal cancelled by a join, and the stop.

`ServerTicker` is a `@LocalBean` of VersatiaCore. Its `@AutoInvoke(PLUGIN_START_ONLY)` function
starts one Bukkit timer, `runTaskTimer(periodTicks = 1)`, whose every run is `TickLoop.heartbeat()`;
`close()` cancels the timer and stops the loop. The loop is told the server thread by
`server.isPrimaryThread`.

A `Lane` is one plugin's part of the loop: the queue of dispatched blocks, the queue of posted
condition rechecks, the event types whose `immediately` triggers already warned, and the
`stopping` and `closed` flags. Lanes are registered in the order the scopes are made, which is the
plugins' start order. `heartbeat()` increments `tick`, then for every lane runs the blocks queued
before its turn (`repeat(queue.size)`, so a block re-dispatching itself advances once per tick) and
then its rechecks, prunes a closed lane whose job completed and whose queue is empty, and finally
fires the due timers: a priority queue ordered by due tick and registration sequence, fed from a
concurrent queue of new timers, compacted when more than half of it, plus a slack of 64, is
cancelled. A throwing block or recheck is caught and logged on its plugin's logger as `A coroutine
threw while running on the server thread`; the heartbeat goes on.

- `submit` (any thread) queues a block and never runs it in place; after `stop` it hands the block
  to `Dispatchers.IO` with its job cancelled (`VersatiaCore stopped`). A block arriving for a closed,
  pruned lane brings the lane back.
- `post` (any thread) queues a recheck, dropped once the loop stopped or the lane is stopping.
- `schedule` (any thread) registers a timer due `tick + max(ticks, 1)`, refused with a
  `CancellationException` once the loop stopped or the lane is stopping (`Plugin X is being
  disabled; no tick passes for it any more`).
- `abandon` cancels the lane's pending timers and removes them from the queue at once, since a
  timer holds its coroutine and through it the plugin's classes, and drops its rechecks; `drain` runs the lane's
  blocks until a condition holds or `closeTimeout` (5 s) passed, parking 1 ms when the queue is
  empty.
- `stop` (VersatiaCore's own disable) cancels every lane, abandons them, runs what comes back until
  every lane's job completed or the timeout passed (skipped when called from inside a coroutine),
  marks the loop stopped, hands what is still queued to `Dispatchers.IO` and returns the lanes whose
  job did not complete; `ServerTicker` then logs `Coroutines of A, B were still running when
  VersatiaCore stopped; they are cancelled and finish on a kotlinx thread`.

### Dispatcher, clock and marker

`PluginDispatcher.isDispatchNeeded` is `!isServerThread()`: on the server thread a coroutine runs
in place (kotlinx's unconfined event loop, so a launch from inside a coroutine already running in
place is queued until it suspends), anywhere else `dispatch` submits to the lane. It implements no
`Delay`, so `delay` waits on kotlinx's default executor and dispatches back at the next heartbeat;
`yield` on the server thread dispatches and therefore waits for the next heartbeat.
`PluginClock` is the plugin's `ServerClock`: `currentTick` is the loop's tick, `timeSource` is
`TimeSource.Monotonic`, `delayTicks` schedules a timer through `suspendCancellableCoroutine`,
`condition` makes a `PluginCondition` closed when its owner job completes, and `reportFailedRun`
logs `<label>: a repeated run failed; it runs again on schedule, and the same failure is not logged
again until a run succeeds` (`label()` is `Coroutine <CoroutineName>` or `A coroutine`).
`CoroutineMarker` is a `ThreadContextElement` counting how deep the current thread is inside a
coroutine of a Versatia plugin; `PluginScopeBean.stop` and `TickLoop.stop` read it to avoid
draining from inside a coroutine.

### `PluginCondition`

A `Condition` delegating to a `MutableStateFlow` and a `Listener` of its own, registered with
`pluginManager.registerEvent` once per trigger (the trigger's priority and `ignoreCancelled`, an
`EventExecutor` checking `isInstance` and the filter, owned by the plugin). `create` requires the
server thread (`A condition is created on the server thread, where its check runs; create it in a
bean, a start function or a coroutine of the plugin`) and evaluates the check for the initial
value. A trigger without `immediately` calls `recheckAtNextTick`: an `AtomicBoolean` keeps one
recheck posted however many events fire. With `immediately` it evaluates in the handler on the
server thread, and elsewhere warns once per lane and event type and posts. `recheck()` assigns
`check()` in place on the server thread (an exception reaches the caller) and posts otherwise.
`evaluate()`, used for triggers and posts, keeps the last value on an exception and logs `A
condition check failed; the condition keeps its last value, <value>`. `create` links the condition
to its owner job with `invokeOnCompletion`; `close()`, from the author or that link, runs once
(an `AtomicBoolean`), unregisters the listener and disposes the link, so a condition closed early
leaves no handler on the plugin's job. A closed condition ignores `recheck()` and drops a posted
recheck.

### `PluginScopeBean`

VersatiaCore's `@ExposePrototype` behind `PluginScope`, taking the consuming `Plugin` and
VersatiaCore's `ServerTicker`, so every consumer, and every module prototype made for one, gets an
instance and a lane of its own. Its context is a `SupervisorJob`, the `PluginDispatcher` (also
`serverThread`), the `PluginClock`, `CoroutineMarker` and a `CoroutineExceptionHandler` logging
`<label> failed` (plus ` after the plugin was disabled` once the lane is closed) at `SEVERE`.
`condition(...)` binds a condition to the scope's job. A completion of the job while the lane is
not stopping logs `PluginScope was cancelled by plugin code, so nothing launched from it runs until
the plugin restarts; cancel the jobs you launch, not the scope`.

`stop()` is idempotent: it marks the lane stopping, cancels the job (`Plugin X is being disabled`)
and, on the server thread with the loop running, abandons the lane and drains it until the job
completed. From inside a running coroutine of the plugin it does not drain and, if the job is still
running, logs `Plugin X is being disabled from inside a running coroutine, ...`; a drain that times
out logs `N coroutines were still running 5s after the plugin was disabled (names); they are
cancelled and finish when they next resume`, naming the children with a `CoroutineName`. The lane
is then closed. `close()` is `stop()`, so the bean session stops a scope the processor did not.
`PluginScopeBean.of(context)` resolves the scope once through the plugin's `BeanResolver` (requester
`@RunBackgroundTasks`), keeps it under `PluginScopeBean.KEY` and refuses a `PluginScope` that is not
this class (`the PluginScope bean is C, not VersatiaCore's; background tasks and the stop order need
VersatiaCore's`); it returns null for a plugin without beans.

### The processors and `BackgroundTasks`

`PluginScopeEnhancementProcessor` accepts exactly one `PluginTarget` per call (`... expects the
plugin's main class exactly once, got [...]`). At `START` it registers a completion action calling
`PluginScopeBean.of`, so every Versatia plugin has its scope and lane from its start. At `STOP` it
calls `stop()` in `process`: the runtime runs completion actions after every processor, so the
plugin's coroutines are cancelled and drained before the `@AutoInvoke` stop functions, and the
beans are closed after both. At `RELOAD` it has no elements.

`RunBackgroundTasksEnhancementProcessor` receives one element per task. At `START`, in a
completion action, so after the bean session created the beans, it resolves the scope (`X is a
BackgroundTask, but the plugin has no bean container; extend VersatiaPlugin or put @PluginBean on
the main class` without one), obtains each task through `subclassInstanceOf` (`X is not a
BackgroundTask` if the instance is not one) and starts a `BackgroundTasks` kept under its key. At
`RELOAD` its completion action, registered after the plugin-bean processor's (the
`PluginBeanEnhancementProcessor` group comes first in the descriptor) and before the auto-invoke
processor's, calls `restart()`: the values are set in `process` and the `Reloadable` beans reloaded
before it, the reload functions run after it.

`BackgroundTasks` keeps the live runs of every task. A run is `scope.launch(CoroutineName(<class>),
LAZY)`: it joins the previous runs of its task (logging `Background task X is still finishing its
previous run 5s after the reload cancelled it; the new run starts when it has` when that takes
longer than the timeout), waits one tick and calls `run()`. A return is logged at `FINE`; an
exception at `SEVERE` as `Background task X failed; it starts again at the next reload`; a
`CancellationException` while the run is still active, such as a `withTimeout` escaping `run()`, as
a warning `Background task X ended with <exception> although nobody cancelled it; it starts again at
the next reload`, and rethrown. `restart()` cancels every live run with `Plugin X reloaded`; their
`finally` blocks run there, on the server thread, before the reload functions, and the new runs
start at a later tick. The stop needs nothing from it: the runs are children of the scope.

Under `@DisableVersatiaFeatures(runBackgroundTasks = true)` neither processor has elements: no
task runs, and an injected scope is stopped only when the bean session closes it, after the stop
functions.

## Tests

- `ProcessorLoaderTest` covers loading by name: one instance per class, Kotlin objects, and the
  errors for a missing class, a class that is no processor and one without a usable constructor.
- `PluginEnhancerTest` covers `@ConditionalOnPlugin` on a bean, on its function and on an
  object's function, the start decision kept at reload, a conditional `@Primary` falling back to
  the other provider, the skip log per missing plugin (not installed or not enabled yet), the
  processor order and completion actions added by completion actions, best-effort stop functions
  and a start or reload cut short by a disable.
- `VersatiaRuntimeTest` drives the runtime over a hand-written descriptor in the `sample` test
  package: the registry and its start order, `@FriendlyName`, the duplicate-name error, the reload
  event and the stop event, a failed or self-cancelled start followed by a disable, a
  self-cancelled reload, the reload guards, the main-thread guard of start, reload and stop, the cascade through a cycle, a consumer of a bean that
  does not follow reloads left alone, and a consumer failing after its owner reloaded.
  `PluginStatesTest` covers the best-effort close of every store.
- `BeanLoadingTest` drives `PluginEnhancer` with hand-written descriptors: creation order,
  isolation of local beans, singleton sharing, prototype semantics including the consumer's plugin
  and prototype chains, `Reloadable` after creation, at reload and on late adoption, ambiguity, lifetime bound to the
  plugin state with `AutoCloseable` beans closed dependents first, reflective instantiation of a private class, the plugin instance as a bean, phases,
  missing processors, and a throwing constructor named with its plugin, bean and consumer.
- `AutoInvokeTest` covers the receivers (plugin, object, bean), parameter injection from local and
  exposed beans and of the plugin itself, reload and stop phases, reflection and error wrapping.
- `ValueTest` covers the receivers for properties, values of the plugin and objects set before the
  beans exist, values set before reloadable beans and functions, reload and a broken file leaving values untouched, nullable and missing values, named
  and missing files, enums, lists, sets and maps, mappers (objects, nested objects, classes, their
  failures), tracked cells with their previous value, and reflective setters for private and
  inline-class properties, a secret read through a file reference whose unset variable at a
  reload names the key but not the value, a file outside the data folder and an unusable mapper;
  `ValueReaderTest` checks that the reader's type maps equal `ValueTypes` and reads every scalar
  type alone and in a list, a nested enum and malformed values; `ValueIntegrationTest` compiles a plugin with `@Value` properties and runs it at start and reload;
  `SubclassValueIntegrationTest` compiles a plain `BackgroundTask` with body and constructor
  values and a plain `Listener` with a value, and checks them before the first run and the
  registration, after a reload and after a failed reload.
- `BeanLoadingTest` also covers optional constructor parameters (local, exposed and default) and
  supplied ones with their two errors; `ConstructorParameterIntegrationTest` compiles plugins with a
  `@Value` constructor `var` and tracking parameter (start, reload, failed reload), a constructor
  reading an object's value at start, an optional
  parameter, and a provider conditional through a meta-annotation that falls back while its plugin
  is missing. `BeanLoadingTest` also covers creation actions and the instances a prototype made
  for other plugins, forgotten with their consumer. `CrossPluginValueIntegrationTest` compiles a
  provider exposing a prototype with `@Value` properties and two consumers: their instances follow
  the provider's reloads, a consumer started after a reload gets the current constructor values,
  every instance tracks in its own cell, the cascade runs the consumers' reload functions in start
  order, reaches a consumer holding only a singleton that wraps the prototype, skips a consumer
  holding only a bean without values, a broken provider file changes
  and cascades nothing, and a disabled consumer's instance is forgotten. Over the same plugins it
  checks that debug mode writes nothing while off, logs each step (never a value) while on, and
  that `PluginInfo` reports the format, beans, consumers, cascade and `@Value` files.
- `TickLoopTest` drives the `TickLoop` by hand on the test thread: tick timers, `delayTicks(0)`,
  cancelled and cross-thread timers, a disabled plugin's timer released at once, `yield`, the order blocks, rechecks, timers, a throwing block
  or recheck, the dispatcher, `stop` with the hand-off to `Dispatchers.IO`, and the `ServerTicker`
  with its warning. `PluginScopeBeanTest` covers launching on and off the server thread, the return
  from `Dispatchers.IO`, failures named by `CoroutineName`, the stop with its `finally` blocks,
  refused tick waits, the drain and its warning, a launch after the stop, a cancelled scope and one
  scope per plugin, a module prototype included. `ConditionTest` covers the check at creation,
  `recheck` on and off the server thread, one recheck per tick, filters and `ignoreCancelled`,
  `immediately` on and off the server thread, a failing check, the listeners' lifetime and
  `runOnceWhile` against a countdown. `RunBackgroundTasksTest` uses `FakeCore` (VersatiaCore's
  generated descriptor over hand-written plugin descriptors, its timer recorded by `FakeScheduler`)
  for object, bean and plain-class tasks, the shared listener instance, the reload order, a failing
  task, the stop order, a plugin without beans and the scope processor's argument check;
  `TaskIntegrationTest` compiles a plugin with the real KSP processor and runs it through start,
  ticks, a reload and the stop.
- `ExtractResourcesTest` and `RegisterListenersTest` cover the two per-plugin features;
  `InstantiatorTest` covers the reflective instantiators of the descriptor.
- `DescriptorLoaderTest` and `DescriptorCompatibilityTest` cover lookup by main class and the
  format-version rule; `CoreDescriptorTest` loads VersatiaCore's own generated descriptor, loads
  every processor it names, checks that it exposes the beans every plugin has and that the command
  stays conditional on CommandAPI; `PluginLoggerBeanTest` checks that the injected `Logger` logs,
  filters by level and lists handlers exactly as `plugin.logger`.
- `DependencyInjectionIntegrationTest` is end to end: it compiles sample plugins with the
  real VersatiaAPI KSP processor (`kotlin-compile-testing`, `dev.zacsweers.kctfork`), builds
  class loaders that delegate the way Bukkit's plugin loaders do, and runs the runtime over the
  generated descriptors at start, reload and stop. Sample beans and functions report to a
  `Recorder` on the test classpath. It also checks that a bean logs through its plugin's `Logger`
  and that a prototype made for another plugin receives that plugin's `Logger`, `ComponentLogger`
  and `Plugin`, or its own local `Logger` bean when it has one. Nothing in these tests needs a running server; the command
  needs one and is not unit-tested.
