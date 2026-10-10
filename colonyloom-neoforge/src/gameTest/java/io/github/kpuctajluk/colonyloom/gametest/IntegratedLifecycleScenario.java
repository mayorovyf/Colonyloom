package io.github.kpuctajluk.colonyloom.gametest;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.github.kpuctajluk.colonyloom.core.citizen.CitizenRecord;
import io.github.kpuctajluk.colonyloom.minecraft.entity.CitizenEntity;
import io.github.kpuctajluk.colonyloom.minecraft.runtime.MinecraftServerRuntime;
import io.github.kpuctajluk.colonyloom.neoforge.ConstructionExecutorEvent;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.imageio.ImageIO;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.AccessibilityOnboardingScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Items;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Actual graphical-client A -> B -> A integrated-server proof; only loaded from the dev GameTest source set. */
@Mod(value = "colonyloom_tests", dist = Dist.CLIENT)
public final class IntegratedLifecycleScenario {
    private static final Gson JSON = new Gson();
    private static final Object RECORD_IO = new Object();
    private static final String ROOT_MARKER = "colonyloom-integrated-lifecycle-owner-root";
    private static final String WORLD_MARKER = "colonyloom-test-world";
    private static final String OWNER = "IntegratedOwner";
    private static final String COLONY_NAME = "IntegratedLifecycle A";
    private static final long NBT_LIMIT = 64L * 1024 * 1024;
    private static final long PHASE_TIMEOUT_NANOS = 5L * 60 * 1_000_000_000;

    private final Path root;
    private final String token;
    private final Map<MinecraftServer, ServerRun> servers = new IdentityHashMap<>();
    private final List<String> systemChat = new ArrayList<>();
    private volatile String nextWorldRole;
    private volatile Throwable serverFailure;
    private volatile Throwable clientWireFailure;
    private Connection installedConnection;
    private boolean initialized, stopped, screenshotRequested, screenshotComplete;
    private int clientTicks;
    private long phaseStarted;
    private String phase = "BOOT";
    private volatile UUID colonyId, citizenId, entityId;
    private volatile long bindingEpoch, a1SessionTicks;
    private volatile String a1Session, originalInventory, inspectRefusal;
    private volatile io.github.kpuctajluk.colonyloom.minecraft.client.management.ManagementClient management;

    private static final class ServerRun {
        final MinecraftServerRuntime runtime;
        final String role;
        final Path world;
        boolean colonySeen, fixtureSeen;
        long ticks;
        ServerRun(MinecraftServerRuntime runtime, String role, Path world) { this.runtime = runtime; this.role = role; this.world = world; }
    }

    public IntegratedLifecycleScenario() {
        String configuredRoot = System.getProperty("colonyloom.test.integratedRoot", "");
        String configuredToken = System.getProperty("colonyloom.test.integratedToken", "");
        if (configuredRoot.isBlank() && configuredToken.isBlank()) { root = null; token = null; return; }
        if (configuredRoot.isBlank() || configuredToken.isBlank()) throw new IllegalStateException("Integrated lifecycle root/token must be supplied together");
        Path candidate = Path.of(configuredRoot);
        if (!candidate.isAbsolute()) throw new IllegalStateException("Integrated lifecycle root must be absolute");
        root = candidate.toAbsolutePath().normalize(); token = configuredToken;
        try {
            require(Files.isRegularFile(root.resolve(ROOT_MARKER)), "Owner-created integrated lifecycle root marker is missing");
            require(Files.readString(root.resolve(ROOT_MARKER), StandardCharsets.UTF_8).trim().equals(token), "Owner-created root marker token mismatch");
            require(Files.isDirectory(root.resolve("saves")), "Owner-runner did not create vanilla saves root");
        } catch (IOException failure) { throw new IllegalStateException("Unable to validate lifecycle disposable root", failure); }
        NeoForge.EVENT_BUS.addListener(this::runtimeBound);
        NeoForge.EVENT_BUS.addListener(this::serverTick);
        NeoForge.EVENT_BUS.addListener(this::serverStopped);
        NeoForge.EVENT_BUS.addListener(this::clientTick);
        NeoForge.EVENT_BUS.addListener(this::renderFrame);
    }

