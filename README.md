# Demo-tape

A four-track recorder for Android. A voice-memo app with four tracks, for sketching song demos. Not a DAW.

**Install:** open the [latest release](https://github.com/ronaldodaiqui/demo-tape/releases/latest) on your phone and tap `DemoTape.apk`.

## Using it

- **Record:** tap a track number to arm it (it turns red), then press ●. You hear the other tracks while you record. Press ● or ■ to stop.
- **Punch in:** start recording partway through a track and only that part gets replaced.
- **Mix:** faders set each track's level (the tick is unity, double-tap a fader to reset). **M** mutes.
- **Undo:** the ↶ button removes the last take.
- **Takes:** long-press a take on the timeline to line it up or delete it. Long-press a track number to clear the track.
- **Timeline:** tap to move the playhead, drag to scroll, pinch to zoom.
- **Export:** the share button mixes down to M4A or WAV and shares it or saves it to Downloads/Demo-tape.

## Staying in sync with Bluetooth headphones

Bluetooth adds a couple of hundred milliseconds between the phone and your ears, so a naive recorder puts every overdub late. Demo-tape handles it three ways:

1. **Automatic.** Playback and recording are timestamped against the same clock, and each take is placed from those timestamps. This covers the delay Android knows about.
2. **Sync check.** The first time you record with a new pair of Bluetooth headphones, a 5-second check plays 8 clicks while you knock on the phone in time. The mic hears the knocks, and whatever delay is left over is saved for those headphones.
3. **Line up.** After any take, "Line up" loops it so you can drag it or tap ±1/±10 ms until it sits right. With "Use for future takes" on, the correction is saved for those headphones too.

The app always records from the phone's built-in mic. Using the headphones' mic would switch Bluetooth into call mode, which sounds terrible.

## Building

GitHub Actions builds the APK on every push to `main` and publishes it to the `latest` release. Kotlin, Jetpack Compose, AudioTrack/AudioRecord. The signing key is committed so new builds install over old ones.
