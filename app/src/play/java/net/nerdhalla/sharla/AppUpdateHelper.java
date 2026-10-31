package net.nerdhalla.sharla;

import android.app.Activity;
import android.os.Handler;
import android.widget.Button;
import android.widget.TextView;

/** Google Play variant: no website APK update functionality or download URL. */
final class AppUpdateHelper {
    AppUpdateHelper(Activity activity, ApiClient api, Handler main) {}
    void attachLogin(Button button) {}
    void attachNative(TextView link, Button banner) {}
    void onDestroy() {}
    void onResume() {}
}
