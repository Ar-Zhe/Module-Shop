package cn.autoforged.server_shop_mod_1789689358.client.screen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Button.OnPress;
import net.minecraft.network.chat.Component;

public class IOSButton extends Button {
   private final IOStyle.Btn style;

   public IOSButton(int x, int y, int width, int height, Component message, OnPress onPress, IOStyle.Btn style) {
      super(x, y, width, height, message, onPress, DEFAULT_NARRATION);
      this.style = style;
   }

   public static IOSButton.Builder builder(Component message, OnPress onPress, IOStyle.Btn style) {
      return new IOSButton.Builder(message, onPress, style);
   }

   protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
      IOStyle.button(
         g,
         this.getX(),
         this.getY(),
         this.getWidth(),
         this.getHeight(),
         this.getMessage(),
         Minecraft.getInstance().font,
         (double)mouseX,
         (double)mouseY,
         this.isActive(),
         this.style
      );
   }

   public static class Builder {
      private final Component message;
      private final OnPress onPress;
      private final IOStyle.Btn style;
      private int x;
      private int y;
      private int width = 90;
      private int height = 20;

      Builder(Component message, OnPress onPress, IOStyle.Btn style) {
         this.message = message;
         this.onPress = onPress;
         this.style = style;
      }

      public IOSButton.Builder pos(int x, int y) {
         this.x = x;
         this.y = y;
         return this;
      }

      public IOSButton.Builder size(int width, int height) {
         this.width = width;
         this.height = height;
         return this;
      }

      public IOSButton.Builder bounds(int x, int y, int width, int height) {
         return this.pos(x, y).size(width, height);
      }

      public IOSButton build() {
         return new IOSButton(this.x, this.y, this.width, this.height, this.message, this.onPress, this.style);
      }
   }
}
