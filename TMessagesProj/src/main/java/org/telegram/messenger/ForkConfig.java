package org.telegram.messenger;

// Feature switches of the optimized fork. Everything is compile-time constant,
// so R8 strips the disabled code paths from release builds.
public final class ForkConfig {

    // Sponsored messages in channels/bots/videos, sponsored peers in search, proxy-sponsored channel
    public static final boolean DISABLE_ADS = true;

    // Don't ask for Firebase SMS at login. It needs Play Integrity/SafetyNet for the official
    // app's signature; for this APK the check hangs or fails and the phone screen gets stuck.
    public static final boolean NO_FIREBASE_SMS = true;

    // Fork build number, bump on every published APK. Compared with "build" in UPDATE_URL.
    public static final int FORK_BUILD = 12;
    // Manifest of the latest APK on the "apk" branch of the fork repo
    public static final String UPDATE_URL = "https://raw.githubusercontent.com/Callzik/Telegram/apk/releases/version.json";

    private ForkConfig() {
    }
}
