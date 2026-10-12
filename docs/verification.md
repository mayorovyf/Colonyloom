# Colonyloom — verification and acceptance

[Russian](ru/verification.md)

## Prerequisites

JDK 21 x64; the Gradle wrapper from the checkout; Minecraft 1.21.1 + NeoForge from `gradle.properties`. Dedicated fixtures require a file containing `eula=true`, already accepted by the owner; the runner does not accept the terms automatically. All crash/load scenarios create new disposable worlds under `colonyloom-neoforge/build/reports/colonyloom/`. Do not use a working world.

Windows: `cmd /c gradlew.bat`; POSIX: `./gradlew`. The commands below are Windows commands run from the repository root. Desktop/OpenGL is required for management/scale UI; the absence of a graphical client is not replaced by a model test.

## Quick gate

```text
cmd /c gradlew.bat check assemble --no-daemon --console=plain
cmd /c gradlew.bat :colonyloom-neoforge:verificationManifest --no-daemon --console=plain
```

`check` verifies architectural boundaries and core/model suites and compiles **all** native GameTest classes/resources. It does not launch Minecraft. `verificationManifest` records source/build-config/content hashes, JDK/OS/CPU-count/RAM details, and the fixed `gradle/acceptance-profile.json`; NOT_RUN status is not acceptance.

## Physical regressions and recovery

```text
cmd /c gradlew.bat :colonyloom-neoforge:runGameTestServer -PcolonyloomGameTestDirectory=build/reports/colonyloom/native-current --no-daemon --console=plain
cmd /c gradlew.bat :colonyloom-neoforge:dedicatedSmoke --no-daemon --console=plain
cmd /c gradlew.bat :colonyloom-neoforge:firstMutationSmoke --no-daemon --console=plain
cmd /c gradlew.bat :colonyloom-neoforge:storageSmoke -PcolonyloomStorageCalibration=true --no-daemon --console=plain
cmd /c gradlew.bat :colonyloom-neoforge:deliverySmoke :colonyloom-neoforge:productionSmoke :colonyloom-neoforge:needsSmoke --no-daemon --console=plain
cmd /c gradlew.bat :colonyloom-neoforge:recoverySmoke --no-daemon --console=plain
cmd /c gradlew.bat :colonyloom-neoforge:managementSmoke --no-daemon --console=plain
```

By default, the owner-accepted EULA is read from `colonyloom-neoforge/runs/server/eula.txt`. To use another already accepted file: `-PcolonyloomEulaFile=<path relative to colonyloom-neoforge or absolute>`. Do not copy an EULA from someone else's environment to bypass consent.

A required GameTest FAIL, a crash without the expected witness, unfinished work, or missing observations is FAIL. Read the runner's final result and individual facts; exit=0 alone does not prove a physical outcome.

Navigation consumer regressions distinguish ready WAITING discovery from native MOVING polling. Pending-query cancellation, reentrant search/apply replacement, pre-entry position supersession and post-apply readiness loss must preserve exact request identity and release old movement/query/route capacity without releasing a successor's demand. Backend exceptions remain failures; cleanup exceptions are suppressed, never converted into a successful typed outcome.

Chunk eviction plans share paid visits with pending admission discovery. A normal request blocked by unrelated critical capacity must not bury a feasible critical domain behind an entire eviction scan; normal blockade refusal, atomic all-ring admission and shared native ticket accounting remain enforced.

Pending discovery also rotates per-lane FIFOs: a feasible critical domain must not wait behind every normal desire during a critical blockade. The existing overall dirty-rescan quota still applies, and normal requests must resume after capacity release.

Production work admission denial must retain the accepted production order and its allocated kit without creating partial work. A later budgeted visit after capacity release must bind one work and complete the original batch; a native tick must not crash on this typed refusal. This regression does not by itself prove economy drain.

Unpaced native GameTest setup prepares only the already-ticketed radius-two FULL halo with `getChunk(..., FULL, false)` before waiting for real block/entity ticking. This creates no extra UNKNOWN tickets and does not substitute loaded chunks for readiness. The original 63-route and cargo-safe food-return fixtures retain their total deadlines and physical assertions; failure diagnostics report native loaded/block/entity states before cleanup.

