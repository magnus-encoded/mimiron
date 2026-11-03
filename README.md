# Mimiron

A Tehuti plugin for WoW Forever: a voice chat router that shows the current
channel on the powerline. Super experimental. Design: Tehuti's
`docs/design/plugins.md`.

Written in Clojure, shipped as a GraalVM native binary.

## Develop

Locally we only run a REPL. Needs the Clojure CLI (Arch: `clojure`).

    clojure -M:repl

## Build

GitHub Actions builds the native binary with GraalVM on every push
(`.github/workflows/native.yml`) and uploads it as an artifact.
`build-native` is the script it runs.

## Install

Copy `mimiron` and `mimiron.json` into `<context-directory>/plugins/`.
