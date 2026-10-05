# Приложение: construction / navigation / chunks / content

Материал к [основному аудиту](audit-2026-10-04.md). Полный read-only отчёт независимого аудитора; учитывает изменённую пользователем навигацию и переданный native результат.

# Аудит slice: навигация, чанки, claims, строительство, content/NeoForge

**База:** docs/architecture.md, rev0.4. Пользовательские изменения в `BoundedGroundSearch.java`, `MinecraftNavigationBackend.java`, `NavigationGameTests.java` учтены как текущая версия. Код не менялся; проверки мной не запускались. Точные выводы отмечены как **[STATIC]** или **[RUNTIME — evidence родителя]**. Неподтверждённые причины прямо помечены **[INFERENCE]**. Changelog не использовался как подтверждение runtime.

## Замечания

### N1 — P1 — текущий реальный GameTest отказ: домен чанков не может быть заменён под protection
**Код:** `colonyloom-core/src/main/java/io/github/kpuctajluk/colonyloom/core/chunk/ChunkDemandManager.java:123-124` (`request`, проверка `old.protectedNow()`); защита домена определяется в `:85-96`. Тест: `colonyloom-neoforge/src/gameTest/java/io/github/kpuctajluk/colonyloom/gametest/ProductionOutputRoutingGameTests.java:87-89`, сценарий `originalNativeKitsContinuePickupWithFourPaidSlotChecksPerTurn`.

**Контракт:** §13 — безопасное сворачивание/смена активной области и сохранение физического груза; §21.2 — работоспособность при ограниченном допуске и продвижение разрешимой цепочки, не только bounds ресурсов.

**Механизм/сценарий:** **[RUNTIME — evidence родителя]** свежий запуск `runGameTestServer`, 123 теста; единственный required fail — `originalnativekitscontinuepickupwithfourpaidslotchecksperturn`, `Protected chunk domain cannot be replaced`, `colonyloom-neoforge/build/reports/colonyloom/audit-gametest-current/logs/latest.log:68,503,962-964`. Остальные 122 без failed mark; runtime штатно остановлен/освобождён (со слов родителя). Код явно отклоняет изменение параметров уже защищённого Demand. **Причина, какой именно owner/параметр изменился и является ли дефект продукта или fixture, не установлена; не приписывать её навигации либо грузу.**

**Эффект:** доказанный текущий регресс/блокировка тестового сценария; по одному логу нельзя утверждать реальный пользовательский ущерб или точную цепочку.

**Рекомендация:** исследовать owner/state старого и нового Demand в этом сценарии; сохранить защиту от потери физического шага/груза, но обеспечить предусмотренный безопасный переход либо скорректировать неверное предположение fixture после подтверждения причины. Не глушить exception и не ослаблять protection без доказательства.

**Оценка:** 1–3 человеко-дня на локализацию и исправление при доступе к сценарию и возможности повторного централизованного GameTest-прогона; если требуется изменение инварианта chunk lease/swap — до 4 дней. Сейчас причина остаётся неизвестной.

### N2 — P2 — shared chunk accounting не отражает долю каждой колонии/полосы
**Код:** `ChunkDemandManager.java:31-33, 202-213`; `Cell` хранит один lease/lane (`:54-59`), а новая квота резервируется только при переходе общего счётчика `references[r]` из 0 в 1 (`:208-210`). Последующие владельцы той же клетки увеличивают refcount, но не создают свой admission-расход.

**Контракт:** §13 требует уникально учитывать пересечения в глобальном лимите **и** учитывать доли колоний; §§6.5/22 требуют видимого admission, который нельзя обходить последовательным созданием удалённых заявок.

**Механизм/сценарий:** **[STATIC]** если Demand одной колонии/полосы первым удержал cell, следующий владелец получает ссылку на уже учтённую клетку, не расходуя свою per-colony/lane quota. Когда первый владелец уходит, сохранённый `Cell.lease` остаётся от первоначальных colony/lane, хотя физическую клетку теперь держит второй. Глобальный счётчик уникальных клеток при этом дедуплицируется правильно; замечание именно о per-colony/lane accounting, не о физическом числе уникальных чанков.

