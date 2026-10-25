package com.byd.dashcast.proxy;

import com.byd.dashcast.BuildConfig;

/**
 * Every name this build shares with the rest of the head unit, in one place.
 *
 * <p>The Java namespace stays {@code com.byd.dashcast}, but the package id, the daemon process and
 * ServiceManager names, the {@code /data/local/tmp} files and the OTA source are global on the car.
 * Deriving them from {@code BuildConfig} lets this build run next to upstream DashCast without the
 * two apps adopting, killing or updating each other's daemons. Lives under {@code proxy.**} so the
 * R8 keep rule covers it for the uid-2000 daemons, which read it too.
 */
public final class AppIdentity {

    private AppIdentity() {}

    /** The installed package id (not the Java namespace). */
    public static final String PACKAGE = BuildConfig.APPLICATION_ID;

    /** Short prefix for device-global files and process names, e.g. {@code bydlakdash}. */
    public static final String DEVICE_TAG = BuildConfig.DEVICE_TAG;

    /** Proxy daemon {@code --nice-name} / argv0. */
    public static final String PROXY_PROCESS = DEVICE_TAG + "_proxy";

    /** Mirror daemon {@code --nice-name} at spawn, before it renames itself. */
    public static final String MIRROR_SPAWN_NAME = DEVICE_TAG + ".mirror.daemon";

    /** Mirror daemon argv0 after {@code setArgV0}. */
    public static final String MIRROR_PROCESS = PACKAGE + ".mirrordaemon";

    /** ServiceManager names of the two uid-2000 daemons. */
    public static final String PROXY_SERVICE = DEVICE_TAG + "_proxy_daemon";
    public static final String MIRROR_SERVICE = DEVICE_TAG + "_mirror_daemon";

    /** Signature permission guarding the mirror daemon's binder broadcast (see the manifest). */
    public static final String PERMISSION_DAEMON_READY = PACKAGE + ".permission.DAEMON_READY";

    /** GitHub {@code owner/repo} whose releases feed the OTA updater. */
    public static final String UPDATE_REPO = BuildConfig.UPDATE_REPO;

    /** Release asset name prefix, matching the Gradle {@code outputFileName}. */
    public static final String APK_BASE_NAME = BuildConfig.APK_BASE_NAME;

    /** A file under {@code /data/local/tmp} owned by this build, e.g. {@code tmp("_proxy.pid")}. */
    public static String tmp(String suffix) {
        return "/data/local/tmp/" + DEVICE_TAG + suffix;
    }

    /** {@code grep} pattern for a process name that does not match the grep itself ({@code [x]yz}). */
    public static String selfExcludingGrep(String processName) {
        return "[" + processName.charAt(0) + "]" + processName.substring(1).replace(".", "[.]");
    }
}
