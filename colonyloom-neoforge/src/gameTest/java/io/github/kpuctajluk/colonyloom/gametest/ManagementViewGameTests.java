package io.github.kpuctajluk.colonyloom.gametest;

import io.github.kpuctajluk.colonyloom.core.citizen.CitizenRecord;
import io.github.kpuctajluk.colonyloom.core.colony.*;
import io.github.kpuctajluk.colonyloom.core.config.SimulationLimits;
import io.github.kpuctajluk.colonyloom.core.config.SimulationLimits.Budget;
import io.github.kpuctajluk.colonyloom.core.config.SimulationLimits.Resource;
import io.github.kpuctajluk.colonyloom.core.management.ManagementProtocol;
import io.github.kpuctajluk.colonyloom.core.management.ManagementProtocol.*;
import io.github.kpuctajluk.colonyloom.core.scheduler.AdmissionLedger;
import io.github.kpuctajluk.colonyloom.core.storage.*;
import io.github.kpuctajluk.colonyloom.minecraft.view.ManagementViews;
import java.util.*;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("colonyloom")
@PrefixGameTestTemplate(false)
public final class ManagementViewGameTests {
    @GameTest(template="identity_empty")
    public static void canonicalStockPagesExcludeForeignSlotsAndPromisedAmounts(GameTestHelper helper) {
        var registry=new ColonyRegistry(() -> {});
        UUID a=UUID.randomUUID(),b=UUID.randomUUID(),owner=UUID.randomUUID();
        colonies(registry,a,b,owner);
        var own=new StockRegion(new StorageId("minecraft:overworld",UUID.randomUUID(),0),0);
        var foreign=new StockRegion(new StorageId("minecraft:overworld",UUID.randomUUID(),0),0);
        var ownPosition=new WorldPosition("minecraft:overworld",1,64,1);
        var otherPosition=new WorldPosition("minecraft:overworld",65,64,1);
        registry.storage().register(a,ownPosition,"warehouse",List.of(own.storage()),List.of(own),List.of(ownPosition));
        registry.storage().register(b,otherPosition,"warehouse",List.of(foreign.storage()),List.of(foreign),List.of(otherPosition));
        var item=new ItemDescriptor("minecraft:oak_log",new byte[0]);
        registry.storage().index().observe(own,item,12,1);
        registry.storage().index().observe(foreign,item,99,1);
        registry.storage().reservations().reserve(UUID.randomUUID(),a,UUID.randomUUID(),own,item,5,1,AdmissionLedger.Lane.NORMAL);
        var rows=new ArrayList<Row>();
        for(int page=0;page<2;page++)rows.addAll(complete(helper,registry,owner,a,ViewType.SUMMARY,page,1).rows());
        var stocks=rows.stream().filter(row -> row.state().equals("STOCK_READY")).toList();
        helper.assertTrue(stocks.size()==1&&stocks.getFirst().relatedId().equals(own.storage().identity())&&stocks.getFirst().detail().equals("observed=12;free=7;slot=0"),"Scoped stock view exposed foreign stock or promised quantity");
        try {
            preparation(registry,owner,b,ViewType.SUMMARY,0);
            helper.fail("Known foreign colony UUID opened a page");
        } catch(SecurityException expected) {}
        helper.succeed();
    }

