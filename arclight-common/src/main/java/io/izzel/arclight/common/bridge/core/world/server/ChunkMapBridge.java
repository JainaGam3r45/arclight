package io.izzel.arclight.common.bridge.core.world.server;

import io.izzel.arclight.common.mod.util.ArclightCallbackExecutor;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.LevelChunk;
import org.apache.commons.lang3.mutable.MutableObject;

import java.util.function.BooleanSupplier;

public interface ChunkMapBridge {

    void bridge$tick(BooleanSupplier hasMoreTime);

    Iterable<ChunkHolder> bridge$getLoadedChunksIterable();

    void bridge$tickEntityTracker();

    ArclightCallbackExecutor bridge$getCallbackExecutor();

    ChunkHolder bridge$chunkHolderAt(long chunkPos);

    void bridge$setViewDistance(int i);

    void bridge$setChunkGenerator(ChunkGenerator generator);

    /**
     * Spigot-style spawn proximity. When {@code reducedRange} is true, uses {@code mob-spawn-range}.
     */
    boolean bridge$anyPlayerCloseEnoughForSpawning(ChunkPos pos, boolean reducedRange);

    void bridge$playerLoadedChunk(ServerPlayer player, MutableObject<ClientboundLevelChunkWithLightPacket> packetCache, LevelChunk chunk);
}
