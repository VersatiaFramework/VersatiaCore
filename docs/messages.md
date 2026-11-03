# Messages and text

`Messages` (package `messages`) and `Text` (package `text`) are part of VersatiaAPI; their
implementation lives here, in the packages of the same names. Plugin-author documentation:
https://versatia.dev/docs/messages.

## Two prototypes per plugin

VersatiaCore exposes two `@ExposePrototype`s, so every plugin that injects them receives instances
of its own:

- `TextImpl`, the parser behind `Text`. The presentations and tags a plugin registers never leak
  into another plugin, and they vanish when that plugin is disabled, because the instance belongs
  to the consumer's bean session. The built-in presentations (`BuiltInPresentations`) are one
  shared `Presenters` used as the fallback of every instance.
- `MessagesImpl`, the bank behind `Messages`, taking the consuming `Plugin` and its `Text`. The
  runtime hands every consumer an instance made with its own plugin, whose `messages/` directory is
  read, and the `Text` instance made for that consumer, the same one its own beans inject, so the
  presentations the consumer registered apply to its messages.

Nothing about them is kept in VersatiaCore itself: every instance belongs to the consumer's bean
session, and a plugin that never injects them never has one.

## Two packages

The packages mirror the API's. `dev.versatia.text` holds the parser (`TextImpl`,
`ArgumentResolver`, `Substitution`, `Presenters`, `BuiltInPresentations`) and never refers to
`messages`; `dev.versatia.messages` holds the bank (`MessagesImpl`,
`MessageLoader`, `MessageImpl`) and the main class, and reaches the parser only through the `Text`
interface. The two halves could be split again without touching a consumer's imports.

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
value's formatting and still never parses it. Braces in an unknown or escaped tag, which
MiniMessage prints as text, take the component path like any text. `<pre>` is not special-cased:
a brace inside it shows the private tag literally.

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

A value that is `Presentable` describes itself; otherwise `Presenters.of` picks, among the types
registered on the instance that the value is an instance of, the one no other candidate is a
subtype of, and falls back to the built-ins. Registering a type again replaces its entry, since
two entries of one type would rule each other out. The built-ins have a fallback of their own
holding Adventure's `Translatable`, which inserts `Component.translatable(value)` so the client
shows `Material`, `EntityType`, `GameMode` and the like in the viewer's language; being a level
below, it never beats a specific built-in such as `Entity` or `ItemStack`, even for a value that
implements both without one extending the other. Iterables and object arrays are not registered
at all: when no presenter answers, `ArgumentResolver` asks `BuiltInPresentations.collection`, which
joins the elements with `, `, each rendered through the instance's own chain (an element without a
component fails as `{name[index]}`), and offers `size`; so a plugin's registration for `List`
still wins. Nothing language-dependent is built in (no durations, dates or number formats). Argument names are
letters, digits, `_` and `-`, checked when the `Argument` is created, with one pattern shared by
argument and property names; an argument given twice is a `DuplicateArgumentException`. Tag names
are checked against the MiniMessage tag-name syntax, the standard tags and the private placeholder
prefix, so `<color>` can never be shadowed and a tag can never shadow a brace.

`Presenters` keeps an immutable list in a volatile field and swaps in a new one on every
registration, and the registered tags are a concurrent map, so a plugin may register while another
thread parses. Thread safety stops at the presentations: the built-in ones read Bukkit state
(`entity.location`, `world.players`), which is safe on the main thread only.

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

Nothing is parsed at load. Tags are typically registered in a start function, which runs after
`Reloadable.reload()`, so parsing at load would freeze a tag-free reading of a text that uses
one, and would evaluate argument tags without arguments. `MessageImpl` keeps the lines of a list
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
constructed outside a server and are not unit-tested.
