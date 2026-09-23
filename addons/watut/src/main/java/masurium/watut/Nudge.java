package masurium.watut;

/**
 * When a bot has done something worth telling WATUT: it moved, or swung, or is using
 * something. At most once a second: WATUT only needs to hear it within its minutes, and
 * calling it every tick would be twenty calls a second for nothing.
 */
final class Nudge {

    /** Less than this, squared, is standing still: a bot breathes too. */
    static final double STILL = 0.0025;
    static final int EVERY = 20;

    private long last = Long.MIN_VALUE / 2;

    boolean due(double movedSquared, boolean acting, long tick) {
        if (movedSquared < STILL && !acting) return false;
        if (tick - last < EVERY && tick >= last) return false;
        last = tick;
        return true;
    }
}
