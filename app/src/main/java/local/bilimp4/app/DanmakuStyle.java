package local.bilimp4.app;

record DanmakuStyle(float scale, float opacity, boolean originalColors, float speed, float area) {
    static final DanmakuStyle DEFAULT = new DanmakuStyle(0.8f, 0.7f, true, 1f, 0.56f);

    record Options(boolean followBili, DanmakuStyle local) {}
    record Resolved(DanmakuStyle style, boolean synced) {}
}
