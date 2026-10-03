package io.github.kpuctajluk.colonyloom.minecraft.metrics;

import io.github.kpuctajluk.colonyloom.core.config.SimulationLimits.Resource;
import io.github.kpuctajluk.colonyloom.core.metrics.RuntimeMetrics;
import io.github.kpuctajluk.colonyloom.minecraft.runtime.MinecraftServerRuntime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Explicit diagnostic boundary. Colony-scoped views never include another colony or shared timers. */
public final class MinecraftMetrics {
    private final MinecraftServerRuntime runtime;
    public MinecraftMetrics(MinecraftServerRuntime runtime) { this.runtime=Objects.requireNonNull(runtime); }
    public Map<String,Object> snapshot() { return snapshot(null); }
    public Map<String,Object> snapshot(UUID colony) {
        var core=runtime.core();
        if(colony!=null) core.registry().colony(colony);
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("scope",colony==null ? "server" : colony.toString());
        result.put("serverTick",core.serverTick());
        result.put("scheduler",core.scheduler().diagnostics(colony));
        if(runtime.chunks()!=null) result.put("chunks",runtime.chunks().diagnostics(colony));
        if(colony==null) {
            result.put("timers",runtime.metrics().snapshot());
            result.put("histogramResolution",RuntimeMetrics.resolution());
            Map<String,Object> resources=new LinkedHashMap<>();
            var admission=core.admission();
            for(Resource resource:Resource.values()) {
                Map<String,Object> data=new LinkedHashMap<>();
                data.put("used",admission.used(resource)); data.put("highWater",admission.highWater(resource));
                data.put("rejected",admission.rejected(resource)); data.put("cap",admission.limits().resource(resource));
                data.put("overLimit",admission.overLimit(resource));
                for(var lane:io.github.kpuctajluk.colonyloom.core.scheduler.AdmissionLedger.Lane.values())
                    data.put(lane.name(),Map.of("used",admission.used(resource,lane),"cap",admission.laneCapacity(resource,lane)));
                resources.put(resource.name(),data);
            }
            result.put("admission",resources);
            result.put("criticalCapacityViolations",admission.criticalCapacityViolations());
            result.put("normalAdmissionBlocked",admission.normalAdmissionBlocked());
            result.put("pins",core.registry().construction().pinDiagnostics());
            result.put("fairnessScope","serviceCount is actual timer progression or physical-executor invocation (which may enter a resource wait), not proof of completion. maxServiceGap includes idle intervals. Per-work ready delay and continuously-ready colony dispatch delay are separate; the200tick barrier applies to colony dispatch. Diagnostics scan bounded admitted state only at report/command boundaries.");
            result.put("evidenceRecords",core.registry().effects().size());
            result.put("constructionSites",core.registry().construction().size());
            if(runtime.navigation()!=null) result.put("navigation",runtime.navigation().diagnostics());
            result.put("navigationBackend",runtime.navigationBackendMetrics());
            result.put("timingScopes",timingScopes());
            result.put("futureMetrics","graph/native storage timings implemented; counts remain experimental pending full profile calibration. Views not implemented. Supported pending-production migration preserves its original checkpoint. No JVM allocation profiler attached. No separable broad vanilla world block-change timer.");
        }
        Map<String,Long> citizens=new LinkedHashMap<>();
        for(var citizen:core.registry().citizensView()) {
            if(colony!=null && !colony.equals(citizen.colonyId())) continue;
            citizens.merge("lifecycle."+citizen.lifecycle().name(),1L,Long::sum);
            citizens.merge("readiness."+citizen.readiness().name(),1L,Long::sum);
            citizens.merge("admission."+citizen.admission().name(),1L,Long::sum);
            if(citizen.needs().get("food")<=6)citizens.merge("hunger.lowFood",1L,Long::sum);
            if(citizen.needs().get("food")==0)citizens.merge("hunger.starved",1L,Long::sum);
            if(runtime.needs()!=null)citizens.merge("hunger.reason."+runtime.needs().reason(citizen.citizenId()).name(),1L,Long::sum);
        }
        result.put("citizens",citizens);
        return Map.copyOf(result);
    }
    private static Map<String,String> timingScopes() {
        Map<String,String> scopes=new LinkedHashMap<>();
        scopes.put("MSPT","ServerTickEvent.Pre HIGHEST to Post LOWEST wall time including vanilla tick and composed post simulation, excluding inter-tick sleep. Platform fixture property disables production recorder; test fixture alone records after its actual actions. Production boundary excludes later equal-priority listeners.");
        scopes.put("MANAGED_TICK","scheduler.tick including beforeWork chunk/citizen/navigation/claim service and scheduler work");
        scopes.put("ASSIGNMENT_UNIT","one admitted candidate lookup and eligibility/distance comparison; not all candidate selection");
        scopes.put("NAVIGATION_UNIT","one backend portion, at most 256 A* expansions; inclusive external checks, not whole query or navigation tick");
        scopes.put("NAVIGATION_EXTERNAL","one bounded backend.search portion including collision/support checks and final bounded route construction; exact noninterruptible max separately visible");
        scopes.put("BLUEPRINT_UNIT","one spatial claim comparison portion or one construction target matches call");
        scopes.put("PHYSICAL_UNIT","one native placement, transfer, craft or food executor call, inclusive validation and observed aftermath");
        scopes.put("CHUNK_UNIT","one ticket acquire call; same scope as CHUNK_EXTERNAL because ticket API exposes no separable internal portion");
        scopes.put("DIRTY_RESCAN_UNIT","one scheduler dirty root rescan or one citizen-admission cursor portion");
        scopes.put("GRAPH_UNIT","one admitted lazy DFS expansion including at most16 indexed candidate checks; not entire root planning or kit admission");
        scopes.put("STORAGE_EXTERNAL","one real StorageService.read including authority, native topology and exact item component checks; not candidate search or whole reconciliation sweep");
        scopes.put("ENTITY_TICK","CitizenEntity.tick including vanilla AI, movement, collision and managed movement guard; nested timers are not additive");
        scopes.put("MOVEMENT","CitizenEntity.move entire vanilla displacement/collision-resolution call; inclusive physical movement, not pure controller CPU");
        scopes.put("NAVIGATION_POLL","one backend.poll safety/progress check and controller tick; inclusive native COLLISION checks");
        scopes.put("COLLISION","navigation backend native Level.noCollision prospective body check; entity.move collision resolution is included in MOVEMENT and cannot be separated by vanilla hooks");
        scopes.put("DAMAGE","CitizenEntity.hurt actual call including rejected damage, death/event side effects; no autonomous combat simulation");
        scopes.put("BLOCK_CHANGE","permitted construction interaction whole call including permissions/events/item use/neighbour updates and thrown-call aftermath; not all external player world changes");
        scopes.put("BLOCK_CHANGE_EXTERNAL","same inclusive permitted interaction scope; cannot isolate vanilla neighbour update cost");
        scopes.put("SAVE","explicit durable DTO save/checkpoint incl saveEverything, async IO flush and verified marker; autosave encode separately SAVE_ENCODE");
        scopes.put("SAVE_ENCODE","SavedData.save DTO capture/encode; does not claim disk durability or full vanilla autosave timing");
        scopes.put("LOAD","bounded preflight, registry restore/session reconciliation and SavedData attachment; not complete world startup");
        return Map.copyOf(scopes);
    }
}
