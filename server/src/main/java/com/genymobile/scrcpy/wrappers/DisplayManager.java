package com.genymobile.scrcpy.wrappers;

import com.genymobile.scrcpy.Command;
import com.genymobile.scrcpy.DisplayInfo;
import com.genymobile.scrcpy.Ln;
import com.genymobile.scrcpy.Size;

import android.view.Display;
import android.view.Surface;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.Executor;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import android.content.Context;
import android.content.ContextWrapper;
import android.media.projection.MediaProjection;
import android.os.Handler;

import java.lang.reflect.Method;

public final class DisplayManager {
    private final Object manager; // instance of hidden class android.hardware.display.DisplayManagerGlobal

    public DisplayManager(Object manager) {
        this.manager = manager;
    }

    // public to call it from unit tests
    public static DisplayInfo parseDisplayInfo(String dumpsysDisplayOutput, int displayId) {
        Pattern regex = Pattern.compile(
                "^    mOverrideDisplayInfo=DisplayInfo\\{\".*?, displayId " + displayId
                        + ".*?(, FLAG_.*)?, real ([0-9]+) x ([0-9]+).*?, "
                        + "rotation ([0-9]+).*?, layerStack ([0-9]+)",
                Pattern.MULTILINE);
        Matcher m = regex.matcher(dumpsysDisplayOutput);
        if (!m.find()) {
            return null;
        }
        int flags = parseDisplayFlags(m.group(1));
        int width = Integer.parseInt(m.group(2));
        int height = Integer.parseInt(m.group(3));
        int rotation = Integer.parseInt(m.group(4));
        int layerStack = Integer.parseInt(m.group(5));

        return new DisplayInfo(displayId, new Size(width, height), rotation, layerStack, flags);
    }

    private static DisplayInfo getDisplayInfoFromDumpsysDisplay(int displayId) {
        try {
            String dumpsysDisplayOutput = Command.execReadOutput("dumpsys", "display");
            return parseDisplayInfo(dumpsysDisplayOutput, displayId);
        } catch (Exception e) {
            Ln.e("Could not get display info from \"dumpsys display\" output", e);
            return null;
        }
    }

    private static int parseDisplayFlags(String text) {
        Pattern regex = Pattern.compile("FLAG_[A-Z_]+");
        if (text == null) {
            return 0;
        }

        int flags = 0;
        Matcher m = regex.matcher(text);
        while (m.find()) {
            String flagString = m.group();
            try {
                Field filed = Display.class.getDeclaredField(flagString);
                flags |= filed.getInt(null);
            } catch (NoSuchFieldException | IllegalAccessException e) {
                // Silently ignore, some flags reported by "dumpsys display" are @TestApi
            }
        }
        return flags;
    }

