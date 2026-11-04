package dev.workers.rangmanch.app;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.PermissionRequest;
import android.webkit.ServiceWorkerClient;
import android.webkit.ServiceWorkerController;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import androidx.browser.customtabs.CustomTabsIntent;
import androidx.webkit.WebViewAssetLoader;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Rang Manch India - native shell around the bundled website (/web folder -> assets).
 * The site is served from a private https origin, so it works offline-first and needs no hosting.
 */
public class MainActivity extends Activity {

    private static final String HOST = "appassets.androidplatform.net";
    private static final String BASE = "https://" + HOST + "/";
    private static final int REQ_FILE = 4401;
    private static final int REQ_PERM = 4402;

    private FrameLayout root;
    private WebView web;
    private View customView;
    private WebChromeClient.CustomViewCallback customViewCallback;
    private ValueCallback<Uri[]> fileCallback;
    private PermissionRequest pendingPermission;
    private WebViewAssetLoader loader;

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.parseColor("#030712"));
        getWindow().setNavigationBarColor(Color.parseColor("#030712"));

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.parseColor("#030712"));
        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#030712"));
        root.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);

        loader = new WebViewAssetLoader.Builder()
                .setDomain(HOST)
                .addPathHandler("/", new SiteAssets())
                .build();

        setupWebView();

        if (!handleAuthIntent(getIntent())) {
            if (savedInstanceState != null) {
                web.restoreState(savedInstanceState);
            } else {
                web.loadUrl(BASE + "index.html");
            }
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleAuthIntent(intent);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (web != null) web.saveState(outState);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (web != null) web.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) web.onResume();
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            root.removeView(web);
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (customView != null) {
            hideCustomView();
        } else if (web != null && web.canGoBack()) {
            web.goBack();
        } else {
            super.onBackPressed();
        }
    }

    // ------------------------------------------------------------------ WebView

    private void setupWebView() {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setTextZoom(100);
        s.setLoadWithOverviewMode(true);
        s.setUserAgentString(s.getUserAgentString() + " RangManchApp/50.74");

        if (0 != (getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE)) {
            WebView.setWebContentsDebuggingEnabled(true);
        }

        // Let the site's service worker load its files from the bundled assets as well.
        if (Build.VERSION.SDK_INT >= 24) {
            ServiceWorkerController.getInstance().setServiceWorkerClient(new ServiceWorkerClient() {
                @Override
                public WebResourceResponse shouldInterceptRequest(WebResourceRequest request) {
                    return loader.shouldInterceptRequest(request.getUrl());
                }
            });
        }

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return loader.shouldInterceptRequest(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if (HOST.equals(u.getHost())) return false;
                if (!request.isForMainFrame()) return false;
                String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
                if (scheme.equals("rangmanch")) {
                    handleAuthUri(u);
                    return true;
                }
                if (scheme.equals("http") || scheme.equals("https")) {
                    openExternal(u);
                    return true;
                }
                // upi:, tel:, mailto:, whatsapp:, intent: ...
                try {
                    Intent i = new Intent(Intent.ACTION_VIEW, u);
                    i.addCategory(Intent.CATEGORY_BROWSABLE);
                    startActivity(i);
                } catch (ActivityNotFoundException | SecurityException ignored) {
                }
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                if (url != null && url.startsWith(BASE)) injectAppBridge(view);
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                try {
                    startActivityForResult(params.createIntent(), REQ_FILE);
                } catch (ActivityNotFoundException e) {
                    fileCallback = null;
                    callback.onReceiveValue(null);
                    return false;
                }
                return true;
            }

            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                runOnUiThread(() -> handleWebPermission(request));
            }

            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                if (customView != null) {
                    callback.onCustomViewHidden();
                    return;
                }
                customView = view;
                customViewCallback = callback;
                root.addView(view, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                web.setVisibility(View.GONE);
            }

            @Override
            public void onHideCustomView() {
                hideCustomView();
            }
        });
    }

    /** Runs after every page load: Google login + share links must work inside the app. */
    private void injectAppBridge(WebView view) {
        String site = BuildConfig.SITE_URL == null ? "" : BuildConfig.SITE_URL;
        String js = "(function(){try{"
                + "window.RM_ANDROID=true;"
                + "window.getOAuthRedirectUrl=function(){return 'rangmanch://auth'};"
                + (site.isEmpty() ? "" :
                "var S=" + jsString(site) + ";"
                        + "window.rmVideoShareUrl=function(id){return S+'/?video='+encodeURIComponent(String(id))};"
                        + "window.rmCreatorShareUrl=function(id){return S+'/?creator='+encodeURIComponent(String(id))};")
                + "}catch(e){}})();";
        view.evaluateJavascript(js, null);
    }

    private static String jsString(String v) {
        return "'" + v.replace("\\", "\\\\").replace("'", "\\'") + "'";
    }

    // ------------------------------------------------------------------ login redirect

    private boolean handleAuthIntent(Intent intent) {
        if (intent == null) return false;
        Uri u = intent.getData();
        if (u != null && "rangmanch".equals(u.getScheme())) {
            handleAuthUri(u);
            return true;
        }
        return false;
    }

    /** rangmanch://auth#access_token=...  ->  load the app with the same fragment so Supabase can read it. */
    private void handleAuthUri(Uri u) {
        StringBuilder sb = new StringBuilder(BASE).append("index.html");
        if (u.getEncodedQuery() != null) sb.append('?').append(u.getEncodedQuery());
        if (u.getEncodedFragment() != null) sb.append('#').append(u.getEncodedFragment());
        if (web != null) web.loadUrl(sb.toString());
    }

    private void openExternal(Uri u) {
        try {
            new CustomTabsIntent.Builder()
                    .setShowTitle(true)
                    .build()
                    .launchUrl(this, u);
        } catch (Exception e) {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, u));
            } catch (ActivityNotFoundException ignored) {
            }
        }
    }

    // ------------------------------------------------------------------ fullscreen video

    private void hideCustomView() {
        if (customView == null) return;
        root.removeView(customView);
        customView = null;
        web.setVisibility(View.VISIBLE);
        if (customViewCallback != null) {
            customViewCallback.onCustomViewHidden();
            customViewCallback = null;
        }
    }

    // ------------------------------------------------------------------ file chooser + permissions

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_FILE && fileCallback != null) {
            Uri[] result = WebChromeClient.FileChooserParams.parseResult(resultCode, data);
            fileCallback.onReceiveValue(result);
            fileCallback = null;
        }
    }

    private void handleWebPermission(PermissionRequest request) {
        if (!HOST.equals(request.getOrigin().getHost())) {
            request.deny();
            return;
        }
        List<String> needed = new ArrayList<>();
        for (String r : request.getResources()) {
            if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(r)
                    && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.CAMERA);
            } else if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r)
                    && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.RECORD_AUDIO);
            }
        }
        if (needed.isEmpty()) {
            request.grant(request.getResources());
            return;
        }
        if (pendingPermission != null) pendingPermission.deny();
        pendingPermission = request;
        requestPermissions(needed.toArray(new String[0]), REQ_PERM);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_PERM || pendingPermission == null) return;
        boolean ok = grantResults.length > 0;
        for (int g : grantResults) if (g != PackageManager.PERMISSION_GRANTED) ok = false;
        if (ok) pendingPermission.grant(pendingPermission.getResources());
        else pendingPermission.deny();
        pendingPermission = null;
    }

    // ------------------------------------------------------------------ bundled site

    /** Serves /web (packaged as assets) with correct MIME types. */
    private class SiteAssets implements WebViewAssetLoader.PathHandler {
        private final Map<String, String> mime = new HashMap<>();

        SiteAssets() {
            mime.put("html", "text/html");
            mime.put("css", "text/css");
            mime.put("js", "application/javascript");
            mime.put("json", "application/json");
            mime.put("webmanifest", "application/manifest+json");
            mime.put("png", "image/png");
            mime.put("jpg", "image/jpeg");
            mime.put("jpeg", "image/jpeg");
            mime.put("webp", "image/webp");
            mime.put("svg", "image/svg+xml");
            mime.put("ico", "image/x-icon");
            mime.put("woff2", "font/woff2");
            mime.put("woff", "font/woff");
            mime.put("ttf", "font/ttf");
            mime.put("txt", "text/plain");
        }

        @Override
        public WebResourceResponse handle(String path) {
            if (path == null || path.isEmpty() || path.endsWith("/")) {
                path = (path == null ? "" : path) + "index.html";
            }
            Map<String, String> headers = new HashMap<>();
            headers.put("Cache-Control", "no-cache");
            try {
                InputStream in = getAssets().open(path);
                int dot = path.lastIndexOf('.');
                String ext = dot >= 0 ? path.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
                String type = mime.containsKey(ext) ? mime.get(ext) : "application/octet-stream";
                String enc = type.startsWith("text/") || type.contains("json") || type.contains("javascript")
                        || type.contains("manifest") || type.contains("svg") ? "utf-8" : null;
                return new WebResourceResponse(type, enc, 200, "OK", headers, in);
            } catch (IOException e) {
                return new WebResourceResponse("text/plain", "utf-8", 404, "Not Found", headers,
                        new ByteArrayInputStream(new byte[0]));
            }
        }
    }
}
