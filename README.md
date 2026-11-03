# Mimiron

A Tehuti plugin for WoW Forever: a voice chat router that shows the current
channel on the powerline. Super experimental. Design: Tehuti's
`docs/design/plugins.md`.

Written in Clojure, shipped as a GraalVM native binary.

## Build

Needs the Clojure CLI and GraalVM with `native-image` (Arch: `clojure`, AUR
`jdk21-graalvm-bin`).

    ./build-native

## Try

    echo '{"jsonrpc":"2.0","id":0,"method":"initialize","params":{"protocol_version":0,"encoding":"json","core_version":"0.0.0"}}' | ./mimiron

Install by copying `mimiron` and `mimiron.json` into
`<context-directory>/plugins/`.
