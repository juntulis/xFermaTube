package me.app.fermatax.auto.xf;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * xFermaTube: hands the app's own installed APK to the phone's package installer, read-only, for the
 * "Fix install source" reinstall. It serves that one file under one fixed name and nothing else.
 * Not exported: only an app that was given a URI grant for this name (the system installer) can read it.
 */
public final class XfApkProvider extends ContentProvider {
	public static final String MIME = "application/vnd.android.package-archive";
	private static final String NAME = "xFermaTube.apk";

	/** content://&lt;package&gt;.xfapk/xFermaTube.apk */
	public static Uri uri(Context ctx) {
		return new Uri.Builder().scheme("content").authority(ctx.getPackageName() + ".xfapk").path(NAME).build();
	}

	/** The installed APK; fails when the app is installed as split APKs (one file is not the whole app). */
	static File apk(Context ctx) throws FileNotFoundException {
		ApplicationInfo ai = ctx.getApplicationInfo();
		if ((ai.splitSourceDirs != null) && (ai.splitSourceDirs.length > 0)) {
			throw new FileNotFoundException("The app is installed as split APKs");
		}
		if (ai.sourceDir == null) throw new FileNotFoundException("No APK path");
		File f = new File(ai.sourceDir);
		if (!f.isFile()) throw new FileNotFoundException("APK not found");
		return f;
	}

	private File file(Uri uri) throws FileNotFoundException {
		Context ctx = getContext();
		java.util.List<String> path = uri.getPathSegments();
		if ((ctx == null) || (path.size() != 1) || !NAME.equals(path.get(0))) {
			throw new FileNotFoundException("Not served");
		}
		return apk(ctx);
	}

	@Override
	public boolean onCreate() {
		return true;
	}

	@Override
	public ParcelFileDescriptor openFile(@NonNull Uri uri, @NonNull String mode) throws FileNotFoundException {
		if (!"r".equals(mode)) throw new FileNotFoundException("Read only");
		return ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.MODE_READ_ONLY);
	}

	/** The installer asks for the name and size before it reads the file. */
	@Override
	public Cursor query(@NonNull Uri uri, @Nullable String[] projection, @Nullable String selection,
											@Nullable String[] selectionArgs, @Nullable String sortOrder) {
		File f;
		try {
			f = file(uri);
		} catch (FileNotFoundException ex) {
			return null;
		}
		String[] cols = (projection != null) ? projection :
				new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
		MatrixCursor c = new MatrixCursor(cols, 1);
		Object[] row = new Object[cols.length];
		for (int i = 0; i < cols.length; i++) {
			if (OpenableColumns.DISPLAY_NAME.equals(cols[i])) row[i] = NAME;
			else if (OpenableColumns.SIZE.equals(cols[i])) row[i] = f.length();
		}
		c.addRow(row);
		return c;
	}

	@Override
	public String getType(@NonNull Uri uri) {
		return MIME;
	}

	@Override
	public Uri insert(@NonNull Uri uri, @Nullable ContentValues values) {
		throw new UnsupportedOperationException();
	}

	@Override
	public int delete(@NonNull Uri uri, @Nullable String selection, @Nullable String[] selectionArgs) {
		throw new UnsupportedOperationException();
	}

	@Override
	public int update(@NonNull Uri uri, @Nullable ContentValues values, @Nullable String selection,
										@Nullable String[] selectionArgs) {
		throw new UnsupportedOperationException();
	}
}
