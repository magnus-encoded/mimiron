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
| Per-plugin dictation hotwords | missing |
| Plugin notices (feedback such as "no key bound") | missing |
