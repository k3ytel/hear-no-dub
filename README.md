# Hear No Dub

No dub? No problem. A client-side Fabric mod for Minecraft 26.3 that shows synced subtitles for the video playing in your browser, so you can watch subbed anime while you play.

- Detects what's playing through Windows' media controls (Firefox, Chrome, Edge).
- Works out the show and episode (TVmaze lookup; long videos are treated as movies) and fetches English subtitles from **SubDL** and **OpenSubtitles** (free API keys), optionally **Jimaku**, or uses your own `.srt` / `.ass` / `.vtt` files from `config/hear-no-dub/subs/`.
- **On screen:** HUD caption, action bar or chat. **In the world:** floating text, **pop-in** lines that appear at random spots in your view and move with your camera, or **speech bubbles** above the mob you look at (or you).
- **Styles:** Movie, Glow, Box, Retro or fully custom (color, outline, shadow, glow, box). Keeps italics/bold, colors by speaker, karaoke word-by-word reveal, fade/pop/typewriter animations, next-line preview.
- **Video control:** play/pause, skip back/forward, browser volume, and a clickable progress bar in the picker. Auto-pause when you open the Esc menu.
- **Skip opening / ending:** opening and ending times come from [AniSkip](https://aniskip.com) (show found on AniList). A "Skip opening [O]" prompt appears while one plays, with optional auto-skip.
- **Line search:** search every line of the subtitles, then click one to sync to it or to jump the video there.
- Follows play, pause and seeking. Fix sync with `[` / `]` or by clicking the line you just heard ("Sync by line" in the picker). Offsets are remembered per show.

| Key | Action |
|---|---|
| `J` | Toggle subtitles |
| `[` / `]` | Subtitles earlier / later |
| `K` | Subtitle picker |
| `P` | Play / pause the video |
| `H` | What did they say? (last 5 lines) |
| `←` / `→` | Skip back / forward (10 s) |
| `O` | Skip opening / ending (or jump ahead 85 s) |
| `-` / `=` | Browser volume down / up |
| (unbound) | Place 3D subtitles here |

Windows only. Requires Fabric API and YetAnotherConfigLib; Mod Menu recommended.

License: MIT
