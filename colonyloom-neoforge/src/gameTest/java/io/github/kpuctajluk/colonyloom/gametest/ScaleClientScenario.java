package io.github.kpuctajluk.colonyloom.gametest;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.kpuctajluk.colonyloom.core.management.ManagementProtocol;
import io.github.kpuctajluk.colonyloom.core.management.ManagementProtocol.Result;
import io.github.kpuctajluk.colonyloom.core.management.ManagementProtocol.ViewData;
import io.github.kpuctajluk.colonyloom.core.management.ManagementProtocol.ViewType;
import io.github.kpuctajluk.colonyloom.core.management.ManagementSession;
import io.github.kpuctajluk.colonyloom.minecraft.client.management.ManagementClient;
import io.github.kpuctajluk.colonyloom.minecraft.client.management.ManagementText;
import io.github.kpuctajluk.colonyloom.minecraft.client.screen.ColonyScreen;
import io.github.kpuctajluk.colonyloom.minecraft.network.ManagementPayloads;
import io.github.kpuctajluk.colonyloom.neoforge.ManagementClientPayloadEvent;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.util.ReferenceCountUtil;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.imageio.ImageIO;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.AccessibilityOnboardingScreen;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.common.NeoForge;

/** Opt-in graphical scale acceptance on two genuine TCP connections; never distributed in the mod. */
@Mod(value = "colonyloom_tests", dist = Dist.CLIENT)
public final class ScaleClientScenario {
    private static final String ADDRESS = "127.0.0.1:25577";
    private static final long SECOND = 1_000_000_000L;
    private static final long PHASE_TIMEOUT = 300 * SECOND;
    private final Path root;
    private final WireObserver wire = new WireObserver();
    private final Map<ViewType, UUID> subscriptions = new EnumMap<>(ViewType.class);
    private final Map<ViewType, Long> rendered = new EnumMap<>(ViewType.class);
    private final Map<UUID, WireStats> measuredTransport = new HashMap<>();
    private final Map<UUID, Long> lastTransportProgress = new HashMap<>();
    private String role;
    private boolean owner;
    private boolean stopped;
    private boolean initialized;
    private boolean diagnostic;
    private long warmupSeconds;
    private long measureSeconds;
    private long startedAt;
    private long phaseAt;
    private long readyAtEpochMillis;
    private long lastSampleAt;
    private long measuredTransportStartedAt;
    private long holdDataPackets;
    private long holdAcknowledgments;
    private long holdCommands;
    private long holdSubscribes;
    private long tabRenderBaseline;
    private long quietPackets;
    private long commandSequence;
    private Phase phase = Phase.CONNECT;
    private int tabIndex;
    private UUID colony;
    private UUID otherColony;
    private UUID citizen;
    private UUID foreignProbe;
    private UUID foreignTarget;
    private ManagementClient client;
    private Result commandResult;
    private Connection connection;
    private volatile boolean installed;
    private volatile Throwable networkFailure;
    private boolean screenshotRequested;
    private boolean screenshotRunning;
    private boolean screenshotComplete;

