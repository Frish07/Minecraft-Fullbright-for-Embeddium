package fullbright.port;

public final class Toggle {
    private static volatile boolean enabled;
    private static boolean keyDown;

    private Toggle() {}

    public static void legacyKey(boolean pressed) {
        if (pressed && !keyDown) {
            toggle();
        }
        keyDown = pressed;
    }

    public static void key(int key, int action, int toggleKey) {
        if (key == toggleKey && action == 1) {
            toggle();
        }
    }

    private static void toggle() {
        enabled = !enabled;
        System.out.println("[Fullbright] " + (enabled ? "Enabled" : "Disabled"));
    }

    public static boolean enabled() {
        return enabled;
    }

    public static int pixel(int original) {
        return enabled ? -1 : original;
    }
}
