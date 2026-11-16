<p align="center">
  <img src="docs/icon.svg" width="120" alt="xFermaTube icon">
</p>

<h1 align="center">xFermaTube</h1>

<p align="center">
  A lightweight YouTube player for Android Auto that fixes the audio delay automatically, using the phone's microphone.
</p>

<p align="center">
  <b><a href="../../releases/latest">⬇ Download the latest APK</a></b> · <a href="#install">Full install guide</a>
</p>

> [!IMPORTANT]
> **Quick install**
> 1. **Don't open the APK directly.** Install it through **AAenabler** (recommended: it is the installer I use and test with). Otherwise Android Auto may show only the audio entry and hide the video app.
> 2. On the phone: Android Auto settings → tap **Version** 10 times → ⋮ → **Developer settings** → tick **Unknown sources**.
> 3. Open xFermaTube on the phone once and allow the **microphone**.
> 4. In the car, open **xFermaTube** (play icon), not *xF Audio* (speaker icon).

---

## The problem

I wanted to watch YouTube on my car's Android Auto screen while parked. Every Android Auto video app I tried had the same two issues:

1. **Audio lagged behind the picture** by around 300 ms, over both USB and wireless. Android Auto streams audio to the car through its own buffer, and the player on the phone has no idea that buffer exists. So the picture is shown "on time" and the sound arrives late. Lips and voices never matched.
2. **They were bloated.** IPTV, radio, podcasts, file browsers, dozens of settings. I only needed YouTube, with working sync.

Some apps had an "audio delay" setting, but it either did nothing for Android Auto or could not be fixed, because the apps are closed source.

## What I built