    public DisplayInfo getDisplayInfo(int displayId) {
        try {
            Object displayInfo = manager.getClass().getMethod("getDisplayInfo", int.class).invoke(manager, displayId);
            if (displayInfo == null) {
                // fallback when displayInfo is null
                return getDisplayInfoFromDumpsysDisplay(displayId);
            }
            Class<?> cls = displayInfo.getClass();
            // width and height already take the rotation into account
            int width = cls.getDeclaredField("logicalWidth").getInt(displayInfo);
            int height = cls.getDeclaredField("logicalHeight").getInt(displayInfo);
            int rotation = cls.getDeclaredField("rotation").getInt(displayInfo);
            int layerStack = cls.getDeclaredField("layerStack").getInt(displayInfo);
            int flags = cls.getDeclaredField("flags").getInt(displayInfo);
            return new DisplayInfo(displayId, new Size(width, height), rotation, layerStack, flags);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    public int[] getDisplayIds() {
        try {
            return (int[]) manager.getClass().getMethod("getDisplayIds").invoke(manager);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private Object createVirtualDisplayConfig(String name, int width, int height, int density, int flags,
            Surface surface, String uniqueId, int displayIdToMirror, boolean windowManagerMirroring)
            throws IllegalAccessException, IllegalArgumentException, InstantiationException, InvocationTargetException,
            NoSuchMethodException, SecurityException, ClassNotFoundException {
        Object builder = Class.forName("android.hardware.display.VirtualDisplayConfig$Builder")
                .getConstructor(String.class, int.class, int.class, int.class)
                .newInstance(name, width, height, density);
        builder.getClass().getMethod("setFlags", int.class).invoke(builder, flags);
        builder.getClass().getMethod("setSurface", Surface.class).invoke(builder, surface);
        return builder.getClass().getMethod("build").invoke(builder);
    }

    class FakePackageNameContext extends ContextWrapper {
        public FakePackageNameContext() {
            super(null);
        }

        @Override
        public String getPackageName() {
            // `Workarounds.getContext().getPackageName()` always returns `android`,
            // but `createVirtualDisplay` will validate the package name againest current
            // uid.
            // For ADB shell, the uid is 2000 (shell) and the only avaiable package name is
            // `com.android.shell`
            return "com.android.shell";
        }

        @Override
        public Display getDisplay() {
            return null;
        }
    }

    public Display createVirtualDisplay(Surface surface, int width, int height)
            throws NoSuchMethodException, ClassNotFoundException, SecurityException, IllegalAccessException,
            IllegalArgumentException, InstantiationException, InvocationTargetException, NoSuchFieldException {
        FakePackageNameContext wrapper = new FakePackageNameContext();
        Ln.i("Package name: " + wrapper.getPackageName());

        String name = "scrcpy-virtual";
        int density = 200;
        int VIRTUAL_DISPLAY_FLAG_PUBLIC = 1 << 0;
        int VIRTUAL_DISPLAY_FLAG_PRESENTATION = 1 << 1;
        int VIRTUAL_DISPLAY_FLAG_SECURE = 1 << 2;
        int VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY = 1 << 3;
        int VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR = 1 << 4;
        int VIRTUAL_DISPLAY_FLAG_CAN_SHOW_WITH_INSECURE_KEYGUARD = 1 << 5;
        int VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH = 1 << 6;
        int VIRTUAL_DISPLAY_FLAG_ROTATES_WITH_CONTENT = 1 << 7;
        int VIRTUAL_DISPLAY_FLAG_DESTROY_CONTENT_ON_REMOVAL = 1 << 8;
        int VIRTUAL_DISPLAY_FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS = 1 << 9;
        int VIRTUAL_DISPLAY_FLAG_TRUSTED = 1 << 10;
        int flags = VIRTUAL_DISPLAY_FLAG_PRESENTATION
                | VIRTUAL_DISPLAY_FLAG_CAN_SHOW_WITH_INSECURE_KEYGUARD
                | VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH
                | VIRTUAL_DISPLAY_FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS;

        try {
            // Android 10
            Method createVirtualDisplay = manager.getClass().getMethod("createVirtualDisplay",
                    Context.class,
                    MediaProjection.class,
                    String.class,
                    int.class,
                    int.class,
                    int.class,
                    Surface.class,
                    int.class,
                    Class.forName("android.hardware.display.VirtualDisplay$Callback"),
                    Handler.class,
                    String.class);
            Object virtualDisplay = createVirtualDisplay.invoke(manager, wrapper, null, name, width, height, density,
                    surface,
                    flags, null, null, null);
            Method getDisplay = Class.forName("android.hardware.display.VirtualDisplay").getMethod("getDisplay");
            Display display = (Display) getDisplay.invoke(virtualDisplay);
            return display;
        } catch (NoSuchMethodException e) {
        }

        Object config = createVirtualDisplayConfig(
                name,
                width,
                height,
                density,
                flags,
                surface,
                null,
                0,
                false);

        try {
            // Android 12
            Method createVirtualDisplay = manager.getClass().getMethod("createVirtualDisplay",
                    Context.class,
                    MediaProjection.class,
                    Class.forName("android.hardware.display.VirtualDisplayConfig"),
                    Class.forName("android.hardware.display.VirtualDisplay$Callback"),
                    Handler.class);
            Object virtualDisplay = createVirtualDisplay.invoke(manager, wrapper, null, config, null, null);
            Method getDisplay = Class.forName("android.hardware.display.VirtualDisplay").getMethod("getDisplay");
            Display display = (Display) getDisplay.invoke(virtualDisplay);
            return display;
        } catch (NoSuchMethodException e) {
        }

        // Android 13
        Method createVirtualDisplay = manager.getClass().getMethod("createVirtualDisplay",
                Context.class,
                MediaProjection.class,
                Class.forName("android.hardware.display.VirtualDisplayConfig"),
                Class.forName("android.hardware.display.VirtualDisplay$Callback"),
                Executor.class,
                Context.class);
        Object virtualDisplay = createVirtualDisplay.invoke(manager, wrapper, null, config, null, null, wrapper);
        Method getDisplay = Class.forName("android.hardware.display.VirtualDisplay").getMethod("getDisplay");
        Display display = (Display) getDisplay.invoke(virtualDisplay);
        return display;
    }
}
