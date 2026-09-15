package com.ilker.opendrive.ui;

import com.ilker.opendrive.gl.HudProgram;

/**
 * A 3x5 block font. Small, chunky and entirely code — enough for labels,
 * vehicle names and readouts without shipping a texture.
 */
public final class PixelFont {

    private static final int[] GLYPHS = new int[128 * 5];

    private static void define(char c, int r0, int r1, int r2, int r3, int r4) {
        int base = c * 5;
        GLYPHS[base] = r0;
        GLYPHS[base + 1] = r1;
        GLYPHS[base + 2] = r2;
        GLYPHS[base + 3] = r3;
        GLYPHS[base + 4] = r4;
    }

    static {
        define('A', 0b010, 0b101, 0b111, 0b101, 0b101);
        define('B', 0b110, 0b101, 0b110, 0b101, 0b110);
        define('C', 0b011, 0b100, 0b100, 0b100, 0b011);
        define('D', 0b110, 0b101, 0b101, 0b101, 0b110);
        define('E', 0b111, 0b100, 0b110, 0b100, 0b111);
        define('F', 0b111, 0b100, 0b110, 0b100, 0b100);
        define('G', 0b011, 0b100, 0b101, 0b101, 0b011);
        define('H', 0b101, 0b101, 0b111, 0b101, 0b101);
        define('I', 0b111, 0b010, 0b010, 0b010, 0b111);
        define('J', 0b001, 0b001, 0b001, 0b101, 0b010);
        define('K', 0b101, 0b101, 0b110, 0b101, 0b101);
        define('L', 0b100, 0b100, 0b100, 0b100, 0b111);
        define('M', 0b101, 0b111, 0b111, 0b101, 0b101);
        define('N', 0b101, 0b111, 0b101, 0b101, 0b101);
        define('O', 0b111, 0b101, 0b101, 0b101, 0b111);
        define('P', 0b110, 0b101, 0b110, 0b100, 0b100);
        define('Q', 0b111, 0b101, 0b101, 0b111, 0b001);
        define('R', 0b110, 0b101, 0b110, 0b101, 0b101);
        define('S', 0b011, 0b100, 0b010, 0b001, 0b110);
        define('T', 0b111, 0b010, 0b010, 0b010, 0b010);
        define('U', 0b101, 0b101, 0b101, 0b101, 0b111);
        define('V', 0b101, 0b101, 0b101, 0b101, 0b010);
        define('W', 0b101, 0b101, 0b111, 0b111, 0b101);
        define('X', 0b101, 0b101, 0b010, 0b101, 0b101);
        define('Y', 0b101, 0b101, 0b010, 0b010, 0b010);
        define('Z', 0b111, 0b001, 0b010, 0b100, 0b111);

        define('0', 0b111, 0b101, 0b101, 0b101, 0b111);
        define('1', 0b010, 0b110, 0b010, 0b010, 0b111);
        define('2', 0b111, 0b001, 0b111, 0b100, 0b111);
        define('3', 0b111, 0b001, 0b111, 0b001, 0b111);
        define('4', 0b101, 0b101, 0b111, 0b001, 0b001);
        define('5', 0b111, 0b100, 0b111, 0b001, 0b111);
        define('6', 0b111, 0b100, 0b111, 0b101, 0b111);
        define('7', 0b111, 0b001, 0b001, 0b001, 0b001);
        define('8', 0b111, 0b101, 0b111, 0b101, 0b111);
        define('9', 0b111, 0b101, 0b111, 0b001, 0b111);

        define('-', 0b000, 0b000, 0b111, 0b000, 0b000);
        define('.', 0b000, 0b000, 0b000, 0b000, 0b010);
        define(':', 0b000, 0b010, 0b000, 0b010, 0b000);
        define('/', 0b001, 0b001, 0b010, 0b100, 0b100);
        define('+', 0b000, 0b010, 0b111, 0b010, 0b000);
        define('!', 0b010, 0b010, 0b010, 0b000, 0b010);
        define('%', 0b101, 0b001, 0b010, 0b100, 0b101);
        define('>', 0b100, 0b010, 0b001, 0b010, 0b100);
        define('<', 0b001, 0b010, 0b100, 0b010, 0b001);
    }