    private void runtimeBound(ConstructionExecutorEvent event) {
        if (root == null) return;
        MinecraftServer server = event.server(); String role = nextWorldRole;
        try {
            require(!server.isDedicatedServer(), "Acceptance requires an actual graphical-client integrated server");
            require(Set.of("A1", "B", "A2").contains(role), "Unexpected integrated runtime role " + role);
            Path saves = root.resolve("saves").toRealPath();
            Path world = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
            require(world.startsWith(saves) && !world.equals(saves) && !Files.isSymbolicLink(world), "Vanilla world escaped isolated real saves root: " + world);
            String folder = Files.readString(root.resolve(role.toLowerCase(java.util.Locale.ROOT) + "-target.txt"), StandardCharsets.UTF_8).trim();
            require(!folder.isBlank() && world.equals(saves.resolve(folder).normalize()), "Vanilla server opened wrong selected save directory");
            require(Files.readString(root.resolve(ROOT_MARKER), StandardCharsets.UTF_8).trim().equals(token), "Owner root marker changed before fixture mutation");
            Path marker = world.resolve(WORLD_MARKER);
            if (role.equals("A1") || role.equals("B")) {
                require(!Files.exists(marker), "Refusing preexisting/previously marked new world " + world);
                require(event.runtime().persistence().isAvailable() && event.runtime().core().registry().colonies().isEmpty()
                                && event.runtime().core().registry().citizensView().isEmpty()
                                && !Files.exists(world.resolve("data/colonyloom.dat"))
                                && !Files.exists(world.resolve("data/colonyloom-session.nbt")),
                        "New world has Colonyloom state or unavailable persistence");
                Files.writeString(marker, "Owner-marked disposable integrated lifecycle world " + role + "\n", StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            } else {
                require(Files.isRegularFile(marker) && world.equals(saves.resolve(Files.readString(root.resolve("a1-target.txt"), StandardCharsets.UTF_8).trim()).normalize()),
                        "A2 is not the owner-marked original A path");
                JsonObject expected = jsonFile("a1-checkpoint.json").getAsJsonObject("identity");
                CompoundTag dto = read(world.resolve("data/colonyloom.dat")).getCompound("data"); verifyDtoIdentity(dto, expected);
                JsonObject previous = jsonFile("a1-server-state.json");
                require(!event.runtime().sessionId().toString().equals(previous.get("session").getAsString()), "A2 reused A1 runtime session");
                require(event.runtime().core().registry().colonies().size() == 1, "A2 did not restore exactly one colony");
                var record = event.runtime().core().registry().citizen(UUID.fromString(expected.get("citizen").getAsString()));
                require(record.colonyId().toString().equals(expected.get("colony").getAsString()) && record.entityId().toString().equals(expected.get("entity").getAsString())
                                && record.bindingEpoch() == expected.get("epoch").getAsLong() && record.activeTimeTicks() == expected.get("activeTimeTicks").getAsLong(),
                        "A2 startup changed canonical identity/property or unloaded active clock");
            }
            ServerRun run = new ServerRun(event.runtime(), role, world); servers.put(server, run);
            writeServerState(run, "runtime-bound", Map.of("world", world.toString(), "activeRuntimes", 1));
            fact(role + "-runtime-bound-integrated", true, "path=" + world + ";session=" + event.runtime().sessionId() + ";sessionTicks=" + event.runtime().serverTick());
        } catch (Throwable failure) { serverFailure = failure; fact(role == null ? "unexpected-runtime-bound" : role + "-runtime-bound", false, failure.toString()); }
    }

    private void serverTick(ServerTickEvent.Post event) {
        if (root == null) return;
        ServerRun run = servers.get(event.getServer()); if (run == null || serverFailure != null) return;
        try {
            require(++run.ticks <= 24_000, run.role + " lifecycle phase exceeded 24000 server ticks");
            switch (run.role) {
                case "A1" -> observeFirstWorld(event.getServer(), run);
                case "B" -> observeIsolatedWorld(event.getServer(), run);
                case "A2" -> observeRestoredWorld(event.getServer(), run);
                default -> throw new IllegalStateException("Unexpected role " + run.role);
            }
        } catch (Throwable failure) { serverFailure = failure; writeServerState(run, "failure", Map.of("error", failure.toString())); fact(run.role + "-server-observer", false, failure.toString()); }
    }

    private void observeFirstWorld(MinecraftServer server, ServerRun run) throws IOException {
        var registry = run.runtime.core().registry();
        if (!run.colonySeen) {
            var colonies = registry.colonies(); if (colonies.isEmpty()) return;
            require(colonies.size() == 1 && registry.citizensView().isEmpty(), "Accepted public bootstrap did not create exactly one empty colony");
            var colony = colonies.getFirst(); require(colony.name().equals(COLONY_NAME) && colony.territory().dimension().equals(server.overworld().dimension().location().toString()), "Public bootstrap colony differs from owner fixture");
            var actor = server.getPlayerList().getPlayers().getFirst();
            var spawn = safeCitizenPosition(server.overworld(), actor.blockPosition());
            colonyId = colony.colonyId(); run.colonySeen = true; writeServerState(run, "colony-created", Map.of("colony", colonyId.toString(), "x", spawn.getX(), "y", spawn.getY(), "z", spawn.getZ()));
            fact("A1-public-bootstrap-colony", true, "colony=" + colonyId + ";name=" + colony.name());
        }
        if (run.fixtureSeen) return;
        var records = registry.citizens(colonyId); if (records.isEmpty()) return;
        require(records.size() == 1 && registry.citizens().size() == 1, "Accepted resident bootstrap did not create exactly one canonical resident");
        CitizenRecord record = records.getFirst();
        require(record.lifecycle() == CitizenRecord.Lifecycle.ALIVE && record.colonyId().equals(colonyId), "A resident is not ALIVE in new colony");
        if (!(server.overworld().getEntity(record.entityId()) instanceof CitizenEntity citizen)) return;
        require(citizen.isAlive() && citizen.citizenId().equals(record.citizenId()) && citizen.bindingEpoch() == record.bindingEpoch(), "Resident is not production-bound original native CitizenEntity");
        writeServerState(run, "resident-ready", Map.of("event", "resident-ready", "citizen", record.citizenId().toString(), "entity", record.entityId().toString(), "epoch", record.bindingEpoch()));
        if (!contains(citizen.inventory().getItems(), Items.OAK_STAIRS, 7)) return;
        JsonObject identity = identity(record, inventoryData(server, citizen));
        colonyId = record.colonyId(); citizenId = record.citizenId(); entityId = record.entityId(); bindingEpoch = record.bindingEpoch(); run.fixtureSeen = true;
        writeServerState(run, "fixture-ready", Map.of("identity", identity)); fact("A1-real-native-citizen-cargo", true, "citizen=" + citizenId + ";entity=" + entityId + ";epoch=" + bindingEpoch);
    }

    private void observeIsolatedWorld(MinecraftServer server, ServerRun run) {
        if (run.fixtureSeen) return;
        var registry = run.runtime.core().registry();
        require(registry.colonies().isEmpty() && registry.citizensView().isEmpty(), "B runtime restored A registry state");
        long citizenEntities = 0;
        for (ServerLevel level : server.getAllLevels()) for (var entity : level.getAllEntities()) {
            require(!entity.getUUID().equals(entityId), "B physically contains A's entity UUID"); if (entity instanceof CitizenEntity) citizenEntities++;
        }
        require(citizenEntities == 0 && !run.runtime.sessionId().toString().equals(a1Session), "B contains native citizens or reuses A session");
        run.fixtureSeen = true;
        writeServerState(run, "isolated", Map.of("session", run.runtime.sessionId().toString(), "colonies", 0, "citizens", 0,
                "citizenEntities", citizenEntities, "a1Session", a1Session, "a1Colony", colonyId.toString(), "a1Citizen", citizenId.toString(), "a1Entity", entityId.toString(), "activeTimeTicks", "ABSENT"));
        fact("B-registry-entity-clock-session-absent", true, "entities=" + citizenEntities + ";clock=ABSENT;session=" + run.runtime.sessionId());
    }

    private void observeRestoredWorld(MinecraftServer server, ServerRun run) throws IOException {
        if (run.fixtureSeen) return;
        JsonObject expected = jsonFile("a1-checkpoint.json").getAsJsonObject("identity");
        UUID originalCitizen = UUID.fromString(expected.get("citizen").getAsString()), originalEntity = UUID.fromString(expected.get("entity").getAsString());
        CitizenRecord record = run.runtime.core().registry().citizen(originalCitizen);
        require(record.colonyId().toString().equals(expected.get("colony").getAsString()) && record.entityId().equals(originalEntity)
                        && record.bindingEpoch() == expected.get("epoch").getAsLong() && record.activeTimeTicks() >= expected.get("activeTimeTicks").getAsLong(),
                "Reopened A changed original canonical property or moved its active clock backwards");
        if (!(server.overworld().getEntity(originalEntity) instanceof CitizenEntity citizen)) return;
        require(citizen.isAlive() && !citizen.isRemoved() && citizen.citizenId().equals(originalCitizen) && citizen.bindingEpoch() == expected.get("epoch").getAsLong(), "A2 loaded a replacement/dead/wrong-epoch native entity");
        long duplicates = 0; for (ServerLevel level : server.getAllLevels()) for (var entity : level.getAllEntities()) if (entity instanceof CitizenEntity nativeCitizen && originalCitizen.equals(nativeCitizen.citizenId())) duplicates++;
        require(duplicates == 1, "A2 has duplicate physical original citizens: " + duplicates);
        String restoredInventory = inventoryData(server, citizen).toString();
        require(restoredInventory.equals(expected.get("inventory").getAsString()), "A2 changed exact original native NPC inventory");
        run.fixtureSeen = true; writeServerState(run, "reopened-original", Map.of("identity", identity(record, inventoryData(server, citizen)), "session", run.runtime.sessionId().toString(), "entityCount", duplicates));
        fact("A2-same-native-entity-property-clock", true, "citizen=" + originalCitizen + ";entity=" + originalEntity + ";inventory=" + restoredInventory);
    }
    private void serverStopped(ServerStoppedEvent event) {
        if (root == null) return;
        ServerRun run = servers.remove(event.getServer()); if (run == null) return;
        try {
            require(Files.isRegularFile(run.world.resolve(WORLD_MARKER)), "Normal-stop world marker disappeared");
            CompoundTag marker = read(run.world.resolve("data/colonyloom-session.nbt")), dto = read(run.world.resolve("data/colonyloom.dat")).getCompound("data");
            require(marker.getBoolean("clean") && marker.hasUUID("checkpointId") && marker.getUUID("checkpointId").equals(dto.getUUID("checkpointId")), run.role + " lacks exact matching normal clean checkpoint and DTO");
            JsonObject state = object("event", "normal-stop-clean", "role", run.role, "session", run.runtime.sessionId().toString(), "checkpointId", marker.getUUID("checkpointId").toString(), "world", run.world.toString(), "clean", true);
            if (run.role.equals("A1")) {
                require(run.fixtureSeen, "A1 stopped before owner bootstrap/citizen/cargo fixture");
                JsonObject identity = jsonFile("a1-server-state.json").getAsJsonObject("identity");
                CompoundTag saved = findByUuid(dto.getList("citizens", CompoundTag.TAG_COMPOUND), "citizenId", UUID.fromString(identity.get("citizen").getAsString()));
                identity.remove("activeTimeTicks"); verifyDtoIdentity(dto, identity); a1SessionTicks = saved.getLong("activeTimeTicks");
                require(a1SessionTicks >= 0, "A1 active clock is negative"); a1Session = run.runtime.sessionId().toString();
                identity.addProperty("activeTimeTicks", a1SessionTicks); state.add("identity", identity);
            } else if (run.role.equals("B")) require(run.fixtureSeen && run.runtime.core().registry().colonies().isEmpty(), "B stopped before isolated-world checks");
            else {
                require(run.fixtureSeen, "A2 stopped before original citizen/cargo verification");
                CompoundTag saved = findByUuid(dto.getList("citizens", CompoundTag.TAG_COMPOUND), "citizenId", citizenId);
                require(saved.getLong("activeTimeTicks") >= a1SessionTicks, "A2 active clock moved backwards across reopen");
                state.add("identity", identityFromRecord(run.runtime.core().registry().citizen(citizenId), originalInventory, saved.getLong("activeTimeTicks")));
            }
            writeJson(root.resolve(run.role.toLowerCase(java.util.Locale.ROOT) + "-server-state.json"), state);
            fact(run.role + "-normal-stop-clean-durable", true, "checkpoint=" + marker.getUUID("checkpointId") + ";world=" + run.world);
        } catch (Throwable failure) { serverFailure = failure; fact(run.role + "-normal-stop-clean-durable", false, failure.toString()); }
        finally { writeRuntimeReleasedEvidence(run); }
    }

    private boolean writeRuntimeReleasedEvidence(ServerRun run) {
        try {
            Path latest = root.resolve("logs/latest.log");
            if (!Files.isRegularFile(latest)) return false;
            String log = Files.readString(latest, StandardCharsets.UTF_8);
            String marker = "Colonyloom runtime released: session=" + run.runtime.sessionId();
            int at = log.lastIndexOf(marker); if (at < 0) return false;
            int end = log.indexOf('\n', at); String line = log.substring(at, end < 0 ? log.length() : end);
            if (!line.contains("activeRuntimes=0")) throw new IllegalStateException("Runtime release did not reach activeRuntimes=0: " + line);
            writeJson(root.resolve(run.role.toLowerCase(java.util.Locale.ROOT) + "-released.json"), object("released", true, "activeRuntimes", 0, "session", run.runtime.sessionId().toString(), "logLine", line));
            fact(run.role + "-runtime-released-activeRuntimes-zero", true, line); return true;
        } catch (Throwable failure) { serverFailure = failure; fact(run.role + "-runtime-released-activeRuntimes-zero", false, failure.toString()); return false; }
    }
    private boolean runtimeReleased(String role) {
        try {
            Path file = root.resolve(role + "-released.json");
            if (Files.isRegularFile(file)) {
                JsonObject released = jsonFile(role + "-released.json");
                return released.get("released").getAsBoolean() && released.get("activeRuntimes").getAsInt() == 0;
            }
            return false;
        } catch (IOException failure) { throw new IllegalStateException("Unable to observe runtime release", failure); }
    }
    private void clientTick(ClientTickEvent.Post event) {
        if (root == null || stopped) return;
        Minecraft minecraft = Minecraft.getInstance();
        try {
            if (!initialized) initialize(minecraft); clientTicks++;
            require(clientTicks <= 36_000, "Integrated lifecycle exceeded 30-minute client tick bound");
            require(System.nanoTime() - phaseStarted <= PHASE_TIMEOUT_NANOS, "Lifecycle phase timeout: " + phase);
            require(minecraft.getWindow().getWidth() >= 640 && minecraft.getWindow().getHeight() >= 360, "Client framebuffer is too small");
            long window = minecraft.getWindow().getWindow();
            if (org.lwjgl.glfw.GLFW.glfwGetWindowAttrib(window, org.lwjgl.glfw.GLFW.GLFW_ICONIFIED) != 0) { org.lwjgl.glfw.GLFW.glfwRestoreWindow(window); return; }
            if (serverFailure != null) throw new IllegalStateException("Integrated server lifecycle observer failed: " + serverFailure, serverFailure);
            if (clientWireFailure != null) throw new IllegalStateException("System-chat packet capture failed: " + clientWireFailure, clientWireFailure);
            if (minecraft.screen instanceof DisconnectedScreen disconnected) throw new IllegalStateException("Unexpected disconnect during " + phase + ": " + disconnected.getTitle().getString());
            installWire(minecraft);
            switch (phase) {
                case "BOOT" -> {
                    if (!minecraft.isGameLoadFinished() || minecraft.getOverlay() != null) return;
                    if (minecraft.screen instanceof AccessibilityOnboardingScreen onboarding) { onboarding.onClose(); return; }
                    if (!(minecraft.screen instanceof TitleScreen)) return;
                    beginWorldCreation(minecraft, "A1"); advance("CREATE_A1");
                }
                case "CREATE_A1" -> createFromVanillaFlow(minecraft, "A1");
                case "WAIT_A1" -> {
                    if (!inWorld(minecraft) || !serverEvent("a1-server-state.json", "runtime-bound")) return;
                    var pos = minecraft.player.blockPosition();
                    command(minecraft, "colonyloom colony create \"" + COLONY_NAME + "\" " + (pos.getX() - 24) + " " + pos.getY() + " " + (pos.getZ() - 24) + " " + (pos.getX() + 24) + " " + pos.getY() + " " + (pos.getZ() + 24));
                    advance("WAIT_COLONY");
                }
                case "WAIT_COLONY" -> {
                    if (!serverEvent("a1-server-state.json", "colony-created")) return;
                    var colony = serverState("a1-server-state.json"); colonyId = UUID.fromString(colony.get("colony").getAsString());
                    command(minecraft, "colonyloom citizen create " + colonyId + " " + colony.get("x").getAsInt() + " " + colony.get("y").getAsInt() + " " + colony.get("z").getAsInt()); advance("WAIT_CITIZEN");
                }
                case "WAIT_CITIZEN" -> {
                    JsonObject resident = jsonFile("a1-server-state.json"); if (!resident.get("event").getAsString().equals("resident-ready")) return;
                    String message = latestChatMessageContaining("citizen=", "entity=", "epoch="); if (message == null) return;
                    citizenId = uuidField(message, "citizen"); entityId = uuidField(message, "entity"); bindingEpoch = longField(message, "epoch");
                    require(citizenId != null && entityId != null && citizenId.toString().equals(resident.get("citizen").getAsString()) && entityId.toString().equals(resident.get("entity").getAsString()) && bindingEpoch == resident.get("epoch").getAsLong(), "Accepted resident command output differs from server-observed canonical READY identity");
                    command(minecraft, "give @s minecraft:oak_stairs 7"); advance("WAIT_GIVE");
                }
                case "WAIT_GIVE" -> {
                    if (minecraft.player.getInventory().items.stream().noneMatch(stack -> stack.is(Items.OAK_STAIRS) && stack.getCount() == 7)) return;
                    command(minecraft, "tp @s " + entityId); advance("WAIT_NEAR_CITIZEN");
                }
                case "WAIT_NEAR_CITIZEN" -> {
                    ClientLevel level = minecraft.level; net.minecraft.world.entity.Entity entity = null;
                    if (level != null) for (var candidate : level.entitiesForRendering()) if (candidate.getUUID().equals(entityId)) { entity = candidate; break; }
                    if (!(entity instanceof CitizenEntity) || minecraft.player.distanceToSqr(entity) > 9.0D) return;
                    minecraft.gameMode.interact(minecraft.player, entity, InteractionHand.MAIN_HAND);
                    advance("WAIT_NPC_MENU");
                }
                case "WAIT_NPC_MENU" -> {
                    if (!(minecraft.screen instanceof AbstractContainerScreen<?>) || minecraft.player == null || minecraft.player.containerMenu.getType() != MenuType.GENERIC_9x1) return;
                    var menu = minecraft.player.containerMenu; int slot = -1;
                    for (int index = 9; index < menu.slots.size(); index++) if (menu.getSlot(index).getItem().is(Items.OAK_STAIRS) && menu.getSlot(index).getItem().getCount() == 7) { slot = index; break; }
                    require(slot >= 0, "Native citizen menu did not expose exact seven stairs");
                    minecraft.gameMode.handleInventoryMouseClick(menu.containerId, slot, 0, ClickType.PICKUP, minecraft.player);
                    minecraft.gameMode.handleInventoryMouseClick(menu.containerId, 0, 0, ClickType.PICKUP, minecraft.player);
                    minecraft.player.closeContainer(); advance("WAIT_CARGO");
                }
                case "WAIT_CARGO" -> {
                    if (!serverEvent("a1-server-state.json", "fixture-ready")) return;
                    originalInventory = serverState("a1-server-state.json").getAsJsonObject("identity").get("inventory").getAsString();
                    command(minecraft, "colonyloom ui " + colonyId); advance("WAIT_SUMMARY_A1");
                }
                case "WAIT_SUMMARY_A1" -> {
                    if (!visibleSummary(minecraft, colonyId)) return;
                    require(summaryHasColony(minecraft, colonyId), "Actual A production summary lacks READY colony");
                    minecraft.setScreen(null); advance("STOP_A1");
                }
                case "STOP_A1" -> stopThroughPauseScreen(minecraft, "WAIT_STOP_A1");
                case "WAIT_STOP_A1" -> {
                    if (!(minecraft.screen instanceof TitleScreen) || minecraft.getSingleplayerServer() != null || !serverEvent("a1-server-state.json", "normal-stop-clean")) return;
                    JsonObject stoppedA = serverState("a1-server-state.json"), identity = stoppedA.getAsJsonObject("identity");
                    colonyId = UUID.fromString(identity.get("colony").getAsString()); citizenId = UUID.fromString(identity.get("citizen").getAsString());
                    entityId = UUID.fromString(identity.get("entity").getAsString()); bindingEpoch = identity.get("epoch").getAsLong();
                    a1SessionTicks = identity.get("activeTimeTicks").getAsLong(); a1Session = stoppedA.get("session").getAsString(); originalInventory = identity.get("inventory").getAsString();
                    writeJson(root.resolve("a1-checkpoint.json"), stoppedA); if (!runtimeReleased("a1")) return; beginWorldCreation(minecraft, "B"); advance("CREATE_B");
                }
                case "CREATE_B" -> createFromVanillaFlow(minecraft, "B");
                case "WAIT_B" -> {
                    if (!inWorld(minecraft) || !serverEvent("b-server-state.json", "isolated")) return;
                    require(!serverState("b-server-state.json").get("session").getAsString().equals(a1Session), "B reused A session");
                    command(minecraft, "colonyloom recovery inspect " + colonyId); advance("WAIT_INSPECT_B");
                }
                case "WAIT_INSPECT_B" -> {
                    String expected = "Unknown colony UUID: " + colonyId; if (latestChatMessageContaining(expected) == null) return;
                    inspectRefusal = expected; fact("B-public-recovery-inspect-refuses-known-A-colony", true, expected); stopThroughPauseScreen(minecraft, "WAIT_STOP_B");
                }
                case "WAIT_STOP_B" -> {
                    if (!(minecraft.screen instanceof TitleScreen) || minecraft.getSingleplayerServer() != null || !serverEvent("b-server-state.json", "normal-stop-clean")) return;
                    JsonObject b = serverState("b-server-state.json"); require(b.get("clean").getAsBoolean() && inspectRefusal != null, "B clean checkpoint/inspect refusal missing");
                    writeJson(root.resolve("b-checkpoint.json"), b); if (!runtimeReleased("b")) return;
                    String folder = Files.readString(root.resolve("a1-target.txt"), StandardCharsets.UTF_8).trim(); nextWorldRole = "A2"; writeText(root.resolve("a2-target.txt"), folder);
                    minecraft.createWorldOpenFlows().openWorld(folder, () -> {}); advance("WAIT_A2");
                }
                case "WAIT_A2" -> {
                    if (!inWorld(minecraft) || !serverEvent("a2-server-state.json", "reopened-original")) return;
                    JsonObject restored = serverState("a2-server-state.json").getAsJsonObject("identity");
                    require(restored.get("colony").getAsString().equals(colonyId.toString()) && restored.get("citizen").getAsString().equals(citizenId.toString())
                                    && restored.get("entity").getAsString().equals(entityId.toString()) && restored.get("epoch").getAsLong() == bindingEpoch
                                    && restored.get("activeTimeTicks").getAsLong() >= a1SessionTicks && restored.get("inventory").getAsString().equals(originalInventory),
                            "A2 canonical identity/cargo differs or resumed active clock moved backwards from A1 checkpoint");
                    command(minecraft, "colonyloom recovery inspect " + colonyId); advance("WAIT_INSPECT_A2");
                }
                case "WAIT_INSPECT_A2" -> {
                    if (latestChatMessageContaining("colonyId=" + colonyId) == null) return;
                    fact("A2-public-inspect-finds-original-colony", true, "colony=" + colonyId); command(minecraft, "colonyloom ui " + colonyId); advance("WAIT_SUMMARY_A2");
                }
                case "WAIT_SUMMARY_A2" -> {
                    if (!visibleSummary(minecraft, colonyId)) return;
                    management = ((io.github.kpuctajluk.colonyloom.minecraft.client.screen.ColonyScreen) minecraft.screen).client();
                    require(summaryHasColony(minecraft, colonyId), "A2 production UI lacks READY colony summary"); screenshotRequested = true; advance("SCREENSHOT_A2");
                }
                case "SCREENSHOT_A2" -> {
                    if (!screenshotComplete) return;
                    fact("scenario-complete", true, "same graphical JVM; normal A1/B shutdown; distinct B; original A2 identity/cargo/UI restored");
                    minecraft.setScreen(null); advance("STOP_A2");
                }
                case "STOP_A2" -> stopThroughPauseScreen(minecraft, "WAIT_STOP_A2");
                case "WAIT_STOP_A2" -> {
                    if (!(minecraft.screen instanceof TitleScreen) || minecraft.getSingleplayerServer() != null || !serverEvent("a2-server-state.json", "normal-stop-clean")) return;
                    JsonObject a2 = serverState("a2-server-state.json"); require(a2.get("clean").getAsBoolean(), "A2 normal shutdown not clean");
                    writeJson(root.resolve("a2-checkpoint.json"), a2); if (!runtimeReleased("a2")) return;
                    writeJson(root.resolve("client-done.json"), object("passed", true, "clientTicks", clientTicks,
                            "a1", jsonFile("a1-checkpoint.json"), "b", jsonFile("b-checkpoint.json"), "a2", jsonFile("a2-checkpoint.json")));
                    Minecraft.getInstance().stop(); stopped = true;
                }
                default -> throw new IllegalStateException("Unknown lifecycle phase " + phase);
            }
        } catch (Throwable failure) { fail(failure); }
    }

    private void initialize(Minecraft minecraft) throws IOException {
        require(minecraft.getGameProfile().getName().equals(OWNER), "Client username does not match owner profile");
        require(Files.readString(root.resolve(ROOT_MARKER), StandardCharsets.UTF_8).trim().equals(token), "Root marker token changed");
        require(minecraft.getLevelSource().getBaseDir().toRealPath().equals(root.resolve("saves").toRealPath()), "Vanilla world loader is outside isolated saves directory");
        require(!Files.exists(root.resolve("failure.json")) && !Files.exists(root.resolve("client-done.json")), "Stale result in lifecycle root");
        initialized = true; advance("BOOT"); fact("actual-graphical-integrated-client", true, "profile=" + minecraft.getGameProfile().getName() + ";uuid=" + minecraft.getGameProfile().getId());
    }

    private void beginWorldCreation(Minecraft minecraft, String role) { nextWorldRole = role; CreateWorldScreen.openFresh(minecraft, new TitleScreen()); }

    private void createFromVanillaFlow(Minecraft minecraft, String role) throws IOException {
        if (!(minecraft.screen instanceof CreateWorldScreen screen)) return;
        WorldCreationUiState state = screen.getUiState(); String proposed = "Colonyloom Lifecycle " + role + " " + token.substring(0, 8);
        if (!state.getName().equals(proposed)) {
            state.setName(proposed); state.setSeed(role.equals("B") ? "211221" : "211122"); state.setGameMode(WorldCreationUiState.SelectedGameMode.CREATIVE);
            state.setDifficulty(Difficulty.PEACEFUL); state.setAllowCommands(true); state.setGenerateStructures(false); state.setBonusChest(false);
            String folder = state.getTargetFolder(); require(!folder.isBlank() && !Path.of(folder).isAbsolute() && !folder.contains(".."), "Unsafe vanilla target folder");
            if (role.equals("A2")) throw new IllegalStateException("A2 must reopen the owner-marked original world, not create a new save");
            writeText(root.resolve(role.toLowerCase(java.util.Locale.ROOT) + "-target.txt"), folder);
            fact(role + "-vanilla-create-world-screen", true, "folder=" + folder + ";creative=true;commands=true");
        }
        String label = net.minecraft.network.chat.Component.translatable("selectWorld.create").getString();
        Button create = screen.children().stream().filter(Button.class::isInstance).map(Button.class::cast)
                .filter(button -> button.visible && button.active && button.getMessage().getString().equals(label)).findFirst().orElse(null);
        if (create == null) return;
        click(screen, create); advance(role.equals("A1") ? "WAIT_A1" : "WAIT_B");
    }

    private void stopThroughPauseScreen(Minecraft minecraft, String next) {
        if (minecraft.player == null || minecraft.getSingleplayerServer() == null) return;
        if (!(minecraft.screen instanceof PauseScreen)) { if (minecraft.screen != null) minecraft.screen.onClose(); minecraft.setScreen(new PauseScreen(true)); return; }
        String label = net.minecraft.network.chat.Component.translatable("menu.returnToMenu").getString();
        Button button = minecraft.screen.children().stream().filter(Button.class::isInstance).map(Button.class::cast)
                .filter(value -> value.visible && value.active && value.getMessage().getString().equals(label)).findFirst().orElse(null);
        if (button == null) return; click(minecraft.screen, button); advance(next);
    }

    private void command(Minecraft minecraft, String value) {
        require(minecraft.getConnection() != null && minecraft.player != null, "No active integrated owner connection");
        minecraft.getConnection().sendCommand(value); fact("actual-owner-player-command", true, value);
    }
    private static net.minecraft.core.BlockPos safeCitizenPosition(ServerLevel level, net.minecraft.core.BlockPos origin) {
        for (int radius = 2; radius <= 8; radius++) for (int dx = -radius; dx <= radius; dx++) for (int dz = -radius; dz <= radius; dz++) {
            if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue;
            var column = origin.offset(dx, 0, dz);
            if (!level.hasChunkAt(column)) continue;
            var feet = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, column);
            if (level.getBlockState(feet).isAir() && level.getBlockState(feet.above()).isAir() && level.getFluidState(feet.below()).isEmpty() && !level.getBlockState(feet.below()).getCollisionShape(level, feet.below()).isEmpty()) return feet;
        }
        throw new IllegalStateException("No safe vanilla surface within eight blocks");
    }