xFermaTube is a stripped-down fork of [FermataX](https://github.com/chuoinho/fermataX) (itself a fork of [Fermata](https://github.com/AndreyPavlenko/Fermata)). It keeps only YouTube and adds A/V sync that tunes itself.

- **Delayed video instead of earlier audio.** Audio that is already in the car's buffer can't be sped up, so the app holds the picture back. YouTube keeps playing normally. The frames are copied to a canvas and shown X ms later, so they line up with what you hear from the car's speakers.
- **Automatic delay measurement with the phone's microphone.** The app records about 10 seconds of what the car's speakers actually play. It compares that with the video's own soundtrack and finds the lag by cross-correlation. In auto mode this repeats on its own: about 10 seconds after a new video starts (a short 6-second measurement, applied right away when the result is clear, so the picture is usually in sync within about 20 seconds), after a buffering stall, and every 60 seconds (every 3 minutes once the delay has settled). Each new video starts with fresh measurements, because the lag differs from video to video. A single measurement is noisy (±20–30 ms), so fine-tuning uses the median of the last five measurements. A big jump, for example after a stall, needs two clear measurements in a row that agree. Changes are applied gradually so the picture doesn't jump.
- **Learns your correction.** If you nudge the delay by hand right after a measurement, it remembers the difference and applies it from then on.
- **Separate settings for USB and wireless Android Auto.** These connections have different latency, so each keeps its own delay. On the phone screen the delay is 0.
- **YouTube only.** The app opens straight into YouTube. The car's side rail has just **Home, History, Back, Refresh, Settings**, and Settings is one short page: appearance, language, YouTube options (quality limit, ad skipping, SponsorBlock, auto fullscreen) and the sync badge. You can sign in to YouTube on the phone once, and the car uses the same session.
- **Much smaller.** The APK went from 185 MB to about 19 MB once the unused add-ons were removed (VLC, ExoPlayer, TV, radio, podcasts, audiobooks, SMB, SFTP).

## What it achieves

In my car (USB Android Auto), the measured audio lag was about 300 ms. With the video delayed by that amount, speech and picture match. The microphone measurement worked in the car without interrupting playback. Before, manual calibration with a sync test video was the only option. Now the app finds the value itself and keeps correcting it if the lag drifts.

## Open source, no tracking

I built this to fix one annoying problem for myself, not to make money or collect data.

- **Fully open source (GPL-3.0).** All the code is in this repo. You can read it and build the APK yourself.
- **No ads, analytics or tracking added.** There are no analytics or crash-reporting SDKs, no accounts and no xFermata server. Nothing is sent to me.
- **The microphone stays on the phone.** It is used only for the sync measurement and only while a video is playing. The recording is turned into a loudness curve in memory to find the delay, and the audio is never saved or uploaded. See [`XfMicRecorder.java`](fermatax/modules/web/src/main/java/me/aap/fermata/addon/web/yt/XfMicRecorder.java).
- **Few permissions.** The APK declares internet, microphone, notifications and media playback, plus a few routine ones (network and Wi-Fi state, wake lock, audio settings). The location, camera, storage, screen overlay, install-packages, boot and system-settings permissions that FermataX declares are removed.
- **YouTube is the normal mobile YouTube site.** Google's own privacy policy applies, the same as in any browser.

## Install

Android Auto only shows video apps that look like they came from the Play Store, so the APK has to be installed with a helper app. Opening the APK from the browser or a file manager is not enough.

1. **Turn on Unknown sources in Android Auto.** On the phone: Android Auto settings → scroll down and tap **Version** 10 times → **⋮** (top right) → **Developer settings** → tick **Unknown sources**.
2. **Download the APK** from [Releases](../../releases/latest) (arm64 phones only).
3. **Install it through AAenabler**, not by opening the file: open AAenabler and pick the APK from inside it. **AAenabler is the recommended installer**: it is the one I use, and every release is tested with it. KingInstaller (the installer FermataX suggests, with Play Store as the install source) is an alternative I have not tested myself.
4. **Open xFermaTube on the phone once** and allow the **microphone** when it asks. The car screen can't show this request. You can also grant it later: Settings → Apps → xFermaTube → Permissions → Microphone.
5. **Connect to the car and open xFermaTube** from the Android Auto app list.
6. Optional: open xFermaTube on the phone and sign in to YouTube (avatar → Sign in). The car uses the same session.

To update, install the new APK over the old one the same way. Your settings are kept.

### Two icons in Android Auto

- **xFermaTube** (play icon): the video app. This is the one you want.
- **xF Audio** (speaker icon): audio only, for the media card and steering wheel controls. In v1.0.0 it was called *xFermaTube Audio* and had the same icon as the video app.

### Only the audio entry shows up?

Then Android Auto is hiding the video app. This usually comes from the way the APK was installed:

1. Uninstall xFermaTube and install it again **through AAenabler** (step 3 above).
   To check how it was installed: phone Settings → Apps → xFermaTube → the install source line at the bottom of the page. On my phone it says *App downloaded from AAEnabler*. If yours names the system package installer, the install did not go through the helper app.
2. Check that **Unknown sources** is ticked (step 1 above).
3. Force stop Android Auto and reconnect to the car.
4. On the phone, open Android Auto settings → **Customize launcher** and check that xFermaTube is ticked.

If the original FermataX doesn't show its video app in your car either, the problem is in the Android Auto setup, not in this app.

## Using it in the car

Tap the faint **⏱ X ms** label at the top left of the video to see the controls:

- **−100 / −10 / +10 / +100** adjust the delay by hand.
- **🎤** measures the delay once.
- **Auto** turns automatic measurement on or off (green = on).

Tap the label again to close it. If you don't need it, hide it in Settings → *Show A/V sync badge*: the delay and auto sync keep working.

In fullscreen, a tap shows or hides the control panel. Play/pause, seek and the **⋮** menu (quality, subtitles, audio track, scaling, loop, stable volume, speed) are buttons on that panel. Subtitles in fullscreen are delayed together with the picture. Swipe gestures on the video are turned off, so a stray touch doesn't pause, seek or skip.

Auto quality picks the best available resolution up to **1080p** by default (Settings → *Highest quality limit*: 720p, 1080p or no limit). Higher resolutions cost battery and data without a visible gain on a car screen.

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