    @GameTest(template="identity_empty",timeoutTicks=200)
    public static void oneRowQuotaCompletesFiftyRowsDuringContinuousStateAndSlotMutation(GameTestHelper helper) {
        var registry=new ColonyRegistry(() -> {});
        UUID colony=UUID.randomUUID(),foreignColony=UUID.randomUUID(),owner=UUID.randomUUID();
        colonies(registry,colony,foreignColony,owner);
        quota(registry,1);
        var ownStorage=new StorageId("minecraft:overworld",UUID.randomUUID(),0);
        var secondStorage=new StorageId("minecraft:overworld",UUID.randomUUID(),0);
        var ownSlots=new ArrayList<StockRegion>();
        for(int slot=0;slot<54;slot++)ownSlots.add(new StockRegion(slot<27?ownStorage:secondStorage,slot%27));
        var ownPosition=new WorldPosition("minecraft:overworld",1,64,1);
        var secondPosition=new WorldPosition("minecraft:overworld",2,64,1);
        registry.storage().register(colony,ownPosition,"warehouse",List.of(ownStorage,secondStorage),ownSlots,List.of(ownPosition,secondPosition));
        var foreignStorage=new StorageId("minecraft:overworld",UUID.randomUUID(),0);
        var foreignSlots=new ArrayList<StockRegion>();
        for(int slot=0;slot<5;slot++)foreignSlots.add(new StockRegion(foreignStorage,slot));
        var foreignPosition=new WorldPosition("minecraft:overworld",65,64,1);
        registry.storage().register(foreignColony,foreignPosition,"warehouse",List.of(foreignStorage),foreignSlots,List.of(foreignPosition));
        var changingPosition=new WorldPosition("minecraft:overworld",66,64,1);
        var changingStorage=new StorageId("minecraft:overworld",UUID.randomUUID(),0);
        registry.storage().register(foreignColony,changingPosition,"warehouse",List.of(changingStorage),List.of(new StockRegion(changingStorage,0)),List.of(changingPosition));
        var item=new ItemDescriptor("minecraft:oak_log",new byte[0]);
        for(var slot:ownSlots)registry.storage().index().observe(slot,item,12,1);
        for(var slot:foreignSlots)registry.storage().index().observe(slot,item,99,1);
        registry.storage().reservations().reserve(UUID.randomUUID(),colony,UUID.randomUUID(),ownSlots.getFirst(),item,5,1,AdmissionLedger.Lane.NORMAL);
        var preparation=preparation(registry,owner,colony,ViewType.SUMMARY,0);
        long[] tick={0};boolean[] done={false};
        helper.onEachTick(() -> {
            if(done[0])return;
            registry.budgets().beginTick(++tick[0]);
            // Every portion sees a different authoritative aggregate, plus a structurally
            // removed/inserted canonical slot. Neither may discard or extend the initial sweep.
            var current=registry.colony(colony);
            registry.updateColony(new ColonyRuntime(colony,current.name(),current.territory(),owner,current.members(),current.revision()+1,current.authorityRevision(),false,null,false));
            registry.storage().register(foreignColony,changingPosition,"warehouse",List.of(changingStorage),List.of(new StockRegion(changingStorage,(int)(tick[0]%27))),List.of(changingPosition));
            registry.storage().index().observe(ownSlots.getFirst(),item,12,tick[0]);
            boolean ready=preparation.advance(registry.budgets());
            helper.assertTrue(registry.budgets().used(Budget.VIEW_ROWS)==1,"VIEW_ROWS=1 visited more than one unit, or stopped making progress");
            if(!ready)return;
            done[0]=true;
            var page=preparation.result();
            helper.assertTrue(page.rows().size()==ManagementProtocol.PAGE_ROWS&&page.totalRows()==1+Resource.values().length+54,"Small legal quota did not complete the initial fifty-row page");
            int firstStock=1+Resource.values().length;
            for(int index=firstStock;index<page.rows().size();index++) {
                int slot=index-firstStock;var row=page.rows().get(index);
                helper.assertTrue(row.relatedId().equals((slot<27?ownStorage:secondStorage).identity())&&row.state().equals("STOCK_READY")&&row.name().equals("minecraft:oak_log")
                        &&row.detail().equals("observed=12;free="+(slot==0?7:12)+";slot="+(slot%27)),"Initial page included foreign/replaced stock or reserved quantity: "+row);
            }
            var sample=registry.metrics().snapshot().get("VIEW_UNIT");
            helper.assertTrue(sample.count()==registry.budgets().totalConsumed(Budget.VIEW_ROWS)&&sample.totalNanos()>0&&sample.maxNanos()>0,"Actual per-unit view timings are absent or synthetic");
            helper.assertTrue(tick[0]<150&&registry.storage().index().slots().size()==60,"Continuous replacement leaked deleted cursor keys or prevented bounded completion");
            helper.succeed();
        });
    }

