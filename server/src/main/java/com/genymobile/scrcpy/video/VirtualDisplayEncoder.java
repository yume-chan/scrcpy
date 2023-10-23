package com.genymobile.scrcpy.video;

import com.genymobile.scrcpy.CodecOption;
import com.genymobile.scrcpy.ConfigurationException;
import com.genymobile.scrcpy.Device;
import com.genymobile.scrcpy.DeviceMessageSender;
import com.genymobile.scrcpy.Ln;
import com.genymobile.scrcpy.Settings;
import com.genymobile.scrcpy.SettingsException;
import com.genymobile.scrcpy.Size;
import com.genymobile.scrcpy.Streamer;
import com.genymobile.scrcpy.Workarounds;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.ActivityTaskManager;
import android.app.IActivityTaskManager;
import android.app.WindowConfiguration;
import android.content.res.Configuration;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.os.Build;
import android.os.RemoteException;
import android.view.Surface;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Objects;

public class VirtualDisplayEncoder extends SurfaceEncoder {
    private static final int VIRTUAL_DISPLAY_FLAG_DESTROY_CONTENT_ON_REMOVAL = 1 << 8;
    private static final int VIRTUAL_DISPLAY_FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS = 1 << 9;
    private static final int VIRTUAL_DISPLAY_FLAG_TRUSTED = 1 << 10;
    private static final int VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP = 1 << 11;
    private static final int VIRTUAL_DISPLAY_FLAG_ALWAYS_UNLOCKED = 1 << 12;

    private final DisplayManager displayManager;
    private final IActivityTaskManager activityTaskManager;

    private final Device device;
    private final DeviceMessageSender sender;
    private final DisplaySize requestedSize;
    private final TaskStackListener taskStackListener = new TaskStackListener();
    private VirtualDisplay virtualDisplay;
    private Size actualSize;

    @SuppressLint("WrongConstant")
    public VirtualDisplayEncoder(Device device, DeviceMessageSender sender, DisplaySize size, int maxSize, Streamer streamer, int videoBitRate, int maxFps, List<CodecOption> codecOptions, String encoderName, boolean downsizeOnError) {
        super(streamer, videoBitRate, maxFps, codecOptions, encoderName, downsizeOnError);

        displayManager = Workarounds.getDisplayManager();
        activityTaskManager = ActivityTaskManager.getService();

        this.device = device;
        this.sender = sender;
        requestedSize = size;
        setSize(maxSize);
    }

    @Override
    protected void initialize() throws ConfigurationException {
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
    protected Size getSize() {
        return actualSize;
    }

    @Override
    protected void setSize(int size) {
        if (size != 0 && (requestedSize.getWidth() > size || requestedSize.getHeight() > size)) {
            float scale = Math.min((float) size / requestedSize.getWidth(), (float) size / requestedSize.getHeight());
            actualSize = new Size((int) (requestedSize.getWidth() * scale), (int) (requestedSize.getHeight() * scale));
        } else {
            actualSize = new Size(requestedSize.getWidth(), requestedSize.getHeight());
        }
    }

    @SuppressLint("WrongConstant")
    @Override
    protected void setSurface(Surface surface) {
        int flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC | DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY | VIRTUAL_DISPLAY_FLAG_DESTROY_CONTENT_ON_REMOVAL | DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            flags |= VIRTUAL_DISPLAY_FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS | VIRTUAL_DISPLAY_FLAG_TRUSTED | VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP | VIRTUAL_DISPLAY_FLAG_ALWAYS_UNLOCKED;
        }

        virtualDisplay = displayManager.createVirtualDisplay("scrcpy", actualSize.getWidth(), actualSize.getHeight(), requestedSize.getDensity(), surface, flags);
        int displayId = virtualDisplay.getDisplay().getDisplayId();
        Ln.e("Virtual Display ID: " + displayId);

        try {
            device.setDisplayId(displayId);
        } catch (ConfigurationException e) {
            throw new RuntimeException("Can't set display ID", e);
        }

        if (sender != null) {
            Ln.d("Start task stack listener");
            try {
                taskStackListener.onTaskStackChanged();
                activityTaskManager.registerTaskStackListener(taskStackListener);
            } catch (RemoteException e) {
                throw new RuntimeException(e);
            }
        }
    }

    @Override
    protected void dispose() {
        if (virtualDisplay != null) {
            virtualDisplay.release();
        }
        try {
            activityTaskManager.unregisterTaskStackListener(taskStackListener);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    class TaskStackListener extends android.app.TaskStackListener {
        @Override
        @TargetApi(Build.VERSION_CODES.R)
        public void onTaskStackChanged() {
            try {
                if (Build.VERSION.SDK_INT == Build.VERSION_CODES.R) {
                    IActivityTaskManager manager = ActivityTaskManager.getService();
                    Method getAllStackInfosOnDisplayMethod = manager.getClass().getDeclaredMethod("getAllStackInfosOnDisplay", int.class);
                    List<?> stackInfoList = (List<?>) Objects.requireNonNull(getAllStackInfosOnDisplayMethod.invoke(manager, virtualDisplay.getDisplay().getDisplayId()));
                    for (Object stackInfo : stackInfoList) {
                        Field configurationField = stackInfo.getClass().getDeclaredField("configuration");
                        Configuration configuration = (Configuration) Objects.requireNonNull(configurationField.get(stackInfo));
                        int activityType = configuration.windowConfiguration.getActivityType();

                        Field visibleField = stackInfo.getClass().getDeclaredField("visible");
                        boolean visible = visibleField.getBoolean(stackInfo);

                        if (activityType == WindowConfiguration.ACTIVITY_TYPE_STANDARD && visible) {
                            return;
                        }
                    }
                    Ln.d("push virtual display empty");
                    return;
                }

                List<ActivityTaskManager.RootTaskInfo> rootTaskInfoList = ActivityTaskManager.getService().getAllRootTaskInfosOnDisplay(virtualDisplay.getDisplay().getDisplayId());
                Ln.v("root task count: " + rootTaskInfoList.size());
                for (ActivityTaskManager.RootTaskInfo rootTaskInfo : rootTaskInfoList) {
                    Ln.v("root task " + (rootTaskInfo.topActivity != null ? rootTaskInfo.topActivity.getPackageName() : "Unknown") + ", visible=" + rootTaskInfo.visible + ", type=" + rootTaskInfo.getActivityType());
                    if (rootTaskInfo.getActivityType() == WindowConfiguration.ACTIVITY_TYPE_STANDARD && rootTaskInfo.visible) {
                        Ln.d("found visible root task");
                        return;
                    }
                }
                Ln.d("push virtual display empty");
//                    sender.pushVirtualDisplayEmpty();
            } catch (Throwable e) {
                Ln.e("error", e);
            }
        }
    }
}
