package com.genymobile.scrcpy;

import java.io.IOException;

public final class DeviceMessageSender {

    private final DesktopConnection connection;

    private Thread thread;

    private String clipboardText;

    private long ack;

    private boolean virtualDisplayEmpty;

    public DeviceMessageSender(DesktopConnection connection) {
        this.connection = connection;
    }

    public synchronized void pushClipboardText(String text) {
        clipboardText = text;
        notify();
    }

    public synchronized void pushAckClipboard(long sequence) {
        ack = sequence;
        notify();
    }

    public synchronized void pushVirtualDisplayEmpty() {
        virtualDisplayEmpty = true;
        notify();
    }

    private void loop() throws IOException, InterruptedException {
        while (!Thread.currentThread().isInterrupted()) {
            String text;
            long sequence;
            boolean virtualDisplayEmpty;
            synchronized (this) {
                while (ack == DeviceMessage.SEQUENCE_INVALID && clipboardText == null && !this.virtualDisplayEmpty) {
                    wait();
                }
                text = clipboardText;
                clipboardText = null;

                sequence = ack;
                ack = DeviceMessage.SEQUENCE_INVALID;

                virtualDisplayEmpty = this.virtualDisplayEmpty;
                this.virtualDisplayEmpty = false;
            }

            if (sequence != DeviceMessage.SEQUENCE_INVALID) {
                DeviceMessage event = DeviceMessage.createAckClipboard(sequence);
                connection.sendDeviceMessage(event);
            }
            if (text != null) {
                DeviceMessage event = DeviceMessage.createClipboard(text);
                connection.sendDeviceMessage(event);
            }
            if (virtualDisplayEmpty) {
                DeviceMessage event = DeviceMessage.createVirtualDisplayEmpty();
                connection.sendDeviceMessage(event);
            }
        }
    }
    public void start() {
        thread = new Thread(() -> {
            try {
                loop();
            } catch (IOException | InterruptedException e) {
                // this is expected on close
            } finally {
                Ln.d("Device message sender stopped");
            }
        }, "control-send");
        thread.start();
    }

    public void stop() {
        if (thread != null) {
            thread.interrupt();
        }
    }

    public void join() throws InterruptedException {
        if (thread != null) {
            thread.join();
        }
    }
}
