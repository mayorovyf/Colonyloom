# Colonyloom — эксплуатация и восстановление

## Поддержанная платформа

Minecraft Java 1.21.1, NeoForge версии из `gradle.properties`, JDK21 x64. Один release JAR из `colonyloom-neoforge/build/libs/`; core/gameplay/minecraft входят в него. Development fixture mod и crash/scale сценарии исключены. Integrated и dedicated используют серверный runtime; performance acceptance привязана к manifest конкретного host, не является гарантией любого TPS.

### Хранилища и защиты

Поддержаны native chest, trapped chest (single/double) и barrel. Все capability contexts обязаны доказывать точное отображение слотов через native InvWrapper. Generic IItemHandler, виртуальные/модовые контейнеры и aliases без доказанной физической идентичности **не поддержаны**. Смена/перестановка контейнера сбрасывает доверие к индексу; identity repair выполняется явно, не отключением guards.

Vanilla world-border/permissions и NeoForge placement checks применяются перед физическими действиями. Colonyloom transfer/craft/food veto events — hooks для интеграций, не универсальная поддержка claims mods. Конкретный claims adapter принимается только после physical проверки всех effects, offline owner и revoke. Unsupported backend запрещается, не выдаёт фиктивный stock.

### Неактивные жители и ожидания

UUID жителя, UUID сущности и binding epoch различны. Не загруженный NPC не заменяется новым; paused own-time не догоняет голод после загрузки. BLOCKED/UNKNOWN/recovery entity сохраняет имущество в карантине. Выдача inventory и команды проверяют актуальные права, а не прежнюю роль соединения.

CAPACITY означает невозможность безопасно разместить cargo. Освободить подходящий слот указанного destination/return/source buffer и дождаться перепроверки. Cargo остаётся у реального курьера; отмена заказа не удаляет вещи. SEARCH_EXHAUSTED означает вычислительный предел поиска, не доказательство отсутствия пути. Разгрузить workload/упростить доступ и повторно наблюдать работу, не принимать cargo доставленным вручную.

Membership: не более **64 non-owner** участников; замена rank и перенос ownership существующему участнику разрешены при полном списке, рост сверх cap отказывает до revisions. Founding: до 128×128 cells, общий предел 16384 физических проверок за server tick, на игрока 16384 за 20 ticks; отказ также расходует allowance. До полной проверки нет colony publication и новых tickets.

### Текущий технический bootstrap

Игровой Gate G ещё не принят: автономных wood/food участков и survival recruitment нет. Не выдавать этот release за playable survival MVP.

1. Игрок основывает территорию через `/colonyloom ui` либо `/colonyloom colony create "Имя" <from x y z> <to x y z>`. Область должна быть уже доступна; founding не грузит отсутствующие чанки. В UI выбрать свои границы и дождаться принятого результата.
2. Для **технического среза** оператор создаёт исходных жителей: `/colonyloom citizen create <colony UUID> <x y z>`. Это существующий op-only bootstrap, не обещание normal-player найма.
3. Поставить native chest/barrel и crafting table; зарегистрировать склад `/colonyloom storage register <colony UUID> <x y z> warehouse`, мастерскую `/colonyloom building register <colony UUID> <table x y z> <inventory x y z>`. Назначить carpenter и workplace, builder и courier через UI либо `citizen assign`/`citizen workplace`. Поддержанные storage roles: `warehouse`, `workshop`, `construction`, `return`.
4. Внести реальные oak logs/planks и bread. Запустить `colonyloom:stair_strip` в UI или `/colonyloom build <colony UUID> colonyloom:stair_strip <x y z> <0|90|180|270>`. Материалы проходят общий supply/production/delivery; fixture выдача в production отсутствует. Еду в техническом срезе обеспечивает игрок.
5. При ожидании читать work reason и указанный физический buffer; освобождать подходящее место, не редактировать cargo или effect evidence. После `work cancel` дождаться безопасной разгрузки. Нормальный `stop` создаёт согласованный clean checkpoint; авария требует operator recovery ниже.

`/colonyloom ui <colony UUID>` открывает доступные адресные страницы; owner/manager могут управлять, viewer только читать. Member/owner команды принимают профиль игрока: `/colonyloom member set <colony UUID> <player> <manager|viewer|none>`, `/colonyloom owner set <colony UUID> <player>`. Server metrics требуют op: `/colonyloom metrics server`; colony metrics требуют права чтения.

## Сохранения и backup

DTO `world/data/colonyloom.dat`, marker `world/data/colonyloom-session.nbt` и native Minecraft region/entity data — части одного checkpoint. До upgrade сохранить полный остановленный мир, server config и прежний release JAR. Не копировать только DTO поверх активного Minecraft world.