    @GameTest(template="identity_empty")
    public static void quotaFortyNineCompletesFiftyRowsAndSmallerRemainingPages(GameTestHelper helper) {
        var registry=new ColonyRegistry(() -> {});
        UUID colony=UUID.randomUUID(),foreignColony=UUID.randomUUID(),owner=UUID.randomUUID();
        colonies(registry,colony,foreignColony,owner);
        var position=new WorldPosition("minecraft:overworld",1,64,1);
        var expected=new ArrayList<UUID>();
        for(int work=0;work<56;work++)expected.add(registry.workBoard().createTimer(UUID.randomUUID(),colony,position,null,work%11,AdmissionLedger.Lane.NORMAL,100).id());
        for(int work=0;work<7;work++)registry.workBoard().createTimer(UUID.randomUUID(),foreignColony,new WorldPosition("minecraft:overworld",65,64,1),null,0,AdmissionLedger.Lane.NORMAL,100);
        quota(registry,49);
        var first=preparation(registry,owner,colony,ViewType.WORK,0);
        registry.budgets().beginTick(1);
        helper.assertTrue(!first.advance(registry.budgets())&&registry.budgets().used(Budget.VIEW_ROWS)==49,"Quota49 was treated as a fifty-row admission barrier");
        registry.budgets().beginTick(2);
        helper.assertTrue(first.advance(registry.budgets())&&registry.budgets().used(Budget.VIEW_ROWS)==15,"Remainder failed to resume with only fifteen real units");
        helper.assertTrue(first.result().rows().stream().map(Row::id).toList().equals(expected.subList(0,50))&&first.result().totalRows()==56,"Quota49 changed the first page or exposed foreign work");
        var remaining=complete(helper,registry,owner,colony,ViewType.WORK,1,49);
        helper.assertTrue(remaining.rows().stream().map(Row::id).toList().equals(expected.subList(50,56))&&remaining.totalRows()==56,"Smaller remaining page never completed or skipped work");
        var absent=complete(helper,registry,owner,colony,ViewType.WORK,2,1);
        helper.assertTrue(absent.rows().isEmpty()&&absent.totalRows()==56,"Out-of-range page did not finish under quota1");
        helper.succeed();
    }

    @GameTest(template="identity_empty",timeoutTicks=160)
    public static void workCursorSurvivesRetirementAndExcludesLaterInsertions(GameTestHelper helper) {
        var registry=new ColonyRegistry(() -> {});
        UUID colony=UUID.randomUUID(),foreignColony=UUID.randomUUID(),owner=UUID.randomUUID();
        colonies(registry,colony,foreignColony,owner);quota(registry,1);
        var position=new WorldPosition("minecraft:overworld",1,64,1);
        var expected=new ArrayList<UUID>();
        for(int work=0;work<60;work++)expected.add(registry.workBoard().createTimer(UUID.randomUUID(),colony,position,null,0,AdmissionLedger.Lane.NORMAL,100).id());
        UUID removed=expected.removeLast();
        var preparation=preparation(registry,owner,colony,ViewType.WORK,0);
        long[] tick={0};UUID[] added={null};boolean[] done={false};
        helper.onEachTick(() -> {
            if(done[0])return;
            registry.budgets().beginTick(++tick[0]);
            if(tick[0]==1){registry.workBoard().cancel(removed);registry.workBoard().retire(removed);}
            if(added[0]!=null){registry.workBoard().cancel(added[0]);registry.workBoard().retire(added[0]);}
            added[0]=registry.workBoard().createTimer(UUID.randomUUID(),colony,position,null,0,AdmissionLedger.Lane.NORMAL,100).id();
            registry.workBoard().priority(expected.getFirst(),(int)(tick[0]%11));
            boolean ready=preparation.advance(registry.budgets());
            helper.assertTrue(registry.budgets().used(Budget.VIEW_ROWS)==1,"Work insertion/retirement exceeded quota1");
            if(!ready)return;
            done[0]=true;var page=preparation.result();
            helper.assertTrue(page.totalRows()==59&&page.rows().stream().map(Row::id).toList().equals(expected.subList(0,50)),"Retired or later-inserted work escaped the initial membership cutoff");
            helper.assertTrue(page.rows().getFirst().revision()==1,"Work rows expose tick telemetry revision instead of command revision");
            int liveKeys=0;Long key=null;
            while((key=registry.workBoard().nextWorkViewKey(key))!=null){helper.assertTrue(registry.workBoard().workAtViewKey(key)!=null,"Retirement retained a stale live key");liveKeys++;}
            helper.assertTrue(liveKeys==registry.workBoard().works().size()&&tick[0]==60,"Live key index leaked deleted entries or sweep followed new insertions forever");
            helper.succeed();
        });
    }

