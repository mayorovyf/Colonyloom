package io.github.kpuctajluk.colonyloom.gametest;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.kpuctajluk.colonyloom.core.colony.WorldPosition;
import io.github.kpuctajluk.colonyloom.core.management.ManagementProtocol;
import io.github.kpuctajluk.colonyloom.core.management.ManagementProtocol.Result;
import io.github.kpuctajluk.colonyloom.core.management.ManagementProtocol.Row;
import io.github.kpuctajluk.colonyloom.core.management.ManagementProtocol.ViewData;
import io.github.kpuctajluk.colonyloom.core.management.ManagementProtocol.ViewType;
import io.github.kpuctajluk.colonyloom.minecraft.client.management.ManagementClient;
import io.github.kpuctajluk.colonyloom.minecraft.client.management.ManagementText;
import io.github.kpuctajluk.colonyloom.minecraft.client.screen.ColonyScreen;
import io.github.kpuctajluk.colonyloom.minecraft.network.ManagementPayloads;
import io.github.kpuctajluk.colonyloom.neoforge.ManagementClientPayloadEvent;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.util.ReferenceCountUtil;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.AccessibilityOnboardingScreen;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.common.NeoForge;

/** Opt-in, disposable-world driver of actual screens and actual TCP packets, never a substitute client. */
@Mod(value = "colonyloom_tests", dist = Dist.CLIENT)
public final class ManagementClientScenario {
    private static final int TIMEOUT_TICKS = 18_000;
    private static final String ADDRESS = "127.0.0.1:25576";
    private final Path root;
    private final Map<UUID, ReceivedView> received = new HashMap<>();
    private final Map<Long, List<Result>> results = new HashMap<>();
    private final WireFaults wire = new WireFaults();
    private String role;
    private boolean owner;
    private boolean stopped;
    private int ticks;
    private int phase;
    private int phaseTick;
    private UUID createdColony;
    private int tabIndex;
    private UUID colony;
    private UUID citizen;
    private UUID otherColony;
    private UUID guessed;
    private UUID probe;
    private UUID buildWork;
    private UUID oldSession;
    private ColonyScreen oldScreen;
    private ManagementClient client;
    private Connection installedConnection;
    private volatile boolean installed;
    private volatile Throwable networkFailure;
    private volatile UUID liveSession;
    private long commandSequence;
    private long probeClock;
    private long postRevokeViews;
    private long reconnectBuildPackets;
    private long respawnSequence;
    private long respawnCommandPackets;
    private LocalPlayer deadPlayer;
    private String requestedScreenshot;
    private String completedScreenshot;
    private boolean screenshotRunning;
    private long fixtureViews;

