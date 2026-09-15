package com.ilker.opendrive.ui;

import com.ilker.opendrive.gl.HudProgram;

/**
 * A 5x7 block font, drawn as small quads. Wide enough for real letterforms —
 * a 3x5 grid cannot tell M from N — and still nothing but code.
 */
public final class PixelFont {

    /** Glyph cell, in font pixels. */
    public static final int GLYPH_W = 5;
    public static final int GLYPH_H = 7;
    private static final int ADVANCE = 6;

    private static final int[] GLYPHS = new int[128 * GLYPH_H];

    private static void define(char c, int r0, int r1, int r2, int r3, int r4, int r5, int r6) {
        int base = c * GLYPH_H;
        GLYPHS[base] = r0;
        GLYPHS[base + 1] = r1;
        GLYPHS[base + 2] = r2;
        GLYPHS[base + 3] = r3;
        GLYPHS[base + 4] = r4;
        GLYPHS[base + 5] = r5;
        GLYPHS[base + 6] = r6;
    }

    static {
        define('A', 0b01110, 0b10001, 0b10001, 0b11111, 0b10001, 0b10001, 0b10001);
        define('B', 0b11110, 0b10001, 0b10001, 0b11110, 0b10001, 0b10001, 0b11110);
        define('C', 0b01110, 0b10001, 0b10000, 0b10000, 0b10000, 0b10001, 0b01110);
        define('D', 0b11110, 0b10001, 0b10001, 0b10001, 0b10001, 0b10001, 0b11110);
        define('E', 0b11111, 0b10000, 0b10000, 0b11110, 0b10000, 0b10000, 0b11111);
        define('F', 0b11111, 0b10000, 0b10000, 0b11110, 0b10000, 0b10000, 0b10000);
        define('G', 0b01110, 0b10001, 0b10000, 0b10111, 0b10001, 0b10001, 0b01111);
        define('H', 0b10001, 0b10001, 0b10001, 0b11111, 0b10001, 0b10001, 0b10001);
        define('I', 0b01110, 0b00100, 0b00100, 0b00100, 0b00100, 0b00100, 0b01110);
        define('J', 0b00111, 0b00010, 0b00010, 0b00010, 0b00010, 0b10010, 0b01100);
        define('K', 0b10001, 0b10010, 0b10100, 0b11000, 0b10100, 0b10010, 0b10001);
        define('L', 0b10000, 0b10000, 0b10000, 0b10000, 0b10000, 0b10000, 0b11111);
        define('M', 0b10001, 0b11011, 0b10101, 0b10101, 0b10001, 0b10001, 0b10001);
        define('N', 0b10001, 0b11001, 0b10101, 0b10011, 0b10001, 0b10001, 0b10001);
        define('O', 0b01110, 0b10001, 0b10001, 0b10001, 0b10001, 0b10001, 0b01110);
        define('P', 0b11110, 0b10001, 0b10001, 0b11110, 0b10000, 0b10000, 0b10000);
        define('Q', 0b01110, 0b10001, 0b10001, 0b10001, 0b10101, 0b10010, 0b01101);
        define('R', 0b11110, 0b10001, 0b10001, 0b11110, 0b10100, 0b10010, 0b10001);
        define('S', 0b01111, 0b10000, 0b10000, 0b01110, 0b00001, 0b00001, 0b11110);
        define('T', 0b11111, 0b00100, 0b00100, 0b00100, 0b00100, 0b00100, 0b00100);
        define('U', 0b10001, 0b10001, 0b10001, 0b10001, 0b10001, 0b10001, 0b01110);
        define('V', 0b10001, 0b10001, 0b10001, 0b10001, 0b10001, 0b01010, 0b00100);
        define('W', 0b10001, 0b10001, 0b10001, 0b10101, 0b10101, 0b11011, 0b10001);
        define('X', 0b10001, 0b10001, 0b01010, 0b00100, 0b01010, 0b10001, 0b10001);
        define('Y', 0b10001, 0b10001, 0b01010, 0b00100, 0b00100, 0b00100, 0b00100);
        define('Z', 0b11111, 0b00001, 0b00010, 0b00100, 0b01000, 0b10000, 0b11111);

        define('0', 0b01110, 0b10001, 0b10011, 0b10101, 0b11001, 0b10001, 0b01110);
        define('1', 0b00100, 0b01100, 0b00100, 0b00100, 0b00100, 0b00100, 0b01110);
        define('2', 0b01110, 0b10001, 0b00001, 0b00010, 0b00100, 0b01000, 0b11111);
        define('3', 0b11111, 0b00010, 0b00100, 0b00010, 0b00001, 0b10001, 0b01110);
        define('4', 0b00010, 0b00110, 0b01010, 0b10010, 0b11111, 0b00010, 0b00010);
        define('5', 0b11111, 0b10000, 0b11110, 0b00001, 0b00001, 0b10001, 0b01110);
        define('6', 0b00110, 0b01000, 0b10000, 0b11110, 0b10001, 0b10001, 0b01110);
        define('7', 0b11111, 0b00001, 0b00010, 0b00100, 0b01000, 0b01000, 0b01000);
        define('8', 0b01110, 0b10001, 0b10001, 0b01110, 0b10001, 0b10001, 0b01110);
        define('9', 0b01110, 0b10001, 0b10001, 0b01111, 0b00001, 0b00010, 0b01100);

        define('-', 0b00000, 0b00000, 0b00000, 0b11111, 0b00000, 0b00000, 0b00000);
        define('.', 0b00000, 0b00000, 0b00000, 0b00000, 0b00000, 0b01100, 0b01100);
        define(',', 0b00000, 0b00000, 0b00000, 0b00000, 0b01100, 0b01100, 0b01000);
        define(':', 0b00000, 0b01100, 0b01100, 0b00000, 0b01100, 0b01100, 0b00000);
        define('/', 0b00001, 0b00010, 0b00010, 0b00100, 0b01000, 0b01000, 0b10000);
        define('+', 0b00000, 0b00100, 0b00100, 0b11111, 0b00100, 0b00100, 0b00000);
        define('!', 0b00100, 0b00100, 0b00100, 0b00100, 0b00100, 0b00000, 0b00100);
        define('?', 0b01110, 0b10001, 0b00001, 0b00110, 0b00100, 0b00000, 0b00100);
        define('%', 0b11001, 0b11010, 0b00010, 0b00100, 0b01000, 0b01011, 0b10011);
        define('>', 0b01000, 0b00100, 0b00010, 0b00001, 0b00010, 0b00100, 0b01000);
        define('<', 0b00010, 0b00100, 0b01000, 0b10000, 0b01000, 0b00100, 0b00010);
        define('(', 0b00010, 0b00100, 0b01000, 0b01000, 0b01000, 0b00100, 0b00010);
        define(')', 0b01000, 0b00100, 0b00010, 0b00010, 0b00010, 0b00100, 0b01000);
        define('*', 0b00000, 0b10101, 0b01110, 0b11111, 0b01110, 0b10101, 0b00000);
    }