    @GameTest(template="identity_empty")
    public static void citizenAndBuildingSourcesRemainScopedAndResumable(GameTestHelper helper) {
        var registry=new ColonyRegistry(() -> {});
        UUID colony=UUID.randomUUID(),foreignColony=UUID.randomUUID(),owner=UUID.randomUUID();
        colonies(registry,colony,foreignColony,owner);
        registry.admission().updateLimits(registry.admission().limits().scale300Capacity());
        var citizens=new ArrayList<UUID>();
        for(int index=0;index<55;index++) {
            UUID id=UUID.randomUUID();citizens.add(id);
            registry.addCitizen(new CitizenRecord(id,colony,UUID.randomUUID(),1,null,null,null,null,Map.of(),Map.of("food",20),CitizenRecord.Lifecycle.ALIVE,CitizenRecord.Admission.INACTIVE,CitizenRecord.Readiness.UNKNOWN,0,Map.of(),new WorldPosition("minecraft:overworld",1,64,1),1),ignored -> {});
        }
        registry.addCitizen(new CitizenRecord(UUID.randomUUID(),foreignColony,UUID.randomUUID(),1,null,null,null,null,Map.of(),Map.of("food",20),CitizenRecord.Lifecycle.ALIVE,CitizenRecord.Admission.INACTIVE,CitizenRecord.Readiness.UNKNOWN,0,Map.of(),new WorldPosition("minecraft:overworld",65,64,1),1),ignored -> {});
        helper.assertTrue(complete(helper,registry,owner,colony,ViewType.CITIZENS,0,1).rows().stream().map(Row::id).toList().equals(citizens.subList(0,50)),"Citizen preparation crossed scope or skipped the initial fifty rows");
        helper.assertTrue(complete(helper,registry,owner,colony,ViewType.CITIZENS,1,49).rows().stream().map(Row::id).toList().equals(citizens.subList(50,55)),"Citizen remainder starved");
        var buildings=new ArrayList<UUID>();
        for(int index=0;index<26;index++) {
            var position=new WorldPosition("minecraft:overworld",1+(index%16),64,1+(index/16));
            var storage=new StorageId("minecraft:overworld",UUID.randomUUID(),0);
            var registration=registry.storage().register(colony,position,"workshop",List.of(storage),List.of(new StockRegion(storage,0)),List.of(position));
            buildings.add(registry.storage().registerWorkshop(colony,position,registration.id()).id());
        }
        for(var registration:registry.storage().registrations(colony))buildings.add(registration.id());
        var foreignPosition=new WorldPosition("minecraft:overworld",65,64,1);
        var foreignStorage=new StorageId("minecraft:overworld",UUID.randomUUID(),0);
        var foreign=registry.storage().register(foreignColony,foreignPosition,"workshop",List.of(foreignStorage),List.of(new StockRegion(foreignStorage,0)),List.of(foreignPosition));
        registry.storage().registerWorkshop(foreignColony,foreignPosition,foreign.id());
        helper.assertTrue(complete(helper,registry,owner,colony,ViewType.BUILDINGS,0,1).rows().stream().map(Row::id).toList().equals(buildings.subList(0,50)),"Workshop/registration preparation crossed scope or reset its portion cursor");
        helper.assertTrue(complete(helper,registry,owner,colony,ViewType.BUILDINGS,1,49).rows().stream().map(Row::id).toList().equals(buildings.subList(50,52)),"Small building remainder starved");
        helper.succeed();
    }