**Эффект:** загрузчик может допустить одну колонию выше ожидаемой доли, а первоначальный владелец может продолжать нести квотный расход без собственной нужды; справедливость и наблюдаемость лимитов искажаются. Масштаб достижимости для конкретной настройки не измерен.

**Рекомендация:** разнести учёт логических требований/долей и глобально уникальной физической клетки. Сохранять глобальный unique charge один раз, но выполнять явную проверку и учёт per-colony/lane на каждом требовании; добавить cross-colony/lane acquire/release и снижение лимита в тесты.

**Оценка:** 1–3 человеко-дня; предположение — ресурсная схема может раздельно выразить unique physical footprint и владельческие квоты без изменения семантики TicketController.

### N3 — P2 — node-cap навигации сводится к `UNREACHABLE`
**Код:** `colonyloom-minecraft/src/main/java/io/github/kpuctajluk/colonyloom/minecraft/navigation/BoundedGroundSearch.java:7-9, 59-72`; `MinecraftNavigationBackend.java:131-137, 175-177`; `colonyloom-core/src/main/java/io/github/kpuctajluk/colonyloom/core/navigation/NavigationService.java:310-319`.

**Контракт:** §§11.1, 11.3 требуют управляемого backend, осмысленной задержки/причины retry; §21.2 требует отличать истощение ресурса от доказанного отсутствия пути/молчаливой блокировки.

**Механизм/сценарий:** **[STATIC]** поиск фиксирует `nodeLimitHit`, при заполнении 8192 узлов пропускает новые узлы и может завершиться с `EXHAUSTED`; backend лишь инкрементирует диагностический счётчик, возвращает `null`, а service назначает `UNREACHABLE` и backoff. Поэтому финальное состояние лимитированного поиска пользовательски не отличимо от исчерпывающего доказательства отсутствия маршрута, хотя диагностический counter различает событие на уровне backend.

**Эффект:** крупный, но проходимый маршрут может надолго отображаться как недостижимый; игроку не показывается, что причина — предел вычисления. Порог 8192 узла и 256 expansions/portion — статические bounds, не нагрузочная гарантия.

**Рекомендация:** сохранить отдельный результат «search budget/node capacity exhausted» до `WorkOrder.Reason`/retry/UI; не повышать предел произвольно. Добавить backend/unit сценарий исчерпания node cap и подтвердить причину ожидания/диагностики.

**Оценка:** 1–2 человеко-дня; предполагается, что существующий контракт причины работы и UI позволяет представить budget exhaustion, иначе ещё до 2 дней на end-to-end отображение.

### N4 — P2 — полная геометрия blueprint синхронна и вне бюджета порций
**Код:** `colonyloom-gameplay/src/main/java/io/github/kpuctajluk/colonyloom/gameplay/construction/ConstructionController.java:35-40, 51-58`; `colonyloom-minecraft/src/main/java/io/github/kpuctajluk/colonyloom/minecraft/construction/MinecraftConstructionGeometry.java:27-54`; `MinecraftConstructionService.java:68-72`; модель `colonyloom-core/src/main/java/io/github/kpuctajluk/colonyloom/core/content/BlueprintDefinition.java:21-36` и `colonyloom-minecraft/src/main/java/io/github/kpuctajluk/colonyloom/minecraft/content/ContentLoader.java:262-267` допускают до 65 536 блоков.

**Контракт:** §§12.2, 21.1, 21.3: ограниченный участок/операции по бюджету, отдельно измерять большие стройки и стоимость подготовки; наличие конечного MAX само по себе не подтверждает приемлемое время одного тика/команды.

**Механизм/сценарий:** **[STATIC]** build path материализует `Target[]` для всего definition, проверяет все цели на build height/world border и проходит их для проверки территории. При старте executor заново вызывает `controller.layout(site)`, вновь трансформируя все блоки и валидируя layout. Эти циклы не потребляют `BLUEPRINT_COMPARISONS`; затем уже supply/сверка/физические действия порционны. Значит один допустимый крупный чертёж создаёт синхронный пик на принятии и ещё один при начале исполнения.

**Эффект:** большие допустимые чертежи способны дать долгий server-thread вызов и спайк памяти; фактическое время/выход за пределы tick budget не замерены, поэтому severity P2, не утверждение о наблюдавшемся лаге.

