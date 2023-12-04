package com.genymobile.scrcpy;

public enum VideoSource {
    DISPLAY("display"),
    CAMERA("camera"),
    VIRTUAL("virtual");

    private final String name;

    VideoSource(String name) {
        this.name = name;
    }

    static VideoSource findByName(String name) {
        for (VideoSource videoSource : VideoSource.values()) {
            if (name.equals(videoSource.name)) {
                return videoSource;
            }
        }

        return null;
    }
}
