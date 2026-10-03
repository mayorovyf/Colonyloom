package io.github.kpuctajluk.colonyloom.gametest;

import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import com.mojang.logging.LogUtils;
import io.github.kpuctajluk.colonyloom.minecraft.entity.CitizenEntity;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;
import static net.minecraft.commands.Commands.literal;

/** Dev-only observations through the public command surface and real Minecraft entities. */
@Mod("colonyloom_tests")
public final class IdentityScenarioMod {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final UUID VIEWER = UUID.fromString("28b1e578-2c29-42e7-9df2-648bd1ec4441");
    private static final UUID MANAGER = UUID.fromString("28b1e578-2c29-42e7-9df2-648bd1ec4442");
    private static final String MANIFEST = "colonyloom-identity-scenario.nbt";
    private static final long NBT_LIMIT = 1024 * 1024;
    private final Map<MinecraftServer, AutomaticRun> automaticRuns = new IdentityHashMap<>();

    private static final class AutomaticRun {
        private final String action;
        private int connectedTicks;
        private boolean done;
        private AutomaticRun(String action) { this.action = action; }
    }

    public IdentityScenarioMod() {
        NeoForge.EVENT_BUS.addListener(this::register);
        NeoForge.EVENT_BUS.addListener(this::automatic);
        NeoForge.EVENT_BUS.addListener(this::stopped);
        new ConstructionScenario();
        new PlatformScenario();
    }

    private void register(RegisterCommandsEvent event) {
        var identity = literal("identity");
        for (String action : List.of("setup", "verify", "conflict", "recovery-check", "isolation", "timer-start", "timer-save", "timer-resume", "timer-complete", "config-observe", "move-start", "move-complete", "build-start", "build-partial")) {
            identity.then(literal(action).executes(context -> run(context.getSource(), action)));
        }
        event.getDispatcher().register(literal("colonyloomtest")
                .requires(source -> source.hasPermission(2)).then(identity));
    }

    private void automatic(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        String requested = System.getProperty("colonyloom.test.identityAction", "");
        if (requested.isBlank()) return;
        AutomaticRun state = automaticRuns.computeIfAbsent(server, ignored -> new AutomaticRun(requested));
        if (state.done && !state.action.equals("config-observe")) return;
        ServerPlayer player = server.getPlayerList().getPlayers().stream()
                .filter(candidate -> !(candidate instanceof net.neoforged.neoforge.common.util.FakePlayer))
                .findFirst().orElse(null);
        if (player == null) return;
        if (++state.connectedTicks < 100) return;
        if (state.action.equals("config-observe") && state.connectedTicks % 100 != 0) return;
        CommandSourceStack source = player.createCommandSourceStack().withPermission(2);
        try {
            if (state.connectedTicks < 1200 && !automaticPositionsReady(source, state.action)) return;
        } catch (IOException | RuntimeException error) {
            // A missing/invalid prerequisite is reported by the normal scenario, not retried forever.
            LOGGER.warn("Identity automation prerequisite failed; invoking scenario once", error);
        }
        state.done = true;
        int result = run(source, state.action);
        LOGGER.info("COLONYLOOM_IDENTITY_AUTO action={} result={} world={} connectedTicks={}",
                state.action, result, world(source), state.connectedTicks);
    }

    private void stopped(ServerStoppedEvent event) { automaticRuns.remove(event.getServer()); }

    private static boolean automaticPositionsReady(CommandSourceStack source, String action) throws IOException {
        ServerLevel level = source.getLevel();
        if (action.equals("setup")) {
            BlockPos origin = source.getPlayer().blockPosition();
            for (int offset : new int[]{-4, 4, 20}) {
                BlockPos position = origin.offset(offset, 0, 4);
                if (!level.hasChunkAt(position) || !level.isPositionEntityTicking(position)) return false;
            }
        } else if (action.equals("verify") || action.startsWith("timer-") || action.startsWith("move-") || action.startsWith("build-")) {
            CompoundTag manifest = readManifest(source);
            for (Tag value : manifest.getList("citizens", Tag.TAG_COMPOUND)) {
                int[] position = ((CompoundTag) value).getIntArray("position");
                if (position.length != 3) throw new IOException("Invalid fixture position");
                BlockPos pos = new BlockPos(position[0], position[1], position[2]);
                if (!level.hasChunkAt(pos) || !level.isPositionEntityTicking(pos)) return false;
            }
        }
        return true;
    }

