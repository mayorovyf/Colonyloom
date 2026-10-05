package io.github.kpuctajluk.colonyloom.core.action;

import io.github.kpuctajluk.colonyloom.core.colony.WorldPosition;
import io.github.kpuctajluk.colonyloom.core.storage.ItemDescriptor;
import io.github.kpuctajluk.colonyloom.core.storage.StockRegion;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Evidence, not a transaction across Minecraft chunks, entities and colony SavedData. */
public record EffectRecord(UUID operationId, UUID colonyId, UUID workId, UUID citizenId,
        long bindingEpoch, ActionContext.Kind kind, WorldPosition target, String expectedBlock,
        String itemId, int countBefore, int countAfter, State state, long revision, Transfer transfer, Craft craft, Food food, Death death) {
    public enum State { PREPARED, OBSERVED, AMBIGUOUS, ACCEPTED }
    /** Existing cargo and native item UUIDs; evidence only, never a command to respawn drops. */
    public record DeathCargo(int slot, ItemDescriptor item, int count) {
        public DeathCargo {
            Objects.requireNonNull(item);
            if(slot<0 || slot>=9 || count<1 || count>99) throw new IllegalArgumentException("Invalid death cargo");
        }
    }
    public record DeathDrop(UUID entityId, ItemDescriptor item, int count) {
        public DeathDrop {
            Objects.requireNonNull(entityId); Objects.requireNonNull(item);
            if(count<1 || count>99) throw new IllegalArgumentException("Invalid death drop");
        }
    }
    public record Death(UUID sourceEntityId, List<DeathCargo> cargo, List<DeathDrop> drops, boolean publicationObserved) {
        public Death {
            Objects.requireNonNull(sourceEntityId); cargo=List.copyOf(cargo); drops=List.copyOf(drops);
            if(cargo.size()>9 || drops.size()>9 || !publicationObserved && !drops.isEmpty()) throw new IllegalArgumentException("Death evidence envelope exceeded");
            var slots=new HashSet<Integer>(); var ids=new HashSet<UUID>();
            for(var value:cargo) if(!slots.add(value.slot())) throw new IllegalArgumentException("Duplicate death cargo slot");
            for(var value:drops) if(!ids.add(value.entityId())) throw new IllegalArgumentException("Duplicate death drop UUID");
        }
        public boolean sameAttempt(Death next) { return next!=null && sourceEntityId.equals(next.sourceEntityId) && cargo.equals(next.cargo); }
        public int cargoCount() { return cargo.stream().mapToInt(DeathCargo::count).sum(); }
        public boolean conserved() {
            if(!publicationObserved) return false;
            var counts=new java.util.HashMap<ItemDescriptor,Integer>();
            for(var value:cargo) counts.merge(value.item(),value.count(),Integer::sum);
            for(var value:drops) counts.merge(value.item(),-value.count(),Integer::sum);
            return counts.values().stream().allMatch(value -> value==0);
        }
    }
    public record Food(StockRegion slot, ItemDescriptor item, UUID shareId, UUID demandId,
            int foodBefore, int foodAfter, long timerBefore, long timerAfter) {
        public Food {
            Objects.requireNonNull(slot); Objects.requireNonNull(item); Objects.requireNonNull(shareId); Objects.requireNonNull(demandId);
            if (!item.itemId().equals("minecraft:bread") || foodBefore < 0 || foodBefore > 20 || foodAfter < 0 || foodAfter > 20
                    || timerBefore < 0 || timerAfter < 0) throw new IllegalArgumentException("Invalid food evidence");
        }
        public boolean sameAttempt(Food next) {
            return next != null && slot.equals(next.slot) && item.equals(next.item) && shareId.equals(next.shareId)
                    && demandId.equals(next.demandId) && foodBefore == next.foodBefore && timerBefore == next.timerBefore;
        }
        public boolean unchanged() { return foodBefore == foodAfter && timerBefore == timerAfter; }
    }
    /** Canonical physical participants and measured deltas, never a replayable inventory command. */
    public enum CraftPhase { PREPARED, INPUTS_CONSUMED, OUTPUT_INSERTED, FACT_OBSERVED }
    /** Exact canonical slot states; a changed component map remains evidence rather than being hidden. */
    public record CraftSlot(StockRegion slot, ItemDescriptor beforeItem, int beforeCount,
            ItemDescriptor afterItem, int afterCount, int amount) {
        public CraftSlot {
            Objects.requireNonNull(slot);
            if (slot.storage().bindingEpoch()!=0 || beforeCount<0 || beforeCount>891 || afterCount<0 || afterCount>891
                    || amount<1 || amount>891 || (beforeCount==0)!=(beforeItem==null) || (afterCount==0)!=(afterItem==null))
                throw new IllegalArgumentException("Invalid native craft slot evidence");
        }
        public boolean sameAttempt(CraftSlot next) {
            return next!=null && slot.equals(next.slot) && Objects.equals(beforeItem,next.beforeItem)
                    && beforeCount==next.beforeCount && amount==next.amount;
        }
        public boolean unchanged() { return beforeCount==afterCount && Objects.equals(beforeItem,afterItem); }
        public CraftSlot observed(ItemDescriptor item,int count) { return new CraftSlot(slot,beforeItem,beforeCount,item,count,amount); }
    }
    /** Pinned batch and native barrel facts, never instructions to repeat a recipe. */
    public record Craft(UUID productionId, long batchOrdinal, UUID workshopId, UUID registrationId,
            long workshopRevision, WorldPosition table, WorldPosition inventory, String recipeId, int recipeVersion,
            String recipeDigest, List<CraftSlot> inputs, ItemDescriptor output, int outputCount,
            List<CraftSlot> outputs, CraftPhase phase) {
        public static final int MAX_SLOTS=16, MAX_TOTAL=MAX_SLOTS*891;
        public Craft {
            Objects.requireNonNull(productionId); Objects.requireNonNull(workshopId); Objects.requireNonNull(registrationId);
            Objects.requireNonNull(table); Objects.requireNonNull(inventory); Objects.requireNonNull(recipeId);
            Objects.requireNonNull(recipeDigest); Objects.requireNonNull(output); Objects.requireNonNull(phase);
            inputs=List.copyOf(inputs); outputs=List.copyOf(outputs);
            if (batchOrdinal<0 || workshopRevision<0 || recipeVersion<1 || recipeId.isEmpty() || recipeId.length()>256
                    || recipeDigest.length()!=64 || inputs.isEmpty() || outputs.isEmpty() || inputs.size()+outputs.size()>MAX_SLOTS
                    || outputCount<1 || outputCount>MAX_TOTAL || !table.dimension().equals(inventory.dimension()))
                throw new IllegalArgumentException("Invalid native craft evidence");
            var slots=new HashSet<StockRegion>(); int routed=0; var barrel=inputs.getFirst().slot().storage();
            for(var input:inputs) {
                if (!slots.add(input.slot()) || input.beforeCount()<input.amount() || !input.slot().storage().equals(barrel)
                        || !input.slot().storage().dimension().equals(inventory.dimension()))
                    throw new IllegalArgumentException("Invalid canonical craft input");
            }
            for(var destination:outputs) {
                if (!slots.add(destination.slot()) || !destination.slot().storage().equals(barrel)
                        || !destination.slot().storage().dimension().equals(inventory.dimension()))
                    throw new IllegalArgumentException("Invalid canonical craft output");
                routed=Math.addExact(routed,destination.amount());
            }
            if(routed!=outputCount) throw new IllegalArgumentException("Craft output routing differs from complete batch");
        }
        public boolean sameAttempt(Craft next) {
            if(next==null || !productionId.equals(next.productionId) || batchOrdinal!=next.batchOrdinal
                    || !workshopId.equals(next.workshopId) || !registrationId.equals(next.registrationId)
                    || workshopRevision!=next.workshopRevision || !table.equals(next.table) || !inventory.equals(next.inventory)
                    || !recipeId.equals(next.recipeId) || recipeVersion!=next.recipeVersion || !recipeDigest.equals(next.recipeDigest)
                    || !output.equals(next.output) || outputCount!=next.outputCount || inputs.size()!=next.inputs.size() || outputs.size()!=next.outputs.size()) return false;
            for(int i=0;i<inputs.size();i++) if(!inputs.get(i).sameAttempt(next.inputs.get(i))) return false;
            for(int i=0;i<outputs.size();i++) if(!outputs.get(i).sameAttempt(next.outputs.get(i))) return false;
            return true;
        }
        public boolean unchanged() {
            return phase==CraftPhase.PREPARED && inputs.stream().allMatch(CraftSlot::unchanged) && outputs.stream().allMatch(CraftSlot::unchanged);
        }
        public int outputBefore() { return outputs.stream().mapToInt(CraftSlot::beforeCount).sum(); }
        public int outputAfter() { return outputs.stream().mapToInt(CraftSlot::afterCount).sum(); }
        public boolean complete() {
            if(phase!=CraftPhase.FACT_OBSERVED) return false;
            for(var input:inputs) if(input.afterCount()!=input.beforeCount()-input.amount()
                    || !Objects.equals(input.afterItem(),input.afterCount()==0?null:input.beforeItem())) return false;
            for(var destination:outputs) if(destination.beforeCount()>0 && !output.equals(destination.beforeItem())
                    || destination.afterCount()!=destination.beforeCount()+destination.amount() || !output.equals(destination.afterItem())) return false;
            return true;
        }
        public Craft observed(List<CraftSlot> sources,List<CraftSlot> destinations,CraftPhase nextPhase) {
            return new Craft(productionId,batchOrdinal,workshopId,registrationId,workshopRevision,table,inventory,
                    recipeId,recipeVersion,recipeDigest,sources,output,outputCount,destinations,nextPhase);
        }
    }
    public record Transfer(StockRegion source, StockRegion destination, ItemDescriptor item,
            int sourceBefore, int sourceAfter, int destinationBefore, int destinationAfter,
            int maximum, int extracted, int inserted, int returned) {
        public Transfer {
            Objects.requireNonNull(source); Objects.requireNonNull(destination); Objects.requireNonNull(item);
            if (source.equals(destination) || sourceBefore < 0 || sourceBefore > 891 || sourceAfter < 0 || sourceAfter > 891
                    || destinationBefore < 0 || destinationBefore > 891 || destinationAfter < 0 || destinationAfter > 891
                    || maximum < 1 || maximum > 891 || extracted < 0 || extracted > 891
                    || inserted < 0 || inserted > 891 || returned < 0 || returned > 891)
                throw new IllegalArgumentException("Invalid native transfer evidence");
        }
        public boolean sameAttempt(Transfer next) {
            return next != null && source.equals(next.source) && destination.equals(next.destination)
                    && item.equals(next.item) && sourceBefore == next.sourceBefore
                    && destinationBefore == next.destinationBefore && maximum == next.maximum;
        }
        public boolean unchanged() {
            return sourceBefore == sourceAfter && destinationBefore == destinationAfter
                    && extracted == 0 && inserted == 0 && returned == 0;
        }
        public Transfer observed(int sourceCount, int destinationCount, int extractedCount, int insertedCount, int returnedCount) {
            return new Transfer(source,destination,item,sourceBefore,sourceCount,destinationBefore,destinationCount,
                    maximum,extractedCount,insertedCount,returnedCount);
        }
    }
    public EffectRecord {
        Objects.requireNonNull(operationId); Objects.requireNonNull(colonyId); Objects.requireNonNull(citizenId);
        Objects.requireNonNull(kind); Objects.requireNonNull(target); Objects.requireNonNull(expectedBlock);
        Objects.requireNonNull(itemId); Objects.requireNonNull(state);
        int maximum=kind==ActionContext.Kind.RECIPE_CRAFT?Craft.MAX_TOTAL:891;
        if (bindingEpoch < 1 || revision < 0 || countBefore < 0 || countBefore > maximum || countAfter < 0
                || countAfter > maximum || expectedBlock.length() > 1024 || itemId.length() > 256
                || kind == ActionContext.Kind.BLOCK_PLACE && workId == null)
            throw new IllegalArgumentException("Invalid physical effect evidence");
        if ((kind == ActionContext.Kind.STORAGE_TRANSFER) != (transfer != null)
                || transfer != null && (!itemId.equals(transfer.item().itemId())
                        || countBefore != transfer.sourceBefore() || countAfter != transfer.sourceAfter()))
            throw new IllegalArgumentException("Transfer evidence does not match physical effect");
        if (transfer != null && state == State.OBSERVED
                && (transfer.extracted() != transfer.inserted()+transfer.returned()
                        || transfer.sourceBefore()-transfer.sourceAfter() != transfer.inserted()
                        || transfer.destinationAfter()-transfer.destinationBefore() != transfer.inserted()
                        || transfer.extracted() > transfer.maximum()))
            throw new IllegalArgumentException("Observed transfer evidence is not conserved");
        if (transfer != null && (!transfer.source().storage().dimension().equals(target.dimension())
                || !transfer.destination().storage().dimension().equals(target.dimension())
                || !(transfer.source().storage().identity().equals(citizenId) && transfer.source().storage().bindingEpoch()==bindingEpoch
                        || transfer.destination().storage().identity().equals(citizenId) && transfer.destination().storage().bindingEpoch()==bindingEpoch)))
            throw new IllegalArgumentException("Transfer evidence missing canonical courier participant");
        if ((kind==ActionContext.Kind.RECIPE_CRAFT)!=(craft!=null) || craft!=null && (transfer!=null
                || !target.equals(craft.table()) || !itemId.equals(craft.output().itemId())
                || countBefore!=craft.outputBefore() || countAfter!=craft.outputAfter()
                || state==State.PREPARED && !craft.unchanged() || state==State.OBSERVED && !craft.complete()))
            throw new IllegalArgumentException("Craft evidence does not match physical effect");
        if ((kind == ActionContext.Kind.FOOD_CONSUME) != (food != null) || food != null && (workId == null || transfer != null || craft != null
                || !itemId.equals(food.item().itemId()) || !food.slot().storage().identity().equals(citizenId)
                || food.slot().storage().bindingEpoch() != bindingEpoch || !food.slot().storage().dimension().equals(target.dimension())
                || state == State.PREPARED && !food.unchanged()
                || state == State.OBSERVED && (countBefore - countAfter != 1 || food.foodAfter() != Math.min(20, food.foodBefore() + 5) || food.timerBefore() != food.timerAfter())))
            throw new IllegalArgumentException("Food evidence does not match physical consumption");
        if(death!=null && (kind!=ActionContext.Kind.DEATH || death.cargoCount()!=countBefore
                || state==State.PREPARED && death.publicationObserved()
                || state==State.OBSERVED && (countAfter!=0 || !death.conserved())))
            throw new IllegalArgumentException("Death evidence does not match native finality");
    }
    public EffectRecord observed(int after, boolean ambiguous) {
        return new EffectRecord(operationId,colonyId,workId,citizenId,bindingEpoch,kind,target,expectedBlock,itemId,countBefore,after,ambiguous ? State.AMBIGUOUS : State.OBSERVED,Math.incrementExact(revision),transfer,craft,food,death);
    }
    public EffectRecord observedTransfer(Transfer fact, boolean ambiguous) {
        if (transfer == null || !transfer.sameAttempt(fact)) throw new IllegalArgumentException("Transfer attempt changed");
        return new EffectRecord(operationId,colonyId,workId,citizenId,bindingEpoch,kind,target,expectedBlock,itemId,countBefore,fact.sourceAfter(),ambiguous ? State.AMBIGUOUS : State.OBSERVED,Math.incrementExact(revision),fact,craft,food,death);
    }
    public EffectRecord observedCraft(Craft fact,boolean ambiguous) {
        if(craft==null || !craft.sameAttempt(fact) || fact.phase().ordinal()<craft.phase().ordinal())
            throw new IllegalArgumentException("Craft attempt changed or phase rewound");
        return new EffectRecord(operationId,colonyId,workId,citizenId,bindingEpoch,kind,target,expectedBlock,itemId,countBefore,fact.outputAfter(),ambiguous?State.AMBIGUOUS:State.OBSERVED,Math.incrementExact(revision),transfer,fact,food,death);
    }
    public EffectRecord observedFood(Food fact, int after, boolean ambiguous) {
        if (food == null || !food.sameAttempt(fact)) throw new IllegalArgumentException("Food attempt changed");
        return new EffectRecord(operationId,colonyId,workId,citizenId,bindingEpoch,kind,target,expectedBlock,itemId,countBefore,after,ambiguous?State.AMBIGUOUS:State.OBSERVED,Math.incrementExact(revision),transfer,craft,fact,death);
    }
    public EffectRecord observedDeath(Death fact,int after,boolean ambiguous) {
        if(death==null || !death.sameAttempt(fact)) throw new IllegalArgumentException("Death attempt changed");
        return new EffectRecord(operationId,colonyId,workId,citizenId,bindingEpoch,kind,target,expectedBlock,
                itemId,countBefore,after,ambiguous?State.AMBIGUOUS:State.OBSERVED,Math.incrementExact(revision),transfer,craft,food,fact);
    }
    public EffectRecord accepted() {
        return new EffectRecord(operationId,colonyId,workId,citizenId,bindingEpoch,kind,target,expectedBlock,itemId,countBefore,countAfter,State.ACCEPTED,Math.incrementExact(revision),transfer,craft,food,death);
    }
}