**Рекомендация:** разделить дешёвую bounded-привязку bounding data/claim и порционную подготовку world-targets; кэшировать неизменяемую трансформацию по pinned digest+rotation либо выдавать итеративные сегменты. Любая схема должна оставлять предварительную проверку границ/территории до необратимых материальных обязательств и не загружать чанки ради анализа. Добавить крупный blueprint сценарий с budget asserts.

**Оценка:** 2–4 человеко-дня при сохранении формата snapshot и лимита 65 536; до 6 дней, если безопасное предварительное claim требует новой транзакции/формата.

### N5 — P2 — нет адаптера сторонних claims/protection с полным субъектом отложенного действия
**Код:** `colonyloom-neoforge/src/main/java/io/github/kpuctajluk/colonyloom/neoforge/NeoForgeItemInteraction.java:25-26, 36-66` (класс прямо документирует отсутствие generic claims-mod adapter; использует `FakePlayer` от имени principal); `colonyloom-minecraft/src/main/java/io/github/kpuctajluk/colonyloom/minecraft/construction/BlockPlacementExecutor.java:104-163`; `MinecraftConstructionService.java:128-146`.

**Контракт:** §§12.3 и 15.1: перед физическим изменением повторно проверять текущие внешние права и выражать владельца колонии как principal и NPC как исполнителя; неподдерживаемый контекст должен отказать, а не обходить защиту.

**Механизм/сценарий:** **[STATIC]** core повторно проверяет территорию/authority и вызывает обычное item-use/event размещение. NeoForge адаптер получает FakePlayer-профиль текущего владельца колонии, переносит на него предметную stack, но событие/защита не получает отдельно исходного NPC как executor; нет конкретного интеграционного моста с third-party claims. Это подтверждает отсутствие выбранной общей интеграции, но не доказывает обход конкретного мода: поведение зависит от его событий/политики.

**Эффект:** владельцы серверов с claims-модами могут получить отказ NPC-стройки либо неоднозначную атрибуцию действия. Возможность неверного разрешения на конкретной protection-системе **[INFERENCE]**; фактического обхода в обследованном коде не установлено. Не путать с vanilla/NeoForge placement checks, которые есть.

**Рекомендация:** до заявлений о совместимости выбрать поддерживаемый claims API/набор модов, адаптировать его к явному principal+executor+target контексту и проверить owner change/revoke/event veto. Для неизвестного API отвечать явной неподдерживаемостью/отказом.

**Оценка:** 2–5 человеко-дней на одну выбранную API и интеграционные сценарии; предпосылка — product решает, какой claims ecosystem входит в MVP. Для нескольких независимых систем оценивать отдельно.

### N6 — P2 — восстановление overlaps claims имеет непорционный квадратичный путь
**Код:** `colonyloom-core/src/main/java/io/github/kpuctajluk/colonyloom/core/spatial/TargetClaimRegistry.java:349-386`; лимиты `:25-46` (до 128 целей, до 128 chunk-links на claim).

**Контракт:** §12.4 требует восстановить владение и конфликты до возобновления работ; §17.2 задаёт staged restore/постепенное возобновление; §§6.5, 21.1 требуют ограниченной стоимости состояния/индексов.

**Механизм/сценарий:** **[STATIC]** `prepareRestore` заранее находит конфликты всего набора вложенными циклами по каждой claim-link и всем claims в chunk bucket. При предельном допустимом размере плотное пересечение даёт порядок до 128×128×128 ≈ 2,1 млн парных посещений; этот цикл не потребляет тиковый `BLUEPRINT_COMPARISONS`. Это лишь верхняя оценка количества итераций, не замер времени.

**Эффект:** загрузка мира с плотным набором сохранённых целей может задержать серверный поток до завершения восстановления; ограниченный admission предотвращает неограниченный размер, но не гарантирует короткий startup.

**Рекомендация:** сохранить quiescent staged/publish atomicity, но формировать conflicts порционно либо строить пересекающиеся кандидаты одной парной процедурой без дублирования по каждому общему chunk-link; перед публикацией завершить и верифицировать полный результат. Добавить предельный benchmark/test, не ослабляя conflict-before-resume.

**Оценка:** 1–2 человеко-дня; предполагается, что restore может сохранять staging state между тиками до возобновления executor.