    public ManagementClientScenario() {
        String property = System.getProperty("colonyloom.test.managementRoot", "");
        if (property.isBlank()) { root = null; return; }
        root = Path.of(property);
        if (!root.isAbsolute() || !Files.isRegularFile(root.resolve("colonyloom-management-test"))) return;
        NeoForge.EVENT_BUS.addListener(this::tick);
        NeoForge.EVENT_BUS.addListener(this::render);
        // Observe before the real production listener consumes the very same inbound payload.
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, this::payload);
    }

    private void tick(ClientTickEvent.Post event) {
        if (stopped) return;
        Minecraft minecraft = Minecraft.getInstance();
        try {
            if (role == null) {
                String name = minecraft.getGameProfile().getName();
                require(name.equals("UIOwner") || name.equals("UIGuest"), "Unexpected owned client profile: " + name);
                owner = name.equals("UIOwner");
                role = owner ? "owner" : "viewer";
                observation("actual-profile", true, name + " UUID=" + minecraft.getGameProfile().getId());
            }
            require(++ticks <= TIMEOUT_TICKS, "Client timeout in phase " + phase);
            if (networkFailure != null) throw new IllegalStateException("Real network interceptor failed", networkFailure);
            Path clock=root.resolve("server-game-time");
            if(Files.isRegularFile(clock)) wire.serverGameTime=Long.parseLong(Files.readString(clock));
            if (minecraft.screen instanceof DisconnectedScreen && phase != 28) {
                throw new IllegalStateException("Actual TCP connection closed in phase " + phase + ": " + minecraft.screen.getTitle().getString());
            }
            if (phase == 0) {
                if (!minecraft.isGameLoadFinished() || minecraft.getOverlay() != null) return;
                if (minecraft.screen instanceof AccessibilityOnboardingScreen onboarding) { onboarding.onClose(); return; }
                if (!(minecraft.screen instanceof TitleScreen)) return;
                connect(minecraft);
                advance(1);
            } else if (phase == 1) {
                if (minecraft.player == null || minecraft.level == null || minecraft.getConnection() == null) return;
                install(minecraft.getConnection().getConnection());
                if (!installed || !Files.isRegularFile(root.resolve("fixture.json"))) return;
                JsonObject fixture = JsonParser.parseString(Files.readString(root.resolve("fixture.json"))).getAsJsonObject();
                colony = UUID.fromString(fixture.get("colony").getAsString());
                citizen = UUID.fromString(fixture.get("citizen").getAsString());
                otherColony = UUID.fromString(fixture.get("otherColony").getAsString());
                require(!owner ? minecraft.player.getUUID().toString().equals(fixture.get("viewer").getAsString()) : true,
                        "Offline server identity does not match fixture viewer");
                minecraft.getConnection().sendCommand(owner ? "colonyloom ui" : "colonyloom ui " + colony);
                advance(owner ? 2 : 10);
            } else if (owner) owner(minecraft);
            else viewer(minecraft);
        } catch (Throwable failure) {
            fail(failure);
        }
    }

    private void owner(Minecraft minecraft) throws IOException {
        switch (phase) {
            case 2 -> {
                if (!acquireScreen() || field("name") == null) return;
                require(client.ready() && client.nextSequence() == 0, "Creation screen must use fresh real management session");
                if (!screenshot("creation-form")) return;
                type("name", "UICreated"); type("dimension", "minecraft:overworld");
                type("min_x", "0"); type("min_z", "0"); type("max_x", "31"); type("max_z", "31");
                commandSequence = client.nextSequence();
                press("submit");
                advance(102);
            }
            case 102 -> {
                if (client.pending() || !results.containsKey(commandSequence) || !acquireScreen()) return;
                Result rejected = results.get(commandSequence).getFirst();
                require(rejected.status() == ManagementProtocol.Status.REJECTED, "Overlapping normal CREATE was not rejected: " + rejected);
                for (var entry : Map.of("name", "UICreated", "dimension", "minecraft:overworld", "min_x", "0", "min_z", "0", "max_x", "31", "max_z", "31").entrySet()) {
                    EditBox box = field(entry.getKey());
                    require(box != null && box.active && box.getValue().equals(entry.getValue()),
                            "Rejected CREATE lost typed editable field: " + entry.getKey());
                    type(entry.getKey(), entry.getValue());
                    require(field(entry.getKey()).getValue().equals(entry.getValue()), "Rejected CREATE field could not be edited: " + entry.getKey());
                }
                require(button("submit").active, "Rejected CREATE did not enable normal correction/retry");
                if (!screenshot("creation-rejected-retained-form")) return;
                observation("creation-rejected-retained-fields", true, "Actual overlapping CREATE rejected=" + rejected + "; all six typed fields retained/editable");
                type("min_x", "128"); type("min_z", "128"); type("max_x", "159"); type("max_z", "159");
                commandSequence = client.nextSequence();
                press("submit");
                advance(3);
            }
            case 3 -> {
                if (!accepted(commandSequence) || !acquireScreen()) return;
                ViewData created = client.subscriptions().keySet().stream().map(client::page)
                        .filter(data -> data != null && data.colonyName().equals("UICreated")).findFirst().orElse(null);
                if (created == null) return;
                createdColony = created.colonyId();
                require(created.rank().equals("owner"), "Created colony did not automatically open as OWNER");
                require(results.get(commandSequence).getFirst().objectId().equals(created.colonyId()), "Creation result did not open its actual colony");
                if (!screenshot("creation-auto-open")) return;
                observation("creation-through-widgets", true, "UICreated territory 128,128..159,159; actual result=" + results.get(commandSequence).getFirst());
                minecraft.getConnection().sendCommand("colonyloom ui " + colony);
                advance(10);
            }
            case 10 -> {
                if (!acquireScreen() || page(ViewType.SUMMARY) == null) return;
                tabIndex = 0;
                advance(11);
            }
            case 11 -> {
                if (!tabs(false)) return;
                signal("owner-ready", "Actual OWNER screen and four production tabs rendered");
                advance(12);
            }
            case 12 -> {
                if (!Files.isRegularFile(root.resolve("viewer-ready"))) return;
                press("tab.citizens");
                advance(13);
            }
            case 13 -> {
                Row row = citizenRow();
                if (row == null) return;
                select(row, ViewType.CITIZENS);
                press("profession");
                choose("colonyloom:carpenter");
                advance(113);
            }
            case 113 -> {
                if (!screenshot("profession-form")) return;
                commandSequence = client.nextSequence();
                press("submit");
                advance(14);
            }
            case 14 -> {
                if (!accepted(commandSequence) || !freshCitizen()) return;
                select(citizenRow(), ViewType.CITIZENS);
                press("workplace");
                advance(15);
            }
            case 15 -> {
                Button workshop = screen().children().stream().filter(Button.class::isInstance).map(Button.class::cast)
                        .filter(button -> isUuid(button.getMessage().getString())).findFirst().orElse(null);
                if (workshop == null || !workshop.active) return;
                if (!screenshot("workplace-form")) return;
                commandSequence = client.nextSequence();
                press("submit");
                advance(16);
            }
            case 16 -> {
                if (!accepted(commandSequence) || !freshCitizen()) return;
                observation("profession-workplace-widgets", true, "Accepted actual carpenter profession and registered-workshop assignment");
                select(citizenRow(), ViewType.CITIZENS);
                press("profession"); choose("colonyloom:builder");
                commandSequence = client.nextSequence();
                press("submit");
                advance(17);
            }
            case 17 -> {
                if (!accepted(commandSequence) || !freshCitizen()) return;
                press("tab.summary");
                advance(171);
            }
            case 171 -> {
                if(page(ViewType.SUMMARY)==null)return;
                press("refresh");
                advance(172);
            }
            case 172 -> {
                if(page(ViewType.SUMMARY)==null)return;
                press("tab.buildings");
                advance(18);
            }
            case 18 -> {
                if (page(ViewType.BUILDINGS) == null || page(ViewType.SUMMARY) == null) return;
                press("build"); choose("colonyloom:stair_strip");
                type("dimension", "minecraft:overworld"); type("x", "8"); type("y", "64"); type("z", "8");
                require(buttonText("0") != null, "Normal rotation choice must show zero");
                advance(19);
            }
            case 19 -> {
                if (!screenshot("typed-build-form")) return;
                commandSequence = client.nextSequence();
                wire.duplicateBuild = true;
                press("submit");
                advance(20);
            }
            case 20 -> {
                List<Result> responses = results.get(commandSequence);
                if (responses == null || responses.size() < 2) return;
                require(responses.size() == 2 && responses.getFirst().equals(responses.get(1))
                                && responses.getFirst().status() == ManagementProtocol.Status.ACCEPTED,
                        "Duplicated actual Build must receive two identical ACCEPTED results: " + responses);
                require(wire.build != null && wire.build.command().sequence() == commandSequence
                                && wire.buildPackets == 2 && wire.duplicateWrites == 1,
                        "Normal typed Build was not duplicated exactly once on the real connection");
                ManagementProtocol.Build body = (ManagementProtocol.Build) wire.build.command().body();
                require(body.blueprintId().equals("colonyloom:stair_strip") && body.rotation() == 0
                                && body.origin().equals(new WorldPosition("minecraft:overworld", 8, 64, 8)), "Wrong normal UI Build fields");
                observation("exact-command-dedup-results", true, "sequence=" + commandSequence + " payload=" + wire.build + " result=" + responses.getFirst());
                signal("build-requested", "Exact normal UI CommandPayload sent twice and two equal ACCEPTED results observed");
                advance(21);
            }
            case 21 -> {
                if (!Files.isRegularFile(root.resolve("build-verified"))) return;
                press("tab.work");
                advance(22);
            }
            case 22 -> {
                ViewData work = page(ViewType.WORK);
                if (work == null || work.rows().isEmpty()) return;
                if (!screenshot("work-after-build")) return;
                observation("authoritative-build-proof", true, "Server build-verified plus real work row(s)=" + work.rows());
                buildWork = results.get(commandSequence).getFirst().objectId();
                Row construction = work.rows().stream().filter(row -> row.id().equals(buildWork)).findFirst().orElseThrow();
                select(construction, ViewType.WORK);
                press("priority"); type("priority_value", "7");
                advance(23);
            }
            case 23 -> {
                if (!screenshot("work-priority-form")) return;
                commandSequence = client.nextSequence();
                press("submit");
                advance(24);
            }
            case 24 -> {
                if (!accepted(commandSequence)) return;
                observation("work-priority-through-widgets", true, "Actual selected construction PrioritizeWork7 result=" + results.get(commandSequence).getFirst());
                advance(25);
            }
            case 25 -> {
                ViewData work = page(ViewType.WORK);
                Row construction = work == null ? null : work.rows().stream().filter(row -> row.id().equals(buildWork)).findFirst().orElse(null);
                if (construction == null || construction.revision() < results.get(commandSequence).getFirst().revision()) return;
                select(construction, ViewType.WORK);
                press("cancel");
                advance(26);
            }
            case 26 -> {
                if (!screenshot("work-cancel-confirmation")) return;
                commandSequence = client.nextSequence();
                press("submit");
                advance(27);
            }
            case 27 -> {
                if (!accepted(commandSequence)) return;
                observation("work-cancel-through-widgets", true, "Actual selected construction CancelWork result=" + results.get(commandSequence).getFirst());
                signal("cancel-requested", "Actual UI CancelWork ACCEPTED work=" + buildWork);
                oldSession = client.sessionId(); oldScreen = screen();
                respawnSequence = client.nextSequence();
                respawnCommandPackets = wire.commandPackets;
                deadPlayer = minecraft.player;
                require(!client.pending(), "Death must begin with no command pending");
                signal("owner-death-request", "All actual priority/cancel commands accepted; session=" + oldSession + " nextSequence=" + respawnSequence);
                advance(127);
            }
            case 127 -> {
                if (!(minecraft.screen instanceof DeathScreen death) || minecraft.player == null || !minecraft.player.isDeadOrDying()) return;
                require(minecraft.player == deadPlayer && minecraft.getConnection().getConnection() == installedConnection,
                        "Death replaced player or TCP connection before normal respawn");
                Button respawn = death.children().stream().filter(Button.class::isInstance).map(Button.class::cast)
                        .filter(button -> button.getMessage().getString().equals(Component.translatable("deathScreen.respawn").getString()))
                        .findFirst().orElseThrow();
                if (!respawn.active) return;
                require(death.mouseClicked(respawn.getX() + respawn.getWidth() / 2.0,
                        respawn.getY() + respawn.getHeight() / 2.0, 0), "Normal death-screen respawn click was not consumed");
                advance(128);
            }
            case 128 -> {
                if (minecraft.player == null || minecraft.player == deadPlayer || minecraft.player.isDeadOrDying()
                        || minecraft.screen instanceof DeathScreen || !Files.isRegularFile(root.resolve("owner-respawned"))) return;
                require(minecraft.getConnection().getConnection() == installedConnection,
                        "Actual respawn replaced TCP connection");
                signal("respawn-summary-requested", "Opening actual /colonyloom ui after normal respawn");
                minecraft.getConnection().sendCommand("colonyloom ui " + colony);
                advance(129);
            }
            case 129 -> {
                if (!acquireScreen() || page(ViewType.SUMMARY) == null || !Files.isRegularFile(root.resolve("respawn-summary-verified"))) return;
                require(oldSession.equals(client.sessionId()) && client.nextSequence() == respawnSequence && !client.pending()
                                && screen() != oldScreen && page(ViewType.SUMMARY).rank().equals("owner"),
                        "Actual respawn lost connection-local session/sequence or refreshed OWNER screen");
                probeClock = wire.serverGameTime;
                advance(130);
            }
            case 130 -> {
                if (wire.serverGameTime - probeClock < 60) return;
                require(wire.commandPackets == respawnCommandPackets, "Management command was replayed across actual respawn");
                require(oldSession.equals(client.sessionId()) && client.nextSequence() == respawnSequence && !client.pending(),
                        "Session or command sequence changed during respawn refresh");
                if (!screenshot("respawn-refreshed-summary")) return;
                observation("actual-respawn-session-retained", true, "sameSession=" + oldSession + " nextSequence=" + respawnSequence
                        + " commandPackets=" + wire.commandPackets + " newLocalPlayer=" + (minecraft.player != deadPlayer)
                        + " sameConnection=" + (minecraft.getConnection().getConnection() == installedConnection));
                signal("owner-respawn-done", "Fresh actual summary, retained session/sequence and no command replay");
                oldScreen = screen();
                reconnectBuildPackets = wire.buildPackets;
                require(!client.pending(), "Reconnect must begin with no command pending");
                minecraft.getConnection().getConnection().disconnect(Component.literal("Disposable management reconnect verification"));
                minecraft.disconnect(new TitleScreen());
                advance(28);
            }
            case 28 -> {
                if (minecraft.getConnection() != null || minecraft.level != null) return;
                require(!client.ready() && client.sessionId() == null && client.nextSequence() == 0
                                && client.subscriptions().isEmpty() && !client.pending() && client.lastResult() == null,
                        "Production connection-local state survived actual disconnect");
                require(!(minecraft.screen instanceof ColonyScreen), "Disconnected production screen survived");
                installedConnection = null; installed = false;
                connect(minecraft);
                advance(29);
            }
            case 29 -> {
                if (minecraft.player == null || minecraft.level == null || minecraft.getConnection() == null) return;
                install(minecraft.getConnection().getConnection());
                if (!installed) return;
                minecraft.getConnection().sendCommand("colonyloom ui " + colony);
                advance(30);
            }
            case 30 -> {
                if (!acquireScreen() || page(ViewType.SUMMARY) == null) return;
                require(!oldSession.equals(client.sessionId()) && client.nextSequence() == 0 && !client.pending()
                                && client.lastResult() == null && screen() != oldScreen,
                        "Reconnect reused session, sequence, result, pending command, or screen");
                probeClock = wire.serverGameTime;
                advance(31);
            }
            case 31 -> {
                if (wire.serverGameTime - probeClock < 60) return;
                require(wire.buildPackets == reconnectBuildPackets, "Build command was resent across actual reconnect");
                if (!screenshot("reconnected-fresh-summary")) return;
                observation("actual-reconnect-no-resend", true, "oldSession=" + oldSession + " newSession=" + client.sessionId()
                        + " nextSequence=" + client.nextSequence() + " buildPackets=" + wire.buildPackets + " elapsedServerTicks=" + (wire.serverGameTime - probeClock));
                advance(32);
            }
            case 32 -> {
                if (!Files.isRegularFile(root.resolve("viewer-proof-done"))) return;
                minecraft.getConnection().sendCommand("colonyloom ui " + createdColony);
                advance(132);
            }
            case 132 -> {
                if (!acquireScreen() || client.subscriptions().keySet().stream().map(client::page)
                        .noneMatch(data -> data != null && data.colonyId().equals(createdColony) && data.type() == ViewType.SUMMARY)) return;
                press("tab.buildings");
                advance(136);
            }
            case 136 -> {
                if (client.subscriptions().keySet().stream().map(client::page)
                        .noneMatch(data -> data != null && data.colonyId().equals(createdColony) && data.type() == ViewType.BUILDINGS)) return;
                press("build");
                choose("colonyloom:stair_strip");
                type("dimension", "minecraft:overworld"); type("x", "128"); type("y", "64"); type("z", "128");
                advance(133);
            }
            case 133 -> {
                if (field("x") == null || buttonText("colonyloom:stair_strip") == null) return;
                if (!screenshot("owner-build-before-revoke")) return;
                JsonObject request = new JsonObject();
                request.addProperty("colony", createdColony.toString());
                request.addProperty("blueprint", "colonyloom:stair_strip");
                atomicSignal("owner-revoke-form-request", request);
                advance(134);
            }
            case 134 -> {
                if (!Files.isRegularFile(root.resolve("owner-form-authority-revoked"))) return;
                boolean denied = received.values().stream().anyMatch(view -> "ACCESS_DENIED".equals(view.closed));
                if (!denied || client.subscriptions().keySet().stream().map(client::page).anyMatch(data -> data != null && data.colonyId().equals(createdColony))) return;
                require(minecraft.screen instanceof ColonyScreen, "Authority revoke unexpectedly closed actual screen");
                require(buttonText(ManagementText.ui("submit").getString()) == null
                                && buttonText(ManagementText.ui("back").getString()) == null
                                && screen().children().stream().noneMatch(EditBox.class::isInstance)
                                && screen().children().stream().filter(Button.class::isInstance).map(Button.class::cast)
                                .noneMatch(button -> isUuid(button.getMessage().getString()) || button.getMessage().getString().equals("colonyloom:stair_strip")),
                        "Revoked OWNER retained stale form selectors or cached blueprint");
                require(wire.commandPackets == respawnCommandPackets, "Revocation form submitted or replayed a management command");
                if (!screenshot("owner-revoked-form-cleared")) return;
                observation("owner-revoked-form-cleared", true, "Actual ACCESS_DENIED on UICreated; no form submit/back, EditBox or cached blueprint; no command inputs");
                signal("owner-revoked-form-cleared", "Production build form cleared after actual authority revoke");
                advance(135);
            }
            case 135 -> {
                if (!Files.isRegularFile(root.resolve("viewer-done"))) return;
                finish();
            }
            default -> throw new IllegalStateException("Unknown OWNER phase " + phase);
        }
    }

    private void viewer(Minecraft minecraft) throws IOException {
        switch (phase) {
            case 10 -> {
                if (!acquireScreen() || page(ViewType.SUMMARY) == null) return;
                require(page(ViewType.SUMMARY).rank().equals("viewer"), "Actual fixture page is not VIEWER");
                tabIndex = 0;
                advance(11);
            }
            case 11 -> {
                if (!tabs(true)) return;
                advance(40);
            }
            case 40 -> {
                commandSequence = client.nextSequence();
                require(client.command(colony, colonyRevision(), new ManagementProtocol.Build("colonyloom:stair_strip",
                                new WorldPosition("minecraft:overworld", 8, 64, 8), 0)), "Live viewer Build was not sent");
                advance(41);
            }
            case 41 -> {
                if (client.pending() || !results.containsKey(commandSequence)) return;
                Result response = results.get(commandSequence).getFirst();
                require(response.status() == ManagementProtocol.Status.REJECTED && response.reason().equals("ACCESS_DENIED"),
                        "Typed malicious VIEWER Build was not denied: " + response);
                observation("viewer-forbidden-build", true, response.toString());
                guessed = subscribe(otherColony);
                advance(42);
            }
            case 42 -> {
                ReceivedView denied = received.get(guessed);
                if (denied == null || denied.closed == null) return;
                require(denied.closed.equals("ACCESS_DENIED") && denied.snapshots == 0 && denied.deltas == 0
                                && client.page(guessed) == null, "Guessed unreadable colony leaked a page: " + denied);
                observation("guessed-colony-no-data", true, "subscription=" + guessed + " colony=" + otherColony + " close=" + denied.closed);
                client.unsubscribe(guessed);
                signal("viewer-ready", "Four actual read-only tabs plus forbidden Build and guessed-colony denial observed");
                advance(43);
            }
            case 43 -> {
                if (!Files.isRegularFile(root.resolve("build-verified"))) return;
                ViewData work = page(ViewType.WORK);
                if (work == null || work.rows().isEmpty()) return;
                select(work.rows().getFirst(), ViewType.WORK);
                readOnly(ViewType.WORK);
                if (!screenshot("readonly-work-after-build")) return;
                probe = subscribe(colony);
                advance(44);
            }
            case 44 -> {
                if (client.page(probe) == null) return;
                wire.dropSubscription = probe;
                wire.dropNextDelta = true;
                advance(45);
            }
            case 45 -> {
                ReceivedView views = received.get(probe);
                if (wire.dropped == null || views == null || views.mismatches == 0 || wire.resyncRequests == 0
                        || views.snapshots < 2 || client.page(probe) == null) return;
                require(views.lastSnapshotRevision == client.page(probe).stateRevision()
                                || client.page(probe).stateRevision() > views.lastSnapshotRevision,
                        "Production page did not recover to its real resync snapshot");
                require(wire.dropCount == 1 && wire.lossAckWrites == 1 && views.mismatchBase == wire.dropped.data().stateRevision(),
                        "Base-loss fault did not drop exactly one genuine delta and trigger the subsequent actual base mismatch");
                observation("real-delta-base-loss-resync", true, "dropped=" + wire.dropped + " mismatchBase=" + views.mismatchBase
                        + " outboundResync=" + wire.resyncRequests + " actualSnapshots=" + views.snapshots);
                client.unsubscribe(probe); wire.dropSubscription = null;
                wire.maxPendingOutboundBytes = 0;
                wire.suppressAckAbove = -1;
                wire.suppressNextSubscription = true;
                probe = subscribe(colony);
                probeClock = wire.serverGameTime;
                advance(46);
            }
            case 46 -> {
                ReceivedView views = received.get(probe);
                if (views == null || views.closed == null || wire.serverGameTime - probeClock < 140) return;
                require(views.closed.equals("ACK_TIMEOUT") && views.snapshots == 1 && views.deltas == 0
                                && client.page(probe) == null && wire.suppressedAcks > 0,
                        "Live unacked subscription did not remain one bounded in-flight snapshot then ACK_TIMEOUT: " + views);
                require(wire.maxPendingOutboundBytes <= ManagementProtocol.VIEW_BUFFER_BYTES,
                        "Real client Netty outbound queue exceeded protocol buffer bound: " + wire.maxPendingOutboundBytes);
                observation("real-ack-timeout-bounded-network", true, "subscription=" + probe + " snapshots=" + views.snapshots
                        + " deltas=" + views.deltas + " suppressedActualAcks=" + wire.suppressedAcks + " elapsedServerTicks="
                        + (wire.serverGameTime - probeClock) + " maxActualNettyPendingBytes=" + wire.maxPendingOutboundBytes);
                client.unsubscribe(probe); wire.suppressAckSubscription = null;
                probe = subscribe(colony);
                advance(47);
            }
            case 47 -> {
                ReceivedView views = received.get(probe);
                if (views == null || views.snapshots != 1 || client.page(probe) == null) return;
                wire.suppressAckAbove = client.page(probe).stateRevision();
                wire.suppressAckSubscription = probe;
                wire.lastSuppressedRevision = -1;
                advance(48);
            }
            case 48 -> {
                ReceivedView views = received.get(probe);
                if (views == null || views.lastDelta == null || views.lastDelta.data().stateRevision() <= wire.suppressAckAbove
                        || wire.lastSuppressedRevision != views.lastDelta.data().stateRevision()) return;
                JsonObject request = new JsonObject();
                request.addProperty("subscriptionId", probe.toString());
                request.addProperty("authorityRevision", views.lastDelta.data().authorityRevision());
                request.addProperty("stateRevision", views.lastDelta.data().stateRevision());
                atomicSignal("revoke-request", request);
                observation("revoke-with-real-delta-in-flight", true, request + " actualDeltaBase=" + views.lastDelta.baseRevision());
                advance(49);
            }
            case 49 -> {
                ReceivedView views = received.get(probe);
                if (!Files.isRegularFile(root.resolve("revoked")) || views == null || views.closed == null) return;
                require(views.closed.equals("ACCESS_DENIED") && client.page(probe) == null,
                        "Revocation did not purge actual in-flight subscription: " + views);
                wire.suppressAckSubscription = null;
                client.unsubscribe(probe);
                guessed = subscribe(colony);
                postRevokeViews = fixtureViews;
                probeClock = wire.serverGameTime;
                advance(50);
            }
            case 50 -> {
                ReceivedView denied = received.get(guessed);
                if (denied == null || denied.closed == null || wire.serverGameTime - probeClock < 120) return;
                require(denied.closed.equals("ACCESS_DENIED") && denied.snapshots == 0 && denied.deltas == 0,
                        "Post-revocation fresh subscription leaked data: " + denied);
                require(fixtureViews == postRevokeViews, "Fixture views continued after actual ACCESS_DENIED revocation barrier");
                require(client.subscriptions().keySet().stream().allMatch(id -> client.page(id) == null),
                        "Revoked production client retained private page data");
                if (!screenshot("revoked-no-private-data")) return;
                observation("revocation-no-new-or-retained-data", true, "ACCESS_DENIED real pending delta; viewsAfterClose="
                        + (fixtureViews - postRevokeViews) + " elapsedServerTicks=" + (wire.serverGameTime - probeClock)
                        + " newSubscription=" + guessed + " reason=" + denied.closed);
                client.unsubscribe(guessed);
                signal("viewer-proof-done", "Original fixture VIEWER revocation and no-data proof complete; retaining genuine connection for created-colony handoff");
                advance(150);
            }
            case 150 -> {
                if (!Files.isRegularFile(root.resolve("owner-revoked-form-cleared"))) return;
                finish();
            }
            default -> throw new IllegalStateException("Unknown VIEWER phase " + phase);
        }
    }

    private boolean tabs(boolean readOnly) throws IOException {
        ViewType type = ViewType.values()[tabIndex];
        ViewData data = page(type);
        if (data == null || ticks - phaseTick < 3) return false;
        require(data.colonyId().equals(colony) && data.rank().equals(readOnly ? "viewer" : "owner"), "Wrong authoritative tab data");
        if (readOnly) {
            if (!data.rows().isEmpty()) select(data.rows().getFirst(), type);
            readOnly(type);
        }
        if (!screenshot((readOnly ? "readonly-" : "") + type.name().toLowerCase(Locale.ROOT))) return false;
        observation("actual-tab-" + type.name().toLowerCase(Locale.ROOT), true,
                "rank=" + data.rank() + " subscriptionType=" + data.type() + " rows=" + data.totalRows()
                        + " authorityRevision=" + data.authorityRevision() + " stateRevision=" + data.stateRevision());
        if (++tabIndex == ViewType.values().length) return true;
        press("tab." + ViewType.values()[tabIndex].name().toLowerCase(Locale.ROOT));
        phaseTick = ticks;
        return false;
    }

    private void readOnly(ViewType type) {
        List<String> actions = switch (type) {
            case SUMMARY -> List.of("member", "owner");
            case CITIZENS -> List.of("profession", "workplace");
            case BUILDINGS -> List.of("build", "storage", "workshop");
            case WORK -> List.of("cancel", "priority");
        };
        for (String action : actions) require(!button(action).active, "VIEWER mutation widget enabled: " + action);
        // Create is deliberately not an edit to this colony: any player may create their own colony.
    }

    private boolean acquireScreen() {
        if (!(Minecraft.getInstance().screen instanceof ColonyScreen screen)) return false;
        client = screen.client();
        liveSession = client.sessionId();
        return client.ready();
    }

    private ColonyScreen screen() {
        require(Minecraft.getInstance().screen instanceof ColonyScreen, "Expected actual production ColonyScreen");
        return (ColonyScreen) Minecraft.getInstance().screen;
    }

    private ViewData page(ViewType type) {
        if (client == null) return null;
        return client.subscriptions().entrySet().stream()
                .filter(entry -> entry.getValue().colonyId().equals(colony) && entry.getValue().type() == type)
                .map(entry -> client.page(entry.getKey())).filter(java.util.Objects::nonNull).findFirst().orElse(null);
    }

    private Row citizenRow() {
        ViewData data = page(ViewType.CITIZENS);
        return data == null ? null : data.rows().stream().filter(row -> row.id().equals(citizen)).findFirst().orElse(null);
    }

    private boolean freshCitizen() {
        Row row = citizenRow();
        return row != null && client.lastResult() != null && row.revision() >= client.lastResult().revision();
    }

    private long colonyRevision() {
        ViewData data = page(ViewType.SUMMARY);
        require(data != null, "Real summary is not ready");
        return data.rows().stream().filter(row -> row.id().equals(colony)).findFirst().orElseThrow().revision();
    }

    private boolean accepted(long sequence) throws IOException {
        if (client.pending() || !results.containsKey(sequence)) return false;
        Result result = results.get(sequence).getFirst();
        if (result.status() != ManagementProtocol.Status.ACCEPTED) {
            Row row = citizenRow();
            observation("normal-widget-command-rejected", false, "result=" + result + " actualOutbound=" + wire.lastCommand
                    + " currentPageCitizenRevision=" + (row == null ? "no-page" : row.revision()));
            throw new IllegalStateException("Normal UI command rejected: " + result);
        }
        return true;
    }

    private UUID subscribe(UUID colonyId) {
        UUID id = client.subscribe(colonyId, ViewType.SUMMARY, 0);
        require(id != null, "Production subscription capacity exhausted");
        return id;
    }

    private void select(Row row, ViewType type) {
        ViewData data = page(type);
        int index = data.rows().indexOf(row);
        require(index >= 0, "Actual row is not in visible production page");
        Button control = screen().children().stream().filter(Button.class::isInstance).map(Button.class::cast)
                .filter(button -> button.getY() == 82 + index * 22).findFirst().orElseThrow();
        click(control);
    }

    private Button button(String key) {
        Button result = buttonText(ManagementText.ui(key).getString());
        require(result != null, "Normal widget missing: " + key);
        return result;
    }

    private Button buttonText(String text) {
        return screen().children().stream().filter(Button.class::isInstance).map(Button.class::cast)
                .filter(button -> button.getMessage().getString().equals(text)).findFirst().orElse(null);
    }

    private void press(String key) { click(button(key)); }

    private void click(AbstractWidget widget) {
        require(widget.active && widget.visible, "Attempt to click inactive normal widget: " + widget.getMessage().getString());
        Screen screen = screen();
        double x = widget.getX() + widget.getWidth() / 2.0, y = widget.getY() + widget.getHeight() / 2.0;
        require(screen.mouseClicked(x, y, 0), "Normal screen did not consume widget click");
        screen.mouseReleased(x, y, 0);
    }

    private EditBox field(String key) {
        String message = ManagementText.ui("field." + key).getString();
        return screen().children().stream().filter(EditBox.class::isInstance).map(EditBox.class::cast)
                .filter(box -> box.getMessage().getString().equals(message)).findFirst().orElse(null);
    }

    private void type(String key, String value) {
        EditBox box = field(key);
        require(box != null, "Normal typed field missing: " + key);
        click(box);
        box.moveCursorToStart(false);
        box.setHighlightPos(box.getValue().length());
        for (char character : value.toCharArray()) require(box.charTyped(character, 0), "Normal EditBox rejected typed input " + key);
        require(box.getValue().equals(value), "Normal typed field differs: " + key + "=" + box.getValue());
    }

    private void choose(String value) {
        if (buttonText(value) != null) return;
        String prefix = value.substring(0, value.indexOf(':') + 1);
        for (int attempt = 0; attempt < 64; attempt++) {
            Button choice = screen().children().stream().filter(Button.class::isInstance).map(Button.class::cast)
                    .filter(button -> button.getY() == 80 && button.getX() == 12 && button.getMessage().getString().startsWith(prefix))
                    .findFirst().orElseThrow(() -> new IllegalStateException("Normal typed choice missing: " + value));
            click(choice);
            if (buttonText(value) != null) return;
        }
        throw new IllegalStateException("Normal choice catalog does not contain " + value);
    }

    private void connect(Minecraft minecraft) {
        ConnectScreen.startConnecting(new TitleScreen(), minecraft, ServerAddress.parseString(ADDRESS),
                new ServerData("Disposable Colonyloom management", ADDRESS, ServerData.Type.OTHER), false, null);
    }

    private void install(Connection connection) {
        if (installedConnection == connection) return;
        installedConnection = connection;
        installed = false;
        connection.channel().eventLoop().execute(() -> {
            try {
                String anchor = connection.channel().pipeline().context(connection).name();
                connection.channel().pipeline().addBefore(anchor, "colonyloom_management_smoke", wire);
                installed = true;
            } catch (Throwable error) { networkFailure = error; }
        });
    }

    private void payload(ManagementClientPayloadEvent event) {
        if (stopped || role == null) return;
        CustomPacketPayload payload = event.payload();
        if (payload instanceof ManagementPayloads.CommandResult response) {
            List<Result> list = results.computeIfAbsent(response.result().sequence(), ignored -> new ArrayList<>(2));
            if (list.size() < 3) list.add(response.result());
        } else if (payload instanceof ManagementPayloads.ViewSnapshot snapshot) {
            ReceivedView view = received.computeIfAbsent(snapshot.subscriptionId(), ignored -> new ReceivedView());
            view.snapshots++;
            view.lastSnapshotRevision = snapshot.data().stateRevision();
            if (snapshot.data().colonyId().equals(colony)) fixtureViews++;
        } else if (payload instanceof ManagementPayloads.ViewDelta delta) {
            ReceivedView view = received.computeIfAbsent(delta.subscriptionId(), ignored -> new ReceivedView());
            view.deltas++;
            view.lastDelta = delta;
            if (delta.data().colonyId().equals(colony)) fixtureViews++;
            ViewData current = client == null ? null : client.page(delta.subscriptionId());
            if (delta.subscriptionId().equals(wire.dropSubscription) && wire.dropped != null && current != null
                    && current.stateRevision() != delta.baseRevision()) {
                view.mismatches++;
                view.mismatchBase = delta.baseRevision();
            }
        } else if (payload instanceof ManagementPayloads.ViewClosed closed) {
            received.computeIfAbsent(closed.subscriptionId(), ignored -> new ReceivedView()).closed = closed.reason();
        }
    }

    private boolean screenshot(String tag) {
        String name = role + "-" + tag + ".png";
        if (name.equals(completedScreenshot)) return true;
        if (requestedScreenshot == null && !screenshotRunning) requestedScreenshot = name;
        return false;
    }

    private void render(RenderFrameEvent.Post event) {
        if (stopped || requestedScreenshot == null || screenshotRunning) return;
        String name = requestedScreenshot;
        requestedScreenshot = null;
        screenshotRunning = true;
        Minecraft minecraft = Minecraft.getInstance();
        Screenshot.grab(root.toFile(), name, minecraft.getMainRenderTarget(), message -> minecraft.execute(() -> {
            try {
                Path path = root.resolve("screenshots").resolve(name);
                require(Files.isRegularFile(path) && Files.size(path) > 0, "Actual graphical screenshot failed: " + message.getString());
                observation("screenshot-" + name, true, root.relativize(path).toString());
                completedScreenshot = name;
                screenshotRunning = false;
            } catch (Throwable failure) { fail(failure); }
        }));
    }

    private void advance(int next) { phase = next; phaseTick = ticks; }

    private void signal(String name, String detail) throws IOException {
        JsonObject signal = new JsonObject();
        signal.addProperty("passed", true); signal.addProperty("detail", detail);
        atomicSignal(name, signal);
    }

    private void atomicSignal(String name, JsonObject data) throws IOException {
        Path temporary = root.resolve(name + ".tmp");
        Files.writeString(temporary, data.toString(), StandardOpenOption.CREATE_NEW);
        Files.move(temporary, root.resolve(name), StandardCopyOption.ATOMIC_MOVE);
    }

    private void observation(String check, boolean passed, String detail) throws IOException {
        JsonObject observation = new JsonObject();
        observation.addProperty("check", check); observation.addProperty("passed", passed); observation.addProperty("detail", detail);
        Files.writeString(root.resolve((role == null ? "client" : role) + "-observations.jsonl"), observation + "\n",
                StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private void finish() throws IOException {
        observation("scenario-complete", true, "Actual " + role + " graphical client completed all required network and widget observations");
        signal(role + "-done", "Completed actual graphical scenario");
        stopped = true;
        Minecraft.getInstance().stop();
    }

    private void fail(Throwable failure) {
        if (stopped) return;
        stopped = true;
        try {
            String detail = "phase=" + phase + " ticks=" + ticks + " " + failure;
            if (failure.getCause() != null) detail += " caused by " + failure.getCause();
            observation("scenario-failure", false, detail);
            JsonObject done = new JsonObject(); done.addProperty("passed", false); done.addProperty("detail", detail);
            Files.writeString(root.resolve((role == null ? "client" : role) + "-done"), done.toString(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException reportFailure) { failure.addSuppressed(reportFailure); }
        failure.printStackTrace();
        Minecraft.getInstance().stop();
    }

    private static void require(boolean passed, String detail) { if (!passed) throw new IllegalStateException(detail); }
    private static boolean isUuid(String text) { try { UUID.fromString(text); return true; } catch (IllegalArgumentException ignored) { return false; } }

    private static final class ReceivedView {
        int snapshots;
        int deltas;
        int mismatches;
        long mismatchBase;
        long lastSnapshotRevision;
        String closed;
        ManagementPayloads.ViewDelta lastDelta;
        @Override public String toString() { return "snapshots=" + snapshots + " deltas=" + deltas + " close=" + closed; }
    }

    @io.netty.channel.ChannelHandler.Sharable
    private final class WireFaults extends ChannelDuplexHandler {
        private volatile boolean duplicateBuild;
        private volatile ManagementPayloads.CommandPayload build;
        private volatile ManagementPayloads.CommandPayload lastCommand;
        private volatile long buildPackets;
        private volatile long commandPackets;
        private volatile int duplicateWrites;
        private volatile UUID dropSubscription;
        private volatile boolean dropNextDelta;
        private volatile ManagementPayloads.ViewDelta dropped;
        private volatile int dropCount;
        private volatile int lossAckWrites;
        private volatile int resyncRequests;
        private volatile UUID suppressAckSubscription;
        private volatile boolean suppressNextSubscription;
        private volatile long suppressAckAbove = -1;
        private volatile long suppressedAcks;
        private volatile long lastSuppressedRevision = -1;
        private volatile long serverGameTime;
        private volatile long maxPendingOutboundBytes;

        @Override public void channelRead(ChannelHandlerContext context, Object packet) throws Exception {
            sampleQueue(context);
            if (packet instanceof ClientboundBundlePacket bundle) {
                List<Packet<? super ClientGamePacketListener>> retained = new ArrayList<>();
                boolean changed = false;
                for (Packet<? super ClientGamePacketListener> child : bundle.subPackets()) {
                    if (accept(context, child)) retained.add(child); else changed = true;
                }
                if (changed) { super.channelRead(context, new ClientboundBundlePacket(retained)); return; }
            } else if (!accept(context, packet)) { ReferenceCountUtil.release(packet); return; }
            super.channelRead(context, packet);
        }

        private boolean accept(ChannelHandlerContext context, Object packet) {
            if (packet instanceof ClientboundCustomPayloadPacket custom
                    && custom.payload() instanceof ManagementPayloads.ViewDelta delta
                    && dropNextDelta && delta.subscriptionId().equals(dropSubscription)) {
                dropNextDelta = false;
                dropped = delta;
                dropCount++;
                // The fault loses only delivery to the local page. ACK the *captured real* revision so
                // the bounded server may send its next genuine delta; never feed either DTO to handle().
                context.channel().writeAndFlush(new ServerboundCustomPayloadPacket(new ManagementPayloads.ViewAck(
                        liveSession, delta.subscriptionId(), delta.data().authorityRevision(), delta.data().stateRevision())));
                lossAckWrites++;
                return false;
            }
            return true;
        }

        @Override public void write(ChannelHandlerContext context, Object packet, ChannelPromise promise) throws Exception {
            sampleQueue(context);
            if (packet instanceof ServerboundCustomPayloadPacket custom) {
                CustomPacketPayload payload = custom.payload();
                if (payload instanceof ManagementPayloads.CommandPayload command) { lastCommand = command; commandPackets++; }
                if (payload instanceof ManagementPayloads.Subscribe subscribe && suppressNextSubscription) {
                    suppressNextSubscription = false;
                    suppressAckSubscription = subscribe.subscription().subscriptionId();
                }
                if (payload instanceof ManagementPayloads.ViewAck ack && ack.subscriptionId().equals(suppressAckSubscription)
                        && ack.stateRevision() > suppressAckAbove) {
                    suppressedAcks++;
                    lastSuppressedRevision = ack.stateRevision();
                    ReferenceCountUtil.release(packet);
                    promise.setSuccess();
                    return;
                }
                if (payload instanceof ManagementPayloads.Subscribe subscribe && subscribe.subscription().resync()
                        && subscribe.subscription().subscriptionId().equals(dropSubscription)) resyncRequests++;
                if (payload instanceof ManagementPayloads.CommandPayload command && command.command().body() instanceof ManagementProtocol.Build) {
                    buildPackets++;
                    if (owner && duplicateBuild) {
                        duplicateBuild = false;
                        build = command;
                        super.write(context, packet, promise);
                        super.write(context, new ServerboundCustomPayloadPacket(command), context.newPromise());
                        buildPackets++;
                        commandPackets++;
                        duplicateWrites++;
                        return;
                    }
                }
            }
            super.write(context, packet, promise);
        }

        private void sampleQueue(ChannelHandlerContext context) {
            var outbound = context.channel().unsafe().outboundBuffer();
            if (outbound != null) maxPendingOutboundBytes = Math.max(maxPendingOutboundBytes, outbound.totalPendingWriteBytes());
        }
    }
}
