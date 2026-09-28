package dev.otectus.mcacrime.justiceclient;

import dev.otectus.mcacrime.client.screen.PlayerReportScreen;
import dev.otectus.mcacrime.client.screen.PlayerReportsScreen;
import dev.otectus.mcacrime.network.PlayerReportsS2CPacket;
import dev.otectus.mcacrime.network.ReportMenuS2CPacket;
import dev.otectus.mcacrime.news.CrimeNewsData;
import dev.otectus.mcacrime.news.CrimeNewsService;
import dev.otectus.mcacrime.report.PlayerCrimeReportService;
import dev.otectus.mcacrime.report.PlayerReportStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.AccessibilityOnboardingScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Difficulty;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;
import org.lwjgl.glfw.GLFW;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Disposable real-client validation for the private report UI and MCA's native letter screen. */
@Mod("crime_justice_client_checks")
public final class JusticeClientRuntimeChecks {
    private static final List<String> SHOTS = List.of(
            "01-report-picker.png", "02-report-picker-selected.png", "03-report-statuses.png",
            "04-native-letter-page-1.png", "05-native-letter-page-2.png", "06-native-letter-page-4.png");
    private final Minecraft mc = Minecraft.getInstance();
    private final Path output = Path.of(System.getProperty("crime.justiceClient.output"));
    private final long started = System.currentTimeMillis();
    private final List<String> navigation = new ArrayList<>();
    private int ticks, stage, entered;
    private boolean creating, finished;
    private Screen letterScreen;

    public JusticeClientRuntimeChecks() { MinecraftForge.EVENT_BUS.register(this); }

