package pondering.loading;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL32C;
import org.lwjgl.stb.STBTTAlignedQuad;
import org.lwjgl.stb.STBTTFontinfo;
import org.lwjgl.stb.STBTTPackContext;
import org.lwjgl.stb.STBTTPackedchar;
import org.lwjgl.stb.STBTruetype;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

/**
 * Pantalla de carga estilo "Pondering...": glifo que se funde entre frames + verbo que cambia
 * con efecto de escritura + barras de progreso. Dibuja con GL puro (sin las piezas internas de NeoForge).
 * Coordenadas como las de NeoForge: origen arriba-izquierda, en pixeles del framebuffer.
 */
final class Scene {
    record Bar(float progress, boolean indeterminate, String label) {}

    // Colores del proyecto de wallpaper
    static final int[] BG = {255, 251, 245};     // #fffbf5
    private static final float[] GLYPH = rgb(198, 97, 63);   // #c6613f
    private static final float[] TEXT = rgb(59, 59, 59);     // #3b3b3b

    private static final String[] VERBS = {"Accomplishing","Actioning","Actualizing","Baking","Booping","Brewing","Calculating","Cerebrating","Channeling","Churning","Clauding","Coalescing","Cogitating","Computing","Combobulating","Concocting","Considering","Contemplating","Cooking","Crafting","Creating","Crunching","Deciphering","Deliberating","Determining","Discombobulating","Doing","Effecting","Elucidating","Enchanting","Envisioning","Finagling","Flibbertigibbeting","Forging","Forming","Frolicking","Generating","Germinating","Hatching","Herding","Honking","Ideating","Imagining","Incubating","Inferring","Manifesting","Marinating","Meandering","Moseying","Mulling","Mustering","Musing","Noodling","Percolating","Perusing","Philosophizing","Pontificating","Pondering","Processing","Puttering","Puzzling","Reticulating","Ruminating","Schlepping","Shimmying","Simmering","Smooshing","Spelunking","Spinning","Stewing","Sussing","Synthesizing","Thinking","Tinkering","Transmuting","Unfurling","Unraveling","Vibing","Wandering","Whirring","Wibbling","Working","Wrangling"};
    private static final int[] GLYPHS = {0xB7, 0x2722, '*', 0x2736, 0x273B, 0x273D};
    private static final int CURSOR = 0x258C;
    private static final long[] DELAYS_MS = {2000, 3000, 5000};
    private static final long FRAME_MS = 170;
    private static final long STEP_MS = 40;

    private static final int ATLAS = 1024;
    private static final String FONT_UI = "C:/Windows/Fonts/segoeui.ttf";
    private static final String FONT_SYM = "C:/Windows/Fonts/seguisym.ttf";

    private record Glyph(float x0, float y0, float x1, float y1, float u0, float v0, float u1, float v1, float adv) {}

    private static final int TEXT_CLASS = 0, SYM_CLASS = 1, SMALL_CLASS = 2;

    private final Random rnd = new Random();
    private final Map<Long, Glyph> glyphs = new HashMap<>();
    private final int widthChars;
    private boolean ready;
    private int program, vao, vbo, atlasTex, uScreen, uTex, uMode, uRect, uRadius;
    private float textPx, symPx, smallPx;
    private float textBoxW;
    private FloatBuffer vertices = BufferUtils.createFloatBuffer(8 * 6 * 160);

    // estado de texto
    private String state;
    private String target;
    private int index;
    private boolean typing;
    private long lastStep;
    private long nextVerbAt;
    private int changes;
    private String current = "";
    private long t0 = -1;
    private int frames;

    Scene() {
        int max = 0;
        for (String v : VERBS) max = Math.max(max, v.length());
        widthChars = max + 3 + 3;
        state = " ".repeat(widthChars);
        target = pad("", widthChars);
    }

    private static float[] rgb(int r, int g, int b) { return new float[]{r / 255f, g / 255f, b / 255f}; }

    private static String pad(String s, int n) { return s.length() >= n ? s : s + " ".repeat(n - s.length()); }

    // ---------------------------------------------------------------- init

    private static ByteBuffer readFont(String path) throws IOException {
        byte[] b = Files.readAllBytes(Path.of(path));
        ByteBuffer buf = BufferUtils.createByteBuffer(b.length);
        buf.put(b).flip();
        return buf;
    }

