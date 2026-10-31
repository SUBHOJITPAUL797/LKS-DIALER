# LKS Dialer v2.9.46 - Release Notes

## Bug Fixes — Null Safety Hardening

### Crash Prevention: Eliminated all unsafe `!!` force-unwraps

Fixed **8 files** with dangerous null force-unwrap (`!!`) that could crash the app
on certain devices (especially Android 10+ with content:// URIs):

- **LksIncomingRingtonePlayer**: `uri.path!!`, `mediaPlayer!!`, `ringtone!!`, `audioFocusRequest!!`
  — all replaced with safe `?.let` / `?: return` guards
- **LksKeepAliveService**: `ringtoneUri.path!!` race condition fixed with local val;
  `tokenRefreshRunnable!!` replaced with `?.let` to prevent foreground service crash
- **LksRingtoneManager**: `uri.path!!` x2 — guarded with `?: return@launch`
- **AudioTrimmerUtil**: `srcUri.path!!` — throws meaningful exception instead of NPE
- **LksCallerTuneManager**: `compressedUri.path!!`, `uri.path!!` — guarded
- **CallerTuneTrimmerDialog**: `uri.path!!` — guarded
- **SongTrimmerDialog**: `uri.path!!` — guarded
- **AudioRouteManager**: `userExplicitSelectedDevice!!` — safe local val with `?: return`
- **CallHistoryScreen**: `selectedLogForDetail!!` x2 — guarded with `?: return`

### What this means for you
- Settings screen crash on certain devices: **fixed**
- Ringtone player crash on MIUI/ColorOS: **fixed**
- Call history crash on quick tap: **fixed**
- Audio route crash during call teardown: **fixed**

**Build**: 204 | **Date**: 2026-10-31