    private PixelFont() {
    }

    /** Width of a string in screen pixels, at the given font-pixel size. */
    public static float width(String text, float pixel) {
        if (text == null || text.isEmpty()) return 0f;
        return (text.length() * ADVANCE - 1) * pixel;
    }

    public static float height(float pixel) {
        return GLYPH_H * pixel;
    }

    public static void draw(HudProgram g, String text, float x, float y, float pixel,
                            float r, float gr, float b, float a) {
        if (text == null) return;
        float cursor = x;
        for (int i = 0; i < text.length(); i++) {
            char c = normalise(text.charAt(i));
            if (c < 128) {
                int base = c * GLYPH_H;
                for (int row = 0; row < GLYPH_H; row++) {
                    int bits = GLYPHS[base + row];
                    if (bits == 0) continue;
                    // Merge horizontal runs into one quad instead of one per
                    // pixel — a third of the triangles for the same picture.
                    int col = 0;
                    while (col < GLYPH_W) {
                        if ((bits & (1 << (GLYPH_W - 1 - col))) == 0) {
                            col++;
                            continue;
                        }
                        int run = 1;
                        while (col + run < GLYPH_W
                                && (bits & (1 << (GLYPH_W - 1 - col - run))) != 0) {
                            run++;
                        }
                        g.rect(cursor + col * pixel, y + row * pixel,
                                run * pixel, pixel, r, gr, b, a);
                        col += run;
                    }
                }
            }
            cursor += ADVANCE * pixel;
        }
    }

