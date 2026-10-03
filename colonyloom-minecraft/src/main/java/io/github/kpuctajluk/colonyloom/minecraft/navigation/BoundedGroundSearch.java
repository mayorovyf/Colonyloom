package io.github.kpuctajluk.colonyloom.minecraft.navigation;

import java.util.Arrays;

/** Reusable fixed-capacity A* storage. Coordinates and heap entries never allocate nodes. */
final class BoundedGroundSearch {
    static final int MAX_EXPANSIONS = 256;
    static final int MAX_NODES = 8192;
    static final int MAX_QUERIES = 16;
    private static final int HASH_SIZE = MAX_NODES * 2;
    private static final int[] DX = {1, -1, 0, 0};
    private static final int[] DZ = {0, 0, 1, -1};
    private static final int[] DY = {0, 1, -1};

    interface Terrain {
        boolean standable(int x, int y, int z);
        boolean transition(int fromX, int fromY, int fromZ, int x, int y, int z);
    }
    enum Result { PENDING, FOUND, EXHAUSTED }

    private final int[] xs = new int[MAX_NODES];
    private final int[] ys = new int[MAX_NODES];
    private final int[] zs = new int[MAX_NODES];
    private final int[] parents = new int[MAX_NODES];
    private final int[] costs = new int[MAX_NODES];
    private final int[] estimates = new int[MAX_NODES];
    private final int[] heapPositions = new int[MAX_NODES];
    private final int[] heap = new int[MAX_NODES];
    private final int[] table = new int[HASH_SIZE];
    private int targetX, targetY, targetZ;
    private int size, openSize, found = -1, lastExpansions, openHighWater;
    private boolean nodeLimitHit;

    void begin(int x, int y, int z, int targetX, int targetY, int targetZ) {
        Arrays.fill(table, 0);
        this.targetX = targetX; this.targetY = targetY; this.targetZ = targetZ;
        size = 0; openSize = 0; found = -1; lastExpansions = 0; openHighWater = 0; nodeLimitHit = false;
        add(x, y, z, -1, 0, slot(x, y, z));
    }

    Result advance(Terrain terrain) {
        lastExpansions = 0;
        while (openSize != 0 && lastExpansions < MAX_EXPANSIONS) {
            int current = removeFirst();
            lastExpansions++;
            int fromX = xs[current], fromY = ys[current], fromZ = zs[current];
            if (fromX == targetX && fromY == targetY && fromZ == targetZ) {
                found = current;
                return Result.FOUND;
            }
            for (int direction = 0; direction < DX.length; direction++) {
                int x = fromX + DX[direction], z = fromZ + DZ[direction];
                for (int deltaY : DY) {
                    int y = fromY + deltaY;
                    int slot = slot(x, y, z), known = table[slot] - 1;
                    if (known >= 0 && heapPositions[known] < 0) break;
                    if (!terrain.standable(x, y, z) || !terrain.transition(fromX, fromY, fromZ, x, y, z)) continue;
                    int cost = costs[current] + 1;
                    if (known < 0) {
                        if (size == MAX_NODES) nodeLimitHit = true;
                        else add(x, y, z, current, cost, slot);
                    } else if (heapPositions[known] >= 0 && cost < costs[known]) {
                        costs[known] = cost; parents[known] = current;
                        siftUp(heapPositions[known]);
                    }
                    // One adjacent supported floor per horizontal direction: no gap jumps or ladders.
                    break;
                }
            }
        }
        return openSize == 0 ? Result.EXHAUSTED : Result.PENDING;
    }

    private int slot(int x, int y, int z) {
        int hash = x * 0x8da6b343 ^ y * 0xd8163841 ^ z * 0xcb1ab31f;
        hash ^= hash >>> 16;
        int slot = hash & (HASH_SIZE - 1);
        while (table[slot] != 0) {
            int node = table[slot] - 1;
            if (xs[node] == x && ys[node] == y && zs[node] == z) break;
            slot = (slot + 1) & (HASH_SIZE - 1);
        }
        return slot;
    }

    private void add(int x, int y, int z, int parent, int cost, int slot) {
        int node = size++;
        xs[node] = x; ys[node] = y; zs[node] = z;
        parents[node] = parent; costs[node] = cost;
        estimates[node] = Math.max(Math.abs(x - targetX) + Math.abs(z - targetZ), Math.abs(y - targetY));
        table[slot] = node + 1;
        heapPositions[node] = openSize; heap[openSize++] = node;
        siftUp(heapPositions[node]);
        openHighWater = Math.max(openHighWater, openSize);
    }

    private boolean before(int a, int b) {
        int fa = costs[a] + estimates[a], fb = costs[b] + estimates[b];
        return fa < fb || fa == fb && (estimates[a] < estimates[b] || estimates[a] == estimates[b] && a < b);
    }
    private void siftUp(int position) {
        int node = heap[position];
        while (position > 0) {
            int parent = (position - 1) >>> 1;
            if (!before(node, heap[parent])) break;
            heap[position] = heap[parent]; heapPositions[heap[position]] = position;
            position = parent;
        }
        heap[position] = node; heapPositions[node] = position;
    }
    private int removeFirst() {
        int first = heap[0], last = heap[--openSize];
        if (openSize > 0) {
            int position = 0;
            while (true) {
                int left = position * 2 + 1;
                if (left >= openSize) break;
                int right = left + 1;
                int child = right < openSize && before(heap[right], heap[left]) ? right : left;
                if (!before(heap[child], last)) break;
                heap[position] = heap[child]; heapPositions[heap[position]] = position;
                position = child;
            }
            heap[position] = last; heapPositions[last] = position;
        }
        heapPositions[first] = -1;
        return first;
    }

    int nodeCount() { return size; }
    int openCount() { return openSize; }
    int openHighWater() { return openHighWater; }
    int lastExpansions() { return lastExpansions; }
    boolean nodeLimitHit() { return nodeLimitHit; }
    int found() { return found; }
    int parent(int node) { return parents[node]; }
    int x(int node) { return xs[node]; }
    int y(int node) { return ys[node]; }
    int z(int node) { return zs[node]; }
}
