package io.github.kpuctajluk.colonyloom.gameplay.construction;

import io.github.kpuctajluk.colonyloom.core.construction.ConstructionSnapshot;

/** Immutable accepted instance; each advance publishes only after physical state has been checked. */
public final class ConstructionSite {
    private ConstructionSite() {}
    public static ConstructionSnapshot advance(ConstructionSnapshot site,boolean consumed) {
        if(site.closed()) throw new IllegalStateException("Closed construction cannot advance");
        return new ConstructionSnapshot(site.workId(),site.colonyId(),site.blueprintDigest(),site.origin(),site.rotation(),
                site.initiatorId(),site.cursor()+1,site.consumed()+(consumed?1:0),site.claimRevision(),site.revision()+1,false);
    }
    public static ConstructionSnapshot close(ConstructionSnapshot site) {
        return new ConstructionSnapshot(site.workId(),site.colonyId(),site.blueprintDigest(),site.origin(),site.rotation(),
                site.initiatorId(),site.cursor(),site.consumed(),site.claimRevision(),site.revision()+1,true);
    }
}