    private static int run(CommandSourceStack source, String action) {
        try {
            ServerPlayer player = source.getPlayerOrException();
            require(source, source.getServer().getPlayerList().getPlayer(player.getUUID()) == player,
                    "connected_real_player", player.getUUID().toString());
            require(source, !(player instanceof net.neoforged.neoforge.common.util.FakePlayer),
                    "not_fake_player", player.getClass().getName());
            require(source, Files.isRegularFile(world(source).resolve("colonyloom-test-world")),
                    "disposable_world_marker", world(source).toString());
            if (action.equals("setup")) setup(source, player);
            else if (action.equals("isolation")) isolation(source);
            else {
                CompoundTag manifest = readManifest(source);
                require(source, manifest.getString("state").equals("READY"), "fixture_ready", manifest.getString("state"));
                require(source, manifest.getUUID("owner").equals(player.getUUID()), "fixture_owner", player.getUUID().toString());
                require(source, manifest.getString("dimension").equals(source.getLevel().dimension().location().toString()),
                        "fixture_dimension", manifest.getString("dimension"));
                switch (action) {
                    case "verify" -> verify(source, manifest, false);
                    case "conflict" -> conflict(source, player, manifest);
                    case "recovery-check" -> recovery(source, manifest);
                    case "timer-start", "timer-save", "timer-resume", "timer-complete" -> timer(source, manifest, action);
                    case "move-start", "move-complete" -> move(source, manifest, action);
                    case "build-start", "build-partial" -> build(source,manifest,action);
                    case "config-observe" -> {
                        for (Tag tag : manifest.getList("colonies", Tag.TAG_COMPOUND)) {
                            UUID colony = ((CompoundTag) tag).getUUID("id");
                            String status = success(source, "colonyloom status " + colony);
                            require(source, status.contains("admission=CITIZENS used=3"), "config_preserves_accepted_citizens", status);
                            if (status.contains("admission=CITIZENS used=3 limit=2 overLimit=1")) {
                                refused(timerSource(source, manifest, colony), "colonyloom citizen create " + colony + " " + coordinates(BlockPos.containing(timerSource(source, manifest, colony).getPosition())));
                            }
                        }
                    }
                    default -> throw new IllegalArgumentException("Unknown identity scenario");
                }
            }
            fact(source, action, true, "completed");
            source.sendSuccess(() -> Component.literal("Colonyloom identity scenario " + action + " PASS; facts: colonyloom-identity-observations.jsonl"), false);
            return 1;
        } catch (Exception error) {
            fact(source, action, false, error.toString());
            LOGGER.error("Colonyloom identity scenario {} failed", action, error);
            source.sendFailure(Component.literal("Identity scenario " + action + " FAIL: " + error.getMessage()));
            return 0;
        }
    }

    private static void build(CommandSourceStack source,CompoundTag manifest,String action) throws Exception {
        var expected=manifest.getList("citizens",Tag.TAG_COMPOUND).getCompound(0);
        var physical=entity(source,expected.getUUID("entity"));
        if(action.equals("build-start")) {
            require(source,!manifest.contains("buildWork"),"build_fixture_once",action);
            var colony=manifest.getList("colonies",Tag.TAG_COMPOUND).getCompound(0); int[] from=colony.getIntArray("from");
            var origin=new BlockPos(from[0]+4,physical.blockPosition().getY(),from[2]+7);
            for(int x=-2;x<=17;x++) for(int z=-1;z<=2;z++) { var pos=origin.offset(x,0,z); validateSite(source,pos); prepareFloor(source.getLevel(),pos); }
            for(int x=0;x<16;x++) require(source,source.getLevel().getBlockState(origin.offset(x,0,0)).isAir(),"build_fixture_target_clear",coordinates(origin.offset(x,0,0)));
            var build=new CompoundTag(); build.putUUID("colony",expected.getUUID("colony")); build.putIntArray("origin",new int[]{origin.getX(),origin.getY(),origin.getZ()});
            var work=uuid(success(source,"colonyloom build "+expected.getUUID("colony")+" colonyloom:stair_strip "+coordinates(origin)+" 0"),"work");
            build.putUUID("work",work); manifest.put("buildWork",build); writeManifest(source,manifest);
        } else {
            var build=manifest.getCompound("buildWork"); int[] p=build.getIntArray("origin"); require(source,p.length==3,"build_fixture_exists",action);
            var origin=new BlockPos(p[0],p[1],p[2]); var status=success(source,"colonyloom status "+build.getUUID("colony"));
            require(source,status.contains("work="+build.getUUID("work")+" state=WAITING reason=MATERIALS"),"production_build_waits_materials",status);
            for(int x=0;x<16;x++) require(source,x<4?source.getLevel().getBlockState(origin.offset(x,0,0)).is(Blocks.OAK_STAIRS):source.getLevel().getBlockState(origin.offset(x,0,0)).isAir(),"production_build_partial_blocks",Integer.toString(x));
            for(int slot=0;slot<CitizenEntity.INVENTORY_SIZE;slot++) require(source,physical.inventory().getItem(slot).isEmpty(),"production_build_exact_real_expense",Integer.toString(slot));
            success(source,"colonyloom work cancel "+build.getUUID("work"));
            fact(source,"production_build_cancel_preserves_physical_blocks",true,"four stairs, zero materials, public sixteen-block goal cancelled");
        }
    }

