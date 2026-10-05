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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Gameplay rules own sites; platform transforms and physical execution stay behind this consumer port. */
public final class ConstructionController implements ConstructionCommands {
    public record Target(WorldPosition position,BlockDescriptor expected) {
        public Target { Objects.requireNonNull(position); Objects.requireNonNull(expected); }
    }
    /** Targets are an immutable indexed view over the pinned definition, never a copied full layout. */
    public record Layout(List<Target> targets,WorldPosition workOrigin,WorldPosition deliveryBuffer,TargetClaimRegistry.Snapshot claim) {
        public Layout { Objects.requireNonNull(targets); Objects.requireNonNull(workOrigin); Objects.requireNonNull(deliveryBuffer); Objects.requireNonNull(claim); if(targets.isEmpty()) throw new IllegalArgumentException("Empty construction"); }
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
        if(!colony.territory().contains(layout.deliveryBuffer())) throw new IllegalArgumentException("Pinned delivery buffer outside territory");
        validateTerritory(colony.territory(),layout);
        var stored=registry.targetClaims().snapshots().stream().filter(claim -> claim.ownerId().equals(site.workId())).findFirst().orElse(null);
        if(!site.closed() && (stored==null || !stored.equals(layout.claim()))) throw new IllegalArgumentException("Pinned geometry differs from authoritative target claim");
        return layout;
    }
    public WorkOrder build(ColonyCommands.CommandContext context,UUID workId,UUID colonyId,String blueprintId,WorldPosition origin,int rotation) {
        registry.requireOwner(); var colony=registry.colony(colonyId); MemberRank rank=colony.rank(context.actorId());
        if(rank!=MemberRank.OWNER && rank!=MemberRank.MANAGER) throw new SecurityException("Construction requires colony manager");
        if(!colony.available()) throw new IllegalStateException("Colony unavailable");
        var availableDefinition=available.get(blueprintId); if(availableDefinition==null) throw new IllegalArgumentException("Unknown blueprint");
        // Reuse the authoritative pin after content reload; avoid comparing all blocks on each build.
        var definition=registry.construction().definitions().stream().filter(pin -> pin.digest().equals(availableDefinition.digest()))
                .findFirst().orElse(availableDefinition);
        var site=new ConstructionSnapshot(workId,colonyId,definition.digest(),origin,rotation,context.actorId(),0,0,0,0,false);
        registry.construction().validateNew(site,definition);
        Layout layout=geometry.layout(workId,colonyId,definition,origin,rotation); geometry.validate(layout);
        if(!colony.territory().contains(layout.workOrigin())) throw new IllegalArgumentException("Construction work origin outside territory");
        if(!colony.territory().contains(layout.deliveryBuffer())) throw new IllegalArgumentException("Construction delivery buffer outside territory");
        validateTerritory(colony.territory(),layout);
        if(registry.storage().registrations(colonyId).stream().noneMatch(value -> value.role().equals("construction")
                && value.address().equals(layout.deliveryBuffer()) && value.storages().stream().allMatch(id -> id.bindingEpoch()==0)))
            throw new IllegalArgumentException("Register the construction barrel at the transformed delivery_buffer marker before building");
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
    private static void validateTerritory(io.github.kpuctajluk.colonyloom.core.colony.Territory territory,Layout layout) {
        var claim=layout.claim();
        if(!territory.dimension().equals(claim.dimension()) || claim.minX()<territory.minX() || claim.maxX()>territory.maxX()
                || claim.minZ()<territory.minZ() || claim.maxZ()>territory.maxZ())
            throw new IllegalArgumentException("Construction blocks outside territory");
    }
    public void advance(UUID workId,boolean consumed) { var site=registry.construction().site(workId); registry.construction().update(ConstructionSite.advance(site,consumed)); }
    public void revisit(UUID workId,int cursor) {
        var site=registry.construction().site(workId);
        if(cursor<0||cursor>=site.cursor())throw new IllegalArgumentException("Invalid construction revisit");
        registry.construction().update(new ConstructionSnapshot(site.workId(),site.colonyId(),site.blueprintDigest(),site.origin(),site.rotation(),site.initiatorId(),cursor,site.consumed(),site.claimRevision(),Math.addExact(site.revision(),1),false));
    }
    public void close(UUID workId) { var site=registry.construction().site(workId); if(site!=null && !site.closed()) registry.construction().update(ConstructionSite.close(site)); registry.targetClaims().release(workId); }
}
