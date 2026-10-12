# Colonyloom

- Java 21; Minecraft 1.21.1 + pinned NeoForge/Gradle. Use the wrapper: Windows `cmd /c gradlew.bat`, POSIX `./gradlew`.
- Dependency direction: `neoforge → minecraft → gameplay → core`. Core/gameplay are JDK-only; Minecraft must not depend on NeoForge. Packages: `colonyloom.*`.
- Authoritative state belongs to the server thread and one runtime per server. Recheck rights, work revision, citizen UUID/epoch, assignment, and admitted native chunk readiness before physical effects.
- Items are real: no duplication, virtual compensation, crash replay, replacement NPCs, or bypassing UNKNOWN/recovery guards. Preserve cargo and durable effect evidence.
- Keep admission, CPU, search, and chunk work bounded. Capacity refusal must preserve accepted work and allow later progress; never hide failures by relaxing limits.
- Verification: target 10–20 minutes, **20 minutes total maximum** unless explicitly authorized. `check assemble` runs unit tests and compiles GameTests, not Minecraft. Exercise changed runtime paths with a focused smoke; partial/old evidence is not acceptance. Use disposable worlds and owner-accepted EULA only.
- Gate T and 300-resident economy acceptance remain open; do not claim a playable survival MVP.
- Follow [Git rules](docs/git.md): working branches from `dev`, merge-only `dev`, PR-only `main`, Conventional Commits with scope and complete body. No automatic commit/push/tag or destructive cleanup of user changes.
- Read relevant [architecture](docs/architecture.md), [verification](docs/verification.md), [operations](docs/operations.md), and [plan](docs/development-plan.md). Update affected docs and [changelog](docs/changelog.md) for behavior changes.
