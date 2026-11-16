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
final class XfMicRecorder {
	private static final int BIN_MS = 10;
	private static volatile boolean busy;

	private XfMicRecorder() {
	}

	/**
	 * Paleidžia įrašą fone; rezultatas perduodamas į window.__xfDelay._mic(json).
	 *
	 * @param arg sekundės, pasirinktinai su „;ask“ (ranka paleistas matavimas: telefone be leidimo
	 *            parodoma leidimo užklausa; šis matavimas vis tiek grąžina klaidą, kitas jau veiks).
	 */
	static void record(YoutubeWebView web, String arg) {
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
		busy = true;
		Thread t = new Thread(() -> {
			String json;
			try {
				json = run(ctx, seconds);
			} catch (Throwable ex) {
				Log.e(ex, "xF mic measure failed");
				json = err(ex.getClass().getSimpleName());
			} finally {
				busy = false;
			}
			String js = "window.__xfDelay&&window.__xfDelay._mic(" + json + ")";
			web.post(() -> web.evaluateJavascript(js, null));
		}, "xf-mic");
		t.setDaemon(true);
		t.start();
	}

	/** Leidimo užklausa tik telefone: automobilio ekrane Android Auto jos parodyti negali. */
	private static void askPermission(YoutubeWebView web) {
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

	private static String run(Context ctx, double seconds) {
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

		try {
			int bin = sr * BIN_MS / 1000;
			int bins = (int) Math.round(seconds * 1000 / BIN_MS);
			float[] env = new float[bins];
			short[] buf = new short[bin];
			AudioTimestamp ts = new AudioTimestamp();
			long tsFrame = -1, tsNano = 0, firstReadNano = 0;

			rec.startRecording();
			if (rec.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING)
				return err("microphone busy");

			for (int i = 0; i < bins; i++) {
				int got = 0;
				while (got < bin) {
					int n = rec.read(buf, got, bin - got);
					if (n <= 0) return err("read error " + n);
					got += n;
				}
				if (i == 0) firstReadNano = System.nanoTime();
				double sum = 0;
				for (short s : buf) {
					double v = s / 32768.0;
					sum += v * v;
				}
				env[i] = (float) Math.sqrt(sum / bin);
				// Tikslus kadro laikas iš garso tvarkyklės (paimamas kartą, kai stabilizuojasi).
				if ((tsFrame < 0) && (i >= 20) &&
						(rec.getTimestamp(ts, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS)) {
					tsFrame = ts.framePosition;
					tsNano = ts.nanoTime;
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
			double t0 = System.currentTimeMillis() - (System.nanoTime() - t0Nano) / 1e6;

			String dev = "?";
			AudioDeviceInfo d = rec.getRoutedDevice();
			if (d != null) dev = d.getProductName() + "/" + typeName(d.getType());

			StringBuilder sb = new StringBuilder(bins * 8 + 128);
			sb.append(String.format(Locale.ROOT, "{\"t0\":%.3f,\"bin\":%d,\"sr\":%d,\"ts\":%b,\"dev\":%s,\"env\":[",
					t0, BIN_MS, sr, tsFrame >= 0, quote(dev)));
			for (int i = 0; i < bins; i++) {
				if (i > 0) sb.append(',');
				sb.append(String.format(Locale.ROOT, "%.5f", env[i]));
			}
			return sb.append("]}").toString();
		} finally {
			try {
				rec.stop();
			} catch (Exception ignored) {
			}
			rec.release();
		}
	}

	private static String typeName(int type) {
		return switch (type) {
			case AudioDeviceInfo.TYPE_BUILTIN_MIC -> "telefonas";
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
