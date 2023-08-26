package com.genymobile.scrcpy.video;

import com.genymobile.scrcpy.Workarounds;

import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.os.Build;

public enum CameraPosition {
    ALL("all", null),
    FRONT("front", CameraCharacteristics.LENS_FACING_FRONT),
    BACK("back", CameraCharacteristics.LENS_FACING_BACK),
    EXTERNAL("external", Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? CameraCharacteristics.LENS_FACING_EXTERNAL : -1);

    private final String name;
    private final Integer value;

    CameraPosition(String name, Integer value) {
        this.name = name;
        this.value = value;
    }

    public static CameraPosition findByName(String name) {
        for (CameraPosition cameraPosition : CameraPosition.values()) {
            if (name.equals(cameraPosition.name)) {
                return cameraPosition;
            }
        }

        return null;
    }

    public String getName() {
        return name;
    }

    public boolean matches(String cameraId) {
        if (value == null) {
            return true;
        }
        try {
            CameraCharacteristics cameraCharacteristics = Workarounds.getCameraManager()
                    .getCameraCharacteristics(cameraId);
            Integer lensFacing = cameraCharacteristics.get(CameraCharacteristics.LENS_FACING);
            return value.equals(lensFacing);
        } catch (CameraAccessException e) {
            return false;
        }
    }
}