    private boolean inWorld(Minecraft minecraft) { return minecraft.player != null && minecraft.level != null && minecraft.getSingleplayerServer() != null; }
    private boolean visibleSummary(Minecraft minecraft, UUID id) {
        if (!(minecraft.screen instanceof io.github.kpuctajluk.colonyloom.minecraft.client.screen.ColonyScreen screen) || !screen.client().ready()) return false;
        return screen.client().subscriptions().keySet().stream().map(screen.client()::page).filter(java.util.Objects::nonNull)
                .anyMatch(page -> page.colonyId().equals(id) && page.type() == io.github.kpuctajluk.colonyloom.core.management.ManagementProtocol.ViewType.SUMMARY);
    }
    private boolean summaryHasColony(Minecraft minecraft, UUID id) {
        if (!(minecraft.screen instanceof io.github.kpuctajluk.colonyloom.minecraft.client.screen.ColonyScreen screen)) return false;
        return screen.client().subscriptions().keySet().stream().map(screen.client()::page).filter(java.util.Objects::nonNull)
                .anyMatch(page -> page.colonyId().equals(id) && page.type() == io.github.kpuctajluk.colonyloom.core.management.ManagementProtocol.ViewType.SUMMARY
                        && page.rows().stream().anyMatch(row -> row.id().equals(id) && row.name().equals(COLONY_NAME) && row.state().equals("READY")));
    }

