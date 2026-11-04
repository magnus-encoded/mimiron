# Mimiron

A Tehuti plugin for WoW Forever: a voice chat router that shows the current
channel on the powerline. Super experimental. Design: Tehuti's
`docs/design/plugins.md`.

Written in Clojure, shipped as a GraalVM native binary.

## Use

- "Chat with party" (say, party, guild, raid, battleground) keeps a sticky
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
  `WTF/Account/*/bindings-cache.wtf` (read only), else WoW's defaults. The
  WTF folder comes from `~/.local/bin/wow-forever`, or `MIMIRON_WTF`.

With a `[pad] passthrough = true`, `virtual = true` context, Mimiron sets pad
overrides only for the buttons it needs in the current state; the game gets
everything else untouched. While a channel is on, that includes Select.
See `CAPABILITIES.md` for what Mimiron still needs from Tehuti.

## Privacy and fair play

Mimiron keeps no message history and never logs your words. It does not read
other players' chat, your screen or game memory, and makes no internet
connections. It reads your launcher and key bindings without changing them.

Mimiron sends game input only while WoW has focus, in response to your voice
or button presses. It has no automatic gameplay, timed actions or input
broadcasting, and does not modify the game client.
## Binding files

By default Mimiron reads the newest account-level
`_forever_/WTF/Account/<account>/bindings-cache.wtf`. Set `MIMIRON_BINDINGS`
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
exists. The local installation checked on 2026-11-04 contained no binding
caches at either level, so selecting a logged-in character was impossible.
Use the explicit file override for character bindings and update it when
changing characters; bindings are read once at startup.

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
