package io.github.kpuctajluk.colonyloom.gametest;

import com.mojang.authlib.GameProfile;
import io.github.kpuctajluk.colonyloom.core.action.ActionContext;
import io.github.kpuctajluk.colonyloom.core.citizen.CitizenRecord;
import io.github.kpuctajluk.colonyloom.core.colony.ColonyRegistry;
import io.github.kpuctajluk.colonyloom.core.colony.ColonyRuntime;
import io.github.kpuctajluk.colonyloom.core.colony.MemberRank;
import io.github.kpuctajluk.colonyloom.core.colony.Territory;
import io.github.kpuctajluk.colonyloom.core.colony.WorldPosition;
import io.github.kpuctajluk.colonyloom.core.content.BlockDescriptor;
import io.github.kpuctajluk.colonyloom.core.content.BlueprintDefinition;
import io.github.kpuctajluk.colonyloom.core.world.WorldAccess.Placement;
import io.github.kpuctajluk.colonyloom.minecraft.construction.BlockPlacementExecutor;
import io.github.kpuctajluk.colonyloom.minecraft.content.ContentLoader;
import io.github.kpuctajluk.colonyloom.minecraft.entity.CitizenEntity;
import io.github.kpuctajluk.colonyloom.neoforge.NeoForgeItemInteraction;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("colonyloom")
@PrefixGameTestTemplate(false)
public final class BlockPlacementGameTests {
    @GameTest(template = "identity_empty", batch = "stage05_placement", timeoutTicks = 100)
    public static void fourActualNpcStairsProduceFourBlocks(GameTestHelper helper) {
        withReadyFixture(helper,fixture -> {
            for (BlueprintDefinition.BlockSpec spec : fixture.blueprint.blocks()) {
                Placement result = fixture.executor.place(fixture.context(fixture.target(spec), ActionContext.AuthorityMode.COLONY, null), 1, spec.block(), 0);
                helper.assertTrue(result == Placement.PLACED, "Ordinary NPC item use failed: " + result);
            }
            for (BlueprintDefinition.BlockSpec spec : fixture.blueprint.blocks()) {
                helper.assertTrue(fixture.executor.matches(fixture.position(fixture.target(spec)), spec.block()), "Placed stairs differ from pinned BlockState");
            }
            helper.assertTrue(fixture.citizen.inventory().isEmpty(), "Four placements did not consume exactly four actual NPC stairs");
            System.out.println("COLONYLOOM_PLACEMENT actualBlocks=4 actualNpcStairs=0");
        });
    }

    @GameTest(template = "identity_empty", batch = "stage05_placement", timeoutTicks = 100)
    public static void cancellablePlacementAndInteractionVetoPreserveProperty(GameTestHelper helper) {
        withReadyFixture(helper,fixture -> {
            BlueprintDefinition.BlockSpec spec = fixture.blueprint.blocks().getFirst();
            BlockPos target = fixture.target(spec);
            int[] events = {0};
            Consumer<BlockEvent.EntityPlaceEvent> placementVeto = event -> {
                if (event.getPos().equals(target) && event.getEntity() instanceof ServerPlayer player && player.getUUID().equals(fixture.owner)) {
                    events[0]++;
                    helper.assertTrue(player.getMainHandItem() == fixture.citizen.inventory().getItem(0), "Placement event received a resource copy");
                    event.setCanceled(true);
                }
            };
            NeoForge.EVENT_BUS.addListener(BlockEvent.EntityPlaceEvent.class, placementVeto);
            try {
                Placement result = fixture.executor.place(fixture.context(target, ActionContext.AuthorityMode.COLONY, null), 1, spec.block(), 0);
                helper.assertTrue(result == Placement.PERMISSION_DENIED && events[0] == 1, "Real placement event did not veto item use");
                helper.assertTrue(helper.getLevel().getBlockState(target).isAir() && fixture.citizen.inventory().getItem(0).getCount() == 4, "Placement veto changed a block or spent a stair");
            } finally {
                NeoForge.EVENT_BUS.unregister(placementVeto);
            }
            Consumer<PlayerInteractEvent.RightClickBlock> interactionVeto = event -> {
                if (event.getPos().equals(target) && event.getEntity().getUUID().equals(fixture.owner)) { events[0]++; event.setCanceled(true); }
            };
            NeoForge.EVENT_BUS.addListener(PlayerInteractEvent.RightClickBlock.class, interactionVeto);
            try {
                Placement result = fixture.executor.place(fixture.context(target, ActionContext.AuthorityMode.COLONY, null), 1, spec.block(), 0);
                helper.assertTrue(result != Placement.PLACED && events[0] == 2, "Real interaction event did not veto item use");
                helper.assertTrue(helper.getLevel().getBlockState(target).isAir() && fixture.citizen.inventory().getItem(0).getCount() == 4, "Interaction veto changed a block or spent a stair");
            } finally {
                NeoForge.EVENT_BUS.unregister(interactionVeto);
            }
            helper.assertTrue(fixture.executor.place(fixture.context(target, ActionContext.AuthorityMode.COLONY, null), 1, spec.block(), 0) == Placement.PLACED, "A veto left stale borrowed resources or event state");
            helper.assertTrue(fixture.citizen.inventory().getItem(0).getCount() == 3, "Following successful placement did not consume exactly one stair");
        });
    }