    private static void move(CommandSourceStack source, CompoundTag manifest, String action) throws Exception {
        if (action.equals("move-start")) {
            require(source, !manifest.contains("moveWork"), "move_fixture_once", action);
            CompoundTag citizen = manifest.getList("citizens", Tag.TAG_COMPOUND).getCompound(2);
            CitizenEntity physical = entity(source, citizen.getUUID("entity"));
            BlockPos start = physical.blockPosition(), target = start.offset(2, 0, 0);
            for (int x=0;x<=2;x++) { validateSite(source,start.offset(x,0,0)); prepareFloor(source.getLevel(),start.offset(x,0,0)); }
            CompoundTag move = new CompoundTag();
            move.putUUID("citizen", citizen.getUUID("citizen")); move.putUUID("entity", citizen.getUUID("entity")); move.putUUID("colony", citizen.getUUID("colony"));
            move.putIntArray("target",new int[]{target.getX(),target.getY(),target.getZ()});
            UUID work = uuid(success(source,"colonyloom work move "+citizen.getUUID("colony")+" "+coordinates(target)),"work");
            move.putUUID("work",work); manifest.put("moveWork",move); writeManifest(source,manifest);
            fact(source,"move_command_accepted",true,"work="+work+" start="+coordinates(start)+" target="+coordinates(target));
        } else {
            CompoundTag move=manifest.getCompound("moveWork");require(source,move.hasUUID("work"),"move_fixture_exists",action);
            String status=success(source,"colonyloom status "+move.getUUID("colony"));
            require(source,status.contains("work="+move.getUUID("work")+" state=COMPLETED reason=NONE assignee=null"),"move_command_real_completion",status);
            CitizenEntity physical=entity(source,move.getUUID("entity"));int[] target=move.getIntArray("target");
            require(source,physical.distanceToSqr(target[0]+0.5,target[1],target[2]+0.5)<=1.1,"move_actual_entity_arrival",physical.position().toString());
            CompoundTag expected=manifest.getList("citizens",Tag.TAG_COMPOUND).getCompound(2);checkEntity(source,physical,expected);
        }
    }
    private static void timer(CommandSourceStack source, CompoundTag manifest, String action) throws Exception {
        ListTag colonies = manifest.getList("colonies", Tag.TAG_COMPOUND);
        if (action.equals("timer-start")) {
            require(source, !manifest.contains("timerWorks"), "timer_fixture_once", action);
            ListTag timers = new ListTag();
            for (Tag tag : colonies) {
                UUID colony = ((CompoundTag) tag).getUUID("id");
                CompoundTag entry = new CompoundTag(); entry.putUUID("colonyId", colony);
                UUID work = uuid(success(timerSource(source, manifest, colony), "colonyloom work wait " + colony + " 12000"), "work");
                success(source, "colonyloom work priority " + work + " 4");
                entry.putUUID("workId", work); timers.add(entry);
            }
            manifest.put("timerWorks", timers); writeManifest(source, manifest);
            return;
        }
        ListTag timers = manifest.getList("timerWorks", Tag.TAG_COMPOUND);
        require(source, timers.size() == 2, "timer_two_colonies", action);
        for (Tag tag : timers) {
            CompoundTag timer = (CompoundTag) tag;
            UUID colony = timer.getUUID("colonyId"), workId = timer.getUUID("workId");
            String status = success(source, "colonyloom status " + colony);
            var match = Pattern.compile("work=" + workId + " state=(\\w+) reason=\\w+ assignee=([^\\s]+) remainingActiveTicks=(\\d+) revision=(\\d+)").matcher(status);
            require(source, match.find(), "timer_status_present", status);
            String state = match.group(1); long remaining = Long.parseLong(match.group(3));
            if (action.equals("timer-save")) {
                require(source, remaining > 0 && remaining < 12000 && state.equals("RUNNING"), "timer_partial_real_progress", match.group());
                timer.putLong("remainingAtSave", remaining);
            } else if (action.equals("timer-resume")) {
                require(source, timer.contains("remainingAtSave") && remaining > 0 && remaining <= timer.getLong("remainingAtSave") && remaining > timer.getLong("remainingAtSave") - 2000,
                        "timer_residual_not_reset_or_offline_caught_up", match.group());
                require(source, state.equals("RUNNING"), "timer_resumed_running", match.group());
                success(source, "colonyloom work cancel " + workId);
                UUID shortWork = uuid(success(timerSource(source, manifest, colony), "colonyloom work wait " + colony + " 40"), "work");
                timer.putUUID("shortWorkId", shortWork);
            } else {
                require(source, state.equals("CANCELLED") && match.group(2).equals("null"), "timer_cancelled_assignment_released", match.group());
                String shortStatus = Pattern.compile("work=" + timer.getUUID("shortWorkId") + " state=COMPLETED reason=\\w+ assignee=null remainingActiveTicks=0 revision=\\d+")
                        .matcher(status).results().map(java.util.regex.MatchResult::group).findFirst().orElse("");
                require(source, !shortStatus.isEmpty(), "timer_real_completion", status);
            }
        }
        if (!action.equals("timer-complete")) writeManifest(source, manifest);
    }
    private static CommandSourceStack timerSource(CommandSourceStack source, CompoundTag manifest, UUID colony) {
        for (Tag tag : manifest.getList("citizens", Tag.TAG_COMPOUND)) {
            CompoundTag citizen = (CompoundTag) tag;
            if (!colony.equals(citizen.getUUID("colony"))) continue;
            int[] pos = citizen.getIntArray("position");
            return source.withPosition(new net.minecraft.world.phys.Vec3(pos[0], pos[1], pos[2]));
        }
        throw new IllegalStateException("Timer colony lacks physical fixture citizen");
    }

