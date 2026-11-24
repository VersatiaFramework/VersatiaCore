# Messages and text

`Messages` (package `messages`) and `Text` (package `text`) are part of VersatiaAPI; their
implementation lives here, in the packages of the same names. Plugin-author documentation:
https://versatia.dev/docs/messages.

## Two prototypes per plugin, one sheet of styles

VersatiaCore exposes two `@ExposePrototype`s, so every plugin that injects them receives instances
of its own:

- `TextImpl`, the parser behind `Text`. The presentations and tags a plugin registers never leak
  into another plugin, and they vanish when that plugin is disabled, because the instance belongs
  to the consumer's bean session. The built-in presentations (`BuiltInPresentations`) are one
  shared `Presenters` used as the fallback of every instance. It takes the consuming `Plugin`,
  VersatiaCore's `ServerStyles` and VersatiaCore's `ServerPresentations`.
- `MessagesImpl`, the bank behind `Messages`, taking the consuming `Plugin` and its `Text`. The
  runtime hands every consumer an instance made with its own plugin, whose `messages/` directory is
  read, and the `Text` instance made for that consumer, the same one its own beans inject, so the
  presentations the consumer registered apply to its messages.

Nothing about them is kept in VersatiaCore itself: every instance belongs to the consumer's bean
session, and a plugin that never injects them never has one. What the plugins share are two
`@LocalBean`s of VersatiaCore that every `TextImpl` receives as the owner's arguments:
`ServerStyles`, the styles of `plugins/VersatiaCore/styles.yml` (see [Styles](#styles)), and
`ServerPresentations`, what the `@ServerPresentation` beans of the running plugins present (see
[Server presentations](#server-presentations)).

## Two packages

The packages mirror the API's. `dev.versatia.text` holds the parser (`TextImpl`,
`ArgumentResolver`, `Substitution`, `Presenters`, `BuiltInPresentations`, `LegacyColorCodes`), the
presentations of the server (`ServerPresentations`, `ServerPresentationEnhancementProcessor`) and
the styles (`ServerStyles`, `StyleFile`, `StyleSheet`, `StyleSnippet`, `TagNames`), and never
refers to `messages`; `dev.versatia.messages` holds the bank (`MessagesImpl`, `MessageLoader`,
`MessageImpl`) and reaches the parser through the `Text` interface and its Core-internal extension
`CheckingText`, which adds `check(input)`: reading a text for its tags alone. The two halves could
be split again without touching a consumer's imports, once `check` is public. Both read their
files through `engine.yaml.TextYaml`, the YAML reader that keeps every scalar a text.

## Resolution

Braces are data, angle brackets are MiniMessage. `TextImpl.parse` builds one `ArgumentResolver`
for the call, holding the arguments as a map, and lets it substitute the input first: every
`{name.property...}` is rendered into a component, walking one property per segment through the
value's `Presentation`. Each distinct brace is rendered once, so a property is evaluated once per
call, and the errors of a wrong brace surface directly from `parse`. Whatever a brace reaches is
inserted through its presentation's component when the instance or the built-ins have one, which
includes a registration for `String` or `Number`; otherwise a `ComponentLike` as itself and
anything else as its `toString()`. Strings are never parsed. A brace that is not an argument
reference (`{}`, `{ x }`) is left to MiniMessage as plain text; a reference to an argument the
call did not pass is an `UnknownArgumentException`, and a property of a value that is null a
`NullValueException`. Presentations run plugin and Bukkit code, so an exception thrown while one is
made, a property read or a component built is wrapped in a `PresentationException` naming the
brace path; a `TextException` of a nested brace passes as it is.

Where the brace sits decides what replaces it. `Substitution` scans the input the way
MiniMessage's first pass does: `\<` and `\\` escape in the text, and a tag runs from `<` to the next
`>` outside quotes unless an unquoted `<` comes first. A brace in the text becomes a private tag
(`<versatia-argument-N>`) that the resolver answers with the component when MiniMessage parses the
rest. MiniMessage does not resolve tags inside a tag's arguments, it reads them as plain strings,
so a brace inside an argument of a known tag (a standard tag or one registered with `Text.tag`)
becomes the value's plain text instead: `<click:suggest_command:'/msg {player.name} '>`,
`<insert:{player.name}>`, `<color:{c}>`. Inside quotes MiniMessage unescapes only `\` before the
active quote and before itself; `<`, `>`, `:` and the other quote are literal there, and escaping
them would leave a visible backslash. The value is therefore escaped for the argument's own quote,
and an unquoted argument holding a brace is wrapped in single quotes first, so `:` and `>` cannot
split or end it. A hostile value such as `x'><click:run_command:'/op x'>` stays inside the
argument. The one argument MiniMessage parses again, the text of `<hover:show_text:...>` (and the
name of `show_entity`), keeps the component tag, quoted the same way, so hover text shows the
value's formatting and still never parses it. Braces in an escaped tag, which MiniMessage prints
as text, take the component path like any text. A tag with a brace in its arguments that nobody
knows is noted as unknown right there: the private tag would split it, and MiniMessage would never
ask about its name.

A `{` after an odd run of backslashes is escaped, consistent with MiniMessage's `\<`: `\{nick}`
shows `{nick}`. In the text the scanner treats `\{` as a third escape beside `\<` and `\\`, writing
the brace alone, so `\\{nick}` stays MiniMessage's escaped backslash followed by the argument. Inside
a tag argument the same parity decides (the backslash runs are counted in the argument's body): the
escaping backslash is dropped and the brace kept as text, any other backslashes stay as the author
wrote them. `TextImpl.argumentNames` and the constant check use `UNESCAPED_ARGUMENT`, a brace with no
odd run of backslashes before it, so an escaped brace is neither an argument nor a reason to
re-parse.

The resolver also serves the tags registered with `Text.tag`, which are real MiniMessage tags
(`<papi:player:kills>`); it implements `Arguments` so they can read the call's arguments.
MiniMessage resolves tags in a pre-processing pass and again while parsing, and swallows any
exception a resolver throws, leaving the tag as text; the resolver therefore records the first
failure of a tag, which `parse` throws once MiniMessage returns.

A tag that does not exist is an error, never text. MiniMessage asks the resolver `has(name)` for
every tag it reads, opening or closing, in the text and in hover text, and prints the tag when the
answer is no; the resolver notes each name that neither it nor the standard tags answer, and
`parse` throws them as one `UnknownTagException` listing the styles. Only a name with a letter
counts (`TagNames.isMeantAsTag`): `<3>`, `<->` and `<?>` were never meant as tags and stay text,
as does anything MiniMessage does not read as a tag at all (`<3`, `<two words>`). A real tag with
an argument MiniMessage cannot read (`<color:nope>`) is still printed: the standard resolvers fail
inside MiniMessage, where a closing tag is resolved without arguments too, so the two cannot be
told apart from here. MiniMessage 5 has no `<pre>`, which is therefore an unknown tag like any
other.

## Styles

A style is a name the server maps to MiniMessage, used as a tag: `<positive>Quest {quest} done.`.
`ServerStyles` reads `styles.yml` in VersatiaCore's data folder into a `StyleSheet` when
VersatiaCore starts and at each of its reloads (it is a `Reloadable` local bean; the file ships
with comments only, so resource extraction puts it there). `StyleFile` is the format: a tree in
which a scalar is a style, a section a scope whose `style` key is its own style and whose other
keys are names that apply only inside it, and a dotted key (`positive.accent`) the same as
nesting. A name must be a tag name of its own (`[A-Za-z0-9_-]+`, no standard MiniMessage tag, not
`style`, not `versatia-...`), a path is defined once, and an empty section is an error. MiniMessage
hands every tag name over in lower case, so a sheet keys its styles by `TagNames.key` and each
`StyleNode` keeps the spelling the file writes, for what is shown and appended: `serverTag` and
`servertag` are one name. Every
style is compiled once into a `StyleSnippet`: the text followed by a private content tag is
parsed, with the unknown tags noted as above and legacy colour codes rejected; when the result is
the content alone with a style on it, the snippet is that `Style` and applying it costs no
parsing, otherwise (a gradient, text of its own) it is parsed again around each content. A style
holds MiniMessage only: another style inside it is an unknown tag.

`StyleSheet.resolve(name, scopes)` looks a name up in the styles around it, nearest first, then
at the top level. MiniMessage resolves a tag without telling it where it stands, so a style tag
is both `Inserting` and `Modifying` (`ArgumentResolver.StyleTag`): MiniMessage calls
`Modifying.visit` with the tag's own node before it asks `Inserting.value()` for the component,
and the node's `parent()` chain, looked up in an identity map of the nodes that opened a style
during this parse, gives the scopes. `value()` then opens with the resolved formatting and the
children follow, exactly as the tags of the snippet would produce, so the result equals the text
written with those tags; `apply` gives every component back unchanged. Nothing is marked and
nothing is rewritten afterwards, so no marker can reach a player. A snippet that needs its content
is applied in `apply` at depth 0, which answers for the whole subtree and returns nothing for the
rest (the empty components this leaves are dropped by MiniMessage's own compaction). Styles are
resolved innermost first, so a style inside a gradient keeps its colour. A name that is a style
somewhere but not where it stands fails as `<accent> is a style only inside <negative>, <positive>`.
Hover text is parsed by MiniMessage as a text of its own while the outer tree is still being
built, so its styles start at the top level. A tag registered with `Text.tag` wins over a style of
its name, and a style tag takes no arguments.

The text of a click takes the style `clickable`. `TextImpl.READER` knows MiniMessage's tags but
`<click>` (`TagNames.STANDARD_BUT_CLICK`), so `ArgumentResolver` answers it: MiniMessage's own tag
reads the arguments, and where the sheet defines `clickable` at the top level the resolver returns
a `ClickTag`, a `StyleTag` of `clickable` whose opening component also carries the click event.
The click is thus a scope like any style: a style inside it is nearer and wins, a tone's own
`clickable` applies inside the tone, and a value that is all the click holds takes no style of its
own. A click a style's snippet holds is MiniMessage's own, read with the sheet, and a click a value
brings stays the value's.

A value takes a style by itself. `ArgumentResolver` renders a brace into a `Rendered`, the
components with the style names each may take (a collection keeps them per element), once per
call: the style of its `Presented.look()`, a built-in one after its type
(`player`, also an `OfflinePlayer`, `entity`, `item`, `location`, `world`), a plugin's own with
the `style` it passes to `Text.present`, the `style` a `Presentable` answers or the `style` of a
`@ServerPresentation`; `Presenters` checks a plugin's with `StyleFile.objection`, once per name,
and the processor a server presentation's at the start. Then `accent`, which every value takes:
a text, a number, a `ComponentLike`, a value none of whose presentations names a style; what a
value formats itself wins, as Adventure's children do. Tags holding a value alone win instead: every brace inserts its
value inside a mark (`ValueMarks`, an insertion only it writes), MiniMessage builds the tree without
compacting it (`TextImpl.READER`), and `ValueMarks.settle` walks it, gathers the colour, decorations
and font of the chain of empty components with a single child above each mark, sets them on the
value and clears them below it, then removes the mark and compacts. Hover texts are settled the same
way; a gradient, applied while the tree is built, never reaches what has a colour of its own. Where the sheet defines one of those names, the
brace's tag is an `Insertion`, self-closing and styled by the scopes it finds as a style tag does,
with the first name that resolves to a style there; otherwise it is the plain inserting tag it
always was. Two braces stay as they are: one that is all a style's tag holds (the chain of only
children above its node reaches a node that opened a style), since its author styled it by hand,
and one whose style is the nearest style around it already, so a style with text of its own is not
applied twice. The plain text put into a tag argument never takes a style.

`TextImpl` is `Reloadable`: at the start and at every reload of its plugin it hands the plugin to
`ServerStyles.adopt`, before the plugin's `MessagesImpl`, which was created after it, is loaded.
`adopt` reads `styles.yml` from the plugin's JAR (`plugin.getResource`; the file sits next to
`plugin.yml`, outside `resources/`, so it is never extracted) and, when the sheet in memory lacks
one of its dotted paths, reads the server file again, appends what that lacks under
`# Default styles of <Plugin>` and swaps the sheet. A name new to the file is written nested as
shipped; under a name the file has, each lacking style is a dotted line, an own style as
`name.style`, because a YAML section cannot be opened twice. The file's text is otherwise kept
byte for byte (its line breaks too) and replaced in one move. The server file wins wherever it
defines a path, so of two plugins shipping one path the first to start decides and the second
reads what is there; a default deleted from the file comes back at the plugin's next start or
reload, like an extracted file. Constant texts are kept per sheet, so a new sheet drops them.

VersatiaCore ships the styles every plugin shares as the `styles.yml` at the root of its own JAR:
the tones `positive`, `negative`, `warning` and `neutral`, `accent`, `hint`, `clickable`, `label`,
`heading`, and `serverTag`, a style that holds text, written self-closing in front of a message and
shipped empty.
`ServerStyles.reload` adopts them like any plugin's right after reading the file, so
they are in the server file before another plugin starts, a plugin's default for one of them is
never used, and a deleted one comes back at VersatiaCore's next start or reload.

`Presenters.of` gathers every presentation of a value into a `Presented`, in layers: the value's
own when it is `Presentable`; then the server's presentations in use and those registered on the
instance, together, ordered so that a type comes before its supertypes and otherwise as given,
the server's first (so a server presentation wins over the plugin's own of the same type); then
the built-ins, ordered the same way, and their fallback. `Presented` makes a layer's
`Presentation` only when a part is looked for in it and answers the component, the style and each
property from the first layer defining it, so a presentation that defines one property leaves the
component, the style and the other properties to the layers after it: `look()` gives the first
component with the first style any layer names, so a plugin's own component for `Player` without
a style keeps `player`, and a layer naming a style alone restyles the component of a later one.
A value matching nothing has no `Presented`. Registering a type again replaces its entry, since two
entries of one type would rule each other out. The built-ins have a fallback of their own
holding Adventure's `Translatable`, which inserts `Component.translatable(value)` so the client
shows `Material`, `EntityType`, `GameMode` and the like in the viewer's language; being a level
below, it never beats a specific built-in such as `Entity` or `ItemStack`, even for a value that
implements both without one extending the other. Iterables and object arrays are not registered
at all: when no layer has a component, `ArgumentResolver` asks `BuiltInPresentations.elements`, and
the elements are joined with `, `, each rendered through the instance's own chain (an element
without a component fails as `{name[index]}`), with `size` as the one property, also under a
presentation that does not define it; so a plugin's registration for `List` still wins. A value
some layer presents but none gives a component, and that is no collection, fails with
`NoTextFormException`. Nothing language-dependent is built in (no durations, dates or number formats). Argument names are
letters, digits, `_` and `-`, checked when the `Argument` is created, with one pattern shared by
argument and property names; an argument given twice is a `DuplicateArgumentException`. Tag names
are checked against the MiniMessage tag-name syntax, the standard tags and the private placeholder
prefix, so `<color>` can never be shadowed and a tag can never shadow a brace.

`Presenters` keeps an immutable list in a volatile field and swaps in a new one on every
registration, and the registered tags are a concurrent map, so a plugin may register while another
thread parses. Thread safety stops at the presentations: the built-in ones read Bukkit state
(`entity.location`, `world.players`), which is safe on the main thread only.

## Server presentations

`ServerPresentations`, a `@LocalBean` of VersatiaCore, holds what the `@ServerPresentation` beans
of the running plugins offer: per offer the owning plugin, the bean class, whether it is
`@Primary`, and a `TypePresentation` (the presented class, the style and the presenter). Of the
offers of one type one is in use: the only one, or the single `@Primary` one, or, when the
`@Primary` one was withdrawn and several remain, the one offered first. An offer that would leave
a type with several offers and not exactly one `@Primary` among them is refused as a whole with
an `IllegalArgumentException` naming every competitor. The offers and the list in use are
immutable lists in volatile fields, replaced under a lock, so `matching` runs on any thread.

`ServerPresentationEnhancementProcessor` interprets the elements of `@ServerPresentation`, which
inherits `@LocalBean`, so the same `ClassTarget` is also a bean of the plugin. At start, once
every processor has seen the plugin's elements and the beans exist, before the start functions
run, it takes each bean instance as a `Presenter`, loads the presented class from the argument of
the `Presenter` supertype among the target's `genericSupertypes` (a Kotlin qualified name, through
`engine.descriptor.loadDescribedType`), reads the style from the annotation's `style` argument
(empty for none, checked with `StyleFile.objection`) and `@Primary` from the class's attributes,
and offers them all at once; a refused offer fails the start with the plugin's name in front. It
reaches `ServerPresentations` through the plugin's own `Text`, resolved through its beans like any
dependency (VersatiaCore makes one for every plugin with beans), and fails when that `Text` is not
a `TextImpl`. The offer is withdrawn by an `AutoCloseable` entry the processor puts into the
plugin's state, closed when the state is dropped: at stop, after the stop functions and before the
plugin's beans are closed, and also when the start fails after the offer. A reload touches
nothing: the bean, and so its presentation, lives as long as the plugin.

