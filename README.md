# Colonyloom

[Russian](docs/ru/README.md)

Colonyloom is a Minecraft Java mod about building and managing a colony. Citizens work in the world, carry real items, process materials and construct buildings. The project aims to grow this into a colony with its own food and resource production, housing and progression.

> **In development — experimental technical preview, not a survival-ready mod.**
> The current build requires an operator to create citizens and a player to supply raw materials and food. The complete economy has not yet passed acceptance at 300 citizens. Use a separate test world, not a valued survival world.

## What you can try now

- Found a colony and manage its territory and owner, manager and viewer permissions.
- Create citizens with physical inventories; assign builders, couriers and carpenters.
- Register warehouses and workshops using supported Minecraft containers.
- Process oak logs into planks and planks into stairs, move materials and build the small `colonyloom:stair_strip` blueprint.
- Supply citizens with bread through the colony's food system.
- Inspect citizens, buildings, stock and work through `/colonyloom ui`.

These features form a technical preview. Survival recruitment, autonomous forestry and farming, functional housing and a complete survival gameplay loop are **planned, not available**. Research, defense and other advanced systems are later goals; this is not feature parity with MineColonies.

## Requirements

| Component | Supported version |
|---|---|
| Minecraft Java Edition | 1.21.1 |
| NeoForge | 21.1.259, required by the current build |
| Java | 21, 64-bit |
| Colonyloom | Current project version: 0.1.0, in development |

Single-player uses Minecraft's integrated server; dedicated servers are also supported. Other Minecraft versions, Forge and Fabric are not supported by this build.

## Installation

1. Create a separate Minecraft 1.21.1 instance with NeoForge 21.1.259 and Java 21.
2. Put the production `colonyloom-0.1.0.jar` in that instance's `mods` directory. For multiplayer, install the same build on the server and each participating client.
3. Start Minecraft and create a test world. On a dedicated server, the server owner must accept Minecraft's EULA before starting it.
4. Keep a full backup before changing the mod version or trying it in an existing world.

The production mod is one JAR; you do not need separate core, gameplay or Minecraft module JARs. Development test fixtures are not part of the production mod. A successful build alone does not mean that version has passed runtime or scale acceptance.

### If you are building from source

With JDK 21 installed, run the wrapper from the repository root:

```text
Windows: cmd /c gradlew.bat assemble --no-daemon --console=plain
Linux/macOS: ./gradlew assemble --no-daemon --console=plain
```

The production JAR is written to `colonyloom-neoforge/build/libs/`. Build instructions and runtime verification are separate: see the [verification guide](docs/verification.md) for development checks.

For a hidden local development client controlled through JSONL/MCP, see [Offcraft setup](docs/verification.md#offcraft-development-client). It uses a separate instance; do not add Offcraft to ordinary player or dedicated-server installations.

## First colony: technical preview

You need operator access for citizen creation, plus real building materials and bread. Commands containing `<...>` require your own values; use command completion for the available arguments.

1. **Choose an accessible area.** Open `/colonyloom ui` to found a colony, or use `/colonyloom colony create "Name" <from x y z> <to x y z>`. Founding does not load missing chunks; the selected area must already be available.
2. **Create the initial citizens as an operator.** Use `/colonyloom citizen create <colony UUID> <x y z>`. There is no ordinary-player survival recruitment yet.
3. **Set up storage and a workshop.** Place a chest or barrel for the warehouse, a separate barrel for the workshop, and a crafting table. Register the warehouse with `/colonyloom storage register <colony UUID> <x y z> warehouse`. Register the workshop barrel with `/colonyloom storage register <colony UUID> <inventory x y z> workshop`, then register the workshop with `/colonyloom building register <colony UUID> <table x y z> <inventory x y z>`. A chest is supported for warehouse storage, but production requires a workshop barrel.
4. **Assign workers.** Use the colony UI to assign a carpenter and its workplace, a builder and a courier. Make sure their workplaces and containers are reachable.
5. **Supply materials and food.** Put real oak logs or planks and bread in the registered storage. The current preview does not produce its own timber or food.
6. **Start a small build.** Before starting `colonyloom:stair_strip`, place a separate barrel at its rotated `delivery_buffer` marker and register it with `/colonyloom storage register <colony UUID> <buffer x y z> construction`. Relative to the build origin `(x, y, z)`, the buffer is at `(x−1, y, z)` for rotation 0, `(x, y, z−1)` for 90, `(x+1, y, z)` for 180, or `(x, y, z+1)` for 270; it must be inside the colony territory. Then select the blueprint in the UI, or use `/colonyloom build <colony UUID> colonyloom:stair_strip <x y z> <0|90|180|270>`. Materials go through the shared production and delivery system; the mod does not generate free supplies.

Open `/colonyloom ui <colony UUID>` to inspect an existing colony. Owners and managers can manage it; viewers can only read. See the [operations guide](docs/operations.md) for storage roles, membership commands and recovery details.

## When work stops

Read the work's reason in the UI before changing the world.

- **Missing materials or food:** supply the required real items to supported registered storage.
- **`CAPACITY`:** free suitable space in the indicated destination or return container. A courier's cargo does not disappear when you cancel its order; allow safe unloading.
- **`SEARCH_EXHAUSTED`:** the route search reached its computational limit. This does not prove no path exists; simplify access and reduce competing work.
- **`UNKNOWN` or recovery-blocked state:** ask the server operator to follow the recovery guide. Do not delete save markers, replace citizens or edit inventories to bypass the block.

Do not assume every waiting job will resolve automatically: aggregate economic progress under load remains an open acceptance blocker.

## World safety and compatibility

- Supported storage: vanilla chests, trapped chests (single or double) and barrels. Arbitrary modded or virtual inventories are not supported.
- Protection integration hooks exist, but compatibility with an arbitrary claims/protection mod is not guaranteed.
- There is no offline production while the server is stopped. Unloaded citizens are not replaced with new NPCs.
- Shut down normally before making a backup. Save the **entire world**, server configuration and the previous mod JAR together; copying only colony data is not a valid backup.
- A crash can require explicit operator recovery. Recovery accepts the observed world; it does not promise automatic refunds, recreated items or replayed actions.
- Several hundred citizens are a target, not a performance guarantee. The technical acceptance gate and 300-citizen economy acceptance are still open.

## Documentation

- [Operations and recovery](docs/operations.md) — setup, supported behavior, backups and operator recovery.
- [Changelog](docs/changelog.md) — implemented changes and recorded verification limits.
- [Development plan](docs/development-plan.md) — current blockers and planned gameplay.
- [Architecture](docs/architecture.md) · [Verification](docs/verification.md) · [Contribution and Git rules](docs/git.md) — developer documentation.
- [Russian documentation](docs/ru/README.md) — Russian translations of this README and all documentation guides.

## Reporting a problem

Include the Minecraft, NeoForge and Colonyloom versions; whether it happened in single-player or on a dedicated server; reproduction steps; relevant logs or a crash report; and the work reason shown in the UI. For inventory or recovery problems, describe what happened before the failure and whether the world has been restarted or edited. Keep an untouched backup before attempting recovery.

## License

Colonyloom is distributed under the [MIT License](LICENSE).