    private static void isolation(CommandSourceStack source) throws Exception {
        String previousPath = System.getProperty("colonyloom.test.previousManifest", "");
        require(source, !previousPath.isBlank() && Path.of(previousPath).isAbsolute(),
                "previous_manifest_absolute", previousPath);
        Path path = Path.of(previousPath);
        require(source, Files.isRegularFile(path) && Files.size(path) <= NBT_LIMIT,
                "previous_manifest_readable", previousPath);
        require(source, !path.toAbsolutePath().normalize().equals(world(source).resolve(MANIFEST).toAbsolutePath().normalize()),
                "isolated_world_distinct", world(source).toString());
        CompoundTag previous = NbtIo.readCompressed(path, NbtAccounter.create(NBT_LIMIT));
        require(source, previous.getInt("schemaVersion") == 1 && previous.getString("state").equals("READY"),
                "previous_fixture_ready", previousPath);
        ListTag colonies = previous.getList("colonies", Tag.TAG_COMPOUND);
        ListTag citizens = previous.getList("citizens", Tag.TAG_COMPOUND);
        require(source, colonies.size() == 2 && citizens.size() == 3,
                "previous_fixture_exact_counts", previousPath);
        for (Tag value : colonies) {
            UUID id = ((CompoundTag) value).getUUID("id");
            refused(source, "colonyloom recovery inspect " + id);
        }
        for (Tag value : citizens) {
            UUID id = ((CompoundTag) value).getUUID("entity");
            require(source, source.getLevel().getEntity(id) == null, "prior_world_entity_absent", id.toString());
        }
        if (previous.contains("duplicate", Tag.TAG_COMPOUND)) {
            UUID id = previous.getCompound("duplicate").getUUID("entity");
            require(source, source.getLevel().getEntity(id) == null, "prior_world_duplicate_absent", id.toString());
        }
        long citizensObserved = 0;
        int entitiesScanned = 0;
        for (Entity entity : source.getLevel().getAllEntities()) {
            if (++entitiesScanned > 4096) throw new IllegalStateException("Isolation fixture scan exceeds bounded 4096 entities");
            if (entity instanceof CitizenEntity) citizensObserved++;
        }
        require(source, citizensObserved == 0, "integrated_world_no_citizen_leak",
                "citizens=" + citizensObserved + " entitiesScanned=" + entitiesScanned);
    }

    private static void setup(CommandSourceStack source, ServerPlayer player) throws Exception {
        require(source, !Files.exists(world(source).resolve(MANIFEST)), "setup_once", MANIFEST);
        ServerLevel level = source.getLevel();
        BlockPos origin = player.blockPosition();
        BlockPos[] positions = {origin.offset(-4, 0, 4), origin.offset(4, 0, 4), origin.offset(20, 0, 4)};
        for (BlockPos position : positions) validateSite(source, position);
        var cache = source.getServer().getProfileCache();
        require(source, cache != null, "profile_cache_available", "standard GameProfileArgument");
        require(source, !player.getUUID().equals(VIEWER) && !player.getUUID().equals(MANAGER), "fixture_member_ids_distinct", player.getUUID().toString());
        cache.add(new GameProfile(VIEWER, "LoomViewer"));
        cache.add(new GameProfile(MANAGER, "LoomManager"));
        CompoundTag manifest = new CompoundTag();
        manifest.putInt("schemaVersion", 1);
        manifest.putString("state", "SETTING_UP");
        manifest.putUUID("owner", player.getUUID());
        manifest.putString("dimension", level.dimension().location().toString());
        ListTag colonies = new ListTag();
        ListTag citizens = new ListTag();
        manifest.put("colonies", colonies);
        manifest.put("citizens", citizens);
        writeManifest(source, manifest);
        for (int index = 0; index < 2; index++) {
            BlockPos from = origin.offset(-16 + index * 32, 0, -16);
            BlockPos to = from.offset(31, 0, 31);
            String output = success(source, "colonyloom colony create IdentityFixture" + index + " " + coordinates(from) + " " + coordinates(to));
            UUID colonyId = uuid(output, "colony");
            CompoundTag colony = new CompoundTag();
            colony.putUUID("id", colonyId);
            colony.putUUID("owner", player.getUUID());
            colony.putUUID("viewer", VIEWER);
            colony.putUUID("manager", MANAGER);
            colony.putIntArray("from", new int[]{from.getX(), from.getY(), from.getZ()});
            colony.putIntArray("to", new int[]{to.getX(), to.getY(), to.getZ()});
            colonies.add(colony);
            writeManifest(source, manifest);
            success(source, "colonyloom member set " + colonyId + " LoomViewer viewer");
            success(source, "colonyloom member set " + colonyId + " LoomManager manager");
        }
        String[] professions = {"colonyloom:builder", "colonyloom:courier", "colonyloom:carpenter"};
        ItemStack[] property = {new ItemStack(Items.OAK_STAIRS, 4), new ItemStack(Items.BREAD, 3), new ItemStack(Items.OAK_LOG, 6)};
        for (int index = 0; index < 3; index++) {
            BlockPos position = positions[index];
            prepareFloor(level, position);
            UUID colonyId = colonies.getCompound(index == 2 ? 1 : 0).getUUID("id");
            String output = success(source, "colonyloom citizen create " + colonyId + " " + coordinates(position));
            UUID citizenId = uuid(output, "citizen");
            UUID entityId = uuid(output, "entity");
            CitizenEntity entity = entity(source, entityId);
            require(source, entity.citizenId().equals(citizenId), "created_citizen_identity", output);
            for (int slot = 0; slot < CitizenEntity.INVENTORY_SIZE; slot++) {
                require(source, entity.inventory().getItem(slot).isEmpty(), "new_inventory_empty", entityId + " slot=" + slot);
            }
            entity.inventory().setItem(0, property[index]);
            entity.inventory().setChanged();
            success(source, "colonyloom citizen assign " + citizenId + " " + professions[index]);
            CompoundTag expected = expectedEntity(entity);
            expected.putUUID("colony", colonyId);
            expected.putString("profession", professions[index]);
            expected.putIntArray("position", new int[]{position.getX(), position.getY(), position.getZ()});
            citizens.add(expected);
            writeManifest(source, manifest);
        }
        manifest.putString("state", "READY");
        writeManifest(source, manifest);
        verify(source, manifest, false);
    }

