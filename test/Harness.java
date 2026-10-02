package pondering.loading;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.ByteBuffer;
import java.util.List;
import javax.imageio.ImageIO;
import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL32C;

/** Visual test without Minecraft: opens an 854x480 window, draws the Scene and saves PNG captures. */
public class Harness {
    public static void main(String[] args) throws Exception {
        String out = args.length > 0 ? args[0] : ".";
        int w = 854, h = 480;
        if (!GLFW.glfwInit()) throw new IllegalStateException("glfwInit");
        GLFW.glfwDefaultWindowHints();
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 2);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_FORWARD_COMPAT, 1);
        GLFW.glfwWindowHint(GLFW.GLFW_RESIZABLE, 0);
        long win = GLFW.glfwCreateWindow(w, h, "Pondering harness", 0, 0);
        GLFW.glfwMakeContextCurrent(win);
        GL.createCapabilities();
        GL32C.glViewport(0, 0, w, h);

        Scene scene = new Scene();
        long start = System.currentTimeMillis();
        int shot = 0;
        boolean gif = args.length > 1 && args[1].equals("gif");
        long[] at = gif ? new long[90] : new long[]{500, 1100, 2300, 4500};
        if (gif) for (int i = 0; i < at.length; i++) at[i] = 300 + i * 100L;
        while (shot < at.length) {
            long t = System.currentTimeMillis() - start;
            float p = Math.min(1f, t / (gif ? 9500f : 5000f));
            GL32C.glClearColor(Scene.BG[0] / 255f, Scene.BG[1] / 255f, Scene.BG[2] / 255f, 1f);
            GL32C.glClear(GL32C.GL_COLOR_BUFFER_BIT);
            scene.draw(w, h, 1f, List.of(
                new Scene.Bar(p, false, "Launching minecraft"),
                new Scene.Bar(0, true, "Loading mods")));
            if (t >= at[shot]) {
                ByteBuffer buf = BufferUtils.createByteBuffer(w * h * 4);
                GL32C.glReadPixels(0, 0, w, h, GL32C.GL_RGBA, GL32C.GL_UNSIGNED_BYTE, buf);
                BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
                for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
                    int i = (y * w + x) * 4;
                    img.setRGB(x, y, (buf.get(i) & 0xFF) << 16 | (buf.get(i + 1) & 0xFF) << 8 | (buf.get(i + 2) & 0xFF));
                }
                ImageIO.write(img, "png", new File(out, (gif ? "f" + String.format("%03d", shot) : "shot" + shot) + ".png"));
                shot++;
            }
            GLFW.glfwSwapBuffers(win);
            GLFW.glfwPollEvents();
            Thread.sleep(gif ? 20 : 50);
        }
        GLFW.glfwTerminate();
    }
}