Virgin world допускается лишь без DTO **и** marker. При существующем marker и отсутствующем/испорченном DTO мод не создаёт пустую колонию и не перезаписывает потерянное состояние. Прекратить сервер, восстановить согласованный checkpoint из backup, затем выполнить проверку. Удаление marker для обхода отказа уничтожает признак прежних обязательств и не является recovery.

Future/неподдержанная root schema отказывает безопасно. Unknown вложенные записи сохраняются opaque; идентифицируемые зависимости блокируются, unrelated colonies не должны блокироваться автоматически. Operator report ограничен metadata, raw NBT клиентам не выдаётся.
Known binding observations whose citizen is opaque remain original NBT alongside that citizen, not live UNKNOWN identities. Their lifetime cap is checked against the original colony scope; truly unidentified observations retain the separate 600-row cap. Native embodiments of retained citizens remain quarantined and do not republish duplicate binding rows on load/unload. Restoring the supported content restores the retained history; deleting retired UUIDs is not recovery.
Текущая root schema — **2**. Конкретный upgrade **1 → 2** сохраняет старые records; hash-only death witness остаётся `UNKNOWN`, UUID drops задним числом не выдумываются. Exact death witness имеет nested schema 1 и ограничен девятью cargo slots и девятью опубликованными drops. Metadata report: до 32 rows, type/schema/object/dependency/reason, opaque count и признак truncation.


Поддержанная миграция сохраняет исходный compressed checkpoint в `world/data/colonyloom-backups/<checkpoint UUID>-v<source root schema>.dat` до перезаписи и проверяет неизменность существующего backup. Конфликт bytes отказывает до writable attachment. Downgrade выполняется восстановлением **полного** backup прежнего мира/config/JAR, не старым JAR поверх нового DTO. Автоматической потери unknown данных, downgrade-conversion или replay нет.

## Operator recovery

После аварии dirty/undefined marker блокирует затронутое состояние; обычный clean shutdown не подтверждает его автоматически. Использовать `/colonyloom recovery inspect <colony UUID>`, затем при необходимости explicit bind и `/colonyloom recovery accept-world <colony UUID> <checkpoint UUID>`.

Inspect показывает canonical effects/assignments и actual property. Перед bind должны быть загружены известные конкурирующие воплощения; bind повышает epoch и оставляет чужое имущество в quarantine. Unloaded/stale observations не принимаются.

Перед accept повторно проверяются checkpoint, inventory, construction world и effect witnesses. Изменённое/подобранное cargo/drop после inspect требует нового inspect. UNKNOWN означает отсутствие доказанного исхода, не команду восстановить вещи. Death recovery не создаёт предметы и не повторяет публикацию drops; политика допускает source-only loss. Оператор принимает actual world, не обещанную экономику.

Identity history ограничена **600 воплощениями на колонию**, отдельно 600 неизвестными воплощениями; retired UUID входят в lifetime cap, общий envelope 2400. Переполнение известной колонии блокирует только её. Неучтённое воплощение сверх cap остаётся quarantined и должно быть устранено до accept-world; retired UUID вручную не удалять: поздняя загрузка может дублировать имущество. Использовать полный backup; сторонний inventory editor не подтверждает сохранность.

Порядок при overflow: остановить изменения и сохранить полный backup; осмотреть history, `unrecordedEmbodimentCount` и физическое имущество каждого конфликтующего NPC. Не удалять сущность с неизвестным/непустым inventory ради снятия блокировки: quarantined cargo не становится свободным stock. Если сохранность нельзя доказать, восстановить согласованный backup; inspect/accept не компенсируют удалённые предметы.

Для ALIVE жителя recovery требует загрузки **всех** известных воплощений, включая retired: историческая строка сама по себе не доказывает отсутствие физического имущества. Для DEAD/REMOVED записи требование живого воплощения не применяется; tombstone и retired UUID сохраняются, поздняя загрузка остаётся quarantined. После устранения неучтённого конфликта выполнить новый inspect и accept с текущим checkpoint. Полный lifetime cap не освобождается: новый найм/новое воплощение в исчерпанной колонии по-прежнему отказывает.

Техническое доказательство `identityOverflowSmoke` обязано использовать marked disposable world и три настоящие dedicated JVM-фазы (`initialize`, `exercise`, `verify`) с неизменными source/config/content hashes. Фиксировать public command IDs, `identityHistory=600/600`, конфликтующий UUID и retained property; нормальная остановка должна сохранить clean checkpoint. После остановки физическое имущество retired UUID проверяется через native entity-region NBT, поскольку `ServerStoppedEvent` может наступить после выгрузки live entities. Это дополняет, а не заменяет, обычный operator inspect/accept.

## Проверка выпуска

Команды, prerequisites, fixed acceptance и archive layout: [verification.md](verification.md). `check` не доказывает физику, прежний PASS не доказывает текущий код; статус игрового выпуска определяется gates [development-plan.md](development-plan.md).