    private static void verify(CommandSourceStack source, CompoundTag manifest, boolean blocked) throws Exception {
        ListTag colonies = manifest.getList("colonies", Tag.TAG_COMPOUND);
        ListTag citizens = manifest.getList("citizens", Tag.TAG_COMPOUND);
        require(source, colonies.size() == 2 && citizens.size() == 3, "fixture_exact_counts", "colonies=" + colonies.size() + " citizens=" + citizens.size());
        for (Tag value : colonies) {
            CompoundTag colony = (CompoundTag) value;
            String status = success(source, "colonyloom status " + colony.getUUID("id"));
            require(source, status.contains("colonyId=" + colony.getUUID("id"))
                    && status.contains("ownerId=" + colony.getUUID("owner"))
                    && status.contains(colony.getUUID("viewer") + "=VIEWER")
                    && status.contains(colony.getUUID("manager") + "=MANAGER"), "persisted_colony_authority", status);
            require(source, status.contains("recoveryBlocked=" + blocked), "recovery_block_state", status);
            String inspect = success(source, "colonyloom recovery inspect " + colony.getUUID("id"));
            require(source, inspect.contains("colonyId=" + colony.getUUID("id")), "scoped_inspection", inspect);
            for (Tag citizenValue : citizens) {
                CompoundTag expected = (CompoundTag) citizenValue;
                if (!expected.getUUID("colony").equals(colony.getUUID("id"))) continue;
                String prefix = "citizen=" + expected.getUUID("citizen") + " entity=" + expected.getUUID("entity")
                        + " epoch=" + expected.getLong("epoch");
                String row = status.lines().filter(line -> line.startsWith(prefix + " ")).findFirst()
                        .orElseThrow(() -> new IllegalStateException("Persisted identity missing: " + prefix));
                require(source, row.contains("profession=" + expected.getString("profession")) && row.contains("lifecycle=ALIVE"),
                        "persisted_profession", row);
                CitizenEntity entity = entity(source, expected.getUUID("entity"));
                checkEntity(source, entity, expected);
                require(source, entity.isQuarantined() == blocked, "physical_quarantine_state", entity.getUUID() + " quarantine=" + entity.isQuarantined());
                long count = 0;
                for (Entity observed : source.getLevel().getAllEntities()) {
                    if (observed instanceof CitizenEntity physical && expected.getUUID("citizen").equals(physical.citizenId())) count++;
                }
                long expectedCount = manifest.contains("duplicate", Tag.TAG_COMPOUND)
                        && manifest.getCompound("duplicate").getUUID("citizen").equals(expected.getUUID("citizen")) ? 2 : 1;
                require(source, count == expectedCount, "no_replacement_identity", expected.getUUID("citizen") + " physicalCount=" + count);
            }
        }
        if (manifest.contains("duplicate", Tag.TAG_COMPOUND)) {
            CompoundTag duplicate = manifest.getCompound("duplicate");
            CitizenEntity physical = entity(source, duplicate.getUUID("entity"));
            checkEntity(source, physical, duplicate);
            require(source, physical.isQuarantined(), "retired_duplicate_quarantine", physical.getUUID().toString());
            String inspect = success(source, "colonyloom recovery inspect " + duplicate.getUUID("colony"));
            require(source, observation(inspect, duplicate.getUUID("entity")).contains("retired=true"), "retired_duplicate_observation", inspect);
        }
    }

    private static void conflict(CommandSourceStack source, ServerPlayer player, CompoundTag manifest) throws Exception {
        require(source, !manifest.contains("duplicate") && !manifest.getBoolean("conflictStarted"), "conflict_once", "no prior duplicate fixture");
        verify(source, manifest, false);
        CompoundTag expected = manifest.getList("citizens", Tag.TAG_COMPOUND).getCompound(0);
        CitizenEntity original = entity(source, expected.getUUID("entity"));
        require(source, player.isAlive() && !player.isSpectator() && player.distanceToSqr(original) <= 36,
                "authorized_player_near_conflict", "Stand within six blocks of builder");
        BlockPos duplicatePosition = original.blockPosition().offset(2, 0, 0);
        validateSite(source, duplicatePosition);
        prepareFloor(source.getLevel(), duplicatePosition);
        manifest.putBoolean("conflictStarted", true);
        writeManifest(source, manifest);
        CompoundTag nbt = saveEntity(original);
        UUID duplicateId = UUID.randomUUID();
        nbt.putUUID("UUID", duplicateId);
        nbt.getCompound("Colonyloom").putLong("bindingEpoch", original.bindingEpoch() - 1);
        // Loading stale identity data, not aliasing a valid runtime identity via initializeIdentity.
        CitizenEntity duplicate = newCitizen(source.getLevel());
        duplicate.load(nbt);
        duplicate.moveTo(duplicatePosition.getX() + 0.5, duplicatePosition.getY(), duplicatePosition.getZ() + 0.5, 0, 0);
        duplicate.inventory().clearContent();
        duplicate.inventory().setItem(0, new ItemStack(Items.BREAD, 3));
        duplicate.inventory().setChanged();
        require(source, source.getLevel().addFreshEntity(duplicate), "real_duplicate_loaded", duplicateId.toString());
        CompoundTag duplicateExpected = expectedEntity(duplicate);
        duplicateExpected.putUUID("colony", expected.getUUID("colony"));
        manifest.put("duplicate", duplicateExpected);
        writeManifest(source, manifest);
        require(source, original.isQuarantined() && duplicate.isQuarantined(), "both_incarnations_quarantined", original.getUUID() + "/" + duplicateId);
        require(source, player.distanceToSqr(duplicate) <= 64, "duplicate_inventory_in_reach", duplicateId.toString());
        require(source, !original.openInventory(player, actor -> actor.getUUID().equals(manifest.getUUID("owner")))
                && !duplicate.openInventory(player, actor -> actor.getUUID().equals(manifest.getUUID("owner"))),
                "quarantine_inventory_inaccessible", "real authorized owner; no menu opened");
        checkEntity(source, original, expected);
        checkEntity(source, duplicate, duplicateExpected);
        long epoch = original.bindingEpoch();
        CompoundTag unloaded = saveEntity(duplicate);
        duplicate.remove(Entity.RemovalReason.UNLOADED_TO_CHUNK);
        try {
            require(source, source.getLevel().getEntity(duplicateId) == null, "duplicate_physically_unloaded", duplicateId.toString());
            String before = success(source, "colonyloom status " + expected.getUUID("colony"));
            refused(source, "colonyloom recovery bind " + original.citizenId() + " " + original.getUUID());
            require(source, before.equals(success(source, "colonyloom status " + expected.getUUID("colony"))), "failed_bind_no_record_mutation", before);
            require(source, original.isQuarantined() && original.bindingEpoch() == epoch, "unloaded_competitor_keeps_quarantine", original.getUUID().toString());
            checkEntity(source, original, expected);
        } finally {
            duplicate = reload(source, unloaded);
        }
        checkEntity(source, duplicate, duplicateExpected);
        String bound = success(source, "colonyloom recovery bind " + original.citizenId() + " " + original.getUUID());
        require(source, original.bindingEpoch() > epoch && !original.isQuarantined() && duplicate.isQuarantined(), "explicit_bind_new_epoch", bound);
        expected.putLong("epoch", original.bindingEpoch());
        checkEntity(source, original, expected);
        checkEntity(source, duplicate, duplicateExpected);
        String inspect = success(source, "colonyloom recovery inspect " + expected.getUUID("colony"));
        require(source, observation(inspect, duplicateId).contains("retired=true"), "competing_property_retired_not_deleted", inspect);
        writeManifest(source, manifest);
        verify(source, manifest, false);
    }

