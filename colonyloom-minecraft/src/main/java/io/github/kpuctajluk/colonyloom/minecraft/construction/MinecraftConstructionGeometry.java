package io.github.kpuctajluk.colonyloom.minecraft.construction;

import io.github.kpuctajluk.colonyloom.core.colony.WorldPosition;
import io.github.kpuctajluk.colonyloom.core.content.BlockDescriptor;
import io.github.kpuctajluk.colonyloom.core.content.BlockOffset;
import io.github.kpuctajluk.colonyloom.core.content.BlueprintDefinition;
import io.github.kpuctajluk.colonyloom.core.spatial.TargetClaimRegistry;
import io.github.kpuctajluk.colonyloom.gameplay.construction.ConstructionController;
import java.util.AbstractList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.RandomAccess;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

/** Uses Minecraft's own coordinate/state transforms, without bulk placement or world loading. */
public final class MinecraftConstructionGeometry implements ConstructionController.Geometry {
    private final MinecraftServer server;
    public MinecraftConstructionGeometry(MinecraftServer server) { this.server=server; }
    public ConstructionController.Layout layout(UUID workId,UUID colonyId,BlueprintDefinition definition,WorldPosition origin,int degrees) {
        Rotation rotation=switch(degrees) { case 0 -> Rotation.NONE; case 90 -> Rotation.CLOCKWISE_90; case 180 -> Rotation.CLOCKWISE_180; case 270 -> Rotation.COUNTERCLOCKWISE_90; default -> throw new IllegalArgumentException("Rotation must be 0/90/180/270"); };
        var bounds=definition.bounds();
        var first=position(origin,new BlockOffset(bounds.minX(),bounds.minY(),bounds.minZ()),rotation);
        var last=position(origin,new BlockOffset(bounds.maxX(),bounds.maxY(),bounds.maxZ()),rotation);
        var claim=new TargetClaimRegistry.Snapshot(workId,colonyId,null,origin.dimension(),
                Math.min(first.x(),last.x()),Math.min(first.y(),last.y()),Math.min(first.z(),last.z()),
                Math.max(first.x(),last.x()),Math.max(first.y(),last.y()),Math.max(first.z(),last.z()),0);
        var marker=definition.markers().get("work_origin"); if(marker==null) throw new IllegalArgumentException("Blueprint lacks work_origin");
        var buffer=definition.markers().get("delivery_buffer"); if(buffer==null) throw new IllegalArgumentException("Blueprint lacks delivery_buffer");
        // The accepted palette is capped at 512, independently of the 65,536 indexed targets.
        Map<BlockDescriptor,BlockDescriptor> palette=new HashMap<>();
        for(var block:definition.palette()) palette.put(block,descriptor(state(block).rotate(rotation),block.itemId()));
        return new ConstructionController.Layout(Collections.unmodifiableList(new Targets(definition,origin,rotation,Map.copyOf(palette))),
                position(origin,marker,rotation),position(origin,buffer,rotation),claim);
    }
    private static final class Targets extends AbstractList<ConstructionController.Target> implements RandomAccess {
        private final BlueprintDefinition definition;
        private final WorldPosition origin;
        private final Rotation rotation;
        private final Map<BlockDescriptor,BlockDescriptor> palette;
        Targets(BlueprintDefinition definition,WorldPosition origin,Rotation rotation,Map<BlockDescriptor,BlockDescriptor> palette) {
            this.definition=definition; this.origin=origin; this.rotation=rotation; this.palette=palette;
        }
        @Override public int size() { return definition.blocks().size(); }
        @Override public ConstructionController.Target get(int index) {
            var block=definition.blocks().get(index);
            return new ConstructionController.Target(position(origin,block.offset(),rotation),palette.get(block.block()));
        }
    }
    private static WorldPosition position(WorldPosition origin,BlockOffset offset,Rotation rotation) {
        BlockPos transformed=StructureTemplate.transform(new BlockPos(offset.x(),offset.y(),offset.z()),Mirror.NONE,rotation,BlockPos.ZERO);
        return new WorldPosition(origin.dimension(),Math.addExact(origin.x(),transformed.getX()),Math.addExact(origin.y(),transformed.getY()),Math.addExact(origin.z(),transformed.getZ()));
    }
    public void validate(ConstructionController.Layout layout) {
        if(!server.isSameThread()) throw new IllegalStateException("Construction validation requires server thread");
        var origin=layout.workOrigin(); var level=server.getLevel(ResourceKey.create(Registries.DIMENSION,ResourceLocation.parse(origin.dimension())));
        if(level==null) throw new IllegalArgumentException("Unknown construction dimension");
        var claim=layout.claim();
        validatePosition(level,new BlockPos(claim.minX(),claim.minY(),claim.minZ()));
        validatePosition(level,new BlockPos(claim.maxX(),claim.maxY(),claim.maxZ()));
        validatePosition(level,new BlockPos(origin.x(),origin.y(),origin.z()));
        var buffer=layout.deliveryBuffer(); validatePosition(level,new BlockPos(buffer.x(),buffer.y(),buffer.z()));
    }
    private static void validatePosition(net.minecraft.server.level.ServerLevel level,BlockPos position) {
        // Border and build height are axis-aligned: exact extrema prove every occupied target.
        // No block reads, chunk demands, or chunk loads are needed for admission.
        if(level.isOutsideBuildHeight(position) || !level.getWorldBorder().isWithinBounds(position))
            throw new IllegalArgumentException("Construction target or marker outside world");
    }
    public static BlockState state(BlockDescriptor descriptor) {
        return io.github.kpuctajluk.colonyloom.minecraft.content.ContentLoader.decodeBlockState(descriptor);
    }
    private static BlockDescriptor descriptor(BlockState state,String item) {
        Map<String,String> values=new HashMap<>(); for(var entry:state.getValues().entrySet()) values.put(entry.getKey().getName(),name(entry.getKey(),entry.getValue()));
        return new BlockDescriptor(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString(),values,item);
    }
    @SuppressWarnings({"rawtypes","unchecked"}) private static String name(Property property,Comparable value) { return property.getName(value); }
}