Physical continuation regressions require exact captured work identity/revision, bidirectional original citizen assignment and binding epoch across synchronous callbacks. An executor-owned typed assigned WAITING transition retains navigation only; fresh RUNNING capture is required before transfer, craft, placement or food expense. A stale pre-effect continuation leaves native property unchanged; a stale post-effect continuation preserves exact/conservative AMBIGUOUS evidence and blocks recovery without compensation or replay. Construction MATERIALS/protection/conflict backoff keeps its existing deadline while waiting, rather than extending it on each scheduler turn.

Cargo-safe food preemption owns its non-delivery work's wait revision and navigation. Scheduler suppression of ordinary construction/production must not rewrite that wait to MATERIALS. Native builder-return scenarios run through the actual scheduler, preserving original cargo and allocation identity across walking, full/removed/vetoed receivers, and safe assignment release.

Construction pickup keeps an ARRIVED navigation continuation across paid inventory-scan BUDGET yields without re-reserving its released chunk domain. The native last-slot regression saturates CHUNK_DEMANDS after arrival, then requires the original transfer and exact placement/material accounting within the unchanged deadline. Non-budget outcomes cancel that continuation; native transfer proximity and authority remain independently checked.

Construction may reuse another admitted owner's complete charged site coverage, never native readiness alone. Every required center must remain admitted and entity-ticking at effect guards. The shared-site regression exhausts descriptor capacity, proves analysis proceeds, withdraws the covering owner, and requires no material/cursor change until ordinary domain admission can resume.

Cargo dependency loading is separate from executor authority: clean restore may release an unloaded courier's assignment while retaining UNKNOWN IN_TRANSIT coverage. Reconciliation can load only the original ALIVE citizen/epoch and owned cargo without conflicting assignment; it cannot move, extract, deposit or consume. `deliverySmoke` proves the same courier reloads and delivers the original 16 items exactly once without replacement extraction.

Delivery CAPACITY preflight with no owned delivery or allocated citizen cargo releases the courier after exact navigation/load-domain cleanup; this lets that courier drain stock blocking the original recipient. Cargo-bearing waits retain the original worker. `deliverySmoke` requires one courier to move 64 blocking cobblestone before the original 20 planks progress, without quota/deadline relaxation or artificial inventory removal.

Shutdown first detaches native citizen/admission callbacks, then closes physical owners and charged chunk domains while the core is still RUNNING. Legitimate cancellation bookkeeping completes before STOPPING closes the mutation gate and the verified clean checkpoint is written. Cleanup exceptions propagate; a secondary checkpoint failure cannot overwrite the first scale failure. Existing construction cancel/clean restart fixtures exercise the actual wrapper, not a copied shutdown order.

`dedicatedSmoke` launches a production-only server, reads real metrics, and observes runtime release. Then separate marked disposable instances with missing DTO/clean marker, missing DTO/corrupt marker, and corrupt DTO/clean marker verify startup refusal, command refusal, and unchanged original bytes after a normal stop. The native suite additionally verifies root migration/backup, late death veto, and drop custody; the recovery runner verifies real interruption/restart boundaries.

## Integrated lifecycle and identity-history proofs

```text
cmd /c gradlew.bat :colonyloom-neoforge:integratedSmoke -PcolonyloomIntegratedRoot=<absolute empty owner-marked directory> --no-daemon --console=plain
cmd /c gradlew.bat :colonyloom-neoforge:identityOverflowSmoke --no-daemon --console=plain
```

The integrated runner requires a pre-created regular marker file named `colonyloom-integrated-lifecycle-owner-root`; its trimmed content must be a unique 16–128 character token. The directory must contain only that marker, and the runner never deletes or accepts EULA terms. It creates `saves/`, drives one graphical client through A1 → isolated B → reopened A2, and writes the report plus stdout/stderr under `build/reports/colonyloom/integrated-<timestamp>/`.

The fixture chooses citizen coordinates from the authoritative server terrain, moves the real owner into interaction range through a public command, and transfers seven stairs through the native NPC inventory menu. A2 must restore the exact startup active clock and original UUID/epoch/cargo; subsequent loaded ticks may advance that clock. Every world stops normally with an exact matching clean checkpoint and runtime-release witness. The production summary screenshot is captured from the Minecraft framebuffer under the root's `screenshots/` directory. Creative provisioning is dev-only technical proof, not Gate G.

The identity overflow runner uses marked disposable dedicated worlds and three real JVM phases (`initialize`, `exercise`, `verify`) to prove the scoped 600-history cap, quarantined late retired embodiments, property preservation and clean restart. Its evidence is under `build/reports/colonyloom/identity-overflow-<timestamp>/`. These runners are technical acceptance only; they do not close Gate G or establish playable survival recruitment.

