package cn.autoforged.module_shop.client.atom;

import com.atom.lib.AtomLibRuntime;
import com.atom.lib.RuntimeHandle;
import com.atom.lib.client.render.BorrowedFramePainter;
import com.atom.lib.client.render.MinecraftRenderBridge;
import com.atom.lib.font.FontId;
import com.atom.lib.font.FontRegistry;
import com.atom.lib.geometry.Rect;
import com.atom.lib.geometry.Viewport;
import com.atom.lib.input.HitTester;
import com.atom.lib.input.InputDispatcher;
import com.atom.lib.input.PointerTarget;
import com.atom.lib.input.ScreenInputAdapter;
import com.atom.lib.layout.Constraints;
import com.atom.lib.layout.LayoutNode;
import com.atom.lib.layout.LayoutSnapshot;
import com.atom.lib.layout.LayoutStyle;
import com.atom.lib.layout.LayoutTree;
import com.atom.lib.layout.LeafMeasure;
import com.atom.lib.layout.Length;
import com.atom.lib.layout.MeasuredSize;
import com.atom.lib.layout.NodeId;
import com.atom.lib.layout.PresentationFrame;
import com.atom.lib.layout.internal.YogaLayoutEngine;
import com.atom.lib.text.TextLayout;
import com.atom.lib.text.TextRequest;
import com.atom.lib.text.TextService;
import io.github.humbleui.skija.Canvas;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;

/**
 * 客户端会话级 AtomLib 运行时。
 * <p>
 * 屏幕打开时在渲染线程创建，整个会话复用；屏幕关闭只结束手势，不关闭这里，
 * 也不关闭 {@link MinecraftRenderBridge}（AtomLib 在客户端退出时自己释放桥）。
 * Skija / Yoga 由 AtomLib 的 Jar-in-Jar 提供，本模组不打包它们。
 */
public final class AtomUi {
    private static RuntimeHandle runtime;
    private static FontRegistry fonts;
    private static TextService textService;
    private static YogaLayoutEngine yogaEngine;

    private AtomUi() {
    }

    /** 渲染线程上打开运行时，并注册 AtomLib 自带的简体中文字体。 */
    public static void ensureOpen() {
        if (runtime != null && !runtime.isClosed()) {
            return;
        }
        RuntimeHandle opened = AtomLibRuntime.open("module_shop");
        FontRegistry nextFonts = null;
        TextService nextText = null;
        YogaLayoutEngine nextYoga = null;
        try {
            nextFonts = new FontRegistry(opened);
            nextFonts.registerDefaults();
            nextText = new TextService(nextFonts);
            nextYoga = new YogaLayoutEngine();
            opened.own(nextText);
            opened.own(nextYoga);
            fonts = nextFonts;
            textService = nextText;
            yogaEngine = nextYoga;
            runtime = opened;
        } catch (RuntimeException | Error failure) {
            fonts = null;
            textService = null;
            yogaEngine = null;
            runtime = null;
            opened.close();
            throw failure;
        }
    }

    /** 退出世界时关掉本会话的字体、排版和 Yoga。不碰共享渲染桥。 */
    public static void close() {
        RuntimeHandle open = runtime;
        runtime = null;
        fonts = null;
        textService = null;
        yogaEngine = null;
        if (open != null && !open.isClosed()) {
            open.close();
        }
    }

    public static FontId regular() {
        ensureOpen();
        return FontRegistry.DEFAULT_REGULAR;
    }

    public static FontId bold() {
        ensureOpen();
        return FontRegistry.DEFAULT_BOLD;
    }

    public static TextService text() {
        ensureOpen();
        return textService;
    }