    private static void recovery(CommandSourceStack source, CompoundTag manifest) throws Exception {
        verify(source, manifest, true);
        ListTag colonies = manifest.getList("colonies", Tag.TAG_COMPOUND);
        for (Tag value : colonies) {
            CompoundTag colony = (CompoundTag) value;
            UUID colonyId = colony.getUUID("id");
            String status = success(source, "colonyloom status " + colonyId);
            UUID checkpoint = uuid(status, "recoveryCheckpointId");
            success(source, "colonyloom recovery inspect " + colonyId);
            UUID wrongCheckpoint = new UUID(checkpoint.getMostSignificantBits() ^ 1, checkpoint.getLeastSignificantBits());
            refused(source, "colonyloom recovery accept-world " + colonyId + " " + wrongCheckpoint);
            require(source, status.equals(success(source, "colonyloom status " + colonyId)), "wrong_checkpoint_no_mutation", colonyId.toString());
            CompoundTag expected = null;
            for (Tag citizen : manifest.getList("citizens", Tag.TAG_COMPOUND)) {
                if (((CompoundTag) citizen).getUUID("colony").equals(colonyId)) { expected = (CompoundTag) citizen; break; }
            }
            if (expected == null) throw new IllegalStateException("Recovery fixture colony lacks citizen");
            CitizenEntity physical = entity(source, expected.getUUID("entity"));
            CompoundTag saved = saveEntity(physical);
            physical.remove(Entity.RemovalReason.UNLOADED_TO_CHUNK);
            try {
                require(source, source.getLevel().getEntity(expected.getUUID("entity")) == null, "recovery_entity_unloaded", expected.getUUID("entity").toString());
                String incomplete = success(source, "colonyloom recovery inspect " + colonyId);
                require(source, incomplete.contains("ready=false"), "incomplete_inspect_observed", incomplete);
                String before = success(source, "colonyloom status " + colonyId);
                refused(source, "colonyloom recovery accept-world " + colonyId + " " + checkpoint);
                require(source, before.equals(success(source, "colonyloom status " + colonyId)), "incomplete_accept_no_mutation", before);
            } finally {
                physical = reload(source, saved);
            }
            checkEntity(source, physical, expected);
            success(source, "colonyloom recovery inspect " + colonyId);
            saved = saveEntity(physical);
            physical.remove(Entity.RemovalReason.UNLOADED_TO_CHUNK);
            try {
                String before = success(source, "colonyloom status " + colonyId);
                refused(source, "colonyloom recovery accept-world " + colonyId + " " + checkpoint);
                require(source, before.equals(success(source, "colonyloom status " + colonyId)), "stale_inspect_no_mutation", before);
            } finally {
                physical = reload(source, saved);
            }
            checkEntity(source, physical, expected);
            if (colonyId.equals(manifest.getList("citizens", Tag.TAG_COMPOUND).getCompound(1).getUUID("colony"))) {
                CompoundTag courierExpected = manifest.getList("citizens", Tag.TAG_COMPOUND).getCompound(1);
                CitizenEntity courier = entity(source, courierExpected.getUUID("entity"));
                checkEntity(source, courier, courierExpected);
                ItemStack bread = courier.inventory().getItem(0);
                require(source, bread.is(Items.BREAD) && bread.getCount() == 3, "real_bread_before_inspect", bread.toString());
                ItemStack originalBread = bread.copy();
                success(source, "colonyloom recovery inspect " + colonyId);
                try {
                    bread.shrink(1);
                    courier.inventory().setChanged();
                    List<CompoundTag> changedProperty = loadedProperty(source, manifest);
                    String before = success(source, "colonyloom status " + colonyId);
                    refused(source, "colonyloom recovery accept-world " + colonyId + " " + checkpoint);
                    require(source, before.equals(success(source, "colonyloom status " + colonyId)), "inventory_drift_accept_no_record_mutation", before);
                    require(source, changedProperty.equals(loadedProperty(source, manifest)), "inventory_drift_refusal_no_item_mutation", courier.getUUID().toString());
                } finally {
                    courier.inventory().setItem(0, originalBread);
                    courier.inventory().setChanged();
                }
                checkEntity(source, courier, courierExpected);
            }
            String ready = success(source, "colonyloom recovery inspect " + colonyId);
            require(source, ready.contains("ready=true"), "physical_recovery_ready", ready);
            List<CompoundTag> propertyBefore = loadedProperty(source, manifest);
            success(source, "colonyloom recovery accept-world " + colonyId + " " + checkpoint);
            require(source, propertyBefore.equals(loadedProperty(source, manifest)), "accept_preserves_all_physical_property", colonyId.toString());
            require(source, success(source, "colonyloom status " + colonyId).contains("recoveryBlocked=false"), "explicit_accept_clears_block", colonyId.toString());
        }
        verify(source, manifest, false);
    }

