package edu.uw.ubicomp.apolloproto;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

/**
 * Magical Touch (CHI EA '26) "Ball Rescue", rebuilt for a stock phone, in plain Java.
 *
 * The paper reads raw 78 x 52 capacitance frames through custom Surface firmware. Android only exposes
 * each touch as an ellipse (position, major/minor axis, orientation, pressure), so this rebuilds the
 * paper's contact field from those ellipses: each touch is painted into a 52 x 78 grid with intensity
 * from its pressure and size, Gaussian-smoothed (three box blurs) and dilated slightly, as in the paper.
 * Physics per the paper: gravity + linear drag + a repulsive force along the field's gradient, and
 * denser "gravel" (firmer, larger contact) has a higher coefficient of restitution.
 */
final class TouchWorld {
    static final int COLS = 52, ROWS = 78;

    static final class Ball {
        double x, y, vx, vy, freeFall, r;
        boolean dead;
    }

    static final class Star {
        double x, y;
    }

    final float[] field = new float[COLS * ROWS];
    private final float[] tmp = new float[COLS * ROWS];
    final List<Ball> balls = new ArrayList<>();
    final List<Star> stars = new ArrayList<>();
    final double width, height, cell;   // world units = pixels
    double basketX0, basketX1, basketY;
    int saved, lost, starsCollected;
    private final Random rng = new Random(7);
    private double spawnTimer;

    // Tuning (pixels, seconds); the paper gives no values, so these are chosen to play well on a phone.
    double gravity, drag = 0.4, repulse, threshold = 0.08, maxFreeFall = 5.0;
    /** Smallest painted touch (px). Phones under-report contact size (the Pixel 4 gives 0-40 px for a fingertip ~150 px wide). */
    double minTouch;

    TouchWorld(double width, double height) {
        this.width = width;
        this.height = height;
        this.cell = width / COLS;
        gravity = height * 0.9;
        repulse = height * 9;
        basketX0 = width * 0.35;
        basketX1 = width * 0.65;
        basketY = height * 0.96;
        minTouch = cell * 1.5;
        for (int i = 0; i < 4; i++) addStar();
    }

    private void addStar() {
        Star s = new Star();
        s.x = width * (0.1 + 0.8 * rng.nextDouble());
        s.y = height * (0.25 + 0.5 * rng.nextDouble());
        stars.add(s);
    }

    void clearField() {
        java.util.Arrays.fill(field, 0);
    }

    /**
     * Paints one touch ellipse (pixels; orientation in radians clockwise from vertical, as Android
     * reports it). Intensity 0..1 stands in for the paper's capacitance strength.
     */
    void addTouch(double x, double y, double major, double minor, double orientation, double intensity) {
        double a = Math.max(major, minTouch) / 2, b = Math.max(minor, minTouch * 0.8) / 2;
        double cos = Math.cos(orientation), sin = Math.sin(orientation);
        int c0 = (int) Math.max(0, (x - a) / cell - 1), c1 = (int) Math.min(COLS - 1, (x + a) / cell + 1);
        int r0 = (int) Math.max(0, (y - a) / cell - 1), r1 = (int) Math.min(ROWS - 1, (y + a) / cell + 1);
        for (int r = r0; r <= r1; r++) {
            for (int c = c0; c <= c1; c++) {
                double dx = (c + 0.5) * cell - x, dy = (r + 0.5) * cell - y;
                double u = dx * cos + dy * sin, v = -dx * sin + dy * cos;   // into the ellipse's frame
                double q = (u * u) / (b * b) + (v * v) / (a * a);           // major axis along v (vertical)
                if (q <= 1.0) field[r * COLS + c] = (float) Math.max(field[r * COLS + c], intensity * (1 - 0.5 * q));
            }
        }
    }

    /** Gaussian smoothing (three box-blur passes), then a slight dilation, as the paper describes. */
    void smoothField() {
        for (int pass = 0; pass < 3; pass++) {
            for (int r = 0; r < ROWS; r++) {
                for (int c = 0; c < COLS; c++) {
                    float s = 0;
                    int n = 0;
                    for (int dc = -1; dc <= 1; dc++) {
                        int cc = c + dc;
                        if (cc < 0 || cc >= COLS) continue;
                        s += field[r * COLS + cc];
                        n++;
                    }
                    tmp[r * COLS + c] = s / n;
                }
            }
            for (int r = 0; r < ROWS; r++) {
                for (int c = 0; c < COLS; c++) {
                    float s = 0;
                    int n = 0;
                    for (int dr = -1; dr <= 1; dr++) {
                        int rr = r + dr;
                        if (rr < 0 || rr >= ROWS) continue;
                        s += tmp[rr * COLS + c];
                        n++;
                    }
                    field[r * COLS + c] = s / n;
                }
            }
        }
        for (int r = 0; r < ROWS; r++) {   // dilate: each cell takes the max of itself and its 4 neighbours
            for (int c = 0; c < COLS; c++) {
                float m = field[r * COLS + c];
                if (c > 0) m = Math.max(m, field[r * COLS + c - 1]);
                if (c < COLS - 1) m = Math.max(m, field[r * COLS + c + 1]);
                if (r > 0) m = Math.max(m, field[(r - 1) * COLS + c]);
                if (r < ROWS - 1) m = Math.max(m, field[(r + 1) * COLS + c]);
                tmp[r * COLS + c] = m;
            }
        }
        System.arraycopy(tmp, 0, field, 0, field.length);
    }

