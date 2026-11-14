# Mimiron

A Tehuti plugin for WoW Forever: a voice chat router that shows the current
channel on the powerline. Super experimental. Design: Tehuti's
`docs/design/plugins.md`.

Written in Clojure, shipped as a GraalVM native binary.

## Use

- "Chat with party" (say, party, guild, raid, battleground, yell, general, reply,
  officer, raid warning, trade, LFG, instance) keeps a sticky
  channel. Each utterance opens chat and types `/p text`.
- The powerline shows the channel: `Party`, `Party (paused)`, or `Party ↕`
  while switching.
- "Switch channel": D-pad up and down step the channel; B or Start leaves.
- A sends the drafted line, B cancels it.
- "Pause chat" pauses dictation and "resume chat" resumes it. Select stays with the game and the push-to-talk chord.
- "Stop chat" ends the mode.
- "Screenshot" (also "take a screenshot" or "take screenshot") takes one screenshot,
  even with a draft open or no chat channel selected.
- "Open spells", "open bags", "open map", "screenshot" press the keys bound in
  `WTF/Account/*/bindings-cache.wtf` (read only, re-read when it changes), else
  WoW's defaults. The
  WTF folder comes from `~/.local/bin/wow-forever`, or `MIMIRON_WTF`.
- The same goes for the other panels: "open character", "open reputation", "open honor"
  (or "pvp"), "open talents", "open professions", "open quest log", "open guild",
  "open friends" (or "social"), "open collections" (or "mounts"), and "open currency",
  "open raid", "open group finder", which have no default key and work once bound.
  "The" or "my" after "open" is fine. Each phrase presses one key, never into an open draft.

With a `[pad] passthrough = true`, `virtual = true` context, Mimiron sets pad
overrides only for the buttons it needs in the current state; the game gets
everything else untouched. While a channel is on, that includes Select.
See `CAPABILITIES.md` for what Mimiron still needs from Tehuti.

## Privacy and fair play

Mimiron keeps no message history and never logs your words. It does not read
other players' chat, your screen or game memory, and makes no internet
connections. It reads your launcher and key bindings without changing them.
One optional exception, off by default: if you type `/mimiron guildnames on` in
game, the add-on saves your guild members' names (nothing else) so voice can
recognise them. They stay on your computer, are never logged, and refresh only
when you `/reload` or log out. `/mimiron guildnames off` clears them.

Mimiron sends game input only while WoW has focus, in response to your voice
or button presses. It has no automatic gameplay, timed actions or input
broadcasting, and does not modify the game client.
## Binding files

By default Mimiron reads the newest account-level
`_forever_/WTF/Account/<account>/bindings-cache.wtf`, WoW's own key bindings
file. It keeps only the `bind KEY ACTION` lines; spell, macro, item and click
bindings and everything else in the file are skipped. Set `MIMIRON_BINDINGS`
to the exact binding file to select an account or character explicitly, for
example `.../_forever_/WTF/Account/<account>/<realm>/<character>/bindings-cache.wtf`.
This takes precedence over `MIMIRON_WTF` and launcher discovery. Files are
only read. A selected missing, empty, unreadable or garbled cache gives an
empty binding map; Mimiron never merges in another character's bindings.
The usual default keyboard keys remain available unless explicitly rebound.

Binding files identify their owner through their path, but do not identify
the currently logged-in character or active binding set. Modification times
can come from another character or session. A character-selection index
does not map to a character name in these files. Mimiron therefore never
guesses a character from cache timestamps, even if only one character cache
exists. The local installation checked on 2026-11-14 had an empty account
cache and no character caches, so every voice action used the defaults.
Use the explicit file override for character bindings and update it when
changing characters.

Mimiron reads the file again each time WoW gets focus and before each phrase,
but only when its path, modification time or size changed, so a key you rebind
in WoW's Key Bindings menu works by voice without a restart. It never reads
the file while another app has focus.

`SHIFT-`, `CTRL-` and `ALT-` prefixes combine on keyboard bindings.
`PAD*` bindings are retained when parsing but skipped when choosing a
keyboard action: the plugin protocol cannot press native gamepad buttons.
Mimiron uses another keyboard binding for the action when available.

## Develop

Locally we only run a REPL and the tests. Needs the Clojure CLI (Arch: `clojure`).

    clojure -M:repl
    clojure -M:test

## Build

GitHub Actions runs the tests and builds the native binary with GraalVM on
every push (`.github/workflows/native.yml`), uploading it as an artifact.
`build-native` is the script it runs.

## Install

Copy `mimiron` and `mimiron.json` into `<context-directory>/plugins/`.
