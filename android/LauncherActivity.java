package com.erwarx.lembar;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Parcelable;
import android.provider.OpenableColumns;
import android.util.Base64;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;
import android.window.OnBackInvokedDispatcher;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.util.ArrayList;

/**
 * Lembar's Android shell: a full-screen WebView that loads the Lembar web app.
 *
 * Replaces Bubblewrap's Trusted Web Activity launcher (copied over it in CI). Chrome's TWA
 * plumbing dropped PDFs opened or shared from WhatsApp on some phones, so this activity reads
 * the PDF itself and hands it to the page through a JavaScript bridge (window.LembarNative),
 * in base64 chunks small enough for the WebView IPC.
 */
public class LauncherActivity extends Activity {

    private static final String TAG = "Lembar";
    private static final String SHARED_DIR = "shared_pdfs";
    private static final long MAX_AGE_MS = 24L * 60 * 60 * 1000;
    private static final int PICK_PDF = 42;
    private static final int MAX_CHUNK = 512 * 1024;

    private WebView web;
    private ValueCallback<Uri[]> fileCallback;

    // The PDF waiting for the page to pick it up.
    private volatile File pendingFile;
    private volatile String pendingId;
    private volatile String pendingName;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        boolean night = isNight();

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(night ? 0xFF1A201E : 0xFFFAFBF8);
        web = new WebView(this);
        web.setBackgroundColor(night ? 0xFF101413 : 0xFFEDF0EC);
        root.addView(web, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(root);
        edgeToEdge(root, night);

        WebView.setWebContentsDebuggingEnabled(true);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);
        s.setTextZoom(100);
        s.setUserAgentString(s.getUserAgentString() + " LembarApp/" + versionName());
        web.addJavascriptInterface(new Bridge(), "LembarNative");
        web.setWebViewClient(new Client());
        web.setWebChromeClient(new Chrome());
        registerBack();

        Uri link = handleIntent(getIntent());
        if (savedInstanceState == null || web.restoreState(savedInstanceState) == null) {
            web.loadUrl(link != null ? link.toString() : startUrl());
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        Uri link = handleIntent(intent);
        if (link != null) {
            web.loadUrl(link.toString());
        } else if (pendingFile != null) {
            web.evaluateJavascript("window.lembarNativeFile && window.lembarNativeFile()", null);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (web != null) web.saveState(outState);
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }

    // ---------------------------------------------------------------- incoming files and links

    private String startUrl() {
        return getString(R.string.launchUrl);
    }

    private String host() {
        return Uri.parse(startUrl()).getHost();
    }

    /** Copies an incoming PDF (Open with / Share). Returns a link to open if the intent is one. */
    private Uri handleIntent(Intent in) {
        if (in == null) return null;
        String action = in.getAction();
        Uri source = null;
        if (Intent.ACTION_VIEW.equals(action)) {
            Uri data = in.getData();
            if (data != null && "https".equalsIgnoreCase(data.getScheme())
                    && host().equalsIgnoreCase(data.getHost())) {
                return data;
            }
            source = data;
        } else if (Intent.ACTION_SEND.equals(action)) {
            Parcelable p = in.getParcelableExtra(Intent.EXTRA_STREAM);
            if (p instanceof Uri) source = (Uri) p;
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            ArrayList<Parcelable> list = in.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (list != null && !list.isEmpty() && list.get(0) instanceof Uri) source = (Uri) list.get(0);
        }
        if (source == null) return null;
        String scheme = source.getScheme();
        if (!"content".equalsIgnoreCase(scheme) && !"file".equalsIgnoreCase(scheme)) return null;

        String name = displayName(source);
        if (!name.toLowerCase().endsWith(".pdf")) name = name + ".pdf";
        try {
            File dir = new File(getCacheDir(), SHARED_DIR);
            cleanUp(dir);
            String id = String.valueOf(System.currentTimeMillis());
            File folder = new File(dir, id);
            if (!folder.mkdirs() && !folder.isDirectory()) throw new IllegalStateException("cannot create cache folder");
            File out = new File(folder, name);
            long bytes = 0;
            try (InputStream is = getContentResolver().openInputStream(source);
                 OutputStream os = new FileOutputStream(out)) {
                if (is == null) throw new IllegalStateException("no data from " + source.getAuthority());
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = is.read(buf)) != -1) {
                    os.write(buf, 0, n);
                    bytes += n;
                }
            }
            if (bytes == 0) throw new IllegalStateException("file is empty");
            pendingName = name;
            pendingId = id;
            pendingFile = out;
            Log.i(TAG, "Received " + bytes + " bytes: " + name);
        } catch (Exception e) {
            Log.e(TAG, "Could not read incoming PDF " + source, e);
            Toast.makeText(this, "Lembar tidak bisa membaca PDF ini: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
        return null;
    }

    private String displayName(Uri uri) {
        String name = null;
        try (Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) name = c.getString(0);
        } catch (Exception ignored) {
            // fall back to the path
        }
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

    /** Called from the page (on a background thread) to fetch the pending PDF in chunks. */
    final class Bridge {
        @JavascriptInterface
        public String pending() {
            File f = pendingFile;
            if (f == null || !f.exists()) return "";
            try {
                JSONObject o = new JSONObject();
                o.put("id", pendingId);
                o.put("name", pendingName);
                o.put("size", f.length());
                return o.toString();
            } catch (Exception e) {
                return "";
            }
        }

        @JavascriptInterface
        public String read(String id, int offset, int length) {
            File f = pendingFile;
            if (f == null || id == null || !id.equals(pendingId)) return "";
            int len = Math.max(0, Math.min(length, MAX_CHUNK));
            try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
                raf.seek(offset);
                byte[] buf = new byte[len];
                int n = raf.read(buf, 0, len);
                if (n <= 0) return "";
                return Base64.encodeToString(buf, 0, n, Base64.NO_WRAP);
            } catch (Exception e) {
                Log.e(TAG, "read failed", e);
                return "";
            }
        }

        @JavascriptInterface
        public void done(String id) {
            if (id == null || !id.equals(pendingId)) return;
            File f = pendingFile;
            pendingFile = null;
            pendingId = null;
            pendingName = null;
            if (f != null) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }

        @JavascriptInterface
        public String version() {
            return versionName();
        }
    }

