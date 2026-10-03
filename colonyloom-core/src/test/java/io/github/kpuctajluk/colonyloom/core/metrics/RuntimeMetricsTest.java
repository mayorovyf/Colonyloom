package io.github.kpuctajluk.colonyloom.core.metrics;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.kpuctajluk.colonyloom.core.metrics.RuntimeMetrics.Timer.MSPT;

final class RuntimeMetricsTest {
    @Test void offThreadRecordingCannotMutateOwnerHistogram() throws InterruptedException {
        var metrics=new RuntimeMetrics();
        var rejected=new java.util.concurrent.atomic.AtomicReference<Throwable>();
        Thread other=new Thread(()-> { try { metrics.record(MSPT,10); } catch(Throwable failure) { rejected.set(failure); } });
        other.start(); other.join();
        assertInstanceOf(IllegalStateException.class,rejected.get());
        assertEquals(0,metrics.snapshot().get("MSPT").count());
        metrics.record(MSPT,10); assertEquals(10,metrics.snapshot().get("MSPT").p99Nanos());
    }
    @Test void percentilesUseNearestRankAndConservativeBounds() {
        var metrics=new RuntimeMetrics();
        for(int value=1;value<=1000;value++) metrics.record(MSPT,value);
        var sample=metrics.snapshot().get("MSPT");
        assertEquals(1000,sample.count()); assertEquals(500500,sample.totalNanos()); assertEquals(1000,sample.maxNanos());
        assertEquals(503,sample.p50Nanos()); assertEquals(959,sample.p95Nanos());
        assertEquals(991,sample.p99Nanos()); assertEquals(1007,sample.p999Nanos());
    }
    @Test void everyPowerOfTwoBoundaryAndMaximumRemainConservative() {
        var metrics=new RuntimeMetrics();
        for(int exponent=0;exponent<=62;exponent++) {
            long power=1L<<exponent;
            for(long value:new long[]{power-1,power, power==1L<<62 ? Long.MAX_VALUE : power+1}) {
                metrics.reset(); metrics.record(MSPT,value);
                var sample=metrics.snapshot().get("MSPT");
                assertTrue(sample.p99Nanos()>=value,"upper bound "+value);
                assertTrue(sample.p99Nanos()-value<=Math.max(1,value/32),"resolution "+value);
                assertEquals(value,sample.maxNanos());
            }
        }
    }
    @Test void invalidDurationsDoNotContaminateTotalsAndOverflowSaturates() {
        var metrics=new RuntimeMetrics();
        assertThrows(IllegalArgumentException.class,()->metrics.record(MSPT,-1));
        metrics.record(MSPT,Long.MAX_VALUE); metrics.record(MSPT,Long.MAX_VALUE);
        var sample=metrics.snapshot().get("MSPT");
        assertEquals(2,sample.count()); assertEquals(Long.MAX_VALUE,sample.totalNanos());
        assertEquals(Long.MAX_VALUE,sample.p50Nanos()); assertEquals(Long.MAX_VALUE,sample.p999Nanos());
        metrics.reset();
        assertEquals(new RuntimeMetrics.Sample(0,0,0,0,0,0,0),metrics.snapshot().get("MSPT"));
    }
}