    @GameTest(template = "identity_empty", batch = "stage05_placement", timeoutTicks = 100)
    public static void unexpectedListenerExpenseIsAmbiguousWithoutCompensation(GameTestHelper helper) {
        withReadyFixture(helper,fixture -> {
            BlueprintDefinition.BlockSpec spec = fixture.blueprint.blocks().getFirst();
            BlockPos target = fixture.target(spec);
            Consumer<PlayerInteractEvent.RightClickBlock> corruptingListener = event -> {
                if (event.getPos().equals(target) && event.getEntity().getUUID().equals(fixture.owner)) event.getItemStack().shrink(1);
            };
            NeoForge.EVENT_BUS.addListener(PlayerInteractEvent.RightClickBlock.class, corruptingListener);
            try {
                helper.assertTrue(fixture.executor.place(fixture.context(target, ActionContext.AuthorityMode.COLONY, null), 1, spec.block(), 0) == Placement.AMBIGUOUS, "Unexpected real item delta was silently committed");
                helper.assertTrue(fixture.executor.matches(fixture.position(target), spec.block()), "Ambiguous placement compensated by destroying the physical block");
                helper.assertTrue(fixture.citizen.inventory().getItem(0).getCount() == 2, "Ambiguous placement compensated by manufacturing a replacement stair");
            } finally {
                NeoForge.EVENT_BUS.unregister(corruptingListener);
            }
        });
    }

    @GameTest(template = "identity_empty", batch = "stage05_placement", timeoutTicks = 100)
    public static void rotatedExpectedFacingUsesRealItemOrientation(GameTestHelper helper) {
        withReadyFixture(helper,fixture -> {
            String[] facings = {"north", "east", "south", "west"};
            for (int index = 0; index < facings.length; index++) {
                BlockPos target = fixture.origin.offset(index, 0, 0);
                BlockDescriptor expected = new BlockDescriptor("minecraft:oak_stairs", Map.of("facing", facings[index], "half", "bottom", "shape", "straight", "waterlogged", "false"), "minecraft:oak_stairs");
                Placement result=fixture.executor.place(fixture.context(target,ActionContext.AuthorityMode.COLONY,null),1,expected,0);
                helper.assertTrue(result==Placement.PLACED,"Rotated item state failed for "+facings[index]+" result="+result+" actual="+helper.getLevel().getBlockState(target)+" inventory="+fixture.citizen.inventory().getItem(0));
                helper.assertTrue(fixture.executor.matches(fixture.position(target), expected), "Rotated placement mismatched expected facing");
                // Distinct orientations would legitimately reshape adjacent stairs; isolate each physical case.
                helper.getLevel().setBlockAndUpdate(target, Blocks.AIR.defaultBlockState());
            }
            helper.assertTrue(fixture.citizen.inventory().isEmpty(), "Rotated item uses consumed other than four stairs");
        });
    }

    @GameTest(template = "identity_empty", batch = "stage05_placement", timeoutTicks = 100)
    public static void playerPreexistingTargetNeverConsumesNpcMaterial(GameTestHelper helper) {
        withReadyFixture(helper,fixture -> {
            BlueprintDefinition.BlockSpec spec = fixture.blueprint.blocks().getFirst();
            BlockPos target = fixture.target(spec);
            helper.getLevel().setBlockAndUpdate(target, ContentLoader.decodeBlockState(spec.block()));
            helper.assertTrue(fixture.executor.place(fixture.context(target, ActionContext.AuthorityMode.COLONY, null), 1, spec.block(), 0) == Placement.ALREADY_PRESENT, "Existing exact target was placed twice");
            helper.assertTrue(fixture.citizen.inventory().getItem(0).getCount() == 4, "Existing target consumed NPC property");
            fixture.citizen.inventory().clearContent();
            helper.assertTrue(fixture.executor.place(fixture.context(target, ActionContext.AuthorityMode.COLONY, null), 1, spec.block(), 0) == Placement.ALREADY_PRESENT, "Already present target incorrectly requires new materials");
        });
    }

