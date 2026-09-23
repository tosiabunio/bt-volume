# BT Volume

## Build & install

    ./gradlew assembleRelease     # app/build/outputs/apk/release/app-release.apk
    adb install -r app/build/outputs/apk/release/app-release.apk

Machine-specific paths (JDK, adb) are in `CLAUDE.local.md` if present.

## Debugging

    adb logcat -s BtVolume        # app logs
    adb shell dumpsys audio       # volume events, safe-volume and absolute-volume state

## ColorOS volume restriction

ColorOS (`oplus-framework.jar` `AudioManagerExtImpl.setStreamVolumePermission`) silently
drops `setStreamVolume` / `adjustStreamVolume` on STREAM_MUSIC unless the caller is the
foreground app; logcat shows `do not have setStreamVolume Permission`.
`adjustSuggestedStreamVolume` is not gated but applies asynchronously, so `BtReceiver.setVolume`
falls back to stepping with it.