    private static boolean has(ByteBuffer font, int cp) {
        STBTTFontinfo info = STBTTFontinfo.malloc();
        try {
            return STBTruetype.stbtt_InitFont(info, font) && STBTruetype.stbtt_FindGlyphIndex(info, cp) != 0;
        } finally {
            info.free();
        }
    }

    private void pack(STBTTPackContext pc, ByteBuffer font, int cls, float px, int first, int count) {
        STBTTPackedchar.Buffer chars = STBTTPackedchar.malloc(count);
        STBTruetype.stbtt_PackFontRange(pc, font, 0, px, first, chars);
        try (MemoryStack st = MemoryStack.stackPush()) {
            for (int i = 0; i < count; i++) {
                var xp = st.floats(0);
                var yp = st.floats(0);
                STBTTAlignedQuad q = STBTTAlignedQuad.malloc(st);
                STBTruetype.stbtt_GetPackedQuad(chars, ATLAS, ATLAS, i, xp, yp, q, false);
                glyphs.put(key(cls, first + i), new Glyph(q.x0(), q.y0(), q.x1(), q.y1(), q.s0(), q.t0(), q.s1(), q.t1(), xp.get(0)));
            }
        }
        chars.free();
    }

    private static long key(int cls, int cp) { return ((long) cls << 32) | cp; }

    private void init(int w, int h) throws IOException {
        textPx = h * 0.058f;
        symPx = textPx * 1.2308f;
        smallPx = h * 0.028f;

        Trace.log("init: start " + w + "x" + h);
        ByteBuffer ui = readFont(FONT_UI);
        ByteBuffer sym = readFont(FONT_SYM);
        ByteBuffer bitmap = BufferUtils.createByteBuffer(ATLAS * ATLAS);
        STBTTPackContext pc = STBTTPackContext.malloc();
        STBTruetype.stbtt_PackBegin(pc, bitmap, ATLAS, ATLAS, 0, 1, MemoryUtil.NULL);
        STBTruetype.stbtt_PackSetOversampling(pc, 2, 2);

        pack(pc, ui, TEXT_CLASS, textPx, 32, 95);
        pack(pc, ui, SMALL_CLASS, smallPx, 32, 95);
        for (int cp : GLYPHS) {
            ByteBuffer f = has(sym, cp) ? sym : ui;
            pack(pc, f, SYM_CLASS, symPx, cp, 1);
        }
        ByteBuffer cursorFont = has(sym, CURSOR) ? sym : (has(ui, CURSOR) ? ui : null);
        if (cursorFont != null) pack(pc, cursorFont, TEXT_CLASS, textPx, CURSOR, 1);
        Trace.log("init: packed");
        STBTruetype.stbtt_PackEnd(pc);
        pc.free();

        atlasTex = GL32C.glGenTextures();
        GL32C.glActiveTexture(GL32C.GL_TEXTURE0 + 14);
        GL32C.glBindTexture(GL32C.GL_TEXTURE_2D, atlasTex);
        int prevAlign = GL32C.glGetInteger(GL32C.GL_UNPACK_ALIGNMENT);
        GL32C.glPixelStorei(GL32C.GL_UNPACK_ALIGNMENT, 1);
        GL32C.glTexImage2D(GL32C.GL_TEXTURE_2D, 0, GL32C.GL_R8, ATLAS, ATLAS, 0, GL32C.GL_RED, GL32C.GL_UNSIGNED_BYTE, bitmap);
        GL32C.glPixelStorei(GL32C.GL_UNPACK_ALIGNMENT, prevAlign);
        GL32C.glTexParameteri(GL32C.GL_TEXTURE_2D, GL32C.GL_TEXTURE_MIN_FILTER, GL32C.GL_LINEAR);
        GL32C.glTexParameteri(GL32C.GL_TEXTURE_2D, GL32C.GL_TEXTURE_MAG_FILTER, GL32C.GL_LINEAR);
        GL32C.glTexParameteri(GL32C.GL_TEXTURE_2D, GL32C.GL_TEXTURE_WRAP_S, GL32C.GL_CLAMP_TO_EDGE);
        GL32C.glTexParameteri(GL32C.GL_TEXTURE_2D, GL32C.GL_TEXTURE_WRAP_T, GL32C.GL_CLAMP_TO_EDGE);

        Trace.log("init: texture uploaded");
        program = buildProgram();
        uScreen = GL32C.glGetUniformLocation(program, "screenSize");
        uTex = GL32C.glGetUniformLocation(program, "tex");
        uMode = GL32C.glGetUniformLocation(program, "mode");
        uRect = GL32C.glGetUniformLocation(program, "rectSize");
        uRadius = GL32C.glGetUniformLocation(program, "radius");

        vao = GL32C.glGenVertexArrays();
        vbo = GL32C.glGenBuffers();
        GL32C.glBindVertexArray(vao);
        GL32C.glBindBuffer(GL32C.GL_ARRAY_BUFFER, vbo);
        GL32C.glEnableVertexAttribArray(0);
        GL32C.glVertexAttribPointer(0, 2, GL32C.GL_FLOAT, false, 32, 0);
        GL32C.glEnableVertexAttribArray(1);
        GL32C.glVertexAttribPointer(1, 2, GL32C.GL_FLOAT, false, 32, 8);
        GL32C.glEnableVertexAttribArray(2);
        GL32C.glVertexAttribPointer(2, 4, GL32C.GL_FLOAT, false, 32, 16);

        Trace.log("init: vao ready");
        textBoxW = measure(pad(longest() + "...", widthChars), TEXT_CLASS);
        ready = true;
        Trace.log("init: done");
    }

