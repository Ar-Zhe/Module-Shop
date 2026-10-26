package cn.autoforged.server_shop_mod_1789689358.client.screen;

import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

public class IOEditBox extends EditBox {
   private static final int PAD = 6;
   private final boolean centered;

   public IOEditBox(Font font, int x, int y, int w, int h, Component hint, boolean centered) {
      super(font, x, y, w, h, hint);
      this.centered = centered;
      this.setBordered(false);
   }

   public void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
      if (this.isVisible()) {
         Font font = Minecraft.getInstance().font;
         String value = this.getValue();
         int color = IOStyle.TEXT;
         int textY = this.getY() + (this.getHeight() - 9) / 2 + 1;
         int textX;
         if (this.centered) {
            textX = this.getX() + (this.getWidth() - font.width(value)) / 2;
         } else {
            textX = this.getX() + 6;
         }

         if (!value.isEmpty()) {
            g.drawString(font, value, textX, textY, color, false);
         }

         if (this.isFocused() && Util.getMillis() / 480L % 2L == 0L) {
            int caretX = textX + font.width(value) + 1;
            g.fill(caretX, textY - 1, caretX + 1, textY + 9, IOStyle.TEXT);
         }
      }
   }
}
