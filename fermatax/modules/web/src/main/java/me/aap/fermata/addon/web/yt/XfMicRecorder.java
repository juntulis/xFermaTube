package me.aap.fermata.addon.web.yt;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioTimestamp;
import android.media.MediaRecorder;

import androidx.core.content.ContextCompat;

import java.util.Locale;

import me.aap.fermata.addon.web.FermataWebView;
import me.aap.fermata.ui.activity.MainActivityDelegate;
import me.aap.utils.log.Log;

/**
 * xFermata: mikrofono įrašas A/V delsos matavimui.
 *
 * <p>Naršyklės getUserMedia Android'e elgiasi kaip pokalbis (paima garso fokusą), todėl Android Auto
 * sustabdo video. AudioRecord fokuso neima ir garso režimo nekeičia: video groja toliau.</p>
 *
 * <p>Į puslapį grąžinama tik garsumo kreivė (RMS kas 10 ms) ir pirmo įrašo kadro laikas
 * (epochos ms), ne pats garsas.</p>
 */
public final class XfMicRecorder {
	private static final int BIN_MS = 10;
	static volatile boolean busy;   // ir kameros testui (XfCamSync): mikrofonas vienas

	private XfMicRecorder() {
	}

	/**
	 * Paleidžia įrašą fone; rezultatas perduodamas į window.__xfDelay._mic(json).
	 *
	 * @param arg sekundės, pasirinktinai su „;ask“ (ranka paleistas matavimas: telefone be leidimo
	 *            parodoma leidimo užklausa; šis matavimas vis tiek grąžina klaidą, kitas jau veiks).
	 */
	public static void record(FermataWebView web, String arg) {
		record(web, arg, null);
	}

	/**
	 * @param deliver asked on the main thread when the recording is done: false = the result is
	 *                dropped (the page that asked for it is gone). null = always delivered.
	 */
	public static void record(FermataWebView web, String arg,
			@androidx.annotation.Nullable java.util.function.BooleanSupplier deliver) {
		if (busy) return;
		String secondsArg = (arg == null) ? "" : arg;
		int sep = secondsArg.indexOf(';');
		if (sep >= 0) {
			if (secondsArg.substring(sep + 1).equals("ask")) askPermission(web);
			secondsArg = secondsArg.substring(0, sep);
		}
		double sec;
		try {
			sec = Double.parseDouble(secondsArg);
		} catch (Exception ex) {
			sec = 11.5;
		}
		double seconds = Math.max(3, Math.min(20, sec));
		Context ctx = web.getContext().getApplicationContext();
		int probe = probeMode();
		busy = true;
		Thread t = new Thread(() -> {
			String json;
			try {
				json = run(ctx, seconds, probe);
			} catch (Throwable ex) {
				Log.e(ex, "xF mic measure failed");
				json = err(ex.getClass().getSimpleName());
			} finally {
				busy = false;
			}
			String js = "window.__xfDelay&&window.__xfDelay._mic(" + json + ")";
			web.post(() -> {
				if ((deliver == null) || deliver.getAsBoolean()) web.evaluateJavascript(js, null);
			});
		}, "xf-mic");
		t.setDaemon(true);
		t.start();
	}

	/** The measurement is not made: the page is told why, so it does not wait for a result. */
	public static void refuse(FermataWebView web, String msg) {
		String js = "window.__xfDelay&&window.__xfDelay._mic(" + err(msg) + ")";
		web.post(() -> web.evaluateJavascript(js, null));
	}