A presenter runs in the parse of whichever plugin shows the value, on that thread; what it throws
is a `PresentationException` of that parse like any presentation's. The descriptor records the
presented type as a fact about the class (its generic supertypes with their type arguments), so
the runtime never reads generic signatures by reflection.

## Loading and reloading

`MessagesImpl` implements `Reloadable`, so the runtime calls `reload()` right after the instance is
created, which is after the consumer's resources were extracted, and at every reload of the
consumer, before its reload-phase functions. `MessageLoader` builds a new map from the files; it is
swapped in only when every file was read, so a broken file at a reload leaves the previous messages
in place and fails the reload with `Plugin X: ... failed to reload` and a `MessagesLoadException`
naming the file.

The files are read with SnakeYAML directly, not through Bukkit's `YamlConfiguration`: a resolver
without implicit types keeps every scalar, key or value, a string exactly as written, where
Bukkit's YAML 1.1 loader turns `Yes`/`off` into booleans, `1.10` into `1.1`, `010` into 8 and
`12:30` into 750, and turns the keys `yes:`/`no:` into `true`/`false`. An empty value is an empty
message. Duplicate keys in one file are rejected (`allowDuplicateKeys = false`, which Bukkit never
sets), and so is a dotted key colliding with a nested one. A key that is a message in one place
and a group of messages in another (`shop: "..."` beside `shop.open`), in one file or across two,
fails as `"shop" is a message in messages/general.yml and a group of messages in messages/shop.yml;
rename one of them`; the loader remembers, for every group prefix, the first file that used it. A
list must hold text lines only, and a file must map keys to messages. The file must be UTF-8. A file that cannot be read and a
SnakeYAML error are reported with the file, the latter as `line L, column C:` and the problem alone. Files are visited in the order of their
path relative to the data folder with `/` separators, the same on every platform. A file whose
name starts with a dot is not read for messages; `messages/.versatia.yml` holds the settings of
the plugin's bank, today only `legacy-color-warnings` (`true` or `false`), and an unknown setting
or another value fails the load.