    private static List<CompoundTag> loadedProperty(CommandSourceStack source, CompoundTag manifest) {
        List<CompoundTag> result = new ArrayList<>();
        for (Tag value : manifest.getList("citizens", Tag.TAG_COMPOUND)) result.add(expectedEntity(entity(source, ((CompoundTag) value).getUUID("entity"))));
        if (manifest.contains("duplicate", Tag.TAG_COMPOUND)) result.add(expectedEntity(entity(source, manifest.getCompound("duplicate").getUUID("entity"))));
        return result;
    }

    private static void validateSite(CommandSourceStack source, BlockPos position) throws IOException {
        ServerLevel level = source.getLevel();
        require(source, level.hasChunkAt(position) && level.isPositionEntityTicking(position)
                && level.getWorldBorder().isWithinBounds(position)
                && position.getY() > level.getMinBuildHeight() && position.getY() + 2 < level.getMaxBuildHeight(), "fixture_site_ready", coordinates(position));
        for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) {
            BlockPos feet = position.offset(x, 0, z);
            require(source, level.getBlockState(feet).isAir() && level.getBlockState(feet.above()).isAir()
                    && level.getBlockState(feet.above(2)).isAir(), "fixture_air_no_destruction", coordinates(feet));
            require(source, level.getBlockState(feet.below()).isAir()
                    || level.getBlockState(feet.below()).isFaceSturdy(level, feet.below(), net.minecraft.core.Direction.UP),
                    "fixture_support_no_replacement", coordinates(feet.below()));
        }
    }

    private static void prepareFloor(ServerLevel level, BlockPos position) {
        for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) {
            BlockPos floor = position.offset(x, -1, z);
            if (level.getBlockState(floor).isAir() && !level.setBlockAndUpdate(floor, Blocks.STONE.defaultBlockState())) {
                throw new IllegalStateException("Fixture floor placement refused: " + floor);
            }
        }
    }

    private static CitizenEntity entity(CommandSourceStack source, UUID id) {
        Entity observed = source.getLevel().getEntity(id);
        if (!(observed instanceof CitizenEntity citizen) || !citizen.isAlive() || citizen.isRemoved()) {
            throw new IllegalStateException("Exact entity is not loaded/alive; no replacement allowed: " + id);
        }
        return citizen;
    }

    private static CitizenEntity newCitizen(ServerLevel level) {
        Entity created = BuiltInRegistries.ENTITY_TYPE.get(ResourceLocation.parse("colonyloom:citizen")).create(level);
        if (!(created instanceof CitizenEntity citizen)) throw new IllegalStateException("Citizen factory unavailable");
        return citizen;
    }

    private static CompoundTag saveEntity(CitizenEntity entity) {
        CompoundTag nbt = new CompoundTag();
        if (!entity.save(nbt)) throw new IllegalStateException("Cannot save physical citizen " + entity.getUUID());
        return nbt;
    }

    private static CitizenEntity reload(CommandSourceStack source, CompoundTag saved) throws IOException {
        CitizenEntity restored = newCitizen(source.getLevel());
        restored.load(saved);
        require(source, source.getLevel().getEntity(restored.getUUID()) == null, "reload_exact_unloaded_uuid", restored.getUUID().toString());
        require(source, source.getLevel().addFreshEntity(restored), "real_saved_entity_reloaded", restored.getUUID().toString());
        return restored;
    }

    private static CompoundTag expectedEntity(CitizenEntity entity) {
        CompoundTag result = new CompoundTag();
        result.putUUID("citizen", entity.citizenId());
        result.putUUID("entity", entity.getUUID());
        result.putLong("epoch", entity.bindingEpoch());
        ListTag slots = new ListTag();
        for (int slot = 0; slot < CitizenEntity.INVENTORY_SIZE; slot++) slots.add(entity.inventory().getItem(slot).saveOptional(entity.registryAccess()));
        result.put("expectedSlots", slots);
        return result;
    }

    private static void checkEntity(CommandSourceStack source, CitizenEntity entity, CompoundTag expected) throws IOException {
        require(source, entity.getUUID().equals(expected.getUUID("entity")) && expected.getUUID("citizen").equals(entity.citizenId())
                && entity.bindingEpoch() == expected.getLong("epoch"), "exact_physical_identity", expected.toString());
        ListTag slots = expected.getList("expectedSlots", Tag.TAG_COMPOUND);
        require(source, slots.size() == CitizenEntity.INVENTORY_SIZE, "manifest_complete_slots", entity.getUUID().toString());
        for (int slot = 0; slot < CitizenEntity.INVENTORY_SIZE; slot++) {
            ItemStack expectedStack = ItemStack.parseOptional(entity.registryAccess(), slots.getCompound(slot));
            require(source, ItemStack.matches(entity.inventory().getItem(slot), expectedStack), "exact_physical_items",
                    entity.getUUID() + " slot=" + slot + " expected=" + slots.getCompound(slot) + " observed=" + entity.inventory().getItem(slot).saveOptional(entity.registryAccess()));
        }
    }

    private static String observation(String inspect, UUID entityId) {
        var match = Pattern.compile("Observation\\[[^\\]]*entityId=" + entityId + "[^\\]]*\\]").matcher(inspect);
        if (!match.find()) throw new IllegalStateException("Missing diagnostic observation " + entityId);
        return match.group();
    }

    private static UUID uuid(String output, String key) {
        var match = Pattern.compile("(?:^|[\\s\\[,])" + Pattern.quote(key) + "=([0-9a-fA-F-]{36})(?=[\\s\\],]|$)").matcher(output);
        if (!match.find()) throw new IllegalStateException("Command output missing UUID " + key + ": " + output);
        return UUID.fromString(match.group(1));
    }

    private static String success(CommandSourceStack source, String command) throws Exception {
        CommandResult result = command(source, command);
        require(source, result.code() == 1, "command_success", command + " output=" + result.output());
        return result.output();
    }

    private static void refused(CommandSourceStack source, String command) throws Exception {
        CommandResult result = command(source, command);
        require(source, result.code() == 0 && !result.output().isBlank(), "command_refused_with_error", command + " output=" + result.output());
    }

    private static CommandResult command(CommandSourceStack source, String command) throws Exception {
        Capture capture = new Capture();
        int result;
        try {
            result = source.getServer().getCommands().getDispatcher().execute(command, source.withSource(capture));
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException error) {
            capture.sendSystemMessage(Component.literal(error.getMessage()));
            result = 0;
        }
        String output = String.join("\n", capture.messages);
        JsonObject record = new JsonObject();
        record.addProperty("type", "command");
        record.addProperty("command", command);
        record.addProperty("result", result);
        record.addProperty("output", output);
        log(source, record);
        return new CommandResult(result, output);
    }

    private record CommandResult(int code, String output) {}
    private static final class Capture implements CommandSource {
        private final List<String> messages = new ArrayList<>();
        public void sendSystemMessage(Component component) { messages.add(component.getString()); }
        public boolean acceptsSuccess() { return true; }
        public boolean acceptsFailure() { return true; }
        public boolean shouldInformAdmins() { return false; }
    }

    private static String coordinates(BlockPos pos) { return pos.getX() + " " + pos.getY() + " " + pos.getZ(); }
    private static Path world(CommandSourceStack source) { return source.getServer().getWorldPath(LevelResource.ROOT); }

    private static CompoundTag readManifest(CommandSourceStack source) throws IOException {
        Path path = world(source).resolve(MANIFEST);
        if (!Files.isRegularFile(path) || Files.size(path) > NBT_LIMIT) throw new IOException("Missing or oversized fixture manifest: " + path);
        CompoundTag manifest = NbtIo.readCompressed(path, NbtAccounter.create(NBT_LIMIT));
        if (manifest.getInt("schemaVersion") != 1) throw new IOException("Unsupported fixture manifest schema");
        return manifest;
    }

    private static void writeManifest(CommandSourceStack source, CompoundTag manifest) throws IOException {
        Path path = world(source).resolve(MANIFEST);
        Path temporary = Files.createTempFile(path.getParent(), "colonyloom-identity-", ".tmp");
        try {
            NbtIo.writeCompressed(manifest, temporary);
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) { channel.force(true); }
            if (!manifest.equals(NbtIo.readCompressed(temporary, NbtAccounter.create(NBT_LIMIT)))) throw new IOException("Manifest round trip mismatch");
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void require(CommandSourceStack source, boolean condition, String check, String detail) throws IOException {
        fact(source, check, condition, detail);
        if (!condition) throw new IllegalStateException(check + ": " + detail);
    }

    private static void fact(CommandSourceStack source, String check, boolean passed, String detail) {
        JsonObject record = new JsonObject();
        record.addProperty("type", "fact");
        record.addProperty("check", check);
        record.addProperty("passed", passed);
        record.addProperty("detail", detail);
        try { log(source, record); }
        catch (IOException error) { throw new IllegalStateException("Cannot record scenario evidence", error); }
    }

    private static void log(CommandSourceStack source, JsonObject record) throws IOException {
        record.addProperty("tick", source.getServer().getTickCount());
        record.addProperty("actor", source.getEntity() == null ? "console" : source.getEntity().getUUID().toString());
        String line = record.toString();
        LOGGER.info("COLONYLOOM_IDENTITY {}", line);
        Files.writeString(world(source).resolve("colonyloom-identity-observations.jsonl"), line + System.lineSeparator(),
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
}
