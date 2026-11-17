package me.aap.fermata.addon.web.yt;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioTimestamp;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Range;
import android.util.Size;
import android.util.TypedValue;
import android.view.Surface;
import android.view.TextureView;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

import me.aap.fermata.ui.activity.MainActivityDelegate;
import me.aap.utils.log.Log;

/**
 * xFermaTube: vienkartinis garso ir vaizdo suderinimas telefono kamera (pirmo paleidimo vedlys).
 *
 * <p>Vedlys pats paleidžia automobilyje testo video: kas sekundę baltas blyksnis ir pyptelėjimas tuo pačiu
 * metu. Palaukia, kol automobilio 🎤 auto sync susiderina, tada telefono kamera žiūri į automobilio ekraną, o
 * mikrofonas klauso garsiakalbių. Abiejų laikai imami iš tvarkyklių (kameros SENSOR_TIMESTAMP, mikrofono
 * AudioTimestamp), todėl skirtumas „pyptelėjimas − blyksnis“ yra tas, kurį mato akys ir girdi ausys, įskaitant
 * Android Auto vaizdo vėlinimą, kurio 🎤 matavimas nemato. Rezultatas pritaikomas automobiliui
 * (YoutubeAddon.xfPutCamCorr) ir patikrinamas dar kartą; baigus ryšys (laidas / belaidis) pažymimas.</p>
 */
final class XfCamSync {
	private static final int REC_SEC = 10;
	private static final int SR = 48000;
	private static final int ENV_BIN = SR / 1000;   // garsumo kreivė kas 1 ms
	private static final int OK_MS = 20;            // tiek ms skirtumo jau nesimato
	private static final int MAX_ROUNDS = 3;
	private static final int MAX_FAILS = 3;
	// Pavienių porų sklaida (mediana nuo medianos). Iš ~10 porų medianos paklaida maždaug sklaida / 3,
	// todėl ±50 ms dar duoda ~±15 ms tikslumą; DHU prie PC buvo ±32 ms, didesnė – kamera judėjo ar trūko pyptelėjimų.
	private static final int MAX_SPREAD = 50;

	private static final int ST_INTRO = 0, ST_TUNING = 1, ST_AIM = 2, ST_MEASURING = 3, ST_SETTLE = 4,
			ST_DONE = 5, ST_FAILED = 6;

	private final Context ctx;
	private final MainActivityDelegate act;
	private final YoutubeAddon addon;
	private final boolean offered;   // pasiūlytas pačios programos (ne iš nustatymų)
	private final Handler main = new Handler(Looper.getMainLooper());
	private HandlerThread thread;
	private Handler bg;
	private AlertDialog dialog;
	private TextView info;
	private TextureView preview;
	private TextView status;
	private TextView liveText;
	private int stage = ST_INTRO;
	private long stageAt;
	private int round, fails, flashTicks;
	private boolean statePending, startedTest, done;
	private String conn = "";
	private volatile boolean flashesSeen;
	private volatile boolean lastOk;
	private volatile double lastSpread;
	private final Runnable tick = this::tick;
	private CameraDevice camera;
	private CameraCaptureSession session;
	private CaptureRequest.Builder request;
	private ImageReader reader;
	private boolean realtimeClock;
	private int fps;
	private String fpsText = "";
	private volatile boolean closed;
	private volatile boolean recording;
	private int lastCorr;

	// Kameros kadrai (tik bg gijoje)
	private final ConcurrentHashMap<Long, long[]> meta = new ConcurrentHashMap<>();
	private final List<long[]> frames = new ArrayList<>();      // {ts, exp, skew}
	private final List<Float> lumas = new ArrayList<>();
	private final float[] live = new float[90];
	private int liveN;
	private long liveShownAt;

	private XfCamSync(MainActivityDelegate a, YoutubeAddon addon, boolean offered) {
		this.ctx = a.getContext();
		this.act = a;
		this.addon = addon;
		this.offered = offered;
	}

	/** @param offered true – pasiūlė pati programa (prisijungus automobiliui), false – iš nustatymų. */
	static void show(MainActivityDelegate a, YoutubeAddon addon, boolean offered) {
		addon.xfCalOpened();
		new Handler(Looper.getMainLooper()).post(() -> {
			try {
				new XfCamSync(a, addon, offered).open();
			} catch (RuntimeException ex) {
				Log.e(ex, "xF sync wizard");
				addon.xfCalClosed(false);
			}
		});
	}

