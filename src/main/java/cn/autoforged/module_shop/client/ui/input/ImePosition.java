package cn.autoforged.module_shop.client.ui.input;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import java.nio.ByteBuffer;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFWNativeWin32;
import org.lwjgl.system.JNI;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.Platform;
import org.lwjgl.system.windows.WindowsLibrary;
import org.slf4j.Logger;

/**
 * 把输入法（IME）的组字窗和候选窗放到输入框的光标处。
 *
 * <p>原版 1.21.1 从不告诉系统光标在哪，Windows 输入法只能把候选窗放在窗口左上角。
 * 这里直接调用 Windows IMM32：
 * <ul>
 *     <li>{@code ImmSetCompositionWindow}：组字（拼音）窗口锚在光标位置；</li>
 *     <li>{@code ImmSetCandidateWindow}：候选窗放在光标下方，并避开输入框所在行（CFS_EXCLUDE）。</li>
 * </ul>
 * 通过 LWJGL 自带的 JNI 调用，不需要额外依赖。只在 Windows 上生效（AtomLib 本身也只支持 Windows）；
 * 任何失败都只记一次日志并永久停用，不影响打字本身。
 *
 * <p>必须在渲染线程（即创建窗口的主线程）调用：输入法上下文是按线程归属的。
 */
public final class ImePosition {
    private static final Logger LOGGER = LogUtils.getLogger();

    private static final int CFS_POINT = 0x0002;
    private static final int CFS_FORCE_POSITION = 0x0020;
    private static final int CFS_EXCLUDE = 0x0080;

    private static boolean initialized;
    private static boolean disabled;
    private static WindowsLibrary imm32;
    private static long immGetContext;
    private static long immReleaseContext;
    private static long immSetCompositionWindow;
    private static long immSetCandidateWindow;
    private static long immSetCompositionFont;

    private static final int LOGFONT_SIZE = 92;
    private static final String FONT_FACE = "Microsoft YaHei UI";
    /** 候选窗与输入框下边框之间的间距（GUI 像素）。 */
    private static final float CANDIDATE_GAP = 2.0F;

    // 上次设置的参数（物理像素），没变化就不重复调用
    private static int[] last = new int[0];

    private ImePosition() {
    }

    /**
     * 设置输入法位置（全部为 GUI 坐标）。
     *
     * @param caretX     光标 x
     * @param lineTop    光标所在文字行的顶部 y（拼音组字窗与我们的文字对齐在这一行）
     * @param lineHeight 文字行高（组字窗字体按这个高度设置，与输入框文字一样大）
     * @param fieldX     输入框左边
     * @param fieldY     输入框顶部
     * @param fieldW     输入框宽度
     * @param fieldH     输入框高度（候选窗整体放在输入框下方，不遮挡输入框）
     */
    public static void place(float caretX, float lineTop, float lineHeight,
                             float fieldX, float fieldY, float fieldW, float fieldH) {
        if (disabled || !RenderSystem.isOnRenderThread()) {
            return;
        }
        if (!initialized && !init()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        double scale = minecraft.getWindow().getGuiScale();
        int x = px(caretX, scale);
        int y = px(lineTop, scale);
        int h = Math.max(1, px(lineHeight, scale));
        int left = px(fieldX, scale);
        int top = px(fieldY, scale);
        int right = px(fieldX + fieldW, scale);
        int bottom = px(fieldY + fieldH + CANDIDATE_GAP, scale);
        int[] now = {x, y, h, left, top, right, bottom};
        if (java.util.Arrays.equals(now, last)) {
            return;
        }
        try {
            long hwnd = GLFWNativeWin32.glfwGetWin32Window(minecraft.getWindow().getWindow());
            if (hwnd == 0L) {
                return;
            }
            long himc = JNI.invokePP(hwnd, immGetContext);
            if (himc == 0L) {
                return; // 当前没有激活输入法（英文键盘等），无需设置
            }
            try (MemoryStack stack = MemoryStack.stackPush()) {
                // 组字窗字体：与输入框文字同样高度（先设字体再设位置，部分输入法按字体高度计算候选窗位置）
                if (immSetCompositionFont != 0L) {
                    ByteBuffer font = stack.calloc(LOGFONT_SIZE);
                    font.putInt(0, -h);       // lfHeight：负数 = 字符高度（像素）
                    font.putInt(16, 400);     // lfWeight：常规
                    font.put(23, (byte) 1);   // lfCharSet：DEFAULT_CHARSET
                    font.put(26, (byte) 5);   // lfQuality：CLEARTYPE_QUALITY
                    for (int i = 0; i < FONT_FACE.length() && i < 31; i++) {
                        font.putChar(28 + i * 2, FONT_FACE.charAt(i));
                    }
                    JNI.invokePPI(himc, MemoryUtil.memAddress(font), immSetCompositionFont);
                }

                // COMPOSITIONFORM { DWORD dwStyle; POINT ptCurrentPos; RECT rcArea; } = 28 字节
                // 拼音组字窗锚在光标所在文字行的左上角，与输入框里的文字在同一行
                ByteBuffer comp = stack.calloc(28);
                comp.putInt(0, CFS_POINT | CFS_FORCE_POSITION);
                comp.putInt(4, x);
                comp.putInt(8, y);
                JNI.invokePPI(himc, MemoryUtil.memAddress(comp), immSetCompositionWindow);

                // CANDIDATEFORM { DWORD dwIndex; DWORD dwStyle; POINT ptCurrentPos; RECT rcArea; } = 32 字节
                // 候选窗放在输入框下方，并要求避开整个输入框（含下边框与间距）
                ByteBuffer cand = stack.calloc(32);
                cand.putInt(0, 0);
                cand.putInt(4, CFS_EXCLUDE);
                cand.putInt(8, x);
                cand.putInt(12, bottom);
                cand.putInt(16, left);
                cand.putInt(20, top);
                cand.putInt(24, right);
                cand.putInt(28, bottom);
                JNI.invokePPI(himc, MemoryUtil.memAddress(cand), immSetCandidateWindow);
            } finally {
                JNI.invokePPI(hwnd, himc, immReleaseContext);
            }
            last = now;
        } catch (Throwable t) {
            disable(t);
        }
    }

    /** 输入框失焦时调用：下次聚焦一定会重新设置位置。 */
    public static void reset() {
        last = new int[0];
    }

    private static int px(float gui, double scale) {
        return (int) Math.round(gui * scale);
    }

    private static boolean init() {
        initialized = true;
        if (Platform.get() != Platform.WINDOWS) {
            disabled = true;
            return false;
        }
        try {
            imm32 = new WindowsLibrary("imm32.dll");
            immGetContext = require("ImmGetContext");
            immReleaseContext = require("ImmReleaseContext");
            immSetCompositionWindow = require("ImmSetCompositionWindow");
            immSetCandidateWindow = require("ImmSetCandidateWindow");
            // 字体设置是锦上添花：缺失时只是组字窗字号不匹配
            immSetCompositionFont = imm32.getFunctionAddress("ImmSetCompositionFontW");
            return true;
        } catch (Throwable t) {
            disable(t);
            return false;
        }
    }

    private static long require(String name) {
        long address = imm32.getFunctionAddress(name);
        if (address == 0L) {
            throw new IllegalStateException("imm32.dll 缺少函数 " + name);
        }
        return address;
    }

    private static void disable(Throwable t) {
        if (!disabled) {
            disabled = true;
            LOGGER.warn("[module_shop] 输入法候选窗定位不可用，候选窗将使用系统默认位置", t);
        }
    }
}
