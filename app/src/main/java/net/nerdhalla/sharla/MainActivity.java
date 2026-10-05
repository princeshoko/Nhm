package net.nerdhalla.sharla;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

public class MainActivity extends Activity {
    private static final String MY_SHARLA = "https://nerdhalla.net/account.html";
    private static final String ADMIN = "https://nerdhalla.net/admin.html";

    private WebView webView;
    private ProgressBar progress;
    private TextView error;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(11, 8, 17));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(8), dp(8), dp(8), dp(8));
        top.setBackgroundColor(Color.rgb(18, 12, 28));

        Button mySharla = makeButton("My Sharla");
        Button admin = makeButton("Admin");

        LinearLayout.LayoutParams buttonParams =
                new LinearLayout.LayoutParams(0, dp(44), 1f);
        buttonParams.setMargins(dp(4), 0, dp(4), 0);
        top.addView(mySharla, buttonParams);
        top.addView(admin, buttonParams);

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setProgress(0);

        error = new TextView(this);
        error.setTextColor(Color.rgb(245, 239, 255));
        error.setBackgroundColor(Color.rgb(23, 16, 32));
        error.setPadding(dp(16), dp(14), dp(16), dp(14));
        error.setVisibility(View.GONE);

        webView = new WebView(this);
        webView.setBackgroundColor(Color.rgb(11, 8, 17));

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setLoadWithOverviewMode(false);
        settings.setUseWideViewPort(false);
        settings.setSupportZoom(true);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);

        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(webView, true);

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progress.setProgress(newProgress);
                progress.setVisibility(newProgress >= 100 ? View.GONE : View.VISIBLE);
            }
        });

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return false;
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                error.setVisibility(View.GONE);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError webError) {
                if (request.isForMainFrame()) {
                    showError("Could not load Nerdhalla.\n\n" + webError.getDescription());
                }
            }
        });

        mySharla.setOnClickListener(v -> load(MY_SHARLA));
        admin.setOnClickListener(v -> load(ADMIN));

        root.addView(top, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(progress, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(3)));
        root.addView(error, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(webView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState);
        } else {
            load(MY_SHARLA);
        }
    }

    private Button makeButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextColor(Color.WHITE);
        button.setTextSize(14);
        button.setBackgroundColor(Color.rgb(79, 70, 200));
        return button;
    }

    private void load(String url) {
        error.setVisibility(View.GONE);
        progress.setVisibility(View.VISIBLE);
        webView.loadUrl(url);
    }

    private void showError(String message) {
        error.setText(message + "\n\nTap My Sharla or Admin to retry.");
        error.setVisibility(View.VISIBLE);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        webView.saveState(outState);
        super.onSaveInstanceState(outState);
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.stopLoading();
            webView.destroy();
        }
        super.onDestroy();
    }
}
