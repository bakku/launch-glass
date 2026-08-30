package dev.bakku.launchglass;

import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.webkit.CookieManager;
import android.webkit.SslErrorHandler;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;

import androidx.activity.ComponentActivity;
import androidx.activity.OnBackPressedCallback;

public final class WebAppActivity extends ComponentActivity {
    private static final String LOCAL_NETWORK_PERMISSION =
            "android.permission.ACCESS_LOCAL_NETWORK";
    private static final int LOCAL_NETWORK_REQUEST = 100;
    private static final int FIRST_FILE_CHOOSER_REQUEST = 101;

    private FrameLayout root;
    private WebView webView;
    private ProgressBar progressBar;
    private ValueCallback<Uri[]> pendingFileSelection;
    private Bundle pendingState;
    private ServiceCatalog.Service service;
    private int nextFileChooserRequest = FIRST_FILE_CHOOSER_REQUEST;
    private int pendingFileChooserRequest = -1;
    private boolean pageStarted;
    private boolean localNetworkRequestPending;
    private boolean showingError;
    private boolean waitingForSettings;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        service = resolveService(getIntent());
        if (service == null) {
            finish();
            return;
        }
        setTitle(service.label);
        boolean debuggable = (getApplicationInfo().flags
                & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        WebView.setWebContentsDebuggingEnabled(debuggable);

        pendingState = savedInstanceState;
        createWebView();
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (webView != null && webView.canGoBack()) {
                    webView.goBack();
                } else {
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                }
            }
        });

        startWhenPermitted();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        ServiceCatalog.Service nextService = resolveService(intent);
        if (nextService == null) {
            return;
        }
        setIntent(intent);
        if (service.componentClassName.equals(nextService.componentClassName)) {
            if (showingError) {
                cancelPendingFileSelection();
                pendingState = null;
                pageStarted = false;
                waitingForSettings = false;
                destroyWebView();
                createWebView();
                startWhenPermitted();
            }
            return;
        }

        cancelPendingFileSelection();
        service = nextService;
        setTitle(service.label);
        pendingState = null;
        pageStarted = false;
        waitingForSettings = false;
        destroyWebView();
        createWebView();

        startWhenPermitted();
    }

    private ServiceCatalog.Service resolveService(Intent intent) {
        ComponentName launchedComponent = intent == null ? null : intent.getComponent();
        return ServiceCatalog.forComponent(
                launchedComponent == null ? null : launchedComponent.getClassName());
    }

    private void cancelPendingFileSelection() {
        if (pendingFileSelection != null) {
            pendingFileSelection.onReceiveValue(null);
            pendingFileSelection = null;
        }
        pendingFileChooserRequest = -1;
    }

    private void createWebView() {
        root = new FrameLayout(this);
        webView = new WebView(this);
        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);

        root.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        FrameLayout.LayoutParams progressParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP);
        root.addView(progressBar, progressParams);
        root.setOnApplyWindowInsetsListener((view, windowInsets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets insets = windowInsets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                view.setPadding(insets.left, insets.top, insets.right, insets.bottom);
            } else {
                view.setPadding(
                        windowInsets.getSystemWindowInsetLeft(),
                        windowInsets.getSystemWindowInsetTop(),
                        windowInsets.getSystemWindowInsetRight(),
                        windowInsets.getSystemWindowInsetBottom());
            }
            return windowInsets;
        });
        showingError = false;
        setContentView(root);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(false);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setSafeBrowsingEnabled(true);
        settings.setSupportMultipleWindows(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);

        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.setAcceptThirdPartyCookies(webView, false);

        webView.setWebViewClient(new SecureWebViewClient(service));
        ProgressBar clientProgressBar = progressBar;
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int progress) {
                if (view != webView) {
                    return;
                }
                clientProgressBar.setProgress(progress);
                clientProgressBar.setVisibility(progress < 100 ? View.VISIBLE : View.GONE);
            }

            @Override
            public boolean onShowFileChooser(
                    WebView view,
                    ValueCallback<Uri[]> filePathCallback,
                    FileChooserParams fileChooserParams) {
                if (view != webView) {
                    filePathCallback.onReceiveValue(null);
                    return true;
                }
                if (pendingFileSelection != null) {
                    pendingFileSelection.onReceiveValue(null);
                }
                pendingFileSelection = filePathCallback;

                Intent picker;
                try {
                    picker = fileChooserParams.createIntent();
                    picker.addCategory(Intent.CATEGORY_OPENABLE);
                    pendingFileChooserRequest = nextFileChooserRequest;
                    nextFileChooserRequest = nextFileChooserRequest == 0xFFFE
                            ? FIRST_FILE_CHOOSER_REQUEST : nextFileChooserRequest + 1;
                    startActivityForResult(picker, pendingFileChooserRequest);
                } catch (ActivityNotFoundException exception) {
                    pendingFileSelection.onReceiveValue(null);
                    pendingFileSelection = null;
                    pendingFileChooserRequest = -1;
                    Toast.makeText(WebAppActivity.this,
                            R.string.no_file_picker, Toast.LENGTH_LONG).show();
                    return true;
                }
                return true;
            }
        });
    }

    private boolean needsLocalNetworkPermission() {
        return service.localNetwork
                && Build.VERSION.SDK_INT >= 37
                && getApplicationInfo().targetSdkVersion >= 37
                && checkSelfPermission(LOCAL_NETWORK_PERMISSION) != PackageManager.PERMISSION_GRANTED;
    }

    private void startWhenPermitted() {
        if (!needsLocalNetworkPermission()) {
            startPage();
            return;
        }
        if (!localNetworkRequestPending) {
            localNetworkRequestPending = true;
            requestPermissions(new String[]{LOCAL_NETWORK_PERMISSION}, LOCAL_NETWORK_REQUEST);
        }
    }

    private void startPage() {
        if (pageStarted) {
            return;
        }
        pageStarted = true;
        if (pendingState == null || webView.restoreState(pendingState) == null) {
            webView.loadUrl(service.startUrl);
        }
        pendingState = null;
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != LOCAL_NETWORK_REQUEST) {
            return;
        }
        localNetworkRequestPending = false;
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startPage();
        } else {
            showLocalNetworkPermissionError();
        }
    }

    private void showLocalNetworkPermissionError() {
        showMessage(
                getString(R.string.local_network_required, service.label),
                getString(R.string.open_settings),
                view -> {
                    Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:" + getPackageName()));
                    try {
                        waitingForSettings = true;
                        startActivity(intent);
                    } catch (ActivityNotFoundException exception) {
                        waitingForSettings = false;
                        Toast.makeText(this, R.string.no_settings_app, Toast.LENGTH_LONG).show();
                    }
                });
    }

    private void showTlsError() {
        showMessage(getString(R.string.tls_error, service.label), null, null);
    }

    private void showMessage(String message, String action, View.OnClickListener listener) {
        if (webView != null) {
            webView.stopLoading();
        }
        showingError = true;
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER);
        int padding = Math.round(24 * getResources().getDisplayMetrics().density);
        panel.setPadding(padding, padding, padding, padding);
        panel.setBackgroundColor(Color.rgb(11, 19, 43));

        TextView text = new TextView(this);
        text.setText(message);
        text.setTextColor(Color.WHITE);
        text.setTextSize(16);
        text.setGravity(Gravity.CENTER);
        panel.addView(text);

        if (action != null) {
            Button button = new Button(this);
            button.setText(action);
            button.setOnClickListener(listener);
            panel.addView(button);
        }
        setContentView(panel);
    }

    private void openExternal(Uri uri) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (ActivityNotFoundException exception) {
            Toast.makeText(this, R.string.no_browser, Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == pendingFileChooserRequest && pendingFileSelection != null) {
            pendingFileSelection.onReceiveValue(
                    WebChromeClient.FileChooserParams.parseResult(resultCode, data));
            pendingFileSelection = null;
            pendingFileChooserRequest = -1;
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        if (pageStarted) {
            webView.saveState(outState);
        }
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (waitingForSettings && !needsLocalNetworkPermission()) {
            waitingForSettings = false;
            showingError = false;
            setContentView(root);
            startPage();
        }
    }

    @Override
    protected void onPause() {
        CookieManager.getInstance().flush();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        cancelPendingFileSelection();
        destroyWebView();
        super.onDestroy();
    }

    private void destroyWebView() {
        if (webView == null) {
            return;
        }
        webView.stopLoading();
        webView.setWebChromeClient(null);
        webView.setWebViewClient(null);
        if (root != null) {
            root.removeView(webView);
        }
        webView.destroy();
        webView = null;
    }

    private final class SecureWebViewClient extends WebViewClient {
        private final ServiceCatalog.Service clientService;

        private SecureWebViewClient(ServiceCatalog.Service clientService) {
            this.clientService = clientService;
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            if (!request.isForMainFrame()) {
                return false;
            }
            return route(view, request.getUrl());
        }

        @Override
        @SuppressWarnings("deprecation")
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            return route(view, Uri.parse(url));
        }

        @Override
        public WebResourceResponse shouldInterceptRequest(
                WebView view, WebResourceRequest request) {
            if (request.isForMainFrame()
                    && UrlPolicy.classify(request.getUrl().toString(), clientService.origin)
                    != UrlPolicy.Destination.INTERNAL) {
                byte[] body = getString(R.string.blocked_navigation)
                        .getBytes(StandardCharsets.UTF_8);
                return new WebResourceResponse(
                        "text/plain",
                        "UTF-8",
                        403,
                        "Blocked",
                        Collections.emptyMap(),
                        new ByteArrayInputStream(body));
            }
            return null;
        }

        private boolean route(WebView source, Uri uri) {
            if (source != webView) {
                return true;
            }
            UrlPolicy.Destination destination =
                    UrlPolicy.classify(uri.toString(), clientService.origin);
            if (destination == UrlPolicy.Destination.INTERNAL) {
                return false;
            }
            if (destination == UrlPolicy.Destination.EXTERNAL) {
                openExternal(uri);
            } else {
                Toast.makeText(WebAppActivity.this,
                        R.string.blocked_navigation, Toast.LENGTH_LONG).show();
            }
            return true;
        }

        @Override
        public void onReceivedSslError(
                WebView view, SslErrorHandler handler, SslError error) {
            handler.cancel();
            if (view == webView) {
                showTlsError();
            }
        }
    }
}
