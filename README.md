# MB Loader (Unofficial)

**Not distributed by us**, modified by The NoxPE Team.

ORIGINAL APP: https://github.com/bambosan/MaterialBinLoader-Apk

## Changes in this fork
- Preloads Minecraft's native dependencies (`libpairipcore.so`, `libfmod.so`, `libPlayFabMultiplayer.so`, `libHttpClient.Android.so`, `libmaesdk.so`, `libc++_shared.so`) before launch. Fixes `UnsatisfiedLinkError: library "libpairipcore.so" not found` on Minecraft 1.26.x.
- `libmc.so` logs `dlopen`/`dlsym` failures to logcat (tag `MBL`).

## Releases
Every push to `main` builds a signed APK via GitHub Actions (`.github/workflows/build.yml`) and publishes it under **Releases** as `MBLoader-Unofficial-v<version>.apk`.
