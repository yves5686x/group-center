# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Spring Boot 4 backend (Kotlin 2.3 + Java 25, Gradle Kotlin DSL) for managing a group of GPU servers. Agents on each machine (the companion project [nvi-notify](https://github.com/a645162/nvi-notify)) report heartbeats, GPU tasks, and messages to this backend, which stores/analyzes them and pushes reports and alarms to WeCom / Lark / DingTalk bots. Companion projects: `group-center-client` and `group-center-dashboard` (the frontend, which consumes `/web/**` endpoints here).

## Commands

```bash
./gradlew bootRun                                  # run locally (port 15090, dev profile)
./gradlew build                                    # build
./gradlew test                                     # run all tests
./gradlew test --tests "com.khm.group.center.service.CacheTest"        # single test class
./gradlew test --tests "com.khm.group.center.service.CacheTest.methodName"  # single test method
./gradlew dependencyUpdates                        # dependency update report (rejects non-stable versions)
docker compose up                                  # full stack: app :15090 + MySQL :15096
```

- `./run.local.sh` (gitignored, local-only) shows the env vars needed for real local integration testing — worth reading before debugging "empty data" issues.
- Requires JDK 25 (`sourceCompatibility`/`jvmTarget` = 25). On macOS the repo assumes e.g. `/opt/homebrew/opt/openjdk@25`.
- `settings.gradle.kts` pins Aliyun Maven mirrors as primary repos — dependency resolution is tuned for a China network.
- MySQL: schema auto-initializes from `src/main/resources/db/mysql/init-schema.sql` (`spring.sql.init.mode: always` with `continue-on-error: true`).
- Tests run hermetically on in-memory H2 (`src/test/resources/db/h2/init-schema.sql`, a port of the MySQL schema) — `./gradlew test` alone is enough; only `DruidTest` needs `SPRING_DATASOURCE_URL` and skips without it.

## Architecture

### Mixed Java + Kotlin, one package tree

Both `src/main/java` and `src/main/kotlin` compile into the same packages under `com.khm.group.center`. Most code is Kotlin; Java remains in `config/spring` (interceptor registration, FastJSON config), `security` (SM crypto helpers), and `message/webhook/wecom`. Lombok is used in Java sources.

### Request flow and URL conventions

- `/api/client/**` — agent-facing endpoints (heartbeat, GPU task upload, monitoring, config fetch). Guarded by `ClientAuthInterceptor` (registered in `java/.../config/spring/interceptor/InterceptorConfigurer.java`): access-key check plus IP whitelist; localhost and docker/podman ranges (172.*, 10.8*) bypass when `MACHINE_AUTH_REMEMBER_IP` is on. Auth responses use `AuthResponse` with custom SM3-based JWT (Tencent Kona, 国密 SM3/SM4) — no Spring Security.
- `/web/**` — dashboard-facing endpoints (dashboard stats, bot push, admin, user info, public query).
- `/web/open/realtime/**` — realtime aggregation layer: a pull-through in-memory cache that pulls data from agents on demand (TTL `realtime.cache-ttl-seconds`, stale fallback after `stale-threshold`). Agent origin comes from `REALTIME_AGENT_BASE_URL` when machine `apiUrl` values are relative paths.
- Layering: `controller/` → `service/` → `db/` (MyBatis-Plus mappers + models). Core table: `gpu_task_info`. Query features live in `db/query/` + `datatype/query/` (a small query-DSL of fields/operators/filters).

### Configuration: layered model (each key has exactly one authoritative source)

| Layer | What lives there | Where |
|---|---|---|
| L1 base | Defaults for ALL Spring-visible config | `application.yml` — the authoritative source per key |
| L2 profile | Only profile deltas (dev swagger, debug logging) | `application-{dev,docker,debug}.yml` |
| L3 test | Test-only config (H2, Druid fail-fast) | `src/test/resources/application-test.yml` + composed annotation `@H2DatabaseTest` |
| L4 deploy/secrets | Passwords, paths, toggles | env vars + `FileEnv.toml` + Dockerfile/compose |
| L5 data | Business data (machines, users, bot keys, proxies) | `Config/` YAML files, pointed to by `CONFIG_*_PATH` env vars |

**Precedence** (high → low):

- Spring chain: docker.start.sh derivation > env vars > profile yml > `application.yml`
- Test chain: inline properties in `@H2DatabaseTest` > env vars > `application-test.yml` (test chain always beats the Spring chain)
- `ConfigEnvironment` chain: `FileEnv.toml` > OS env > code default — **this chain is isolated from the Spring chain**; values here never affect `spring.datasource.*` etc.

Rules of thumb:
- `application.yml` is the single home for every Spring key; `@Value` in code must not carry a default literal (`@Value("\${key}")`, not `@Value("\${key:default}")`) — a missing key must fail loudly at startup. `application-docker.yml` must stay in sync: `docker.start.sh` replaces the classpath config with it (`--spring.config.location`), so it carries the full base key set.
- Tests never need an external MySQL or env vars: Spring Boot tests use `@H2DatabaseTest` (`test/.../test/H2DatabaseTest.kt`), which pins BOTH the H2 url and driver inline — do not move those into `application-test.yml` (env vars would override the url but not the driver → H2-driver-vs-MySQL-url mismatch → Druid create-loop hang). The only external-DB test, `DruidTest`, is guarded by `@EnabledIfEnvironmentVariable(named = "SPRING_DATASOURCE_URL")`.
- `ConfigEnvironment` (`kotlin/.../config/env/ConfigEnvironment.kt`) — static companion with ~22 keys (full list in its header comment). Machine list lives in `Config/Machine/*.yaml`, NOT the database; `Config/Bot/bot-groups.yaml` is shared by `BotPushService` and `LarkBotConfig` via the `bot.config.file` property. In Docker the whole `Config/` dir is a mounted volume.

### Messaging and scheduling

- `message/MessageCenter` — coroutine-based queue (`Dispatchers.IO`) that drains `MessageItem`s and fans out to webhook senders in `message/webhook/` (WeCom, Lark, DingTalk). Per-machine webhook config comes from machine/user YAML.
- `task/` — schedulers (machine ping checks, proxy health check, report generation/push). `service/ReportPushService` + `service/cache/ReportCacheManager` generate and cache daily/weekly/monthly/yearly reports; toggles live in `ConfigEnvironment` (`REPORT_*_ENABLE`, `ALARM_*_ENABLE`).

### Conventions

- Commit messages are written in Chinese (e.g. `feat: 新增实时数据同源聚合层`).
- JSON naming for responses is normalized (see `FastJsonConfigurer.java` and the `ResponseJsonNamingTest` guard) — keep response models consistent with that test.
- `writeVersionProperties` Gradle task regenerates `src/main/resources/settings/version.properties` on every `ProcessResources`; the version comes from `build.gradle.kts` `version`.
- `src/test` mixes Kotlin and Java tests; some (WeCom/Lark bot tests) hit live external services.
