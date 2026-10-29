package org.telegram.messenger;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;

import org.telegram.ui.ActionBar.AlertDialog;

// Notifications depend on the keep-alive connection (no FCM for the fork's api_id),
// which Doze and OEM battery savers kill unless the app is exempt from battery optimization.
public final class ForkBattery {

    private static final String ASKED_KEY = "fork_battery_asked";

    public static boolean isOptimized() {
        if (Build.VERSION.SDK_INT < 23) {
            return false;
        }
        try {
            PowerManager pm = (PowerManager) ApplicationLoader.applicationContext.getSystemService(android.content.Context.POWER_SERVICE);
            return !pm.isIgnoringBatteryOptimizations(ApplicationLoader.applicationContext.getPackageName());
        } catch (Throwable e) {
            FileLog.e(e);
            return false;
        }
    }

    public static void requestExemption(Activity activity) {
        if (activity == null || Build.VERSION.SDK_INT < 23) {
            return;
        }
        try {
            Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + activity.getPackageName()));
            activity.startActivity(intent);
        } catch (Throwable e) {
            try {
                activity.startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Throwable e2) {
                FileLog.e(e2);
            }
        }
    }

    // Asks once after login; later the switch in fork settings does the same.
    public static void askOnce(Activity activity) {
        if (activity == null || activity.isFinishing() || !isOptimized() || !UserConfig.getInstance(UserConfig.selectedAccount).isClientActivated()) {
            return;
        }
        SharedPreferences preferences = MessagesController.getGlobalMainSettings();
        if (preferences.getBoolean(ASKED_KEY, false)) {
            return;
        }
        preferences.edit().putBoolean(ASKED_KEY, true).apply();
        new AlertDialog.Builder(activity)
                .setTitle(LocaleController.getString(R.string.ForkBatteryTitle))
                .setMessage(LocaleController.getString(R.string.ForkBatteryText))
                .setPositiveButton(LocaleController.getString(R.string.ForkBatteryAllow), (dialog, which) -> requestExemption(activity))
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .show();
    }

    private ForkBattery() {
    }
}