    private void installWire(Minecraft minecraft) {
        if (minecraft.getConnection() == null) return;
        Connection candidate = minecraft.getConnection().getConnection(); if (candidate == installedConnection) return;
        installedConnection = candidate; synchronized (systemChat) { systemChat.clear(); }
        candidate.channel().eventLoop().execute(() -> {
            try { String anchor = candidate.channel().pipeline().context(candidate).name(); candidate.channel().pipeline().addBefore(anchor, "colonyloom_integrated_lifecycle_chat", new Wire()); }
            catch (Throwable failure) { clientWireFailure = failure; }
        });
    }

    private void renderFrame(RenderFrameEvent.Post event) {
        if (root == null || stopped || !screenshotRequested || screenshotComplete
                || !(Minecraft.getInstance().screen instanceof io.github.kpuctajluk.colonyloom.minecraft.client.screen.ColonyScreen screen)
                || management == null || screen.client() != management) return;
        Minecraft minecraft = Minecraft.getInstance();
        try {
            require(visibleSummary(minecraft, colonyId) && summaryHasColony(minecraft, colonyId), "Requested frame is not visible production colony summary");
            long handle = minecraft.getWindow().getWindow();
            if (org.lwjgl.glfw.GLFW.glfwGetWindowAttrib(handle, org.lwjgl.glfw.GLFW.GLFW_ICONIFIED) != 0) { org.lwjgl.glfw.GLFW.glfwRestoreWindow(handle); return; }
            require(minecraft.getWindow().getWidth() >= 640 && minecraft.getWindow().getHeight() >= 360, "Summary framebuffer is unusable");
            screenshotRequested = false; Files.createDirectories(root.resolve("screenshots"));
            Screenshot.grab(root.toFile(), "integrated-production-summary.png", minecraft.getMainRenderTarget(), message -> minecraft.execute(() -> {
                try {
                    Path imagePath = root.resolve("screenshots/integrated-production-summary.png");
                    require(Files.isRegularFile(imagePath) && Files.size(imagePath) > 0, "Minecraft framebuffer screenshot missing");
                    BufferedImage image = ImageIO.read(imagePath.toFile()); require(image != null && image.getWidth() >= 640 && image.getHeight() >= 360, "Screenshot framebuffer dimensions invalid");
                    java.util.HashSet<Integer> colors = new java.util.HashSet<>(); long sampled = 0, nonblack = 0;
                    int stepX = Math.max(1, image.getWidth() / 100), stepY = Math.max(1, image.getHeight() / 100);
                    for (int y = 0; y < image.getHeight(); y += stepY) for (int x = 0; x < image.getWidth(); x += stepX) {
                        int color = image.getRGB(x, y) & 0xffffff; sampled++; if (color != 0) nonblack++; if (colors.size() < 256) colors.add(color);
                    }
                    require(colors.size() >= 16 && nonblack > sampled / 10, "Production summary screenshot is black/unrendered");
                    require(Minecraft.getInstance().screen instanceof io.github.kpuctajluk.colonyloom.minecraft.client.screen.ColonyScreen actual
                                    && actual.client() == management && summaryHasColony(Minecraft.getInstance(), colonyId), "Production summary disappeared before screenshot");
                    fact("actual-production-summary-framebuffer", true, "file=screenshots/integrated-production-summary.png;size=" + image.getWidth() + "x" + image.getHeight() + ";colors=" + colors.size() + ";nonblack=" + nonblack + "/" + sampled);
                    screenshotComplete = true;
                } catch (Throwable failure) { fail(failure); }
            }));
        } catch (Throwable failure) { fail(failure); }
    }

