package io.github.kpuctajluk.colonyloom.gameplay.construction;

import io.github.kpuctajluk.colonyloom.core.colony.ColonyRegistry;
import io.github.kpuctajluk.colonyloom.core.colony.MemberRank;
import io.github.kpuctajluk.colonyloom.core.colony.WorldPosition;
import io.github.kpuctajluk.colonyloom.core.command.ColonyCommands;
import io.github.kpuctajluk.colonyloom.core.command.ConstructionCommands;
import io.github.kpuctajluk.colonyloom.core.construction.ConstructionSnapshot;
import io.github.kpuctajluk.colonyloom.core.content.BlockDescriptor;
import io.github.kpuctajluk.colonyloom.core.content.BlueprintDefinition;
import io.github.kpuctajluk.colonyloom.core.scheduler.AdmissionLedger.Lane;
import io.github.kpuctajluk.colonyloom.core.spatial.TargetClaimRegistry;
import io.github.kpuctajluk.colonyloom.core.work.WorkOrder;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Gameplay rules own sites; platform transforms and physical execution stay behind this consumer port. */
public final class ConstructionController implements ConstructionCommands {
    public record Target(WorldPosition position,BlockDescriptor expected) {
        public Target { Objects.requireNonNull(position); Objects.requireNonNull(expected); }
    }
    public record Layout(Target[] targets,WorldPosition workOrigin,TargetClaimRegistry.Snapshot claim) {
        public Layout { Objects.requireNonNull(targets); Objects.requireNonNull(workOrigin); Objects.requireNonNull(claim); if(targets.length==0) throw new IllegalArgumentException("Empty construction"); targets=targets.clone(); }
    }
    public interface Geometry {
        Layout layout(UUID workId,UUID colonyId,BlueprintDefinition definition,WorldPosition origin,int rotation);
        void validate(Layout layout);
    }
    private final ColonyRegistry registry;
    private final Geometry geometry;
    private Map<String,BlueprintDefinition> available=Map.of();
    public ConstructionController(ColonyRegistry registry,Geometry geometry) { this.registry=Objects.requireNonNull(registry); this.geometry=Objects.requireNonNull(geometry); }
    public void definitions(Map<String,BlueprintDefinition> definitions) { registry.requireOwner(); available=Map.copyOf(definitions); }
    public Layout layout(ConstructionSnapshot site) {
        registry.requireOwner(); var layout=geometry.layout(site.workId(),site.colonyId(),registry.construction().definition(site.blueprintDigest()),site.origin(),site.rotation());
        geometry.validate(layout); var colony=registry.colony(site.colonyId());
        if(!colony.territory().contains(layout.workOrigin())) throw new IllegalArgumentException("Pinned work origin outside territory");
        for(var target:layout.targets()) if(!colony.territory().contains(target.position())) throw new IllegalArgumentException("Pinned target outside territory");
        var stored=registry.targetClaims().snapshots().stream().filter(claim -> claim.ownerId().equals(site.workId())).findFirst().orElse(null);
        if(!site.closed() && (stored==null || !stored.equals(layout.claim()))) throw new IllegalArgumentException("Pinned geometry differs from authoritative target claim");
        return layout;
    }
    public WorkOrder build(ColonyCommands.CommandContext context,UUID workId,UUID colonyId,String blueprintId,WorldPosition origin,int rotation) {
        registry.requireOwner(); var colony=registry.colony(colonyId); MemberRank rank=colony.rank(context.actorId());
        if(rank!=MemberRank.OWNER && rank!=MemberRank.MANAGER) throw new SecurityException("Construction requires colony manager");
        if(!colony.available()) throw new IllegalStateException("Colony unavailable");
        var definition=available.get(blueprintId); if(definition==null) throw new IllegalArgumentException("Unknown blueprint");
        var site=new ConstructionSnapshot(workId,colonyId,definition.digest(),origin,rotation,context.actorId(),0,0,0,0,false);
        registry.construction().validateNew(site,definition);
        Layout layout=geometry.layout(workId,colonyId,definition,origin,rotation); geometry.validate(layout);
        if(!colony.territory().contains(layout.workOrigin())) throw new IllegalArgumentException("Construction work origin outside territory");
        for(var target:layout.targets()) if(!colony.territory().contains(target.position())) throw new IllegalArgumentException("Construction blocks outside territory");
        // Reserve all site evidence before publishing work or demanding any physical ownership.
        try(var prepared=registry.construction().prepareNew(site,definition)) {
            registry.targetClaims().propose(layout.claim());
            WorkOrder work;
            try { work=registry.workBoard().createConstruction(workId,colonyId,layout.workOrigin(),0,Lane.NORMAL); }
            catch(RuntimeException failure) { registry.targetClaims().release(workId); throw failure; }
            prepared.commit();
            return work;
        }
    }
    public void advance(UUID workId,boolean consumed) { var site=registry.construction().site(workId); registry.construction().update(ConstructionSite.advance(site,consumed)); }
    public void close(UUID workId) { var site=registry.construction().site(workId); if(site!=null && !site.closed()) registry.construction().update(ConstructionSite.close(site)); registry.targetClaims().release(workId); }
}