    /** Field value at a point (pixels), bilinear. */
    double density(double x, double y) {
        double gx = x / cell - 0.5, gy = y / cell - 0.5;
        int c = (int) Math.floor(gx), r = (int) Math.floor(gy);
        double fx = gx - c, fy = gy - r;
        return (1 - fx) * (1 - fy) * at(c, r) + fx * (1 - fy) * at(c + 1, r) + (1 - fx) * fy * at(c, r + 1) + fx * fy * at(c + 1, r + 1);
    }

    private double at(int c, int r) {
        if (c < 0 || c >= COLS || r < 0 || r >= ROWS) return 0;
        return field[r * COLS + c];
    }

    void spawnBall() {
        Ball b = new Ball();
        b.r = width * 0.022;
        b.x = width * (0.15 + 0.7 * rng.nextDouble());
        b.y = -b.r;
        b.vx = (rng.nextDouble() - 0.5) * width * 0.2;
        balls.add(b);
    }

    /** Advance the world by dt seconds (call with dt <= ~1/60). */
    void step(double dt, boolean autoSpawn) {
        if (autoSpawn) {
            spawnTimer -= dt;
            if (spawnTimer <= 0) {
                spawnBall();
                spawnTimer = 2.2;
            }
        }
        for (Iterator<Ball> it = balls.iterator(); it.hasNext(); ) {
            Ball b = it.next();
            b.vy += gravity * dt;
            b.vx -= drag * b.vx * dt;
            b.vy -= drag * b.vy * dt;
            double d = density(b.x, b.y);
            if (d > threshold) {
                double h = cell;   // gradient of the field (central differences)
                double gx = (density(b.x + h, b.y) - density(b.x - h, b.y)) / (2 * h);
                double gy = (density(b.x, b.y + h) - density(b.x, b.y - h)) / (2 * h);
                double g = Math.hypot(gx, gy);
                if (g > 1e-9) {
                    double nx = gx / g, ny = gy / g;            // points into denser gravel
                    b.vx -= repulse * d * nx * dt;               // repelled out of the hand
                    b.vy -= repulse * d * ny * dt;
                    double vn = b.vx * nx + b.vy * ny;
                    if (vn > 0) {                                // moving into the hand: bounce
                        // Restitution from the gravel just inside the surface (one radius in), not the
                        // thin edge the ball first touches: denser gravel = bouncier, as in the paper.
                        double inner = density(b.x + nx * b.r * 1.5, b.y + ny * b.r * 1.5);
                        double e = Math.min(0.92, 0.25 + 0.7 * Math.max(d, inner));
                        b.vx -= (1 + e) * vn * nx;
                        b.vy -= (1 + e) * vn * ny;
                    }
                }
                b.freeFall = 0;
            } else {
                b.freeFall += dt;
            }
            b.x += b.vx * dt;
            b.y += b.vy * dt;
            if (b.x < b.r) {
                b.x = b.r;
                b.vx = Math.abs(b.vx) * 0.8;
            } else if (b.x > width - b.r) {
                b.x = width - b.r;
                b.vx = -Math.abs(b.vx) * 0.8;
            }
            for (Iterator<Star> si = stars.iterator(); si.hasNext(); ) {
                Star s = si.next();
                if (Math.hypot(s.x - b.x, s.y - b.y) < b.r * 2.2) {
                    si.remove();
                    starsCollected++;
                }
            }
            if (b.y > basketY && b.x > basketX0 && b.x < basketX1) {
                saved++;
                it.remove();
            } else if (b.y > height + b.r || b.freeFall > maxFreeFall) {
                lost++;   // fell out, or free-fell too long (the paper's finite lifespan)
                b.dead = true;
                it.remove();
            }
        }
        while (stars.size() < 4) addStar();
    }
}
