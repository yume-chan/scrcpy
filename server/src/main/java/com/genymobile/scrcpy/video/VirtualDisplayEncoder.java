package com.genymobile.scrcpy.video;

import com.genymobile.scrcpy.CodecOption;
import com.genymobile.scrcpy.ConfigurationException;
import com.genymobile.scrcpy.Device;
import com.genymobile.scrcpy.DeviceMessageSender;
import com.genymobile.scrcpy.FakeContext;
import com.genymobile.scrcpy.Ln;
import com.genymobile.scrcpy.Size;
import com.genymobile.scrcpy.Streamer;
import com.genymobile.scrcpy.Workarounds;

import android.annotation.SuppressLint;
import android.app.ActivityTaskManager;
import android.app.WindowConfiguration;
import android.content.Context;
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
    private final ActivityTaskManager activityTaskManager;

    private final Device device;
    private final DeviceMessageSender sender;
    private final Size requestedSize;

    private VirtualDisplay virtualDisplay;
    private Size actualSize;
    private TaskStackListener taskStackListener = new TaskStackListener();

    @SuppressLint("WrongConstant")
    public VirtualDisplayEncoder(Device device, DeviceMessageSender sender, Size size, int maxSize, Streamer streamer, int videoBitRate, int maxFps, List<CodecOption> codecOptions, String encoderName, boolean downsizeOnError) {
        super(streamer, videoBitRate, maxFps, codecOptions, encoderName, downsizeOnError);

        displayManager = Workarounds.getDisplayManager();
        activityTaskManager = (ActivityTaskManager) FakeContext.get().getSystemService(Context.ACTIVITY_TASK_SERVICE);

        this.device = device;
        this.sender = sender;
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
        if (size != 0 && (requestedSize.getWidth() > size || requestedSize.getHeight() > size)) {
            float scale = Math.min((float) size / requestedSize.getWidth(), (float) size / requestedSize.getHeight());
            actualSize = new Size((int) (requestedSize.getWidth() * scale), (int) (requestedSize.getHeight() * scale));
        } else {
            actualSize = requestedSize;
        }
    }

    @SuppressLint("WrongConstant")
    @Override
    protected void setSurface(Surface surface) {
        int flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC | DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY | VIRTUAL_DISPLAY_FLAG_DESTROY_CONTENT_ON_REMOVAL;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            flags |= VIRTUAL_DISPLAY_FLAG_TRUSTED;
        }
        virtualDisplay = displayManager.createVirtualDisplay("scrcpy", actualSize.getWidth(), actualSize.getHeight(), 200, surface, flags);
        int displayId = virtualDisplay.getDisplay().getDisplayId();
        try {
            device.setDisplayId(displayId);
        } catch (ConfigurationException e) {
            throw new RuntimeException("Can't set display ID", e);
        }

        if (sender != null) {
            Ln.d("Start task stack listener");
            taskStackListener.onTaskStackChanged();
            activityTaskManager.registerTaskStackListener(taskStackListener);
        }
    }

    @Override
    protected void dispose() {
        if (virtualDisplay != null) {
            virtualDisplay.release();
        }
        activityTaskManager.unregisterTaskStackListener(taskStackListener);
    }

    class TaskStackListener extends android.app.TaskStackListener {
        @Override
        public void onTaskStackChanged() {
            try {
                List<ActivityTaskManager.RootTaskInfo> rootTaskInfoList = ActivityTaskManager.getService().getAllRootTaskInfosOnDisplay(virtualDisplay.getDisplay().getDisplayId());
                Ln.d("root task count: " + rootTaskInfoList.size());
                boolean hasVisibleRootTask = false;
                for (ActivityTaskManager.RootTaskInfo rootTaskInfo : rootTaskInfoList) {
                    Ln.d("root task " + (rootTaskInfo.topActivity != null ? rootTaskInfo.topActivity.getPackageName() : "Unknown") + ", visible=" + rootTaskInfo.visible + ", type=" + rootTaskInfo.getActivityType());
                    if (rootTaskInfo.getActivityType() == WindowConfiguration.ACTIVITY_TYPE_STANDARD && rootTaskInfo.visible) {
                        Ln.d("found visible root task");
                        hasVisibleRootTask = true;
                        break;
                    }
                }
                if (!hasVisibleRootTask) {
                    Ln.d("push virtual display empty");
//                    sender.pushVirtualDisplayEmpty();
                }
            } catch (Throwable e) {
                Ln.e("error", e);
            }
        }
    }
}
