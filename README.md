# BT Volume

Sets media volume to a per-device level when a paired Bluetooth device connects,
and restores the previous volume when it disconnects. Both events post a notification.

No dependencies beyond the Kotlin stdlib; no background service (a manifest receiver
for `ACL_CONNECTED` / `ACL_DISCONNECTED` is woken by the system).

Requires Android 10 (API 29) or newer.

## Build

Requires JDK 17+ and the Android SDK (easiest: open the folder in Android Studio).
To sign release builds with your own key, create `keystore.properties` in the project
root (it is gitignored):

    storeFile=/path/to/release.jks
    storePassword=...
    keyAlias=...
    keyPassword=...

Without it, the release build is signed with the debug key.

    ./gradlew assembleRelease     # app/build/outputs/apk/release/app-release.apk
    adb install -r app/build/outputs/apk/release/app-release.apk

## Use

Open the app once, grant *Nearby devices* and *Notifications*, then enable devices
and pick a level. The app must have been launched at least once (and not force-stopped)
for the system to deliver Bluetooth broadcasts to it.

## ColorOS

ColorOS (tested on ColorOS 16, Oppo Find X8 Pro) only lets the foreground app call
`setStreamVolume` / `adjustStreamVolume` on media volume; background calls are dropped
silently, logging `do not have setStreamVolume Permission`. The app then steps the volume
with `adjustSuggestedStreamVolume`, which is not restricted. The volume therefore lands on
the nearest step (1/16 of the range on ColorOS), e.g. 72% when 70% is set.

Some headsets bring up the audio link well after the Bluetooth connection (about 15 s for a
Bose QC Ultra), so the app waits up to 25 s for audio to move to the headset.

Other volume apps (e.g. Bluetooth Volume Manager) react to the same events; don't let both
manage the same device.

## License

[MIT](LICENSE)
