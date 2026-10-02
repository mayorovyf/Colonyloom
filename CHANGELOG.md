# Changelog

## 0.1.0 — implementation in progress

### Stage 01 — runnable platform

- Java 21 multi-module build: pure-Java core/gameplay, vanilla Minecraft integration, NeoForge composition root.
- Pinned Gradle 9.2.1 (distribution SHA-256), ModDevGradle 2.0.148, NeoForge 21.1.252, NeoForm 1.21.1-20240808.144430, Parchment 2024.11.17.
- One server-thread-owned runtime per MinecraftServer; explicit start/tick/shutdown; stopped integrated worlds are released. Client setup is isolated from dedicated loading.
- One distributable `colonyloom-neoforge/build/libs/colonyloom-0.1.0.jar`; development scenarios are a separate, excluded source set. Four dev runs: client, client2, server, gameTestServer.
- Verified: wrapper `build`, lifecycle regression tests and standalone lifecycle smoke; dev dedicated startup and console `stop`; installed NeoForge dedicated instance with only the release JAR and normal shutdown; two new integrated worlds in one actual graphical client, each released with `activeRuntimes=0` before the next session.
- Verification host: Windows x64, Intel i5-12500H (12 cores/16 logical), 34,050,289,664 bytes RAM, Eclipse Temurin 21.0.12.1+1-LTS. This stage does not establish NPC capacity or economy behavior.
- Local visual evidence: `colonyloom-neoforge/build/client-worldA.png` and `client-worldB.png`; runtime evidence: each instance's `logs/latest.log`. Generated worlds, logs and screenshots are not release resources.

Run from repository root with JDK 21: `./gradlew build` (PowerShell: `.\gradlew.bat build`). Minecraft dev tasks belong to `:colonyloom-neoforge`. Dedicated instances require the environment owner's accepted Minecraft EULA; builds do not accept it automatically. `runServer` forwards console input so `stop` performs normal shutdown. GameTests are added with stage 02; an empty stage-01 suite is not identity verification.
