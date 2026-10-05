package io.github.kpuctajluk.colonyloom.core.scheduler;

import io.github.kpuctajluk.colonyloom.core.config.SimulationLimits;
import io.github.kpuctajluk.colonyloom.core.config.SimulationLimits.Budget;
import io.github.kpuctajluk.colonyloom.core.scheduler.AdmissionLedger.Lane;
import java.util.Arrays;
import java.util.Objects;
import java.util.function.LongSupplier;

/** Global units plus a time guard, with smooth weighted deficits carried across ticks. */
public final class GlobalWorkBudgets {
    private static final Budget[] BUDGETS = Budget.values();
    private static final Lane[] LANES = Lane.values();
    private static final int[] WEIGHTS = {40, 50, 10}; // Lane order: normal, critical, service.
    private final LongSupplier clock;
    private SimulationLimits limits;
    private final int[] used = new int[BUDGETS.length];
    private final int[][] usedByLane = new int[BUDGETS.length][LANES.length];
    // A service-only cursor is a different contest, not evidence that another
    // consumer's critical/normal candidates became idle. Each mask has bounded debt.
    private final long[][][] deficit = new long[BUDGETS.length][1 << LANES.length][LANES.length];
    private final int[] demandMasks = new int[BUDGETS.length];
    private final int[] selections = new int[BUDGETS.length];
    private final long[] totalConsumed = new long[BUDGETS.length];
    private final long[] rejected = new long[BUDGETS.length];
    private final int[] highWater = new int[BUDGETS.length];
    private long tick = -1;
    private long startedNanos;

    public GlobalWorkBudgets(SimulationLimits limits) { this(limits, System::nanoTime); }
    /** Injectable monotonic clock permits deterministic guard boundary tests. */
    public GlobalWorkBudgets(SimulationLimits limits, LongSupplier clock) {
        this.limits = Objects.requireNonNull(limits);
        this.clock = Objects.requireNonNull(clock);
        Arrays.fill(selections, -1);
    }

    public void updateLimits(SimulationLimits limits) { this.limits = Objects.requireNonNull(limits); }
    public SimulationLimits limits() { return limits; }

    public void beginTick(long monotonicTick) {
        if (monotonicTick < 0 || monotonicTick <= tick) throw new IllegalArgumentException("Tick must advance monotonically");
        tick = monotonicTick;
        startedNanos = clock.getAsLong();
        Arrays.fill(used, 0);
        Arrays.fill(demandMasks, 0);
        Arrays.fill(selections, -1);
        for (int[] laneCounts : usedByLane) Arrays.fill(laneCounts, 0);
    }

    public boolean timeAvailable() {
        return tick >= 0 && clock.getAsLong() - startedNanos < limits.maxManagedNanos();
    }

    /**
     * Declare all currently eligible lanes and select one. Missing lanes lend only CPU,
     * never memory. Selection itself does not spend a token or advance a deficit.
     */
    public Lane chooseLane(Budget budget, boolean critical, boolean service, boolean normal) {
        int index = Objects.requireNonNull(budget).ordinal();
        int mask = (normal ? 1 << Lane.NORMAL.ordinal() : 0)
                | (critical ? 1 << Lane.CRITICAL.ordinal() : 0)
                | (service ? 1 << Lane.SERVICE.ordinal() : 0);
        demandMasks[index] = mask;
        selections[index] = -1;
        if (mask == 0 || used[index] >= limits.budget(budget) || !timeAvailable()) return null;
        int best = -1;
        long bestCredit = Long.MIN_VALUE;
        for (Lane lane : LANES) {
            int laneIndex = lane.ordinal();
            if ((mask & (1 << laneIndex)) == 0) continue;
            long credit = deficit[index][mask][laneIndex] + WEIGHTS[laneIndex];
            if (credit > bestCredit) {
                bestCredit = credit;
                best = laneIndex;
            }
        }
        selections[index] = best;
        return LANES[best];
    }

    /** Consume one real unit. Contested callers must select using chooseLane first. */
    public boolean tryConsume(Budget budget, Lane lane) {
        int index = Objects.requireNonNull(budget).ordinal();
        int laneIndex = Objects.requireNonNull(lane).ordinal();
        if (used[index] >= limits.budget(budget) || !timeAvailable()
                || (demandMasks[index] != 0 && selections[index] != laneIndex)) {
            rejected[index]++;
            return false;
        }
        int mask = demandMasks[index] == 0 ? 1 << laneIndex : demandMasks[index];
        int activeWeight = 0;
        for (Lane activeLane : LANES) {
            int active = activeLane.ordinal();
            if ((mask & (1 << active)) != 0) {
                deficit[index][mask][active] += WEIGHTS[active];
                activeWeight += WEIGHTS[active];
            }
        }
        deficit[index][mask][laneIndex] -= activeWeight;
        used[index]++;
        usedByLane[index][laneIndex]++;
        totalConsumed[index]++;
        highWater[index] = Math.max(highWater[index], used[index]);
        demandMasks[index] = 0;
        selections[index] = -1;
        return true;
    }

    public long tick() { return tick; }
    public int used(Budget budget) { return used[budget.ordinal()]; }
    public int used(Budget budget, Lane lane) { return usedByLane[budget.ordinal()][lane.ordinal()]; }
    public long totalConsumed(Budget budget) { return totalConsumed[budget.ordinal()]; }
    public long rejected(Budget budget) { return rejected[budget.ordinal()]; }
    public int highWater(Budget budget) { return highWater[budget.ordinal()]; }
}
