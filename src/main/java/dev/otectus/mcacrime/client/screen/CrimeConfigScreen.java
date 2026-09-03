package dev.otectus.mcacrime.client.screen;

import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.client.hud.HudAnchor;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.ArrayList;
import java.util.List;

/**
 * The in-game settings screen, reached from Mods → MCA: Crime → Config.
 *
 * <p>It covers the client options only, and that boundary is the point. Every option here is about
 * what <em>this</em> player sees — nameplates, the HUD, whether hostile actions ask before they fire.
 * None of it changes what the server allows, so it is safe to expose in a screen that a client can
 * open on any server. The common settings that decide fines, sentences and capture rules stay in the
 * server's TOML, where they belong and where a client cannot reach them.
 *
 * <p>Values are written straight through to the Forge config and saved on close. There is no separate
 * "apply" step because there is nothing to apply: each of these is read live by the thing that draws
 * with it, so a toggle takes effect on the next frame.
 */
public final class CrimeConfigScreen extends Screen {

    private static final int PANEL_W = 260;
    private static final int ROW_H = 22;
    private static final int HEADER_H = 30;
    private static final int FOOTER_H = 28;

    private final Screen parent;
    private final List<Button> options = new ArrayList<>();

    private int panelLeft;
    private int panelTop;
    private int panelHeight;

    public CrimeConfigScreen(Screen parent) {
        super(Component.translatable("gui.mcacrime.config.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        options.clear();
        McaCrimeConfig.Client c = McaCrimeConfig.CLIENT;

        // Laid out first so the panel can be sized to exactly the rows it has, rather than to a
        // constant that drifts every time an option is added or removed.
        List<Runnable> builders = new ArrayList<>();
        builders.add(() -> addToggle("gui.mcacrime.config.name_color", c.nameColorEnabled));
        builders.add(() -> addToggle("gui.mcacrime.config.player_card", c.showPlayerCardButton));
        builders.add(() -> addToggle("gui.mcacrime.config.confirm_hostile", c.confirmHostileActions));
        builders.add(() -> addToggle("gui.mcacrime.config.captive_screen", c.captiveScreenToggle));
        builders.add(() -> addToggle("gui.mcacrime.config.hud", c.hudEnabled));
        builders.add(() -> addToggle("gui.mcacrime.config.hud_channel", c.hudChannelBar));
        builders.add(() -> addToggle("gui.mcacrime.config.hud_status", c.hudStatusIndicator));
        builders.add(() -> addToggle("gui.mcacrime.config.hud_custody", c.hudCustodyIndicator));
        builders.add(this::addAnchorCycle);

        panelHeight = HEADER_H + builders.size() * ROW_H + FOOTER_H;
        panelHeight = Math.min(panelHeight, Math.max(120, height - 20));
        panelLeft = (width - PANEL_W) / 2;
        panelTop = (height - panelHeight) / 2;

        builders.forEach(Runnable::run);

        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose())
                .bounds(panelLeft + 6, panelTop + panelHeight - 24, PANEL_W - 12, 20).build());
    }

    /** A boolean row that reads its current value each time it is redrawn, not once at build time. */
    private void addToggle(String labelKey, ModConfigSpec.BooleanValue value) {
        int index = options.size();
        Button button = Button.builder(label(labelKey, value.get()), b -> {
                    value.set(!value.get());
                    b.setMessage(label(labelKey, value.get()));
                })
                .bounds(panelLeft + 8, panelTop + HEADER_H + index * ROW_H, PANEL_W - 16, 20)
                .build();
        options.add(button);
        addRenderableWidget(button);
    }

    /** The anchor is a small enum, so it cycles rather than opening a second screen to choose from. */
    private void addAnchorCycle() {
        int index = options.size();
        ModConfigSpec.EnumValue<HudAnchor> value = McaCrimeConfig.CLIENT.hudAnchor;
        Button button = Button.builder(anchorLabel(value.get()), b -> {
                    HudAnchor[] all = HudAnchor.values();
                    HudAnchor next = all[(value.get().ordinal() + 1) % all.length];
                    value.set(next);
                    b.setMessage(anchorLabel(next));
                })
                .bounds(panelLeft + 8, panelTop + HEADER_H + index * ROW_H, PANEL_W - 16, 20)
                .build();
        options.add(button);
        addRenderableWidget(button);
    }

    private static Component label(String labelKey, boolean on) {
        return Component.translatable(labelKey).append(": ")
                .append(Component.translatable(on ? "options.on" : "options.off"));
    }

    private static Component anchorLabel(HudAnchor anchor) {
        return Component.translatable("gui.mcacrime.config.hud_anchor").append(": ")
                .append(Component.translatable(anchor.labelKey()));
    }

    @Override
    public void onClose() {
        // Written once, on the way out. Saving per click would rewrite the TOML on every toggle.
        McaCrimeConfig.CLIENT_SPEC.save();
        if (minecraft != null) {
            minecraft.setScreen(parent);
        }
    }

    /**
     * The panel and its headings.
     *
     * <p>Drawn from {@code renderBackground} rather than from {@code render}: 1.21.1's
     * {@code Screen.render} calls {@code renderBackground} itself, so chrome drawn in {@code render}
     * before {@code super.render} would be painted over by the blur and the menu background.
     */
    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(graphics, mouseX, mouseY, partialTick);
        CrimeSprites.panel(graphics, panelLeft, panelTop, PANEL_W, panelHeight);
        graphics.drawString(font, title, panelLeft + 8, panelTop + 8, PanelColours.TEXT, false);
        graphics.drawString(font, Component.translatable("gui.mcacrime.config.client_only"),
                panelLeft + 8, panelTop + 19, PanelColours.TEXT_DISABLED, false);
    }
}