    @GameTest(template="identity_empty")
    public static void cancellationAndAuthorityChangesDiscardIncompletePages(GameTestHelper helper) {
        var registry=new ColonyRegistry(() -> {});
        UUID colony=UUID.randomUUID(),foreign=UUID.randomUUID(),owner=UUID.randomUUID();
        colonies(registry,colony,foreign,owner);quota(registry,1);
        var cancelled=preparation(registry,owner,colony,ViewType.SUMMARY,0);
        registry.budgets().beginTick(1);helper.assertTrue(!cancelled.advance(registry.budgets()),"Cancellation fixture unexpectedly completed");
        cancelled.cancel();registry.budgets().beginTick(2);
        try {cancelled.advance(registry.budgets());helper.fail("Cancelled preparation continued");}catch(IllegalStateException expected){}
        try {cancelled.result();helper.fail("Cancelled preparation returned stale data");}catch(IllegalStateException expected){}
        helper.assertTrue(registry.budgets().used(Budget.VIEW_ROWS)==0,"Cancelled preparation consumed units");
        var changed=preparation(registry,owner,colony,ViewType.SUMMARY,0);
        helper.assertTrue(!changed.advance(registry.budgets()),"Authority fixture unexpectedly completed");
        var current=registry.colony(colony);
        registry.updateColony(new ColonyRuntime(colony,current.name(),current.territory(),owner,current.members(),current.revision()+1,current.authorityRevision()+1,false,null,false));
        registry.budgets().beginTick(3);
        try {changed.advance(registry.budgets());helper.fail("Old-authority portion survived invalidation");}catch(IllegalStateException expected){}
        changed.cancel();
        var revoked=preparation(registry,owner,colony,ViewType.SUMMARY,0);
        helper.assertTrue(!revoked.advance(registry.budgets()),"Revocation fixture unexpectedly completed");
        current=registry.colony(colony);
        registry.updateColony(new ColonyRuntime(colony,current.name(),current.territory(),UUID.randomUUID(),Map.of(),current.revision()+1,current.authorityRevision()+1,false,null,false));
        registry.budgets().beginTick(4);
        try {revoked.advance(registry.budgets());helper.fail("Revoked actor completed a page");}catch(SecurityException expected){}
        try {revoked.result();helper.fail("Revoked actor retrieved retained rows");}catch(SecurityException expected){}
        helper.assertTrue(registry.budgets().used(Budget.VIEW_ROWS)==0,"Revoked preparation consumed units before authority recheck");
        revoked.cancel();helper.succeed();
    }

    @GameTest(template="identity_empty")
    public static void exhaustedTimeGuardDefersWithoutChargingOrDiscardingPreparation(GameTestHelper helper) {
        var registry=new ColonyRegistry(() -> {});
        UUID colony=UUID.randomUUID(),foreign=UUID.randomUUID(),owner=UUID.randomUUID();
        colonies(registry,colony,foreign,owner);
        var limits=registry.admission().limits();
        var clock=new java.util.concurrent.atomic.AtomicLong();
        var budgets=new io.github.kpuctajluk.colonyloom.core.scheduler.GlobalWorkBudgets(limits,clock::get);
        var preparation=preparation(registry,owner,colony,ViewType.SUMMARY,0);
        budgets.beginTick(1);clock.set(limits.maxManagedNanos());
        helper.assertTrue(!preparation.advance(budgets)&&budgets.used(Budget.VIEW_ROWS)==0&&registry.metrics().snapshot().get("VIEW_UNIT").count()==0,"Exhausted time guard charged or measured nonexistent row work");
        budgets.beginTick(2);
        helper.assertTrue(preparation.advance(budgets)&&preparation.result().rows().size()==1+Resource.values().length,"Preparation was lost instead of resumed after the global time guard");
        helper.succeed();
    }

    @GameTest(template="identity_empty")
    public static void retainedCatalogAndRowsAreAdmittedBeforeExceedingWireLimit(GameTestHelper helper) {
        var registry=new ColonyRegistry(() -> {});
        UUID colony=UUID.randomUUID(),foreign=UUID.randomUUID(),owner=UUID.randomUUID();
        colonies(registry,colony,foreign,owner);quota(registry,100);
        var subscription=new Subscription(UUID.randomUUID(),UUID.randomUUID(),colony,ViewType.SUMMARY,0,false);
        var strings=Collections.nCopies(64,"x".repeat(ManagementProtocol.STRING_BYTES));
        try {new ManagementViews.Preparation(registry,owner,subscription,strings,strings,1);helper.fail("Oversized retained catalogs were copied into a preparation");}
        catch(IllegalArgumentException expected){helper.assertTrue(expected.getMessage().equals("VIEW_LIMIT"),"Catalog envelope failed for an unrelated reason");}
        var preparation=new ManagementViews.Preparation(registry,owner,subscription,strings.subList(0,62),strings.subList(0,63),1);
        registry.budgets().beginTick(1);
        try {preparation.advance(registry.budgets());helper.fail("Incremental rows exceeded retained wire limit");}
        catch(IllegalArgumentException expected){helper.assertTrue(expected.getMessage().equals("VIEW_LIMIT"),"Incremental page envelope failed for an unrelated reason");}
        helper.assertTrue(registry.metrics().snapshot().get("VIEW_UNIT").count()==registry.budgets().used(Budget.VIEW_ROWS),"Rejected emitted unit was not timed in finally");
        preparation.cancel();helper.succeed();
    }

