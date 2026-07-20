package io.izzel.arclight.common.mod.server.proxy;

import com.google.common.net.InetAddresses;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import io.izzel.arclight.i18n.ArclightConfig;
import io.izzel.arclight.i18n.conf.VelocitySpec;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.UUID;

/**
 * Velocity modern player-info forwarding (login custom query + HMAC-SHA256).
 */
public final class VelocityModernForwarding {

    public static final ResourceLocation PLAYER_INFO_CHANNEL = new ResourceLocation("velocity", "player_info");
    public static final int MODERN_FORWARDING_DEFAULT = 1;
    public static final int MODERN_FORWARDING_WITH_KEY = 2;
    public static final int MODERN_FORWARDING_WITH_KEY_V2 = 3;
    public static final int MODERN_LAZY_SESSION = 4;
    public static final byte MAX_SUPPORTED_FORWARDING_VERSION = MODERN_LAZY_SESSION;

    private VelocityModernForwarding() {
    }

    public static VelocitySpec config() {
        return ArclightConfig.spec().getProxies().getVelocity();
    }

    public static boolean isEnabled() {
        return config().isEnabled();
    }

    public static FriendlyByteBuf createForwardingRequest() {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        buf.writeByte(MAX_SUPPORTED_FORWARDING_VERSION);
        return buf;
    }

    public static boolean checkIntegrity(FriendlyByteBuf buf, String secret) {
        byte[] signature = new byte[32];
        buf.readBytes(signature);

        byte[] data = new byte[buf.readableBytes()];
        buf.getBytes(buf.readerIndex(), data);

        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] mySignature = mac.doFinal(data);
            return MessageDigest.isEqual(signature, mySignature);
        } catch (InvalidKeyException | NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    public static InetAddress readAddress(FriendlyByteBuf buf) {
        return InetAddresses.forString(buf.readUtf(Short.MAX_VALUE));
    }

    public static GameProfile readProfile(FriendlyByteBuf buf) {
        UUID uuid = buf.readUUID();
        String name = buf.readUtf(16);
        GameProfile profile = new GameProfile(uuid, name);
        Property[] properties = readProperties(buf);
        for (Property property : properties) {
            profile.getProperties().put(property.getName(), property);
        }
        return profile;
    }

    public static Property[] readProperties(FriendlyByteBuf buf) {
        int count = buf.readVarInt();
        Property[] properties = new Property[count];
        for (int i = 0; i < count; i++) {
            String name = buf.readUtf(Short.MAX_VALUE);
            String value = buf.readUtf(Short.MAX_VALUE);
            String signature = buf.readBoolean() ? buf.readUtf(Short.MAX_VALUE) : null;
            properties[i] = new Property(name, value, signature);
        }
        return properties;
    }

    public static void skipKeyData(FriendlyByteBuf buf, int version) {
        if (version == MODERN_FORWARDING_WITH_KEY || version == MODERN_FORWARDING_WITH_KEY_V2) {
            buf.readLong();
            int keyLen = buf.readVarInt();
            buf.skipBytes(keyLen);
            int sigLen = buf.readVarInt();
            buf.skipBytes(sigLen);
            if (version == MODERN_FORWARDING_WITH_KEY_V2) {
                if (buf.readBoolean()) {
                    buf.readUUID();
                }
            }
        }
    }

    public static String describeSupportedVersions() {
        return Arrays.toString(new int[]{
            MODERN_FORWARDING_DEFAULT,
            MODERN_FORWARDING_WITH_KEY,
            MODERN_FORWARDING_WITH_KEY_V2,
            MODERN_LAZY_SESSION
        });
    }
}
