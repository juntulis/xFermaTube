package me.aap.fermata.addon.web.yt;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTimestamp;
import android.media.AudioTrack;

import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * xFermaTube: how late the app's sound reaches the car speakers, measured with the app's
 * own short signal instead of the video sound, so it works the same for every site (DRM or not).
 *
 * <p>While the microphone records (the 🎤 measurement), a short pattern of three chirps is played
 * through an ordinary media AudioTrack (mixed with the video sound, no audio focus). Android tells
 * when the pattern left the phone (AudioTrack timestamp); the microphone recording tells when it
 * came out of the speakers. The difference is the link delay that Android Auto adds and does not
 * report. Inaudible mode uses 18–19.5 kHz (most adults do not hear it); audible mode 2–4 kHz, for
 * cars or phones that cut high tones.</p>
 */
final class XfLinkProbe {
	static final int OFF = 0, INAUDIBLE = 1, AUDIBLE = 2;
	private static final int SR = 48000;
	private static final int CHIRP_MS = 50;
	private static final int[] AT_MS = {0, 370, 830};   // irregular: echoes and repeats do not fit
	private static final int LEAD_MS = 100;
	// Car Wi-Fi seen up to ~1 s (Taycan, 2026-11-23); the search goes well beyond it
	private static final int SEARCH_FROM_MS = -150, SEARCH_TO_MS = 2500;
	static final int START_MS = 800;     // after the recording starts
	static final double MIN_SEC = 4.5;   // shorter recordings cannot hold the signal and its late arrival

	final int mode;
	private final AtomicBoolean cancel;
	private volatile long emitNano;  // monotonic time the pattern's first frame left the phone
	private volatile String err;
	private Thread thread;

	XfLinkProbe(int mode, AtomicBoolean cancel) {
		this.mode = mode;
		this.cancel = cancel;
	}

	/** Mic sample rate needed to hear the pattern at all. */
	boolean canHear(int micSr) {
		return (mode != INAUDIBLE) || (micSr >= 44100);
	}

	void start(long delayMs) {
		thread = new Thread(() -> {
			try {
				play(delayMs);
			} catch (Throwable ex) {
				err = "play: " + ex.getClass().getSimpleName();
			}
		}, "xf-probe");
		thread.setDaemon(true);
		thread.start();
	}