    /**
     * GUI 像素到帧缓冲像素的比例。布局和命中都用 GUI 坐标；
     * beta3 的渲染桥画布是物理像素，绘制时只缩放画布这一次，输入适配器不再乘。
     */
    public static float pixelsPerUnit() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.getWindow() == null) {
            return 1.0F;
        }
        double guiWidth = Math.max(1.0D, minecraft.getWindow().getGuiScaledWidth());
        float scale = (float) (minecraft.getWindow().getScreenWidth() / guiWidth);
        if (!Float.isFinite(scale) || scale <= 0.01F) {
            return 1.0F;
        }
        return scale;
    }

    public static LayoutNode node(String id, LayoutStyle style, List<LayoutNode> children) {
        return new LayoutNode(new NodeId(id), style, children, null, 0L);
    }

    /** 固定尺寸的叶子，用来做按钮热区。 */
    public static LayoutNode fixed(String id, float width, float height) {
        return LayoutNode.leaf(id, LayoutStyle.row().size(Length.points(width), Length.points(height)),
                constraints -> new MeasuredSize(width, height, height), 1L);
    }

    /** 按给定宽度测量并换行的文本叶子。空字符串占一行高。 */
    public static LayoutNode textLeaf(String id, String value, FontId font, float size, int color, float width) {
        String safe = value == null ? "" : value;
        long revision = ((long) safe.hashCode() << 32) ^ (safe.length() * 31L) ^ Float.floatToRawIntBits(size);
        LeafMeasure measure = constraints -> measureText(safe, font, size, color, width, constraints);
        return LayoutNode.leaf(id, LayoutStyle.column().size(Length.points(width), Length.auto()), measure, revision);
    }

    private static MeasuredSize measureText(String value, FontId font, float size, int color, float width,
            Constraints constraints) {
        if (value.isEmpty()) {
            return new MeasuredSize(1.0F, size, size);
        }
        float max = width;
        Constraints.Axis axis = constraints.width();
        if (axis.mode() != Constraints.Mode.UNDEFINED) {
            max = Math.min(width, Math.max(1.0F, axis.value()));
        }
        TextLayout laid = text().layout(
                new TextRequest(value, font, size, color),
                new Constraints(Constraints.Axis.atMost(Math.max(1.0F, max)), Constraints.Axis.undefined()));
        MeasuredSize measured = laid.size();
        return new MeasuredSize(Math.max(1.0F, measured.width()), Math.max(1.0F, measured.height()),
                measured.baseline());
    }

    public static PresentationFrame present(LayoutNode root, float width, float height) {
        ensureOpen();
        float safeWidth = Math.max(1.0F, width);
        float safeHeight = Math.max(1.0F, height);
        Viewport viewport = new Viewport(safeWidth, safeHeight, pixelsPerUnit());
        LayoutTree tree = new LayoutTree(root);
        LayoutSnapshot snapshot = yogaEngine.layout(tree, viewport);
        return PresentationFrame.create(tree, snapshot, viewport, Map.of(), new PresentationFrame.Offset(0.0F, 0.0F));
    }

    /** 在 GUI 坐标系里画一帧。画布按 {@link #pixelsPerUnit()} 缩放一次。 */
    public static void draw(BorrowedFramePainter painter) {
        ensureOpen();
        float scale = pixelsPerUnit();
        MinecraftRenderBridge.get().drawFrame((canvas, borrowed) -> {
            canvas.save();
            try {
                canvas.scale(scale, scale);
                painter.paint(canvas, borrowed);
            } finally {
                canvas.restore();
            }
        });
    }

    public static void drawText(Canvas canvas, String value, FontId font, float size, int color, float x, float y,
            float maxWidth) {
        if (value == null || value.isEmpty() || maxWidth <= 0.0F) {
            return;
        }
        TextLayout laid = text().layout(
                new TextRequest(value, font, size, color),
                new Constraints(Constraints.Axis.atMost(maxWidth), Constraints.Axis.undefined()));
        text().draw(canvas, laid, x, y);
    }

    public static void drawTextCentered(Canvas canvas, String value, FontId font, float size, int color, Rect rect) {
        if (value == null || value.isEmpty() || rect.width() <= 1.0F || rect.height() <= 1.0F) {
            return;
        }
        float max = Math.max(1.0F, rect.width() - 8.0F);
        TextLayout laid = text().layout(
                new TextRequest(value, font, size, color, 1, true),
                new Constraints(Constraints.Axis.atMost(max), Constraints.Axis.undefined()));
        float x = rect.x() + (rect.width() - laid.size().width()) / 2.0F;
        float y = rect.y() + (rect.height() - laid.size().height()) / 2.0F;
        text().draw(canvas, laid, x, y);
    }

    /**
     * 一个屏幕一份的指针适配器。坐标保持 Minecraft GUI 空间，不额外缩放。
     * {@link #removed()} 取消未完成的手势，不会把它当成一次松开。
     */
    public static final class Pointers {
        public final InputDispatcher dispatcher;
        public final ScreenInputAdapter input;
        private PresentationFrame frame;
        private Set<NodeId> interactive = Set.of();

        public Pointers() {
            HitTester hits = (x, y) -> {
                if (this.frame == null || this.interactive.isEmpty()) {
                    return null;
                }
                return this.frame.topAt(this.interactive, x, y);
            };
            this.dispatcher = new InputDispatcher(hits);
            this.input = new ScreenInputAdapter(this.dispatcher);
        }

        public void use(PresentationFrame next, Set<NodeId> hitNodes, Map<NodeId, ? extends PointerTarget> targets) {
            this.frame = next;
            this.interactive = hitNodes == null ? Set.of() : hitNodes;
            this.dispatcher.setTargets(targets == null ? Map.of() : targets);
        }

        public void removed() {
            this.input.removed();
            this.frame = null;
        }
    }
}
