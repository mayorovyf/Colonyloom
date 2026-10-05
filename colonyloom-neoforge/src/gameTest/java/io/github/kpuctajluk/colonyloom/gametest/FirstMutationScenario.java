package io.github.kpuctajluk.colonyloom.gametest;

import com.mojang.authlib.GameProfile;
import io.github.kpuctajluk.colonyloom.minecraft.runtime.MinecraftServerRuntime;
import io.github.kpuctajluk.colonyloom.neoforge.ConstructionExecutorEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.commands.CommandSource;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** First real public mutation in a virgin disposable world, interrupted before any DTO write. */
final class FirstMutationScenario {
    private final Map<MinecraftServer, MinecraftServerRuntime> runtimes = new IdentityHashMap<>();
    private final Map<MinecraftServer, Integer> ticks = new IdentityHashMap<>();
    private static final UUID OWNER = UUID.fromString("7b2ca82a-6aef-4e2c-bcdc-97fac10a204e");
    FirstMutationScenario() {
        NeoForge.EVENT_BUS.addListener(this::configure);
        NeoForge.EVENT_BUS.addListener(this::tick);
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.server.ServerStoppedEvent event) -> {
            runtimes.remove(event.getServer());ticks.remove(event.getServer());
        });
    }
    private static String phase() { return System.getProperty("colonyloom.test.firstMutationPhase", ""); }
    private void configure(ConstructionExecutorEvent event) {
        if (!phase().isEmpty()) runtimes.put(event.server(), event.runtime());
    }
    private void tick(ServerTickEvent.Post event) {
        if (phase().isEmpty()) return;
        MinecraftServer server = event.getServer();
        var runtime = runtimes.get(server);
        if (runtime == null || ticks.getOrDefault(server, 0) == -1) return;
        Path world = server.getWorldPath(LevelResource.ROOT);
        try {
            require(server.isDedicatedServer() && Files.isRegularFile(world.resolve("colonyloom-test-world"))
                    && (phase().equals("exercise") || phase().equals("verify")), "First mutation fixture lacks marked disposable dedicated world");
            if (phase().equals("exercise")) require(Boolean.getBoolean("colonyloom.testFaults"), "First mutation halt requires explicit fault opt-in");
            int elapsed = ticks.merge(server, 1, Integer::sum);
            require(elapsed <= 400, "First mutation fixture timeout");
            Path dto = world.resolve("data/colonyloom.dat"), marker = world.resolve("data/colonyloom-session.nbt");
            if (phase().equals("exercise") && !server.overworld().hasChunkAt(new net.minecraft.core.BlockPos(0, 64, 0))) return;
            var actor = new ServerPlayer(server, server.overworld(), new GameProfile(OWNER, "FirstMutationOwner"), ClientInformation.createDefault());
            var capture = new Capture();
            int result = server.getCommands().getDispatcher().execute("colonyloom colony create FirstMutation 0 64 0 15 64 15",
                    actor.createCommandSourceStack().withPermission(2).withSource(capture));
            if (phase().equals("exercise")) {
                require(runtime.persistence().isAvailable() && result == 1 && runtime.core().registry().colonies().size() == 1,
                        "First public mutation was not accepted: " + capture.text);
                require(runtime.core().registry().colonies().iterator().next().ownerId().equals(OWNER), "First mutation changed actor ownership");
                require(!Files.exists(dto) && Files.isRegularFile(marker), "First mutation did not precede DTO write");
                var persisted = NbtIo.readCompressed(marker, NbtAccounter.create(8192));
                require(!persisted.getBoolean("clean") && persisted.getUUID("checkpointId").equals(runtime.persistence().checkpointId()),
                        "Dirty marker was not durable before public mutation");
                System.out.println("COLONYLOOM_FIRST_MUTATION_CRASH canonicalColonies=1 dirtyMarker=true dtoAbsent=true owner=" + OWNER + " expectedExit=97");
                System.out.flush();
                Runtime.getRuntime().halt(97);
            } else {
                require(!runtime.persistence().isAvailable() && result == 0 && capture.text.contains("persistence blocked"),
                        "Restart silently initialized an empty writable runtime: " + capture.text);
                require(!Files.exists(dto) && Files.isRegularFile(marker), "Restart replaced missing DTO");
                System.out.println("COLONYLOOM_FIRST_MUTATION_REFUSED markerPreserved=true dtoAbsent=true mutationRefused=true");
                ticks.put(server, -1);server.halt(false);
            }
        } catch (Exception failure) {
            System.err.println("COLONYLOOM_FIRST_MUTATION_FAILED " + failure);
            ticks.put(server, -1);server.halt(false);
        }
    }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
    private static final class Capture implements CommandSource {
        String text = "";
        public void sendSystemMessage(Component message) { text += message.getString() + "\n"; }
        public boolean acceptsSuccess() { return true; }
        public boolean acceptsFailure() { return true; }
        public boolean shouldInformAdmins() { return false; }
    }
}
