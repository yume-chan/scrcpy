package com.genymobile.scrcpy;

public class DisplaySize {
    private final int width;
    private final int height;
    private final int density;

    public DisplaySize(int width, int height, int density) {
        this.width = width;
        this.height = height;
        this.density = density;
    }

    public static DisplaySize parse(String value) {
        int xIndex = value.indexOf('x');
        if (xIndex == -1) {
            return null;
        }
        int width = Integer.parseInt(value.substring(0, xIndex));

        int slashIndex = value.indexOf('/', xIndex + 1);
        if (slashIndex == -1) {
            return null;
        }
        int height = Integer.parseInt(value.substring(xIndex + 1, slashIndex));
        int density = Integer.parseInt(value.substring(slashIndex + 1));

        return new DisplaySize(width, height, density);
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public int getDensity() {
        return density;
    }
}