Once every file is read, the loader logs one warning through the consumer's logger (so the line
carries its `[Name]` prefix) for each message holding legacy colour codes, which MiniMessage shows
as text, naming the key, the file and the tag for every code: `&a -> <green>, &l -> <bold>`.
`LegacyColorCodes` finds `&` or `§` followed by `0-9`, `a-f`, `k-o` or `r`, either case, when the
code is not preceded by a letter or digit, unless that character ends another code: `&a&lSale` has
two codes, `R&D` and `B&B` none. The warnings are logged only when the load succeeds, again at
every reload, and `legacy-color-warnings: false` turns them off.

Nothing is parsed for use at load, but every message is checked for its tags before a player can
meet it: `MessageImpl.check` reads each line through `CheckingText.check`, the same parse with a
resolver that renders nothing, calls no argument tag and only notes what does not exist, and a
failure is a `MessagesLoadException` naming the key and the file. Tags are typically registered in
a start function, which runs after `Reloadable.reload()`, so the first load cannot be checked on
the spot. `MessagesImpl` is therefore `StartAware`, a Core-internal hook of the bean session: the
session's action that creates and loads the beans registers one more completion action, which
runs after every start function, and calls `started()` on the beans that ask for it; a prototype
instance obtained later is told right after its first `reload()`. `started()` checks the bank,
failing the start, and from then on `reload()` checks the new bank before swapping it in, so a
reload with a tag that does not exist keeps the previous messages. Parsing for use at load would
also freeze a tag-free reading of a text that uses a tag and evaluate argument tags without
arguments. `MessageImpl` keeps the lines of a list
apart and parses each on its own, so formatting does not leak into the next line; `lines()` returns
them, `get()` returns a single line as it is and several joined with `Component.newline()`. It
parses at every use and wraps any failure in a `MessageRenderException` naming the plugin, key and
file. `TextImpl` keeps the components of constant texts, those without braces and without a
registered tag name, in a bounded LRU map (1024 entries per instance), whatever arguments the call
passes (they are still checked), so such a line is parsed once even beside lines with arguments; a text naming a
registered tag is parsed every time, since the tag may be dynamic, and registering a tag clears the
map, so tags registered after load apply at once. MiniMessage calls a tag resolver twice per parse
(pre-processing and parsing), so a dynamic tag sees two calls per use.

