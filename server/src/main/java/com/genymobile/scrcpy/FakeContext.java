package com.genymobile.scrcpy;

import com.genymobile.scrcpy.util.Ln;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Build;

import java.lang.reflect.Field;

public final class FakeContext {

    public static final String PACKAGE_NAME = "com.android.shell";
    public static final int ROOT_UID = 0; // Like android.os.Process.ROOT_UID, but before API 29

    @SuppressLint("StaticFieldLeak")
    private static Context INSTANCE;

    public static Context get() {
        if (INSTANCE == null) {
            try {
                INSTANCE = Workarounds.getSystemContext().createPackageContext(PACKAGE_NAME, 0);

                Field mOpPackageNameField = INSTANCE.getClass().getDeclaredField("mOpPackageName");
                mOpPackageNameField.setAccessible(true);
                mOpPackageNameField.set(INSTANCE, PACKAGE_NAME);

                INSTANCE = INSTANCE.createPackageContext(PACKAGE_NAME, 0);

                Ln.e("getPackageName: " + INSTANCE.getPackageName());
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    Ln.e("getOpPackageName: " + INSTANCE.getOpPackageName());
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    Ln.e("getAttributionSource: " + INSTANCE.getAttributionSource().getUid() + " " + INSTANCE.getAttributionSource().getPackageName());
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    Ln.e("getDeviceId: " + INSTANCE.getDeviceId());
                }
                Ln.e("getContentResolver: " + INSTANCE.getContentResolver());
            } catch (Throwable e) {
                throw new RuntimeException(e);
            }
        }

        return INSTANCE;
    }
}