    private static String longest() {
        String best = "";
        for (String v : VERBS) if (v.length() >= best.length()) best = v;
        return best;
    }

    private static int buildProgram() {
        String vs = "#version 150\n"
            + "in vec2 position; in vec2 tex_in; in vec4 colour_in;\n"
            + "uniform vec2 screenSize;\n"
            + "out vec2 uv; out vec4 col;\n"
            + "void main(){ uv = tex_in; col = colour_in; gl_Position = vec4((position/screenSize)*2.0-1.0, 0.0, 1.0); }\n";
        String fs = "#version 150\n"
            + "uniform sampler2D tex; uniform int mode; uniform vec2 rectSize; uniform float radius;\n"
            + "in vec2 uv; in vec4 col; out vec4 outColor;\n"
            + "void main(){\n"
            + "  if (mode == 0) { outColor = vec4(col.rgb, col.a * texture(tex, uv).r); }\n"
            + "  else {\n"
            + "    vec2 p = (uv - 0.5) * rectSize;\n"
            + "    vec2 q = abs(p) - 0.5 * rectSize + radius;\n"
            + "    float d = length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - radius;\n"
            + "    outColor = vec4(col.rgb, col.a * clamp(0.5 - d, 0.0, 1.0));\n"
            + "  }\n"
            + "}\n";
        int v = compile(GL32C.GL_VERTEX_SHADER, vs);
        int f = compile(GL32C.GL_FRAGMENT_SHADER, fs);
        int p = GL32C.glCreateProgram();
        GL32C.glBindAttribLocation(p, 0, "position");
        GL32C.glBindAttribLocation(p, 1, "tex_in");
        GL32C.glBindAttribLocation(p, 2, "colour_in");
        GL32C.glAttachShader(p, v);
        GL32C.glAttachShader(p, f);
        GL32C.glLinkProgram(p);
        if (GL32C.glGetProgrami(p, GL32C.GL_LINK_STATUS) == 0) throw new IllegalStateException("Pondering link: " + GL32C.glGetProgramInfoLog(p));
        GL32C.glDetachShader(p, v);
        GL32C.glDetachShader(p, f);
        GL32C.glDeleteShader(v);
        GL32C.glDeleteShader(f);
        return p;
    }

    private static int compile(int type, String src) {
        int s = GL32C.glCreateShader(type);
        GL32C.glShaderSource(s, src);
        GL32C.glCompileShader(s);
        if (GL32C.glGetShaderi(s, GL32C.GL_COMPILE_STATUS) == 0) throw new IllegalStateException("Pondering shader: " + GL32C.glGetShaderInfoLog(s));
        return s;
    }

    // ---------------------------------------------------------------- texto

    private Glyph glyph(int cls, int cp) {
        Glyph g = glyphs.get(key(cls, cp));
        if (g == null && cls == TEXT_CLASS) g = glyphs.get(key(TEXT_CLASS, cp == CURSOR ? '|' : '?'));
        if (g == null) g = glyphs.get(key(cls, '?'));
        return g;
    }

    private float measure(String s, int cls) {
        float w = 0;
        for (int i = 0; i < s.length(); i++) {
            Glyph g = glyph(cls, s.charAt(i));
            if (g != null) w += g.adv();
        }
        return w;
    }

