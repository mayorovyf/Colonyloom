# Colonyloom — operations and recovery

[Russian](ru/operations.md)

## Supported platform

Minecraft Java 1.21.1, the NeoForge version from `gradle.properties`, and JDK21 x64. A single release JAR from `colonyloom-neoforge/build/libs/` includes core/gameplay/minecraft. The development fixture mod and crash/scale scenarios are excluded. Integrated and dedicated servers use the server runtime; performance acceptance is tied to a particular host's manifest and does not guarantee any TPS.

### Storage and protection

Native chests, trapped chests (single/double), and barrels are supported. All capability contexts must prove an exact slot mapping through the native InvWrapper. Generic IItemHandler, virtual/modded containers, and aliases without proven physical identity are **not supported**. Replacing/rearranging a container invalidates trust in the index; identity repair is performed explicitly, not by disabling guards.

Vanilla world-border/permission checks and NeoForge placement checks apply before physical actions. Colonyloom transfer/craft/food veto events are integration hooks, not universal support for claims mods. A specific claims adapter is accepted only after physical verification of all effects, an offline owner, and revocation. An unsupported backend is prohibited and does not provide fictitious stock.

### Inactive citizens and waits

A citizen UUID, entity UUID, and binding epoch are distinct. An unloaded NPC is not replaced with a new one; paused own-time does not catch up on hunger after loading. A BLOCKED/UNKNOWN/recovery entity keeps its property quarantined. Inventory access and commands check current permissions, not the connection's former role.

CAPACITY means cargo cannot be placed safely. Free a suitable slot in the specified destination/return/source buffer and wait for rechecking. Cargo remains with the real courier; cancelling an order does not delete items. SEARCH_EXHAUSTED means a computational search limit, not proof that no path exists. Reduce the workload/simplify access and observe the work again; do not manually declare cargo delivered.

Membership: no more than **64 non-owner** members; changing a rank and transferring ownership to an existing member are allowed with a full list, while growth beyond the cap is refused before revisions. Founding: up to 128×128 cells, a total limit of 16384 physical checks per server tick, and 16384 per player per 20 ticks; refusal also consumes allowance. There is no colony publication or new tickets until verification is complete.

### Current technical bootstrap

Gameplay Gate G has not yet been accepted: autonomous wood/food sites and survival recruitment are absent. Do not present this release as a playable survival MVP.

1. The player founds a territory through `/colonyloom ui` or `/colonyloom colony create "Name" <from x y z> <to x y z>`. The area must already be accessible; founding does not load missing chunks. In the UI, select your boundaries and wait for an accepted result.
2. For the **technical slice**, an operator creates the initial citizens: `/colonyloom citizen create <colony UUID> <x y z>`. This is the existing op-only bootstrap, not a promise of normal-player recruitment.
3. Place a native chest/barrel for the warehouse, a separate workshop barrel, and a crafting table. Register the warehouse with `/colonyloom storage register <colony UUID> <x y z> warehouse`. Register the workshop barrel with `/colonyloom storage register <colony UUID> <inventory x y z> workshop` before `/colonyloom building register <colony UUID> <table x y z> <inventory x y z>`; production requires a barrel, not a chest. Assign a carpenter and workplace, builder, and courier through the UI or `citizen assign`/`citizen workplace`. Supported storage roles: `warehouse`, `workshop`, `construction`, `return`.
4. Add real oak logs/planks and bread. Before starting `colonyloom:stair_strip`, place a separate barrel at its rotated `delivery_buffer` marker and register it with `/colonyloom storage register <colony UUID> <buffer x y z> construction`. Relative to the build origin `(x, y, z)`, the buffer is at `(x−1, y, z)` for rotation 0, `(x, y, z−1)` for 90, `(x+1, y, z)` for 180, or `(x, y, z+1)` for 270; it must be inside the colony territory. Then start the blueprint in the UI or with `/colonyloom build <colony UUID> colonyloom:stair_strip <x y z> <0|90|180|270>`. Materials pass through the shared supply/production/delivery flow; fixture provisioning is absent from production. The player supplies food in the technical slice.
5. During a wait, read the work reason and the specified physical buffer; free suitable space without editing cargo or effect evidence. After `work cancel`, wait for safe unloading. A normal `stop` creates a consistent clean checkpoint; a crash requires the operator recovery procedure below.

`/colonyloom ui <colony UUID>` opens the available addressed pages; owner/manager can manage them, while viewer can only read. Member/owner commands accept a player profile: `/colonyloom member set <colony UUID> <player> <manager|viewer|none>`, `/colonyloom owner set <colony UUID> <player>`. Server metrics require op: `/colonyloom metrics server`; colony metrics require read permission.

## Saves and backups