    public static void drawCentered(HudProgram g, String text, float cx, float y, float pixel,
                                    float r, float gr, float b, float a) {
        draw(g, text, cx - width(text, pixel) * 0.5f, y, pixel, r, gr, b, a);
    }

    public static void drawRight(HudProgram g, String text, float rightX, float y, float pixel,
                                 float r, float gr, float b, float a) {
        draw(g, text, rightX - width(text, pixel), y, pixel, r, gr, b, a);
    }

    /** Folds Turkish letters onto their nearest ASCII shape. */
    private static char normalise(char c) {
        switch (c) {
            case 'ç': case 'Ç': return 'C';
            case 'ğ': case 'Ğ': return 'G';
            case 'ı': return 'I';
            case 'İ': return 'I';
            case 'ö': case 'Ö': return 'O';
            case 'ş': case 'Ş': return 'S';
            case 'ü': case 'Ü': return 'U';
            default: return Character.toUpperCase(c);
        }
    }

    // ------------------------------------------------------- seven segment

    private static final int[] SEVEN = {
            0b1111110, // 0
            0b0110000, // 1
            0b1101101, // 2
            0b1111001, // 3
            0b0110011, // 4
            0b1011011, // 5
            0b1011111, // 6
            0b1110000, // 7
            0b1111111, // 8
            0b1111011, // 9
    };

    /** Calculator-style digit filling the given box. */
    public static void sevenSegment(HudProgram g, int digit, float x, float y,
                                    float w, float h, float t,
                                    float r, float gr, float b, float a) {
        if (digit < 0 || digit > 9) return;
        int mask = SEVEN[digit];
        float half = h * 0.5f;
        if ((mask & 0b1000000) != 0) g.rect(x + t, y, w - 2 * t, t, r, gr, b, a);
        if ((mask & 0b0100000) != 0) g.rect(x + w - t, y + t, t, half - 1.5f * t, r, gr, b, a);
        if ((mask & 0b0010000) != 0) g.rect(x + w - t, y + half + 0.5f * t, t, half - 1.5f * t, r, gr, b, a);
        if ((mask & 0b0001000) != 0) g.rect(x + t, y + h - t, w - 2 * t, t, r, gr, b, a);
        if ((mask & 0b0000100) != 0) g.rect(x, y + half + 0.5f * t, t, half - 1.5f * t, r, gr, b, a);
        if ((mask & 0b0000010) != 0) g.rect(x, y + t, t, half - 1.5f * t, r, gr, b, a);
        if ((mask & 0b0000001) != 0) g.rect(x + t, y + half - 0.5f * t, w - 2 * t, t, r, gr, b, a);
    }

    /** Right-aligned integer; leading zeros are drawn faintly, like a real dash. */
    public static void sevenSegmentNumber(HudProgram g, int value, int digits,
                                          float rightX, float y, float digitW, float h, float t,
                                          float r, float gr, float b, float a,
                                          float dimR, float dimG, float dimB, float dimA) {
        if (value < 0) value = 0;
        float gap = digitW * 0.26f;
        int place = 1;
        for (int i = 0; i < digits; i++) {
            int d = (value / place) % 10;
            boolean significant = value >= place || i == 0;
            float x = rightX - (i + 1) * digitW - i * gap;
            if (significant) {
                sevenSegment(g, d, x, y, digitW, h, t, r, gr, b, a);
            } else {
                sevenSegment(g, 8, x, y, digitW, h, t, dimR, dimG, dimB, dimA);
            }
            place *= 10;
        }
    }

    /** Total width of a right-aligned seven-segment number. */
    public static float numberWidth(int digits, float digitW) {
        return digits * digitW + (digits - 1) * digitW * 0.26f;
    }
}
