package io.github.kpuctajluk.colonyloom.core.config;

import io.github.kpuctajluk.colonyloom.core.config.SimulationLimits.Budget;
import io.github.kpuctajluk.colonyloom.core.config.SimulationLimits.Resource;
import io.github.kpuctajluk.colonyloom.core.scheduler.AdmissionLedger;
import io.github.kpuctajluk.colonyloom.core.scheduler.AdmissionLedger.Lane;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class SimulationLimitsTest {
    @Test
    void callerMutationCannotChangeValidatedAdmissionCapacity() {
        SimulationLimits defaults = SimulationLimits.development();
        EnumMap<Resource, Integer> input = new EnumMap<>(defaults.resources());
        input.put(Resource.CITIZENS, 1);
        SimulationLimits snapshot = new SimulationLimits(input, defaults.budgets(), defaults.maxManagedNanos());
        AdmissionLedger ledger = new AdmissionLedger(snapshot, () -> {});
        input.put(Resource.CITIZENS, 300);
        assertThrows(UnsupportedOperationException.class, () -> snapshot.resources().put(Resource.CITIZENS, 300));
        try (var citizen = ledger.reserve(new UUID(0, 1), Lane.NORMAL, Map.of(Resource.CITIZENS, 1))) {
            assertThrows(AdmissionLedger.AdmissionException.class,
                    () -> ledger.reserve(new UUID(0, 2), Lane.NORMAL, Map.of(Resource.CITIZENS, 1)));
        }
    }

    @Test
    void incompleteOrNonpositiveSnapshotsAreRejectedBeforeTheyCanReplaceValidLimits() {
        SimulationLimits original = SimulationLimits.development();
        EnumMap<Resource, Integer> missing = new EnumMap<>(original.resources());
        missing.remove(Resource.EVIDENCE);
        assertThrows(IllegalArgumentException.class,
                () -> new SimulationLimits(missing, original.budgets(), original.maxManagedNanos()));
        assertThrows(IllegalArgumentException.class, () -> original.withResource(Resource.CITIZENS, 0));
        assertThrows(IllegalArgumentException.class, () -> original.withBudget(Budget.CHUNK_REQUESTS, -1));
        assertThrows(IllegalArgumentException.class, () -> original.withMaxManagedNanos(0));
        AdmissionLedger ledger = new AdmissionLedger(original, () -> {});
        try (var accepted = ledger.reserve(new UUID(0, 1), Lane.NORMAL, Map.of(Resource.CITIZENS, 30))) {
            assertThrows(AdmissionLedger.AdmissionException.class,
                    () -> ledger.reserve(new UUID(0, 2), Lane.NORMAL, Map.of(Resource.CITIZENS, 1)));
        }
    }
}
