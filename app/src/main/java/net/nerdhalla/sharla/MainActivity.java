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
    private static final int PURPLE = Color.rgb(155, 108, 255);
    private static final int PURPLE_DARK = Color.rgb(74, 48, 112);
    private static final int TEXT = Color.rgb(245, 239, 255);
    private static final int MUTED = Color.rgb(170, 160, 183);

    private WebView webView;
    private ProgressBar progress;
    private TextView error;
    private TextView sectionTitle;
    private TextView mySharlaNav;
    private TextView adminNav;
    private String currentSection = "My Sharla";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);

        root.addView(buildAppBar(), new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(72)));

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setProgress(0);
        progress.setIndeterminate(false);
        root.addView(progress, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(3)));

        error = new TextView(this);
        error.setTextColor(TEXT);
        error.setTextSize(14);
        error.setBackgroundColor(PANEL);
        error.setPadding(dp(16), dp(14), dp(16), dp(14));
        error.setVisibility(View.GONE);
        root.addView(error, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        webView = new WebView(this);
        webView.setBackgroundColor(BG);

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
        settings.setMediaPlaybackRequiresUserGesture(false);

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
                progress.setVisibility(View.VISIBLE);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (url != null && url.contains("nerdhalla.net")) {
                    applyAppStyle(view);
                }
                updateTitleForUrl(url);
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
            loadSection(MY_SHARLA, "My Sharla");
        }
    }

    private View buildAppBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(14), dp(9), dp(10), dp(9));
        bar.setBackgroundColor(BG2);

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.nerdhalla_icon);
        logo.setScaleType(ImageView.ScaleType.CENTER_CROP);
        logo.setBackground(roundRect(PANEL, dp(12), PURPLE_DARK, dp(1)));
        logo.setPadding(dp(2), dp(2), dp(2), dp(2));
        bar.addView(logo, new LinearLayout.LayoutParams(dp(50), dp(50)));

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setGravity(Gravity.CENTER_VERTICAL);
        titles.setPadding(dp(12), 0, 0, 0);

        TextView brand = new TextView(this);
        brand.setText("NERDHALLA");
        brand.setTextColor(TEXT);
        brand.setTextSize(18);
        brand.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        titles.addView(brand);

        sectionTitle = new TextView(this);
        sectionTitle.setText("My Sharla");
        sectionTitle.setTextColor(MUTED);
        sectionTitle.setTextSize(13);
        titles.addView(sectionTitle);

        bar.addView(titles, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, 1f));

        TextView refresh = new TextView(this);
        refresh.setText("↻");
        refresh.setTextColor(TEXT);
        refresh.setTextSize(26);
        refresh.setGravity(Gravity.CENTER);
        refresh.setBackground(roundRect(PANEL, dp(12), PURPLE_DARK, dp(1)));
        refresh.setOnClickListener(v -> {
            if (webView != null) webView.reload();
        });
        bar.addView(refresh, new LinearLayout.LayoutParams(dp(48), dp(48)));

        return bar;
    }

    private View buildBottomNav() {
        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setGravity(Gravity.CENTER);
        nav.setPadding(dp(10), dp(8), dp(10), dp(8));
        nav.setBackgroundColor(BG2);

        mySharlaNav = makeNavItem("●\nMy Sharla");
        adminNav = makeNavItem("◆\nAdmin");

        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, 1f);
        p.setMargins(dp(4), 0, dp(4), 0);

        nav.addView(mySharlaNav, p);
        nav.addView(adminNav, p);

        mySharlaNav.setOnClickListener(v -> loadSection(MY_SHARLA, "My Sharla"));
        adminNav.setOnClickListener(v -> loadSection(ADMIN, "Admin"));

        updateNavSelection("My Sharla");
        return nav;
    }

    private TextView makeNavItem(String label) {
        TextView item = new TextView(this);
        item.setText(label);
        item.setGravity(Gravity.CENTER);
        item.setTextSize(12);
        item.setTextColor(MUTED);
        item.setPadding(dp(8), dp(4), dp(8), dp(4));
        item.setBackground(roundRect(Color.TRANSPARENT, dp(14), Color.TRANSPARENT, 0));
        return item;
    }

    private void loadSection(String url, String section) {
        currentSection = section;
        updateNavSelection(section);
        sectionTitle.setText(section);
        error.setVisibility(View.GONE);
        progress.setVisibility(View.VISIBLE);
        webView.loadUrl(url);
    }

    private void updateNavSelection(String section) {
        if (mySharlaNav == null || adminNav == null) return;

        boolean my = "My Sharla".equals(section);

        mySharlaNav.setTextColor(my ? TEXT : MUTED);
        mySharlaNav.setBackground(roundRect(my ? Color.rgb(55, 38, 78) : Color.TRANSPARENT,
                dp(14), my ? PURPLE_DARK : Color.TRANSPARENT, my ? dp(1) : 0));

        adminNav.setTextColor(!my ? TEXT : MUTED);
        adminNav.setBackground(roundRect(!my ? Color.rgb(55, 38, 78) : Color.TRANSPARENT,
                dp(14), !my ? PURPLE_DARK : Color.TRANSPARENT, !my ? dp(1) : 0));
    }

    private void updateTitleForUrl(String url) {
        if (url == null) return;
        if (url.contains("/admin")) {
            currentSection = "Admin";
        } else if (url.contains("/account")) {
            currentSection = "My Sharla";
        }
        sectionTitle.setText(currentSection);
        updateNavSelection(currentSection);
    }

    private void applyAppStyle(WebView view) {
        String js =
                "(function(){" +
                "var s=document.getElementById('nerdhalla-app-style');" +
                "if(!s){s=document.createElement('style');s.id='nerdhalla-app-style';" +
                "s.innerHTML='" +
                ".topbar,body>header,footer,.site-footer,.mobile-nav,#mobile-nav,.mobile-menu,.mobile-menu-toggle{display:none!important;}" +
                "html,body{background:#0b0811!important;margin:0!important;padding:0!important;}" +
                "body{min-height:100vh!important;}" +
                "main{padding-top:12px!important;padding-bottom:18px!important;}" +
                "a,button,input,select,textarea{-webkit-tap-highlight-color:transparent;}" +
                "';document.head.appendChild(s);}" +
                "var v=document.querySelector('meta[name=viewport]');" +
                "if(!v){v=document.createElement('meta');v.name='viewport';document.head.appendChild(v);}" +
                "v.content='width=device-width,initial-scale=1,maximum-scale=1,viewport-fit=cover';" +
                "})();";
        view.evaluateJavascript(js, null);
    }

    private void showError(String message) {
        error.setText(message + "\n\nUse the bottom navigation to retry.");
        error.setVisibility(View.VISIBLE);
    }

    private GradientDrawable roundRect(int fill, int radius, int stroke, int strokeWidth) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(radius);
        if (strokeWidth > 0) d.setStroke(strokeWidth, stroke);
        return d;
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
