package com.genymobile.scrcpy;

public class UinputKeyboard extends UinputDevice {

    public static final short KEY_LEFTSHIFT = 42;
    public static final short KEY_LEFTALT = 56;
    public static final short KEY_X = 45;
    public static final short KEY_0 = 11;
    public static final short KEY_1 = 2;
    public static final short KEY_2 = 3;
    public static final short KEY_3 = 4;
    public static final short KEY_4 = 5;
    public static final short KEY_5 = 6;
    public static final short KEY_6 = 7;
    public static final short KEY_7 = 8;
    public static final short KEY_8 = 9;
    public static final short KEY_9 = 10;
    public static final short KEY_A = 30;
    public static final short KEY_B = 48;
    public static final short KEY_C = 46;
    public static final short KEY_D = 32;
    public static final short KEY_E = 18;
    public static final short KEY_F = 33;

    public static final short[] HEX_KEYS = { KEY_0, KEY_1, KEY_2, KEY_3, KEY_4, KEY_5, KEY_6, KEY_7, KEY_8,
            KEY_9, KEY_A, KEY_B, KEY_C, KEY_D, KEY_E, KEY_F };

    public UinputKeyboard() {
        setup();
    }

    @Override
    protected void setupKeys() {
        for (short i = 1; i < 584; i++) {
            // Don't add BTN_TOUCH
            // Otherwise will be classified as stylus
            if (i != 330) {
                addKey(i);
            }
        }
    }

    @Override
    protected boolean hasKeys() {
        return true;
    }

    @Override
    protected void setupAbs() {

    }

    @Override
    protected boolean hasAbs() {
        return false;
    }

    @Override
    protected short getVendor() {
        // Doesn't matter
        return 0x0123;
    }

    @Override
    protected short getProduct() {
        // Doesn't matter
        return 0x4567;
    }

    @Override
    protected String getName() {
        // Must be qwerty or qwerty2
        // only these key character map has hex input feature
        return "qwerty";
    }

    public void type(String text) {
        for (char ch : text.toCharArray()) {
            String hexString = Integer.toHexString((int) ch);
            for (char digit : hexString.toCharArray()) {
                short key = HEX_KEYS[Character.getNumericValue(digit)];
                emitKey(key, 1);
                emitReport();
                emitKey(key, 0);
                emitReport();
            }
            emitKey(KEY_LEFTSHIFT, 1);
            emitReport();
            emitKey(KEY_LEFTALT, 1);
            emitReport();
            emitKey(KEY_X, 1);
            emitReport();
            emitKey(KEY_LEFTSHIFT, 0);
            emitReport();
            emitKey(KEY_LEFTALT, 0);
            emitReport();
            emitKey(KEY_X, 0);
            emitReport();
        }
    }

    @Override
    protected void setupMsc() {
        // Emulating my keyboard
        // No idea what this does
        // Shouldn't matter
        addMsc(4);
    }

    @Override
    protected boolean hasMsc() {
        return true;
    }

    @Override
    protected void setupLed() {
        // Shouldn't matter
        addLed(0);
        addLed(1);
        addLed(2);
        addLed(3);
        addLed(4);
    }

    @Override
    protected boolean hasLed() {
        return true;
    }
}
