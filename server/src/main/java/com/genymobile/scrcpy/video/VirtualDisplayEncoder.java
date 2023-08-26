package com.genymobile.scrcpy.video;

import com.genymobile.scrcpy.CodecOption;
import com.genymobile.scrcpy.ConfigurationException;
import com.genymobile.scrcpy.Device;
import com.genymobile.scrcpy.Ln;
import com.genymobile.scrcpy.Size;
import com.genymobile.scrcpy.Streamer;
import com.genymobile.scrcpy.Workarounds;

import android.annotation.SuppressLint;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.os.Build;
import android.view.Surface;

import java.util.List;

public class VirtualDisplayEncoder extends SurfaceEncoder {
    private static final int VIRTUAL_DISPLAY_FLAG_DESTROY_CONTENT_ON_REMOVAL = 1 << 8;
    private static final int VIRTUAL_DISPLAY_FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS = 1 << 9;
    private static final int VIRTUAL_DISPLAY_FLAG_TRUSTED = 1 << 10;

    private final DisplayManager displayManager;

    private final Device device;
    private final Size requestedSize;

    private VirtualDisplay virtualDisplay;
    private int maxSize;
    private Size actualSize;

    public VirtualDisplayEncoder(Device device, Size size, int maxSize, Streamer streamer, int videoBitRate, int maxFps, List<CodecOption> codecOptions, String encoderName, boolean downsizeOnError) {
        super(streamer, videoBitRate, maxFps, codecOptions, encoderName, downsizeOnError);

        displayManager = Workarounds.getDisplayManager();

        this.device = device;
        requestedSize = size;
        setSize(maxSize);
    }

    @Override
    protected void initialize() {
    }

    @Override
    protected Size getSize() {
        return actualSize;
    }

    @Override
    protected void setSize(int size) {
        maxSize = size;
        if (size != 0 && (requestedSize.getWidth() > size || requestedSize.getHeight() > size)) {
            float scale = Math.min((float) maxSize / requestedSize.getWidth(), (float) maxSize / requestedSize.getHeight());
            actualSize = new Size((int) (requestedSize.getWidth() * scale), (int) (requestedSize.getHeight() * scale));
        } else {
            actualSize = requestedSize;
        }
    }

    @SuppressLint("WrongConstant")
    @Override
    protected void setSurface(Surface surface) {
        int flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC |
                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY |
                VIRTUAL_DISPLAY_FLAG_DESTROY_CONTENT_ON_REMOVAL;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            flags |= VIRTUAL_DISPLAY_FLAG_TRUSTED;
        }
        virtualDisplay = displayManager.createVirtualDisplay("scrcpy", actualSize.getWidth(),
                actualSize.getHeight(), 200, surface, flags);
        int displayId = virtualDisplay.getDisplay().getDisplayId();
        Ln.v("Set display ID " + displayId);
        try {
            device.setDisplayId(displayId);
        } catch (ConfigurationException e) {
            throw new RuntimeException("Can't set display ID", e);
        }
    }

    @Override
    protected void dispose() {
        if (virtualDisplay != null) {
            virtualDisplay.release();
        }
    }
}