### N7 — P2 — нет теста поворота целого строительного проекта
**Код:** transform целевых координат и состояния — `MinecraftConstructionGeometry.java:27-49`; `ConstructionController.build/layout` — `ConstructionController.java:35-55`. Покрытие: `BlockPlacementGameTests.java:111-120` проверяет ориентацию placement для четырёх facing; `ConstructionGameTests.java:38-56` — обычное строительство/отмена, а UI-сценарии `ManagementClientScenario.java:272-294` и `ManagementScenario.java:360-364` используют rotation 0.

**Контракт:** §12.1 и §12.3 — экземпляр blueprint имеет transform и реальные физические блоки; §21.1 — реальные Minecraft assertions.

**Механизм/сценарий:** **[STATIC: покрытие не найдено поиском по `src/gameTest` и тестовым сценариям]** тест placement на повёрнутую лестницу не покрывает преобразование абсолютных координат целого layout, `work_origin`/`delivery_buffer`, claim bounds, территории и progression cursor при 90/180/270 градусов.

**Эффект:** конкретная ошибка трансформации проекта может пройти имеющиеся сценарии; нарушение runtime пока не подтверждено.

**Рекомендация:** добавить GameTest для layout+реальной стройки хотя бы на 90 и 270 градусах, проверить marker/barrel, claims, каждую ожидаемую state и отказ до создания work при выходе bounds/territory. Не считать эту дыру доказательством дефекта алгоритма поворота.

**Оценка:** 1–2 человеко-дня при наличии готового GameTest fixture.

## Подтверждённые реализации

- **Навигационный lifecycle:** `NavigationService.java:108-160, 188-228, 238-319` проверяет binding epoch, assignment, goal authority, revisions чанков и готовность до search/apply; новая цель/отмена останавливает старый query. `RETRY` capped; отрицательный поиск временно освобождает ticking domain. Unit tests `NavigationServiceTest.java` покрывают stale epoch/goal/chunk между порциями, cancel, readiness, backoff, capacity wait и справедливость очереди на test backend.
- **Bounded path backend текущей версии:** `BoundedGroundSearch.java:7-10, 35-72` — массивы фиксированной ёмкости, ≤256 expansions за порцию, 8192 nodes/query, 16 concurrent queries. `MinecraftNavigationBackend.java:94-147, 180-204, 245-278, 336-429` ограничивает поиск заранее допущенной entity-ticking областью и повторно проверяет path; реальное движение ограждается физическими checks. Текущая пользовательская версия добавила occupancy cost (`:384-438`) и goal-ring integration scenario (`NavigationGameTests.java:70-153`); выводы выше относятся к текущим исходникам, а не к архивному варианту без этих изменений.
- **Фактический traversal scope:** `MinecraftNavigationBackend.java:421-429` требует сухую полно-высотную поддерживающую поверхность и свободный body; A* ищет только кардинальные соседние клетки с перепадом высоты до одного блока (`BoundedGroundSearch.java:11-13, 50-68`). Поэтому вода, лестницы как опора, climbing, gaps/jumps и сложные способы перемещения не представлены как маршрутные переходы. Это фактическое ограничение первого ground backend, а не само по себе нарушение §11: двухуровневый backend спецификация не требует и измеренная необходимость не доказана.
- **Тикетная модель:** `ChunkDemandManager.java:139-152, 164-180, 202-226, 234-259` строит footprint 5×5 loaded, 3×3 block-ticking и центр entity-ticking для ticking demand, принимает Demand через global limits и строит eviction plan; protected domains исключаются из eviction. `NeoForgeChunkAccess.java:18-67` использует зарегистрированный TicketController и отдельно проверяет LOADED/BLOCK_TICKING/ENTITY_TICKING. `ChunkGameTests.java:23-47` проверяет реальные rings и общий reference release; core tests проверяют лимиты/план admission.
- **Claims:** `TargetClaimRegistry.java:125-217, 238-263, 278-295` индексирует claims по dimension/chunk, сравнивает физическое пересечение, выполняет порционную сверку с revision guard и атомарным grant; `:349-386` staged restore заранее блокирует участников конфликта. `TargetClaimRegistryTest.java:49-130, 219+` содержит cross-colony overlap, touching bounds, same-building конфликт, расширение/re-arbitration и restore. Строительство проверяет grant в `ConstructionController.java:35-42` и до размещения в `MinecraftConstructionService.java:65-67,128`.
- **Blueprint/site:** `BlueprintDefinition.java:21-57` ограничивает число/размер свойств, сортирует модель и вычисляет digest; `ConstructionRegistry.java:1-186` держит immutable pinned definitions, лимиты версий/объёма, monotonic site progress и checkpoint compaction. `ConstructionNbt.java` и `ConstructionPersistenceGameTests.java:34-148` проверяют palette/evidence round-trip, unknown schema retention/blocking и compaction. Рeload меняет доступные definitions, принятый site ссылается на digest закреплённого snapshot.
- **Материалы и физический эффект:** `MinecraftConstructionSupply.java:20-75, 87-180` формирует порции до 64 целевых блоков и real inventory demand/allocations; `MinecraftConstructionService.java:84-160` повторно сравнивает target, подтверждает прогресс лишь после world/item checks, checks claim/readiness и effect evidence. `BlockPlacementExecutor.java:104-158` запрещает block entities/опасные замены, требует настоящий NPC item, актуальную территорию/owner authority и фактическую физическую установку. `ConstructionGameTests` проверяют placement, cancellation, stale commit, budget pauses и native material continuity.
- **Контент:** `ContentLoader.java:59-90, 183-246, 255-286` строго проверяет NBT structure/state/markers/duplicates и caps; ресурсы контента приходят из resource/datapack definitions. Текущая whitelist `BUILDING_BLOCKS` — только `minecraft:oak_planks` и `minecraft:oak_stairs` (`:60-61,184-186`); обязательный runtime blueprint `stair_strip` проверяется в `:132-133`. Отсутствие других штатных blueprint/material types — ограниченность текущего контента, само по себе не нарушение архитектурного инварианта §16.

