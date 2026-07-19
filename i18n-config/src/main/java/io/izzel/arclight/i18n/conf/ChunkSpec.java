package io.izzel.arclight.i18n.conf;

import ninja.leaping.configurate.objectmapping.Setting;
import ninja.leaping.configurate.objectmapping.serialize.ConfigSerializable;

@ConfigSerializable
public class ChunkSpec {

    @Setting("enabled")
    private boolean enabled = true;

    @Setting("skip-gen-when-mspt-above")
    private double skipGenWhenMsptAbove = 45.0D;

    @Setting("max-chunk-loads-per-tick")
    private int maxChunkLoadsPerTick = 24;

    @Setting("max-chunk-gens-per-tick")
    private int maxChunkGensPerTick = 4;

    @Setting("max-chunk-sends-per-tick")
    private int maxChunkSendsPerTick = 48;

    @Setting("max-ms-per-tick")
    private double maxMsPerTick = 4.0D;

    public boolean isEnabled() {
        return enabled;
    }

    public double getSkipGenWhenMsptAbove() {
        return skipGenWhenMsptAbove;
    }

    public int getMaxChunkLoadsPerTick() {
        return maxChunkLoadsPerTick;
    }

    public int getMaxChunkGensPerTick() {
        return maxChunkGensPerTick;
    }

    public int getMaxChunkSendsPerTick() {
        return maxChunkSendsPerTick;
    }

    public double getMaxMsPerTick() {
        return maxMsPerTick;
    }
}
