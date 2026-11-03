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
- "Open spells", "open bags", "open map" press the keys bound in
  `WTF/Account/*/bindings-cache.wtf` (read only), else WoW's defaults. The
  WTF folder comes from `~/.local/bin/wow-forever`, or `MIMIRON_WTF`.

With a `[pad] passthrough = true`, `virtual = true` context, Mimiron sets pad
overrides only for the buttons it needs in the current state; the game gets
everything else untouched. While a channel is on, that includes Select.
See `CAPABILITIES.md` for what Mimiron still needs from Tehuti.

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
