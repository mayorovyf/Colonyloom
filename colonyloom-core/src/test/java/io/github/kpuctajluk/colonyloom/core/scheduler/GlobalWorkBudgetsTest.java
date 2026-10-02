package io.github.kpuctajluk.colonyloom.core.scheduler;

import io.github.kpuctajluk.colonyloom.core.config.SimulationLimits;
import io.github.kpuctajluk.colonyloom.core.config.SimulationLimits.Budget;
import io.github.kpuctajluk.colonyloom.core.scheduler.AdmissionLedger.Lane;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class GlobalWorkBudgetsTest {
    @Test
    void oneChunkRequestAcrossTicksStillServicesAllThreeClassesInRequiredProportions() {
        GlobalWorkBudgets budgets = new GlobalWorkBudgets(SimulationLimits.development(), () -> 0L);
        int[] consumed = new int[3];
        for (int tick = 1; tick <= 100; tick++) {
            budgets.beginTick(tick);
            Lane selected = budgets.chooseLane(Budget.CHUNK_REQUESTS, true, true, true);
            assertNotNull(selected);
            assertTrue(budgets.tryConsume(Budget.CHUNK_REQUESTS, selected));
            consumed[selected.ordinal()]++;
            assertFalse(budgets.tryConsume(Budget.CHUNK_REQUESTS, selected));
        }
        assertEquals(50, consumed[Lane.CRITICAL.ordinal()]);
        assertEquals(10, consumed[Lane.SERVICE.ordinal()]);
        assertEquals(40, consumed[Lane.NORMAL.ordinal()]);
        assertEquals(100, budgets.totalConsumed(Budget.CHUNK_REQUESTS));
        assertEquals(1, budgets.highWater(Budget.CHUNK_REQUESTS));
    }

    @Test
    void absentClassesLendUnitsAndReturningNormalClassDoesNotStarve() {
        GlobalWorkBudgets budgets = new GlobalWorkBudgets(SimulationLimits.development(), () -> 0L);
        for (int tick = 1; tick <= 100; tick++) {
            budgets.beginTick(tick);
            assertEquals(Lane.SERVICE, budgets.chooseLane(Budget.CHUNK_REQUESTS, false, true, false));
            assertTrue(budgets.tryConsume(Budget.CHUNK_REQUESTS, Lane.SERVICE));
        }
        int normal = 0;
        for (int tick = 101; tick <= 110; tick++) {
            budgets.beginTick(tick);
            Lane selected = budgets.chooseLane(Budget.CHUNK_REQUESTS, true, true, true);
            if (selected == Lane.NORMAL) normal++;
            assertTrue(budgets.tryConsume(Budget.CHUNK_REQUESTS, selected));
        }
        assertEquals(4, normal);
    }

    @Test
    void rejectedWrongLaneDoesNotSpendTokenOrAdvanceFairness() {
        GlobalWorkBudgets budgets = new GlobalWorkBudgets(SimulationLimits.development(), () -> 0L);
        budgets.beginTick(1);
        assertEquals(Lane.CRITICAL, budgets.chooseLane(Budget.CHUNK_REQUESTS, true, true, true));
        assertFalse(budgets.tryConsume(Budget.CHUNK_REQUESTS, Lane.NORMAL));
        assertEquals(0, budgets.used(Budget.CHUNK_REQUESTS));
        assertEquals(Lane.CRITICAL, budgets.chooseLane(Budget.CHUNK_REQUESTS, true, true, true));
        assertTrue(budgets.tryConsume(Budget.CHUNK_REQUESTS, Lane.CRITICAL));
        budgets.beginTick(2);
        assertEquals(Lane.NORMAL, budgets.chooseLane(Budget.CHUNK_REQUESTS, true, true, true));
    }

    @Test
    void timeGuardAppliesToDirtyCursorAndAllOtherCategoriesAtExactBoundary() {
        AtomicLong clock = new AtomicLong(100);
        GlobalWorkBudgets budgets = new GlobalWorkBudgets(SimulationLimits.development().withMaxManagedNanos(10), clock::get);
        assertFalse(budgets.timeAvailable());
        budgets.beginTick(1);
        clock.set(109);
        assertTrue(budgets.tryConsume(Budget.DIRTY_RESCAN_OBJECTS, Lane.SERVICE));
        clock.set(110);
        assertFalse(budgets.timeAvailable());
        assertFalse(budgets.tryConsume(Budget.DIRTY_RESCAN_OBJECTS, Lane.SERVICE));
        assertFalse(budgets.tryConsume(Budget.PHYSICAL_ACTIONS, Lane.CRITICAL));
        assertEquals(1, budgets.used(Budget.DIRTY_RESCAN_OBJECTS));
        budgets.beginTick(2);
        assertTrue(budgets.timeAvailable());
        assertEquals(0, budgets.used(Budget.DIRTY_RESCAN_OBJECTS));
    }

    @Test
    void lowerBudgetImmediatelyStopsFurtherUnitsWithoutErasingAlreadyExecutedCounts() {
        GlobalWorkBudgets budgets = new GlobalWorkBudgets(SimulationLimits.development(), () -> 0L);
        budgets.beginTick(1);
        assertTrue(budgets.tryConsume(Budget.PHYSICAL_ACTIONS, Lane.NORMAL));
        assertTrue(budgets.tryConsume(Budget.PHYSICAL_ACTIONS, Lane.NORMAL));
        budgets.updateLimits(budgets.limits().withBudget(Budget.PHYSICAL_ACTIONS, 1));
        assertFalse(budgets.tryConsume(Budget.PHYSICAL_ACTIONS, Lane.NORMAL));
        assertEquals(2, budgets.used(Budget.PHYSICAL_ACTIONS));
        assertThrows(IllegalArgumentException.class, () -> budgets.beginTick(1));
        budgets.beginTick(2);
        assertTrue(budgets.tryConsume(Budget.PHYSICAL_ACTIONS, Lane.NORMAL));
        assertFalse(budgets.tryConsume(Budget.PHYSICAL_ACTIONS, Lane.NORMAL));
    }
}
