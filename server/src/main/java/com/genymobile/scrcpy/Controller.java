package com.genymobile.scrcpy;

import com.genymobile.scrcpy.wrappers.InputManager;
import com.genymobile.scrcpy.wrappers.ServiceManager;

import android.os.Build;
import android.os.SystemClock;
import android.util.SparseArray;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;

import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class Controller {

    private static final int DEFAULT_DEVICE_ID = 0;

    // control_msg.h values of the pointerId field in inject_touch_event message
    private static final int POINTER_ID_MOUSE = -1;
    private static final int POINTER_ID_VIRTUAL_MOUSE = -3;

    private static final ScheduledExecutorService EXECUTOR = Executors.newSingleThreadScheduledExecutor();

    private Thread thread;

    private final Device device;
    private final DesktopConnection connection;
    private final DeviceMessageSender sender;
    private final boolean clipboardAutosync;
    private final boolean powerOn;

    private final KeyCharacterMap charMap = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD);

    private long lastTouchDown;
    private final PointersState pointersState = new PointersState();
    private final MotionEvent.PointerProperties[] pointerProperties = new MotionEvent.PointerProperties[PointersState.MAX_POINTERS];
    private final MotionEvent.PointerCoords[] pointerCoords = new MotionEvent.PointerCoords[PointersState.MAX_POINTERS];

    private boolean keepPowerModeOff;

    private SparseArray<GameController> gameControllers = new SparseArray<GameController>();
    private boolean gameControllersEnabled;

    private UinputKeyboard keyboard;

    public Controller(Device device, DesktopConnection connection, boolean clipboardAutosync, boolean powerOn) {
        this.device = device;
        this.connection = connection;
        this.clipboardAutosync = clipboardAutosync;
        this.powerOn = powerOn;
        initPointers();
        sender = new DeviceMessageSender(connection);

        try {
            UinputDevice.loadNativeLibraries();
            gameControllersEnabled = true;
        } catch (UnsatisfiedLinkError e) {
            Ln.e("Could not load native libraries. Game controllers will be disabled.", e);
            gameControllersEnabled = false;
        }

        // The first keyboard will be rejected due to device is not being zero
        new UinputKeyboard();
        // The second one will be classified as physical keyboard
        keyboard = new UinputKeyboard();
    }

    private void initPointers() {
        for (int i = 0; i < PointersState.MAX_POINTERS; ++i) {
            MotionEvent.PointerProperties props = new MotionEvent.PointerProperties();
            props.toolType = MotionEvent.TOOL_TYPE_FINGER;

            MotionEvent.PointerCoords coords = new MotionEvent.PointerCoords();
            coords.orientation = 0;
            coords.size = 0;

            pointerProperties[i] = props;
            pointerCoords[i] = coords;
        }
    }

    private void control() throws IOException {
        // on start, power on the device
        if (powerOn && !Device.isScreenOn()) {
            device.pressReleaseKeycode(KeyEvent.KEYCODE_POWER, Device.INJECT_MODE_ASYNC);

            // dirty hack
            // After POWER is injected, the device is powered on asynchronously.
            // To turn the device screen off while mirroring, the client will send a message that
            // would be handled before the device is actually powered on, so its effect would
            // be "canceled" once the device is turned back on.
            // Adding this delay prevents to handle the message before the device is actually
            // powered on.
            SystemClock.sleep(500);
        }

        while (!Thread.currentThread().isInterrupted()) {
            handleEvent();
        }
    }

    public void start() {
        thread = new Thread(() -> {
            try {
                control();
            } catch (IOException e) {
                // this is expected on close
                Ln.d("Controller stopped");
            }
        });
        thread.start();
        sender.start();
    }

    public void stop() {
        if (thread != null) {
            thread.interrupt();
            thread = null;
        }
        sender.stop();
    }

    public DeviceMessageSender getSender() {
        return sender;
    }

    private void handleEvent() throws IOException {
        ControlMessage msg = connection.receiveControlMessage();
        switch (msg.getType()) {
            case ControlMessage.TYPE_INJECT_KEYCODE:
                if (device.supportsInputEvents()) {
                    injectKeycode(msg.getAction(), msg.getKeycode(), msg.getRepeat(), msg.getMetaState());
                }
                break;
            case ControlMessage.TYPE_INJECT_TEXT:
                if (device.supportsInputEvents()) {
                    injectText(msg.getText());
                }
                break;
            case ControlMessage.TYPE_INJECT_TOUCH_EVENT:
                if (device.supportsInputEvents()) {
                    injectTouch(msg.getAction(), msg.getPointerId(), msg.getPosition(), msg.getPressure(), msg.getActionButton(), msg.getButtons());
                }
                break;
            case ControlMessage.TYPE_INJECT_SCROLL_EVENT:
                if (device.supportsInputEvents()) {
                    injectScroll(msg.getPosition(), msg.getHScroll(), msg.getVScroll(), msg.getButtons());
                }
                break;
            case ControlMessage.TYPE_BACK_OR_SCREEN_ON:
                if (device.supportsInputEvents()) {
                    pressBackOrTurnScreenOn(msg.getAction());
                }
                break;
            case ControlMessage.TYPE_EXPAND_NOTIFICATION_PANEL:
                Device.expandNotificationPanel();
                break;
            case ControlMessage.TYPE_EXPAND_SETTINGS_PANEL:
                Device.expandSettingsPanel();
                break;
            case ControlMessage.TYPE_COLLAPSE_PANELS:
                Device.collapsePanels();
                break;
            case ControlMessage.TYPE_GET_CLIPBOARD:
                getClipboard(msg.getCopyKey());
                break;
            case ControlMessage.TYPE_SET_CLIPBOARD:
                setClipboard(msg.getText(), msg.getPaste(), msg.getSequence());
                break;
            case ControlMessage.TYPE_SET_SCREEN_POWER_MODE:
                if (device.supportsInputEvents()) {
                    int mode = msg.getAction();
                    boolean setPowerModeOk = Device.setScreenPowerMode(mode);
                    if (setPowerModeOk) {
                        keepPowerModeOff = mode == Device.POWER_MODE_OFF;
                        Ln.i("Device screen turned " + (mode == Device.POWER_MODE_OFF ? "off" : "on"));
                    }
                }
                break;
            case ControlMessage.TYPE_ROTATE_DEVICE:
                Device.rotateDevice();
                break;
            case ControlMessage.TYPE_INJECT_GAME_CONTROLLER_AXIS:
                if (gameControllersEnabled) {
                    int id = msg.getGameControllerId();
                    int axis = msg.getGameControllerAxis();
                    int value = msg.getGameControllerAxisValue();

                    GameController controller = gameControllers.get(id);

                    if (controller != null) {
                        controller.setAxis(axis, value);
                    } else {
                        Ln.w("Received data for non-existant controller.");
                    }
                    break;
                }
                break;
            case ControlMessage.TYPE_INJECT_GAME_CONTROLLER_BUTTON:
                if (gameControllersEnabled) {
                    int id = msg.getGameControllerId();
                    int button = msg.getGameControllerButton();
                    int state = msg.getGameControllerButtonState();

                    GameController controller = gameControllers.get(id);

                    if (controller != null) {
                        controller.setButton(button, state);
                    } else {
                        Ln.w("Received data for non-existant controller.");
                    }
                }
                break;
            case ControlMessage.TYPE_INJECT_GAME_CONTROLLER_DEVICE:
                if (gameControllersEnabled) {
                    int id = msg.getGameControllerId();
                    int event = msg.getGameControllerDeviceEvent();

                    switch (event) {
                        case GameController.DEVICE_ADDED:
                            try {
                                gameControllers.append(id, new GameController());
                            } catch (Exception e) {
                                Ln.e("Failed to add new game controller. Game controllers will be disabled.", e);
                                gameControllersEnabled = false;
                            }
                            break;

                        case GameController.DEVICE_REMOVED:
                            GameController controller = gameControllers.get(id);

                            if (controller != null) {
                                controller.close();
                                gameControllers.delete(id);
                            } else {
                                Ln.w("Non-existant game controller removed.");
                            }

                            break;

                        default:
                            Ln.w("Unknown game controller event received.");
                    }
                }
                break;
            default:
                // do nothing
        }
    }

    private boolean injectKeycode(int action, int keycode, int repeat, int metaState) {
        if (keepPowerModeOff && action == KeyEvent.ACTION_UP && (keycode == KeyEvent.KEYCODE_POWER || keycode == KeyEvent.KEYCODE_WAKEUP)) {
            schedulePowerModeOff();
        }
        return device.injectKeyEvent(action, keycode, repeat, metaState, Device.INJECT_MODE_ASYNC);
    }

    private int injectText(String text) {
        keyboard.type(text);
        return text.length();
    }

    private boolean injectTouch(int action, long pointerId, Position position, float pressure, int actionButton, int buttons) {
        long now = SystemClock.uptimeMillis();

        Point point = device.getPhysicalPoint(position);
        if (point == null) {
            Ln.w("Ignore touch event, it was generated for a different device size");
            return false;
        }

        int pointerIndex = pointersState.getPointerIndex(pointerId);
        if (pointerIndex == -1) {
            Ln.w("Too many pointers for touch event");
            return false;
        }
        Pointer pointer = pointersState.get(pointerIndex);
        pointer.setPoint(point);
        pointer.setPressure(pressure);

        int source;
        if (pointerId == POINTER_ID_MOUSE || pointerId == POINTER_ID_VIRTUAL_MOUSE) {
            // real mouse event (forced by the client when --forward-on-click)
            pointerProperties[pointerIndex].toolType = MotionEvent.TOOL_TYPE_MOUSE;
            source = InputDevice.SOURCE_MOUSE;
            pointer.setUp(buttons == 0);
        } else {
            // POINTER_ID_GENERIC_FINGER, POINTER_ID_VIRTUAL_FINGER or real touch from device
            pointerProperties[pointerIndex].toolType = MotionEvent.TOOL_TYPE_FINGER;
            source = InputDevice.SOURCE_TOUCHSCREEN;
            // Buttons must not be set for touch events
            buttons = 0;
            pointer.setUp(action == MotionEvent.ACTION_UP);
        }

        int pointerCount = pointersState.update(pointerProperties, pointerCoords);
        if (pointerCount == 1) {
            if (action == MotionEvent.ACTION_DOWN) {
                lastTouchDown = now;
            }
        } else {
            // secondary pointers must use ACTION_POINTER_* ORed with the pointerIndex
            if (action == MotionEvent.ACTION_UP) {
                action = MotionEvent.ACTION_POINTER_UP | (pointerIndex << MotionEvent.ACTION_POINTER_INDEX_SHIFT);
            } else if (action == MotionEvent.ACTION_DOWN) {
                action = MotionEvent.ACTION_POINTER_DOWN | (pointerIndex << MotionEvent.ACTION_POINTER_INDEX_SHIFT);
            }
        }

        /* If the input device is a mouse (on API >= 23):
         *   - the first button pressed must first generate ACTION_DOWN;
         *   - all button pressed (including the first one) must generate ACTION_BUTTON_PRESS;
         *   - all button released (including the last one) must generate ACTION_BUTTON_RELEASE;
         *   - the last button released must in addition generate ACTION_UP.
         *
         * Otherwise, Chrome does not work properly: <https://github.com/Genymobile/scrcpy/issues/3635>
         */
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && source == InputDevice.SOURCE_MOUSE) {
            if (action == MotionEvent.ACTION_DOWN) {
                if (actionButton == buttons) {
                    // First button pressed: ACTION_DOWN
                    MotionEvent downEvent = MotionEvent.obtain(lastTouchDown, now, MotionEvent.ACTION_DOWN, pointerCount, pointerProperties,
                            pointerCoords, 0, buttons, 1f, 1f, DEFAULT_DEVICE_ID, 0, source, 0);
                    if (!device.injectEvent(downEvent, Device.INJECT_MODE_ASYNC)) {
                        return false;
                    }
                }

                // Any button pressed: ACTION_BUTTON_PRESS
                MotionEvent pressEvent = MotionEvent.obtain(lastTouchDown, now, MotionEvent.ACTION_BUTTON_PRESS, pointerCount, pointerProperties,
                        pointerCoords, 0, buttons, 1f, 1f, DEFAULT_DEVICE_ID, 0, source, 0);
                if (!InputManager.setActionButton(pressEvent, actionButton)) {
                    return false;
                }
                if (!device.injectEvent(pressEvent, Device.INJECT_MODE_ASYNC)) {
                    return false;
                }

                return true;
            }

            if (action == MotionEvent.ACTION_UP) {
                // Any button released: ACTION_BUTTON_RELEASE
                MotionEvent releaseEvent = MotionEvent.obtain(lastTouchDown, now, MotionEvent.ACTION_BUTTON_RELEASE, pointerCount, pointerProperties,
                        pointerCoords, 0, buttons, 1f, 1f, DEFAULT_DEVICE_ID, 0, source, 0);
                if (!InputManager.setActionButton(releaseEvent, actionButton)) {
                    return false;
                }
                if (!device.injectEvent(releaseEvent, Device.INJECT_MODE_ASYNC)) {
                    return false;
                }

                if (buttons == 0) {
                    // Last button released: ACTION_UP
                    MotionEvent upEvent = MotionEvent.obtain(lastTouchDown, now, MotionEvent.ACTION_UP, pointerCount, pointerProperties,
                            pointerCoords, 0, buttons, 1f, 1f, DEFAULT_DEVICE_ID, 0, source, 0);
                    if (!device.injectEvent(upEvent, Device.INJECT_MODE_ASYNC)) {
                        return false;
                    }
                }

                return true;
            }
        }

        MotionEvent event = MotionEvent
                .obtain(lastTouchDown, now, action, pointerCount, pointerProperties, pointerCoords, 0, buttons, 1f, 1f, DEFAULT_DEVICE_ID, 0, source,
                        0);
        return device.injectEvent(event, Device.INJECT_MODE_ASYNC);
    }

    private boolean injectScroll(Position position, float hScroll, float vScroll, int buttons) {
        long now = SystemClock.uptimeMillis();
        Point point = device.getPhysicalPoint(position);
        if (point == null) {
            // ignore event
            return false;
        }

        MotionEvent.PointerProperties props = pointerProperties[0];
        props.id = 0;

        MotionEvent.PointerCoords coords = pointerCoords[0];
        coords.x = point.getX();
        coords.y = point.getY();
        coords.setAxisValue(MotionEvent.AXIS_HSCROLL, hScroll);
        coords.setAxisValue(MotionEvent.AXIS_VSCROLL, vScroll);

        MotionEvent event = MotionEvent
                .obtain(lastTouchDown, now, MotionEvent.ACTION_SCROLL, 1, pointerProperties, pointerCoords, 0, buttons, 1f, 1f, DEFAULT_DEVICE_ID, 0,
                        InputDevice.SOURCE_MOUSE, 0);
        return device.injectEvent(event, Device.INJECT_MODE_ASYNC);
    }

    /**
     * Schedule a call to set power mode to off after a small delay.
     */
    private static void schedulePowerModeOff() {
        EXECUTOR.schedule(() -> {
            Ln.i("Forcing screen off");
            Device.setScreenPowerMode(Device.POWER_MODE_OFF);
        }, 200, TimeUnit.MILLISECONDS);
    }

    private boolean pressBackOrTurnScreenOn(int action) {
        if (Device.isScreenOn()) {
            return device.injectKeyEvent(action, KeyEvent.KEYCODE_BACK, 0, 0, Device.INJECT_MODE_ASYNC);
        }

        // Screen is off
        // Only press POWER on ACTION_DOWN
        if (action != KeyEvent.ACTION_DOWN) {
            // do nothing,
            return true;
        }

        if (keepPowerModeOff) {
            schedulePowerModeOff();
        }
        return device.pressReleaseKeycode(KeyEvent.KEYCODE_POWER, Device.INJECT_MODE_ASYNC);
    }

    private void getClipboard(int copyKey) {
        // On Android >= 7, press the COPY or CUT key if requested
        if (copyKey != ControlMessage.COPY_KEY_NONE && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && device.supportsInputEvents()) {
            int key = copyKey == ControlMessage.COPY_KEY_COPY ? KeyEvent.KEYCODE_COPY : KeyEvent.KEYCODE_CUT;
            // Wait until the event is finished, to ensure that the clipboard text we read just after is the correct one
            device.pressReleaseKeycode(key, Device.INJECT_MODE_WAIT_FOR_FINISH);
        }

        // If clipboard autosync is enabled, then the device clipboard is synchronized to the computer clipboard whenever it changes, in
        // particular when COPY or CUT are injected, so it should not be synchronized twice. On Android < 7, do not synchronize at all rather than
        // copying an old clipboard content.
        if (!clipboardAutosync) {
            String clipboardText = Device.getClipboardText();
            if (clipboardText != null) {
                sender.pushClipboardText(clipboardText);
            }
        }
    }

    private boolean setClipboard(String text, boolean paste, long sequence) {
        boolean ok = device.setClipboardText(text);
        if (ok) {
            Ln.i("Device clipboard set");
        }

        // On Android >= 7, also press the PASTE key if requested
        if (paste && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && device.supportsInputEvents()) {
            device.pressReleaseKeycode(KeyEvent.KEYCODE_PASTE, Device.INJECT_MODE_ASYNC);
        }

        if (sequence != ControlMessage.SEQUENCE_INVALID) {
            // Acknowledgement requested
            sender.pushAckClipboard(sequence);
        }

        return ok;
    }
}
