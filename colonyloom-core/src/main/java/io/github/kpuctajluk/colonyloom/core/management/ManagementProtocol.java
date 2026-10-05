package io.github.kpuctajluk.colonyloom.core.management;

import io.github.kpuctajluk.colonyloom.core.colony.Territory;
import io.github.kpuctajluk.colonyloom.core.colony.WorldPosition;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Immutable wire values only. Commands never contain authoritative colony or inventory objects. */
public final class ManagementProtocol {
    public static final String PROTOCOL_VERSION="1";
    public static final int COMMAND_BYTES=8192, VIEW_BYTES=32768, STRING_BYTES=256, PAGE_ROWS=50;
    public static final int RESULT_COUNT=128, RESULT_BYTES=65536, SUBSCRIPTIONS=4, VIEW_BUFFER_BYTES=262144;
    public static final int COMMANDS_PER_SECOND=20, SEND_INTERVAL=10, ACK_TIMEOUT=100;
    public static final int SUBSCRIPTIONS_PER_SECOND=20, VIEW_CLOSES_PER_SECOND=20;
    private ManagementProtocol() {}
    public static String text(String value) {
        Objects.requireNonNull(value);
        if(ManagementSession.utf8Bytes(value)>STRING_BYTES)throw new IllegalArgumentException("STRING_LIMIT");
        return value;
    }
    private static long revision(long value) {if(value<0)throw new IllegalArgumentException("INVALID_REVISION");return value;}
    public enum Status { ACCEPTED, REJECTED, STALE, DUPLICATE_EXPIRED }
    public enum ViewType { SUMMARY, CITIZENS, BUILDINGS, WORK }
    public sealed interface Body permits CreateColony, AssignProfession, AssignWorkplace, Build, CancelWork, PrioritizeWork, SetMember, SetOwner, RegisterStorage, RegisterWorkshop {
        String typeId();
    }
    public record CreateColony(String name,Territory territory) implements Body {
        public CreateColony {text(name);Objects.requireNonNull(territory);text(territory.dimension());}
        public String typeId(){return "colonyloom:create_colony";}
    }
    public record AssignProfession(UUID citizenId,String professionId) implements Body {
        public AssignProfession {Objects.requireNonNull(citizenId);text(professionId);}
        public String typeId(){return "colonyloom:assign_profession";}
    }
    public record AssignWorkplace(UUID citizenId,UUID workshopId) implements Body {
        public AssignWorkplace {Objects.requireNonNull(citizenId);Objects.requireNonNull(workshopId);}
        public String typeId(){return "colonyloom:assign_workplace";}
    }
    public record Build(String blueprintId,WorldPosition origin,int rotation) implements Body {
        public Build {text(blueprintId);Objects.requireNonNull(origin);text(origin.dimension());if(rotation!=0&&rotation!=90&&rotation!=180&&rotation!=270)throw new IllegalArgumentException("INVALID_ROTATION");}
        public String typeId(){return "colonyloom:build";}
    }
    public record CancelWork(UUID workId) implements Body {
        public CancelWork {Objects.requireNonNull(workId);}
        public String typeId(){return "colonyloom:cancel_work";}
    }
    public record PrioritizeWork(UUID workId,int priority) implements Body {
        public PrioritizeWork {Objects.requireNonNull(workId);if(priority<0||priority>10)throw new IllegalArgumentException("INVALID_PRIORITY");}
        public String typeId(){return "colonyloom:prioritize_work";}
    }
    /** rank is manager, viewer or none; ownership uses a separate command. */
    public record SetMember(UUID playerId,String rank) implements Body {
        public SetMember {Objects.requireNonNull(playerId);if(!List.of("manager","viewer","none").contains(rank))throw new IllegalArgumentException("INVALID_RANK");}
        public String typeId(){return "colonyloom:set_member";}
    }
    public record SetOwner(UUID playerId) implements Body {
        public SetOwner {Objects.requireNonNull(playerId);}
        public String typeId(){return "colonyloom:set_owner";}
    }
    public record RegisterStorage(WorldPosition position,String role) implements Body {
        public RegisterStorage {Objects.requireNonNull(position);text(position.dimension());text(role);}
        public String typeId(){return "colonyloom:register_storage";}
    }
    public record RegisterWorkshop(WorldPosition table,WorldPosition inventory) implements Body {
        public RegisterWorkshop {Objects.requireNonNull(table);Objects.requireNonNull(inventory);text(table.dimension());text(inventory.dimension());}
        public String typeId(){return "colonyloom:register_workshop";}
    }
    public record Command(UUID sessionId,long sequence,UUID colonyId,long expectedRevision,Body body) {
        public Command {
            Objects.requireNonNull(sessionId);Objects.requireNonNull(body);revision(expectedRevision);
            if(sequence<0)throw new IllegalArgumentException("INVALID_SEQUENCE");
            if(body instanceof CreateColony) {if(colonyId!=null||expectedRevision!=0)throw new IllegalArgumentException("INVALID_CREATE_ENVELOPE");}
            else Objects.requireNonNull(colonyId);
        }
    }
    public record Result(long sequence,Status status,String reason,UUID objectId,long revision) {
        public Result {if(sequence<0)throw new IllegalArgumentException("INVALID_SEQUENCE");Objects.requireNonNull(status);text(reason);ManagementProtocol.revision(revision);}
    }
    /** Seven bounded columns, not arbitrary NBT/JSON or a full authoritative aggregate. */
    public record Row(UUID id,long revision,String name,String state,String reason,String detail,UUID relatedId) {
        public Row {Objects.requireNonNull(id);ManagementProtocol.revision(revision);text(name);text(state);text(reason);text(detail);}
    }
    public record ViewData(UUID colonyId,String colonyName,String rank,long authorityRevision,long stateRevision,ViewType type,int page,int totalRows,
                           List<Row> rows,List<String> professions,List<String> blueprints) {
        public ViewData {
            Objects.requireNonNull(colonyId);text(colonyName);text(rank);Objects.requireNonNull(type);
            revision(authorityRevision);revision(stateRevision);
            if(page<0||page>1_000_000||totalRows<0)throw new IllegalArgumentException("INVALID_PAGE");
            rows=List.copyOf(rows);professions=List.copyOf(professions);blueprints=List.copyOf(blueprints);
            if(rows.size()>PAGE_ROWS||professions.size()>64||blueprints.size()>64)throw new IllegalArgumentException("VIEW_LIMIT");
            professions.forEach(ManagementProtocol::text);blueprints.forEach(ManagementProtocol::text);
        }
    }
    public record Subscription(UUID sessionId,UUID subscriptionId,UUID colonyId,ViewType type,int page,boolean resync) {
        public Subscription {Objects.requireNonNull(sessionId);Objects.requireNonNull(subscriptionId);Objects.requireNonNull(colonyId);Objects.requireNonNull(type);if(page<0||page>1_000_000)throw new IllegalArgumentException("INVALID_PAGE");}
    }
}
