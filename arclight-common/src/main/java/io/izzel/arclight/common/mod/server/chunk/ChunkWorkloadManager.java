package io.izzel.arclight.common.mod.server.chunk;

import com.mojang.datafixers.util.Either;
import io.izzel.arclight.common.bridge.core.world.server.ChunkMapBridge;
import io.izzel.arclight.i18n.ArclightConfig;
import io.izzel.arclight.i18n.conf.ChunkSpec;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.LevelChunk;
import org.apache.commons.lang3.mutable.MutableObject;

import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Main-thread budget for chunk load / generate / send under MSPT pressure.
 * Never gates {@code pollTask}. Sync {@code getChunk} always flushes deferred work first.
 */
public final class ChunkWorkloadManager {

    private static final int MAX_DEFERRED_SENDS = 512;
    private static final int MAX_DEFERRED_SCHEDULES = 256;

    private static TickBudget budget;
    private static final ArrayDeque<DeferredSend> deferredSends = new ArrayDeque<>();
    private static final ArrayDeque<DeferredSchedule> deferredSchedules = new ArrayDeque<>();
    private static boolean flushingSends;
    private static boolean flushingSchedules;
    private static final ThreadLocal<Integer> BLOCKING = ThreadLocal.withInitial(() -> 0);

    private static int lastGens;
    private static int lastLoads;
    private static int lastSends;
    private static boolean lastSkipGen;
    private static double lastMspt;

    private ChunkWorkloadManager() {
    }

    public static void beginServerTick(MinecraftServer server) {
        // Reset any leaked blocking depth from an exceptional getChunk path.
        BLOCKING.set(0);
        ChunkSpec spec = ArclightConfig.spec().getOptimization().getChunks();
        if (!spec.isEnabled()) {
            budget = null;
            flushAllDeferredSchedules();
            flushDeferredSendsUnlimited();
            lastGens = lastLoads = lastSends = 0;
            lastSkipGen = false;
            lastMspt = server != null ? server.getAverageTickTime() : 0.0D;
            return;
        }
        double mspt = server != null ? server.getAverageTickTime() : 0.0D;
        budget = new TickBudget(spec, mspt);
        lastMspt = mspt;
        lastSkipGen = budget.skipGen;
        flushDeferredSchedulesBudgeted();
        flushDeferredSends();
        lastGens = budget.gens;
        lastLoads = budget.loads;
        lastSends = budget.sends;
    }

    public static void endServerTick() {
        if (budget != null) {
            lastGens = budget.gens;
            lastLoads = budget.loads;
            lastSends = budget.sends;
            lastSkipGen = budget.skipGen;
        }
        budget = null;
    }

    public static void pushBlocking() {
        BLOCKING.set(BLOCKING.get() + 1);
    }

    public static void popBlocking() {
        int depth = BLOCKING.get() - 1;
        BLOCKING.set(Math.max(0, depth));
    }

    public static boolean isBlocking() {
        return BLOCKING.get() > 0;
    }

    public static boolean isFlushingSends() {
        return flushingSends;
    }

    public static boolean isFlushingSchedules() {
        return flushingSchedules;
    }

    /**
     * Sync getChunk path: flush deferred schedule for this pos (or all if needed) before waiting.
     */
    public static void ensureProgress(long chunkPos) {
        flushDeferredFor(chunkPos);
        if (isBlocking() && !deferredSchedules.isEmpty()) {
            // Still waiting and other deferred work may be dependencies — drain all.
            flushAllDeferredSchedules();
        }
    }

    public static boolean shouldDeferSchedule(ChunkHolder holder, ChunkStatus status) {
        if (flushingSchedules || isBlocking() || budget == null) {
            return false;
        }
        // Player-near chunks must progress (anti-Watchdog / anti-empty view).
        FullChunkStatus full = ChunkLevel.fullStatus(holder.getTicketLevel());
        if (full.isOrAfter(FullChunkStatus.ENTITY_TICKING)) {
            return false;
        }
        boolean generation = isGenerationStatus(status);
        if (generation) {
            if (budget.skipGen) {
                return true;
            }
            return budget.gens >= budget.spec.getMaxChunkGensPerTick() || budget.timeExhausted();
        }
        return budget.loads >= budget.spec.getMaxChunkLoadsPerTick() || budget.timeExhausted();
    }