    public ScaleClientScenario() {
        String property = System.getProperty("colonyloom.test.scaleRoot", "");
        if (property.isBlank()) { root = null; return; }
        root = Path.of(property);
        if (!root.isAbsolute() || !Files.isRegularFile(root.resolve("colonyloom-scale-test"))) return;
        NeoForge.EVENT_BUS.addListener(this::tick);
        NeoForge.EVENT_BUS.addListener(this::render);
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, this::payload);
    }

    private void tick(ClientTickEvent.Post event) {
        if (stopped) return;
        Minecraft minecraft = Minecraft.getInstance();
        try {
            if (!initialized) initialize(minecraft);
            long now = System.nanoTime();
            require(now - startedAt <= Math.addExact(Math.addExact(warmupSeconds, measureSeconds), 900) * SECOND,
                    "Scale client overall wall-clock timeout in " + phase);
            if (phase != Phase.HOLD && phase != Phase.OWNER_WAIT) {
                require(now - phaseAt <= PHASE_TIMEOUT, "Scale client phase timeout in " + phase);
            }
            if (networkFailure != null) throw new IllegalStateException("Actual packet observer failed", networkFailure);
            if (minecraft.screen instanceof DisconnectedScreen) {
                throw new IllegalStateException("Real TCP connection closed in " + phase + ": " + minecraft.screen.getTitle().getString());
            }
            switch (phase) {
                case CONNECT -> {
                    if (!minecraft.isGameLoadFinished() || minecraft.getOverlay() != null) return;
                    if (minecraft.screen instanceof AccessibilityOnboardingScreen onboarding) { onboarding.onClose(); return; }
                    if (!(minecraft.screen instanceof TitleScreen)) return;
                    if (!Files.isRegularFile(root.resolve("server-listening"))) return;
                    ConnectScreen.startConnecting(new TitleScreen(), minecraft, ServerAddress.parseString(ADDRESS),
                            new ServerData("Disposable Colonyloom scale", ADDRESS, ServerData.Type.OTHER), false, null);
                    advance(Phase.FIXTURE);
                }
                case FIXTURE -> {
                    if (minecraft.player == null || minecraft.level == null || minecraft.getConnection() == null) return;
                    install(minecraft.getConnection().getConnection());
                    if (!installed || !Files.isRegularFile(root.resolve("scale-ready.json"))) return;
                    JsonObject fixture = json("scale-ready.json");
                    require(ADDRESS.equals(fixture.get("address").getAsString()), "Unexpected scale server address");
                    colony = UUID.fromString(fixture.get("colony").getAsString());
                    otherColony = UUID.fromString(fixture.get("otherColony").getAsString());
                    citizen = UUID.fromString(fixture.get("citizen").getAsString());
                    require(!colony.equals(otherColony), "Scale fixture foreign colony equals allowed colony");
                    require(minecraft.player.getUUID().equals(offline(owner ? "ScaleOwner" : "ScaleViewer")),
                            "Actual offline server identity differs from the configured graphical profile");
                    observation("real-server-identity", true, "player=" + minecraft.player.getUUID() + ";address=" + ADDRESS);
                    minecraft.getConnection().sendCommand("colonyloom ui " + colony);
                    advance(Phase.SCREEN);
                }
                case SCREEN -> {
                    if (!(minecraft.screen instanceof ColonyScreen screen)) return;
                    client = screen.client();
                    if (!client.ready() || page(ViewType.SUMMARY) == null) return;
                    validate(page(ViewType.SUMMARY), ViewType.SUMMARY);
                    // All three scale territories belong to ScaleOwner. Only the viewer's otherColony
                    // is an existing unreadable colony; the owner probes an unknown scope instead.
                    foreignTarget = owner ? UUID.randomUUID() : otherColony;
                    foreignProbe = client.subscribe(foreignTarget, ViewType.SUMMARY, 0);
                    require(foreignProbe != null, "Foreign-scope probe could not use actual production subscription API");
                    advance(Phase.FOREIGN_WAIT);
                }
                case FOREIGN_WAIT -> {
                    WireStats denied = wire.stats(foreignProbe);
                    if (denied == null || denied.closed() == null) return;
                    require("ACCESS_DENIED".equals(denied.closed()) && denied.dataPackets() == 0 && client.page(foreignProbe) == null,
                            "Unauthorized subscription leaked data or was not denied: " + denied);
                    observation(owner ? "unknown-scope-denied" : "foreign-colony-denied", true,
                            "colony=" + foreignTarget + ";subscription=" + foreignProbe + ";wire=" + denied);
                    client.unsubscribe(foreignProbe);
                    if (owner) beginTabs();
                    else {
                        ViewData summary = page(ViewType.SUMMARY);
                        long revision = summary.rows().stream().filter(row -> row.id().equals(colony)).findFirst().orElseThrow().revision();
                        commandSequence = client.nextSequence();
                        require(client.command(colony, revision, new ManagementProtocol.SetMember(minecraft.player.getUUID(), "manager")),
                                "Actual typed VIEWER mutation was not sent");
                        advance(Phase.MUTATION_WAIT);
                    }
                }
                case MUTATION_WAIT -> {
                    if (client.pending() || commandResult == null) return;
                    require(commandResult.sequence() == commandSequence && commandResult.status() == ManagementProtocol.Status.REJECTED
                                    && "ACCESS_DENIED".equals(commandResult.reason()),
                            "Actual typed VIEWER self-escalation was not rejected before mutation: " + commandResult);
                    require(wire.commandPackets() == 1, "Viewer rejection proof did not send exactly one actual typed command");
                    observation("viewer-mutation-denied", true, "SetMember(self,manager)=" + commandResult + ";wireCommands=" + wire.commandPackets());
                    beginTabs();
                }
                case TABS -> tabs();
                case FOUR -> {
                    if (!fourReady()) return;
                    verifyFour();
                    screenshotRequested = true;
                    advance(Phase.SCREENSHOT);
                }
                case SCREENSHOT -> {
                    verifyFour();
                    if (!screenshotComplete) return;
                    readyAtEpochMillis = System.currentTimeMillis();
                    JsonObject ready = new JsonObject();
                    ready.addProperty("passed", true);
                    ready.addProperty("readyAtEpochMillis", readyAtEpochMillis);
                    ready.addProperty("subscriptions", subscriptions.size());
                    ready.addProperty("session", client.sessionId().toString());
                    ready.addProperty("diagnostic", diagnostic);
                    ready.addProperty("warmupSeconds", warmupSeconds);
                    ready.addProperty("measureSeconds", measureSeconds);
                    publish(role + "-ready", ready);
                    holdDataPackets = wire.dataPackets();
                    holdAcknowledgments = wire.acknowledgments();
                    holdCommands = wire.commandPackets();
                    holdSubscribes = wire.subscribePackets();
                    observation("four-concurrent-subscriptions-ready", true,
                            "session=" + client.sessionId() + ";ids=" + subscriptions + ";readyAtEpochMillis=" + readyAtEpochMillis);
                    advance(Phase.HOLD);
                }
                case HOLD -> {
                    verifyFour();
                    if (now - lastSampleAt >= SECOND) { wire.assertBounds(); lastSampleAt = now; }
                    if (measuredTransport.isEmpty() && Files.isRegularFile(root.resolve("measurement-started"))) {
                        measuredTransportStartedAt = now;
                        for (UUID id : subscriptions.values()) {
                            measuredTransport.put(id, wire.stats(id));
                            lastTransportProgress.put(id, now);
                        }
                    }
                    if (!measuredTransport.isEmpty()) for (UUID id : subscriptions.values()) {
                        WireStats current = wire.stats(id), previous = measuredTransport.get(id);
                        require(current != null && previous != null, "Measured production subscription disappeared: " + id);
                        if (current.dataPackets() > previous.dataPackets() && current.acknowledgments() > previous.acknowledgments()) {
                            lastTransportProgress.put(id, now);
                            measuredTransport.put(id, current);
                        }
                        require(now - lastTransportProgress.get(id) <= 120 * SECOND,
                                "No acknowledged production view progress for120seconds: " + id + ";wire=" + current);
                    }
                    if (!Files.isRegularFile(root.resolve("server-done"))) return;
                    JsonObject peer = json((owner ? "viewer" : "owner") + "-ready");
                    require(peer.get("passed").getAsBoolean() && peer.get("subscriptions").getAsInt() == 4,
                            "Peer did not establish four real subscriptions");
                    long togetherAt = Math.max(readyAtEpochMillis, peer.get("readyAtEpochMillis").getAsLong());
                    long elapsedMillis = System.currentTimeMillis() - togetherAt;
                    require(elapsedMillis >= Math.addExact(warmupSeconds, measureSeconds) * 1000,
                            "Server ended before both clients held four subscriptions for the configured warmup and measurement: " + elapsedMillis);
                    for (UUID id : subscriptions.values()) {
                        WireStats stats = wire.stats(id);
                        require(stats != null && stats.snapshots() == 1 && stats.deltas() > 0 && stats.acknowledgments() > 1
                                        && stats.droppedAcks() == 0 && stats.closed() == null && stats.maxInFlight() == 1
                                        && measuredTransport.containsKey(id) && lastTransportProgress.get(id) > measuredTransportStartedAt,
                                "Normal ACK/delta transport did not progress during economic measurement: " + stats);
                    }
                    require(wire.commandPackets() == holdCommands && wire.subscribePackets() == holdSubscribes,
                            "Client injected gameplay commands or recreated subscriptions during measurement");
                    observation("concurrent-economic-measurement", true,
                            "elapsedTogetherMillis=" + elapsedMillis + ";warmupSeconds=" + warmupSeconds + ";measureSeconds=" + measureSeconds
                                    + ";diagnostic=" + diagnostic + ";dataPacketDelta=" + (wire.dataPackets() - holdDataPackets)
                                    + ";actualAckDelta=" + (wire.acknowledgments() - holdAcknowledgments) + ";wire=" + wire.totals());
                    advance(owner ? Phase.OWNER_WAIT : Phase.SLOW_START);
                }
                case SLOW_START -> {
                    // Fresh IDs avoid mixing any previously sent normal packet into the slow-client
                    // measurement. The fault suppresses only genuine production-generated ViewAck.
                    for (UUID id : subscriptions.values()) client.unsubscribe(id);
                    subscriptions.clear();
                    wire.dropNewSubscriptionAcks = true;
                    for (ViewType type : ViewType.values()) subscribe(type);
                    advance(Phase.SLOW_WAIT);
                }
                case SLOW_WAIT -> {
                    for (UUID id : subscriptions.values()) {
                        WireStats stats = wire.stats(id);
                        if (stats == null || stats.closed() == null || client.page(id) != null) return;
                    }
                    for (UUID id : subscriptions.values()) {
                        WireStats stats = wire.stats(id);
                        require("ACK_TIMEOUT".equals(stats.closed()) && stats.snapshots() == 1 && stats.deltas() == 0
                                        && stats.maxInFlight() == 1 && stats.acknowledgments() == 0 && stats.droppedAcks() == 1
                                        && client.page(id) == null,
                                "Unacknowledged real subscription did not stay bounded and close after the actual server timeout: " + stats);
                        observation("slow-client-" + stats.type().name().toLowerCase(java.util.Locale.ROOT), true,
                                "subscription=" + id + ";wire=" + stats);
                    }
                    wire.assertBounds();
                    quietPackets = wire.dataPackets();
                    advance(Phase.SLOW_QUIET);
                }
                case SLOW_QUIET -> {
                    require(wire.dataPackets() == quietPackets, "Server sent further private view data after all four ACK_TIMEOUT closures");
                    for (UUID id : subscriptions.values()) require(client.page(id) == null, "Timed-out production client retained a page");
                    if (now - phaseAt < 2 * SECOND) return;
                    observation("slow-client-bounded-and-purged", true,
                            "fourTimeouts=4;postCloseDataPackets=0;maxInFlightBytes=" + wire.maxInFlightBytes()
                                    + ";protocolBufferBound=" + ManagementProtocol.VIEW_BUFFER_BYTES + ";wire=" + wire.totals());
                    finish();
                }
                case OWNER_WAIT -> {
                    verifyFour();
                    wire.assertBounds();
                    if (Files.isRegularFile(root.resolve("viewer-failure.json"))) throw new IllegalStateException("Viewer failed: " + Files.readString(root.resolve("viewer-failure.json")));
                    if (!Files.isRegularFile(root.resolve("viewer-done"))) return;
                    require(json("viewer-done").get("passed").getAsBoolean(), "Viewer completion was not successful");
                    observation("owner-normal-ack-through-viewer-timeout", true, "All four OWNER views remained live;wire=" + wire.totals());
                    finish();
                }
            }
        } catch (Throwable failure) { fail(failure); }
    }

    private void initialize(Minecraft minecraft) throws IOException {
        String name = minecraft.getGameProfile().getName();
        require(name.equals("ScaleOwner") || name.equals("ScaleViewer"), "Unexpected scale client profile: " + name);
        owner = name.equals("ScaleOwner");
        role = owner ? "owner" : "viewer";
        diagnostic = Boolean.getBoolean("colonyloom.test.scaleDiagnostic");
        warmupSeconds = Long.parseLong(System.getProperty("colonyloom.test.scaleWarmupSeconds", "300"));
        measureSeconds = Long.parseLong(System.getProperty("colonyloom.test.scaleMeasureSeconds", "1800"));
        require(warmupSeconds >= 0 && measureSeconds > 0 && warmupSeconds <= 86_400 && measureSeconds <= 86_400,
                "Invalid scale client duration configuration");
        require(diagnostic || warmupSeconds >= 300 && measureSeconds >= 1800,
                "Shortened UI warmup/measurement requires explicit non-acceptance scaleDiagnostic=true");
        require(!Files.exists(root.resolve(role + "-done")) && !Files.exists(root.resolve(role + "-ready"))
                        && !Files.exists(root.resolve(role + "-failure.json")), "Scale client root contains stale role markers");
        startedAt = phaseAt = System.nanoTime();
        initialized = true;
        observation("actual-profile", true, "name=" + name + ";UUID=" + minecraft.getGameProfile().getId() + ";diagnostic=" + diagnostic);
    }

    private void beginTabs() {
        tabIndex = 0;
        tabRenderBaseline = rendered.getOrDefault(ViewType.SUMMARY, 0L);
        advance(Phase.TABS);
    }

    private void tabs() throws IOException {
        ViewType type = ViewType.values()[tabIndex];
        ViewData data = page(type);
        if (data == null || rendered.getOrDefault(type, 0L) <= tabRenderBaseline) return;
        validate(data, type);
        switch (type) {
            case SUMMARY -> {
                require(data.rows().stream().anyMatch(row -> row.id().equals(colony)), "Summary does not identify the allowed colony");
                require(data.rows().stream().anyMatch(row -> row.state().equals("STOCK_READY") && row.detail().contains("observed=")
                                && row.detail().contains("free=")), "SUMMARY did not contain actual typed stocks");
            }
            case CITIZENS -> {
                require(data.totalRows() == 100, "Allowed scale territory does not expose its 100 actual citizens: " + data.totalRows());
                require(data.rows().stream().anyMatch(row -> row.id().equals(citizen)), "Expected fixture citizen is absent from its authoritative page");
            }
            case BUILDINGS -> require(data.rows().stream().anyMatch(row -> row.state().equals("STORAGE"))
                            && data.rows().stream().anyMatch(row -> row.state().equals("REGISTERED")),
                    "Actual BUILDINGS page lacks registered stock storage/workshops");
            case WORK -> require(data.totalRows() > 0 && !data.rows().isEmpty(), "Scale WORK page has no actual economic work");
        }
        if (!owner) {
            List<String> actions = switch (type) {
                case SUMMARY -> List.of("member", "owner");
                case CITIZENS -> List.of("profession", "workplace");
                case BUILDINGS -> List.of("build", "storage", "workshop");
                case WORK -> List.of("cancel", "priority");
            };
            for (String action : actions) require(!button(action).active, "Actual VIEWER mutation widget enabled: " + action);
        }
        observation("actual-rendered-" + type.name().toLowerCase(java.util.Locale.ROOT), true,
                "colony=" + data.colonyId() + ";type=" + data.type() + ";rank=" + data.rank() + ";rows=" + data.totalRows()
                        + ";authorityRevision=" + data.authorityRevision() + ";stateRevision=" + data.stateRevision()
                        + ";renderFrames=" + rendered.getOrDefault(type, 0L));
        if (++tabIndex < ViewType.values().length) {
            ViewType next = ViewType.values()[tabIndex];
            tabRenderBaseline = rendered.getOrDefault(next, 0L);
            press("tab." + next.name().toLowerCase(java.util.Locale.ROOT));
            phaseAt = System.nanoTime();
            return;
        }
        press("tab.summary");
        UUID summary = client.subscriptions().entrySet().stream()
                .filter(entry -> entry.getValue().type() == ViewType.SUMMARY && entry.getValue().colonyId().equals(colony))
                .map(Map.Entry::getKey).findFirst().orElseThrow();
        subscriptions.put(ViewType.SUMMARY, summary);
        for (ViewType next : ViewType.values()) if (next != ViewType.SUMMARY) subscribe(next);
        advance(Phase.FOUR);
    }

    private void subscribe(ViewType type) {
        UUID id = client.subscribe(colony, type, 0);
        require(id != null, "Production four-subscription capacity exhausted for " + type);
        subscriptions.put(type, id);
    }

    private boolean fourReady() {
        return subscriptions.size() == ManagementProtocol.SUBSCRIPTIONS && subscriptions.values().stream().allMatch(id -> client.page(id) != null);
    }

    private void verifyFour() {
        require(Minecraft.getInstance().screen instanceof ColonyScreen screen && screen.client() == client,
                "Actual production ColonyScreen was replaced during scale measurement");
        require(connection != null && connection.isConnected() && client.ready(), "Real scale connection/session is not live");
        require(client.subscriptions().size() == 4 && client.subscriptions().keySet().equals(Set.copyOf(subscriptions.values())),
                "Actual production client does not retain exactly the four measured subscriptions");
        for (var entry : subscriptions.entrySet()) {
            ViewData data = client.page(entry.getValue());
            require(data != null, "Live scale page disappeared: " + entry.getKey() + ";wire=" + wire.stats(entry.getValue()));
            validate(data, entry.getKey());
            WireStats stats = wire.stats(entry.getValue());
            require(stats != null && stats.closed() == null && stats.droppedAcks() == 0, "Normal measured subscription closed/dropped ACK: " + stats);
        }
    }

    private void validate(ViewData data, ViewType type) {
        require(data.colonyId().equals(colony) && data.type() == type && data.page() == 0
                        && data.rank().equals(owner ? "owner" : "viewer") && data.rows().size() <= ManagementProtocol.PAGE_ROWS,
                "Typed page does not match its allowed colony/type/rank: " + type);
    }

    private ViewData page(ViewType type) {
        if (client == null) return null;
        return client.subscriptions().entrySet().stream()
                .filter(entry -> entry.getValue().colonyId().equals(colony) && entry.getValue().type() == type)
                .map(entry -> client.page(entry.getKey())).filter(java.util.Objects::nonNull).findFirst().orElse(null);
    }

    private Button button(String key) {
        require(Minecraft.getInstance().screen instanceof ColonyScreen, "Expected actual ColonyScreen widget");
        String text = ManagementText.ui(key).getString();
        return Minecraft.getInstance().screen.children().stream().filter(Button.class::isInstance).map(Button.class::cast)
                .filter(control -> control.getMessage().getString().equals(text)).findFirst()
                .orElseThrow(() -> new IllegalStateException("Actual production widget missing: " + key));
    }

    private void press(String key) {
        Button control = button(key);
        require(control.active && control.visible, "Actual production widget inactive: " + key);
        double x = control.getX() + control.getWidth() / 2.0, y = control.getY() + control.getHeight() / 2.0;
        var screen = Minecraft.getInstance().screen;
        require(screen.mouseClicked(x, y, 0), "Production screen did not consume actual widget click: " + key);
        screen.mouseReleased(x, y, 0);
    }

    private void install(Connection candidate) {
        if (connection == candidate) return;
        require(connection == null, "Scale client unexpectedly replaced its real connection");
        connection = candidate;
        candidate.channel().eventLoop().execute(() -> {
            try {
                String anchor = candidate.channel().pipeline().context(candidate).name();
                candidate.channel().pipeline().addBefore(anchor, "colonyloom_scale_client", wire);
                installed = true;
            } catch (Throwable failure) { networkFailure = failure; }
        });
    }

    private void payload(ManagementClientPayloadEvent event) {
        if (stopped || role == null) return;
        if (event.payload() instanceof ManagementPayloads.CommandResult response && response.result().sequence() == commandSequence) {
            commandResult = response.result();
        }
    }

    private void render(RenderFrameEvent.Post event) {
        if (stopped || client == null || !(Minecraft.getInstance().screen instanceof ColonyScreen screen) || screen.client() != client) return;
        try {
            for (ViewType type : ViewType.values()) {
                if (!button("tab." + type.name().toLowerCase(java.util.Locale.ROOT)).active && page(type) != null) {
                    rendered.merge(type, 1L, Long::sum);
                }
            }
            if (!screenshotRequested || screenshotRunning) return;
            require(page(ViewType.SUMMARY) != null && !button("tab.summary").active, "Screenshot is not a rendered production summary");
            screenshotRequested = false;
            screenshotRunning = true;
            String name = role + "-scale.png";
            Minecraft minecraft = Minecraft.getInstance();
            Screenshot.grab(root.toFile(), name, minecraft.getMainRenderTarget(), message -> minecraft.execute(() -> {
                if (stopped) return;
                try {
                    Path source = root.resolve("screenshots").resolve(name);
                    require(Files.isRegularFile(source) && Files.size(source) > 0, "Minecraft render-target screenshot failed: " + message.getString());
                    BufferedImage image = ImageIO.read(source.toFile());
                    require(image != null && image.getWidth() >= 320 && image.getHeight() >= 180, "Screenshot is not a usable rendered Minecraft image");
                    Set<Integer> colors = new HashSet<>();
                    int samples = 0, nonblack = 0;
                    for (int y = 0; y < image.getHeight(); y += Math.max(1, image.getHeight() / 100)) {
                        for (int x = 0; x < image.getWidth(); x += Math.max(1, image.getWidth() / 100)) {
                            int rgb = image.getRGB(x, y) & 0xffffff;
                            samples++;
                            if (rgb != 0) nonblack++;
                            if (colors.size() < 256) colors.add(rgb);
                        }
                    }
                    require(colors.size() >= 16 && nonblack > samples / 10, "Actual Minecraft screenshot is black/unrendered");
                    Files.move(source, root.resolve(name), StandardCopyOption.ATOMIC_MOVE);
                    observation("actual-gui-screenshot", true, "file=" + name + ";dimensions=" + image.getWidth() + "x" + image.getHeight()
                            + ";sampleColors=" + colors.size() + ";nonblack=" + nonblack + "/" + samples + ";source=Minecraft.mainRenderTarget");
                    screenshotComplete = true;
                    screenshotRunning = false;
                } catch (Throwable failure) { fail(failure); }
            }));
        } catch (Throwable failure) { fail(failure); }
    }

    private JsonObject json(String name) throws IOException {
        return JsonParser.parseString(Files.readString(root.resolve(name), StandardCharsets.UTF_8)).getAsJsonObject();
    }

    private void advance(Phase next) { phase = next; phaseAt = System.nanoTime(); }

    private void publish(String name, JsonObject value) throws IOException {
        Path temporary = root.resolve(name + ".tmp");
        Files.writeString(temporary, value.toString(), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        Files.move(temporary, root.resolve(name), StandardCopyOption.ATOMIC_MOVE);
    }

    private void observation(String check, boolean passed, String detail) throws IOException {
        JsonObject value = new JsonObject();
        value.addProperty("check", check);
        value.addProperty("passed", passed);
        value.addProperty("detail", detail);
        Files.writeString(root.resolve((role == null ? "client" : role) + "-observations.jsonl"), value + "\n",
                StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private void finish() throws IOException {
        wire.assertBounds();
        observation("scenario-complete", true, "Actual graphical " + role + " completed every assertion;diagnostic=" + diagnostic + ";wire=" + wire.totals());
        JsonObject done = new JsonObject();
        done.addProperty("passed", true);
        done.addProperty("diagnostic", diagnostic);
        done.addProperty("detail", "Every required graphical, authorization, duration and actual packet assertion passed");
        publish(role + "-done", done);
        stopped = true;
        Minecraft.getInstance().stop();
    }

    private void fail(Throwable failure) {
        if (stopped) return;
        stopped = true;
        try {
            String detail = "phase=" + phase + ";failure=" + failure + ";wire=" + wire.totals();
            observation("scenario-failure", false, detail);
            JsonObject report = new JsonObject();
            report.addProperty("passed", false);
            report.addProperty("phase", phase.name());
            report.addProperty("detail", detail);
            Files.writeString(root.resolve((role == null ? "client" : role) + "-failure.json"), report.toString(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        } catch (Throwable reportFailure) { failure.addSuppressed(reportFailure); }
        failure.printStackTrace();
        // Deliberate normal shutdown: the runner decides pass/fail from reports, not a crash.
        Minecraft.getInstance().stop();
    }

    private static UUID offline(String name) { return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8)); }
    private static void require(boolean condition, String detail) { if (!condition) throw new IllegalStateException(detail); }

    private enum Phase { CONNECT, FIXTURE, SCREEN, FOREIGN_WAIT, MUTATION_WAIT, TABS, FOUR, SCREENSHOT, HOLD, SLOW_START, SLOW_WAIT, SLOW_QUIET, OWNER_WAIT }

    private record WireStats(ViewType type, long snapshots, long deltas, long acknowledgments, long droppedAcks,
                             int maxInFlight, String closed, long firstDataAtNanos, long closedAtNanos) {
        long dataPackets() { return snapshots + deltas; }
    }

    private static final class Flight {
        final UUID colony;
        final ViewType type;
        final boolean slow;
        long snapshots, deltas, acknowledgments, droppedAcks, authority, revision;
        long firstDataAtNanos, closedAtNanos;
        int inFlightBytes, maxInFlight;
        String closed;
        boolean retired;
        Flight(ManagementProtocol.Subscription subscription, boolean slow) {
            colony = subscription.colonyId(); type = subscription.type(); this.slow = slow;
        }
        WireStats stats() { return new WireStats(type, snapshots, deltas, acknowledgments, droppedAcks, maxInFlight, closed, firstDataAtNanos, closedAtNanos); }
    }

    /** Observes decoded real packets on the actual channel; only the viewer ACK fault alters traffic. */
    private final class WireObserver extends ChannelDuplexHandler {
        private final Map<UUID, Flight> flights = new HashMap<>();
        private volatile boolean dropNewSubscriptionAcks;
        private long dataPackets, acknowledgments, droppedAcks, commandPackets, subscribePackets;
        private long inFlightBytes, maxInFlightBytes, maxPendingOutboundBytes;

        @Override public void channelRead(ChannelHandlerContext context, Object packet) throws Exception {
            try {
                synchronized (this) {
                    sampleQueue(context);
                    if (packet instanceof ClientboundBundlePacket bundle) {
                        for (var child : bundle.subPackets()) receive(child);
                    } else receive(packet);
                }
            } catch (Throwable failure) { networkFailure = failure; }
            super.channelRead(context, packet);
        }

        private void receive(Object packet) {
            if (!(packet instanceof ClientboundCustomPayloadPacket custom)) return;
            CustomPacketPayload payload = custom.payload();
            if (payload instanceof ManagementPayloads.ViewSnapshot snapshot) {
                data(snapshot.subscriptionId(), snapshot.data(), true);
            } else if (payload instanceof ManagementPayloads.ViewDelta delta) {
                data(delta.subscriptionId(), delta.data(), false);
            } else if (payload instanceof ManagementPayloads.ViewClosed closed) {
                Flight flight = flights.get(closed.subscriptionId());
                if (flight == null) return;
                require(flight.closed == null, "Server repeated closure of actual subscription " + closed.subscriptionId());
                flight.closed = closed.reason();
                flight.closedAtNanos = System.nanoTime();
                inFlightBytes -= flight.inFlightBytes;
                flight.inFlightBytes = 0;
            }
        }

        private void data(UUID id, ViewData data, boolean snapshot) {
            Flight flight = flights.get(id);
            // Observe only the IDs installed through this driver's production API, not unrelated
            // setup traffic which might have preceded channel-observer installation.
            if (flight == null || flight.retired) return;
            require(flight.closed == null, "Actual data emitted after subscription closure: " + id);
            require(flight.colony.equals(data.colonyId()) && flight.type == data.type() && data.page() == 0,
                    "Real wire page crossed subscription colony/type/page boundaries: " + id);
            require(flight.inFlightBytes == 0, "Server emitted a second unacknowledged data packet: " + id);
            int bytes = snapshot ? ManagementSession.snapshotBytes(data) : ManagementSession.deltaBytes(data);
            require(bytes <= ManagementProtocol.VIEW_BYTES, "Actual view packet exceeded bounded protocol size: " + bytes);
            flight.inFlightBytes = bytes;
            flight.maxInFlight = 1;
            flight.authority = data.authorityRevision();
            flight.revision = data.stateRevision();
            if (flight.firstDataAtNanos == 0) flight.firstDataAtNanos = System.nanoTime();
            if (snapshot) flight.snapshots++; else flight.deltas++;
            dataPackets++;
            inFlightBytes += bytes;
            maxInFlightBytes = Math.max(maxInFlightBytes, inFlightBytes);
            assertBounds();
        }

        @Override public void write(ChannelHandlerContext context, Object packet, ChannelPromise promise) throws Exception {
            boolean suppress = false;
            try {
                synchronized (this) {
                    sampleQueue(context);
                    if (packet instanceof ServerboundCustomPayloadPacket custom) {
                        CustomPacketPayload payload = custom.payload();
                        if (payload instanceof ManagementPayloads.Subscribe subscribe) {
                            subscribePackets++;
                            require(!flights.containsKey(subscribe.subscription().subscriptionId()), "Unexpected production resubscribe/resync during acceptance");
                            flights.put(subscribe.subscription().subscriptionId(), new Flight(subscribe.subscription(), dropNewSubscriptionAcks));
                        } else if (payload instanceof ManagementPayloads.CommandPayload) commandPackets++;
                        else if (payload instanceof ManagementPayloads.Unsubscribe unsubscribe) {
                            Flight flight = flights.get(unsubscribe.subscriptionId());
                            if (flight != null) {
                                flight.retired = true;
                                inFlightBytes -= flight.inFlightBytes;
                                flight.inFlightBytes = 0;
                            }
                        } else if (payload instanceof ManagementPayloads.ViewAck ack) {
                            Flight flight = flights.get(ack.subscriptionId());
                            if (flight != null && !flight.retired) {
                                require(flight.closed == null && flight.inFlightBytes > 0 && flight.authority == ack.authorityRevision()
                                                && flight.revision == ack.stateRevision(), "Production ACK did not correspond to its real in-flight packet");
                                suppress = flight.slow;
                                if (suppress) { flight.droppedAcks++; droppedAcks++; }
                                else { flight.acknowledgments++; acknowledgments++; inFlightBytes -= flight.inFlightBytes; flight.inFlightBytes = 0; }
                            }
                        }
                    }
                    assertBounds();
                }
            } catch (Throwable failure) { networkFailure = failure; }
            if (suppress) {
                ReferenceCountUtil.release(packet);
                promise.setSuccess();
                return;
            }
            super.write(context, packet, promise);
        }

        private void sampleQueue(ChannelHandlerContext context) {
            var outbound = context.channel().unsafe().outboundBuffer();
            if (outbound != null) maxPendingOutboundBytes = Math.max(maxPendingOutboundBytes, outbound.totalPendingWriteBytes());
        }

        synchronized void assertBounds() {
            require(inFlightBytes >= 0 && maxInFlightBytes <= (long) ManagementProtocol.SUBSCRIPTIONS * ManagementProtocol.VIEW_BYTES
                            && maxInFlightBytes <= ManagementProtocol.VIEW_BUFFER_BYTES,
                    "Actual emitted unacknowledged view bytes exceeded bounded four-subscription capacity: " + maxInFlightBytes);
            require(maxPendingOutboundBytes <= ManagementProtocol.VIEW_BUFFER_BYTES,
                    "Actual client Netty outbound queue exceeded protocol buffer bound: " + maxPendingOutboundBytes);
        }
        synchronized WireStats stats(UUID id) { Flight flight = flights.get(id); return flight == null ? null : flight.stats(); }
        synchronized long dataPackets() { return dataPackets; }
        synchronized long acknowledgments() { return acknowledgments; }
        synchronized long commandPackets() { return commandPackets; }
        synchronized long subscribePackets() { return subscribePackets; }
        synchronized long maxInFlightBytes() { return maxInFlightBytes; }
        synchronized String totals() {
            return "dataPackets=" + dataPackets + ";actualAcks=" + acknowledgments + ";droppedActualAcks=" + droppedAcks
                    + ";commandPackets=" + commandPackets + ";subscribePackets=" + subscribePackets
                    + ";maxInFlightBytes=" + maxInFlightBytes + ";maxActualNettyPendingBytes=" + maxPendingOutboundBytes;
        }
    }
}