    @GameTest(template="identity_empty")
    public static void restoredSourcesRebuildLiveKeysWithoutRetainingPreviousMembership(GameTestHelper helper) {
        var registry=new ColonyRegistry(() -> {});
        UUID colony=UUID.randomUUID(),foreign=UUID.randomUUID(),owner=UUID.randomUUID();
        colonies(registry,colony,foreign,owner);
        var position=new WorldPosition("minecraft:overworld",1,64,1);
        UUID citizen=UUID.randomUUID();
        registry.addCitizen(new CitizenRecord(citizen,colony,UUID.randomUUID(),1,null,null,null,null,Map.of(),Map.of("food",20),CitizenRecord.Lifecycle.ALIVE,CitizenRecord.Admission.INACTIVE,CitizenRecord.Readiness.UNKNOWN,0,Map.of(),position,1),ignored -> {});
        var storage=new StorageId("minecraft:overworld",UUID.randomUUID(),0);var slot=new StockRegion(storage,0);
        var registration=registry.storage().register(colony,position,"workshop",List.of(storage),List.of(slot),List.of(position));
        var workshop=registry.storage().registerWorkshop(colony,position,registration.id());
        var work=registry.workBoard().createTimer(UUID.randomUUID(),colony,position,null,0,AdmissionLedger.Lane.NORMAL,100);
        var interrupted=preparation(registry,owner,colony,ViewType.WORK,0);
        registry.restore(registry.snapshot());
        registry.budgets().beginTick(1);
        helper.assertTrue(interrupted.advance(registry.budgets())&&interrupted.result().rows().isEmpty(),"Restore reused old live keys for replacement objects");
        helper.assertTrue(complete(helper,registry,owner,colony,ViewType.WORK,0,1).rows().getFirst().id().equals(work.id()),"Restored work keys were not rebuilt");
        helper.assertTrue(complete(helper,registry,owner,colony,ViewType.CITIZENS,0,1).rows().getFirst().id().equals(citizen),"Restored citizen keys were not rebuilt");
        helper.assertTrue(complete(helper,registry,owner,colony,ViewType.BUILDINGS,0,1).rows().stream().map(Row::id).toList().equals(List.of(workshop.id(),registration.id())),"Restored storage/workshop keys were not rebuilt");
        helper.assertTrue(complete(helper,registry,owner,colony,ViewType.SUMMARY,0,1).rows().stream().anyMatch(row -> storage.identity().equals(row.relatedId())),"Restored canonical slot keys were not rebuilt");
        helper.succeed();
    }

    private static void colonies(ColonyRegistry registry,UUID a,UUID b,UUID owner) {
        registry.addColony(new ColonyRuntime(a,"A",new Territory("minecraft:overworld",0,0,31,31),owner,Map.of(),1,1,false,null,false));
        registry.addColony(new ColonyRuntime(b,"B",new Territory("minecraft:overworld",64,0,95,31),UUID.randomUUID(),Map.of(),1,1,false,null,false));
    }
    private static ManagementViews.Preparation preparation(ColonyRegistry registry,UUID owner,UUID colony,ViewType type,int page) {
        return new ManagementViews.Preparation(registry,owner,new Subscription(UUID.randomUUID(),UUID.randomUUID(),colony,type,page,false),List.of(),List.of(),Math.max(1,registry.budgets().tick()));
    }
    private static void quota(ColonyRegistry registry,int rows) {
        var limits=registry.admission().limits();var budgets=new EnumMap<Budget,Integer>(Budget.class);budgets.putAll(limits.budgets());budgets.put(Budget.VIEW_ROWS,rows);
        registry.budgets().updateLimits(new SimulationLimits(limits.resources(),budgets,100_000_000L));
    }
    private static ViewData complete(GameTestHelper helper,ColonyRegistry registry,UUID owner,UUID colony,ViewType type,int page,int rows) {
        quota(registry,rows);var preparation=preparation(registry,owner,colony,type,page);
        for(int portions=0;portions<256;portions++) {
            registry.budgets().beginTick(Math.max(1,registry.budgets().tick()+1));
            boolean ready=preparation.advance(registry.budgets());
            helper.assertTrue(registry.budgets().used(Budget.VIEW_ROWS)<=rows,"Portion exceeded legal row quota");
            if(ready)return preparation.result();
        }
        throw new AssertionError("Legal row quota permanently starved "+type+" page "+page);
    }
}
