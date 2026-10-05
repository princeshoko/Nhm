package net.nerdhalla.sharla;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
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
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

public class MainActivity extends Activity {
    private static final String MY_SHARLA = "https://nerdhalla.net/account.html";
    private static final String ADMIN = "https://nerdhalla.net/admin.html";

    private static final int BG = Color.rgb(11, 8, 17);
    private static final int BG2 = Color.rgb(18, 12, 28);
    private static final int PANEL = Color.rgb(23, 16, 32);
    private static final int LINE = Color.rgb(53, 35, 71);
    private static final int TEXT = Color.rgb(245, 239, 255);
    private static final int MUTED = Color.rgb(170, 160, 183);
    private static final int PURPLE = Color.rgb(155, 108, 255);

    private WebView webView;
    private ProgressBar progress;
    private TextView error;
    private TextView pageTitle;
    private TextView pageSubtitle;
    private TextView mySharlaTab;
    private TextView adminTab;
    private String currentSection = "account";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);

        root.addView(buildAppBar(), new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(64)));

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setProgress(0);
        progress.setBackgroundColor(BG2);
        root.addView(progress, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(2)));

        error = new TextView(this);
        error.setTextColor(TEXT);
        error.setTextSize(13);
        error.setBackgroundColor(PANEL);
        error.setPadding(dp(16), dp(12), dp(16), dp(12));
        error.setVisibility(View.GONE);
        root.addView(error, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        webView = new WebView(this);
        webView.setBackgroundColor(BG);
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        webView.setScrollBarStyle(View.SCROLLBARS_INSIDE_OVERLAY);

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
        settings.setMediaPlaybackRequiresUserGesture(true);

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
                syncSectionFromUrl(url);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                syncSectionFromUrl(url);
                if (isNerdhallaDashboard(url)) {
                    applyAppModeCss();
                }
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError webError) {
                if (request.isForMainFrame()) {
                    showError("Could not load Nerdhalla.\n\n" + webError.getDescription());
                }
            }
        });

        root.addView(webView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        root.addView(buildBottomNav(), new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(68)));

        setContentView(root);

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState);
        } else {
            loadSection("account");
        }
    }

    private View buildAppBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(12), dp(8), dp(10), dp(8));
        bar.setBackgroundColor(BG2);

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.nerdhalla_icon);
        logo.setScaleType(ImageView.ScaleType.CENTER_CROP);
        GradientDrawable logoBg = new GradientDrawable();
        logoBg.setColor(Color.rgb(28, 20, 40));
        logoBg.setCornerRadius(dp(10));
        logoBg.setStroke(dp(1), LINE);
        logo.setBackground(logoBg);
        logo.setClipToOutline(true);
        bar.addView(logo, new LinearLayout.LayoutParams(dp(44), dp(44)));

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setGravity(Gravity.CENTER_VERTICAL);
        titles.setPadding(dp(12), 0, 0, 0);

        pageTitle = new TextView(this);
        pageTitle.setText("My Sharla");
        pageTitle.setTextColor(TEXT);
        pageTitle.setTextSize(17);
        pageTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);

        pageSubtitle = new TextView(this);
        pageSubtitle.setText("Nerdhalla");
        pageSubtitle.setTextColor(MUTED);
        pageSubtitle.setTextSize(12);

        titles.addView(pageTitle);
        titles.addView(pageSubtitle);
        bar.addView(titles, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, 1f));

        TextView refresh = new TextView(this);
        refresh.setText("↻");
        refresh.setTextColor(TEXT);
        refresh.setTextSize(28);
        refresh.setGravity(Gravity.CENTER);
        refresh.setContentDescription("Refresh");
        refresh.setOnClickListener(v -> webView.reload());
        GradientDrawable refreshBg = new GradientDrawable();
        refreshBg.setColor(PANEL);
        refreshBg.setCornerRadius(dp(12));
        refreshBg.setStroke(dp(1), LINE);
        refresh.setBackground(refreshBg);
        bar.addView(refresh, new LinearLayout.LayoutParams(dp(44), dp(44)));

        return bar;
    }

    private View buildBottomNav() {
        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setGravity(Gravity.CENTER);
        nav.setPadding(dp(8), dp(7), dp(8), dp(7));
        nav.setBackgroundColor(BG2);

        mySharlaTab = makeNavTab("My Sharla");
        adminTab = makeNavTab("Admin");

        mySharlaTab.setOnClickListener(v -> loadSection("account"));
        adminTab.setOnClickListener(v -> loadSection("admin"));

        LinearLayout.LayoutParams tabParams = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, 1f);
        tabParams.setMargins(dp(4), 0, dp(4), 0);
        nav.addView(mySharlaTab, tabParams);
        nav.addView(adminTab, tabParams);

        updateTabs();
        return nav;
    }

    private TextView makeNavTab(String label) {
        TextView tab = new TextView(this);
        tab.setText(label);
        tab.setTextSize(13);
        tab.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        tab.setGravity(Gravity.CENTER);
        tab.setPadding(dp(8), dp(6), dp(8), dp(6));
        return tab;
    }

    private void loadSection(String section) {
        currentSection = section;
        updateTabs();
        error.setVisibility(View.GONE);
        progress.setVisibility(View.VISIBLE);

        if ("admin".equals(section)) {
            pageTitle.setText("Sharla Admin");
            pageSubtitle.setText("Nerdhalla controls");
            webView.loadUrl(ADMIN);
        } else {
            pageTitle.setText("My Sharla");
            pageSubtitle.setText("Your Nerdhalla profile");
            webView.loadUrl(MY_SHARLA);
        }
    }

    private void syncSectionFromUrl(String url) {
        if (url == null) return;

        if (url.contains("/admin.html")) {
            currentSection = "admin";
            pageTitle.setText("Sharla Admin");
            pageSubtitle.setText("Nerdhalla controls");
            updateTabs();
        } else if (url.contains("/account.html")) {
            currentSection = "account";
            pageTitle.setText("My Sharla");
            pageSubtitle.setText("Your Nerdhalla profile");
            updateTabs();
        }
    }

    private void updateTabs() {
        styleTab(mySharlaTab, "account".equals(currentSection));
        styleTab(adminTab, "admin".equals(currentSection));
    }

    private void styleTab(TextView tab, boolean active) {
        if (tab == null) return;

        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(14));

        if (active) {
            bg.setColor(Color.rgb(52, 36, 78));
            bg.setStroke(dp(1), PURPLE);
            tab.setTextColor(TEXT);
        } else {
            bg.setColor(BG2);
            bg.setStroke(dp(1), LINE);
            tab.setTextColor(MUTED);
        }
        tab.setBackground(bg);
    }

    private boolean isNerdhallaDashboard(String url) {
        return url != null &&
                url.startsWith("https://nerdhalla.net/") &&
                (url.contains("account.html") || url.contains("admin.html"));
    }

    private void applyAppModeCss() {
        String js = "(function(){"
                + "var old=document.getElementById('nerdhalla-app-mode');"
                + "if(old)old.remove();"
                + "var s=document.createElement('style');"
                + "s.id='nerdhalla-app-mode';"
                + "s.innerHTML='"
                + ".topbar{display:none!important;}"
                + "footer,.site-footer{display:none!important;}"
                + ".mobile-nav,.mobile-nav-backdrop{display:none!important;}"
                + "html,body{background:#0b0811!important;margin:0!important;padding-top:0!important;}"
                + "body{min-height:100vh!important;}"
                + "main{padding-top:12px!important;padding-bottom:20px!important;}"
                + ".wrap,.container{max-width:100%!important;}"
                + "@media(max-width:700px){main{padding-left:10px!important;padding-right:10px!important;}"
                + ".card,.panel{border-radius:14px!important;}}"
                + "';"
                + "document.head.appendChild(s);"
                + "})();";
        webView.evaluateJavascript(js, null);
    }

    private void showError(String message) {
        error.setText(message + "\n\nUse the bottom tabs to retry.");
        error.setVisibility(View.VISIBLE);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        webView.saveState(outState);
        outState.putString("section", currentSection);
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
