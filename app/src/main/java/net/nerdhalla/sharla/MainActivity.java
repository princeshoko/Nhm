package net.nerdhalla.sharla;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.app.DownloadManager;
import android.app.TimePickerDialog;
import android.content.Intent;
import android.net.Uri;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Environment;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.webkit.CookieManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.net.URL;
import java.net.URLEncoder;
import java.text.NumberFormat;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final String BASE = "https://nerdhalla.net/";
    private static final String MOBILE_LOGIN = BASE + "mobile-login.php";

    private static final int BG = Color.rgb(11, 8, 17);
    private static final int BG2 = Color.rgb(18, 12, 28);
    private static final int PANEL = Color.rgb(23, 16, 32);
    private static final int PANEL2 = Color.rgb(30, 21, 42);
    private static final int LINE = Color.rgb(53, 35, 71);
    private static final int TEXT = Color.rgb(245, 239, 255);
    private static final int MUTED = Color.rgb(170, 160, 183);
    private static final int PURPLE = Color.rgb(155, 108, 255);
    private static final int GREEN = Color.rgb(111, 215, 168);
    private static final int GOLD = Color.rgb(228, 185, 93);
    private static final int RED = Color.rgb(225, 95, 105);

    private final ApiClient api = new ApiClient();
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private FrameLayout root;
    private LinearLayout nativeRoot;
    private FrameLayout contentHost;
    private ProgressBar busy;
    private TextView titleText;
    private TextView myNav;
    private TextView adminNav;
    private LinearLayout bottomNav;
    private boolean adminButtonAllowed = false;
    private HorizontalScrollView activeMenuScroller;
    private float swipeDownX;
    private float swipeDownY;
    private boolean swipeTracking;
    private int pendingMenuTransitionDirection = 0;
    private boolean menuTransitionRunning = false;

    private JSONObject session;
    private JSONObject meData;
    private long meDataFetchedElapsed = 0L;
    private JSONObject adminAccess;
    private JSONObject guildData;
    private String currentGuildId = "";
    private String currentTop = "my";
    private String currentMySection = "profile";
    private String currentAdminSection = "health";
    private long scheduleAtMillis = 0;
    private boolean authCheckRunning = false;

    private String selectedMemberId = "";
    private String selectedMemberName = "";
    private TextView memberModerationSelected;
    private EditText memberModerationReason;
    private Spinner memberTimeoutSpinner;
    private Spinner memberRoleSpinner;
    private List<JSONObject> memberRoleObjects = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);

        CookieManager.getInstance().setAcceptCookie(true);

        root = new FrameLayout(this);
        root.setBackgroundColor(BG);

        // Android 15+ enforces edge-to-edge for target SDK 35. Keep all app
        // content inside the safe system-bar/cutout area so the top app bar
        // stays below the clock/status icons and the bottom navigation stays
        // above Android's navigation buttons/gesture area.
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                android.graphics.Insets safe = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                v.setPadding(safe.left, safe.top, safe.right, safe.bottom);
            } else {
                v.setPadding(
                        insets.getSystemWindowInsetLeft(),
                        insets.getSystemWindowInsetTop(),
                        insets.getSystemWindowInsetRight(),
                        insets.getSystemWindowInsetBottom());
            }
            return insets;
        });

        setContentView(root);
        root.requestApplyInsets();

        showStartup();
        if (!handleAuthIntent(getIntent())) {
            checkSession();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (!handleAuthIntent(intent)) {
            checkSession();
        }
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (event != null && nativeRoot != null && nativeRoot.getParent() != null) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    swipeDownX = event.getRawX();
                    swipeDownY = event.getRawY();
                    swipeTracking = !isTouchInsideActiveMenu(event.getRawX(), event.getRawY());
                    break;
                case MotionEvent.ACTION_UP:
                    if (swipeTracking) {
                        float dx = event.getRawX() - swipeDownX;
                        float dy = event.getRawY() - swipeDownY;
                        float absX = Math.abs(dx);
                        float absY = Math.abs(dy);
                        if (absX >= dp(70) && absX > absY * 1.35f) {
                            int direction = dx < 0 ? 1 : -1;
                            main.post(() -> navigateMenuBySwipe(direction));
                        }
                    }
                    swipeTracking = false;
                    break;
                case MotionEvent.ACTION_CANCEL:
                    swipeTracking = false;
                    break;
            }
        }
        return super.dispatchTouchEvent(event);
    }

    private boolean isTouchInsideActiveMenu(float rawX, float rawY) {
        if (activeMenuScroller == null || !activeMenuScroller.isShown()) return false;
        Rect rect = new Rect();
        return activeMenuScroller.getGlobalVisibleRect(rect) &&
                rect.contains((int) rawX, (int) rawY);
    }

    private void navigateMenuBySwipe(int direction) {
        if (direction == 0 ||
                menuTransitionRunning ||
                (busy != null && busy.getVisibility() == View.VISIBLE)) return;

        if ("my".equals(currentTop)) {
            if (meData == null) return;
            List<String> keys = list("profile", "homeworlds", "inventory", "achievements");
            int current = keys.indexOf(currentMySection);
            if (current < 0) current = 0;
            int next = Math.floorMod(current + direction, keys.size());
            pendingMenuTransitionDirection = direction;
            currentMySection = keys.get(next);
            renderMySharla();
            return;
        }

        if ("admin".equals(currentTop)) {
            if (adminAccess == null || guildData == null) return;
            List<String> keys = visibleAdminTabKeys();
            if (keys.isEmpty()) return;
            int current = keys.indexOf(currentAdminSection);
            if (current < 0) current = 0;
            int next = Math.floorMod(current + direction, keys.size());
            pendingMenuTransitionDirection = direction;
            currentAdminSection = keys.get(next);
            renderAdmin();
        }
    }

    private List<String> visibleAdminTabKeys() {
        List<String> keys = new ArrayList<>();
        if (hasPerm("live_health")) keys.add("health");
        if (guildData != null && guildData.optBoolean("full_admin", false)) keys.add("talk");
        if (hasPerm("announcements") || hasPerm("scheduled_announcements")) keys.add("announce");
        if (hasPerm("member_lookup")) keys.add("members");
        if (hasPerm("homeworlds_admin")) keys.add("homeworlds");
        if (hasPerm("server_settings")) keys.add("settings");
        if (isAdminOwner()) {
            keys.add("moderators");
            keys.add("backups");
        }
        return keys;
    }

    private boolean handleAuthIntent(Intent intent) {
        if (intent == null || intent.getData() == null) return false;
        Uri data = intent.getData();
        if (!"nerdhallasharla".equalsIgnoreCase(data.getScheme()) ||
                !"auth".equalsIgnoreCase(data.getHost())) {
            return false;
        }
        String cancelled = data.getQueryParameter("cancelled");
        if ("1".equals(cancelled) || "true".equalsIgnoreCase(cancelled)) {
            intent.setData(null);
            showLogin();
            return true;
        }

        String code = data.getQueryParameter("code");
        if (code == null || code.trim().isEmpty()) {
            showLogin();
            toast("Nerdhalla login did not return a handoff code.");
            return true;
        }
        intent.setData(null);
        showStartup();
        exchangeMobileLogin(code.trim());
        return true;
    }

    private void exchangeMobileLogin(String code) {
        io.execute(() -> {
            try {
                api.exchangeMobileAuth(code);
                JSONObject s = api.getSession();
                if (!s.optBoolean("logged_in", false)) {
                    throw new Exception("Nerdhalla did not create an app session.");
                }
                main.post(() -> {
                    session = s;
                    api.setCsrfToken(s.optString("csrf_token", ""));
                    showNativeApp();
                });
            } catch (Exception e) {
                main.post(() -> {
                    showLogin();
                    toast("Login handoff failed: " + e.getMessage());
                });
            }
        });
    }

    private void showStartup() {
        root.removeAllViews();
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setPadding(dp(28), dp(28), dp(28), dp(28));

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.nerdhalla_icon);
        logo.setScaleType(ImageView.ScaleType.CENTER_CROP);
        box.addView(logo, new LinearLayout.LayoutParams(dp(96), dp(96)));

        TextView title = text("NERDHALLA", 24, TEXT, true);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, dp(18), 0, dp(6));
        box.addView(title);

        TextView sub = text("Loading Sharla…", 14, MUTED, false);
        sub.setGravity(Gravity.CENTER);
        box.addView(sub);

        ProgressBar p = new ProgressBar(this);
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(dp(48), dp(48));
        pp.topMargin = dp(18);
        box.addView(p, pp);

        root.addView(box, match());
    }

    private void checkSession() {
        if (authCheckRunning) return;
        authCheckRunning = true;
        io.execute(() -> {
            try {
                JSONObject s = api.getSession();
                boolean logged = s.optBoolean("logged_in", false);
                main.post(() -> {
                    authCheckRunning = false;
                    if (logged) {
                        session = s;
                        api.setCsrfToken(s.optString("csrf_token", ""));
                        showNativeApp();
                    } else {
                        showLogin();
                    }
                });
            } catch (Exception e) {
                main.post(() -> {
                    authCheckRunning = false;
                    showLogin();
                });
            }
        });
    }

    private void showLogin() {
        root.removeAllViews();

        LinearLayout shell = new LinearLayout(this);
        shell.setOrientation(LinearLayout.VERTICAL);
        shell.setGravity(Gravity.CENTER);
        shell.setPadding(dp(30), dp(30), dp(30), dp(30));
        shell.setBackgroundColor(BG);

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.nerdhalla_icon);
        logo.setScaleType(ImageView.ScaleType.CENTER_CROP);
        shell.addView(logo, new LinearLayout.LayoutParams(dp(128), dp(128)));

        TextView brand = text("NERDHALLA", 28, TEXT, true);
        brand.setGravity(Gravity.CENTER);
        brand.setPadding(0, dp(22), 0, dp(24));
        shell.addView(brand);

        Button login = primaryButton("Login with Discord");
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(54));
        lp.setMargins(dp(10), 0, dp(10), 0);
        shell.addView(login, lp);

        login.setOnClickListener(v -> {
            try {
                Intent browser = new Intent(Intent.ACTION_VIEW, Uri.parse(MOBILE_LOGIN));
                browser.addCategory(Intent.CATEGORY_BROWSABLE);
                startActivity(browser);
            } catch (Exception e) {
                toast("Could not open your browser.");
            }
        });

        root.addView(shell, match());
    }

    private void showNativeApp() {
        root.removeAllViews();

        nativeRoot = new LinearLayout(this);
        nativeRoot.setOrientation(LinearLayout.VERTICAL);
        nativeRoot.setBackgroundColor(BG);

        nativeRoot.addView(buildAppBar(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(72)));

        busy = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        busy.setIndeterminate(true);
        busy.setVisibility(View.GONE);
        nativeRoot.addView(busy, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(3)));

        contentHost = new FrameLayout(this);
        contentHost.setBackgroundColor(BG);
        nativeRoot.addView(contentHost, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        nativeRoot.addView(buildBottomNav(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(70)));

        root.addView(nativeRoot, match());

        currentTop = "my";
        adminButtonAllowed = false;
        updateBottomNav();
        checkAdminButtonAccess();
        loadMySharla();
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
        logo.setBackground(roundRect(PANEL, 12, LINE, 1));
        bar.addView(logo, new LinearLayout.LayoutParams(dp(50), dp(50)));

        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setPadding(dp(12), 0, 0, 0);

        labels.addView(text("NERDHALLA", 18, TEXT, true));
        titleText = text("My Sharla", 13, MUTED, false);
        labels.addView(titleText);

        bar.addView(labels, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        TextView refresh = actionIcon("↻");
        refresh.setOnClickListener(v -> {
            if ("my".equals(currentTop)) {
                meData = null;
                loadMySharla();
            } else {
                adminAccess = null;
                guildData = null;
                checkAdminButtonAccess();
                loadAdmin();
            }
        });
        bar.addView(refresh, new LinearLayout.LayoutParams(dp(48), dp(48)));

        Button logout = secondaryButton("Logout");
        logout.setPadding(dp(8), 0, dp(8), 0);
        LinearLayout.LayoutParams logoutParams =
                new LinearLayout.LayoutParams(dp(82), dp(48));
        logoutParams.leftMargin = dp(6);
        bar.addView(logout, logoutParams);
        logout.setOnClickListener(v -> confirm(
                "Log out of Nerdhalla?",
                "This signs this app out of Nerdhalla. You can log back in with Discord at any time.",
                this::logoutFromApp));

        return bar;
    }

    private View buildBottomNav() {
        bottomNav = new LinearLayout(this);
        bottomNav.setOrientation(LinearLayout.HORIZONTAL);
        bottomNav.setGravity(Gravity.CENTER);
        bottomNav.setPadding(dp(8), dp(7), dp(8), dp(7));
        bottomNav.setBackgroundColor(BG2);

        myNav = bottomItem("●\nMy Sharla");
        adminNav = bottomItem("◆\nAdmin");
        adminNav.setVisibility(View.GONE);

        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        p.setMargins(dp(4), 0, dp(4), 0);
        bottomNav.addView(myNav, p);

        LinearLayout.LayoutParams adminParams = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        adminParams.setMargins(dp(4), 0, dp(4), 0);
        bottomNav.addView(adminNav, adminParams);

        myNav.setOnClickListener(v -> {
            pendingMenuTransitionDirection = 0;
            currentTop = "my";
            titleText.setText("My Sharla");
            updateBottomNav();
            loadMySharla();
        });

        adminNav.setOnClickListener(v -> {
            if (!adminButtonAllowed) return;
            pendingMenuTransitionDirection = 0;
            currentTop = "admin";
            titleText.setText("Sharla Admin");
            updateBottomNav();
            loadAdmin();
        });

        return bottomNav;
    }

    private void checkAdminButtonAccess() {
        io.execute(() -> {
            boolean allowed = false;
            JSONObject filteredAccess = null;
            JSONObject firstGuildData = null;
            String firstGuildId = "";

            try {
                JSONObject access = api.api("mod_guilds");
                JSONArray guilds = access.optJSONArray("guilds");
                JSONArray allowedGuilds = new JSONArray();

                if (guilds != null) {
                    for (int i = 0; i < guilds.length(); i++) {
                        JSONObject listed = guilds.optJSONObject(i);
                        if (listed == null) continue;

                        String guildId = listed.optString("id", "");
                        if (guildId.isEmpty()) continue;

                        try {
                            JSONObject guild = api.api("mod_guild", "GET", null,
                                    "&guild_id=" + URLEncoder.encode(guildId, "UTF-8"));

                            if (!hasUsableAdminAccess(guild)) continue;

                            allowedGuilds.put(listed);
                            if (firstGuildData == null) {
                                firstGuildData = guild;
                                firstGuildId = guildId;
                            }
                        } catch (Exception ignored) {
                            // A guild that no longer returns usable moderator data
                            // must not keep the Admin button visible.
                        }
                    }
                }

                filteredAccess = new JSONObject(access.toString());
                filteredAccess.put("guilds", allowedGuilds);
                allowed = allowedGuilds.length() > 0;
            } catch (Exception ignored) {
                allowed = false;
            }

            final boolean finalAllowed = allowed;
            final JSONObject finalAccess = filteredAccess;
            final JSONObject finalGuildData = firstGuildData;
            final String finalGuildId = firstGuildId;

            main.post(() -> {
                adminButtonAllowed = finalAllowed;

                if (finalAllowed && finalAccess != null && finalGuildData != null) {
                    adminAccess = finalAccess;
                    guildData = finalGuildData;
                    currentGuildId = finalGuildId;
                } else {
                    adminAccess = null;
                    guildData = null;
                    currentGuildId = "";

                    if ("admin".equals(currentTop)) {
                        currentTop = "my";
                        if (titleText != null) titleText.setText("My Sharla");
                        loadMySharla();
                    }
                }

                updateBottomNav();
            });
        });
    }

    private boolean hasUsableAdminAccess(JSONObject guild) {
        if (guild == null) return false;
        if (guild.optBoolean("full_admin", false)) return true;

        JSONArray permissions = guild.optJSONArray("permissions");
        if (permissions == null || permissions.length() == 0) return false;

        for (int i = 0; i < permissions.length(); i++) {
            String permission = permissions.optString(i, "").trim();
            if (!permission.isEmpty()) return true;
        }
        return false;
    }

    private void updateBottomNav() {
        if (myNav == null) return;

        if (adminNav != null) {
            adminNav.setVisibility(adminButtonAllowed ? View.VISIBLE : View.GONE);
        }

        boolean my = "my".equals(currentTop) || !adminButtonAllowed;
        styleSelected(myNav, my);

        if (adminNav != null && adminButtonAllowed) {
            styleSelected(adminNav, !my);
        }
    }

    private void loadMySharla() {
        if (meData != null) {
            renderMySharla();
            return;
        }
        showBusy(true);
        io.execute(() -> {
            try {
                JSONObject d = api.api("me");
                main.post(() -> {
                    meData = d;
                    meDataFetchedElapsed = SystemClock.elapsedRealtime();
                    showBusy(false);
                    renderMySharla();
                });
            } catch (Exception e) {
                main.post(() -> {
                    showBusy(false);
                    showError("My Sharla", e.getMessage(), this::loadMySharla);
                });
            }
        });
    }

    private void renderMySharla() {
        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setBackgroundColor(BG);

        outer.addView(buildMyTabs());

        FrameLayout body = new FrameLayout(this);
        outer.addView(body, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        View page;
        switch (currentMySection) {
            case "homeworlds":
                page = renderHomeworldsPage();
                break;
            case "inventory":
                page = renderInventoryPage();
                break;
            case "achievements":
                page = renderAchievementsPage();
                break;
            default:
                page = renderProfilePage();
        }
        body.addView(page, match());
        setContent(outer);
    }

    private View buildMyTabs() {
        HorizontalScrollView scroller = new HorizontalScrollView(this);
        scroller.setHorizontalScrollBarEnabled(false);
        scroller.setBackgroundColor(BG);

        LinearLayout row = new LinearLayout(this);
        row.setPadding(dp(10), dp(10), dp(10), dp(8));
        String[][] tabs = {
                {"profile", "🦄 Profile"},
                {"homeworlds", "🌌 Homeworlds"},
                {"inventory", "🎒 Inventory"},
                {"achievements", "🏆 Achievements"}
        };
        for (String[] tab : tabs) {
            TextView b = chip(tab[1], tab[0].equals(currentMySection));
            b.setTag(tab[0]);
            b.setOnClickListener(v -> {
                pendingMenuTransitionDirection = 0;
                currentMySection = tab[0];
                renderMySharla();
            });
            row.addView(b, chipParams());
        }
        scroller.addView(row);
        activeMenuScroller = scroller;
        focusSelectedMenuTab(scroller, row, currentMySection);
        return scroller;
    }

    private View renderProfilePage() {
        LinearLayout c = scrollColumn();
        c.addView(pageHeading("My Profile", "Your Sharla profile and collection."));

        LinearLayout profile = card();
        LinearLayout person = new LinearLayout(this);
        person.setGravity(Gravity.CENTER_VERTICAL);

        ImageView avatar = new ImageView(this);
        avatar.setImageResource(R.drawable.nerdhalla_icon);
        avatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
        avatar.setBackground(roundRect(PANEL2, 18, LINE, 1));
        person.addView(avatar, new LinearLayout.LayoutParams(dp(72), dp(72)));

        LinearLayout names = new LinearLayout(this);
        names.setOrientation(LinearLayout.VERTICAL);
        names.setPadding(dp(14), 0, 0, 0);
        names.addView(text(session.optString("display_name", session.optString("username", "Discord User")), 20, TEXT, true));
        names.addView(text("@" + session.optString("username", ""), 13, MUTED, false));
        person.addView(names, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        profile.addView(person);

        String avatarUrl = session.optString("avatar_url", "");
        if (!avatarUrl.isEmpty()) loadImage(avatarUrl, avatar);

        JSONObject p = meData.optJSONObject("profile");
        if (p == null) p = new JSONObject();
        JSONObject stats = meData.optJSONObject("stats");
        if (stats == null) stats = new JSONObject();

        profile.addView(spacer(12));
        profile.addView(statLine("Title", p.optString("title", "Noob Alert!")));
        profile.addView(statLine("Activity Tier", p.optString("tier", "Newcomer 🆕")));
        profile.addView(statLine("Messages", fmt(p.optLong("count", 0))));
        profile.addView(statLine("Horse Tokens", fmt(p.optLong("horse_tokens", 0)) + " 🪙"));

        profile.addView(label("Race"));
        Spinner race = darkSpinner(list("Person", "Place", "Thing", "Cat"));
        setSpinnerValue(race, p.optString("race", "Person"));
        profile.addView(race, fullWrap());

        profile.addView(label("Class"));
        Spinner clazz = darkSpinner(list("Dumpster Fire", "Drama Llama", "Steve", "Has no Class"));
        setSpinnerValue(clazz, p.optString("class", "Dumpster Fire"));
        profile.addView(clazz, fullWrap());

        Button save = primaryButton("Save Profile");
        profile.addView(save, buttonParams());
        save.setOnClickListener(v -> {
            JSONObject body = new JSONObject();
            try {
                body.put("race", String.valueOf(race.getSelectedItem()));
                body.put("class", String.valueOf(clazz.getSelectedItem()));
            } catch (Exception ignored) {}
            post("profile", body, result -> {
                toast("Profile updated.");
                meData = null;
                loadMySharla();
            });
        });

        c.addView(profile, cardParams());

        JSONObject loot = meData.optJSONObject("loot");
        LinearLayout lootCard = card();
        lootCard.addView(sectionTitle("🎁 Sharla Loot"));
        lootCard.addView(text(
                "Claim one random loot item and +1 Horse Token every 12 hours.",
                13, MUTED, false));

        TextView lootCountdown = text("", 20, GOLD, true);
        lootCountdown.setPadding(0, dp(12), 0, dp(4));
        lootCard.addView(lootCountdown);

        Button claimLoot = primaryButton("Claim Loot");
        lootCard.addView(claimLoot, buttonParams());

        if (loot == null) {
            lootCountdown.setText("Loot status unavailable");
            lootCountdown.setTextColor(RED);
            claimLoot.setEnabled(false);
        } else {
            long baseRemaining = Math.max(0L, loot.optLong("remaining_seconds", 0L));
            long ageSeconds = meDataFetchedElapsed > 0L
                    ? Math.max(0L, (SystemClock.elapsedRealtime() - meDataFetchedElapsed) / 1000L)
                    : 0L;
            long remaining = Math.max(0L, baseRemaining - ageSeconds);
            startLootCountdown(lootCountdown, claimLoot, remaining);
            claimLoot.setOnClickListener(v -> claimLootFromApp(claimLoot));
        }

        c.addView(lootCard, cardParams());

        LinearLayout collection = card();
        collection.addView(sectionTitle("Collection"));
        collection.addView(bigStat(String.valueOf(stats.optInt("unique_items", 0)), "unique items"));
        collection.addView(statLine("Total items", String.valueOf(stats.optInt("total_items", 0))));
        collection.addView(statLine("Duplicates", String.valueOf(stats.optInt("duplicate_items", 0))));
        collection.addView(statLine("Duplicate pawn value", stats.optInt("duplicate_sell_value", 0) + " 🪙"));
        c.addView(collection, cardParams());

        return wrapScroll(c);
    }

    private void startLootCountdown(
            TextView countdown, Button claimButton, long remainingSeconds) {
        final long readyAt = SystemClock.elapsedRealtime() +
                Math.max(0L, remainingSeconds) * 1000L;

        Runnable ticker = new Runnable() {
            @Override
            public void run() {
                if (!countdown.isAttachedToWindow()) return;

                long millis = Math.max(0L, readyAt - SystemClock.elapsedRealtime());
                long seconds = (millis + 999L) / 1000L;

                if (seconds <= 0L) {
                    countdown.setText("Ready now");
                    countdown.setTextColor(GREEN);
                    claimButton.setText("Claim Loot");
                    claimButton.setEnabled(true);
                    return;
                }

                long hours = seconds / 3600L;
                long minutes = (seconds % 3600L) / 60L;
                long secs = seconds % 60L;
                countdown.setText(String.format(
                        Locale.US,
                        "Next loot in %02d:%02d:%02d",
                        hours, minutes, secs));
                countdown.setTextColor(GOLD);
                claimButton.setText("Loot on cooldown");
                claimButton.setEnabled(false);
                main.postDelayed(this, 1000L);
            }
        };

        main.post(ticker);
    }

    private void claimLootFromApp(Button claimButton) {
        claimButton.setEnabled(false);
        showBusy(true);

        io.execute(() -> {
            try {
                JSONObject result = api.api("loot", "POST", new JSONObject());
                main.post(() -> {
                    showBusy(false);
                    String rarity = result.optString("rarity", "Loot");
                    String item = result.optString("item", "item");
                    toast("🎁 " + item + " [" + rarity + "] • +1 Horse Token");
                    meData = null;
                    meDataFetchedElapsed = 0L;
                    loadMySharla();
                });
            } catch (Exception e) {
                main.post(() -> {
                    showBusy(false);
                    toast(e.getMessage());
                    meData = null;
                    meDataFetchedElapsed = 0L;
                    loadMySharla();
                });
            }
        });
    }

    private View renderHomeworldsPage() {
        LinearLayout c = scrollColumn();
        c.addView(pageHeading("Homeworlds Stats", "Your Head Pool Homeworlds record and recent results."));

        JSONObject h = meData.optJSONObject("homeworlds");
        if (h == null || !h.optBoolean("available", false)) {
            c.addView(infoCard("Homeworlds", "Head Pool stats are not available right now."));
            return wrapScroll(c);
        }

        LinearLayout card = card();
        card.addView(bigStat(trim1(h.optDouble("win_rate", 0)) + "%", "overall win rate"));
        card.addView(statLine("Archived", String.valueOf(h.optInt("archived", 0))));
        card.addView(statLine("Overall", record(h)));
        card.addView(statLine("PvP", record(h.optJSONObject("pvp"))));
        card.addView(statLine("Head Pool Easy", record(h.optJSONObject("easy"))));
        card.addView(statLine("Head Pool Normal", record(h.optJSONObject("normal"))));
        card.addView(statLine("Head Pool Hard", record(h.optJSONObject("hard"))));

        JSONObject legacy = h.optJSONObject("legacy_ai");
        if (legacy != null && legacy.optInt("games", 0) > 0) {
            card.addView(statLine("Legacy AI", record(legacy)));
        }

        JSONArray recent = h.optJSONArray("recent");
        if (recent != null && recent.length() > 0) {
            card.addView(spacer(10));
            card.addView(sectionTitle("Recent"));
            for (int i = 0; i < Math.min(3, recent.length()); i++) {
                JSONObject x = recent.optJSONObject(i);
                if (x != null) {
                    card.addView(text("• " + x.optString("result", "Result") + " vs " + x.optString("opponent", "opponent"), 14, MUTED, false));
                }
            }
        }
        c.addView(card, cardParams());
        return wrapScroll(c);
    }

    private View renderInventoryPage() {
        LinearLayout c = scrollColumn();
        c.addView(pageHeading("Inventory", "Browse, filter, sort, and pawn your Sharla collection."));

        JSONObject stats = meData.optJSONObject("stats");
        if (stats == null) stats = new JSONObject();

        LinearLayout summary = card();
        summary.addView(statLine("Unique items", String.valueOf(stats.optInt("unique_items", 0))));
        summary.addView(statLine("Total items", String.valueOf(stats.optInt("total_items", 0))));
        summary.addView(statLine("Duplicates", String.valueOf(stats.optInt("duplicate_items", 0))));
        summary.addView(statLine("Duplicate pawn value", stats.optInt("duplicate_sell_value", 0) + " 🪙"));
        Button all = dangerButton("Sell All Duplicates");
        summary.addView(all, buttonParams());
        int dupeQty = stats.optInt("duplicate_items", 0);
        int dupeValue = stats.optInt("duplicate_sell_value", 0);
        all.setEnabled(dupeQty > 0);
        all.setOnClickListener(v -> confirm(
                "Sell duplicates?",
                "Sell " + dupeQty + " duplicate item(s) for " + dupeValue + " Horse Tokens? One copy of every unique item will be kept.",
                () -> post("sell_duplicates", new JSONObject(), result -> {
                    toast("Duplicates sold.");
                    meData = null;
                    loadMySharla();
                })));
        c.addView(summary, cardParams());

        final List<JSONObject> items = jsonList(meData.optJSONArray("inventory"));

        LinearLayout controls = card();
        controls.addView(sectionTitle("Inventory view"));
        Spinner rarity = darkSpinner(list(
                "All rarities", "Mythic", "Legendary", "Rare", "Uncommon", "Common"));
        addField(controls, "Rarity", rarity);

        Spinner sort = darkSpinner(list(
                "Name A–Z", "Quantity high–low", "Rarity high–low"));
        addField(controls, "Sort by", sort);
        c.addView(controls, cardParams());

        ScrollView inventoryScroll = new ScrollView(this);
        inventoryScroll.setFillViewport(false);
        inventoryScroll.setVerticalScrollBarEnabled(true);
        inventoryScroll.setNestedScrollingEnabled(true);
        inventoryScroll.setBackground(roundRect(PANEL, 14, LINE, 1));

        LinearLayout itemList = new LinearLayout(this);
        itemList.setOrientation(LinearLayout.VERTICAL);
        itemList.setPadding(dp(4), dp(4), dp(4), dp(4));
        inventoryScroll.addView(itemList, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout.LayoutParams inventoryWindow = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(600));
        inventoryWindow.leftMargin = dp(14);
        inventoryWindow.rightMargin = dp(14);
        inventoryWindow.bottomMargin = dp(18);
        c.addView(inventoryScroll, inventoryWindow);

        Runnable refresh = () -> renderInventoryItems(
                itemList,
                items,
                String.valueOf(rarity.getSelectedItem()),
                String.valueOf(sort.getSelectedItem()));

        rarity.setOnItemSelectedListener(new SimpleItemSelectedListener(position -> refresh.run()));
        sort.setOnItemSelectedListener(new SimpleItemSelectedListener(position -> refresh.run()));
        refresh.run();

        return wrapScroll(c);
    }

    private void renderInventoryItems(LinearLayout parent, List<JSONObject> source, String rarityFilter, String sortMode) {
        parent.removeAllViews();

        List<JSONObject> items = new ArrayList<>();
        for (JSONObject item : source) {
            if ("All rarities".equals(rarityFilter) ||
                    rarityFilter.equals(item.optString("rarity", "Unknown"))) {
                items.add(item);
            }
        }

        if ("Quantity high–low".equals(sortMode)) {
            items.sort((a, b) -> Integer.compare(
                    b.optInt("quantity", 0), a.optInt("quantity", 0)));
        } else if ("Rarity high–low".equals(sortMode)) {
            items.sort((a, b) -> {
                int byRarity = Integer.compare(
                        rarityRank(b.optString("rarity", "")),
                        rarityRank(a.optString("rarity", "")));
                if (byRarity != 0) return byRarity;
                return a.optString("name", "").compareToIgnoreCase(b.optString("name", ""));
            });
        } else {
            items.sort(Comparator.comparing(
                    a -> a.optString("name", "").toLowerCase(Locale.US)));
        }

        if (items.isEmpty()) {
            parent.addView(infoCard("Inventory", source.isEmpty()
                    ? "Your inventory is empty."
                    : "No inventory items match that rarity."));
            return;
        }

        for (JSONObject item : items) {
            LinearLayout itemCard = card();
            String rarity = item.optString("rarity", "Unknown");
            TextView name = text(item.optString("name", "Item"), 17, rarityColor(rarity), true);
            itemCard.addView(name);
            itemCard.addView(text(rarity + "  •  ×" + item.optInt("quantity", 0) +
                    "  •  " + tokenValue(rarity) + " 🪙 each", 13, MUTED, false));

            String desc = item.optString("description", "");
            if (!desc.isEmpty()) {
                TextView d = text(desc, 13, MUTED, false);
                d.setPadding(0, dp(8), 0, 0);
                itemCard.addView(d);
            }

            Button sell = secondaryButton("Sell 1  +" + tokenValue(rarity) + " 🪙");
            itemCard.addView(sell, buttonParams());
            sell.setOnClickListener(v -> {
                JSONObject body = new JSONObject();
                try {
                    body.put("item", item.optString("name", ""));
                    body.put("rarity", rarity);
                } catch (Exception ignored) {}
                String warning = item.optInt("quantity", 0) <= 1 ? "\n\nThis is your last copy." : "";
                confirm("Sell item?", "Sell one " + item.optString("name", "item") + " for " +
                        tokenValue(rarity) + " Horse Tokens?" + warning, () ->
                        post("sell_duplicates", body, result -> {
                            toast("Item sold.");
                            meData = null;
                            loadMySharla();
                        }));
            });
            parent.addView(itemCard, cardParams());
        }
    }

    private int rarityRank(String rarity) {
        switch (rarity) {
            case "Mythic": return 5;
            case "Legendary": return 4;
            case "Rare": return 3;
            case "Uncommon": return 2;
            case "Common": return 1;
            default: return 0;
        }
    }

    private View renderAchievementsPage() {
        LinearLayout c = scrollColumn();
        c.addView(pageHeading("Achievements", "Your unlocked Sharla achievements."));

        JSONArray arr = meData.optJSONArray("achievements");
        if (arr == null || arr.length() == 0) {
            c.addView(infoCard("Achievements", "No recorded achievements yet."));
            return wrapScroll(c);
        }

        for (int i = 0; i < arr.length(); i++) {
            JSONObject a = arr.optJSONObject(i);
            if (a == null) continue;
            LinearLayout card = card();
            card.addView(text("🏆 " + a.optString("name", a.optString("key", "Achievement")), 17, GOLD, true));
            String unlocked = a.optString("unlocked_at", "");
            if (!unlocked.isEmpty()) card.addView(text("Unlocked " + unlocked, 13, MUTED, false));
            String desc = a.optString("description", "");
            if (!desc.isEmpty()) {
                TextView d = text(desc, 14, TEXT, false);
                d.setPadding(0, dp(8), 0, 0);
                card.addView(d);
            }
            c.addView(card, cardParams());
        }
        return wrapScroll(c);
    }

    private void loadAdmin() {
        if (!adminButtonAllowed) {
            currentTop = "my";
            if (titleText != null) titleText.setText("My Sharla");
            updateBottomNav();
            loadMySharla();
            return;
        }

        if (adminAccess != null && guildData != null && hasUsableAdminAccess(guildData)) {
            renderAdmin();
            return;
        }
        showBusy(true);
        io.execute(() -> {
            try {
                JSONObject access = api.api("mod_guilds");
                JSONArray guilds = access.optJSONArray("guilds");
                if (guilds == null || guilds.length() == 0) {
                    throw new Exception("Your Discord roles do not currently have access to Sharla Admin.");
                }
                JSONObject first = guilds.optJSONObject(0);
                String id = first == null ? "" : first.optString("id", "");
                JSONObject guild = api.api("mod_guild", "GET", null,
                        "&guild_id=" + URLEncoder.encode(id, "UTF-8"));
                main.post(() -> {
                    adminAccess = access;
                    guildData = guild;
                    currentGuildId = id;
                    showBusy(false);
                    renderAdmin();
                });
            } catch (Exception e) {
                main.post(() -> {
                    showBusy(false);
                    showError("Sharla Admin", e.getMessage(), this::loadAdmin);
                });
            }
        });
    }

    private void renderAdmin() {
        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setBackgroundColor(BG);

        LinearLayout serverBar = new LinearLayout(this);
        serverBar.setOrientation(LinearLayout.HORIZONTAL);
        serverBar.setGravity(Gravity.CENTER_VERTICAL);
        serverBar.setPadding(dp(10), dp(10), dp(10), dp(6));

        JSONArray guilds = adminAccess.optJSONArray("guilds");
        List<String> guildNames = new ArrayList<>();
        List<String> guildIds = new ArrayList<>();
        if (guilds != null) {
            for (int i = 0; i < guilds.length(); i++) {
                JSONObject g = guilds.optJSONObject(i);
                if (g != null) {
                    guildNames.add(g.optString("name", "Discord Server"));
                    guildIds.add(g.optString("id", ""));
                }
            }
        }

        Spinner guildSpinner = darkSpinner(guildNames);
        int currentPos = guildIds.indexOf(currentGuildId);
        if (currentPos >= 0) guildSpinner.setSelection(currentPos);
        serverBar.addView(guildSpinner, new LinearLayout.LayoutParams(0, dp(48), 1f));

        Button reload = secondaryButton("↻");
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(dp(52), dp(48));
        rp.leftMargin = dp(8);
        serverBar.addView(reload, rp);
        reload.setOnClickListener(v -> loadGuild(currentGuildId));

        guildSpinner.setOnItemSelectedListener(new SimpleItemSelectedListener(position -> {
            if (position >= 0 && position < guildIds.size()) {
                String selected = guildIds.get(position);
                if (!selected.equals(currentGuildId)) loadGuild(selected);
            }
        }));

        outer.addView(serverBar);
        outer.addView(buildAdminTabs());

        FrameLayout body = new FrameLayout(this);
        outer.addView(body, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        View page;
        switch (currentAdminSection) {
            case "talk":
                page = renderTalkPage();
                break;
            case "members":
                page = renderMembersPage();
                break;
            case "settings":
                page = renderSettingsPage();
                break;
            case "announce":
                page = renderAnnouncementPage();
                break;
            case "homeworlds":
                page = renderHomeworldsAdminPage();
                break;
            case "moderators":
                page = renderModeratorAccessPage();
                break;
            case "backups":
                page = renderBackupsPage();
                break;
            default:
                page = renderHealthPage();
        }
        body.addView(page, match());
        setContent(outer);
    }

    private void loadGuild(String id) {
        if (id == null || id.isEmpty()) return;
        showBusy(true);
        io.execute(() -> {
            try {
                JSONObject g = api.api("mod_guild", "GET", null,
                        "&guild_id=" + URLEncoder.encode(id, "UTF-8"));
                main.post(() -> {
                    if (!hasUsableAdminAccess(g)) {
                        adminButtonAllowed = false;
                        adminAccess = null;
                        guildData = null;
                        currentGuildId = "";
                        currentTop = "my";
                        if (titleText != null) titleText.setText("My Sharla");
                        showBusy(false);
                        updateBottomNav();
                        loadMySharla();
                        toast("Your Admin access has been removed.");
                        return;
                    }

                    currentGuildId = id;
                    guildData = g;
                    showBusy(false);
                    renderAdmin();
                });
            } catch (Exception e) {
                main.post(() -> {
                    showBusy(false);
                    toast(e.getMessage());
                });
            }
        });
    }

    private View buildAdminTabs() {
        HorizontalScrollView scroller = new HorizontalScrollView(this);
        scroller.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(this);
        row.setPadding(dp(10), dp(4), dp(10), dp(8));

        addAdminTab(row, "health", "❤️ Health", hasPerm("live_health"));
        addAdminTab(row, "talk", "💬 Talk", guildData.optBoolean("full_admin", false));
        addAdminTab(row, "announce", "📣 Announce", hasPerm("announcements") || hasPerm("scheduled_announcements"));
        addAdminTab(row, "members", "👤 Members", hasPerm("member_lookup"));
        addAdminTab(row, "homeworlds", "🎮 Homeworlds", hasPerm("homeworlds_admin"));
        addAdminTab(row, "settings", "⚙ Settings", hasPerm("server_settings"));
        addAdminTab(row, "moderators", "🛡 Moderator Access", isAdminOwner());
        addAdminTab(row, "backups", "💾 Backup & Restore", isAdminOwner());

        scroller.addView(row);
        activeMenuScroller = scroller;
        focusSelectedMenuTab(scroller, row, currentAdminSection);
        return scroller;
    }

    private void addAdminTab(LinearLayout row, String key, String label, boolean allowed) {
        if (!allowed) return;
        TextView b = chip(label, key.equals(currentAdminSection));
        b.setTag(key);
        b.setOnClickListener(v -> {
            pendingMenuTransitionDirection = 0;
            currentAdminSection = key;
            renderAdmin();
        });
        row.addView(b, chipParams());
    }

    private void focusSelectedMenuTab(
            HorizontalScrollView scroller, LinearLayout row, String selectedKey) {
        scroller.post(() -> {
            for (int i = 0; i < row.getChildCount(); i++) {
                View child = row.getChildAt(i);
                Object tag = child.getTag();
                if (tag != null && selectedKey.equals(String.valueOf(tag))) {
                    int target = Math.max(0, child.getLeft() - dp(18));
                    scroller.smoothScrollTo(target, 0);
                    break;
                }
            }
        });
    }

    private boolean hasPerm(String name) {
        if (guildData == null) return false;
        if (guildData.optBoolean("full_admin", false)) return true;
        JSONArray p = guildData.optJSONArray("permissions");
        if (p == null) return false;
        for (int i = 0; i < p.length(); i++) {
            if (name.equals(p.optString(i))) return true;
        }
        return false;
    }


    private boolean isAdminOwner() {
        return adminAccess != null &&
                adminAccess.optBoolean("talk_as_sharla_owner", false) &&
                guildData != null &&
                guildData.optBoolean("full_admin", false);
    }

    private View renderModeratorAccessPage() {
        LinearLayout c = scrollColumn();
        c.addView(pageHeading("Moderator Access",
                "Choose which Discord roles may use specific Nerdhalla Admin tools. Talk as Sharla and backups remain owner-only."));

        if (!isAdminOwner()) {
            c.addView(infoCard("Owner only", "Only the Nerdhalla owner can change moderator access."));
            return wrapScroll(c);
        }

        LinearLayout holder = card();
        holder.addView(text("Loading moderator access…", 13, MUTED, false));
        c.addView(holder, cardParams());

        io.execute(() -> {
            try {
                JSONObject config = api.api("mod_access_config", "GET", null,
                        "&guild_id=" + URLEncoder.encode(currentGuildId, "UTF-8"));
                main.post(() -> renderModeratorAccessConfig(holder, config));
            } catch (Exception e) {
                main.post(() -> {
                    holder.removeAllViews();
                    holder.addView(text(e.getMessage(), 13, RED, false));
                });
            }
        });

        return wrapScroll(c);
    }

    private void renderModeratorAccessConfig(LinearLayout holder, JSONObject config) {
        holder.removeAllViews();

        List<JSONObject> roles = jsonList(guildData.optJSONArray("roles"));
        List<String> roleLabels = new ArrayList<>();
        roleLabels.add("Choose Discord role");
        for (JSONObject role : roles) {
            roleLabels.add("@" + role.optString("name", role.optString("id", "Role")));
        }
        Spinner roleSpinner = darkSpinner(roleLabels);
        addField(holder, "Moderator role", roleSpinner);

        JSONObject featureLabels = new JSONObject();
        try {
            featureLabels.put("server_settings", "Server settings");
            featureLabels.put("announcements", "Post announcements");
            featureLabels.put("scheduled_announcements", "Scheduled announcements");
            featureLabels.put("member_lookup", "Member lookup");
            featureLabels.put("member_moderation", "Member moderation");
            featureLabels.put("member_roles", "Member role management");
            featureLabels.put("member_kick_ban", "Kick / ban members");
            featureLabels.put("live_health", "Live bot health");
            featureLabels.put("homeworlds_admin", "Homeworlds Admin");

            JSONObject remote = config.optJSONObject("features");
            if (remote != null) {
                JSONArray remoteNames = remote.names();
                if (remoteNames != null) {
                    for (int i = 0; i < remoteNames.length(); i++) {
                        String key = remoteNames.optString(i, "");
                        if (!key.isEmpty()) featureLabels.put(key, remote.optString(key, key));
                    }
                }
            }
        } catch (Exception ignored) {}

        holder.addView(sectionTitle("Allowed features"));
        List<String> featureKeys = new ArrayList<>();
        List<Switch> featureSwitches = new ArrayList<>();
        JSONArray names = featureLabels.names();
        if (names != null) {
            for (int i = 0; i < names.length(); i++) {
                String key = names.optString(i, "");
                if (key.isEmpty()) continue;
                featureKeys.add(key);
                Switch sw = switchRow(featureLabels.optString(key, key), false);
                featureSwitches.add(sw);
                holder.addView(sw);
            }
        }

        JSONArray entries = config.optJSONArray("entries");
        if (entries == null) entries = new JSONArray();
        final JSONArray finalEntries = entries;

        Runnable syncRole = () -> {
            int pos = roleSpinner.getSelectedItemPosition() - 1;
            String roleId = pos >= 0 && pos < roles.size()
                    ? roles.get(pos).optString("id", "") : "";
            JSONObject entry = moderatorEntryForRole(finalEntries, roleId);
            JSONArray selected = entry == null ? null : entry.optJSONArray("features");
            for (int i = 0; i < featureSwitches.size(); i++) {
                featureSwitches.get(i).setChecked(jsonArrayContains(selected, featureKeys.get(i)));
            }
        };
        roleSpinner.setOnItemSelectedListener(new SimpleItemSelectedListener(position -> syncRole.run()));

        Button save = primaryButton("Save Role Access");
        holder.addView(save, buttonParams());
        save.setOnClickListener(v -> {
            int pos = roleSpinner.getSelectedItemPosition() - 1;
            if (pos < 0 || pos >= roles.size()) {
                toast("Choose a Discord role first.");
                return;
            }
            JSONArray selected = new JSONArray();
            for (int i = 0; i < featureSwitches.size(); i++) {
                if (featureSwitches.get(i).isChecked()) selected.put(featureKeys.get(i));
            }
            if (selected.length() == 0) {
                toast("Choose at least one feature, or use Remove Role Access.");
                return;
            }
            JSONObject body = new JSONObject();
            try {
                body.put("guild_id", currentGuildId);
                body.put("role_id", roles.get(pos).optString("id", ""));
                body.put("features", selected);
            } catch (Exception ignored) {}
            post("mod_access_save", body, result -> {
                toast("Moderator role access saved.");
                renderAdmin();
            });
        });

        Button remove = secondaryButton("Remove Role Access");
        holder.addView(remove, buttonParams());
        remove.setOnClickListener(v -> {
            int pos = roleSpinner.getSelectedItemPosition() - 1;
            if (pos < 0 || pos >= roles.size()) {
                toast("Choose a Discord role first.");
                return;
            }
            String roleName = roles.get(pos).optString("name", "role");
            confirm("Remove moderator access?",
                    "Remove all Nerdhalla Admin access for @" + roleName + "?",
                    () -> {
                        JSONObject body = new JSONObject();
                        try {
                            body.put("guild_id", currentGuildId);
                            body.put("role_id", roles.get(pos).optString("id", ""));
                            body.put("features", new JSONArray());
                        } catch (Exception ignored) {}
                        post("mod_access_save", body, result -> {
                            toast("Moderator role access removed.");
                            renderAdmin();
                        });
                    });
        });

        holder.addView(spacer(16));
        holder.addView(sectionTitle("Configured moderator roles"));
        if (finalEntries.length() == 0) {
            holder.addView(text("No moderator roles configured yet.", 13, MUTED, false));
        } else {
            for (int i = 0; i < finalEntries.length(); i++) {
                JSONObject entry = finalEntries.optJSONObject(i);
                if (entry == null) continue;
                String roleName = entry.optString("role_name", entry.optString("role_id", "Role"));
                JSONArray features = entry.optJSONArray("features");
                StringBuilder list = new StringBuilder();
                if (features != null) {
                    for (int j = 0; j < features.length(); j++) {
                        String key = features.optString(j, "");
                        if (list.length() > 0) list.append(" • ");
                        list.append(featureLabels.optString(key, key));
                    }
                }
                LinearLayout row = cardInner();
                row.addView(text("@" + roleName, 16, TEXT, true));
                row.addView(text(list.length() == 0 ? "No features" : list.toString(),
                        12, MUTED, false));
                if (entry.optBoolean("missing", false)) {
                    row.addView(text("Discord role no longer exists.", 12, RED, false));
                }
                holder.addView(row, cardParams());
            }
        }
        syncRole.run();
    }

    private JSONObject moderatorEntryForRole(JSONArray entries, String roleId) {
        if (entries == null || roleId == null || roleId.isEmpty()) return null;
        for (int i = 0; i < entries.length(); i++) {
            JSONObject entry = entries.optJSONObject(i);
            if (entry != null && roleId.equals(entry.optString("role_id", ""))) return entry;
        }
        return null;
    }

    private boolean jsonArrayContains(JSONArray array, String value) {
        if (array == null) return false;
        for (int i = 0; i < array.length(); i++) {
            if (value.equals(array.optString(i, ""))) return true;
        }
        return false;
    }

    private View renderBackupsPage() {
        LinearLayout c = scrollColumn();
        c.addView(pageHeading("Backup & Restore",
                "Owner-only backups of Sharla, Head Pool, and Homeworlds data. Restoring restarts both bots."));

        if (!isAdminOwner()) {
            c.addView(infoCard("Owner only", "Only the Nerdhalla owner can create or restore server backups."));
            return wrapScroll(c);
        }

        LinearLayout actions = card();
        Button create = primaryButton("Create Backup");
        Button refresh = secondaryButton("Refresh Backups");
        actions.addView(create, buttonParams());
        actions.addView(refresh, buttonParams());
        c.addView(actions, cardParams());

        LinearLayout holder = card();
        holder.addView(text("Loading backups…", 13, MUTED, false));
        c.addView(holder, cardParams());

        create.setOnClickListener(v -> post("suite_backup_create", new JSONObject(), result -> {
            toast("Created " + result.optString("name", "backup") +
                    (result.optString("database", "").isEmpty() ? "" : ". " + result.optString("database", "")));
            loadBackupsInto(holder);
        }));
        refresh.setOnClickListener(v -> loadBackupsInto(holder));

        loadBackupsInto(holder);
        return wrapScroll(c);
    }

    private void loadBackupsInto(LinearLayout holder) {
        holder.removeAllViews();
        holder.addView(text("Loading backups…", 13, MUTED, false));
        io.execute(() -> {
            try {
                JSONObject data = api.api("suite_backups");
                main.post(() -> renderBackupsList(holder, data.optJSONArray("items")));
            } catch (Exception e) {
                main.post(() -> {
                    holder.removeAllViews();
                    holder.addView(text(e.getMessage(), 13, RED, false));
                });
            }
        });
    }

    private void renderBackupsList(LinearLayout holder, JSONArray items) {
        holder.removeAllViews();
        holder.addView(sectionTitle("Available backups"));
        if (items == null || items.length() == 0) {
            holder.addView(text("No backups yet.", 13, MUTED, false));
            return;
        }

        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) continue;
            String name = item.optString("name", "backup");
            LinearLayout row = cardInner();
            row.addView(text(name, 15, TEXT, true));
            row.addView(text(item.optString("created_at", "") + "  •  " +
                    humanBytes(item.optLong("size", 0)), 12, MUTED, false));

            Button download = secondaryButton("Download");
            row.addView(download, buttonParams());
            download.setOnClickListener(v -> startBackupDownload(name));

            Button restore = dangerButton("Restore");
            row.addView(restore, buttonParams());
            restore.setOnClickListener(v -> promptRestoreBackup(name, holder));

            holder.addView(row, cardParams());
        }
    }

    private void promptRestoreBackup(String name, LinearLayout holder) {
        EditText input = edit("Type RESTORE", false);
        new AlertDialog.Builder(this)
                .setTitle(rainbowNerdhalla("Restore " + name + "?"))
                .setMessage(rainbowNerdhalla("This will restart Sharla and Head Pool. Type RESTORE exactly to continue."))
                .setView(input)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Restore", (dialog, which) -> {
                    String confirmText = input.getText().toString().trim();
                    if (!"RESTORE".equals(confirmText)) {
                        toast("Restore cancelled: confirmation text did not match.");
                        return;
                    }
                    JSONObject body = new JSONObject();
                    try {
                        body.put("name", name);
                        body.put("confirm", confirmText);
                    } catch (Exception ignored) {}
                    post("suite_backup_restore", body, result -> {
                        toast(result.optString("message", "Backup restored."));
                        loadBackupsInto(holder);
                    });
                })
                .show();
    }

    private void startBackupDownload(String name) {
        try {
            String encoded = URLEncoder.encode(name, "UTF-8");
            Uri uri = Uri.parse(BASE + "portal-api.php?action=suite_backup_download&name=" + encoded);
            DownloadManager.Request request = new DownloadManager.Request(uri);
            request.setTitle(name);
            request.setDescription("Nerdhalla server backup");
            request.setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            String cookie = CookieManager.getInstance().getCookie(BASE);
            if (cookie != null && !cookie.isEmpty()) request.addRequestHeader("Cookie", cookie);
            String fileName = name.replaceAll("[^A-Za-z0-9._-]", "_");
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName);
            DownloadManager manager = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            if (manager == null) throw new Exception("Android Download Manager is unavailable.");
            manager.enqueue(request);
            toast("Backup download started.");
        } catch (Exception e) {
            toast("Could not start backup download: " + e.getMessage());
        }
    }

    private String humanBytes(long n) {
        double value = Math.max(0, n);
        if (value < 1024) return (long) value + " B";
        value /= 1024.0;
        if (value < 1024) return String.format(Locale.US, "%.1f KB", value);
        value /= 1024.0;
        if (value < 1024) return String.format(Locale.US, "%.1f MB", value);
        value /= 1024.0;
        return String.format(Locale.US, "%.1f GB", value);
    }

    private View renderHomeworldsAdminPage() {
        LinearLayout c = scrollColumn();
        c.addView(pageHeading("Homeworlds Admin",
                "Live Head Pool bridge for MySQL/state health, active games, matchmaking, force-stop controls, and owner stats tools."));

        LinearLayout controls = card();
        EditText filter = edit("Filter match / player / channel", false);
        addField(controls, "Game filter", filter);
        Button refresh = primaryButton("Refresh Homeworlds");
        controls.addView(refresh, buttonParams());
        c.addView(controls, cardParams());

        LinearLayout holder = card();
        holder.addView(text("Loading Homeworlds…", 13, MUTED, false));
        c.addView(holder, cardParams());

        refresh.setOnClickListener(v -> loadHomeworldsInto(holder, filter));

        if (isAdminOwner()) {
            LinearLayout stats = card();
            stats.addView(sectionTitle("Owner stats correction"));

            EditText player = edit("@username, mention, name, or Discord ID", false);
            addField(stats, "Player", player);

            Spinner scope = darkSpinner(list(
                    "Choose scope", "Overall", "PvP", "Head Pool Easy",
                    "Head Pool Normal", "Head Pool Hard"));
            addField(stats, "Scope", scope);

            Spinner stat = darkSpinner(list(
                    "Choose stat", "Wins", "Losses", "Draws", "Games",
                    "Cancelled", "Unknown results"));
            addField(stats, "Stat", stat);

            EditText value = edit("Final displayed value", false);
            value.setInputType(InputType.TYPE_CLASS_NUMBER);
            addField(stats, "Final displayed value", value);

            Button set = primaryButton("Apply Correction");
            stats.addView(set, buttonParams());
            Button reset = secondaryButton("Reset Correction");
            stats.addView(reset, buttonParams());

            set.setOnClickListener(v -> {
                String scopeValue = homeworldScopeValue(scope);
                String statValue = homeworldStatValue(stat);
                String raw = value.getText().toString().trim();
                if (scopeValue.isEmpty() || statValue.isEmpty() || raw.isEmpty()) {
                    toast("Choose a player, scope, stat, and final displayed value.");
                    return;
                }
                long finalValue;
                try {
                    finalValue = Long.parseLong(raw);
                    if (finalValue < 0) throw new NumberFormatException();
                } catch (Exception e) {
                    toast("Final displayed value must be a whole number of 0 or greater.");
                    return;
                }
                resolveHomeworldsMemberId(player.getText().toString(), playerId -> {
                    JSONObject body = new JSONObject();
                    try {
                        body.put("guild_id", currentGuildId);
                        body.put("operation", "set");
                        body.put("player_id", playerId);
                        body.put("scope", scopeValue);
                        body.put("stat", statValue);
                        body.put("value", finalValue);
                    } catch (Exception ignored) {}
                    post("suite_homeworlds_stat", body, result -> {
                        toast(result.optString("message", "Correction applied."));
                        loadHomeworldsInto(holder, filter);
                    });
                });
            });

            reset.setOnClickListener(v -> {
                String scopeValue = homeworldScopeValue(scope);
                String statValue = homeworldStatValue(stat);
                if (scopeValue.isEmpty() || statValue.isEmpty()) {
                    toast("Choose a player, scope, and stat to reset.");
                    return;
                }
                resolveHomeworldsMemberId(player.getText().toString(), playerId ->
                        confirm("Reset correction?",
                                "Remove this manual Homeworlds stat correction and return to the recorded value?",
                                () -> {
                                    JSONObject body = new JSONObject();
                                    try {
                                        body.put("guild_id", currentGuildId);
                                        body.put("operation", "reset");
                                        body.put("player_id", playerId);
                                        body.put("scope", scopeValue);
                                        body.put("stat", statValue);
                                    } catch (Exception ignored) {}
                                    post("suite_homeworlds_stat", body, result -> {
                                        toast(result.optString("message", "Correction reset."));
                                        loadHomeworldsInto(holder, filter);
                                    });
                                }));
            });

            stats.addView(spacer(18));
            stats.addView(text("Danger zone — wipe Homeworlds user data", 17, RED, true));
            stats.addView(text(
                    "Permanently removes this user's Homeworlds archived match records, stat corrections, and matchmaking queue entry. Active games must be stopped first.",
                    12, MUTED, false));

            EditText wipePlayer = edit("@username, mention, name, or Discord ID", false);
            addField(stats, "User to wipe", wipePlayer);
            EditText wipeVerify = edit("Retype numeric Discord ID", false);
            wipeVerify.setInputType(InputType.TYPE_CLASS_NUMBER);
            addField(stats, "Verification 1", wipeVerify);
            EditText wipePhrase = edit("WIPE HOMEWORLDS", false);
            addField(stats, "Verification 2 — type exactly", wipePhrase);

            Button wipe = dangerButton("Permanently Wipe Homeworlds Data");
            stats.addView(wipe, buttonParams());
            wipe.setOnClickListener(v -> resolveHomeworldsMemberId(
                    wipePlayer.getText().toString(), playerId -> {
                        String verifyId = wipeVerify.getText().toString().trim();
                        String phrase = wipePhrase.getText().toString().trim();
                        if (!playerId.equals(verifyId)) {
                            toast("Verification failed: the retyped Discord ID does not match.");
                            return;
                        }
                        if (!"WIPE HOMEWORLDS".equals(phrase)) {
                            toast("Verification failed: type WIPE HOMEWORLDS exactly.");
                            return;
                        }
                        confirm("FINAL CONFIRMATION",
                                "Permanently wipe ALL stored Homeworlds stats/history/corrections for Discord user " +
                                        playerId + "? This cannot be undone except from backup.",
                                () -> {
                                    JSONObject body = new JSONObject();
                                    try {
                                        body.put("guild_id", currentGuildId);
                                        body.put("player_id", playerId);
                                        body.put("verify_player_id", verifyId);
                                        body.put("confirmation_phrase", phrase);
                                    } catch (Exception ignored) {}
                                    post("suite_homeworlds_wipe", body, result -> {
                                        toast(result.optString("message", "Homeworlds user data wiped."));
                                        wipePlayer.setText("");
                                        wipeVerify.setText("");
                                        wipePhrase.setText("");
                                        loadHomeworldsInto(holder, filter);
                                    });
                                });
                    }));

            c.addView(stats, cardParams());
        }

        loadHomeworldsInto(holder, filter);
        return wrapScroll(c);
    }

    private void loadHomeworldsInto(LinearLayout holder, EditText filter) {
        String filterText = filter == null ? "" : filter.getText().toString().trim();
        holder.removeAllViews();
        holder.addView(text("Loading Homeworlds…", 13, MUTED, false));
        io.execute(() -> {
            try {
                JSONObject data = api.api("suite_homeworlds", "GET", null,
                        "&guild_id=" + URLEncoder.encode(currentGuildId, "UTF-8"));
                main.post(() -> renderHomeworldsData(holder, data, filter, filterText));
            } catch (Exception e) {
                main.post(() -> {
                    holder.removeAllViews();
                    holder.addView(text(e.getMessage(), 13, RED, false));
                });
            }
        });
    }

    private void renderHomeworldsData(
            LinearLayout holder, JSONObject data, EditText filter, String filterText) {
        holder.removeAllViews();

        JSONObject db = data.optJSONObject("database");
        if (db == null) db = new JSONObject();

        holder.addView(sectionTitle("Database & Head Pool"));
        holder.addView(statLine("Head Pool",
                data.optBoolean("discord_ready", false) ? "Online / ready" : "Not ready"));
        if (data.has("latency_ms")) {
            holder.addView(statLine("Latency", data.optInt("latency_ms", 0) + " ms"));
        }
        holder.addView(statLine("MySQL",
                db.optBoolean("ok", false)
                        ? "Connected • " + db.optString("database", "") + " • " + db.optString("version", "")
                        : "Error • " + db.optString("error", "unknown")));

        holder.addView(spacer(10));
        holder.addView(sectionTitle("Active / paused games"));
        JSONArray games = data.optJSONArray("games");
        int shown = 0;
        String needle = filterText == null ? "" : filterText.toLowerCase(Locale.US);
        if (games != null) {
            for (int i = 0; i < games.length(); i++) {
                JSONObject game = games.optJSONObject(i);
                if (game == null) continue;
                if (!needle.isEmpty() &&
                        !game.toString().toLowerCase(Locale.US).contains(needle)) continue;

                shown++;
                LinearLayout row = cardInner();
                row.addView(text("Match " + game.optString("id", "—"), 16, TEXT, true));
                String details = "Channel " + game.optString("channel_id", "—") +
                        " • " + game.optString("status", "");
                if (!game.optString("turn", "").isEmpty()) {
                    details += " • turn " + game.optString("turn", "");
                }
                row.addView(text(details, 12, MUTED, false));
                row.addView(text(homeworldPlayersText(game.opt("players")), 12, MUTED, false));

                String channelId = game.optString("channel_id", "");
                if (!channelId.isEmpty()) {
                    Button stop = dangerButton("Force Stop in Game Channel");
                    row.addView(stop, buttonParams());
                    stop.setOnClickListener(v -> confirm(
                            "Force-stop Homeworlds game?",
                            "Force-stop the Homeworlds game in channel " + channelId + "?",
                            () -> {
                                JSONObject body = new JSONObject();
                                try {
                                    body.put("guild_id", currentGuildId);
                                    body.put("channel_id", channelId);
                                    body.put("command", "hw stop");
                                } catch (Exception ignored) {}
                                post("suite_homeworlds_command", body, result -> {
                                    toast(result.optString("message", "Command complete."));
                                    loadHomeworldsInto(holder, filter);
                                });
                            }));
                }
                holder.addView(row, cardParams());
            }
        }
        if (shown == 0) {
            holder.addView(text(
                    "No discovered active games match this filter. The bridge also checks MySQL and fallback state.",
                    13, MUTED, false));
        }

        holder.addView(spacer(10));
        holder.addView(sectionTitle("Matchmaking queue"));
        JSONArray queue = data.optJSONArray("queue");
        if (queue == null || queue.length() == 0) {
            holder.addView(text("No queue entries discovered.", 13, MUTED, false));
        } else {
            for (int i = 0; i < queue.length(); i++) {
                JSONObject item = queue.optJSONObject(i);
                if (item == null) continue;
                LinearLayout row = cardInner();
                row.addView(text(item.optString("id", "Queue entry"), 15, TEXT, true));
                row.addView(text(homeworldPlayersText(item.opt("players")) +
                        " • " + item.optString("status", "queued"), 12, MUTED, false));
                holder.addView(row, cardParams());
            }
        }
    }

    private String homeworldPlayersText(Object players) {
        if (players == null || players == JSONObject.NULL) return "";
        if (players instanceof JSONArray) {
            JSONArray arr = (JSONArray) players;
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < arr.length(); i++) {
                Object value = arr.opt(i);
                if (value == null || value == JSONObject.NULL) continue;
                if (out.length() > 0) out.append(" vs ");
                if (value instanceof JSONObject) {
                    JSONObject p = (JSONObject) value;
                    out.append(p.optString("display_name",
                            p.optString("username", p.optString("id", p.toString()))));
                } else {
                    out.append(String.valueOf(value));
                }
            }
            return out.toString();
        }
        if (players instanceof JSONObject) return players.toString();
        return String.valueOf(players);
    }

    private String homeworldScopeValue(Spinner spinner) {
        switch (spinner.getSelectedItemPosition()) {
            case 1: return "overall";
            case 2: return "pvp";
            case 3: return "easy";
            case 4: return "normal";
            case 5: return "hard";
            default: return "";
        }
    }

    private String homeworldStatValue(Spinner spinner) {
        switch (spinner.getSelectedItemPosition()) {
            case 1: return "wins";
            case 2: return "losses";
            case 3: return "draws";
            case 4: return "games";
            case 5: return "cancelled";
            case 6: return "unknown";
            default: return "";
        }
    }

    private void resolveHomeworldsMemberId(String raw, StringCallback callback) {
        String query = raw == null ? "" : raw.trim();
        if (query.isEmpty()) {
            toast("Enter a member name, @username, mention, or Discord ID.");
            return;
        }

        String direct = extractDiscordId(query);
        if (!direct.isEmpty()) {
            callback.onString(direct);
            return;
        }

        showBusy(true);
        io.execute(() -> {
            try {
                JSONObject data = api.api("mod_member_lookup", "GET", null,
                        "&guild_id=" + URLEncoder.encode(currentGuildId, "UTF-8") +
                                "&q=" + URLEncoder.encode(query, "UTF-8"));
                JSONArray members = data.optJSONArray("members");
                main.post(() -> {
                    showBusy(false);
                    if (members == null || members.length() == 0) {
                        toast("No matching Discord member was found.");
                        return;
                    }
                    if (members.length() == 1) {
                        JSONObject member = members.optJSONObject(0);
                        if (member != null) callback.onString(member.optString("id", ""));
                        return;
                    }

                    List<String> labels = new ArrayList<>();
                    List<String> ids = new ArrayList<>();
                    for (int i = 0; i < members.length() && i < 15; i++) {
                        JSONObject member = members.optJSONObject(i);
                        if (member == null) continue;
                        String id = member.optString("id", "");
                        if (id.isEmpty()) continue;
                        ids.add(id);
                        labels.add(member.optString("display_name",
                                member.optString("username", id)) +
                                "  •  @" + member.optString("username", "") +
                                "  •  " + id);
                    }
                    if (ids.isEmpty()) {
                        toast("No matching Discord member was found.");
                        return;
                    }
                    new AlertDialog.Builder(this)
                            .setTitle("Choose matching member")
                            .setItems(labels.toArray(new String[0]),
                                    (dialog, which) -> callback.onString(ids.get(which)))
                            .setNegativeButton("Cancel", null)
                            .show();
                });
            } catch (Exception e) {
                main.post(() -> {
                    showBusy(false);
                    toast(e.getMessage());
                });
            }
        });
    }

    private String extractDiscordId(String raw) {
        if (raw == null) return "";
        java.util.regex.Matcher matcher =
                java.util.regex.Pattern.compile("\\d{15,22}").matcher(raw);
        return matcher.find() ? matcher.group() : "";
    }

    private View renderHealthPage() {
        LinearLayout c = scrollColumn();
        c.addView(pageHeading("Live Bot Health", "Live Sharla, Head Pool, and website heartbeat status."));

        LinearLayout holder = card();
        holder.addView(text("Checking…", 14, MUTED, false));
        c.addView(holder, cardParams());

        io.execute(() -> {
            JSONObject health = null;
            JSONObject heartbeat = null;
            String healthError = null;
            String heartbeatError = null;

            try {
                health = api.api("mod_health", "GET", null,
                        "&guild_id=" + URLEncoder.encode(currentGuildId, "UTF-8"));
            } catch (Exception e) {
                healthError = e.getMessage();
            }

            try {
                heartbeat = api.publicJson("api/headpool.json");
            } catch (Exception e) {
                heartbeatError = e.getMessage();
            }

            final JSONObject finalHealth = health;
            final JSONObject finalHeartbeat = heartbeat;
            final String finalHealthError = healthError;
            final String finalHeartbeatError = heartbeatError;

            main.post(() -> {
                holder.removeAllViews();

                if (finalHealth != null) {
                    holder.addView(botHealthCard("Sharla", finalHealth.optJSONObject("sharla"), true));
                    holder.addView(spacer(12));
                    holder.addView(botHealthCard("Head Pool", finalHealth.optJSONObject("headpool"), false));
                    holder.addView(spacer(12));
                } else {
                    holder.addView(text("Bot health: " +
                            (finalHealthError == null ? "Unavailable" : finalHealthError), 14, RED, false));
                    holder.addView(spacer(12));
                }

                holder.addView(heartbeatHealthCard(finalHeartbeat, finalHeartbeatError));

                if (finalHealth != null) {
                    holder.addView(spacer(8));
                    holder.addView(text("Checked " + finalHealth.optString("checked_at", ""), 12, MUTED, false));
                }
            });
        });

        return wrapScroll(c);
    }

    private View heartbeatHealthCard(JSONObject data, String error) {
        LinearLayout box = cardInner();
        box.addView(text("💓 Head Pool Website Heartbeat", 18, TEXT, true));

        if (data == null) {
            box.addView(text("Unavailable", 14, RED, true));
            box.addView(statLine("Offline threshold", "150 sec"));
            box.addView(statLine("HTTP result", "Failed"));
            box.addView(text("Could not read api/headpool.json" +
                    (error == null ? "." : ": " + error), 12, MUTED, false));
            return box;
        }

        String updatedAt = data.optString("updated_at", "");
        long ageSeconds = -1;
        try {
            ageSeconds = Math.max(0,
                    (System.currentTimeMillis() - Instant.parse(updatedAt).toEpochMilli()) / 1000L);
        } catch (Exception ignored) {}

        boolean healthy = ageSeconds >= 0 && ageSeconds < 150;
        long commandTotal = data.has("command_count")
                ? data.optLong("command_count", 0)
                : data.optLong("slash_commands", 0) + data.optLong("prefix_commands", 0);
        long uptime = data.optLong("uptime_seconds", 0);
        if (healthy && ageSeconds >= 0) uptime += ageSeconds;

        box.addView(text(healthy ? "● Healthy" : "● Stale / offline",
                16, healthy ? GREEN : RED, true));
        box.addView(statLine("Last heartbeat",
                ageSeconds < 0 ? "Unknown" : heartbeatAgeText(ageSeconds)));
        box.addView(statLine("Offline threshold", "150 sec"));
        box.addView(statLine("HTTP result", "HTTP 200"));
        box.addView(statLine("Updated", updatedAt.isEmpty() ? "—" : updatedAt));
        box.addView(statLine("Servers", data.has("guilds") ? String.valueOf(data.optInt("guilds", 0)) : "—"));
        box.addView(statLine("Commands", commandTotal > 0 ? String.valueOf(commandTotal) : "—"));
        box.addView(statLine("Bot uptime", uptime > 0 ? durationText(uptime) : "—"));
        box.addView(statLine("Website state", ageSeconds < 0 ? "Unknown" : (healthy ? "Fresh" : "Expired")));

        box.addView(text(
                healthy
                        ? "Nerdhalla is receiving a fresh Head Pool heartbeat."
                        : "The heartbeat is older than the website cutoff. Head Pool may still be running, but Nerdhalla will treat this heartbeat as offline until a fresh update arrives.",
                12, MUTED, false));
        return box;
    }

    private String heartbeatAgeText(long seconds) {
        if (seconds < 60) return seconds + "s ago";
        long minutes = seconds / 60;
        long remSeconds = seconds % 60;
        if (minutes < 60) return minutes + "m " + remSeconds + "s ago";
        long hours = minutes / 60;
        long remMinutes = minutes % 60;
        if (hours < 24) return hours + "h " + remMinutes + "m ago";
        long days = hours / 24;
        long remHours = hours % 24;
        return days + "d " + remHours + "h ago";
    }

    private String durationText(long seconds) {
        long s = Math.max(0, seconds);
        long days = s / 86400;
        s %= 86400;
        long hours = s / 3600;
        s %= 3600;
        long minutes = s / 60;
        if (days > 0) return days + "d " + hours + "h";
        if (hours > 0) return hours + "h " + minutes + "m";
        return minutes + "m";
    }

    private View botHealthCard(String name, JSONObject d, boolean sharla) {
        if (d == null) d = new JSONObject();
        LinearLayout box = cardInner();
        boolean online = d.optBoolean("online", false);
        box.addView(text(name + "  " + (online ? "● Online" : "● Offline"), 18, online ? GREEN : RED, true));
        if (sharla) {
            box.addView(statLine("Discord gateway", d.optBoolean("discord_ready", false) ? "Ready" : "Not ready"));
            box.addView(statLine("Latency", d.has("latency_ms") ? d.optInt("latency_ms") + " ms" : "—"));
            box.addView(statLine("Servers", String.valueOf(d.optInt("guild_count", 0))));
            box.addView(statLine("Members", String.valueOf(d.optInt("member_count", 0))));
        } else {
            box.addView(statLine("Service", d.optString("active_state", "unknown")));
            box.addView(statLine("Process", d.optString("sub_state", "unknown")));
            box.addView(statLine("PID", d.optString("pid", "—")));
        }
        return box;
    }

    private View renderTalkPage() {
        LinearLayout c = scrollColumn();
        c.addView(pageHeading("Talk as Sharla", "Send a message through Sharla."));

        LinearLayout form = card();
        List<JSONObject> channels = filterChannels("text");
        Spinner channel = spinnerForObjects(channels, "name");
        form.addView(label("Channel"));
        form.addView(channel, fullWrap());

        EditText reply = edit("Reply message ID (optional)", false);
        form.addView(label("Reply to message"));
        form.addView(reply, fullWrap());

        EditText message = edit("Message", true);
        message.setMinLines(5);
        message.setMaxLines(10);
        form.addView(label("Message"));
        form.addView(message, fullWrap());

        Button send = primaryButton("Send as Sharla");
        form.addView(send, buttonParams());

        send.setOnClickListener(v -> {
            int pos = channel.getSelectedItemPosition();
            if (pos < 0 || pos >= channels.size()) return;
            String bodyText = message.getText().toString().trim();
            if (bodyText.isEmpty()) {
                toast("Type a message first.");
                return;
            }
            if (bodyText.length() > 2000 || bodyText.contains("@everyone") || bodyText.contains("@here")) {
                toast("Message is too long or contains a disabled mass mention.");
                return;
            }
            JSONObject b = new JSONObject();
            try {
                b.put("guild_id", currentGuildId);
                b.put("channel_id", channels.get(pos).optString("id", ""));
                b.put("message", bodyText);
                b.put("reply_message_id", reply.getText().toString().trim());
            } catch (Exception ignored) {}
            post("suite_talk_send", b, result -> {
                toast("Sent to #" + result.optString("channel_name", "channel"));
                message.setText("");
            });
        });

        c.addView(form, cardParams());
        return wrapScroll(c);
    }

    private View renderMembersPage() {
        selectedMemberId = "";
        selectedMemberName = "";
        memberRoleObjects = jsonList(guildData.optJSONArray("roles"));

        LinearLayout c = scrollColumn();
        c.addView(pageHeading("Member Lookup", "Search members and use your permitted moderation controls."));

        LinearLayout search = card();
        EditText q = edit("Name, @mention, or Discord user ID", false);
        search.addView(q, fullWrap());
        Button go = primaryButton("Look Up Member");
        search.addView(go, buttonParams());

        LinearLayout results = new LinearLayout(this);
        results.setOrientation(LinearLayout.VERTICAL);
        search.addView(results);
        c.addView(search, cardParams());

        if (hasAnyMemberModeration()) {
            LinearLayout mod = card();
            mod.addView(sectionTitle("🛡 Moderate selected member"));

            memberModerationSelected = text(
                    "Choose Manage on a member result.", 13, MUTED, false);
            mod.addView(memberModerationSelected);

            memberModerationReason = edit(
                    "Reason for warning / timeout / kick / ban", false);
            addField(mod, "Reason", memberModerationReason);

            memberTimeoutSpinner = darkSpinner(list(
                    "5 minutes", "10 minutes", "30 minutes", "1 hour",
                    "6 hours", "1 day", "7 days"));
            addField(mod, "Timeout", memberTimeoutSpinner);

            List<String> roleLabels = new ArrayList<>();
            roleLabels.add("Choose role");
            for (JSONObject role : memberRoleObjects) {
                roleLabels.add("@" + role.optString("name", role.optString("id", "Role")));
            }
            memberRoleSpinner = darkSpinner(roleLabels);
            addField(mod, "Role", memberRoleSpinner);

            if (hasPerm("member_moderation")) {
                addMemberActionButton(mod, "Warn", "warn", false);
                addMemberActionButton(mod, "Timeout", "timeout", false);
                addMemberActionButton(mod, "Remove Timeout", "untimeout", false);
            }

            if (hasPerm("member_roles")) {
                addMemberActionButton(mod, "Add Role", "add_role", false);
                addMemberActionButton(mod, "Remove Role", "remove_role", false);
            }

            if (hasPerm("member_kick_ban")) {
                addMemberActionButton(mod, "Kick", "kick", true);
                addMemberActionButton(mod, "Ban", "ban", true);

                EditText unbanId = edit("Banned Discord user ID", false);
                addField(mod, "Unban by ID", unbanId);
                Button unban = secondaryButton("Unban ID");
                mod.addView(unban, buttonParams());
                unban.setOnClickListener(v -> {
                    String id = unbanId.getText().toString().trim();
                    if (id.isEmpty()) {
                        toast("Enter a banned Discord user ID.");
                        return;
                    }
                    runMemberAction("unban", id);
                });
            }

            c.addView(mod, cardParams());
        }

        go.setOnClickListener(v -> {
            String query = q.getText().toString().trim();
            if (query.isEmpty()) {
                toast("Enter a member name, mention, or user ID.");
                return;
            }

            results.removeAllViews();
            results.addView(text("Searching…", 13, MUTED, false));
            io.execute(() -> {
                try {
                    JSONObject d = api.api("mod_member_lookup", "GET", null,
                            "&guild_id=" + URLEncoder.encode(currentGuildId, "UTF-8") +
                                    "&q=" + URLEncoder.encode(query, "UTF-8"));
                    main.post(() -> renderMemberResults(results, d.optJSONArray("members")));
                } catch (Exception e) {
                    main.post(() -> {
                        results.removeAllViews();
                        results.addView(text(e.getMessage(), 13, RED, false));
                    });
                }
            });
        });

        return wrapScroll(c);
    }

    private boolean hasAnyMemberModeration() {
        return hasPerm("member_moderation") ||
                hasPerm("member_roles") ||
                hasPerm("member_kick_ban");
    }

    private void addMemberActionButton(LinearLayout parent, String label, String action, boolean dangerous) {
        Button button = dangerous ? dangerButton(label) : secondaryButton(label);
        parent.addView(button, buttonParams());
        button.setOnClickListener(v -> runMemberAction(action, selectedMemberId));
    }

    private int selectedTimeoutMinutes() {
        if (memberTimeoutSpinner == null) return 5;
        switch (memberTimeoutSpinner.getSelectedItemPosition()) {
            case 1: return 10;
            case 2: return 30;
            case 3: return 60;
            case 4: return 360;
            case 5: return 1440;
            case 6: return 10080;
            default: return 5;
        }
    }

    private String selectedMemberRoleId() {
        if (memberRoleSpinner == null) return "";
        int pos = memberRoleSpinner.getSelectedItemPosition();
        int idx = pos - 1;
        if (idx < 0 || idx >= memberRoleObjects.size()) return "";
        return memberRoleObjects.get(idx).optString("id", "");
    }

    private void runMemberAction(String action, String memberId) {
        if (memberId == null || memberId.trim().isEmpty()) {
            toast("Choose a member first.");
            return;
        }

        String roleId = selectedMemberRoleId();
        if (("add_role".equals(action) || "remove_role".equals(action)) && roleId.isEmpty()) {
            toast("Choose a role first.");
            return;
        }

        Runnable execute = () -> {
            JSONObject body = new JSONObject();
            try {
                body.put("guild_id", currentGuildId);
                body.put("member_id", memberId.trim());
                body.put("action", action);
                body.put("reason", memberModerationReason == null
                        ? "" : memberModerationReason.getText().toString().trim());
                body.put("minutes", String.valueOf(selectedTimeoutMinutes()));
                body.put("role_id", roleId);
            } catch (Exception ignored) {}

            post("suite_member_action", body, result -> {
                toast(result.optString("message", "Done."));
                if ("kick".equals(action) || "ban".equals(action)) {
                    selectedMemberId = "";
                    selectedMemberName = "";
                    if (memberModerationSelected != null) {
                        memberModerationSelected.setText("Choose Manage on a member result.");
                    }
                }
            });
        };

        if ("kick".equals(action) || "ban".equals(action)) {
            confirm(action.equals("kick") ? "Kick member?" : "Ban member?",
                    (action.equals("kick") ? "Kick " : "Ban ") +
                            (selectedMemberName.isEmpty() ? memberId : selectedMemberName) + "?",
                    execute);
        } else {
            execute.run();
        }
    }

    private void renderMemberResults(LinearLayout results, JSONArray members) {
        results.removeAllViews();

        if (members == null || members.length() == 0) {
            results.addView(text("No matching member found.", 13, MUTED, false));
            return;
        }

        for (int i = 0; i < members.length(); i++) {
            JSONObject m = members.optJSONObject(i);
            if (m == null) continue;

            LinearLayout card = cardInner();
            String display = m.optString("display_name",
                    m.optString("username", m.optString("id", "Member")));
            card.addView(text(display, 17, TEXT, true));
            card.addView(text("@" + m.optString("username", "") +
                    "  •  " + m.optString("id", ""), 12, MUTED, false));
            card.addView(statLine("Administrator",
                    m.optBoolean("administrator", false) ? "Yes" : "No"));
            card.addView(statLine("Joined", m.optString("joined_at", "—")));
            card.addView(statLine("Account created", m.optString("created_at", "—")));
            card.addView(statLine("Presence", m.optString("status", "Not tracked")));

            JSONArray roles = m.optJSONArray("roles");
            if (roles != null && roles.length() > 0) {
                StringBuilder roleText = new StringBuilder();
                for (int r = 0; r < roles.length(); r++) {
                    JSONObject role = roles.optJSONObject(r);
                    if (role == null) continue;
                    if (roleText.length() > 0) roleText.append(", ");
                    roleText.append("@").append(role.optString("name", role.optString("id", "Role")));
                }
                if (roleText.length() > 0) card.addView(statLine("Roles", roleText.toString()));
            }

            JSONObject profile = m.optJSONObject("profile");
            if (profile != null) {
                card.addView(spacer(6));
                card.addView(text("Sharla: " + profile.optString("title", "No title") +
                        " • " + profile.optString("tier", "No tier") +
                        " • " + profile.optInt("tokens", 0) + " tokens", 13, GOLD, false));
            }

            if (hasAnyMemberModeration()) {
                Button manage = secondaryButton("Manage " + display);
                card.addView(manage, buttonParams());
                manage.setOnClickListener(v -> {
                    selectedMemberId = m.optString("id", "");
                    selectedMemberName = display;
                    if (memberModerationSelected != null) {
                        memberModerationSelected.setText(
                                "Selected: " + display + " (" + selectedMemberId + ")");
                    }
                    toast("Selected " + display + " for moderation.");
                });
            }

            results.addView(card, cardParams());
        }
    }

    private View renderSettingsPage() {
        LinearLayout c = scrollColumn();
        c.addView(pageHeading("Server Settings", "Native controls for Sharla server configuration."));

        JSONObject settings = guildData.optJSONObject("settings");
        if (settings == null) settings = new JSONObject();

        LinearLayout form = card();
        List<JSONObject> channels = jsonList(guildData.optJSONArray("channels"));
        List<JSONObject> roles = jsonList(guildData.optJSONArray("roles"));

        Spinner support = objectSpinnerWithBlank(filterObjects(channels, "type", "text"), "name", settings.optString("support_channel_id", ""));
        Spinner room = objectSpinnerWithBlank(filterObjects(channels, "type", "category"), "name", settings.optString("room_category_id", ""));
        Spinner voice = objectSpinnerWithBlank(filterObjects(channels, "type", "voice"), "name", settings.optString("voice_hub_channel_id", ""));
        Spinner vote = objectSpinnerWithBlank(filterObjects(channels, "type", "text"), "name", settings.optString("vote_reminder_channel_id", ""));
        Spinner meme = objectSpinnerWithBlank(filterObjects(channels, "type", "text"), "name", settings.optString("meme_channel_id", ""));
        Spinner verified = objectSpinnerWithBlank(roles, "name", settings.optString("verified_role_id", ""));
        Spinner tagRole = objectSpinnerWithBlank(roles, "name", settings.optString("tag_role_id", ""));

        addField(form, "Support channel", support);
        addField(form, "Personal-room category", room);
        addField(form, "Temporary voice hub", voice);
        addField(form, "Vote reminder channel", vote);
        addField(form, "Meme channel", meme);
        addField(form, "Verified/member role", verified);
        addField(form, "Tag role", tagRole);

        EditText tagText = edit("Tag text", false);
        tagText.setText(settings.optString("tag_text", ""));
        addField(form, "Tag text", tagText);
        EditText tagName = edit("Tag display name", false);
        tagName.setText(settings.optString("tag_name", ""));
        addField(form, "Tag display name", tagName);

        Switch rooms = switchRow("🏠 Personal Rooms", settings.optBoolean("rooms_enabled", false));
        Switch temp = switchRow("🔊 Temporary Voice", settings.optBoolean("voice_rooms_enabled", false));
        Switch tag = switchRow("🏷️ Server Tag", settings.optBoolean("tag_system_enabled", false));
        Switch voteRem = switchRow("🗳️ Vote Reminders", settings.optBoolean("vote_reminders_enabled", false));
        Switch memes = switchRow("😂 Meme Poster", settings.optBoolean("meme_poster_enabled", false));
        form.addView(rooms);
        form.addView(temp);
        form.addView(tag);
        form.addView(voteRem);
        form.addView(memes);

        Button save = primaryButton("Save Sharla Settings");
        form.addView(save, buttonParams());

        JSONObject finalSettings = settings;
        save.setOnClickListener(v -> {
            JSONObject fields = new JSONObject();
            try {
                fields.put("support_channel_id", selectedIdOrNull(support));
                fields.put("room_category_id", selectedIdOrNull(room));
                fields.put("voice_hub_channel_id", selectedIdOrNull(voice));
                fields.put("vote_reminder_channel_id", selectedIdOrNull(vote));
                fields.put("meme_channel_id", selectedIdOrNull(meme));
                fields.put("verified_role_id", selectedIdOrNull(verified));
                fields.put("tag_role_id", selectedIdOrNull(tagRole));
                fields.put("tag_text", tagText.getText().toString().trim());
                fields.put("tag_name", tagName.getText().toString().trim());
                fields.put("rooms_enabled", rooms.isChecked());
                fields.put("voice_rooms_enabled", temp.isChecked());
                fields.put("tag_system_enabled", tag.isChecked());
                fields.put("vote_reminders_enabled", voteRem.isChecked());
                fields.put("meme_poster_enabled", memes.isChecked());

                JSONObject b = new JSONObject();
                b.put("guild_id", currentGuildId);
                b.put("fields", fields);
                post("mod_update", b, result -> {
                    guildData = result;
                    toast("Sharla settings saved.");
                    renderAdmin();
                });
            } catch (Exception e) {
                toast(e.getMessage());
            }
        });

        c.addView(form, cardParams());
        return wrapScroll(c);
    }

    private View renderAnnouncementPage() {
        LinearLayout c = scrollColumn();
        c.addView(pageHeading("Announcements", "Compose, post, or schedule a Discord announcement."));

        List<JSONObject> channels = filterChannels("text");
        List<JSONObject> roles = jsonList(guildData.optJSONArray("roles"));

        LinearLayout form = card();
        Spinner channel = spinnerForObjects(channels, "name");
        String configured = guildData.optString("announcement_channel_id", "");
        setObjectSpinnerById(channel, channels, configured);
        addField(form, "Channel", channel);

        EditText title = edit("Announcement title", false);
        addField(form, "Title", title);

        EditText body = edit("Announcement message (Markdown)", true);
        body.setMinLines(7);
        addField(form, "Message", body);

        Spinner mention = darkSpinner(list("none", "everyone", "here", "role"));
        addField(form, "Mention", mention);

        Spinner role = spinnerForObjects(roles, "name");
        addField(form, "Role (when mention = role)", role);

        EditText color = edit("#4F46C8", false);
        color.setText("#4F46C8");
        addField(form, "Embed color", color);

        EditText image = edit("https://…", false);
        addField(form, "Image URL (optional)", image);

        EditText thumb = edit("https://…", false);
        addField(form, "Thumbnail URL (optional)", thumb);

        EditText footer = edit("Footer (optional)", false);
        addField(form, "Footer", footer);

        if (hasPerm("announcements")) {
            Button postNow = primaryButton("Post Announcement Now");
            form.addView(postNow, buttonParams());
            postNow.setOnClickListener(v -> {
                JSONObject payload = buildAnnouncementPayload(
                        channels, roles, channel, title, body, mention, role,
                        color, image, thumb, footer);
                if (payload == null) return;

                confirm("Post announcement?", "Post this announcement to Discord now?", () ->
                        post("mod_announcement", payload,
                                result -> toast("Announcement posted.")));
            });
        }

        if (hasPerm("scheduled_announcements")) {
            form.addView(spacer(14));
            form.addView(sectionTitle("🗓 Schedule this announcement"));

            Button when = secondaryButton("Choose Date & Time");
            form.addView(when, buttonParams());
            when.setOnClickListener(v -> chooseScheduleTime(when));

            Button schedule = primaryButton("Schedule Announcement");
            form.addView(schedule, buttonParams());
            schedule.setOnClickListener(v -> {
                if (scheduleAtMillis <= System.currentTimeMillis() + 20000) {
                    toast("Choose a time at least 20 seconds in the future.");
                    return;
                }

                JSONObject payload = buildAnnouncementPayload(
                        channels, roles, channel, title, body, mention, role,
                        color, image, thumb, footer);
                if (payload == null) return;

                try {
                    payload.put("run_at", Instant.ofEpochMilli(scheduleAtMillis).toString());
                    payload.put("recurrence", "once");
                    payload.put("timezone", java.util.TimeZone.getDefault().getID());
                    payload.put("weekdays", new JSONArray());
                } catch (Exception ignored) {}

                confirm("Schedule announcement?",
                        "Schedule this announcement for " +
                                new java.util.Date(scheduleAtMillis).toString() + "?",
                        () -> post("suite_schedule_save", payload, result -> {
                            toast("Announcement scheduled.");
                            scheduleAtMillis = 0;
                            when.setText("Choose Date & Time");
                        }));
            });
        }

        c.addView(form, cardParams());
        return wrapScroll(c);
    }

    private JSONObject buildAnnouncementPayload(
            List<JSONObject> channels,
            List<JSONObject> roles,
            Spinner channel,
            EditText title,
            EditText body,
            Spinner mention,
            Spinner role,
            EditText color,
            EditText image,
            EditText thumb,
            EditText footer) {

        int cp = channel.getSelectedItemPosition();
        if (cp < 0 || cp >= channels.size()) {
            toast("Choose a channel.");
            return null;
        }

        String titleTextValue = title.getText().toString().trim();
        String bodyText = body.getText().toString();
        if (titleTextValue.isEmpty() && bodyText.trim().isEmpty()) {
            toast("Add a title or announcement message first.");
            return null;
        }
        if (bodyText.length() > 4000) {
            toast("Announcement body is over 4000 Discord characters.");
            return null;
        }

        String mode = String.valueOf(mention.getSelectedItem());
        String roleId = null;
        if ("role".equals(mode)) {
            int rp = role.getSelectedItemPosition();
            if (rp < 0 || rp >= roles.size()) {
                toast("Choose a role to mention.");
                return null;
            }
            roleId = roles.get(rp).optString("id", "");
        }

        JSONObject payload = new JSONObject();
        try {
            payload.put("guild_id", currentGuildId);
            payload.put("channel_id", channels.get(cp).optString("id", ""));
            payload.put("title", titleTextValue);
            payload.put("markdown", bodyText);
            payload.put("color", color.getText().toString().trim());
            payload.put("image_url", image.getText().toString().trim());
            payload.put("thumbnail_url", thumb.getText().toString().trim());
            payload.put("footer", footer.getText().toString().trim());
            payload.put("mention_mode", mode);
            payload.put("role_id", roleId == null ? JSONObject.NULL : roleId);
        } catch (Exception ignored) {}
        return payload;
    }

    private View renderSchedulesPage() {
        LinearLayout c = scrollColumn();
        c.addView(pageHeading("Scheduled Announcements", "Create and manage future posts."));

        List<JSONObject> channels = filterChannels("text");
        LinearLayout form = card();
        Spinner channel = spinnerForObjects(channels, "name");
        addField(form, "Channel", channel);
        EditText title = edit("Title", false);
        addField(form, "Title", title);
        EditText body = edit("Message (Markdown)", true);
        body.setMinLines(5);
        addField(form, "Message", body);

        Button when = secondaryButton("Choose Date & Time");
        form.addView(when, buttonParams());
        when.setOnClickListener(v -> chooseScheduleTime(when));

        Button create = primaryButton("Schedule Announcement");
        form.addView(create, buttonParams());
        create.setOnClickListener(v -> {
            int pos = channel.getSelectedItemPosition();
            if (pos < 0 || pos >= channels.size()) {
                toast("Choose a channel.");
                return;
            }
            if (scheduleAtMillis <= System.currentTimeMillis() + 20000) {
                toast("Choose a time at least 20 seconds in the future.");
                return;
            }
            JSONObject b = new JSONObject();
            try {
                b.put("guild_id", currentGuildId);
                b.put("channel_id", channels.get(pos).optString("id", ""));
                b.put("title", title.getText().toString().trim());
                b.put("markdown", body.getText().toString());
                b.put("color", "#4F46C8");
                b.put("image_url", "");
                b.put("thumbnail_url", "");
                b.put("footer", "");
                b.put("mention_mode", "none");
                b.put("role_id", JSONObject.NULL);
                b.put("run_at", Instant.ofEpochMilli(scheduleAtMillis).toString());
                b.put("recurrence", "once");
                b.put("timezone", java.util.TimeZone.getDefault().getID());
                b.put("weekdays", new JSONArray());
            } catch (Exception ignored) {}
            post("suite_schedule_save", b, result -> {
                toast("Announcement scheduled.");
                scheduleAtMillis = 0;
                renderAdmin();
            });
        });

        c.addView(form, cardParams());

        LinearLayout list = card();
        list.addView(sectionTitle("Upcoming"));
        list.addView(text("Loading…", 13, MUTED, false));
        c.addView(list, cardParams());

        io.execute(() -> {
            try {
                JSONObject d = api.api("suite_schedules", "GET", null,
                        "&guild_id=" + URLEncoder.encode(currentGuildId, "UTF-8"));
                main.post(() -> renderSchedulesList(list, d.optJSONArray("pending")));
            } catch (Exception e) {
                main.post(() -> {
                    list.removeAllViews();
                    list.addView(sectionTitle("Upcoming"));
                    list.addView(text(e.getMessage(), 13, RED, false));
                });
            }
        });

        return wrapScroll(c);
    }

    private void renderSchedulesList(LinearLayout list, JSONArray pending) {
        list.removeAllViews();
        list.addView(sectionTitle("Upcoming"));
        if (pending == null || pending.length() == 0) {
            list.addView(text("No upcoming scheduled announcements.", 13, MUTED, false));
            return;
        }
        for (int i = 0; i < pending.length(); i++) {
            JSONObject x = pending.optJSONObject(i);
            if (x == null) continue;
            LinearLayout row = cardInner();
            row.addView(text(x.optString("title", "Untitled announcement"), 15, TEXT, true));
            row.addView(text(x.optString("run_at", "") + "  •  #" + x.optString("channel_name", "channel"), 12, MUTED, false));
            Button cancel = dangerButton("Cancel");
            row.addView(cancel, buttonParams());
            cancel.setOnClickListener(v -> confirm("Cancel schedule?", "Cancel this scheduled announcement?", () -> {
                JSONObject b = new JSONObject();
                try {
                    b.put("guild_id", currentGuildId);
                    b.put("schedule_id", x.optString("id", ""));
                } catch (Exception ignored) {}
                post("suite_schedule_cancel", b, result -> {
                    toast("Scheduled announcement cancelled.");
                    renderAdmin();
                });
            }));
            list.addView(row, cardParams());
        }
    }

    private void chooseScheduleTime(Button button) {
        Calendar c = Calendar.getInstance();
        DatePickerDialog d = new DatePickerDialog(this, (view, y, m, day) -> {
            TimePickerDialog t = new TimePickerDialog(this, (tv, hour, minute) -> {
                Calendar chosen = Calendar.getInstance();
                chosen.set(y, m, day, hour, minute, 0);
                scheduleAtMillis = chosen.getTimeInMillis();
                button.setText("Scheduled: " + chosen.getTime().toString());
            }, c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), false);
            t.show();
        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH));
        d.show();
    }

    private void post(String action, JSONObject body, JsonCallback callback) {
        showBusy(true);
        io.execute(() -> {
            try {
                JSONObject result = api.api(action, "POST", body);
                main.post(() -> {
                    showBusy(false);
                    callback.onResult(result);
                });
            } catch (Exception e) {
                main.post(() -> {
                    showBusy(false);
                    toast(e.getMessage());
                });
            }
        });
    }

    private void logoutFromApp() {
        showBusy(true);
        CookieManager cookies = CookieManager.getInstance();
        cookies.removeAllCookies(removed -> {
            cookies.flush();
            main.post(() -> {
                api.setCsrfToken("");
                session = null;
                meData = null;
                meDataFetchedElapsed = 0L;
                adminAccess = null;
                guildData = null;
                currentGuildId = "";
                currentTop = "my";
                currentMySection = "profile";
                currentAdminSection = "health";
                selectedMemberId = "";
                selectedMemberName = "";
                memberRoleObjects = new ArrayList<>();
                activeMenuScroller = null;
                pendingMenuTransitionDirection = 0;
                menuTransitionRunning = false;
                contentHost = null;
                busy = null;
                titleText = null;
                myNav = null;
                adminNav = null;
                bottomNav = null;
                adminButtonAllowed = false;
                nativeRoot = null;
                showLogin();
                toast("Logged out of Nerdhalla.");
            });
        });
    }

    private void setContent(View view) {
        if (contentHost == null) return;

        int direction = pendingMenuTransitionDirection;
        pendingMenuTransitionDirection = 0;

        if (direction == 0 || contentHost.getChildCount() == 0) {
            contentHost.removeAllViews();
            view.setTranslationX(0f);
            view.setAlpha(1f);
            contentHost.addView(view, match());
            menuTransitionRunning = false;
            return;
        }

        View oldView = contentHost.getChildAt(contentHost.getChildCount() - 1);
        int width = contentHost.getWidth();
        if (width <= 0) width = getResources().getDisplayMetrics().widthPixels;
        if (width <= 0) {
            contentHost.removeAllViews();
            contentHost.addView(view, match());
            menuTransitionRunning = false;
            return;
        }

        menuTransitionRunning = true;

        view.setTranslationX(direction > 0 ? width : -width);
        view.setAlpha(0.88f);
        contentHost.addView(view, match());

        long duration = 260L;

        oldView.animate()
                .translationX(direction > 0 ? -width : width)
                .alpha(0.88f)
                .setDuration(duration)
                .withEndAction(() -> {
                    if (oldView.getParent() == contentHost) {
                        contentHost.removeView(oldView);
                    }
                })
                .start();

        view.animate()
                .translationX(0f)
                .alpha(1f)
                .setDuration(duration)
                .withEndAction(() -> {
                    view.setTranslationX(0f);
                    view.setAlpha(1f);
                    menuTransitionRunning = false;
                })
                .start();
    }

    private void showError(String title, String message, Runnable retry) {
        LinearLayout c = scrollColumn();
        c.addView(pageHeading(title, "Could not load this section."));
        LinearLayout card = card();
        card.addView(text(message == null ? "Unknown error" : message, 14, RED, false));
        Button b = primaryButton("Retry");
        card.addView(b, buttonParams());
        b.setOnClickListener(v -> retry.run());
        c.addView(card, cardParams());
        setContent(wrapScroll(c));
    }

    private void showBusy(boolean show) {
        if (busy != null) busy.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private List<JSONObject> filterChannels(String type) {
        return filterObjects(jsonList(guildData.optJSONArray("channels")), "type", type);
    }

    private List<JSONObject> filterObjects(List<JSONObject> src, String key, String value) {
        List<JSONObject> out = new ArrayList<>();
        for (JSONObject x : src) if (value.equals(x.optString(key, ""))) out.add(x);
        return out;
    }

    private List<JSONObject> jsonList(JSONArray a) {
        List<JSONObject> out = new ArrayList<>();
        if (a == null) return out;
        for (int i = 0; i < a.length(); i++) {
            JSONObject x = a.optJSONObject(i);
            if (x != null) out.add(x);
        }
        return out;
    }

    private Spinner spinnerForObjects(List<JSONObject> objects, String labelKey) {
        List<String> labels = new ArrayList<>();
        for (JSONObject o : objects) labels.add(o.optString(labelKey, o.optString("id", "Item")));
        return darkSpinner(labels);
    }

    private Spinner objectSpinnerWithBlank(List<JSONObject> objects, String labelKey, String selectedId) {
        List<String> labels = new ArrayList<>();
        labels.add("Not configured");
        for (JSONObject o : objects) labels.add(o.optString(labelKey, o.optString("id", "Item")));
        Spinner s = darkSpinner(labels);
        s.setTag(objects);
        int pos = 0;
        for (int i = 0; i < objects.size(); i++) {
            if (selectedId.equals(objects.get(i).optString("id", ""))) {
                pos = i + 1;
                break;
            }
        }
        s.setSelection(pos);
        return s;
    }

    @SuppressWarnings("unchecked")
    private Object selectedIdOrNull(Spinner spinner) {
        int pos = spinner.getSelectedItemPosition();
        if (pos <= 0) return JSONObject.NULL;
        Object tag = spinner.getTag();
        if (!(tag instanceof List)) return JSONObject.NULL;
        List<JSONObject> list = (List<JSONObject>) tag;
        int idx = pos - 1;
        return idx >= 0 && idx < list.size() ? list.get(idx).optString("id", "") : JSONObject.NULL;
    }

    private void setObjectSpinnerById(Spinner s, List<JSONObject> list, String id) {
        for (int i = 0; i < list.size(); i++) {
            if (id.equals(list.get(i).optString("id", ""))) {
                s.setSelection(i);
                return;
            }
        }
    }

    private void addField(LinearLayout parent, String name, View field) {
        parent.addView(label(name));
        parent.addView(field, fullWrap());
    }

    private Switch switchRow(String name, boolean checked) {
        Switch s = new Switch(this);
        s.setText(name);
        s.setTextColor(TEXT);
        s.setTextSize(15);
        s.setChecked(checked);
        s.setPadding(0, dp(7), 0, dp(7));
        return s;
    }

    private View pageHeading(String title, String subtitle) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(12), dp(16), dp(4));
        box.addView(text(title, 24, TEXT, true));
        box.addView(text(subtitle, 13, MUTED, false));
        return box;
    }

    private LinearLayout scrollColumn() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(10), dp(4), dp(10), dp(18));
        return l;
    }

    private View wrapScroll(View child) {
        ScrollView s = new ScrollView(this);
        s.setFillViewport(true);
        s.setBackgroundColor(BG);
        s.addView(child, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return s;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(16), dp(16), dp(16), dp(16));
        c.setBackground(roundRect(PANEL, 16, LINE, 1));
        return c;
    }

    private LinearLayout cardInner() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(14), dp(14), dp(14), dp(14));
        c.setBackground(roundRect(PANEL2, 14, LINE, 1));
        return c;
    }

    private View infoCard(String title, String msg) {
        LinearLayout c = card();
        c.addView(sectionTitle(title));
        c.addView(text(msg, 14, MUTED, false));
        return c;
    }

    private TextView statLine(String key, String value) {
        TextView t = text(key + "\n" + value, 14, TEXT, false);
        t.setLineSpacing(0, 1.1f);
        t.setPadding(0, dp(7), 0, dp(7));
        return t;
    }

    private View bigStat(String value, String label) {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(0, dp(6), 0, dp(10));
        l.addView(text(value, 28, PURPLE, true));
        l.addView(text(label, 12, MUTED, false));
        return l;
    }

    private TextView sectionTitle(String s) {
        TextView t = text(s, 18, TEXT, true);
        t.setPadding(0, 0, 0, dp(8));
        return t;
    }

    private TextView label(String s) {
        TextView t = text(s, 12, MUTED, true);
        t.setPadding(0, dp(10), 0, dp(5));
        return t;
    }

    private TextView text(String value, float size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(rainbowNerdhalla(value));
        t.setTextSize(size);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }

    private CharSequence rainbowNerdhalla(String value) {
        if (value == null || value.isEmpty()) return value == null ? "" : value;

        SpannableString styled = new SpannableString(value);
        String lower = value.toLowerCase(Locale.US);
        String needle = "nerdhalla";
        int[] palette = {
                Color.rgb(255, 95, 109),
                Color.rgb(255, 159, 67),
                Color.rgb(255, 217, 61),
                Color.rgb(107, 255, 149),
                Color.rgb(77, 201, 255),
                Color.rgb(155, 108, 255),
                Color.rgb(255, 110, 199)
        };

        int start = 0;
        while ((start = lower.indexOf(needle, start)) >= 0) {
            for (int i = 0; i < needle.length(); i++) {
                float position = needle.length() <= 1
                        ? 0f
                        : (i / (float) (needle.length() - 1)) * (palette.length - 1);
                int left = Math.min(palette.length - 1, (int) Math.floor(position));
                int right = Math.min(palette.length - 1, left + 1);
                float amount = position - left;
                int color = blendColor(palette[left], palette[right], amount);
                styled.setSpan(
                        new ForegroundColorSpan(color),
                        start + i,
                        start + i + 1,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            start += needle.length();
        }
        return styled;
    }

    private int blendColor(int from, int to, float amount) {
        amount = Math.max(0f, Math.min(1f, amount));
        int r = Math.round(Color.red(from) + (Color.red(to) - Color.red(from)) * amount);
        int g = Math.round(Color.green(from) + (Color.green(to) - Color.green(from)) * amount);
        int b = Math.round(Color.blue(from) + (Color.blue(to) - Color.blue(from)) * amount);
        return Color.rgb(r, g, b);
    }

    private TextView bottomItem(String label) {
        TextView t = text(label, 12, MUTED, false);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(8), dp(4), dp(8), dp(4));
        return t;
    }

    private TextView actionIcon(String icon) {
        TextView t = text(icon, 25, TEXT, false);
        t.setGravity(Gravity.CENTER);
        t.setBackground(roundRect(PANEL, 12, LINE, 1));
        return t;
    }

    private TextView chip(String label, boolean selected) {
        TextView t = text(label, 13, selected ? TEXT : MUTED, selected);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(14), dp(9), dp(14), dp(9));
        t.setBackground(roundRect(selected ? Color.rgb(55, 38, 78) : PANEL,
                18, selected ? PURPLE : LINE, 1));
        return t;
    }

    private void styleSelected(TextView t, boolean selected) {
        t.setTextColor(selected ? TEXT : MUTED);
        t.setTypeface(Typeface.DEFAULT, selected ? Typeface.BOLD : Typeface.NORMAL);
        t.setBackground(roundRect(selected ? Color.rgb(55, 38, 78) : Color.TRANSPARENT,
                14, selected ? PURPLE : Color.TRANSPARENT, selected ? 1 : 0));
    }

    private Button primaryButton(String label) {
        return styledButton(label, PURPLE, TEXT);
    }

    private Button secondaryButton(String label) {
        return styledButton(label, PANEL2, TEXT);
    }

    private Button dangerButton(String label) {
        return styledButton(label, Color.rgb(112, 43, 51), TEXT);
    }

    private Button styledButton(String label, int fill, int text) {
        Button b = new Button(this);
        b.setText(rainbowNerdhalla(label));
        b.setAllCaps(false);
        b.setTextColor(text);
        b.setTextSize(14);
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        b.setBackground(roundRect(fill, 12, LINE, 1));
        return b;
    }

    private EditText edit(String hint, boolean multiline) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setHintTextColor(Color.rgb(117, 107, 129));
        e.setTextColor(TEXT);
        e.setTextSize(15);
        e.setPadding(dp(12), dp(10), dp(12), dp(10));
        e.setBackground(roundRect(PANEL2, 10, LINE, 1));
        if (multiline) {
            e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
            e.setGravity(Gravity.TOP | Gravity.START);
        } else {
            e.setSingleLine(true);
        }
        return e;
    }

    private Spinner darkSpinner(List<String> labels) {
        Spinner s = new Spinner(this);
        ArrayAdapter<String> a = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, labels) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                TextView v = (TextView) super.getView(position, convertView, parent);
                v.setTextColor(TEXT);
                v.setTextSize(14);
                v.setPadding(dp(12), dp(10), dp(12), dp(10));
                return v;
            }

            @Override
            public View getDropDownView(int position, View convertView, ViewGroup parent) {
                TextView v = (TextView) super.getDropDownView(position, convertView, parent);
                v.setTextColor(TEXT);
                v.setBackgroundColor(PANEL2);
                v.setPadding(dp(12), dp(12), dp(12), dp(12));
                return v;
            }
        };
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        s.setAdapter(a);
        s.setBackground(roundRect(PANEL2, 10, LINE, 1));
        return s;
    }

    private void setSpinnerValue(Spinner s, String value) {
        for (int i = 0; i < s.getCount(); i++) {
            if (value.equals(String.valueOf(s.getItemAtPosition(i)))) {
                s.setSelection(i);
                return;
            }
        }
    }

    private LinearLayout.LayoutParams cardParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.setMargins(dp(6), dp(6), dp(6), dp(6));
        return p;
    }

    private LinearLayout.LayoutParams buttonParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        p.topMargin = dp(12);
        return p;
    }

    private LinearLayout.LayoutParams chipParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(42));
        p.setMargins(dp(3), 0, dp(3), 0);
        return p;
    }

    private LinearLayout.LayoutParams fullWrap() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.bottomMargin = dp(2);
        return p;
    }

    private FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private View spacer(int height) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, dp(height)));
        return v;
    }

    private GradientDrawable roundRect(int fill, int radiusDp, int stroke, int strokeDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(radiusDp));
        if (strokeDp > 0) d.setStroke(dp(strokeDp), stroke);
        return d;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private String fmt(long n) {
        return NumberFormat.getIntegerInstance().format(n);
    }

    private String trim1(double d) {
        if (Math.abs(d - Math.rint(d)) < 0.0001) return String.valueOf((int)Math.rint(d));
        return String.format(Locale.US, "%.1f", d);
    }

    private String record(JSONObject x) {
        if (x == null) return "0W • 0L • 0D";
        return x.optInt("wins", 0) + "W • " + x.optInt("losses", 0) + "L • " + x.optInt("draws", 0) + "D";
    }

    private int tokenValue(String rarity) {
        switch (rarity) {
            case "Uncommon": return 2;
            case "Rare": return 5;
            case "Legendary": return 15;
            case "Mythic": return 50;
            default: return 1;
        }
    }

    private int rarityColor(String rarity) {
        switch (rarity) {
            case "Mythic": return Color.rgb(244, 114, 182);
            case "Legendary": return GOLD;
            case "Rare": return Color.rgb(96, 165, 250);
            case "Uncommon": return GREEN;
            default: return TEXT;
        }
    }

    private List<String> list(String... values) {
        List<String> out = new ArrayList<>();
        Collections.addAll(out, values);
        return out;
    }

    private void loadImage(String url, ImageView image) {
        io.execute(() -> {
            try (InputStream in = new URL(url).openStream()) {
                Bitmap bitmap = BitmapFactory.decodeStream(in);
                if (bitmap != null) main.post(() -> image.setImageBitmap(bitmap));
            } catch (Exception ignored) {}
        });
    }

    private void confirm(String title, String message, Runnable yes) {
        new AlertDialog.Builder(this)
                .setTitle(rainbowNerdhalla(title))
                .setMessage(rainbowNerdhalla(message))
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Confirm", (d, which) -> yes.run())
                .show();
    }

    private void toast(String message) {
        Toast.makeText(this,
                rainbowNerdhalla(message == null ? "Done" : message),
                Toast.LENGTH_LONG).show();
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }

    private interface JsonCallback {
        void onResult(JSONObject result);
    }

    private interface StringCallback {
        void onString(String value);
    }

    private interface PositionCallback {
        void onPosition(int position);
    }

    private static class SimpleItemSelectedListener implements android.widget.AdapterView.OnItemSelectedListener {
        private final PositionCallback callback;
        SimpleItemSelectedListener(PositionCallback callback) {
            this.callback = callback;
        }
        @Override
        public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
            callback.onPosition(position);
        }
        @Override
        public void onNothingSelected(android.widget.AdapterView<?> parent) {}
    }
}
