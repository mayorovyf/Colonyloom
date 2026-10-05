# Colonyloom — verification и acceptance

## Предпосылки

JDK 21 x64; Gradle wrapper из checkout; Minecraft 1.21.1 + NeoForge из `gradle.properties`. Для dedicated fixtures требуется файл с уже принятым владельцем `eula=true`; runner не принимает условия автоматически. Все crash/load сценарии создают новые disposable worlds под `colonyloom-neoforge/build/reports/colonyloom/`. Рабочий мир не использовать.

Windows: `cmd /c gradlew.bat`; POSIX: `./gradlew`. Ниже команды Windows из корня репозитория. Desktop/OpenGL нужен для management/scale UI; отсутствие графического клиента не заменяется model test.

## Быстрый gate

```text
cmd /c gradlew.bat check assemble --no-daemon --console=plain
cmd /c gradlew.bat :colonyloom-neoforge:verificationManifest --no-daemon --console=plain
```

`check` проверяет архитектурные границы, core/model suites и компилирует **все** native GameTest classes/resources. Он не запускает Minecraft. `verificationManifest` записывает hashes source/build-config/content, сведения JDK/OS/CPU-count/RAM и фиксированный `gradle/acceptance-profile.json`; статус NOT_RUN не является acceptance.

## Физические regressions и recovery

```text
cmd /c gradlew.bat :colonyloom-neoforge:runGameTestServer -PcolonyloomGameTestDirectory=build/reports/colonyloom/native-current --no-daemon --console=plain
cmd /c gradlew.bat :colonyloom-neoforge:dedicatedSmoke --no-daemon --console=plain
cmd /c gradlew.bat :colonyloom-neoforge:firstMutationSmoke --no-daemon --console=plain
cmd /c gradlew.bat :colonyloom-neoforge:storageSmoke -PcolonyloomStorageCalibration=true --no-daemon --console=plain
cmd /c gradlew.bat :colonyloom-neoforge:deliverySmoke :colonyloom-neoforge:productionSmoke :colonyloom-neoforge:needsSmoke --no-daemon --console=plain
cmd /c gradlew.bat :colonyloom-neoforge:recoverySmoke --no-daemon --console=plain
cmd /c gradlew.bat :colonyloom-neoforge:managementSmoke --no-daemon --console=plain
```

По умолчанию owner-accepted EULA читается из `colonyloom-neoforge/runs/server/eula.txt`. Иной уже принятый файл: `-PcolonyloomEulaFile=<path relative to colonyloom-neoforge or absolute>`. Не копировать EULA из чужого окружения для обхода согласия.

Required GameTest FAIL, авария без ожидаемого witness, незавершённая работа или отсутствие observations — FAIL. Прочитать итог runner и individual facts; exit=0 сам по себе не доказывает physical outcome.

`dedicatedSmoke` запускает production-only сервер, читает реальные metrics и наблюдает освобождение runtime. Затем отдельные marked disposable instances с missing DTO/clean marker, missing DTO/corrupt marker и corrupt DTO/clean marker проверяют startup refusal, отказ command и неизменность исходных bytes после нормального stop. Native suite дополнительно проверяет root migration/backup, late death veto и drop custody; recovery runner — настоящие interruption/restart boundaries.

## Integrated lifecycle and identity-history proofs

```text
cmd /c gradlew.bat :colonyloom-neoforge:integratedSmoke -PcolonyloomIntegratedRoot=<absolute empty owner-marked directory> --no-daemon --console=plain
cmd /c gradlew.bat :colonyloom-neoforge:identityOverflowSmoke --no-daemon --console=plain
```

The integrated runner requires a pre-created regular marker file named `colonyloom-integrated-lifecycle-owner-root`; its trimmed content must be a unique 16–128 character token. The directory must contain only that marker, and the runner never deletes or accepts EULA terms. It creates `saves/`, drives one graphical client through A1 → isolated B → reopened A2, and writes the report plus stdout/stderr under `build/reports/colonyloom/integrated-<timestamp>/`.

The fixture chooses citizen coordinates from the authoritative server terrain, moves the real owner into interaction range through a public command, and transfers seven stairs through the native NPC inventory menu. A2 must restore the exact startup active clock and original UUID/epoch/cargo; subsequent loaded ticks may advance that clock. Every world stops normally with an exact matching clean checkpoint and runtime-release witness. The production summary screenshot is captured from the Minecraft framebuffer under the root's `screenshots/` directory. Creative provisioning is dev-only technical proof, not Gate G.

The identity overflow runner uses marked disposable dedicated worlds and three real JVM phases (`initialize`, `exercise`, `verify`) to prove the scoped 600-history cap, quarantined late retired embodiments, property preservation and clean restart. Its evidence is under `build/reports/colonyloom/identity-overflow-<timestamp>/`. These runners are technical acceptance only; they do not close Gate G or establish playable survival recruitment.

