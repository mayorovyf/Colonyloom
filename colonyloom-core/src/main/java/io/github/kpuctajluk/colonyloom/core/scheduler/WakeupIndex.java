package io.github.kpuctajluk.colonyloom.core.scheduler;

import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/** Indexed min-heap: one retained ticket per admitted root, never stale duplicate deadlines. */
public final class WakeupIndex {
    public static final class Ticket {
        private final UUID workId;
        private long dueTick;
        private int index = -1;
        public Ticket(UUID workId) { this.workId = Objects.requireNonNull(workId); }
        public UUID workId() { return workId; }
        public long dueTick() { return dueTick; }
        public boolean scheduled() { return index >= 0; }
    }
    private Ticket[] heap = new Ticket[16];
    public void ensureCapacity(int capacity) { if (capacity > heap.length) heap = Arrays.copyOf(heap,Math.max(capacity,Math.multiplyExact(heap.length,2))); }
    private int size;
    public int size() { return size; }
    public void schedule(Ticket ticket, long dueTick) {
        if (dueTick < 0) throw new IllegalArgumentException("Negative deadline");
        remove(ticket); ticket.dueTick = dueTick;
        if (size == heap.length) heap = Arrays.copyOf(heap, Math.multiplyExact(heap.length,2));
        ticket.index = size; heap[size++] = ticket; up(ticket.index);
    }
    public Ticket peek() { return size == 0 ? null : heap[0]; }
    public Ticket pollDue(long tick) { Ticket ticket = peek(); if (ticket == null || ticket.dueTick > tick) return null; remove(ticket); return ticket; }
    public void remove(Ticket ticket) {
        int index = ticket.index; if (index < 0) return;
        if (index >= size || heap[index] != ticket) throw new IllegalStateException("Ticket belongs to another index");
        Ticket last = heap[--size]; heap[size] = null; ticket.index = -1;
        if (index < size) { heap[index] = last; last.index = index; if (index > 0 && compare(last,heap[(index-1)/2]) < 0) up(index); else down(index); }
    }
    public void clear() { while (size > 0) { Ticket ticket = heap[--size]; heap[size] = null; ticket.index = -1; } }
    private static int compare(Ticket a, Ticket b) { int result = Long.compare(a.dueTick,b.dueTick); return result != 0 ? result : a.workId.compareTo(b.workId); }
    private void up(int index) { Ticket ticket = heap[index]; while (index > 0) { int parent = (index-1)/2; if (compare(ticket,heap[parent]) >= 0) break; heap[index] = heap[parent]; heap[index].index = index; index = parent; } heap[index] = ticket; ticket.index = index; }
    private void down(int index) { Ticket ticket = heap[index]; while (index*2+1 < size) { int child = index*2+1; if (child+1 < size && compare(heap[child+1],heap[child]) < 0) child++; if (compare(ticket,heap[child]) <= 0) break; heap[index] = heap[child]; heap[index].index = index; index = child; } heap[index] = ticket; ticket.index = index; }
}