    @GameTest(template = "identity_empty", batch = "stage05_placement", timeoutTicks = 100)
    public static void currentOwnerAndRevokedIndividualAuthorityControlItemUse(GameTestHelper helper) {
        withReadyFixture(helper,fixture -> {
            BlueprintDefinition.BlockSpec spec = fixture.blueprint.blocks().getFirst();
            BlockPos target = fixture.target(spec);
            UUID manager = UUID.randomUUID(), nextOwner = UUID.randomUUID();
            fixture.profiles.put(manager, new GameProfile(manager, "PlacementManager"));
            fixture.profiles.put(nextOwner, new GameProfile(nextOwner, "PlacementNext"));
            ColonyRuntime initial = fixture.registry.colony(fixture.colony);
            fixture.registry.updateColony(new ColonyRuntime(initial.colonyId(), initial.name(), initial.territory(), initial.ownerId(), Map.of(manager, MemberRank.MANAGER), 2, 2, false, null, false));
            ActionContext oldIndividual = fixture.context(target, ActionContext.AuthorityMode.INDIVIDUAL, manager);
            fixture.registry.updateColony(new ColonyRuntime(initial.colonyId(), initial.name(), initial.territory(), nextOwner, Map.of(), 3, 3, false, null, false));
            helper.assertTrue(fixture.executor.place(oldIndividual, 1, spec.block(), 0) == Placement.PERMISSION_DENIED, "Stale individual authority survived revocation");
            helper.assertTrue(fixture.executor.place(fixture.context(target, ActionContext.AuthorityMode.INDIVIDUAL, manager), 1, spec.block(), 0) == Placement.PERMISSION_DENIED, "Fresh context restored revoked manager rights");
            helper.assertTrue(helper.getLevel().getBlockState(target).isAir() && fixture.citizen.inventory().getItem(0).getCount() == 4, "Denied authority changed world/property");
            UUID[] observedPrincipal = {null};
            Consumer<BlockEvent.EntityPlaceEvent> observeOwner = event -> {
                if (event.getPos().equals(target) && event.getEntity() instanceof ServerPlayer player) {
                    observedPrincipal[0] = player.getGameProfile().getId();
                    helper.assertTrue(!player.hasPermissions(2) && !player.isCreative(), "Colony actor gained operator/creative bypass");
                }
            };
            NeoForge.EVENT_BUS.addListener(BlockEvent.EntityPlaceEvent.class, observeOwner);
            try {
                helper.assertTrue(fixture.executor.place(fixture.context(target, ActionContext.AuthorityMode.COLONY, manager), 1, spec.block(), 0) == Placement.PLACED, "Colony action incorrectly inherited revoked initiator rights");
                helper.assertTrue(nextOwner.equals(observedPrincipal[0]), "Placement event used previous owner/initiator rather than current owner");
            } finally {
                NeoForge.EVENT_BUS.unregister(observeOwner);
            }
        });
    }

    private static void withReadyFixture(GameTestHelper helper,Consumer<Fixture> check) {
        BlockPos origin=helper.absolutePos(new BlockPos(1,1,1));
        var access=new io.github.kpuctajluk.colonyloom.neoforge.NeoForgeChunkAccess(helper.getLevel().getServer(),new net.neoforged.neoforge.common.world.chunk.TicketController(ResourceLocation.parse("colonyloom:runtime")));
        UUID owner=UUID.randomUUID(); var centers=new java.util.ArrayList<io.github.kpuctajluk.colonyloom.core.chunk.ChunkKey>();
        for(int x=(origin.getX()-2)>>4;x<=(origin.getX()+5)>>4;x++) for(int z=(origin.getZ()-2)>>4;z<=(origin.getZ()+4)>>4;z++) {
            var key=new io.github.kpuctajluk.colonyloom.core.chunk.ChunkKey(helper.getLevel().dimension().location().toString(),x,z); centers.add(key);
            if(!access.acquire(owner,key,io.github.kpuctajluk.colonyloom.core.chunk.ChunkDemandManager.Readiness.ENTITY_TICKING)) throw new IllegalStateException("Placement fixture ticket denied");
        }
        boolean[] done={false}; helper.onEachTick(() -> {
            if(done[0] || !centers.stream().allMatch(key -> access.ready(key,io.github.kpuctajluk.colonyloom.core.chunk.ChunkDemandManager.Readiness.ENTITY_TICKING))) return;
            done[0]=true;
            try(var fixture=new Fixture(helper,4)) { check.accept(fixture); }
            finally { for(var key:centers) access.release(owner,key,io.github.kpuctajluk.colonyloom.core.chunk.ChunkDemandManager.Readiness.ENTITY_TICKING); }
            helper.succeed();
        });
    }