    // ---------------------------------------------------------------- WebView clients

    private final class Client extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            Uri url = request.getUrl();
            if ("https".equalsIgnoreCase(url.getScheme()) && host().equalsIgnoreCase(url.getHost())) {
                return false;
            }
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, url));
            } catch (ActivityNotFoundException ignored) {
                // nothing can open it
            }
            return true;
        }
    }

    private final class Chrome extends WebChromeClient {
        @Override
        public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
            if (fileCallback != null) fileCallback.onReceiveValue(null);
            fileCallback = callback;
            Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            pick.addCategory(Intent.CATEGORY_OPENABLE);
            pick.setType("application/pdf");
            try {
                startActivityForResult(pick, PICK_PDF);
            } catch (ActivityNotFoundException e) {
                try {
                    startActivityForResult(params.createIntent(), PICK_PDF);
                } catch (ActivityNotFoundException e2) {
                    fileCallback = null;
                    return false;
                }
            }
            return true;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == PICK_PDF) {
            if (fileCallback != null) {
                Uri[] result = (resultCode == RESULT_OK && data != null && data.getData() != null)
                        ? new Uri[]{data.getData()} : null;
                fileCallback.onReceiveValue(result);
                fileCallback = null;
            }
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    // ---------------------------------------------------------------- back, bars, misc

    private void registerBack() {
        if (Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::goBack);
        }
    }

    private void goBack() {
        if (web != null && web.canGoBack()) web.goBack();
        else finish();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        goBack();
    }

    private boolean isNight() {
        return (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
    }

    /** Draw behind the system bars on every Android version and pad the content by their size. */
    @SuppressWarnings("deprecation")
    private void edgeToEdge(View root, boolean night) {
        Window w = getWindow();
        w.setStatusBarColor(Color.TRANSPARENT);
        w.setNavigationBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= 30) {
            w.setDecorFitsSystemWindows(false);
            WindowInsetsController c = w.getInsetsController();
            if (c != null) {
                int light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                        | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                c.setSystemBarsAppearance(night ? 0 : light, light);
            }
        } else {
            int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
            if (!night) {
                flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                if (Build.VERSION.SDK_INT >= 26) flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            }
            w.getDecorView().setSystemUiVisibility(flags);
        }
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets i = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
                v.setPadding(i.left, i.top, i.right, i.bottom);
            } else {
                v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                        insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            }
            return insets;
        });
    }

    private String versionName() {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            return info.versionName != null ? info.versionName : "?";
        } catch (Exception e) {
            return "?";
        }
    }
}