## Tests

`TextImplTest` runs MiniMessage directly, including braces inside click, insert, color and hover
arguments with hostile values, escaped braces, presenter precedence and the constant cache. `MessageLoaderTest`
reads files from a temporary data folder through a `Text` of its own: YAML scalars, duplicate
keys, message/group collisions, separate lines, file order, tags registered before and after
load, and the legacy-code warnings, collected by a log handler, with their settings file. Translatable and collection presentations are tested with plain stand-ins (a `Translatable`
stub and an `Entity` proxy). `MessagesImplTest` drives the bank
through a `Plugin` proxy and checks that a failing reload keeps the previous messages;
`MissingMessageExceptionTest` covers the error text. Nothing needs a server. The Bukkit presentations (`Player`, `ItemStack`, ...) cannot be
constructed outside a server and are not unit-tested; the style of one is, through a `Player` proxy.

`ServerPresentationsTest` gives two texts one `ServerPresentations` and checks the layers: the
server's presentation winning over a plugin's own of the same type, every part it leaves out
coming from the next layer (the built-in `Player` component, properties and style under a
presentation adding one property), the nearest type across the server and the plugin
(`OfflinePlayer` covering `Player`), and competing offers (refused without a single `@Primary`,
the first one back in use when the `@Primary` one is withdrawn). `ServerPresentationIntegrationTest`
compiles providers with the real KSP processor and runs them through the runtime: a provider's
property reaching a plugin that started before it, kept by a reload and gone with the stop, and
a second provider of the same type failing its start unless it is `@Primary`.

`StyleFileTest` covers the format (scopes, dotted keys, every load error) and the text of what is
added for a plugin; `TextStylesTest` what styles come to in a parse, compared with the same text
written in the tags of the style: scopes, the nearest one winning, closing and `<reset>`, snippets
with gradients and text, hover text, styled values and lists of them, the errors of unknown and
misplaced tags, and the constant cache following a new sheet. `ServerStylesTest` drives the file:
reloads, defaults appended under their comment, the file winning, two plugins shipping one name,
dotted additions, Windows line breaks and files that cannot be read. `MessagesImplTest` checks the
bank at the end of the start and at a reload, and `BeanLoadingTest` the order of the `StartAware`
hook. `TextStylesIntegrationTest` runs a plugin compiled with the real KSP processor through the
runtime: its defaults reach the server file, a tag of a start function counts, and a tag that
does not exist fails the start or the reload.