The DTO `world/data/colonyloom.dat`, marker `world/data/colonyloom-session.nbt`, and native Minecraft region/entity data are parts of one checkpoint. Before upgrading, back up the entire stopped world, server config, and previous release JAR. Do not copy only the DTO over an active Minecraft world.

A virgin world is permitted only when **both** the DTO and marker are absent. If a marker exists and the DTO is missing/corrupt, the mod does not create an empty colony or overwrite the lost state. Stop the server, restore a consistent checkpoint from backup, then perform verification. Deleting the marker to bypass refusal destroys the indication of prior obligations and is not recovery.

A future/unsupported root schema is refused safely. Unknown nested records are retained as opaque; identifiable dependencies are blocked, while unrelated colonies must not be blocked automatically. The operator report is limited to metadata; raw NBT is not sent to clients.
Known binding observations whose citizen is opaque remain original NBT alongside that citizen, not live UNKNOWN identities. Their lifetime cap is checked against the original colony scope; truly unidentified observations retain the separate 600-row cap. Native embodiments of retained citizens remain quarantined and do not republish duplicate binding rows on load/unload. Restoring the supported content restores the retained history; deleting retired UUIDs is not recovery.
The current root schema is **2**. The specific upgrade **1 → 2** retains old records; a hash-only death witness remains `UNKNOWN`, and drop UUIDs are not invented retroactively. An exact death witness has nested schema 1 and is limited to nine cargo slots and nine published drops. Metadata report: up to 32 rows, type/schema/object/dependency/reason, opaque count, and a truncation indicator.


A supported migration saves the original compressed checkpoint to `world/data/colonyloom-backups/<checkpoint UUID>-v<source root schema>.dat` before overwriting and verifies that an existing backup is unchanged. Conflicting bytes cause refusal before writable attachment. Downgrading means restoring a **full** backup of the old world/config/JAR, not running an old JAR over a new DTO. There is no automatic loss of unknown data, downgrade conversion, or replay.

## Operator recovery

After a crash, a dirty/undefined marker blocks the affected state; a normal clean shutdown does not accept it automatically. Use `/colonyloom recovery inspect <colony UUID>`, then, if necessary, explicit binding and `/colonyloom recovery accept-world <colony UUID> <checkpoint UUID>`.

Inspect shows canonical effects/assignments and actual property. Known competing embodiments must be loaded before binding; binding increases the epoch and leaves others' property quarantined. Unloaded/stale observations are not accepted.

Before acceptance, the checkpoint, inventory, construction world, and effect witnesses are rechecked. Cargo/drops changed or picked up after inspect require a new inspect. UNKNOWN means no proven outcome, not an instruction to restore items. Death recovery does not create items or republish drops; the policy allows source-only loss. The operator accepts the actual world, not the promised economy.

Identity history is limited to **600 embodiments per colony**, plus a separate 600 unknown embodiments; retired UUIDs count toward the lifetime cap, and the total envelope is 2400. Overflow in a known colony blocks only that colony. An unrecorded embodiment beyond the cap remains quarantined and must be resolved before accept-world; do not delete retired UUIDs manually: late loading can duplicate property. Use a full backup; a third-party inventory editor does not confirm preservation.

Overflow procedure: stop changes and save a full backup; inspect the history, `unrecordedEmbodimentCount`, and the physical property of each conflicting NPC. Do not delete an entity with unknown/nonempty inventory to remove the block: quarantined cargo does not become free stock. If preservation cannot be proven, restore a consistent backup; inspect/accept do not compensate for deleted items.

For an ALIVE citizen, recovery requires loading **all** known embodiments, including retired ones: a historical row alone does not prove the absence of physical property. For DEAD/REMOVED records, the live embodiment requirement does not apply; the tombstone and retired UUIDs are retained, and late loading remains quarantined. After resolving an unrecorded conflict, run a new inspect and accept with the current checkpoint. A full lifetime cap is not freed: new recruitment/new embodiments in an exhausted colony are still refused.

The technical proof `identityOverflowSmoke` must use a marked disposable world and three real dedicated JVM phases (`initialize`, `exercise`, `verify`) with unchanged source/config/content hashes. Record public command IDs, `identityHistory=600/600`, the conflicting UUID, and retained property; a normal stop must save a clean checkpoint. After stopping, verify the physical property of retired UUIDs through native entity-region NBT, because `ServerStoppedEvent` may occur after live entities have unloaded. This supplements rather than replaces ordinary operator inspect/accept.

## Release verification

Commands, prerequisites, fixed acceptance, and archive layout: [verification.md](verification.md). `check` does not prove physics, and an earlier PASS does not prove the current code; gameplay release status is determined by the gates in [development-plan.md](development-plan.md).