    public static CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>> deferSchedule(
        long chunkPos,
        boolean generation,
        Supplier<CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>>> schedule
    ) {
        CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>> future = new CompletableFuture<>();
        DeferredSchedule deferred = new DeferredSchedule(chunkPos, generation, () -> {
            try {
                schedule.get().whenComplete((result, error) -> {
                    if (error != null) {
                        future.completeExceptionally(error);
                    } else {
                        future.complete(result);
                    }
                });
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        if (deferredSchedules.size() >= MAX_DEFERRED_SCHEDULES) {
            DeferredSchedule dropped = deferredSchedules.pollFirst();
            if (dropped != null) {
                runSchedule(dropped);
            }
        }
        deferredSchedules.addLast(deferred);
        return future;
    }

    public static boolean tryConsumeSend() {
        TickBudget current = budget;
        if (current == null) {
            return true;
        }
        if (current.timeExhausted()) {
            return false;
        }
        if (current.sends >= current.spec.getMaxChunkSendsPerTick()) {
            return false;
        }
        current.sends++;
        return true;
    }

    public static void deferSend(ChunkMap map, ServerPlayer player, LevelChunk chunk) {
        if (deferredSends.size() >= MAX_DEFERRED_SENDS) {
            DeferredSend dropped = deferredSends.pollFirst();
            if (dropped != null) {
                flushingSends = true;
                try {
                    dropped.send();
                } finally {
                    flushingSends = false;
                }
            }
        }
        deferredSends.addLast(new DeferredSend(map, player, chunk));
    }

    public static void noteScheduled(boolean generation) {
        TickBudget current = budget;
        if (current == null) {
            return;
        }
        if (generation) {
            current.gens++;
        } else {
            current.loads++;
        }
    }

    public static Stats snapshot() {
        ChunkSpec spec = ArclightConfig.spec().getOptimization().getChunks();
        return new Stats(
            spec.isEnabled(),
            lastMspt,
            lastSkipGen,
            lastGens,
            lastLoads,
            lastSends,
            deferredSchedules.size(),
            deferredSends.size(),
            spec.getMaxChunkGensPerTick(),
            spec.getMaxChunkLoadsPerTick(),
            spec.getMaxChunkSendsPerTick(),
            spec.getMaxMsPerTick(),
            spec.getSkipGenWhenMsptAbove()
        );
    }

    public static boolean isGenerationStatus(ChunkStatus status) {
        // Heavy worldgen / promotion work (not empty shell, not pure full-chunk bookkeeping alone).
        return status.isOrAfter(ChunkStatus.STRUCTURE_STARTS);
    }

    private static void flushDeferredFor(long chunkPos) {
        if (deferredSchedules.isEmpty()) {
            return;
        }
        Iterator<DeferredSchedule> it = deferredSchedules.iterator();
        while (it.hasNext()) {
            DeferredSchedule next = it.next();
            if (next.chunkPos == chunkPos) {
                it.remove();
                runSchedule(next);
            }
        }
    }

    private static void flushDeferredSchedulesBudgeted() {
        int guard = deferredSchedules.size();
        while (!deferredSchedules.isEmpty() && guard-- > 0) {
            TickBudget current = budget;
            if (current == null) {
                flushAllDeferredSchedules();
                return;
            }
            DeferredSchedule peek = deferredSchedules.peekFirst();
            if (peek == null) {
                break;
            }
            if (peek.generation) {
                if (current.skipGen || current.gens >= current.spec.getMaxChunkGensPerTick() || current.timeExhausted()) {
                    break;
                }
            } else if (current.loads >= current.spec.getMaxChunkLoadsPerTick() || current.timeExhausted()) {
                break;
            }
            DeferredSchedule next = deferredSchedules.pollFirst();
            if (next != null) {
                runSchedule(next);
            }
        }
    }

    private static void flushAllDeferredSchedules() {
        while (!deferredSchedules.isEmpty()) {
            DeferredSchedule next = deferredSchedules.pollFirst();
            if (next != null) {
                runSchedule(next);
            }
        }
    }

    private static void runSchedule(DeferredSchedule deferred) {
        flushingSchedules = true;
        try {
            if (budget != null) {
                if (deferred.generation) {
                    budget.gens++;
                } else {
                    budget.loads++;
                }
            }
            deferred.run.run();
        } finally {
            flushingSchedules = false;
        }
    }

    private static void flushDeferredSends() {
        int guard = deferredSends.size();
        while (!deferredSends.isEmpty() && guard-- > 0) {
            if (!tryConsumeSend()) {
                break;
            }
            DeferredSend next = deferredSends.pollFirst();
            if (next == null) {
                continue;
            }
            flushingSends = true;
            try {
                next.send();
            } finally {
                flushingSends = false;
            }
        }
    }

    private static void flushDeferredSendsUnlimited() {
        while (!deferredSends.isEmpty()) {
            DeferredSend next = deferredSends.pollFirst();
            if (next == null) {
                continue;
            }
            flushingSends = true;
            try {
                next.send();
            } finally {
                flushingSends = false;
            }
        }
    }

    public record Stats(
        boolean enabled,
        double mspt,
        boolean skipGen,
        int gensThisTick,
        int loadsThisTick,
        int sendsThisTick,
        int deferredScheduleQueue,
        int deferredSendQueue,
        int maxGens,
        int maxLoads,
        int maxSends,
        double maxMs,
        double skipGenMspt
    ) {
    }

    private static final class TickBudget {
        private final ChunkSpec spec;
        private final long startNanos;
        private final boolean skipGen;
        private int gens;
        private int loads;
        private int sends;

        private TickBudget(ChunkSpec spec, double mspt) {
            this.spec = spec;
            this.startNanos = System.nanoTime();
            this.skipGen = mspt >= spec.getSkipGenWhenMsptAbove();
        }

        private boolean timeExhausted() {
            return (System.nanoTime() - startNanos) / 1_000_000.0D >= spec.getMaxMsPerTick();
        }
    }

    private static final class DeferredSchedule {
        private final long chunkPos;
        private final boolean generation;
        private final Runnable run;

        private DeferredSchedule(long chunkPos, boolean generation, Runnable run) {
            this.chunkPos = chunkPos;
            this.generation = generation;
            this.run = run;
        }
    }

    private static final class DeferredSend {
        private final ChunkMap map;
        private final ServerPlayer player;
        private final LevelChunk chunk;

        private DeferredSend(ChunkMap map, ServerPlayer player, LevelChunk chunk) {
            this.map = map;
            this.player = player;
            this.chunk = chunk;
        }

        private void send() {
            if (this.player.isRemoved() || this.player.level() != this.chunk.getLevel()) {
                return;
            }
            ((ChunkMapBridge) this.map).bridge$playerLoadedChunk(this.player, new MutableObject<>(), this.chunk);
        }
    }
}