`firstMutationSmoke` использует public founding command в virgin marked world: первая mutation создаёт durable dirty marker, canonical colony ещё отсутствует в DTO, затем genuine halt(97). Same-world restart отказывает в новой mutation, не создаёт DTO и сохраняет marker bytes после обычного stop. Сценарий не удаляет файл из принятого мира, чтобы подменить first-mutation boundary.

## Платформа и экономика 300 жителей

```text
cmd /c gradlew.bat :colonyloom-neoforge:platformSmoke --no-daemon --console=plain
cmd /c gradlew.bat :colonyloom-neoforge:managementSmoke -PcolonyloomManagementCalibration=true --no-daemon --console=plain
cmd /c gradlew.bat :colonyloom-neoforge:scaleSmoke -PcolonyloomProfile=scale300 -PcolonyloomScaleProfile=<platform/frozen-profile.json> -PcolonyloomScaleStockMeasurements=<storage/world/colonyloom-supply-metrics-calibrate.json> -PcolonyloomScaleNativeStockMeasurement=<storage/world/colonyloom-supply-metrics-calibrate.json> -PcolonyloomScaleViewMeasurement=<management/view-calibration.json> --no-daemon --console=plain
```

Пути получают из логов **текущих** completed runners, не из timestamp прошлого разработчика. Параметры scale не имеют локальных defaults для reports. Platform baseline → 30 → 100 → 300 последовательны; measured computational counts замораживаются на full-duration 30-citizen barrier. Native graph/storage и graphical view calibration включают 300 s warmup + 600 s measurement, положительные реальные unit samples. `managementSmoke -PcolonyloomManagementCalibration=true` после revoke/reconnect proofs удерживает настоящий server и два клиента с четырьмя ordinary-ACK views на каждом. Короткие exercise/verify или обычный UI smoke годятся для диагностики, не final scale prerequisites.
Platform/scale fixtures основывают полные 128×128 территории последовательно одним actor, соблюдая реальные `FoundingTerritoryValidation.CLIENT_WINDOW_TICKS` между public commands. Production per-tick/per-client admission не отключается для setup; ожидание setup не входит в warmup/measurement.

Source/config/content hashes всех prerequisites должны совпадать с checkout; mismatch отказывает до load-run. На время измерения код/контент/конфигурация не изменяются. Изменение между phases также завершает run отказом. Не запускать независимые load runs одновременно на одном host; внешняя нагрузка фиксируется в отчёте, чужие процессы не останавливать автоматически.

`gradle/acceptance-profile.json` фиксирует durations, population/scenarios и существующие проверочные пороги **до** прогона. Текущие production limits и per-unit measured costs хранятся в frozen profile; zero samples не калибруют категорию. Mean/p95/p99/p999 latency, critical deadline, stock freshness, bounded state, paused clocks и завершение разрешимых goals проверяются вместе, не заменяют друг друга.

## Диагностика не acceptance

Platform: `-PcolonyloomPlatformDiagnostic=true`, `-PcolonyloomPlatformWarmupSeconds=<seconds>`, `-PcolonyloomPlatformMeasureSeconds=<seconds>`, `-PcolonyloomPlatformScenario=baseline,idle30,...`.

Scale: `-PcolonyloomScaleDiagnostic=true`, `-PcolonyloomScaleWarmupSeconds=<seconds>`, `-PcolonyloomScaleMeasureSeconds=<seconds>`, `-PcolonyloomScaleScenario=economy,...`. Даже diagnostic higher-population требует genuinely measured source-matched prerequisite profile.

Короткий/частичный/diagnostic результат никогда не принимается за full barrier. Failed profile не превращать в passed изменением порогов после наблюдения.

Save tail metrics разделены: `SAVE_WORLD` — synchronous `saveEverything`, `SAVE_FLUSH` — synchronous pending-I/O flush, `SAVE_ENCODE` — DTO capture/encode, `SAVE` — полный explicit durable checkpoint, `COMPACTION` — checkpoint/retirement/new snapshot вне managed tick. Nested timers **не суммируются**. Vanilla autosave получает текущую owner-thread authority каждый раз; explicit envelope использует уже captured snapshot. Force/readback/marker порядок не ослабляется ради меньшего MSPT.

## Сохранение evidence

Сохранять `manifest.json`, `report.json`, frozen profile, individual observations/stdout/stderr, hashes release JAR и полный checkout/config snapshot. Runtime reports в ignored build dirs переносить в архив выпуска вне transient checkout; не добавлять серверный мир или dev fixtures в release JAR. После gameplay/effect changes повторять нужные crash policies и полный профиль; старый platform сертификат не доказывает новую нагрузку.
