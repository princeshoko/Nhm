package net.nerdhalla.sharla;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.SystemClock;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Public, read-only APK update checker. The app never installs APKs silently:
 * Android's browser/download UI and package installer remain in charge.
 */
final class AppUpdateHelper {
    private static final String APK_URL = "https://nerdhalla.net/nerdhalla-latest.apk";
    private static final String CERT_SHA256 =
            "a19dd27a5de82e5989d44c318e0a66b5ef2ea1e3fb43a76e90d49633f655eb35";
    private static final long CHECK_INTERVAL_MS = 60L * 60L * 1000L;

    private final Activity activity;
    private final ApiClient api;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main;

    private Button loginButton;
    private Button updateBanner;
    private TextView headerLink;
    private boolean checking;
    private long lastCheckElapsed;
    private long availableBuild;
    private String availableVersion = "";

    AppUpdateHelper(Activity activity, ApiClient api, Handler main) {
        this.activity = activity;
        this.api = api;
        this.main = main;
    }

    void attachLogin(Button button) {
        loginButton = button;
        updateBanner = null;
        headerLink = null;
        button.setOnClickListener(v -> pressedUpdateControl());
        refreshControls();
        checkForUpdates(false);
    }

    void attachNative(TextView link, Button banner) {
        loginButton = null;
        headerLink = link;
        updateBanner = banner;
        link.setOnClickListener(v -> pressedUpdateControl());
        banner.setOnClickListener(v -> showDownloadDialog());
        refreshControls();
        checkForUpdates(false);
    }

    void onDestroy() {
        io.shutdownNow();
    }

    void onResume() {
        if (loginButton == null && headerLink == null) return;
        if (lastCheckElapsed == 0 ||
                SystemClock.elapsedRealtime() - lastCheckElapsed >= CHECK_INTERVAL_MS) {
            checkForUpdates(false);
        }
    }

    private void pressedUpdateControl() {
        if (availableBuild > installedBuild()) {
            showDownloadDialog();
        } else {
            checkForUpdates(true);
        }
    }

    private long installedBuild() {
        try {
            PackageInfo pkg = activity.getPackageManager()
                    .getPackageInfo(activity.getPackageName(), 0);
            return Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                    ? pkg.getLongVersionCode() : pkg.versionCode;
        } catch (Exception ignored) {
            return -1;
        }
    }

    private void checkForUpdates(boolean requestedByUser) {
        if (checking) {
            if (requestedByUser) toast("Already checking for updates.");
            return;
        }
        checking = true;
        lastCheckElapsed = SystemClock.elapsedRealtime();

        io.execute(() -> {
            try {
                // The website's metadata is public; login is not required.
                JSONObject info = api.publicJson("apk-build.json");
                long latestBuild = info.optLong("build", -1L);
                String latestVersion = info.optString("version", "").trim();
                String signing = info.optString("signing_cert_sha256", "")
                        .toLowerCase(Locale.US);
                String sha = info.optString("sha256", "").toLowerCase(Locale.US);
                if (latestBuild < 1 || latestVersion.isEmpty()
                        || !"release".equals(info.optString("build_type"))
                        || !CERT_SHA256.equals(signing)
                        || !sha.matches("[a-f0-9]{64}")) {
                    throw new Exception("Unexpected APK release metadata.");
                }

                main.post(() -> {
                    checking = false;
                    availableBuild = latestBuild;
                    availableVersion = latestVersion;
                    refreshControls();
                    if (requestedByUser) {
                        if (latestBuild > installedBuild()) {
                            showDownloadDialog();
                        } else {
                            new AlertDialog.Builder(activity)
                                    .setTitle("Nerdhalla is up to date")
                                    .setMessage("You're running build " + installedBuild()
                                            + ", the latest available version.")
                                    .setPositiveButton("OK", null)
                                    .show();
                        }
                    }
                });
            } catch (Exception e) {
                main.post(() -> {
                    checking = false;
                    // Keep an already-known update available on transient errors.
                    if (requestedByUser) {
                        new AlertDialog.Builder(activity)
                                .setTitle("Couldn't check for updates")
                                .setMessage("Please try again with an internet connection. "
                                        + e.getMessage())
                                .setPositiveButton("OK", null)
                                .show();
                    }
                });
            }
        });
    }

    private void refreshControls() {
        boolean newer = availableBuild > installedBuild();
        if (loginButton != null) {
            loginButton.setText(newer
                    ? "Update available - Build " + availableBuild
                    : "Check for Updates");
        }
        if (headerLink != null) {
            headerLink.setText(newer
                    ? "⬇ Update build " + availableBuild
                    : "Check for Updates ↻");
            headerLink.setContentDescription(newer
                    ? "Download Nerdhalla build " + availableBuild
                    : "Check for Nerdhalla updates");
        }
        if (updateBanner != null) {
            updateBanner.setVisibility(newer ? View.VISIBLE : View.GONE);
            if (newer) {
                updateBanner.setText("⬇ Nerdhalla build " + availableBuild
                        + " available - Download");
            }
        }
    }

    private void showDownloadDialog() {
        if (availableBuild <= installedBuild()) {
            checkForUpdates(true);
            return;
        }
        new AlertDialog.Builder(activity)
                .setTitle("Nerdhalla update available")
                .setMessage("Installed: build " + installedBuild()
                        + "\nAvailable: " + availableVersion
                        + " (build " + availableBuild + ")"
                        + "\n\nDownload the official Nerdhalla APK? "
                        + "Android will ask you to approve installing the update.")
                .setNegativeButton("Not now", null)
                .setPositiveButton("Download APK", (dialog, which) -> {
                    try {
                        Intent browser = new Intent(Intent.ACTION_VIEW, Uri.parse(APK_URL));
                        browser.addCategory(Intent.CATEGORY_BROWSABLE);
                        activity.startActivity(browser);
                    } catch (Exception e) {
                        toast("Couldn't open the APK download in your browser.");
                    }
                })
                .show();
    }

    private void toast(String message) {
        Toast.makeText(activity, message, Toast.LENGTH_SHORT).show();
    }
}
