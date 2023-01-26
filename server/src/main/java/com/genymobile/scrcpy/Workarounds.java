package com.genymobile.scrcpy;

import android.annotation.SuppressLint;
import android.app.Application;
import android.app.Instrumentation;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Looper;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

public final class Workarounds {
    private Workarounds() {
        // not instantiable
    }

    @SuppressWarnings("deprecation")
    public static void prepareMainLooper() {
        // Some devices internally create a Handler when creating an input Surface, causing an exception:
        //   "Can't create handler inside thread that has not called Looper.prepare()"
        // <https://github.com/Genymobile/scrcpy/issues/240>
        //
        // Use Looper.prepareMainLooper() instead of Looper.prepare() to avoid a NullPointerException:
        //   "Attempt to read from field 'android.os.MessageQueue android.os.Looper.mQueue'
        //    on a null object reference"
        // <https://github.com/Genymobile/scrcpy/issues/921>
        Looper.prepareMainLooper();
    }

    public static Object getActivityThread()
            throws IllegalAccessException, IllegalArgumentException, InstantiationException, InvocationTargetException,
            ClassNotFoundException, NoSuchMethodException, SecurityException {
        // ActivityThread activityThread = new ActivityThread();
        Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
        Constructor<?> activityThreadConstructor = activityThreadClass.getDeclaredConstructor();
        activityThreadConstructor.setAccessible(true);
        Object activityThread = activityThreadConstructor.newInstance();
        return activityThread;
    }

    public static Context getContext()
            throws ClassNotFoundException, NoSuchMethodException, SecurityException, IllegalAccessException,
            IllegalArgumentException, InstantiationException, InvocationTargetException, NoSuchFieldException {
        Object activityThread = getActivityThread();

        // ActivityThread.sCurrentActivityThread = activityThread;
        Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
        Field sCurrentActivityThreadField = activityThreadClass.getDeclaredField("sCurrentActivityThread");
        sCurrentActivityThreadField.setAccessible(true);
        sCurrentActivityThreadField.set(null, activityThread);

        // ActivityThread.AppBindData appBindData = new ActivityThread.AppBindData();
        Class<?> appBindDataClass = Class.forName("android.app.ActivityThread$AppBindData");
        Constructor<?> appBindDataConstructor = appBindDataClass.getDeclaredConstructor();
        appBindDataConstructor.setAccessible(true);
        Object appBindData = appBindDataConstructor.newInstance();

        ApplicationInfo applicationInfo = new ApplicationInfo();
        applicationInfo.packageName = "com.android.shell";

        // appBindData.appInfo = applicationInfo;
        Field appInfoField = appBindDataClass.getDeclaredField("appInfo");
        appInfoField.setAccessible(true);
        appInfoField.set(appBindData, applicationInfo);

        // activityThread.mBoundApplication = appBindData;
        Field mBoundApplicationField = activityThreadClass.getDeclaredField("mBoundApplication");
        mBoundApplicationField.setAccessible(true);
        mBoundApplicationField.set(activityThread, appBindData);

        // Context ctx = activityThread.getSystemContext();
        Method getSystemContextMethod = activityThreadClass.getDeclaredMethod("getSystemContext");
        Context ctx = (Context) getSystemContextMethod.invoke(activityThread);
        return ctx;
    }

    @SuppressLint("PrivateApi,DiscouragedPrivateApi")
    public static void fillAppInfo() {
        try {
            Context ctx = getContext();

            Application app = Instrumentation.newApplication(Application.class, ctx);

            // activityThread.mInitialApplication = app;
            Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
            Field mInitialApplicationField = activityThreadClass.getDeclaredField("mInitialApplication");
            mInitialApplicationField.setAccessible(true);
            Object activityThread = getActivityThread();
            mInitialApplicationField.set(activityThread, app);
        } catch (Throwable throwable) {
            // this is a workaround, so failing is not an error
            Ln.d("Could not fill app info: " + throwable.getMessage());
        }
    }
}
