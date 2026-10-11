package com.erwarx.lembar;

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Parcelable;
import android.provider.OpenableColumns;
import android.util.Log;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import com.google.androidbrowserhelper.trusted.TwaLauncher;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;

/**
 * Replaces Bubblewrap's generated LauncherActivity (copied over it in CI).
 *
 * Why: when WhatsApp (or another app) opens or shares a PDF, it hands us a content:// URI
 * that only we are allowed to read. Passing that URI on to Chrome needs a second permission
 * hand-off that silently fails on some phones, and the page then gets no file.
 *
 * Fix: read the PDF here, copy it into our own cache, and pass Chrome a URI from our own
 * FileProvider through the Web Share Target. The page's service worker receives it at
 * POST /share-target and opens it.
 *
 * It also pins Chrome as the browser that runs the app when Chrome is installed. By default the
 * helper uses the phone's default browser, which on Samsung phones is often Samsung Internet;
 * that browser doesn't deliver opened files to the page, and its share-target requests arrive
 * with an unreadable body ("Failed to fetch").
 */
public class LauncherActivity
        extends com.google.androidbrowserhelper.trusted.LauncherActivity {

    private static final String TAG = "Lembar";
    private static final String SHARED_DIR = "shared_pdfs";
    private static final long MAX_AGE_MS = 24L * 60 * 60 * 1000;
    private static final String[] CHROME_PACKAGES = {
            "com.android.chrome", "com.chrome.beta", "com.chrome.dev", "com.chrome.canary"
    };

    /** Run the app in Chrome when it's installed, instead of the phone's default browser. */
    @Override
    protected TwaLauncher createTwaLauncher() {
        String chrome = installedChrome();
        if (chrome != null) {
            Log.i(TAG, "Using " + chrome + " as the TWA provider");
            return new TwaLauncher(this, chrome);
        }
        return super.createTwaLauncher();
    }

    private String installedChrome() {
        PackageManager pm = getPackageManager();
        for (String pkg : CHROME_PACKAGES) {
            try {
                PackageInfo info = pm.getPackageInfo(pkg, 0);
                if (info.applicationInfo != null && info.applicationInfo.enabled) return pkg;
            } catch (PackageManager.NameNotFoundException ignored) {
                // not installed
            }
        }
        return null;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        if (savedInstanceState == null) {
            Intent rewritten = copyIncomingPdf(getIntent());
            if (rewritten != null) setIntent(rewritten);
        }
        super.onCreate(savedInstanceState);
        // Setting an orientation crashes the app due to the transparent background on Android 8.0
        // Oreo and below. See https://github.com/GoogleChromeLabs/bubblewrap/issues/496.
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.O) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        }
    }

    /** Our own FileProvider URIs are safe to hand to Chrome. */
    @Override
    protected boolean isTrustedContentUri(Uri uri) {
        if (isOwnSharedUri(uri)) return true;
        return super.isTrustedContentUri(uri);
    }

    private String authority() {
        return getPackageName() + ".fileprovider";
    }

    private boolean isOwnSharedUri(Uri uri) {
        if (uri == null || !"content".equalsIgnoreCase(uri.getScheme())) return false;
        String path = uri.getPath();
        return authority().equals(uri.getAuthority()) && path != null && path.startsWith("/" + SHARED_DIR + "/");
    }

    /**
     * If the intent carries a PDF from another app (VIEW = "Open with", SEND = "Share"),
     * copy it and return an equivalent SEND intent pointing at our copy. Otherwise null.
     */
    private Intent copyIncomingPdf(Intent in) {
        if (in == null) return null;
        String action = in.getAction();
        Uri source = null;
        if (Intent.ACTION_VIEW.equals(action)) {
            source = in.getData();
        } else if (Intent.ACTION_SEND.equals(action)) {
            Parcelable p = in.getParcelableExtra(Intent.EXTRA_STREAM);
            if (p instanceof Uri) source = (Uri) p;
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            ArrayList<Parcelable> list = in.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (list != null && !list.isEmpty() && list.get(0) instanceof Uri) source = (Uri) list.get(0);
        }
        if (source == null || !"content".equalsIgnoreCase(source.getScheme()) || isOwnSharedUri(source)) {
            return null;
        }

        String type = null;
        try { type = getContentResolver().getType(source); } catch (Exception ignored) { }
        if (type == null) type = in.getType();
        String name = displayName(source);
        boolean isPdf = (type != null && type.toLowerCase().contains("pdf"))
                || name.toLowerCase().endsWith(".pdf")
                || Intent.ACTION_VIEW.equals(action); // our VIEW filter only accepts PDFs
        if (!isPdf) return null;
        if (!name.toLowerCase().endsWith(".pdf")) name = name + ".pdf";

        try {
            File dir = new File(getCacheDir(), SHARED_DIR);
            cleanUp(dir);
            File folder = new File(dir, String.valueOf(System.currentTimeMillis()));
            if (!folder.mkdirs() && !folder.isDirectory()) throw new IllegalStateException("cannot create cache folder");
            File out = new File(folder, name);
            long bytes = 0;
            try (InputStream is = getContentResolver().openInputStream(source);
                 OutputStream os = new FileOutputStream(out)) {
                if (is == null) throw new IllegalStateException("no data from " + source.getAuthority());
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = is.read(buf)) != -1) { os.write(buf, 0, n); bytes += n; }
            }
            if (bytes == 0) throw new IllegalStateException("file is empty");

            Uri copy = FileProvider.getUriForFile(this, authority(), out);
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setComponent(new ComponentName(this, LauncherActivity.class));
            send.setType("application/pdf");
            send.putExtra(Intent.EXTRA_STREAM, copy);
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            send.addFlags(in.getFlags() & (Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            Log.i(TAG, "Copied " + bytes + " bytes from " + source.getAuthority() + " to " + copy);
            return send;
        } catch (Exception e) {
            Log.e(TAG, "Could not read incoming PDF " + source, e);
            Toast.makeText(this, "Lembar tidak bisa membaca PDF ini: " + e.getMessage(), Toast.LENGTH_LONG).show();
            return null;
        }
    }

    private String displayName(Uri uri) {
        String name = null;
        try (Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) name = c.getString(0);
        } catch (Exception ignored) { }
        if (name == null || name.trim().isEmpty()) name = uri.getLastPathSegment();
        if (name == null || name.trim().isEmpty()) name = "Dokumen";
        name = name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        if (name.length() > 120) name = name.substring(0, 120);
        return name.isEmpty() ? "Dokumen" : name;
    }

    /** Delete copies older than a day so the cache doesn't grow. */
    private static void cleanUp(File dir) {
        File[] folders = dir.listFiles();
        if (folders == null) return;
        long now = System.currentTimeMillis();
        for (File f : folders) {
            if (now - f.lastModified() < MAX_AGE_MS) continue;
            File[] files = f.listFiles();
            if (files != null) for (File x : files) //noinspection ResultOfMethodCallIgnored
                x.delete();
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }

    @Override
    protected Uri getLaunchingUrl() {
        return super.getLaunchingUrl();
    }
}
