package org.telegram.messenger;

// Feature switches of the optimized fork. Everything is compile-time constant,
// so R8 strips the disabled code paths from release builds.
public final class ForkConfig {

    // Sponsored messages in channels/bots/videos, sponsored peers in search, proxy-sponsored channel
    public static final boolean DISABLE_ADS = true;

    private ForkConfig() {
    }
}