    private void text(String s, float x, float baseline, int cls, float[] c, float a) {
        for (int i = 0; i < s.length(); i++) {
            Glyph g = glyph(cls, s.charAt(i));
            if (g == null) continue;
            if (s.charAt(i) != ' ') quad(x + g.x0(), baseline + g.y0(), x + g.x1(), baseline + g.y1(), g.u0(), g.v0(), g.u1(), g.v1(), c, a);
            x += g.adv();
        }
    }

    private void quad(float x0, float y0, float x1, float y1, float u0, float v0, float u1, float v1, float[] c, float a) {
        if (vertices.remaining() < 48) {
            FloatBuffer n = BufferUtils.createFloatBuffer(vertices.capacity() * 2);
            vertices.flip();
            n.put(vertices);
            vertices = n;
        }
        v(x0, y0, u0, v0, c, a); v(x1, y0, u1, v0, c, a); v(x1, y1, u1, v1, c, a);
        v(x0, y0, u0, v0, c, a); v(x1, y1, u1, v1, c, a); v(x0, y1, u0, v1, c, a);
    }

    private void v(float x, float y, float u, float t, float[] c, float a) {
        vertices.put(x).put(y).put(u).put(t).put(c[0]).put(c[1]).put(c[2]).put(a);
    }

    private void flush(int mode) {
        vertices.flip();
        int count = vertices.remaining() / 8;
        if (count > 0) {
            GL32C.glUniform1i(uMode, mode);
            GL32C.glBufferData(GL32C.GL_ARRAY_BUFFER, vertices, GL32C.GL_STREAM_DRAW);
            GL32C.glDrawArrays(GL32C.GL_TRIANGLES, 0, count);
        }
        vertices.clear();
    }

    private void roundRect(float x, float y, float w, float h, float[] c, float a) {
        GL32C.glUniform2f(uRect, w, h);
        GL32C.glUniform1f(uRadius, h / 2f);
        quad(x, y, x + w, y + h, 0, 0, 1, 1, c, a);
        flush(1);
    }

    // ---------------------------------------------------------------- animacion de texto


    private char stage(char target, int s) {
        if (target == ' ') return ' ';
        return switch (s) {
            case 3 -> target;
            case 2, 1 -> {
                int r = rnd.nextInt(3);
                yield r == 0 ? '.' : r == 1 ? '_' : target;
            }
            default -> (char) CURSOR;
        };
    }

    private void step() {
        int u = index;
        if (u - 3 >= target.length()) { typing = false; return; }
        index++;
        char[] chars = state.toCharArray();
        for (int b = 0; b <= 3; b++) {
            int k = u - b;
            if (k >= 0 && k < target.length()) chars[k] = stage(target.charAt(k), b);
        }
        state = new String(chars);
    }

    private void setVerb(String verb) {
        target = pad(verb + "...", widthChars);
        index = 0;
        typing = true;
    }

    private void animate(long now) {
        if (nextVerbAt == 0 || now >= nextVerbAt) {
            String v;
            do { v = VERBS[rnd.nextInt(VERBS.length)]; } while (v.equals(current));
            current = v;
            setVerb(v);
            nextVerbAt = now + (changes < DELAYS_MS.length ? DELAYS_MS[changes] : 5000);
            changes++;
            lastStep = now;
        }
        if (typing) {
            int guard = 0;
            while (typing && now - lastStep >= STEP_MS && guard++ < 20) { step(); lastStep += STEP_MS; }
            if (now - lastStep > 200) lastStep = now;
        }
    }

    // ---------------------------------------------------------------- dibujo

