package dev.cowboy.chunkoptimizer.screen;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

/**
 * Fallback config screen shown when Cloth Config is not installed.
 * Tells the user where to find the config file and what each major
 * setting does, with a link to documentation.
 */
public class NoClothConfigScreen extends Screen {

    private final Screen parent;

    public NoClothConfigScreen(Screen parent) {
        super(Text.literal("Cowboy's Chunk Optimizer — Config"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int centerY = this.height / 2;

        // Back button
        addDrawableChild(ButtonWidget.builder(Text.literal("Back"), btn -> {
            assert this.client != null;
            this.client.setScreen(parent);
        }).dimensions(centerX - 100, centerY + 60, 200, 20).build());

        // Open config dir button
        addDrawableChild(ButtonWidget.builder(Text.literal("Open Config Folder"), btn -> {
            try {
                java.awt.Desktop.getDesktop().open(
                    FabricLoader.getInstance().getConfigDir().toFile());
            } catch (Exception ignored) {}
        }).dimensions(centerX - 100, centerY + 85, 200, 20).build());
    }

    @Override
    public void render(net.minecraft.client.gui.DrawContext context, int mouseX, int mouseY, float delta) {
        this.renderBackground(context, mouseX, mouseY, delta);
        super.render(context, mouseX, mouseY, delta);

        int cx = this.width / 2;
        int y  = this.height / 2 - 80;
        int lineH = 12;

        context.drawCenteredTextWithShadow(textRenderer,
            Text.literal("§eCowboy's Chunk Optimizer v2"), cx, y, 0xFFFFFF);
        y += lineH * 2;

        context.drawCenteredTextWithShadow(textRenderer,
            Text.literal("§7Install §fCloth Config§7 for a visual settings screen."), cx, y, 0xFFFFFF);
        y += lineH;

        context.drawCenteredTextWithShadow(textRenderer,
            Text.literal("§7Config file: §f.minecraft/config/cowboy-chunk-optimizer.json"), cx, y, 0xFFFFFF);
        y += lineH * 2;

        String[] tips = {
            "§fKey settings:",
            "  §7uploadBudgetMs§f — max ms/tick for chunk uploads (default: 5)",
            "  §7adaptiveBudget§f — auto-scale budget based on player speed",
            "  §7nearestFirstScheduling§f — build closest chunks first",
            "  §7frustumPriorityScheduling§f — prioritise visible (in-view) chunks",
            "  §7paletteHashDedup§f — skip unchanged section rebuilds",
            "  §7earlyOcclusionCull§f — skip fully-enclosed sections",
            "  §7enableFade§f — fade new chunks in smoothly",
            "  §7showHud§f / press §fH§7 to toggle live stats overlay",
        };
        for (String tip : tips) {
            context.drawCenteredTextWithShadow(textRenderer, Text.literal(tip), cx, y, 0xFFFFFF);
            y += lineH;
        }
    }
}
