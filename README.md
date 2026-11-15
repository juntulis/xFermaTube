<p align="center">
  <img src="docs/icon.svg" width="120" alt="xFermata Tube icon">
</p>

<h1 align="center">xFermata Tube</h1>

<p align="center">
  A lightweight YouTube player for Android Auto that fixes the audio delay automatically, using the phone's microphone.
</p>

---

## The problem

I wanted to watch YouTube on my car's Android Auto screen while parked. Every Android Auto video app I tried had the same two issues:

1. **Audio lagged behind the picture** by around 300 ms, over both USB and wireless. Android Auto streams audio to the car through its own buffer, and the player on the phone has no idea that buffer exists. So the picture is shown "on time" and the sound arrives late. Lips and voices never matched.
2. **They were bloated.** IPTV, radio, podcasts, file browsers, dozens of settings. I only needed YouTube, with working sync.

Some apps had an "audio delay" setting, but it either did nothing for Android Auto or could not be fixed, because the apps are closed source.

## What I built

xFermata Tube is a stripped-down fork of [FermataX](https://github.com/chuoinho/fermataX) (itself a fork of [Fermata](https://github.com/AndreyPavlenko/Fermata)). It keeps only YouTube and adds A/V sync that tunes itself.

- **Delayed video instead of earlier audio.** Audio that is already in the car's buffer can't be sped up, so the app holds the picture back. YouTube keeps playing normally. The frames are copied to a canvas and shown X ms later, so they line up with what you hear from the car's speakers.
- **Automatic delay measurement with the phone's microphone.** The app records about 10 seconds of what the car's speakers actually play. It compares that with the video's own soundtrack and finds the lag by cross-correlation. In auto mode this repeats on its own: shortly after playback starts, after a buffering stall, and every 90 seconds. The delay changes only when two measurements agree, and it changes gradually so the picture doesn't jump.
- **Learns your correction.** If you nudge the delay by hand right after a measurement, it remembers the difference and applies it from then on.
- **Separate settings for USB and wireless Android Auto.** These connections have different latency, so each keeps its own delay. On the phone screen the delay is 0.
- **YouTube only.** The app opens straight into YouTube. The car's side rail has just **Home, History, Back, Refresh, Settings**, and Settings is trimmed to what matters. You can sign in to YouTube on the phone once, and the car uses the same session.
- **Much smaller.** The APK went from 185 MB to about 19 MB once the unused add-ons were removed (VLC, ExoPlayer, TV, radio, podcasts, audiobooks, SMB, SFTP).

## What it achieves

In my car (USB Android Auto), the measured audio lag was about 300 ms. With the video delayed by that amount, speech and picture match. The microphone measurement worked in the car without interrupting playback. Before, manual calibration with a sync test video was the only option. Now the app finds the value itself and keeps correcting it if the lag drifts.

## Install

1. Download the APK from [Releases](../../releases).
2. Sideload it so that Android Auto accepts it, for example with **AAenabler** or **KingInstaller**. Then turn on *Unknown sources* in Android Auto developer settings.
3. On the phone, grant the **microphone** permission: Settings → Apps → xFermata Tube → Permissions → Microphone. The app can't ask for it while it runs on the car screen.
4. Optional: open xFermata Tube on the phone and sign in to YouTube (avatar → Sign in).

In the car, tap the faint **⏱ X ms** label at the top left of the video to see the controls:

- **−100 / −10 / +10 / +100** adjust the delay by hand.
- **🎤** measures the delay once.
- **Auto** turns automatic measurement on or off (green = on).

> The release APK is debug-signed and built for arm64 phones only.

> **Safety:** the app has no driving lock of its own, and in my car the video keeps playing while moving. Other cars or Android Auto versions may block it. The driver must never watch video while driving: it is dangerous and illegal in many places. Use it only while parked or for passengers, and follow your local laws.

## Build

Requirements: Android SDK and a JDK supported by the Gradle wrapper.

```powershell
$env:ANDROID_HOME="$env:LOCALAPPDATA\Android\Sdk"
cd fermatax
.\gradlew.bat verifyWebOnlyProductionGraph fermata:packageAutoDebugUniversalApk
```

The output is `fermatax/fermata/build/outputs/apk_from_bundle/autoDebug/*-universal.apk`. You need the **universal** APK, because YouTube lives in a dynamic-feature module.

Key files:

| What | Where |
|---|---|
| Video delay, sync measurement, auto mode (injected JS) | `fermatax/modules/web/src/main/assets/xf-video-delay.js` |
| Microphone recorder (Java `AudioRecord`, no audio focus) | `fermatax/modules/web/.../yt/XfMicRecorder.java` |
| Injection and USB / wireless detection | `fermatax/modules/web/.../yt/YoutubeWebView.java` |
| YouTube-only switches | `fermatax/fermata/src/main/java/me/aap/fermata/xf/` |
| Car side rail | `fermatax/fermata/.../ui/glass/rail/GlassNavigationRailView.java` |

The microphone is recorded in Java rather than with the browser's `getUserMedia`. Android Auto treats `getUserMedia` as a phone call and pauses all media.

Some upstream FermataX unit tests refer to the removed add-on modules and are not maintained in this fork.

## Credits and license

- [Fermata Media Player](https://github.com/AndreyPavlenko/Fermata) by Andrey Pavlenko
- [FermataX](https://github.com/chuoinho/fermataX) by chuoinho

Licensed under **GPL-3.0**, the same license as the projects it is based on (see [LICENSE](LICENSE)). Third-party notices are in [`fermatax/THIRD_PARTY_NOTICES.md`](fermatax/THIRD_PARTY_NOTICES.md).

This project is not affiliated with YouTube, Google or any car maker.
