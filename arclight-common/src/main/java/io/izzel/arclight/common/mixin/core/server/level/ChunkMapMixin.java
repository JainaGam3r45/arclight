package io.izzel.arclight.common.mixin.core.server.level;

import com.mojang.datafixers.DataFixer;
import com.mojang.datafixers.util.Either;
import io.izzel.arclight.common.bridge.core.world.WorldBridge;
import io.izzel.arclight.common.bridge.core.world.server.ChunkMapBridge;
import io.izzel.arclight.common.mod.server.chunk.ChunkWorkloadManager;
import io.izzel.arclight.common.mod.util.ArclightCallbackExecutor;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.progress.ChunkProgressListener;
import net.minecraft.util.thread.BlockableEventLoop;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LightChunkGetter;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.entity.ChunkStatusUpdateListener;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.apache.commons.lang3.mutable.MutableObject;
import org.bukkit.craftbukkit.v.generator.CustomChunkGenerator;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import javax.annotation.Nullable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

@Mixin(ChunkMap.class)
public abstract class ChunkMapMixin implements ChunkMapBridge {

    // @formatter:off
    @Shadow @Nullable protected abstract ChunkHolder getUpdatingChunkIfPresent(long chunkPosIn);
    @Shadow protected abstract Iterable<ChunkHolder> getChunks();
    @Shadow protected abstract void tick();
    @Shadow @Mutable public ChunkGenerator generator;
    @Shadow @Final public ServerLevel level;
    @Shadow @Final @Mutable private RandomState randomState;
    @Shadow public abstract boolean anyPlayerCloseEnoughForSpawning(ChunkPos pos);
    @Shadow private void playerLoadedChunk(ServerPlayer player, MutableObject<ClientboundLevelChunkWithLightPacket> packetCache, LevelChunk chunk) {}
    @Shadow private CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>> scheduleChunkGeneration(ChunkHolder holder, ChunkStatus status) { return null; }
    @Invoker("tick") public abstract void bridge$tick(BooleanSupplier hasMoreTime);
    @Invoker("setViewDistance") public abstract void bridge$setViewDistance(int i);
    // @formatter:on

    @Override
    public void bridge$playerLoadedChunk(ServerPlayer player, MutableObject<ClientboundLevelChunkWithLightPacket> packetCache, LevelChunk chunk) {
        this.playerLoadedChunk(player, packetCache, chunk);
    }

    @Inject(method = "<init>", at = @At("RETURN"))
    private void arclight$updateRandom(ServerLevel p_214836_, LevelStorageSource.LevelStorageAccess p_214837_, DataFixer p_214838_, StructureTemplateManager p_214839_, Executor p_214840_, BlockableEventLoop p_214841_, LightChunkGetter p_214842_, ChunkGenerator p_214843_, ChunkProgressListener p_214844_, ChunkStatusUpdateListener p_214845_, Supplier p_214846_, int p_214847_, boolean p_214848_, CallbackInfo ci) {
        this.bridge$setChunkGenerator(this.generator);
    }

    @Inject(method = "scheduleChunkGeneration", at = @At("HEAD"), cancellable = true)
    private void arclight$paceChunkGeneration(ChunkHolder holder, ChunkStatus status,
                                              CallbackInfoReturnable<CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>>> cir) {
        if (ChunkWorkloadManager.isFlushingSchedules()) {
            return;
        }
        boolean generation = ChunkWorkloadManager.isGenerationStatus(status);
        if (ChunkWorkloadManager.shouldDeferSchedule(holder, status)) {
            long pos = holder.getPos().toLong();
            cir.setReturnValue(ChunkWorkloadManager.deferSchedule(pos, generation,
                () -> this.scheduleChunkGeneration(holder, status)));
            return;
        }
        ChunkWorkloadManager.noteScheduled(generation);
    }

    @Inject(method = "playerLoadedChunk", at = @At("HEAD"), cancellable = true)
    private void arclight$paceChunkSend(ServerPlayer player, MutableObject<ClientboundLevelChunkWithLightPacket> packetCache, LevelChunk chunk, CallbackInfo ci) {
        if (ChunkWorkloadManager.isFlushingSends()) {
            return;
        }
        if (ChunkWorkloadManager.tryConsumeSend()) {
            return;
        }
        ChunkWorkloadManager.deferSend((ChunkMap) (Object) this, player, chunk);
        ci.cancel();
    }

    @Redirect(method = "upgradeChunkTag", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;dimension()Lnet/minecraft/resources/ResourceKey;"))
    private ResourceKey<LevelStem> arclight$useTypeKey(ServerLevel serverWorld) {
        return ((WorldBridge) serverWorld).bridge$getTypeKey();
    }

    public final ArclightCallbackExecutor callbackExecutor = new ArclightCallbackExecutor();

    @Override
    public ArclightCallbackExecutor bridge$getCallbackExecutor() {
        return this.callbackExecutor;
    }

    @Override
    public ChunkHolder bridge$chunkHolderAt(long chunkPos) {
        return getUpdatingChunkIfPresent(chunkPos);
    }

    @Override
    public Iterable<ChunkHolder> bridge$getLoadedChunksIterable() {
        return this.getChunks();
    }

    @Override
    public void bridge$tickEntityTracker() {
        this.tick();
    }

    @Override
    public void bridge$setChunkGenerator(ChunkGenerator generator) {
        this.generator = generator;
        if (generator instanceof CustomChunkGenerator custom) {
            generator = custom.getDelegate();
        }
        if (generator instanceof NoiseBasedChunkGenerator noisebasedchunkgenerator) {
            this.randomState = RandomState.create(noisebasedchunkgenerator.generatorSettings().value(), this.level.registryAccess().lookupOrThrow(Registries.NOISE), this.level.getSeed());
        } else {
            this.randomState = RandomState.create(NoiseGeneratorSettings.dummy(), this.level.registryAccess().lookupOrThrow(Registries.NOISE), this.level.getSeed());
        }
    }

    @Override
    public boolean bridge$anyPlayerCloseEnoughForSpawning(ChunkPos pos, boolean reducedRange) {
        if (!reducedRange) {
            return this.anyPlayerCloseEnoughForSpawning(pos);
        }
        var config = ((WorldBridge) this.level).bridge$spigotConfig();
        int chunkRange = config.mobSpawnRange;
        if (chunkRange > config.viewDistance) {
            chunkRange = config.viewDistance;
        }
        if (chunkRange > 8) {
            chunkRange = 8;
        }
        double blockRange = Math.pow(chunkRange << 4, 2);
        double centerX = pos.getMiddleBlockX();
        double centerZ = pos.getMiddleBlockZ();
        for (ServerPlayer player : this.level.players()) {
            if (player.isSpectator()) {
                continue;
            }
            double dx = centerX - player.getX();
            double dz = centerZ - player.getZ();
            if (dx * dx + dz * dz < blockRange) {
                return true;
            }
        }
        return false;
    }
}
