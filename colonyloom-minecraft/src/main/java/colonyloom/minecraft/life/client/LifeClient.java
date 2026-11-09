package colonyloom.minecraft.life.client;

import colonyloom.minecraft.life.net.LifePayloads;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public final class LifeClient {
    private static Consumer<CustomPacketPayload> teen_pro_YT_sender = payload -> {};
    private static LifePayloads.View teen_pro_YT_view;

    private LifeClient() {}

    public static void sender(Consumer<CustomPacketPayload> sender) { teen_pro_YT_sender = Objects.requireNonNull(sender); }

    public static void request(UUID colony, int tab, int page) {
        teen_pro_YT_sender.accept(new LifePayloads.ViewRequest(colony, tab, page));
    }

    public static void view(LifePayloads.View view) {
        teen_pro_YT_view = view;
        if (Minecraft.getInstance().screen instanceof LifeScreen screen) screen.received(view);
    }

    public static LifePayloads.View view() { return teen_pro_YT_view; }

    public static void open(UUID colony) {
        open(colony, null);
    }

    public static void open(UUID colony, Supplier<Screen> back) {
        Minecraft.getInstance().setScreen(new LifeScreen(colony, back));
    }

    public static void clear() {
        teen_pro_YT_view = null;
        ResidentVisuals.clear();
    }

    public static void handle(CustomPacketPayload payload) {
        if (payload instanceof LifePayloads.Outfit outfit) ResidentVisuals.outfit(outfit.entityId(), outfit.outfit());
        else if (payload instanceof LifePayloads.Speech speech) ResidentVisuals.speech(speech.entityId(), speech.spec());
        else if (payload instanceof LifePayloads.View view) view(view);
    }
}
