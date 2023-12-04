package com.genymobile.scrcpy;

import android.annotation.SuppressLint;
import android.content.Context;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.os.Build;
import android.view.Surface;

public class VirtualDisplayCapture extends SurfaceCapture {
    private static final int VIRTUAL_DISPLAY_FLAG_DESTROY_CONTENT_ON_REMOVAL = 1 << 8;
    private static final int VIRTUAL_DISPLAY_FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS = 1 << 9;
    private static final int VIRTUAL_DISPLAY_FLAG_TRUSTED = 1 << 10;
    private static final int VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP = 1 << 11;
    private static final int VIRTUAL_DISPLAY_FLAG_ALWAYS_UNLOCKED = 1 << 12;

    private final DisplayManager displayManager;

    private final Device device;
    private final DisplaySize requestedSize;
    private VirtualDisplay virtualDisplay;
    private Size actualSize;

    @SuppressLint("WrongConstant")
    public VirtualDisplayCapture(Device device, DisplaySize size, int maxSize) {
        try {
            displayManager = DisplayManager.class.getDeclaredConstructor(Context.class)
                    .newInstance(FakeContext.get());
        } catch (Exception e) {
            // Shouldn't happen
            throw new RuntimeException(e);
        }

        this.device = device;
        requestedSize = size;
        setMaxSize(maxSize);
    }

    @Override
    public void init() throws ConfigurationException {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Ln.e("Virtual display is not supported before Android 11");
            throw new ConfigurationException("Virtual display is not supported before Android 11");
        }

        try {
            Settings.putValue(Settings.TABLE_GLOBAL, "force_desktop_mode_on_external_displays", "1");
        } catch (SettingsException e) {
            Ln.w("Failed to set desktop mode on virtual display, some apps won't work correctly", e);
        }
    }

    @Override
    public Size getSize() {
        return actualSize;
    }

    @Override
    public boolean setMaxSize(int maxSize) {
        if (maxSize != 0 && (requestedSize.getWidth() > maxSize || requestedSize.getHeight() > maxSize)) {
            float scale = Math.min((float) maxSize / requestedSize.getWidth(), (float) maxSize / requestedSize.getHeight());
            actualSize = new Size((int) (requestedSize.getWidth() * scale), (int) (requestedSize.getHeight() * scale));
        } else {
            actualSize = new Size(requestedSize.getWidth(), requestedSize.getHeight());
        }
        return true;
    }

    @SuppressLint("WrongConstant")
    @Override
    public void start(Surface surface) {
        int flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC | DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY | VIRTUAL_DISPLAY_FLAG_DESTROY_CONTENT_ON_REMOVAL | DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            flags |= VIRTUAL_DISPLAY_FLAG_TRUSTED | VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP | VIRTUAL_DISPLAY_FLAG_ALWAYS_UNLOCKED;
        }

        virtualDisplay = displayManager.createVirtualDisplay("scrcpy", actualSize.getWidth(), actualSize.getHeight(), requestedSize.getDensity(), surface, flags);
        int displayId = virtualDisplay.getDisplay().getDisplayId();
        Ln.e("Virtual Display ID: " + displayId);

        try {
            device.setDisplayId(displayId);
        } catch (ConfigurationException e) {
            throw new RuntimeException("Can't set display ID", e);
        }
    }

    @Override
    public void release() {
        if (virtualDisplay != null) {
            virtualDisplay.release();
        }
    }
}