	void join() {
		if (thread == null) return;
		try {
			thread.join(4000);
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
	}

	private double f0() {
		return (mode == AUDIBLE) ? 2000 : 18000;
	}

	private double f1() {
		return (mode == AUDIBLE) ? 4000 : 19500;
	}

	/** The pattern (amplitude 1) at the given sample rate. */
	private float[] pattern(int sr) {
		int chirp = sr * CHIRP_MS / 1000;
		float[] p = new float[sr * AT_MS[AT_MS.length - 1] / 1000 + chirp];
		double f0 = f0(), k = (f1() - f0) / (CHIRP_MS / 1000.0);
		int taper = chirp / 4;
		for (int at : AT_MS) {
			int off = sr * at / 1000;
			for (int i = 0; i < chirp; i++) {
				double t = (double) i / sr;
				double w = 1;   // Tukey window: no clicks (a click would be heard at any frequency)
				if (i < taper) w = 0.5 * (1 - Math.cos(Math.PI * i / taper));
				else if (i >= chirp - taper) w = 0.5 * (1 - Math.cos(Math.PI * (chirp - 1 - i) / taper));
				p[off + i] = (float) (w * Math.sin(2 * Math.PI * (f0 * t + 0.5 * k * t * t)));
			}
		}
		return p;
	}

	private void play(long delayMs) throws InterruptedException {
		long until = System.nanoTime() + delayMs * 1_000_000L;
		while (System.nanoTime() < until) {
			if (cancel.get()) return;
			Thread.sleep(20);
		}
		float[] p = pattern(SR);
		int lead = SR * LEAD_MS / 1000;
		short[] data = new short[lead + p.length + SR / 5];
		double amp = 32767 * ((mode == AUDIBLE) ? 0.05 : 0.1);  // −26 / −20 dBFS
		for (int i = 0; i < p.length; i++) data[lead + i] = (short) Math.round(p[i] * amp);

		int min = AudioTrack.getMinBufferSize(SR, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
		AudioTrack t = new AudioTrack.Builder()
				.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
						.setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
				.setAudioFormat(new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
						.setSampleRate(SR).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
				.setTransferMode(AudioTrack.MODE_STREAM)
				.setBufferSizeInBytes(Math.max(min, SR)).build();
		try {
			if (t.getState() != AudioTrack.STATE_INITIALIZED) {
				err = "track not initialized";
				return;
			}
			t.play();
			int w = t.write(data, 0, data.length, AudioTrack.WRITE_BLOCKING);
			if (w < data.length) {
				err = "write " + w;
				return;
			}
			// The newest timestamp after the pattern has played: the frame clock is the most exact then.
			AudioTimestamp ts = new AudioTimestamp();
			long tsFrame = -1, tsNano = 0;
			long deadline = System.nanoTime() + 3_000_000_000L;
			while (System.nanoTime() < deadline) {
				if (t.getTimestamp(ts) && (ts.framePosition > 0)) {
					tsFrame = ts.framePosition;
					tsNano = ts.nanoTime;
					if (tsFrame >= lead + p.length) break;
				}
				Thread.sleep(20);
			}
			if (tsFrame < 0) {
				err = "no track timestamp";
				return;
			}
			emitNano = tsNano + Math.round((lead - tsFrame) * 1e9 / SR);
		} finally {
			try {
				t.stop();
			} catch (Exception ignored) {
			}
			t.release();
		}
	}

	/**
	 * Finds the pattern in the microphone recording.
	 *
	 * @return a log/JSON-ready result, or null with {@link #error()} set
	 */
	Result find(short[] mic, int micSr, long micT0Nano) {
		if (err != null) return null;
		if (emitNano == 0) {
			err = "not played";
			return null;
		}
		if (!canHear(micSr)) {
			err = "mic rate " + micSr;
			return null;
		}
		float[] tmpl = pattern(micSr);
		long s = Math.round((emitNano - micT0Nano + SEARCH_FROM_MS * 1_000_000L) * micSr / 1e9);
		int span = micSr * (SEARCH_TO_MS - SEARCH_FROM_MS) / 1000;
		int from = (int) Math.max(0, s);
		int to = (int) Math.min(mic.length, s + span + tmpl.length);
		if (to - from < tmpl.length + micSr / 10) {
			err = "outside the recording";
			return null;
		}
		int len = to - from;
		int n = Integer.highestOneBit(len + tmpl.length - 1) << 1;
		double[] xr = new double[n], xi = new double[n], tr = new double[n], ti = new double[n];
		for (int i = 0; i < len; i++) xr[i] = mic[from + i] / 32768.0;
		for (int i = 0; i < tmpl.length; i++) tr[i] = tmpl[i];
		fft(xr, xi, false);
		fft(tr, ti, false);
		// X·conj(T) only in the pattern's band, positive frequencies doubled: the inverse is the
		// analytic correlation, its magnitude a smooth envelope (no carrier ripple).
		int lo = (int) Math.floor((f0() - 500) * n / micSr), hi = (int) Math.ceil((f1() + 500) * n / micSr);
		hi = Math.min(hi, n / 2 - 1);
		// Whitening (PHAT on the recording, smoothed over ±W bins): a loud note of the video sound in
		// the same band no longer outweighs the quiet signal.
		int wb = Math.max(2, (int) (8L * n / micSr));   // ±8 Hz
		int half = n / 2;
		double[] cum = new double[half + 2];   // cum[k] = |X[0]| + … + |X[k-1]|
		for (int k = 0; k <= half; k++) cum[k + 1] = cum[k] + Math.hypot(xr[k], xi[k]);
		double[] wgt = new double[n];
		for (int k = Math.max(0, lo); k <= hi; k++) {
			int a = Math.max(0, k - wb), b = Math.min(half, k + wb);
			double avg = (cum[b + 1] - cum[a]) / (b - a + 1);
			wgt[k] = (avg > 0) ? 1 / avg : 0;
		}
		for (int k = 0; k < n; k++) {
			if ((k >= lo) && (k <= hi)) {
				double re = xr[k] * tr[k] + xi[k] * ti[k];
				double im = xi[k] * tr[k] - xr[k] * ti[k];
				xr[k] = 2 * re * wgt[k];
				xi[k] = 2 * im * wgt[k];
			} else {
				xr[k] = 0;
				xi[k] = 0;
			}
		}
		fft(xr, xi, true);
		int lags = len - tmpl.length + 1;
		double[] env = new double[lags];
		int best = 0;
		for (int k = 0; k < lags; k++) {
			env[k] = Math.hypot(xr[k], xi[k]);
			if (env[k] > env[best]) best = k;
		}
		int guard = micSr * 30 / 1000;
		double second = 0;
		for (int k = 0; k < lags; k++) {
			if ((Math.abs(k - best) > guard) && (env[k] > second)) second = env[k];
		}
		double[] sorted = env.clone();
		Arrays.sort(sorted);
		double median = sorted[lags / 2];
		Result r = new Result();
		double arrivalNano = micT0Nano + (from + best) * 1e9 / micSr;
		r.ms = (arrivalNano - emitNano) / 1e6;
		r.conf = (median > 0) ? env[best] / median : 0;
		r.ratio = (second > 0) ? env[best] / second : 99;
		// The level the microphone heard the pattern at (dBFS of the template-normalised peak)
		// (plain correlation at the found lag, ± a carrier period: the whitened one has no scale)
		double tEnergy = 0, dot = 0;
		for (float v : tmpl) tEnergy += v * v;
		for (int d = -3; d <= 3; d++) {
			int at = from + best + d;
			if ((at < 0) || (at + tmpl.length > mic.length)) continue;
			double sum = 0;
			for (int i = 0; i < tmpl.length; i++) sum += mic[at + i] / 32768.0 * tmpl[i];
			dot = Math.max(dot, Math.abs(sum));
		}
		r.levelDb = 20 * Math.log10(Math.max(1e-9, dot / tEnergy));
		r.mode = (mode == AUDIBLE) ? "2-4k" : "18-19.5k";
		return r;
	}

	String error() {
		return err;
	}

	static final class Result {
		double ms, conf, ratio, levelDb;
		String mode;

		/** Trustworthy: a clear single peak well above the rest of the search span. */
		boolean ok() {
			// (synthetic tests: every wrong peak had peak/2nd ≤ 1.08 and peak/median ≤ 3.7)
			return (conf >= 4.5) && (ratio >= 1.25);
		}

		@Override
		public String toString() {
			return String.format(Locale.ROOT, "%.0f ms (%s, peak/median %.1f, peak/2nd %.2f, level %.0f dB%s)",
					ms, mode, conf, ratio, levelDb, ok() ? "" : ", unsure");
		}

		String json() {
			return String.format(Locale.ROOT,
					"{\"ms\":%.1f,\"conf\":%.1f,\"ratio\":%.2f,\"db\":%.1f,\"ok\":%b,\"mode\":\"%s\"}",
					ms, conf, ratio, levelDb, ok(), mode);
		}
	}

	/** In-place radix-2 FFT (n a power of two); inverse scaled by 1/n. */
	static void fft(double[] re, double[] im, boolean inverse) {
		int n = re.length;
		for (int i = 1, j = 0; i < n; i++) {
			int bit = n >> 1;
			for (; (j & bit) != 0; bit >>= 1) j ^= bit;
			j ^= bit;
			if (i < j) {
				double t = re[i];
				re[i] = re[j];
				re[j] = t;
				t = im[i];
				im[i] = im[j];
				im[j] = t;
			}
		}
		for (int size = 2; size <= n; size <<= 1) {
			double ang = 2 * Math.PI / size * (inverse ? 1 : -1);
			double wr = Math.cos(ang), wi = Math.sin(ang);
			int half = size >> 1;
			for (int i = 0; i < n; i += size) {
				double cr = 1, ci = 0;
				for (int k = 0; k < half; k++) {
					int a = i + k, b = a + half;
					double vr = re[b] * cr - im[b] * ci, vi = re[b] * ci + im[b] * cr;
					re[b] = re[a] - vr;
					im[b] = im[a] - vi;
					re[a] += vr;
					im[a] += vi;
					double nr = cr * wr - ci * wi;
					ci = cr * wi + ci * wr;
					cr = nr;
				}
			}
		}
		if (inverse) {
			for (int i = 0; i < n; i++) {
				re[i] /= n;
				im[i] /= n;
			}
		}
	}
}
