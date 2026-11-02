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
 * AtomLib 空壳界面：一张卡片、默认中文字体、Yoga 布局、Skija 绘制。
 * <p>
 * 开发入口是按键「打开 AtomLib 壳层（开发）」，默认 {@code F8}，只在没有界面时生效。
 * 它不走 {@link Nav}，关闭后回到游戏，不会把玩家送进商店。
 * 正式打开商店的路径仍是 {@code K} → {@link ShopScreen}。
 */
public final class AtomShopShellScreen extends AtomScreen {
    private static final float CARD_W = 320.0F;
    private static final float TEXT_W = 288.0F;
    private static final float BUTTON_W = 88.0F;
    private static final float BUTTON_H = 24.0F;

    private static final NodeId CARD = new NodeId("shell.card");
    private static final NodeId TITLE = new NodeId("shell.title");
    private static final NodeId BODY = new NodeId("shell.body");
    private static final NodeId CLOSE = new NodeId("shell.close");

    private final PointerTarget closeTarget = new CloseTarget();
    private final Map<NodeId, PointerTarget> targets = Map.of(CLOSE, this.closeTarget);
    private final Set<NodeId> interactive = Set.of(CLOSE);

    private int mouseX;
    private int mouseY;
    private String titleText = "";
    private String bodyText = "";
    private String closeText = "";

    public AtomShopShellScreen() {
        super(Component.translatable("gui.module_shop.atom_shell.title"));
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        this.mouseX = mouseX;
        this.mouseY = mouseY;
        if (this.width < 2 || this.height < 2) {
            return;
        }
        this.titleText = Component.translatable("gui.module_shop.atom_shell.title").getString();
        this.bodyText = Component.translatable("gui.module_shop.atom_shell.body").getString();
        this.closeText = Component.translatable("gui.module_shop.atom_shell.close").getString();
        int cardFill = UiTokens.cardFill();
        int titleColor = Design.isLight(cardFill) ? 0xFF8A4B12 : 0xFFE0B15E;
        int bodyColor = Design.isLight(cardFill) ? 0xFF1B1E27 : 0xFFF3F5F9;
        LayoutNode root = AtomUi.node("shell.root",
                LayoutStyle.column()
                        .size(Length.points(this.width), Length.points(this.height))
                        .align(LayoutStyle.Align.CENTER)
                        .justify(LayoutStyle.Justify.CENTER),
                List.of(AtomUi.node("shell.card",
                        LayoutStyle.column().size(Length.points(CARD_W), Length.auto()).padding(16.0F)
                                .spacing(10.0F, 0.0F).align(LayoutStyle.Align.CENTER),
                        List.of(
                                AtomUi.textLeaf("shell.title", this.titleText, AtomUi.bold(), 18.0F, titleColor, TEXT_W),
                                AtomUi.textLeaf("shell.body", this.bodyText, AtomUi.regular(), 14.0F, bodyColor, TEXT_W),
                                AtomUi.fixed("shell.close", BUTTON_W, BUTTON_H)))));
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
                    AtomUi.drawText(canvas, this.titleText, AtomUi.bold(), 18.0F, titleColor, box.content().x(),
                            box.content().y(), box.content().width());
                } else if (BODY.equals(id)) {
                    AtomUi.drawText(canvas, this.bodyText, AtomUi.regular(), 14.0F, bodyColor, box.content().x(),
                            box.content().y(), box.content().width());
                } else if (CLOSE.equals(id)) {
                    boolean hover = rect.contains(this.mouseX, this.mouseY);
                    int fill = UiTokens.accentFill();
                    UiCards.drawCard(canvas, rect.x(), rect.y(), rect.width(), rect.height(), UiTokens.buttonRadius(),
                            hover ? 1.0F : 0.0F, 0x55000000, 6.0F, fill);
                    AtomUi.drawTextCentered(canvas, this.closeText, AtomUi.bold(), 14.0F, UiTokens.onAccent(fill),
                            rect);
                }
            }
        });
    }

    private final class CloseTarget implements PointerTarget {
        @Override
        public boolean press(PointerEvent event) {
            Bricks.click();
            AtomShopShellScreen.this.onClose();
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