    private String latestChatMessageContaining(String... fragments) {
        synchronized (systemChat) {
            for (int index = systemChat.size() - 1; index >= 0; index--) {
                String message = systemChat.get(index); boolean matches = true;
                for (String fragment : fragments) matches &= message.contains(fragment);
                if (matches) return message;
            }
        }
        return null;
    }
    private void advance(String next) { phase = next; phaseStarted = System.nanoTime(); }
    private boolean serverEvent(String name, String expected) throws IOException { Path file = root.resolve(name); return Files.isRegularFile(file) && jsonFile(name).get("event").getAsString().equals(expected); }
    private JsonObject serverState(String name) throws IOException { return jsonFile(name); }
    private JsonObject jsonFile(String name) throws IOException {
        synchronized (RECORD_IO) {
            Path file = root.resolve(name); require(Files.isRegularFile(file) && Files.size(file) <= 1024 * 1024, "Missing/oversized lifecycle record " + name);
            return JSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), JsonObject.class);
        }
    }
    private static boolean contains(List<net.minecraft.world.item.ItemStack> items, net.minecraft.world.item.Item item, int count) { return items.stream().anyMatch(stack -> stack.is(item) && stack.getCount() == count); }
    private static CompoundTag inventoryData(MinecraftServer server, CitizenEntity citizen) {
        CompoundTag tag = new CompoundTag(); net.minecraft.world.ContainerHelper.saveAllItems(tag, citizen.inventory().getItems(), server.registryAccess()); return tag;
    }
    private static JsonObject identity(CitizenRecord record, CompoundTag inventory) {
        JsonObject value = new JsonObject(); value.addProperty("colony", record.colonyId().toString()); value.addProperty("citizen", record.citizenId().toString());
        value.addProperty("entity", record.entityId().toString()); value.addProperty("epoch", record.bindingEpoch()); value.addProperty("activeTimeTicks", record.activeTimeTicks()); value.addProperty("inventory", inventory.toString()); return value;
    }
    private static JsonObject identityFromRecord(CitizenRecord record, String inventory, long activeTicks) {
        JsonObject value = identity(record, new CompoundTag()); value.addProperty("inventory", inventory); value.addProperty("activeTimeTicks", activeTicks); return value;
    }
    private static CompoundTag read(Path path) throws IOException {
        require(Files.isRegularFile(path) && Files.size(path) <= NBT_LIMIT, "Missing/oversized durable NBT " + path); return NbtIo.readCompressed(path, NbtAccounter.create(NBT_LIMIT));
    }
    private static CompoundTag findByUuid(ListTag list, String key, UUID id) {
        for (int i = 0; i < list.size(); i++) { CompoundTag entry = list.getCompound(i); if (entry.hasUUID(key) && entry.getUUID(key).equals(id)) return entry; }
        throw new IllegalStateException("Missing durable canonical " + key + "=" + id);
    }
    private static void verifyDtoIdentity(CompoundTag dto, JsonObject expected) {
        UUID colony = UUID.fromString(expected.get("colony").getAsString()), citizen = UUID.fromString(expected.get("citizen").getAsString()), entity = UUID.fromString(expected.get("entity").getAsString());
        boolean colonyFound = false; for (int i = 0; i < dto.getList("colonies", CompoundTag.TAG_COMPOUND).size(); i++) if (dto.getList("colonies", CompoundTag.TAG_COMPOUND).getCompound(i).hasUUID("colonyId") && dto.getList("colonies", CompoundTag.TAG_COMPOUND).getCompound(i).getUUID("colonyId").equals(colony)) colonyFound = true;
        require(colonyFound, "Canonical colony absent from durable DTO");
        CompoundTag saved = findByUuid(dto.getList("citizens", CompoundTag.TAG_COMPOUND), "citizenId", citizen);
        require(saved.getUUID("colonyId").equals(colony) && saved.getUUID("entityId").equals(entity) && saved.getLong("bindingEpoch") == expected.get("epoch").getAsLong(), "Durable canonical resident identity/property differs");
        // Citizen inventory is native entity state, not part of the core citizen DTO; the entity-side check runs on A2 load.
    }
    private static JsonObject object(Object... values) {
        JsonObject result = new JsonObject(); for (int i = 0; i < values.length; i += 2) result.add(String.valueOf(values[i]), JSON.toJsonTree(values[i + 1])); return result;
    }
    private void writeServerState(ServerRun run, String event, Map<String, ?> values) {
        try { JsonObject data = new JsonObject(); data.addProperty("event", event); data.addProperty("role", run.role); data.addProperty("session", run.runtime.sessionId().toString()); values.forEach((key, value) -> data.add(key, JSON.toJsonTree(value))); writeJson(root.resolve(run.role.toLowerCase(java.util.Locale.ROOT) + "-server-state.json"), data); }
        catch (Throwable failure) { serverFailure = failure; }
    }
    private void fact(String name, boolean passed, String detail) {
        try { synchronized (this) { Files.writeString(root.resolve("observations.jsonl"), object("check", name, "passed", passed, "detail", detail).toString() + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND); } }
        catch (IOException failure) { throw new IllegalStateException("Cannot write lifecycle evidence", failure); }
    }
    private static void writeJson(Path path, JsonObject value) throws IOException {
        synchronized (RECORD_IO) {
            Path temporary = Files.createTempFile(path.getParent(), "colonyloom-integrated-", ".tmp");
            try {
                Files.writeString(temporary, value.toString() + "\n", StandardCharsets.UTF_8, StandardOpenOption.WRITE);
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(temporary);
            }
        }
    }
    private static void writeText(Path path, String value) throws IOException { Files.writeString(path, value + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE); }
    private static UUID uuidField(String text, String field) { java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(?:^|\\s)" + field + "=([0-9a-fA-F-]{36})(?:\\s|$)").matcher(text); return matcher.find() ? UUID.fromString(matcher.group(1)) : null; }
    private static long longField(String text, String field) { java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(?:^|\\s)" + field + "=([0-9]+)(?:\\s|$)").matcher(text); if (!matcher.find()) throw new IllegalStateException("Missing " + field + " in accepted command output: " + text); return Long.parseLong(matcher.group(1)); }
    private static void click(net.minecraft.client.gui.screens.Screen screen, Button button) {
        double x = button.getX() + button.getWidth() / 2.0, y = button.getY() + button.getHeight() / 2.0;
        require(screen.mouseClicked(x, y, 0), "Vanilla UI ignored actual button click " + button.getMessage().getString()); screen.mouseReleased(x, y, 0);
    }
    private void fail(Throwable failure) {
        if (stopped) return; stopped = true;
        try { fact("scenario-failure", false, "phase=" + phase + ";clientTicks=" + clientTicks + ";error=" + failure); writeJson(root.resolve("failure.json"), object("passed", false, "phase", phase, "clientTicks", clientTicks, "error", failure.toString())); }
        catch (Throwable reportFailure) { failure.addSuppressed(reportFailure); }
        Minecraft.getInstance().stop();
    }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }

    private final class Wire extends ChannelDuplexHandler {
        @Override public void channelRead(ChannelHandlerContext context, Object packet) throws Exception {
            try {
                if (packet instanceof ClientboundSystemChatPacket chat) remember(chat.content().getString());
                else if (packet instanceof ClientboundBundlePacket bundle) for (Packet<?> nested : bundle.subPackets()) if (nested instanceof ClientboundSystemChatPacket chat) remember(chat.content().getString());
            } catch (Throwable failure) { clientWireFailure = failure; }
            super.channelRead(context, packet);
        }
        private void remember(String message) { synchronized (systemChat) { systemChat.add(message); } }
    }
}