    @SubscribeEvent
    public void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || finished) return;
        ticks++;
        try {
            if (System.currentTimeMillis() - started > 240_000) throw new AssertionError("Client fixture timed out");
            if (!creating && (mc.screen instanceof TitleScreen || mc.screen instanceof AccessibilityOnboardingScreen)) {
                creating = true;
                mc.options.pauseOnLostFocus = false;
                mc.options.renderDistance().set(2);
                mc.options.guiScale().set(2);
                mc.getTutorial().setStep(net.minecraft.client.tutorial.TutorialSteps.NONE);
                mc.resizeDisplay();
                mc.createWorldOpenFlows().createFreshLevel("justice-client-checks", new LevelSettings("Justice client checks",
                        GameType.CREATIVE, false, Difficulty.PEACEFUL, true, new GameRules(), WorldDataConfiguration.DEFAULT),
                        new WorldOptions(73L, false, false), registry -> registry.registryOrThrow(Registries.WORLD_PRESET)
                                .getOrThrow(WorldPresets.FLAT).createWorldDimensions());
            }
            if (mc.level == null || mc.player == null || ticks - entered < 12) return;
            switch (stage) {
                case 0 -> { mc.setScreen(reportPicker()); next(); }
                case 1 -> {
                    require(mc.screen instanceof PlayerReportScreen, "report picker did not open");
                    shot(SHOTS.get(0));
                    keyboardSelectFirst(mc.screen);
                    next();
                }
                case 2 -> {
                    require(activeButton(mc.screen, "Report"), "keyboard selection did not enable Report");
                    shot(SHOTS.get(1));
                    mc.setScreen(new PlayerReportsScreen(new PlayerReportsS2CPacket(statusRows())));
                    next();
                }
                case 3 -> {
                    require(mc.screen instanceof PlayerReportsScreen, "report status screen did not open");
                    shot(SHOTS.get(2));
                    letterScreen = nativeLetterScreen();
                    mc.setScreen(letterScreen);
                    next();
                }
                case 4 -> {
                    require(mc.screen == letterScreen, "native MCA letter screen did not open");
                    require(pageIndex(letterScreen) == 0, "native letter did not start on page 1");
                    shot(SHOTS.get(3));
                    require(letterScreen.keyPressed(GLFW.GLFW_KEY_PAGE_DOWN, 0, 0), "Page Down was not handled");
                    require(pageIndex(letterScreen) == 1, "Page Down did not open page 2");
                    navigation.add("native PageDown: page 1 -> page 2");
                    next();
                }
                case 5 -> {
                    shot(SHOTS.get(4));
                    require(letterScreen.keyPressed(GLFW.GLFW_KEY_PAGE_DOWN, 0, 0), "second Page Down was not handled");
                    require(letterScreen.keyPressed(GLFW.GLFW_KEY_PAGE_DOWN, 0, 0), "third Page Down was not handled");
                    require(pageIndex(letterScreen) == 3, "Page Down order did not reach page 4");
                    navigation.add("native PageDown x2: page 2 -> page 4");
                    next();
                }
                case 6 -> { shot(SHOTS.get(5)); next(); }
                case 7 -> finish();
                default -> throw new AssertionError("unexpected stage " + stage);
            }
        } catch (Throwable failure) { fail(failure); }
    }

    private PlayerReportScreen reportPicker() {
        long now = 240_000L;
        var menu = new PlayerCrimeReportService.Menu(UUID.randomUUID(), 9, UUID.randomUUID(), now,
                List.of(
                        choice("Lady Alexandriana von Oakridge the Third", "Oakridge northern market district", now - 320, PlayerReportStatus.ELIGIBLE, true),
                        choice("Unidentified thief", "Wilderness road near the old bridge", now - 1_180, PlayerReportStatus.ELIGIBLE, false),
                        choice("Mara", "Oakridge", now - 2_400, PlayerReportStatus.ELIGIBLE, true),
                        choice("The Highwayman Wearing a Weathered Crimson Scarf", "South gate", now - 8_000, PlayerReportStatus.ELIGIBLE, true),
                        choice("Unidentified thief", "Riverside", now - 12_000, PlayerReportStatus.ELIGIBLE, false),
                        choice("Second Page Suspect", "Hill village", now - 18_000, PlayerReportStatus.ELIGIBLE, true)), null);
        return new PlayerReportScreen(new ReportMenuS2CPacket(menu), null);
    }

    private List<PlayerCrimeReportService.Choice> statusRows() {
        List<PlayerCrimeReportService.Choice> rows = new ArrayList<>();
        PlayerReportStatus[] statuses = PlayerReportStatus.values();
        for (int i = 0; i < statuses.length; i++) {
            String name = i == 1 ? "Unidentified thief" : i == 5
                    ? "Alexandriana the Remarkably Long-Named Suspect" : "Suspect " + (i + 1);
            rows.add(choice(name, "Oakridge", 230_000L - i * 600L, statuses[i], i != 1));
        }
        return rows;
    }

    private static PlayerCrimeReportService.Choice choice(String name, String area, long at,
                                                            PlayerReportStatus status, boolean identified) {
        return new PlayerCrimeReportService.Choice(UUID.randomUUID(), UUID.randomUUID(),
                identified ? UUID.randomUUID() : null, Component.literal(name),
                new ResourceLocation("mcacrime", identified ? "mugging" : "attempted_mugging"),
                Component.literal(area), at, status);
    }

    private void keyboardSelectFirst(Screen screen) {
        boolean tabResult = screen.keyPressed(GLFW.GLFW_KEY_TAB, 0, 0);
        GuiEventListener focused = screen.getFocused();
        require(focused instanceof Button, "Tab did not focus a report row");
        String label = ((Button) focused).getMessage().getString();
        require(label.contains("Alexandriana"), "first keyboard focus was not the first case: " + label);
        boolean enterResult = screen.keyPressed(GLFW.GLFW_KEY_ENTER, 0, 0);
        navigation.add("report picker Tab focus: " + label + " (key result " + tabResult + ")");
        navigation.add("report picker Enter: selected first case and enabled Report");
        navigation.add("report picker Enter key result: " + enterResult);
    }

    private static boolean activeButton(Screen screen, String label) {
        if (screen == null) return false;
        for (GuiEventListener child : screen.children()) {
            if (child instanceof Button button && button.getMessage().getString().equals(label)) return button.active;
        }
        return false;
    }

    private Screen nativeLetterScreen() throws Exception {
        Item item = ForgeRegistries.ITEMS.getValue(new ResourceLocation("mca", "letter"));
        require(item != null, "mca:letter is not registered");
        ItemStack stack = new ItemStack(item);
        UUID recipient = UUID.randomUUID();
        long now = 41L * 24_000L;
        String[] kinds = {"reported", "arrested", "recovered", "served", "reported", "arrested", "recovered", "served"};
        List<CrimeNewsData.Fact> facts = new ArrayList<>();
        for (int i = 0; i < kinds.length; i++) {
            facts.add(new CrimeNewsData.Fact(UUID.randomUUID(), UUID.randomUUID(), recipient, "",
                    kinds[i], now - (i + 1L) * 200L, i + 1L));
        }
        CompoundTag tag = CrimeNewsService.compose(null, facts, now);
        require(tag.getList("pages", net.minecraft.nbt.Tag.TAG_STRING).size() == 4,
                "production news composition did not produce four pages for eight stories");
        tag.putString("mcacrime:origin", "mcacrime");
        tag.putUUID("mcacrime:edition", UUID.nameUUIDFromBytes("justice-client-eight-stories".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        stack.setTag(tag);

        Method getBook = item.getClass().getMethod("getBook", ItemStack.class);
        Object book = getBook.invoke(item, stack);
        String itemClass = item.getClass().getName();
        String root = itemClass.substring(0, itemClass.indexOf("item."));
        Class<?> screenType = Class.forName(root + "client.gui.ExtendedBookScreen");
        Constructor<?> constructor = screenType.getConstructor(getBook.getReturnType());
        Object screen = constructor.newInstance(book);
        require(screen instanceof Screen, "MCA native letter screen is not a Screen");
        navigation.add("native screen: " + screenType.getName() + ", 4 pages, 8 ordered stories");
        return (Screen) screen;
    }

    private static int pageIndex(Screen screen) throws Exception {
        Field field = screen.getClass().getDeclaredField("pageIndex");
        field.setAccessible(true);
        return field.getInt(screen);
    }

    private void finish() throws Exception {
        for (String name : SHOTS) require(Files.isRegularFile(output.resolve("screenshots/" + name)), "missing " + name);
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("display", "1600x1000");
        evidence.put("guiScale", 2);
        evidence.put("screenshots", SHOTS);
        evidence.put("reportPickerRows", 6);
        evidence.put("reportStatusRows", PlayerReportStatus.values().length);
        evidence.put("nativeLetterPages", 4);
        evidence.put("nativeLetterStories", 8);
        evidence.put("navigation", navigation);
        Files.writeString(output.resolve("evidence.json"), toJson(evidence));
        Files.writeString(output.resolve("PASS.txt"),
                "PASS: report picker/status layouts and MCA native 4-page, 8-story letter rendered at GUI scale 2; keyboard ordering verified.\n");
        finished = true;
        mc.stop();
    }

    private static String toJson(Map<String, Object> evidence) {
        StringBuilder out = new StringBuilder("{\n");
        int row = 0;
        for (var entry : evidence.entrySet()) {
            if (row++ > 0) out.append(",\n");
            out.append("  \"").append(entry.getKey()).append("\": ");
            Object value = entry.getValue();
            if (value instanceof Number) out.append(value);
            else if (value instanceof List<?> list) {
                out.append("[");
                for (int i = 0; i < list.size(); i++) {
                    if (i > 0) out.append(", ");
                    out.append("\"").append(String.valueOf(list.get(i)).replace("\\", "\\\\").replace("\"", "\\\"")).append("\"");
                }
                out.append("]");
            } else out.append("\"").append(value).append("\"");
        }
        return out.append("\n}\n").toString();
    }

    private void next() { stage++; entered = ticks; }
    private void shot(String name) {
        Screenshot.grab(output.toFile(), name, mc.getMainRenderTarget(), message -> System.out.println(message.getString()));
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private void fail(Throwable failure) {
        if (finished) return;
        finished = true;
        failure.printStackTrace();
        try { Files.writeString(output.resolve("FAIL.txt"), failure.toString()); } catch (Exception ignored) { }
        mc.execute(mc::stop);
    }
}