    private static final class Fixture implements AutoCloseable {
        final GameTestHelper helper;
        final ColonyRegistry registry;
        final UUID colony = UUID.randomUUID(), identity = UUID.randomUUID(), owner = UUID.randomUUID();
        final CitizenEntity citizen;
        final BlockPos origin;
        final BlueprintDefinition blueprint;
        final BlockPlacementExecutor executor;
        final Map<UUID, GameProfile> profiles = new java.util.HashMap<>();
        Fixture(GameTestHelper helper, int stairs) {
            this.helper = helper;
            registry = new ColonyRegistry(() -> { if (!helper.getLevel().getServer().isSameThread()) throw new IllegalStateException("Fixture requires server thread"); });
            blueprint = ContentLoader.load(helper.getLevel().getServer().getResourceManager(),helper.getLevel().registryAccess()).blueprints().get("colonyloom:test_four_stairs");
            if (blueprint == null || blueprint.blocks().size() != 4) throw new IllegalStateException("Four-stair pinned test resource missing");
            origin = helper.absolutePos(new BlockPos(1, 1, 1));
            for (int x = -1; x <= 5; x++) for (int z = -1; z <= 4; z++) {
                BlockPos feet = origin.offset(x, 0, z);
                helper.getLevel().setBlockAndUpdate(feet.below(), Blocks.STONE.defaultBlockState());
                helper.getLevel().setBlockAndUpdate(feet, Blocks.AIR.defaultBlockState());
                helper.getLevel().setBlockAndUpdate(feet.above(), Blocks.AIR.defaultBlockState());
            }
            registry.addColony(new ColonyRuntime(colony, "Placement smoke", new Territory(helper.getLevel().dimension().location().toString(), origin.getX() - 8, origin.getZ() - 8, origin.getX() + 15, origin.getZ() + 15), owner, Map.of(), 1, 1, false, null, false));
            profiles.put(owner, new GameProfile(owner, "PlacementOwner"));
            citizen = (CitizenEntity) BuiltInRegistries.ENTITY_TYPE.get(ResourceLocation.parse("colonyloom:citizen")).create(helper.getLevel());
            if (citizen == null) throw new IllegalStateException("Citizen type unavailable");
            citizen.initializeIdentity(identity, 1);
            citizen.moveTo(origin.getX() + 2, origin.getY(), origin.getZ() + 2.5, 0, 0);
            citizen.inventory().setItem(0, new ItemStack(Items.OAK_STAIRS, stairs));
            registry.addCitizen(new CitizenRecord(identity, colony, citizen.getUUID(), 1, null, null, null, "colonyloom:builder", Map.of(), Map.of("food", 20), CitizenRecord.Lifecycle.ALIVE, CitizenRecord.Admission.ACTIVE, CitizenRecord.Readiness.READY, 0, Map.of("food", 1200L), position(citizen.blockPosition()), 1), record -> {
                if (!helper.getLevel().addFreshEntity(citizen)) throw new IllegalStateException("Physical fixture spawn refused");
            });
            registry.bindings().observe(identity, citizen.getUUID(), 1);
            citizen.setQuarantined(false);
            executor = new BlockPlacementExecutor(helper.getLevel().getServer(), registry, new NeoForgeItemInteraction(profiles::get));
        }
        BlockPos target(BlueprintDefinition.BlockSpec spec) { return origin.offset(spec.offset().x(), spec.offset().y(), spec.offset().z()); }
        WorldPosition position(BlockPos pos) { return new WorldPosition(helper.getLevel().dimension().location().toString(), pos.getX(), pos.getY(), pos.getZ()); }
        ActionContext context(BlockPos target, ActionContext.AuthorityMode mode, UUID initiator) {
            return new ActionContext(colony, identity, ActionContext.Kind.BLOCK_PLACE, position(target), mode, initiator, registry.colony(colony).authorityRevision());
        }
        @Override public void close() { citizen.remove(Entity.RemovalReason.DISCARDED); }
    }
}