	private int dp(int v) {
		return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
				ctx.getResources().getDisplayMetrics()));
	}

	private static String connName(String conn) {
		return "usb".equals(conn) ? "cable" : "wireless";
	}

	private void open() {
		LinearLayout box = new LinearLayout(ctx);
		box.setOrientation(LinearLayout.VERTICAL);
		box.setPadding(dp(20), dp(8), dp(20), 0);
		info = new TextView(ctx);
		info.setText("""
				Android Auto shows the picture a bit later than you hear the sound, and every car is \
				different. This one-time check measures your car with the phone camera and fixes it \
				(about a minute). After that, auto sync keeps every video in sync by itself.

				1. Park the car and keep xFermaTube open on the car screen. Turn the volume up a bit.
				2. Tap Start: the car screen flashes and beeps once a second.
				3. Point the phone's back camera at the car screen and hold still until it says Done.""");
		box.addView(info);
		preview = new TextureView(ctx);
		LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
				LinearLayout.LayoutParams.MATCH_PARENT, dp(220));
		lp.topMargin = dp(10);
		preview.setVisibility(android.view.View.GONE);
		box.addView(preview, lp);
		liveText = new TextView(ctx);
		liveText.setPadding(0, dp(6), 0, 0);
		liveText.setAlpha(0.7f);
		box.addView(liveText);
		status = new TextView(ctx);
		status.setPadding(0, dp(8), 0, dp(4));
		status.setTextIsSelectable(true);
		box.addView(status);
		android.widget.ScrollView scroll = new android.widget.ScrollView(ctx);
		scroll.addView(box);

		AlertDialog.Builder b = new AlertDialog.Builder(ctx)
				.setTitle("Sync sound and picture")
				.setView(scroll)
				.setPositiveButton("Start", null)
				.setNegativeButton(offered ? "Later" : "Close", null);
		if (offered) b.setNeutralButton("Don't ask again", null);
		dialog = b.create();
		dialog.setCanceledOnTouchOutside(false);
		dialog.setOnShowListener(d -> {
			dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> onPositive());
			Button never = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
			if (never != null) never.setOnClickListener(v -> {
				addon.xfCalMark("never");
				dialog.dismiss();
			});
		});
		dialog.setOnDismissListener(d -> close());
		dialog.show();

		thread = new HandlerThread("xf-cam");
		thread.start();
		bg = new Handler(thread.getLooper());
		preview.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
			@Override
			public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
				openCamera(st);
			}

			@Override
			public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) {
				fitPreview();
			}

			@Override
			public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
				return true;
			}

			@Override
			public void onSurfaceTextureUpdated(SurfaceTexture st) {
			}
		});
	}

	private volatile Size previewSize;
	private volatile int previewSensorDeg;

	/**
	 * TextureView ištempia kameros kadrą per visą rėmelį. Kadras jau pasuktas pagal ekraną, todėl stačiame
	 * telefone jis status (9:16), o rėmelis platus: apkerpam nuo centro, kad proporcijos liktų tikros
	 * (matuojamas irgi kadro vidurys).
	 */
	private void fitPreview() {
		Size pv = previewSize;
		if ((pv == null) || (preview == null)) return;
		float vw = preview.getWidth(), vh = preview.getHeight();
		if ((vw <= 0) || (vh <= 0)) return;
		int rot = 0;
		try {
			android.view.Display d = preview.getDisplay();
			if (d != null) rot = d.getRotation() * 90;
		} catch (RuntimeException ignored) {
		}
		boolean swap = ((previewSensorDeg - rot + 360) % 180) != 0;
		float cw = swap ? pv.getHeight() : pv.getWidth(), ch = swap ? pv.getWidth() : pv.getHeight();
		float s = Math.max(vw / cw, vh / ch);
		android.graphics.Matrix m = new android.graphics.Matrix();
		m.setScale(cw * s / vw, ch * s / vh, vw / 2, vh / 2);
		preview.setTransform(m);
	}

	private void setStatus(String s) {
		main.post(() -> {
			if (!closed) status.setText(s);
		});
	}

	// ---- Vedlio eiga (pagrindinė gija) ----

	private void positive(String text, boolean enabled) {
		Button p = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
		p.setText(text);
		p.setEnabled(enabled);
	}

	private void onPositive() {
		if (closed) return;
		if (stage == ST_DONE) {
			dialog.dismiss();
		} else if (stage == ST_INTRO) {
			YoutubeWebView car = YoutubeWebView.xfCarWeb();
			if (car == null) {
				status.setText("xFermaTube is not open on the car screen. Connect the car, open xFermaTube there " +
						"(any page is fine), then tap Start.");
				return;
			}
			conn = car.xfConnection();
			positive("Start", false);
			act.getAppActivity().checkPermissions(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
					.onCompletion((r, err) -> main.post(() -> {
						if (closed) return;
						if ((ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) !=
								PackageManager.PERMISSION_GRANTED) ||
								(ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) !=
										PackageManager.PERMISSION_GRANTED)) {
							status.setText("The check needs the camera and the microphone. They are used only " +
									"during this check, nothing is saved or sent anywhere.");
							positive("Start", true);
							return;
						}
						begin();
					}));
		} else if (stage == ST_FAILED) {
			round = 0;
			fails = 0;
			YoutubeWebView car = YoutubeWebView.xfCarWeb();
			if (car != null) {
				car.xfSetSyncTest(true);
				startedTest = true;
			}
			setStage(ST_AIM);
			positive("Checking…", false);
			main.removeCallbacks(tick);
			main.post(tick);
		}
	}

	private void begin() {
		Button never = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
		if (never != null) never.setVisibility(android.view.View.GONE);
		dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setText("Cancel");
		info.setText("Point the phone's back camera at the car screen, so the screen fills the middle of the " +
				"picture. Keep the phone still: rest it against the steering wheel or the seat if you can.");
		preview.setVisibility(android.view.View.VISIBLE);   // kamera įsijungia, kai paviršius paruoštas
		YoutubeWebView car = YoutubeWebView.xfCarWeb();
		if (car != null) {
			car.xfSetSyncTest(true);
			startedTest = true;
		}
		positive("Checking…", false);
		setStage(ST_TUNING);
		main.post(tick);
	}

	private void setStage(int s) {
		stage = s;
		stageAt = SystemClock.uptimeMillis();
		flashTicks = 0;
	}

	private void tick() {
		if (closed || (stage == ST_DONE) || (stage == ST_FAILED) || (stage == ST_INTRO)) return;
		main.postDelayed(tick, 500);
		long el = SystemClock.uptimeMillis() - stageAt;
		YoutubeWebView car = YoutubeWebView.xfCarWeb();
		if (car == null) {
			if (stage != ST_MEASURING) fail("The car screen is gone. Open xFermaTube on the car screen and tap Try again.");
			return;
		}
		switch (stage) {
			case ST_TUNING -> {
				if (statePending) return;
				statePending = true;
				car.xfWizardState(n -> {
					statePending = false;
					if (closed || (stage != ST_TUNING)) return;
					boolean testOn = n >= 1000;
					int votes = Math.max(0, n % 100);
					boolean ramping = (n >= 0) && ((n % 1000) >= 100);
					// Puslapis galėjo persikrauti: testą įjungiam dar kartą.
					if (!testOn && (n >= 0) && (SystemClock.uptimeMillis() - stageAt > 3000)) car.xfSetSyncTest(true);
					status.setText("The car is tuning in by itself (🎤 " + Math.min(votes, 2) + "/2)…");
					// Kamera matuoja tai, ko 🎤 nemato, todėl pirma leidžiam 🎤 susiderinti (ne ilgiau nei 40 s).
					if ((testOn && (votes >= 2) && !ramping) || (SystemClock.uptimeMillis() - stageAt > 40000)) {
						setStage(ST_AIM);
					}
				});
			}
			case ST_AIM -> {
				if (flashesSeen && (session != null)) flashTicks++;
				else flashTicks = 0;
				if (flashTicks >= 3) {
					measure();
				} else if ((fails > 0) && (el < 3000)) {
					// paliekam „Trying again…“ priežastį perskaityti
				} else if (el > 45000) {
					status.setText("Still no flashes seen. Is the whole car screen in the middle of the picture? " +
							"Hold the phone closer, about 30–50 cm from the screen.");
				} else {
					status.setText((round == 0) ? "Point the camera at the car screen…"
							: "Checking again: keep the camera on the car screen…");
				}
			}
			case ST_SETTLE -> {
				if (el > 5000) setStage(ST_AIM);
			}
			default -> {
			}
		}
	}

	private void fail(String text) {
		setStage(ST_FAILED);
		status.setText(text);
		positive("Try again", true);
	}

	/** Matavimas baigtas (pagrindinė gija). */
	private void onMeasured(String text) {
		if (closed) return;
		if (!lastOk || (lastSpread > MAX_SPREAD)) {
			fails++;
			round--;   // patikrų skaičius – tik pavykę matavimai
			String why = !lastOk ? text : "The result was unsteady (±" + Math.round(lastSpread) +
					" ms). Keep the phone still, rest it against something.";
			if (fails >= MAX_FAILS) {
				fail(why + "\nTap Try again when ready.");
			} else {
				status.setText(why + "\nTrying again…");
				setStage(ST_SETTLE);
				stageAt -= 2000;   // pakartojam po ~3 s
			}
			return;
		}
		int ms = lastCorr;
		if (Math.abs(ms) <= OK_MS) {
			finish(ms, false);
		} else if (round >= MAX_ROUNDS) {
			addon.xfPutCamCorr(ms);
			finish(ms, true);
		} else {
			addon.xfPutCamCorr(ms);
			status.setText(((ms > 0) ? "Sound was " + ms + " ms behind the picture"
					: "Sound was " + (-ms) + " ms ahead of the picture") + ". Fixed it, checking once more…");
			setStage(ST_SETTLE);
		}
	}

	private void finish(int ms, boolean lastFix) {
		done = true;
		setStage(ST_DONE);
		addon.xfCalMark(conn);
		YoutubeWebView car = YoutubeWebView.xfCarWeb();
		if (car != null) {
			if (startedTest) car.xfSetSyncTest(false);
			car.xfHint("✓ Sound and picture are in sync. Enjoy!");
		}
		startedTest = false;
		releaseCamera(false);
		preview.setVisibility(android.view.View.GONE);
		liveText.setText("");
		info.setText("Done! ✓");
		status.setText((lastFix ? "Fixed the last " + Math.abs(ms) + " ms." :
				"Sound and picture match (" + (ms >= 0 ? "+" : "") + ms + " ms).") +
				"\n\nThat was a one-time thing for " + connName(conn) + " Android Auto. From now on auto sync " +
				"keeps every video in sync by itself. If you also connect by " +
				("usb".equals(conn) ? "wireless" : "cable") + ", it asks once more there.\n\n" +
				"You can run it again any time from the YouTube settings: Sync sound and picture (camera).");
		positive("Done", true);
		dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setVisibility(android.view.View.GONE);
	}

	@SuppressLint("MissingPermission")
	private void openCamera(SurfaceTexture st) {
		try {
			CameraManager cm = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
			String id = null;
			CameraCharacteristics ch = null;
			for (String c : cm.getCameraIdList()) {
				CameraCharacteristics cc = cm.getCameraCharacteristics(c);
				Integer f = cc.get(CameraCharacteristics.LENS_FACING);
				if ((f != null) && (f == CameraCharacteristics.LENS_FACING_BACK)) {
					id = c;
					ch = cc;
					break;
				}
			}
			if (id == null) {
				setStatus("No back camera found.");
				return;
			}
			Integer src = ch.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE);
			realtimeClock = (src != null) &&
					(src == CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME);
			// Kuo daugiau kadrų per sekundę, tuo tiksliau matosi blyksnio pradžia.
			Range<Integer> best = null;
			Range<Integer>[] ranges = ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
			if (ranges != null) {
				for (Range<Integer> r : ranges) {
					if (r.getUpper() > 60) continue;
					if ((best == null) || (r.getUpper() > best.getUpper()) ||
							(r.getUpper().equals(best.getUpper()) && (r.getLower() > best.getLower()))) best = r;
				}
			}
			fps = (best != null) ? best.getUpper() : 30;
			fpsText = (best != null) ? best.toString() : "?";
			StreamConfigurationMap map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
			Size an = pick(map.getOutputSizes(ImageFormat.YUV_420_888), 320, 640);
			Size pv = pick(map.getOutputSizes(SurfaceTexture.class), 480, 1280);
			st.setDefaultBufferSize(pv.getWidth(), pv.getHeight());
			Integer so = ch.get(CameraCharacteristics.SENSOR_ORIENTATION);
			previewSize = pv;
			previewSensorDeg = (so != null) ? so : 90;
			main.post(this::fitPreview);
			reader = ImageReader.newInstance(an.getWidth(), an.getHeight(), ImageFormat.YUV_420_888, 6);
			reader.setOnImageAvailableListener(this::onImage, bg);
			Surface ps = new Surface(st);
			Range<Integer> fpsRange = best;
			cm.openCamera(id, new CameraDevice.StateCallback() {
				@Override
				public void onOpened(CameraDevice cam) {
					if (closed) {
						cam.close();
						return;
					}
					camera = cam;
					try {
						request = cam.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
						request.addTarget(ps);
						request.addTarget(reader.getSurface());
						if (fpsRange != null) request.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange);
						request.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
						//noinspection deprecation
						cam.createCaptureSession(Arrays.asList(ps, reader.getSurface()),
								new CameraCaptureSession.StateCallback() {
									@Override
									public void onConfigured(CameraCaptureSession s) {
										session = s;
										repeat(false);
									}

									@Override
									public void onConfigureFailed(CameraCaptureSession s) {
										setStatus("Camera could not start.");
									}
								}, bg);
					} catch (Exception ex) {
						Log.e(ex, "xF cam session");
						setStatus("Camera error: " + ex.getMessage());
					}
				}

				@Override
				public void onDisconnected(CameraDevice cam) {
					cam.close();
					camera = null;
				}

				@Override
				public void onError(CameraDevice cam, int error) {
					cam.close();
					camera = null;
					setStatus("Camera error " + error + ".");
				}
			}, bg);
		} catch (Exception ex) {
			Log.e(ex, "xF cam open");
			setStatus("Camera error: " + ex.getMessage());
		}
	}

	/** Mažiausias dydis, kurio trumpesnė kraštinė ≥ min (bet ne didesnis nei max), kitaip mažiausias. */
	private static Size pick(Size[] sizes, int min, int max) {
		Size best = null;
		for (Size s : sizes) {
			int sh = Math.min(s.getWidth(), s.getHeight()), lg = Math.max(s.getWidth(), s.getHeight());
			if ((sh < min) || (lg > max)) continue;
			if ((best == null) || ((long) s.getWidth() * s.getHeight() < (long) best.getWidth() * best.getHeight()))
				best = s;
		}
		if (best != null) return best;
		for (Size s : sizes) {
			if ((best == null) || ((long) s.getWidth() * s.getHeight() < (long) best.getWidth() * best.getHeight()))
				best = s;
		}
		return best;
	}

	/** aeLock – matuojant ekspozicija užrakinama, kad blyksniai jos netampytų. */
	private void repeat(boolean aeLock) {
		try {
			if ((session == null) || (request == null)) return;
			request.set(CaptureRequest.CONTROL_AE_LOCK, aeLock);
			session.setRepeatingRequest(request.build(), new CameraCaptureSession.CaptureCallback() {
				@Override
				public void onCaptureCompleted(CameraCaptureSession s, CaptureRequest r, TotalCaptureResult res) {
					Long ts = res.get(CaptureResult.SENSOR_TIMESTAMP);
					if (ts == null) return;
					Long exp = res.get(CaptureResult.SENSOR_EXPOSURE_TIME);
					Long skew = res.get(CaptureResult.SENSOR_ROLLING_SHUTTER_SKEW);
					meta.put(ts, new long[]{(exp != null) ? exp : -1, (skew != null) ? skew : -1});
					if (meta.size() > 400) {
						long old = ts - 5_000_000_000L;
						meta.keySet().removeIf(k -> k < old);
					}
				}
			}, bg);
		} catch (Exception ex) {
			Log.e(ex, "xF cam repeat");
		}
	}

	private void onImage(ImageReader r) {
		Image img;
		while (true) {
			try {
				img = r.acquireNextImage();
			} catch (IllegalStateException ex) {
				return;
			}
			if (img == null) return;
			try {
				float l = roiLuma(img);
				long ts = img.getTimestamp();
				if (recording) {
					frames.add(new long[]{ts, 0, 0});
					lumas.add(l);
				}
				live[liveN++ % live.length] = l;
				long now = SystemClock.uptimeMillis();
				if (now - liveShownAt > 400) {
					liveShownAt = now;
					int n = Math.min(liveN, live.length);
					float lo = 255, hi = 0;
					for (int i = 0; i < n; i++) {
						lo = Math.min(lo, live[i]);
						hi = Math.max(hi, live[i]);
					}
					flashesSeen = (n >= 30) && (hi - lo >= 25);
					String seen = flashesSeen ? "flashes seen ✓" : "no flashes seen yet";
					String txt = String.format(Locale.ROOT, "Camera %s fps · brightness %.0f–%.0f · %s",
							fpsText, lo, hi, seen);
					main.post(() -> {
						if (!closed) liveText.setText(txt);
					});
				}
			} finally {
				img.close();
			}
		}
	}

	/** Vidutinis šviesumas vaizdo viduryje (40 % pločio ir aukščio). */
	private static float roiLuma(Image img) {
		Image.Plane p = img.getPlanes()[0];
		ByteBuffer b = p.getBuffer();
		int w = img.getWidth(), h = img.getHeight(), rs = p.getRowStride(), ps = p.getPixelStride();
		int x0 = w * 3 / 10, x1 = w * 7 / 10, y0 = h * 3 / 10, y1 = h * 7 / 10;
		long sum = 0;
		int n = 0;
		for (int y = y0; y < y1; y += 2) {
			int row = y * rs;
			for (int x = x0; x < x1; x += 2) {
				sum += b.get(row + x * ps) & 0xFF;
				n++;
			}
		}
		return (n > 0) ? (float) sum / n : 0;
	}

	private void measure() {
		if (recording || (session == null)) return;
		setStage(ST_MEASURING);
		round++;
		lastCorr = 0;
		lastOk = false;
		Thread t = new Thread(this::record, "xf-cam-audio");
		t.setDaemon(true);
		t.start();
	}

	private void record() {
		String out;
		try {
			// Automobilio 🎤 matavimas naudoja tą patį mikrofoną: palaukiam, kol jis baigs.
			for (int i = 0; XfMicRecorder.busy && (i < 150) && !closed; i++) {
				if (i == 0) setStatus("Waiting for the auto sync measurement to finish…");
				SystemClock.sleep(100);
			}
			XfMicRecorder.busy = true;
			try {
				out = recordAndAnalyze();
			} finally {
				XfMicRecorder.busy = false;
				bg.post(() -> {
					if (recording) {
						recording = false;
						repeat(false);
					}
				});
			}
		} catch (Throwable ex) {
			Log.e(ex, "xF cam measure");
			out = "Error: " + ex;
		}
		String text = out;
		Log.i("xF: camera sync round " + round + ": " + text.replace('\n', ' '));
		main.post(() -> onMeasured(text));
	}

	@SuppressLint("MissingPermission")
	private String recordAndAnalyze() {
		int min = AudioRecord.getMinBufferSize(SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
		AudioRecord rec = new AudioRecord(MediaRecorder.AudioSource.MIC, SR, AudioFormat.CHANNEL_IN_MONO,
				AudioFormat.ENCODING_PCM_16BIT, Math.max(min, SR / 2));
		if (rec.getState() != AudioRecord.STATE_INITIALIZED) {
			rec.release();
			return "Microphone unavailable.";
		}
		int timebase = realtimeClock ? AudioTimestamp.TIMEBASE_BOOTTIME : AudioTimestamp.TIMEBASE_MONOTONIC;
		int bins = REC_SEC * 1000;
		float[] env = new float[bins];
		short[] buf = new short[ENV_BIN * 10];
		AudioTimestamp ats = new AudioTimestamp();
		long tsFrame = -1, tsNano = 0, firstReadNano = 0;
		bg.post(() -> {
			frames.clear();
			lumas.clear();
			repeat(true);
			recording = true;
		});
		setStatus("Measuring… hold still for " + REC_SEC + " s" + ((round > 1) ? " (check " + round + ")" : ""));
		try {
			rec.startRecording();
			if (rec.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) return "Microphone busy.";
			int bin = 0;
			short prev = 0;
			while ((bin < bins) && !closed) {
				int got = 0;
				while (got < buf.length) {
					int n = rec.read(buf, got, buf.length - got);
					if (n <= 0) return "Microphone read error " + n + ".";
					got += n;
				}
				if (firstReadNano == 0) firstReadNano = realtimeClock ? SystemClock.elapsedRealtimeNanos() : System.nanoTime();
				// Aukštų dažnių filtras (skirtumas): pyptelėjimas (1 kHz) išryškėja, automobilio ūžesys nuslopsta.
				for (int k = 0; (k < 10) && (bin < bins); k++, bin++) {
					long s = 0;
					for (int i = k * ENV_BIN, e = i + ENV_BIN; i < e; i++) {
						int d = buf[i] - prev;
						prev = buf[i];
						s += Math.abs(d);
					}
					env[bin] = (float) s / ENV_BIN;
				}
				if ((tsFrame < 0) && (bin >= 300) && (rec.getTimestamp(ats, timebase) == AudioRecord.SUCCESS)) {
					tsFrame = ats.framePosition;
					tsNano = ats.nanoTime;
				}
			}
		} finally {
			try {
				rec.stop();
			} catch (Exception ignored) {
			}
			rec.release();
		}
		// Kamera dar truputį pafilmuoja, kad paskutinis pyptelėjimas turėtų blyksnį.
		SystemClock.sleep(300);
		Object lock = new Object();
		long[][] fr = new long[1][];
		float[][] lu = new float[1][];
		synchronized (lock) {
			bg.post(() -> {
				recording = false;
				repeat(false);
				int n = frames.size();
				long[] ts = new long[n * 3];
				float[] l = new float[n];
				for (int i = 0; i < n; i++) {
					long t = frames.get(i)[0];
					long[] m = meta.get(t);
					ts[i * 3] = t;
					ts[i * 3 + 1] = (m != null) ? m[0] : -1;
					ts[i * 3 + 2] = (m != null) ? m[1] : -1;
					l[i] = lumas.get(i);
				}
				synchronized (lock) {
					fr[0] = ts;
					lu[0] = l;
					lock.notifyAll();
				}
			});
			try {
				while ((fr[0] == null) && !closed) lock.wait(500);
			} catch (InterruptedException ex) {
				return "Interrupted.";
			}
		}
		if (closed || (fr[0] == null)) return "";
		// Pirmo garso kadro laikas tuo pačiu laikrodžiu kaip kameros.
		long readNano = firstReadNano - 10_000_000L;
		boolean exact = tsFrame >= 0;
		long t0 = exact ? tsNano - Math.round(tsFrame * 1e9 / SR) : readNano;
		if (Math.abs(t0 - readNano) > 300_000_000L) {
			t0 = readNano;
			exact = false;
		}
		Res res = analyze(fr[0], lu[0], fps, env, t0);
		if (res.err != null) return res.err;
		lastCorr = (int) Math.round(res.ms);
		lastSpread = res.spread;
		lastOk = true;
		return String.format(Locale.ROOT, "%+d ms · %d flashes, %d beeps, %d pairs, spread ±%.0f ms · camera %s fps%s%s",
				lastCorr, res.flashes, res.beeps, res.pairs, res.spread, fpsText,
				realtimeClock ? "" : " · camera clock unknown", exact ? "" : " · mic clock approx.");
	}

	private void close() {
		closed = true;
		recording = false;
		main.removeCallbacks(tick);
		if (startedTest) {
			startedTest = false;
			YoutubeWebView car = YoutubeWebView.xfCarWeb();
			if (car != null) car.xfSetSyncTest(false);
		}
		// „Later“ ar atšaukus: per šį prisijungimą nebeklausiam.
		addon.xfCalClosed(offered && !done);
		releaseCamera(true);
	}

	private void releaseCamera(boolean quit) {
		Handler h = bg;
		if (h == null) return;
		h.post(() -> {
			try {
				if (session != null) session.close();
			} catch (Exception ignored) {
			}
			try {
				if (camera != null) camera.close();
			} catch (Exception ignored) {
			}
			try {
				if (reader != null) reader.close();
			} catch (Exception ignored) {
			}
			session = null;
			camera = null;
			reader = null;
			if (quit) thread.quitSafely();
		});
	}

	// ---- Analizė (be Android API, kad būtų galima tikrinti atskirai) ----

	static final class Res {
		String err;
		double ms, spread;
		int flashes, beeps, pairs;
	}

	/**
	 * @param fr  kadrai po 3: {pradžios laikas ns, ekspozicija ns (-1 nežinoma), rolling shutter ns (-1)}
	 * @param lu  šviesumas vaizdo viduryje kiekvienam kadrui
	 * @param env garso kreivė kas 1 ms, env[0] prasideda t0 ns (tuo pačiu laikrodžiu)
	 */
	static Res analyze(long[] fr, float[] lu, int fps, float[] env, long t0) {
		Res res = new Res();
		int n = lu.length;
		if (n < 3 * Math.max(fps, 10)) {
			res.err = "Too few camera frames (" + n + "). Try again.";
			return res;
		}
		float[] sl = lu.clone();
		Arrays.sort(sl);
		// Blyksnis trunka ~10 % laiko: tamsus lygis – mediana, šviesus – 98-a procentilė (ne 90-a, ji jau ant krašto).
		float lo = sl[n / 2], hi = sl[n - 1 - n / 50];
		if (hi - lo < 12) {
			res.err = "The camera did not see the flashes. Keep the car screen in the middle of the picture.";
			return res;
		}
		// Kadro vidurio laikas vaizdo vidurio eilutėms: pradžia + pusė rolling shutter + pusė ekspozicijos.
		double period = 1e9 / Math.max(fps, 1);
		double[] tc = new double[n];
		for (int i = 0; i < n; i++) {
			long exp = fr[i * 3 + 1], skew = fr[i * 3 + 2];
			tc[i] = fr[i * 3] + ((skew > 0) ? skew / 2.0 : 0) + ((exp > 0) ? exp / 2.0 : period / 2);
		}
		float th = (lo + hi) / 2;
		List<Double> flashes = new ArrayList<>();
		double lastF = -1e18;
		for (int i = 1; i < n; i++) {
			if ((lu[i - 1] < th) && (lu[i] >= th)) {
				double t = tc[i - 1] + (th - lu[i - 1]) / (lu[i] - lu[i - 1]) * (tc[i] - tc[i - 1]);
				if (t - lastF > 500e6) {
					flashes.add(t);
					lastF = t;
				}
			}
		}
		// Pyptelėjimų pradžios: kreivė pakyla virš slenksčio po bent 300 ms tylos.
		int m = env.length;
		float[] se = env.clone();
		Arrays.sort(se);
		float floor = se[m / 2], peak = se[Math.max(0, m - 1 - m / 200)];
		if (peak < floor * 3 + 1) {
			res.err = "No beeps heard. Turn the car volume up and try again.";
			res.flashes = flashes.size();
			return res;
		}
		float bt = floor + 0.25f * (peak - floor);
		List<Double> beeps = new ArrayList<>();
		int quiet = 0;
		for (int k = 0; k < m; k++) {
			if (env[k] >= bt) {
				if (quiet >= 300) beeps.add(t0 + (k + 0.5) * 1e6);
				quiet = 0;
			} else {
				quiet++;
			}
		}
		res.flashes = flashes.size();
		res.beeps = beeps.size();
		List<Double> d = new ArrayList<>();
		for (double f : flashes) {
			double best = Double.NaN;
			for (double b : beeps) {
				if (Math.abs(b - f) <= 450e6 && (Double.isNaN(best) || Math.abs(b - f) < Math.abs(best))) best = b - f;
			}
			if (!Double.isNaN(best)) d.add(best / 1e6);
		}
		res.pairs = d.size();
		if (d.size() < 4) {
			res.err = String.format(Locale.ROOT,
					"Not enough flash/beep pairs (%d flashes, %d beeps, %d pairs). Check that the camera sees " +
							"the screen and the beeps are loud enough, then try again.", res.flashes, res.beeps, res.pairs);
			return res;
		}
		res.ms = median(d);
		List<Double> dev = new ArrayList<>();
		for (double v : d) dev.add(Math.abs(v - res.ms));
		res.spread = median(dev);
		return res;
	}

	private static double median(List<Double> v) {
		Double[] a = v.toArray(new Double[0]);
		Arrays.sort(a);
		int n = a.length;
		return (n % 2 == 1) ? a[n / 2] : (a[n / 2 - 1] + a[n / 2]) / 2;
	}
}