`firstMutationSmoke` uses the public founding command in a virgin marked world: the first mutation creates a durable dirty marker, the canonical colony is still absent from the DTO, then a genuine halt(97) occurs. A same-world restart refuses a new mutation, creates no DTO, and preserves marker bytes after a normal stop. The scenario does not delete a file from an accepted world to substitute for the first-mutation boundary.

## Platform and 300-citizen economy

```text
cmd /c gradlew.bat :colonyloom-neoforge:platformSmoke --no-daemon --console=plain
cmd /c gradlew.bat :colonyloom-neoforge:managementSmoke -PcolonyloomManagementCalibration=true --no-daemon --console=plain
cmd /c gradlew.bat :colonyloom-neoforge:scaleSmoke -PcolonyloomProfile=scale300 -PcolonyloomScaleProfile=<platform/frozen-profile.json> -PcolonyloomScaleStockMeasurements=<storage/world/colonyloom-supply-metrics-calibrate.json> -PcolonyloomScaleNativeStockMeasurement=<storage/world/colonyloom-supply-metrics-calibrate.json> -PcolonyloomScaleViewMeasurement=<management/view-calibration.json> --no-daemon --console=plain
```

Obtain paths from the logs of **current** completed runners, not a previous developer's timestamp. Scale parameters have no local report defaults. Platform baseline → 30 → 100 → 300 are sequential; measured computational counts are frozen at the full-duration 30-citizen barrier. Native graph/storage and graphical view calibration include 300 s warmup + 600 s measurement and positive real unit samples. After revoke/reconnect proofs, `managementSmoke -PcolonyloomManagementCalibration=true` maintains a real server and two clients with four ordinary-ACK views on each. Short exercise/verify phases or ordinary UI smoke are useful for diagnostics, not final scale prerequisites.
Platform/scale fixtures found full 128×128 territories sequentially with one actor, observing the real `FoundingTerritoryValidation.CLIENT_WINDOW_TICKS` between public commands. Production per-tick/per-client admission is not disabled for setup; setup waiting is excluded from warmup/measurement.

Source/config/content hashes of all prerequisites must match the checkout; a mismatch is refused before the load run. Code/content/configuration must not change during measurement. A change between phases also ends the run with refusal. Do not run independent load runs simultaneously on one host; external load is recorded in the report, and other people's processes must not be stopped automatically.

`gradle/acceptance-profile.json` fixes durations, population/scenarios, and existing verification thresholds **before** the run. Current production limits and per-unit measured costs are stored in the frozen profile; zero samples do not calibrate a category. Mean/p95/p99/p999 latency, critical deadline, stock freshness, bounded state, paused clocks, and completion of solvable goals are checked together and do not replace each other.

## Diagnostics are not acceptance

Platform: `-PcolonyloomPlatformDiagnostic=true`, `-PcolonyloomPlatformWarmupSeconds=<seconds>`, `-PcolonyloomPlatformMeasureSeconds=<seconds>`, `-PcolonyloomPlatformScenario=baseline,idle30,...`.

Scale: `-PcolonyloomScaleDiagnostic=true`, `-PcolonyloomScaleWarmupSeconds=<seconds>`, `-PcolonyloomScaleMeasureSeconds=<seconds>`, `-PcolonyloomScaleScenario=economy,...`. Even a diagnostic higher-population run requires a genuinely measured, source-matched prerequisite profile.

A short/partial/diagnostic result is never accepted as a full barrier. Do not turn a failed profile into a passed one by changing thresholds after observation.

Save tail metrics are separate: `SAVE_WORLD` — synchronous `saveEverything`, `SAVE_FLUSH` — synchronous pending-I/O flush, `SAVE_ENCODE` — DTO capture/encode, `SAVE` — the full explicit durable checkpoint, `COMPACTION` — checkpoint/retirement/new snapshot outside the managed tick. Nested timers **are not summed**. Vanilla autosave obtains current owner-thread authority each time; the explicit envelope uses the already captured snapshot. The force/readback/marker order is not weakened to reduce MSPT.

## Preserving evidence

Preserve `manifest.json`, `report.json`, the frozen profile, individual observations/stdout/stderr, release JAR hashes, and a complete checkout/config snapshot. Move runtime reports from ignored build directories to a release archive outside the transient checkout; do not add the server world or development fixtures to the release JAR. After gameplay/effect changes, repeat the relevant crash policies and full profile; an old platform certificate does not prove a new load.