## Неизвестность и границы среза

- По одному N1 логу неизвестны Demand owner/параметры при ошибке; **не** утверждается, что виноват cargo transfer, ticket eviction, path goal или сам fixture.
- Точные бюджеты A*/occupancy, производительность startup claim restore и construction layout на максимальных blueprint не замерялись в этом срезе. Значения counters/caps не считать доказательством throughput.
- Специальные маршруты для плавания/лестниц/других размеров и дополнительных профессий отсутствуют; будет ли это MVP capability решается областью продукта. §11 допускает локальный стартовый backend и отложенную иерархию при отсутствии измеренного выигрыша.
- В NeoForge есть vanilla/placement событийный путь, но универсальная совместимость с внешними claim mods не доказана; поддерживаемый набор protection APIs требует product decision.
- Конструкция использует одну высокоуровневую работу на site; доступные тесты реального блока/восстановления не доказывают завершение всех механик, перечисленных в §2; это граница продукта, не предлагаю молча расширять этот slice.
- **Покрытие §21.2:** stale routes/chunk sharing/claims/build/cancel/persistence представлены разными unit/GameTests. Unit navigation пользуется test double и не верифицирует A* — это компенсируется реальными GameTests, включая goal-ring current addition. Construction full-rotation scenario не обнаружен. Parent сообщал, что свежий compileGameTestJava прошёл; фактический актуальный GameTest run имеет 122 non-failing и 1 required failure N1. Я не запускал повторный прогон.

## Оценка остатка этого slice до приемлемого MVP

При условии фикса текущего наблюдаемого N1, решения per-colony admission, диагностической причины node cap, bounded geometry, выбора внешнего protection API и добавления rotation coverage: **9–20 человеко-дней** (N1: 1–3; N2: 1–3; N3: 1–2; N4: 2–4; N5: 2–5; N6: 1–2; N7: 1–2; часть тестирования может выполняться параллельно с кодом). Диапазон не включает новые movement modalities или content parity; если они выбраны, ориентир **дополнительно 2–5 дней** на выбранный traversal class и **2–4 дня** на ограниченный пакет новых валидируемых блоков/контента. Оценки предполагают сохранение четырёхслойной границы и существующих форматов, без полной переписи, и должны быть уточнены после локализации N1 и выбора protection API. Это только оценка текущего slice, не общий остаток до MVP.