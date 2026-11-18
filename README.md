<p align="center">
  <img src="docs/icon.svg" width="120" alt="xFermaTube icon">
</p>

<h1 align="center">xFermaTube</h1>

<p align="center">
  A lightweight YouTube player for Android Auto that fixes the audio delay automatically, using the phone's microphone.
</p>

<p align="center">
  <b><a href="../../releases/latest">⬇ Download the APK (latest release)</a></b> · <a href="#install">Full install guide</a>
</p>

> [!WARNING]
> **After the latest Android Auto update: car screen black or grey, buttons too big, or the picture freezing when the phone is locked?** Try the beta [v1.0.4-beta.2](../../releases/tag/v1.0.4-beta.2). No extra permission is needed any more. [Why](#the-car-picture-freezes-or-the-car-screen-turns-grey-newer-android-auto)

> [!IMPORTANT]
> **Quick install** (v1.0.3-beta.3 and later: no helper app, PC or Wi‑Fi needed)
> 1. Download the APK and open it to install.
> 2. Open xFermaTube on the phone and tap **Fix now** → **Open the setting** (allow) → **Reinstall now** → **Update**. Without this Android Auto shows only the audio entry and hides the video app.
> 3. On the phone: Android Auto settings → tap **Version** 10 times → ⋮ → **Developer settings** → tick **Unknown sources**.
> 4. Open xFermaTube on the phone again and allow the **microphone**.
> 5. In the car, open **xFermaTube** (play icon), not *xF Audio* (speaker icon).
> 6. **Do the one-time sync check (new in v1.0.3).** The first time xFermaTube is open on the car screen, the phone offers it. Park, tap **Start**, point the phone's back camera at the car screen and hold still for about a minute. The car flashes and beeps, the phone measures the difference and fixes it. You do it once per connection type (USB / wireless). [More](#the-one-time-sync-check)
> 7. **Sync is not instant.** After that, auto sync listens to each new video first: give it up to a minute before sound and picture line up.
>
> Older versions (v1.0.2 and earlier) have no **Fix now**: install those through AAenabler, see the [install guide](#install).

---

## The problem

I wanted to watch YouTube on my car's Android Auto screen while parked. Every Android Auto video app I tried had the same two issues:

1. **Audio lagged behind the picture** by around 300 ms, over both USB and wireless. Android Auto streams audio to the car through its own buffer, and the player on the phone has no idea that buffer exists. So the picture is shown "on time" and the sound arrives late. Lips and voices never matched.
2. **They were bloated.** IPTV, radio, podcasts, file browsers, dozens of settings. I only needed YouTube, with working sync.

Some apps had an "audio delay" setting, but it either did nothing for Android Auto or could not be fixed, because the apps are closed source.

## What I built

xFermaTube is a stripped-down fork of [FermataX](https://github.com/chuoinho/fermataX) (itself a fork of [Fermata](https://github.com/AndreyPavlenko/Fermata)). It keeps only YouTube and adds A/V sync that tunes itself.

- **Delayed video instead of earlier audio.** Audio that is already in the car's buffer can't be sped up, so the app holds the picture back. YouTube keeps playing normally. The frames are copied to a canvas and shown X ms later, so they line up with what you hear from the car's speakers.
- **Automatic delay measurement with the phone's microphone.** The app records about 10 seconds of what the car's speakers actually play. It compares that with the video's own soundtrack and finds the lag by cross-correlation. In auto mode this repeats on its own: about 4 seconds after a new video starts (short 6-second measurements, the first clear one is applied right away, so the picture is in sync within a minute, often sooner), after a buffering stall, and every 20 seconds (every minute once the delay has settled). Each new video starts with fresh measurements, because the lag differs from video to video. A single measurement is noisy (±20–30 ms), so fine-tuning uses the median of the last five measurements. A big jump, for example after a stall, needs two measurements that agree (one of them clear), or three of any kind. Changes are applied gradually so the picture doesn't jump.
- **One-time check with the phone camera.** The microphone can't hear everything: the phone adds its own lag to the measurement, and Android Auto delays the picture on its way to the car screen. A one-time check fixes that part: the car screen flashes and beeps, and the phone camera and microphone measure how far apart they really are. The result is kept as a correction on top of every later measurement.
- **Learns your correction.** If you nudge the delay by hand right after a measurement, it remembers the difference and applies it from then on.
- **Videos play on the car screen only.** Since v1.0.4-beta.1, xFermaTube on the phone is just the settings and the YouTube sign-in: no videos play there, so the phone and the car never fight over one player.
- **Separate settings for USB and wireless Android Auto.** These connections have different latency, so each keeps its own delay.
- **YouTube only.** On the car screen the app opens straight into YouTube. The car's side rail has just **Home, History, Back, Refresh, Settings**, and Settings is one short page: appearance, language, YouTube options (quality limit, ad skipping, SponsorBlock, auto fullscreen) and the sync badge. You can sign in to YouTube on the phone once, and the car uses the same session.
- **Much smaller.** The APK went from 185 MB to about 19 MB once the unused add-ons were removed (VLC, ExoPlayer, TV, radio, podcasts, audiobooks, SMB, SFTP).

## What it achieves

In my car (USB Android Auto), the measured audio lag was about 300 ms. With the video delayed by that amount, speech and picture match. The microphone measurement worked in the car without interrupting playback. Before, manual calibration with a sync test video was the only option. Now the app finds the value itself and keeps correcting it if the lag drifts.

## Open source, no tracking

I built this to fix one annoying problem for myself, not to make money or collect data.

- **Fully open source (GPL-3.0).** All the code is in this repo. You can read it and build the APK yourself.
- **No ads, analytics or tracking added.** There are no analytics or crash-reporting SDKs, no accounts and no xFermata server. Nothing is sent to me.
- **The microphone stays on the phone.** It is used only for the sync measurement and only while a video (or the sync check) is playing. The recording is turned into a loudness curve in memory to find the delay, and the audio is never saved or uploaded. See [`XfMicRecorder.java`](fermatax/modules/web/src/main/java/me/aap/fermata/addon/web/yt/XfMicRecorder.java).
- **The camera is used only during the one-time sync check.** It is asked for when you tap **Start**, not at install. Each frame is turned into one brightness number in memory to find the flashes; no picture is saved or uploaded. See [`XfCamSync.java`](fermatax/modules/web/src/main/java/me/aap/fermata/addon/web/yt/XfCamSync.java).
- **Few permissions.** The APK declares internet, microphone, notifications and media playback, plus a few routine ones (network and Wi-Fi state, wake lock, audio settings). Since v1.0.3-beta.3 it also declares the permission to request installs: **Fix now** uses it only to reinstall xFermaTube itself, it does nothing until you allow it in the phone's settings, and you can turn it off again afterwards. Since v1.0.3 it declares the camera, for the sync check only. Since v1.0.4-beta.1 it declares *Display over other apps* (Samsung: *Appear on top*). Since v1.0.4-beta.2 the app doesn't need it and no longer asks for it: it is an optional extra on Samsung phones only ([why](#the-car-picture-freezes-or-the-car-screen-turns-grey-newer-android-auto)), and it does nothing until you allow it. The location, storage, boot and system-settings permissions that FermataX declares are removed.
- **The update check asks GitHub only.** Since v1.0.4, once a day when you open the app on the phone, it asks GitHub for the latest release. Nothing is sent except that request; GitHub sees your IP address as with any download. You can turn it off: Settings → **Check for updates**. See [`XfUpdate.java`](fermatax/fermata/src/main/java/me/aap/fermata/xf/XfUpdate.java).
- **YouTube is the normal mobile YouTube site.** Google's own privacy policy applies, the same as in any browser.

## Install

Android Auto only shows video apps that look like they came from the Play Store. Opening the APK from the browser or a file manager is not enough on its own: Android Auto then shows only the audio entry.

Since **v1.0.3-beta.3** the app fixes this itself. You install the APK the normal way, and the app then asks the phone's own installer to install it once more, marked as coming from Google Play. No helper app, PC or Wi‑Fi is needed. I tested it in my car with a Samsung phone on Android 17: after a plain install the video app was missing, and after **Fix now** it showed up.

1. **Turn on Unknown sources in Android Auto.** On the phone: Android Auto settings → scroll down and tap **Version** 10 times → **⋮** (top right) → **Developer settings** → tick **Unknown sources**.
2. **Download the APK** from the [latest release](../../releases/latest) (arm64 phones only) and open it to install. If Google Play Protect asks about the app, let it scan or choose to install anyway.
3. **Open xFermaTube on the phone.** It says *Android Auto may hide the video app*. Tap **Fix now**, then:
   - **Open the setting** → turn on *Allow from this source* for xFermaTube → go back to the app. This is asked once.
   - **Reinstall now** → **Update** in the installer window.
   - Open xFermaTube again. It shows *Install source fixed*.
4. **Allow the microphone** when the app asks. The car screen can't show this request. You can also grant it later: Settings → Apps → xFermaTube → Permissions → Microphone.
5. **Connect to the car and open xFermaTube** from the Android Auto app list. If the phone was already connected, unplug it and connect again: Android Auto reads the app list when it connects.
6. **Do the one-time sync check** when the phone offers it: see [below](#the-one-time-sync-check).
7. Optional: open xFermaTube on the phone and tap **Sign in to YouTube**. The car uses the same session.

**Updates.** Since v1.0.4 the app tells you when a new version is out (on the phone, at most once a day). Tap **Update**: it downloads the new APK from GitHub, checks it, and hands it to the phone's installer the same way **Fix now** does, so the video app stays in Android Auto. You can also check by hand: Settings → **Check for updates now**. Pre-releases (betas) are never offered.

On older versions, open the new APK over the old one. Your settings are kept. The app may then ask for **Fix now** again, because the update changes the install record: do it the same way.

**With a helper app instead.** [AAenabler](https://github.com/malebuffy/AAEnabler) still works, and it is the way to install **v1.0.2** and older: open AAenabler and pick the APK from inside it, don't open the file directly. No **Fix now** is needed then. KingInstaller (the installer FermataX suggests, with Play Store as the install source) is an alternative I have not tested myself.

### Two icons in Android Auto

- **xFermaTube** (play icon): the video app. This is the one you want.
- **xF Audio** (speaker icon): audio only, for the media card and steering wheel controls. In v1.0.0 it was called *xFermaTube Audio* and had the same icon as the video app.

### Only the audio entry shows up?

Then Android Auto is hiding the video app. This usually comes from the way the APK was installed:

1. **Check the install record:** open xFermaTube on the phone → **Android Auto install check** (on older versions: gear icon in the top bar first). *Installer* must be `com.android.vending (Google Play Store)` and *Initiated by* must be the phone's package installer. The last line of the report says whether Android Auto accepts it.
2. **Not accepted?** On v1.0.3-beta.3 and later tap **Fix now**: see [the steps below](#fix-the-install-source-on-the-phone). On v1.0.2 and older, install the newer APK over it first, or uninstall and install through AAenabler.
3. Check that **Unknown sources** is ticked (step 1 above).
4. Unplug the phone and connect to the car again. Still missing? Force stop Android Auto on the phone and reconnect.
5. Look for the app on the **car screen**, not in the phone's *Customize launcher* list: without a connected car that list may show only *xF Audio* even when everything is fine.

Installing from a PC with `adb install -i com.android.vending` is not enough, although older versions of this page suggested it. Android Auto also checks who started the install, and in a real car it does not accept adb.

If the original FermataX doesn't show its video app in your car either, the problem is in the Android Auto setup, not in this app.

When you ask for help, tap **Copy** in the install check and paste the report: it contains no personal data (app version, phone model, Android Auto version, install source).

### The car picture freezes or the car screen turns grey (newer Android Auto)

A newer Android Auto (seen with 18.2) shows apps on the car screen in a new kind of window. That broke xFermaTube in three ways, and v1.0.3 has all of them:

- **The car screen stays black or keeps restarting.** The new window refuses a setting the app's theme used. Fixed since v1.0.4-beta.1.
- **The picture freezes while the sound keeps playing**, a few seconds after the phone is locked or shows another app. The phone doesn't count the new car window as visible, and Samsung phones (One UI) stop the screen drawing of apps that have no visible window, to save battery.
- **The car screen turns plain grey, the buttons are far too big, or they vanish when the car switches to night mode.** The new window describes itself with the phone's screen (its pixel density, portrait) instead of the car's, and switches to the car's values a moment later. The app laid everything out for the wrong screen.

Since v1.0.4-beta.2 the last two are fixed, with no extra permission:

- While the car screen is on, the app posts an empty input event to its own frame clock a few times a second. Samsung keeps drawing an app that is handling input, so the picture keeps moving with the phone locked. Only on Samsung phones.
- The app always lays the car screen out with the car display's own values. With an older Android Auto the two already agree, and nothing changes.

v1.0.4-beta.1 used an invisible 1×1 pixel window instead (*Display over other apps*, Samsung: *Appear on top*). That only helped while the phone was unlocked. The app no longer asks for it, and you can take the permission back in the phone's settings. On Samsung it is still there as an optional extra: Settings → **Extra help for a frozen car picture**.

I tested beta.2 with Android Auto's Desktop Head Unit on a Samsung phone with Android 17, also with the phone locked, and not yet in a real car. If your car picture still freezes or the screen looks wrong, open xFermaTube on the phone → **Android Auto install check** → **Copy**, and send me the report with your phone model and Android Auto version: it now notes when the frames stop.

### Fix the install source on the phone

For v1.0.3-beta.3 and later, Android 11 or later. No PC, Wi‑Fi or helper app is needed, and your settings and sign‑in are kept.

The app asks the phone's own installer to install it again, with Google Play Store recorded as the installer. This is the same record that AAenabler leaves. You confirm the install yourself with one tap.

1. Open xFermaTube on the phone and tap **Fix now**. If it doesn't ask: **Android Auto install check** in the settings → **Fix now**.
2. Tap **Open the setting**, turn on *Allow from this source* for xFermaTube and go back to the app. The phone's installer takes the request only from an app that is allowed to install apps. You can turn it off again when you are done.
3. Tap **Reinstall now**, then **Update** in the installer window. If Google Play Protect asks about the app, let it scan or choose to install anyway.
4. The app closes when it is replaced. Tap **Open** in the installer window or start xFermaTube again: it shows *Install source fixed*.
5. Connect to the car, or unplug and reconnect, and open xFermaTube on the car screen.

Confirmed in my car with a Samsung phone on Android 17. On some phones the installer may not keep the Google Play Store mark. The app notices that and offers a second way through the phone's Wireless debugging (Android 15 or later, needs Wi‑Fi, not on Samsung). If neither works, it tells you so: uninstall xFermaTube and install it through AAenabler. Please tell me how it went on your phone and car.

Xiaomi / HyperOS: if the phone blocks the second way, turn on **Install via USB** and **USB debugging (Security settings)** in Developer options and tap **Try again**.

## Using it in the car

### The one-time sync check

New in v1.0.3. In beta.3 you had to fix the last bit by ear with **−10 / +10**. Then I had a better idea: let the phone *watch* the car screen. It worked better than I hoped, so now it's a one-time step on first start.

Auto sync measures the delay through the phone's microphone, and two things stay hidden from it: the phone's own recording lag, and the extra time Android Auto needs to get the picture onto the car screen. The check measures exactly that, once:

1. The first time xFermaTube is open on the car screen, the phone offers **Sync sound and picture**. The offer appears on the phone, so open xFermaTube there while the car is connected.
2. Park the car, turn the volume up a bit and tap **Start**. Allow the camera and the microphone.
3. The car screen flashes white and beeps once a second. Point the phone's back camera at the car screen so it fills the middle of the picture, and keep the phone still: rest it against the steering wheel or the seat.
4. The phone waits until auto sync has settled, then measures for 10 seconds: when the flash is seen and when the beep is heard. It fixes the difference, checks once more and says **Done**.

It takes about a minute, and you only do it once. If you connect both by cable and wirelessly, it asks once for each.

After that you don't have to do anything: every new video syncs by itself. In my car, after the check, the next videos synced within seconds and the lips matched.

- Not now? Tap **Later** and it asks again next time you connect.
- Never want it? Tap **Don't ask again**.
- Prefer to tune by ear? The **−10 / +10** buttons on the ⏱ label still work.
- Want to redo it? Settings → **Sync sound and picture (camera)**, with the car connected.

**Sync is not instant.** The app can't know the delay in advance: it has to hear the car's speakers through the phone's microphone and compare that with the video's soundtrack. For each new video it can take up to a minute before sound and picture line up. It can take longer when the audio is quiet, the car is noisy, or the music is very rhythmic. Until then the video uses the delay from the previous video. The sound must be audible in the cabin: with the volume at zero there is nothing to measure.

Tap the faint **⏱ X ms** label at the top left of the video to see the controls:

- **−10 / +10** fine-tune the delay by hand. Do it right after a measurement: the app remembers your correction, applies it to every later measurement and shows it in the label, for example **⏱ 480 ms (+30)**. The buttons are greyed out while the microphone is measuring.
- **Auto** turns automatic measurement on or off (green = on).

**Why there is a correction.** The measurement itself is not free of delay. The sound travels from the car's speakers to the phone, and the phone's microphone and audio system need some time before the app gets the recording. The app can't see that time, so the measured value can be off by a few tens of milliseconds. The error is about the same every time, which is why one correction is enough. The [one-time sync check](#the-one-time-sync-check) sets it with the camera; if you skipped it, set it with **−10 / +10** until lips and sound match. The app adds it to every later measurement. The number in the label is the delay in use, the number in brackets is the correction.

The panel used to have **−100 / +100** and a **🎤** button for a single measurement. I removed them: auto sync does the measuring, and big manual steps only fought with it. After you seek, the app measures again about 3 seconds later.

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
| One-time camera sync check (wizard) | `fermatax/modules/web/.../yt/XfCamSync.java` |
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
