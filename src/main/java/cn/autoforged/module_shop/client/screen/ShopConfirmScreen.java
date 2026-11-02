package cn.autoforged.module_shop.client.screen;

import cn.autoforged.module_shop.client.atom.AtomUi;
import com.atom.lib.design.atomchat.Design;
import com.atom.lib.design.atomchat.UiCards;
import com.atom.lib.design.atomchat.UiTokens;
import com.atom.lib.geometry.Rect;
import com.atom.lib.input.PointerEvent;
import com.atom.lib.input.PointerTarget;
import com.atom.lib.layout.LayoutBox;
import com.atom.lib.layout.LayoutNode;
import com.atom.lib.layout.LayoutStyle;
import com.atom.lib.layout.Length;
import com.atom.lib.layout.NodeId;
import com.atom.lib.layout.PresentationFrame;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/**
 * 商店风格的确认框（AtomLib 绘制，行为与原来的确认框一致）。
 * <p>
 * 点「是 / 否」仍走 {@link Bricks#click()}。确定回调 {@code true} 后 {@link Nav#back()}；
 * 取消、ESC、{@link #onClose()} 回调 {@code false} 后同样返回上一级。
 * 按钮文字传 {@code null} 时用 {@code gui.module_shop.yes} / {@code gui.module_shop.no}。
 */
public class ShopConfirmScreen extends AtomScreen {
    /** 结果回调：true = 确定，false = 取消 / ESC。 */
    public interface Callback {
        void accept(boolean confirmed);
    }

    private static final float CARD_W = 280.0F;
    private static final float TEXT_W = 248.0F;
    private static final float BUTTON_W = 80.0F;
    private static final float BUTTON_H = 22.0F;

    private static final NodeId CARD = new NodeId("confirm.card");
    private static final NodeId TITLE = new NodeId("confirm.title");
    private static final NodeId MESSAGE = new NodeId("confirm.message");
    private static final NodeId YES = new NodeId("confirm.yes");
    private static final NodeId NO = new NodeId("confirm.no");

    private final Component title;
    private final Component message;
    private final Component yesLabel;
    private final Component noLabel;
    private final Callback callback;
    private final PointerTarget yesTarget = new Choice(true);
    private final PointerTarget noTarget = new Choice(false);
    private final Map<NodeId, PointerTarget> targets = Map.of(YES, this.yesTarget, NO, this.noTarget);
    private final Set<NodeId> interactive = Set.of(YES, NO);

    private boolean finished;
    private int mouseX;
    private int mouseY;
    private String titleText = "";
    private String messageText = "";
    private String yesText = "";
    private String noText = "";

    public ShopConfirmScreen(Component title, Component message, Callback callback) {
        this(title, message, null, null, callback);
    }

    /**
     * 自定义两个按钮的文字（例如求购详情里的「交付 / 取消」）。
     * 传 {@code null} 就用默认的「是 / 否」。
     */
    public ShopConfirmScreen(Component title, Component message, Component yesLabel, Component noLabel,
            Callback callback) {
        super(title);
        this.title = title;
        this.message = message;
        this.yesLabel = yesLabel;
        this.noLabel = noLabel;
        this.callback = callback;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        finish(false);
    }

    /** 结算并回到上一级界面。只结算一次，避免关闭过程里再回退一层。 */
    private void finish(boolean confirmed) {
        if (this.finished) {
            return;
        }
        this.finished = true;
        if (this.callback != null) {
            this.callback.accept(confirmed);
        }
        Nav.back();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        this.mouseX = mouseX;
        this.mouseY = mouseY;
        if (this.width < 2 || this.height < 2 || this.finished) {
            return;
        }
        this.titleText = this.title == null ? "" : this.title.getString();
        this.messageText = this.message == null ? "" : this.message.getString();
        this.yesText = (this.yesLabel == null ? Component.translatable("gui.module_shop.yes") : this.yesLabel)
                .getString();
        this.noText = (this.noLabel == null ? Component.translatable("gui.module_shop.no") : this.noLabel)
                .getString();
        int cardFill = UiTokens.cardFill();
        int titleColor = Design.isLight(cardFill) ? 0xFF8A4B12 : 0xFFE0B15E;
        int bodyColor = Design.isLight(cardFill) ? 0xFF1B1E27 : 0xFFF3F5F9;
        LayoutNode buttons = AtomUi.node("confirm.buttons",
                LayoutStyle.row().spacing(12.0F, 0.0F).justify(LayoutStyle.Justify.CENTER)
                        .align(LayoutStyle.Align.CENTER),
                List.of(AtomUi.fixed("confirm.yes", BUTTON_W, BUTTON_H),
                        AtomUi.fixed("confirm.no", BUTTON_W, BUTTON_H)));
        LayoutNode card = AtomUi.node("confirm.card",
                LayoutStyle.column().size(Length.points(CARD_W), Length.auto()).padding(16.0F).spacing(8.0F, 0.0F)
                        .align(LayoutStyle.Align.CENTER),
                List.of(
                        AtomUi.textLeaf("confirm.title", this.titleText, AtomUi.bold(), 16.0F, titleColor, TEXT_W),
                        AtomUi.textLeaf("confirm.message", this.messageText, AtomUi.regular(), 13.0F, bodyColor,
                                TEXT_W),
                        buttons));
        LayoutNode root = AtomUi.node("confirm.root",
                LayoutStyle.column().size(Length.points(this.width), Length.points(this.height))
                        .align(LayoutStyle.Align.CENTER).justify(LayoutStyle.Justify.CENTER),
                List.of(card));
        PresentationFrame frame = AtomUi.present(root, this.width, this.height);
        this.pointers.use(frame, this.interactive, this.targets);
        paintAtom(graphics, (canvas, borrowed) -> {
            for (NodeId id : frame.paintOrder()) {
                LayoutBox box = frame.box(id);
                if (box == null) {
                    continue;
                }
                Rect rect = box.border();
                if (CARD.equals(id)) {
                    UiCards.drawCard(canvas, rect.x(), rect.y(), rect.width(), rect.height(), UiTokens.cardRadius(),
                            0.0F);
                } else if (TITLE.equals(id)) {
                    AtomUi.drawText(canvas, this.titleText, AtomUi.bold(), 16.0F, titleColor, box.content().x(),
                            box.content().y(), box.content().width());
                } else if (MESSAGE.equals(id)) {
                    AtomUi.drawText(canvas, this.messageText, AtomUi.regular(), 13.0F, bodyColor, box.content().x(),
                            box.content().y(), box.content().width());
                } else if (YES.equals(id) || NO.equals(id)) {
                    boolean yes = YES.equals(id);
                    boolean hover = rect.contains(this.mouseX, this.mouseY);
                    int fill = yes ? UiTokens.accentFill() : 0xFF5A6270;
                    UiCards.drawCard(canvas, rect.x(), rect.y(), rect.width(), rect.height(), UiTokens.buttonRadius(),
                            hover ? 1.0F : 0.0F, 0x55000000, 6.0F, fill);
                    AtomUi.drawTextCentered(canvas, yes ? this.yesText : this.noText, AtomUi.bold(), 13.0F,
                            UiTokens.onAccent(fill), rect);
                }
            }
        });
    }

    private final class Choice implements PointerTarget {
        private final boolean confirm;

        private Choice(boolean confirm) {
            this.confirm = confirm;
        }

        @Override
        public boolean press(PointerEvent event) {
            Bricks.click();
            finish(this.confirm);
            return true;
        }

        @Override
        public void drag(PointerEvent event) {
        }

        @Override
        public void release(PointerEvent event) {
        }

        @Override
        public void cancel() {
        }
    }
}
