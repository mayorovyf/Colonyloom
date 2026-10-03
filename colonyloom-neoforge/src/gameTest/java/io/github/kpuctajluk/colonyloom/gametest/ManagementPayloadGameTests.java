package io.github.kpuctajluk.colonyloom.gametest;

import io.github.kpuctajluk.colonyloom.minecraft.network.ManagementPayloads;
import io.netty.buffer.Unpooled;
import java.util.UUID;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("colonyloom")
@PrefixGameTestTemplate(false)
public final class ManagementPayloadGameTests {
    @GameTest(template="identity_empty")
    public static void oversizedUtf8TextIsRejectedBeforeHandshake(GameTestHelper helper) {
        var buffer=buffer(helper);
        try {
            buffer.writeUtf("1");buffer.writeUtf("я".repeat(129));
            reject(helper,() -> ManagementPayloads.Hello.CODEC.decode(buffer),"258-byte UTF-8 build accepted");
        } finally {buffer.release();}
        helper.succeed();
    }

    @GameTest(template="identity_empty")
    public static void oversizedCommandAndTrailingBytesNeverDecode(GameTestHelper helper) {
        var buffer=buffer(helper);
        try {
            buffer.writeZero(8193);
            reject(helper,() -> ManagementPayloads.CommandPayload.CODEC.decode(buffer),"Oversized command decoded");
            buffer.clear();
            buffer.writeUUID(UUID.randomUUID());buffer.writeLong(0);buffer.writeBoolean(false);buffer.writeLong(0);
            buffer.writeUtf("colonyloom:create_colony");buffer.writeUtf("Fixture");buffer.writeUtf("minecraft:overworld");
            buffer.writeInt(0);buffer.writeInt(0);buffer.writeInt(31);buffer.writeInt(31);buffer.writeByte(1);
            reject(helper,() -> ManagementPayloads.CommandPayload.CODEC.decode(buffer),"Trailing command bytes accepted");
        } finally {buffer.release();}
        helper.succeed();
    }

    @GameTest(template="identity_empty")
    public static void excessiveRowsAndUnknownViewTypeCannotAllocatePage(GameTestHelper helper) {
        var buffer=buffer(helper);
        try {
            buffer.writeUUID(UUID.randomUUID());buffer.writeUUID(UUID.randomUUID());buffer.writeUtf("Fixture");buffer.writeUtf("viewer");
            buffer.writeLong(1);buffer.writeLong(1);buffer.writeByte(0);buffer.writeInt(0);buffer.writeInt(51);buffer.writeVarInt(51);
            reject(helper,() -> ManagementPayloads.ViewSnapshot.CODEC.decode(buffer),"51-row page accepted");
            buffer.clear();
            buffer.writeUUID(UUID.randomUUID());buffer.writeUUID(UUID.randomUUID());buffer.writeUUID(UUID.randomUUID());buffer.writeByte(255);buffer.writeInt(0);buffer.writeBoolean(false);
            reject(helper,() -> ManagementPayloads.Subscribe.CODEC.decode(buffer),"Unknown view enum accepted");
        } finally {buffer.release();}
        helper.succeed();
    }

    private static RegistryFriendlyByteBuf buffer(GameTestHelper helper) {
        return new RegistryFriendlyByteBuf(Unpooled.buffer(),helper.getLevel().registryAccess());
    }
    private static void reject(GameTestHelper helper,Runnable decode,String message) {
        try {decode.run();} catch(IllegalArgumentException expected) {return;}
        helper.fail(message);
    }
}
