# What Mimiron needs from Tehuti

| Need | Status |
| --- | --- |
| Start with a manifest, JSON lines over stdio | exists |
| `key` and `text` actions | exists |
| Button requests and focus notices (button params carry no app id, so focus is tracked) | exists |
| Speech requests to a plugin (`speech` is in the schema; the protocol README lists only button and focus events) | exists, undocumented |
| Keep D-pad up/down from the game while switching channel (virtual pad overrides) | #447 |
| Show the channel on the powerline (status method) | #447 |
| A press reaching the plugin (South presses go to the core as Enter) | solved by a South override while a line is drafted (#447) |
| Telling WoW apart from other Proton games (all are `steam_proton`) | Mimiron gates on the title "World of Warcraft"; a context title matcher comes after launch |
| Per-plugin dictation hotwords | #526, after launch: context `[speech] hotwords`, and a `speech.hints { hotwords }` notification that replaces the plugin's list (capped at 64 merged entries) |
| Fuzzy matching of speech a plugin parses itself ("chat with {channel}": heard as "cat with yields"). The context `[speech_matching]` only covers the context's own `[speech]` rules | missing; a protocol addition so a plugin declares patterns with slots and core does the fuzzy match, after launch. Until then Mimiron matches plurals and heard variants itself |
| Plugin notices (feedback such as "no key bound") | missing |
| Game link: sample a small strip of the focused game window for the plugin (`screen_probe.watch` / `screen_probe`, see docs/notes/game-link.md) | missing; drafted for tehuti 2026-11-11 |
| Voice chat push-to-talk: route the PTT button to a held game key (`push_to_talk.route`, see docs/notes/voice-chat-ptt.md) | missing; drafted for tehuti 2026-11-12 |
