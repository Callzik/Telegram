package org.telegram.messenger;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import org.json.JSONObject;
import org.telegram.ui.LaunchActivity;
import org.telegram.ui.web.HttpGetFileTask;
import org.telegram.ui.web.HttpGetTask;

import java.io.File;

// Self-updates from the fork's GitHub: reads ForkConfig.UPDATE_URL
// ({"version": "13.0.3", "build": 3, "file_url": "...apk", "changelog": "..."})
// and offers the APK when its build is newer than ForkConfig.FORK_BUILD.
public class ForkUpdater {

    private static ForkUpdater instance;
    public static ForkUpdater getInstance() {
        if (instance == null) {
            instance = new ForkUpdater();
        }
        return instance;
    }

    private static final long CHECK_INTERVAL = 1000 * 60 * 60 * 3; // 3 hours

    private String version;
    private int build;
    private String changelog;
    private String fileUrl;
    private String path;
    private long lastCheck;

    private boolean checking;
    private boolean downloading;
    private float downloadingProgress;
    private HttpGetFileTask downloadingTask;

    private ForkUpdater() {
        final SharedPreferences prefs = getPrefs();
        version = prefs.getString("version", null);
        build = prefs.getInt("build", 0);
        changelog = prefs.getString("changelog", null);
        fileUrl = prefs.getString("fileUrl", null);
        path = prefs.getString("path", null);
        lastCheck = prefs.getLong("lastCheck", 0);
        if (build <= ForkConfig.FORK_BUILD || !TextUtils.isEmpty(path) && !new File(path).exists()) {
            clear();
            save();
        }
    }

    private SharedPreferences getPrefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("fork_updater", Activity.MODE_PRIVATE);
    }

    private void save() {
        getPrefs().edit()
            .putString("version", version)
            .putInt("build", build)
            .putString("changelog", changelog)
            .putString("fileUrl", fileUrl)
            .putString("path", path)
            .putLong("lastCheck", lastCheck)
            .apply();
    }

    private void clear() {
        deleteDownloaded();
        version = null;
        build = 0;
        changelog = null;
        fileUrl = null;
    }

    private void deleteDownloaded() {
        if (!TextUtils.isEmpty(path)) {
            try {
                new File(path).delete();
            } catch (Exception e) {
                FileLog.e(e);
            }
        }
        path = null;
    }

    public void checkForUpdate(boolean force, Runnable whenDone) {
        if (checking || !force && System.currentTimeMillis() - lastCheck < CHECK_INTERVAL) {
            if (whenDone != null) {
                whenDone.run();
            }
            return;
        }
        checking = true;
        new HttpGetTask(str -> AndroidUtilities.runOnUIThread(() -> {
            checking = false;
            final int oldBuild = build;
            try {
                final JSONObject json = new JSONObject(str);
                final int newBuild = json.getInt("build");
                if (newBuild <= ForkConfig.FORK_BUILD) {
                    clear();
                } else {
                    if (newBuild != build) {
                        deleteDownloaded();
                    }
                    version = json.optString("version", BuildVars.BUILD_VERSION_STRING);
                    build = newBuild;
                    fileUrl = json.getString("file_url");
                    changelog = json.optString("changelog", null);
                }
                lastCheck = System.currentTimeMillis();
                save();
            } catch (Exception e) {
                FileLog.e("fork update check failed: " + str, e);
            }
            if (build != oldBuild) {
                NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.appUpdateAvailable);
            }
            if (whenDone != null) {
                whenDone.run();
            } else if (build != oldBuild && build != 0 && !ApplicationLoader.mainInterfacePaused) {
                final Context context = LaunchActivity.instance;
                if (context != null) {
                    ApplicationLoader.applicationLoaderInstance.showCustomUpdateAppPopup(context, getUpdate(), UserConfig.selectedAccount);
                }
            }
        })).execute(ForkConfig.UPDATE_URL);
    }

    public BetaUpdate getUpdate() {
        if (build <= ForkConfig.FORK_BUILD) {
            return null;
        }
        return new BetaUpdate(version, build, changelog);
    }

    public void downloadUpdate() {
        if (downloading || getDownloadedFile() != null) return;
        if (TextUtils.isEmpty(fileUrl)) {
            checkForUpdate(true, () -> {
                if (!TextUtils.isEmpty(fileUrl)) downloadUpdate();
            });
            return;
        }
        downloading = true;
        downloadingProgress = 0.0f;
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.appUpdateLoading);
        downloadingTask = new HttpGetFileTask(
            file -> AndroidUtilities.runOnUIThread(() -> {
                downloading = false;
                downloadingTask = null;
                if (file != null) {
                    deleteDownloaded();
                    path = file.getAbsolutePath();
                    downloadingProgress = 1.0f;
                    save();
                }
                NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.appUpdateAvailable);
            }),
            progress -> {
                downloadingProgress = progress;
                NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.appUpdateLoading);
            }
        ).setOverrideExtension("apk");
        downloadingTask.execute(fileUrl);
    }

    public void cancelDownloadingUpdate() {
        if (!downloading) return;
        if (downloadingTask != null) {
            downloadingTask.cancel(false);
            downloadingTask = null;
        }
        downloading = false;
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.appUpdateAvailable);
    }

    public boolean isDownloading() {
        return downloading;
    }

    public float getDownloadingProgress() {
        return downloadingProgress;
    }

    public File getDownloadedFile() {
        if (path == null) return null;
        final File file = new File(path);
        if (!file.exists()) {
            path = null;
            save();
            return null;
        }
        return file;
    }
}
