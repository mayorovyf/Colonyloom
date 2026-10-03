package io.github.kpuctajluk.colonyloom.minecraft.construction;

import io.github.kpuctajluk.colonyloom.core.colony.WorldPosition;
import io.github.kpuctajluk.colonyloom.core.content.BlockDescriptor;
import io.github.kpuctajluk.colonyloom.core.content.BlockOffset;
import io.github.kpuctajluk.colonyloom.core.content.BlueprintDefinition;
import io.github.kpuctajluk.colonyloom.core.spatial.TargetClaimRegistry;
import io.github.kpuctajluk.colonyloom.gameplay.construction.ConstructionController;
import java.util.HashMap;
import java.util.Map;
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
        var targets=new ConstructionController.Target[definition.blocks().size()];
        int minX=Integer.MAX_VALUE,minY=Integer.MAX_VALUE,minZ=Integer.MAX_VALUE,maxX=Integer.MIN_VALUE,maxY=Integer.MIN_VALUE,maxZ=Integer.MIN_VALUE;
        Map<BlockDescriptor,BlockDescriptor> palette=new HashMap<>();
        for(int i=0;i<targets.length;i++) {
            var block=definition.blocks().get(i); var position=position(origin,block.offset(),rotation);
            var expected=palette.computeIfAbsent(block.block(),descriptor -> descriptor(state(descriptor).rotate(rotation),descriptor.itemId()));
            targets[i]=new ConstructionController.Target(position,expected);
            minX=Math.min(minX,position.x()); minY=Math.min(minY,position.y()); minZ=Math.min(minZ,position.z());
            maxX=Math.max(maxX,position.x()); maxY=Math.max(maxY,position.y()); maxZ=Math.max(maxZ,position.z());
        }
        var marker=definition.markers().get("work_origin"); if(marker==null) throw new IllegalArgumentException("Blueprint lacks work_origin");
        var buffer=definition.markers().get("delivery_buffer"); if(buffer==null) throw new IllegalArgumentException("Blueprint lacks delivery_buffer");
        return new ConstructionController.Layout(targets,position(origin,marker,rotation),position(origin,buffer,rotation),new TargetClaimRegistry.Snapshot(workId,colonyId,null,origin.dimension(),minX,minY,minZ,maxX,maxY,maxZ,0));
    }
    private static WorldPosition position(WorldPosition origin,BlockOffset offset,Rotation rotation) {
        BlockPos transformed=StructureTemplate.transform(new BlockPos(offset.x(),offset.y(),offset.z()),Mirror.NONE,rotation,BlockPos.ZERO);
        return new WorldPosition(origin.dimension(),Math.addExact(origin.x(),transformed.getX()),Math.addExact(origin.y(),transformed.getY()),Math.addExact(origin.z(),transformed.getZ()));
    }
    public void validate(ConstructionController.Layout layout) {
        if(!server.isSameThread()) throw new IllegalStateException("Construction validation requires server thread");
        var origin=layout.workOrigin(); var level=server.getLevel(ResourceKey.create(Registries.DIMENSION,ResourceLocation.parse(origin.dimension())));
        if(level==null) throw new IllegalArgumentException("Unknown construction dimension");
        for(var target:layout.targets()) { var position=target.position(); BlockPos pos=new BlockPos(position.x(),position.y(),position.z());
            if(level.isOutsideBuildHeight(pos) || !level.getWorldBorder().isWithinBounds(pos)) throw new IllegalArgumentException("Construction target outside world");
            state(target.expected()); }
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