	/** Leidimo užklausa tik telefone: automobilio ekrane Android Auto jos parodyti negali. */
	private static void askPermission(FermataWebView web) {
		try {
			Context ctx = web.getContext();
			if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) ==
					PackageManager.PERMISSION_GRANTED) return;
			MainActivityDelegate a = MainActivityDelegate.get(ctx);
			if ((a == null) || a.isCarActivity()) return;
			a.getAppActivity().checkPermissions(Manifest.permission.RECORD_AUDIO).onFailure(err ->
					Log.e(err, "Failed to request RECORD_AUDIO permission"));
		} catch (RuntimeException ex) {
			Log.e(ex, "xF mic permission request failed");
		}
	}

	/** The "Sound link test" setting (XfLinkProbe), OFF when it cannot be read. */
	private static int probeMode() {
		try {
			YoutubeAddon yt = me.aap.fermata.addon.AddonManager.get().getAddon(YoutubeAddon.class);
			return (yt == null) ? XfLinkProbe.OFF : yt.xfLinkProbe();
		} catch (RuntimeException ex) {
			return XfLinkProbe.OFF;
		}
	}

	private static String run(Context ctx, double seconds, int probeMode) {
		if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) !=
				PackageManager.PERMISSION_GRANTED) return err("no microphone permission");

		AudioRecord rec = null;
		int sr = 0;
		for (int rate : new int[]{48000, 44100, 16000}) {
			int min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO,
					AudioFormat.ENCODING_PCM_16BIT);
			if (min <= 0) continue;
			try {
				AudioRecord r = new AudioRecord(MediaRecorder.AudioSource.MIC, rate,
						AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(min, rate / 2));
				if (r.getState() == AudioRecord.STATE_INITIALIZED) {
					rec = r;
					sr = rate;
					break;
				}
				r.release();
			} catch (Exception ignored) {
			}
		}
		if (rec == null) return err("microphone unavailable");

		int bins = (int) Math.round(seconds * 1000 / BIN_MS);
		java.util.concurrent.atomic.AtomicBoolean cancel = new java.util.concurrent.atomic.AtomicBoolean();
		// xFermaTube: the app's own short sync signal during the same recording (XfLinkProbe): how late
		// the car plays the app's sound, whatever the page is (it also works where the page cannot
		// hand out its video sound: DRM, other-origin streams).
		XfLinkProbe probe = null;
		String probeErr = null;
		if (probeMode == XfLinkProbe.OFF) probeErr = "off";
		else if (seconds < XfLinkProbe.MIN_SEC) probeErr = "recording too short";
		else {
			XfLinkProbe p = new XfLinkProbe(probeMode, cancel);
			if (p.canHear(sr)) {
				probe = p;
				probe.start(XfLinkProbe.START_MS);
			} else probeErr = "mic rate " + sr;
		}
		try {
			Track mic = new Track();
			if (probe != null) mic.raw = new short[bins * (sr * BIN_MS / 1000)];
			String e = mic.record(rec, sr, bins, null);
			if (e != null) return err(e);
			String probeJson = (probeErr != null) ? ",\"probeErr\":" + quote(probeErr) : "";
			if (probe != null) {
				probe.join();
				XfLinkProbe.Result pr = probe.find(mic.raw, sr, mic.t0Nano);
				mic.raw = null;
				if (pr != null) {
					me.aap.fermata.xf.XfLog.i("xfProbe", "link " + pr + (mic.ts ? "" : ", mic time from reads"));
					probeJson = ",\"probe\":" + pr.json();
				} else {
					me.aap.fermata.xf.XfLog.i("xfProbe", "link not measured: " + probe.error());
					probeJson = ",\"probeErr\":" + quote(String.valueOf(probe.error()));
				}
			}

			String dev = "?";
			AudioDeviceInfo d = rec.getRoutedDevice();
			if (d != null) dev = d.getProductName() + "/" + typeName(d.getType());

			StringBuilder sb = new StringBuilder(bins * 16 + 256);
			sb.append(String.format(Locale.ROOT, "{\"t0\":%.3f,\"bin\":%d,\"sr\":%d,\"ts\":%b,\"dev\":%s,\"env\":",
					mic.t0, BIN_MS, sr, mic.ts, quote(dev)));
			mic.appendEnv(sb);
			sb.append(probeJson);
			return sb.append('}').toString();
		} finally {
			// The signal ends with the measurement (also when the microphone failed): the next one
			// starts only after it is released (busy stays set until then).
			cancel.set(true);
			if (probe != null) probe.join();
			try {
				rec.stop();
			} catch (Exception ignored) {
			}
			rec.release();
		}
	}

	/** A loudness curve (RMS every BIN_MS) and the time of its first sample (epoch ms). */
	private static final class Track {
		float[] env;
		double t0;
		long t0Nano;     // the first sample's monotonic time
		boolean ts;
		short[] raw;     // when set (bins × bin samples): the samples themselves are kept too

		/** @return null when done, or why not */
		String record(AudioRecord rec, int sr, int bins,
				@androidx.annotation.Nullable java.util.concurrent.atomic.AtomicBoolean cancel) {
			int bin = sr * BIN_MS / 1000;
			float[] e = new float[bins];
			short[] buf = new short[bin];
			AudioTimestamp ats = new AudioTimestamp();
			long tsFrame = -1, tsNano = 0, firstReadNano = 0;

			rec.startRecording();
			if (rec.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) return "busy";

			for (int i = 0; i < bins; i++) {
				if ((cancel != null) && cancel.get()) return "cancelled";
				int got = 0;
				while (got < bin) {
					int n = rec.read(buf, got, bin - got);
					if (n <= 0) return "read error " + n;
					got += n;
				}
				if (i == 0) firstReadNano = System.nanoTime();
				if (raw != null) System.arraycopy(buf, 0, raw, i * bin, bin);
				double sum = 0;
				for (short s : buf) {
					double v = s / 32768.0;
					sum += v * v;
				}
				e[i] = (float) Math.sqrt(sum / bin);
				// Tikslus kadro laikas iš garso tvarkyklės (paimamas kartą, kai stabilizuojasi).
				if ((tsFrame < 0) && (i >= 20) &&
						(rec.getTimestamp(ats, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS)) {
					tsFrame = ats.framePosition;
					tsNano = ats.nanoTime;
				}
			}

			// Pirmo įrašyto kadro laikas monotoniniu laikrodžiu, tada epochos ms (kaip JS Date.now()).
			long readNano = firstReadNano - (long) BIN_MS * 1_000_000L;
			long t0Nano = (tsFrame >= 0) ? tsNano - Math.round(tsFrame * 1e9 / sr) : readNano;
			// Kai kurios tvarkyklės framePosition skaičiuoja ne nuo įrašo pradžios: tada laikas nepatikimas.
			if (Math.abs(t0Nano - readNano) > 300_000_000L) {
				t0Nano = readNano;
				tsFrame = -1;
			}
			t0 = System.currentTimeMillis() - (System.nanoTime() - t0Nano) / 1e6;
			this.t0Nano = t0Nano;
			ts = tsFrame >= 0;
			env = e;
			return null;
		}

		void appendEnv(StringBuilder sb) {
			sb.append('[');
			for (int i = 0; i < env.length; i++) {
				if (i > 0) sb.append(',');
				sb.append(String.format(Locale.ROOT, "%.5f", env[i]));
			}
			sb.append(']');
		}
	}

	private static String typeName(int type) {
		return switch (type) {
			case AudioDeviceInfo.TYPE_BUILTIN_MIC -> "phone";
			case AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "bluetooth";
			case AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET -> "usb";
			case AudioDeviceInfo.TYPE_BUS -> "bus";
			case AudioDeviceInfo.TYPE_REMOTE_SUBMIX -> "submix";
			default -> "tipas " + type;
		};
	}

	private static String err(String msg) {
		return "{\"err\":" + quote(msg) + "}";
	}

	private static String quote(String s) {
		StringBuilder sb = new StringBuilder("\"");
		for (char c : s.toCharArray()) {
			if ((c == '"') || (c == '\\')) sb.append('\\').append(c);
			else if (c < 0x20) sb.append(' ');
			else sb.append(c);
		}
		return sb.append('"').toString();
	}
}