    /** Dibuja una escena completa. alpha 0..1 (fundido final). */
    void draw(int w, int h, float alpha, List<Bar> bars) {
        int prevProgram = GL32C.glGetInteger(GL32C.GL_CURRENT_PROGRAM);
        int prevVao = GL32C.glGetInteger(GL32C.GL_VERTEX_ARRAY_BINDING);
        int prevBuf = GL32C.glGetInteger(GL32C.GL_ARRAY_BUFFER_BINDING);
        int prevActive = GL32C.glGetInteger(GL32C.GL_ACTIVE_TEXTURE);
        boolean cull = GL32C.glIsEnabled(GL32C.GL_CULL_FACE);
        boolean depth = GL32C.glIsEnabled(GL32C.GL_DEPTH_TEST);
        try {
            if (!ready) init(w, h);
            long now = System.nanoTime() / 1_000_000L;
            if (t0 < 0) t0 = now;
            animate(now);

            GL32C.glDisable(GL32C.GL_CULL_FACE);
            GL32C.glDisable(GL32C.GL_DEPTH_TEST);
            GL32C.glEnable(GL32C.GL_BLEND);
            GL32C.glBlendFunc(GL32C.GL_SRC_ALPHA, GL32C.GL_ONE_MINUS_SRC_ALPHA);
            GL32C.glUseProgram(program);
            GL32C.glUniform2f(uScreen, w, h);
            GL32C.glActiveTexture(GL32C.GL_TEXTURE0 + 14);
            GL32C.glBindTexture(GL32C.GL_TEXTURE_2D, atlasTex);
            GL32C.glUniform1i(uTex, 14);
            GL32C.glBindVertexArray(vao);
            GL32C.glBindBuffer(GL32C.GL_ARRAY_BUFFER, vbo);

            drawSpinner(w, h, alpha, now - t0);
            drawBars(w, h, alpha, bars, now - t0);
            if (frames++ < 3) Trace.log("draw ok #" + frames + " err=" + GL32C.glGetError());
        } catch (Throwable t) {
            Trace.error("draw failed", t);
            throw new RuntimeException(t);
        } finally {
            if (cull) GL32C.glEnable(GL32C.GL_CULL_FACE);
            if (depth) GL32C.glEnable(GL32C.GL_DEPTH_TEST);
            GL32C.glActiveTexture(prevActive);
            GL32C.glBindBuffer(GL32C.GL_ARRAY_BUFFER, prevBuf);
            GL32C.glBindVertexArray(prevVao);
            GL32C.glUseProgram(prevProgram);
        }
    }

    private void drawSpinner(int w, int h, float alpha, long elapsed) {
        float iconW = symPx * 1.5f;
        float gap = textPx * 0.2f;
        float total = iconW + gap + textBoxW;
        float cx = w * 0.53f, cy = h * 0.5f;
        float left = cx - total / 2f;

        // glifo: 12 frames (ida y vuelta) con fundido entre ellos
        int n = GLYPHS.length * 2;
        float p = (elapsed / (float) FRAME_MS) % n;
        int i = (int) p;
        float f = p - i;
        f = f * f * (3 - 2 * f);
        int a = frameGlyph(i), b = frameGlyph((i + 1) % n);
        float gx = left + iconW / 2f;
        for (int gi = 0; gi < GLYPHS.length; gi++) {
            int cp = GLYPHS[gi];
            float wgt = (cp == a ? 1 - f : 0) + (cp == b ? f : 0);
            if (wgt <= 0.001f) continue;
            Glyph g = glyph(SYM_CLASS, cp);
            if (g == null) continue;
            float scale = 0.85f + 0.15f * wgt;
            float hw = (g.x1() - g.x0()) / 2f * scale, hh = (g.y1() - g.y0()) / 2f * scale;
            quad(gx - hw, cy - hh, gx + hw, cy + hh, g.u0(), g.v0(), g.u1(), g.v1(), GLYPH, alpha * wgt);
        }
        flush(0);

        text(state, left + iconW + gap, cy + textPx * 0.35f, TEXT_CLASS, TEXT, alpha);
        flush(0);
    }

    private static int frameGlyph(int i) {
        int n = GLYPHS.length;
        return i < n ? GLYPHS[i] : GLYPHS[2 * n - 1 - i];
    }

    private void drawBars(int w, int h, float alpha, List<Bar> bars, long elapsed) {
        float bw = w * 0.34f;
        float bh = Math.max(4f, h * 0.0125f);
        float x = (w - bw) / 2f;
        float y = h * 0.67f;
        int shown = 0;
        for (Bar bar : bars) {
            if (shown++ >= 2) break;
            roundRect(x, y, bw, bh, TEXT, alpha * 0.12f);
            float x0, x1;
            if (bar.indeterminate()) {
                float s = (elapsed % 1600) / 1600f * 1.4f;
                x0 = x + bw * Math.max(0f, s - 0.4f);
                x1 = x + bw * Math.min(1f, s);
            } else {
                x0 = x;
                x1 = x + Math.max(bh, bw * Math.max(0f, Math.min(1f, bar.progress())));
            }
            if (x1 - x0 > 1f) roundRect(x0, y, x1 - x0, bh, GLYPH, alpha);
            String label = bar.label() == null ? "" : bar.label();
            float lw = measure(label, SMALL_CLASS);
            text(label, (w - lw) / 2f, y + bh + smallPx * 1.35f, SMALL_CLASS, TEXT, alpha * 0.75f);
            flush(0);
            y += h * 0.12f;
        }
    }
}
