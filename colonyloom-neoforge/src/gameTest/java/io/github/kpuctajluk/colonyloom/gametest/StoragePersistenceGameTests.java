package io.github.kpuctajluk.colonyloom.gametest;

import io.github.kpuctajluk.colonyloom.core.colony.*;
import io.github.kpuctajluk.colonyloom.core.runtime.ServerRuntime;
import io.github.kpuctajluk.colonyloom.core.storage.*;
import io.github.kpuctajluk.colonyloom.minecraft.persistence.ColonySavedData;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.*;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("colonyloom")
@PrefixGameTestTemplate(false)
public final class StoragePersistenceGameTests {
    private static UUID id(long n) {return new UUID(1,n);}
    private static final WorldPosition ADDRESS=new WorldPosition("minecraft:overworld",8,64,8);
    private static ServerRuntime fixture() {
        var runtime=ServerRuntime.start(Thread.currentThread());runtime.configureCommands(()->{},List.of());
        var colony=new ColonyRuntime(id(1),"Stocks",new Territory("minecraft:overworld",0,0,31,31),id(2),Map.of(),0,0,false,null,false);
        var old=runtime.registry().snapshot();
        runtime.registry().restore(new io.github.kpuctajluk.colonyloom.core.persistence.RegistrySnapshot(List.of(colony),old.citizens(),old.buildings(),old.tombstones(),old.observations(),old.works(),old.targetClaims(),old.effects(),old.constructionSites(),old.pinnedBlueprints(),old.storage()));
        return runtime;
    }
    private static CompoundTag stocked(GameTestHelper helper) {
        var runtime=fixture();var stocks=runtime.registry().storage();var storage=new StorageId(ADDRESS.dimension(),id(3),0);var slot=new StockRegion(storage,0);
        stocks.register(id(1),ADDRESS,"workshop",List.of(storage),List.of(slot),List.of(ADDRESS));
        var item=new ItemDescriptor("minecraft:oak_planks",new byte[]{10,0});stocks.index().observe(slot,item,64,0);
        stocks.reservations().reserve(id(4),id(1),id(20),slot,item,16,0);
        stocks.allocations().allocate(id(5),id(1),id(21),slot,item,32,0);
        stocks.retire(storage,id(1));
        return ColonySavedData.empty(runtime.registry().snapshot()).save(new CompoundTag(),helper.getLevel().registryAccess());
    }
    @GameTest(template="identity_empty")
    public static void compressedRestartPreservesRetiredObligationsUnknown(GameTestHelper helper) throws Exception {
        CompoundTag root=stocked(helper),envelope=new CompoundTag();envelope.put("data",root);
        var directory=Files.createTempDirectory("colonyloom-stock-restart-");var path=directory.resolve("colonyloom.dat");
        try {
            NbtIo.writeCompressed(envelope,path);
            var loaded=ColonySavedData.preflight(path,helper.getLevel().registryAccess());var runtime=fixture();runtime.registry().restore(loaded.snapshot());
            var stocks=runtime.registry().storage();var reservation=stocks.reservations().entries().iterator().next();var allocation=stocks.allocations().entries().iterator().next();
            helper.assertTrue(reservation.count()==16 && allocation.count()==32,"Unknown restart discarded accepted quantities");
            helper.assertTrue(stocks.isRetired(reservation.slot().storage()),"Restart forgot explicit identity retirement");
            helper.assertTrue(stocks.index().free(reservation.slot(),0)==0,"Unreconciled retired slot promised free stock");
            stocks.index().observe(reservation.slot(),reservation.item(),64,1);
            helper.assertTrue(stocks.index().free(reservation.slot(),1)==0,"Copied old attachment revived retired stock");
        } finally {Files.deleteIfExists(path);Files.deleteIfExists(directory);}
        helper.succeed();
    }
    @GameTest(template="identity_empty")
    public static void futureRegistrationRetainsDependentWorkshopAndOpaqueObligation(GameTestHelper helper) {
        var runtime=fixture();var stocks=runtime.registry().storage();var storage=new StorageId(ADDRESS.dimension(),id(3),0);
        var registration=stocks.register(id(1),ADDRESS,"workshop",List.of(storage),List.of(new StockRegion(storage,0)),List.of(ADDRESS));
        stocks.registerWorkshop(id(1),new WorldPosition(ADDRESS.dimension(),9,64,8),registration.id());
        var root=ColonySavedData.empty(runtime.registry().snapshot()).save(new CompoundTag(),helper.getLevel().registryAccess());
        for(Tag raw:root.getList("evidence",Tag.TAG_COMPOUND)) {var entry=(CompoundTag)raw;if(entry.getString("typeId").equals("colonyloom:storage_registration"))entry.putString("typeId","future:storage");}
        var opaque=new CompoundTag();opaque.putString("typeId","future:reservation");opaque.putUUID("colonyId",id(1));opaque.putUUID("obligationId",id(40));opaque.putString("opaque","preserve bytes");root.getList("reservations",Tag.TAG_COMPOUND).add(opaque);
        var loaded=ColonySavedData.load(root,helper.getLevel().registryAccess());
        helper.assertTrue(loaded.contentBlockedColonies().contains(id(1)),"Future stock failed to block economic promises");
        helper.assertTrue(loaded.snapshot().storage().workshops().isEmpty(),"Known workshop lost its unknown registration dependency");
        var saved=loaded.save(new CompoundTag(),helper.getLevel().registryAccess());
        helper.assertTrue(root.getList("evidence",Tag.TAG_COMPOUND).equals(saved.getList("evidence",Tag.TAG_COMPOUND)) && root.getList("reservations",Tag.TAG_COMPOUND).equals(saved.getList("reservations",Tag.TAG_COMPOUND)),"Future storage/dependency records changed on save");
        helper.succeed();
    }
}
