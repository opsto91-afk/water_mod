package org.fmod;

import android.content.Context;

/**
 * Minimal FMOD compatibility shim so libfmod.so JNI_OnLoad / Java class lookups
 * succeed inside the headless background :mcre process without needing an Activity.
 */
public class FMOD {
    private static Context sContext;

    public static void init(Context context) {
        sContext = context != null ? context.getApplicationContext() : null;
    }

    public static void close() {
        sContext = null;
    }

    public static boolean checkInit() {
        return sContext != null;
    }

    public static Context getApplicationContext() {
        return sContext;
    }

    public static boolean supportsLowLatency() {
        return false;
    }

    public static int getOutputSampleRate() {
        return 48000;
    }

    public static int getOutputBlockSize() {
        return 512;
    }

    public static boolean isBluetoothOn() {
        return false;
    }
}