    private PixelFont() {
    }

    /** Width in pixels of a string drawn at the given pixel size. */
    public static float width(String text, float pixel) {
        if (text.isEmpty()) return 0f;
        return text.length() * 4f * pixel - pixel;
    }

    public static void draw(HudProgram g, String text, float x, float y, float pixel,
                            float r, float gr, float b, float a) {
        float cursor = x;
        for (int i = 0; i < text.length(); i++) {
            char c = Character.toUpperCase(text.charAt(i));
            if (c < 128) {
                int base = c * 5;
                for (int row = 0; row < 5; row++) {
                    int bits = GLYPHS[base + row];
                    if (bits == 0) continue;
                    for (int col = 0; col < 3; col++) {
                        if ((bits & (1 << (2 - col))) != 0) {
                            g.rect(cursor + col * pixel, y + row * pixel, pixel, pixel, r, gr, b, a);
                        }
                    }
                }
            }
            cursor += 4f * pixel;
        }
    }

    public static void drawCentered(HudProgram g, String text, float cx, float y, float pixel,
                                    float r, float gr, float b, float a) {
        draw(g, text, cx - width(text, pixel) * 0.5f, y, pixel, r, gr, b, a);
    }

    // ------------------------------------------------------- seven segment

    private static final int[] SEVEN = {
            0b1111110, // 0 : a b c d e f
            0b0110000, // 1 : b c
            0b1101101, // 2 : a b d e g
            0b1111001, // 3 : a b c d g
            0b0110011, // 4 : b c f g
            0b1011011, // 5 : a c d f g
            0b1011111, // 6 : a c d e f g
            0b1110000, // 7 : a b c
            0b1111111, // 8
            0b1111011, // 9
    };

    /** Big calculator-style digit, drawn inside the given box. */
    public static void sevenSegment(HudProgram g, int digit, float x, float y,
                                    float w, float h, float t,
                                    float r, float gr, float b, float a) {
        if (digit < 0 || digit > 9) return;
        int mask = SEVEN[digit];
        float half = h * 0.5f;
        // a
        if ((mask & 0b1000000) != 0) g.rect(x + t, y, w - 2 * t, t, r, gr, b, a);
        // b
        if ((mask & 0b0100000) != 0) g.rect(x + w - t, y + t, t, half - 1.5f * t, r, gr, b, a);
        // c
        if ((mask & 0b0010000) != 0) g.rect(x + w - t, y + half + 0.5f * t, t, half - 1.5f * t, r, gr, b, a);
        // d
        if ((mask & 0b0001000) != 0) g.rect(x + t, y + h - t, w - 2 * t, t, r, gr, b, a);
        // e
        if ((mask & 0b0000100) != 0) g.rect(x, y + half + 0.5f * t, t, half - 1.5f * t, r, gr, b, a);
        // f
        if ((mask & 0b0000010) != 0) g.rect(x, y + t, t, half - 1.5f * t, r, gr, b, a);
        // g
        if ((mask & 0b0000001) != 0) g.rect(x + t, y + half - 0.5f * t, w - 2 * t, t, r, gr, b, a);
    }

    /** Right-aligned integer using seven-segment digits. */
    public static void sevenSegmentNumber(HudProgram g, int value, int digits,
                                          float rightX, float y, float digitW, float h, float t,
                                          float r, float gr, float b, float a,
                                          float dimR, float dimG, float dimB, float dimA) {
        if (value < 0) value = 0;
        float gap = digitW * 0.28f;
        for (int i = 0; i < digits; i++) {
            int place = (int) Math.pow(10, i);
            int d = (value / place) % 10;
            boolean significant = value >= place || i == 0;
            float x = rightX - (i + 1) * digitW - i * gap;
            if (significant) {
                sevenSegment(g, d, x, y, digitW, h, t, r, gr, b, a);
            } else {
                sevenSegment(g, 8, x, y, digitW, h, t, dimR, dimG, dimB, dimA);
            }
        }
    }
}
