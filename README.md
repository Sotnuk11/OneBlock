# OneBlock

A full game mode for Minecraft servers, written in **Kotlin** for **Paper / Folia**. Players mine a single "core" block on a private island, level up, trade on a player-driven market and fight through co-op dungeons.
Current version: `1.0.0-rc.7` (release candidate).

## Features

- **Private islands**: one persistent void world per owner, co-op members and guests with separate permissions, protected area and border logic
- **Hub generator**: builds a fantasy-style central hub with preview, backup, resume and rollback
- **Player economy**: Bazaar with an order book (instant and limit orders, escrow, partial fills, taxes), an Auction House (buy-it-now and bidding with refunds) and an NPC merchant with buy-back
- **Dungeons**: co-op mine for 1-4 players with bosses, plus **endless arenas** with a boss every 5 waves and per-group-size leaderboards
- **Combat gear**: nine weapon mechanics (melee, ranged, magic) and armor sets with a guaranteed-drop system
- **Progression**: quests, guild orders, island events, room rewards with virtual chests and a claim window
- **Admin tooling**: paginated admin menu, evacuation, world backup and reset with explicit confirmation, hot reload with validation
- **Resilience**: missing config files are recreated automatically; reload falls back to the last good configuration

## Engineering highlights

- **PostgreSQL storage** with a serialized background writer, immutable snapshots, changed-row upserts, JSONB patches for large profiles and an advisory lock so only one backend writes the database. A failed write stops mutations instead of silently falling back.
- **Safe item handling**: an inventory guard with handoff journal prevents duplication and loss during trades, rewards and market claims, including disconnects and deaths.
- **Folia-aware concurrency**: region-thread safe scheduling, no blocking calls on game threads.
- **Tests**: 119 test files (JUnit 5, Kotlin Test) including integration tests that start a **real embedded PostgreSQL** instance, balance simulations and a "golden path" release test.
- **Documentation and migrations**: versioned schema, upgrade and rollback procedures, player and admin guides (kept outside this repository).

## Tech stack

Kotlin 2.x, JDK 25, Gradle (Kotlin DSL, Shadow), Paper API 26.1.2 / Folia, PostgreSQL 42.7 JDBC, Gson, JUnit 5, embedded PostgreSQL for tests.

Server-side dependencies: Worlds 4.5.0+ (per-island worlds) and FancyNpcs 2.11 (NPC menus).

## Build

```bash
./gradlew clean build        # runs the tests and produces build/libs/one-block-<version>.jar
./gradlew runServer          # optional: local Paper test server
```

## Configure

1. Install `Worlds` and `FancyNpcs` on the server.
2. Copy `src/main/resources/.env.example` to `plugins/one-block/.env` on the backend and fill in the PostgreSQL connection (use a dedicated database and user).
3. Start the server once; default `config.yml`, menus and language files are created automatically.
4. Set the hub with `/ob admin sethub` or generate one with the hub generator.

## Status

This is a release candidate. The repository contains a JUnit test suite (run locally with Gradle, not in CI), but a large-scale human playtest has not been done yet, so balance values may still change.

## Project layout

```
src/main/kotlin    plugin code (islands, market, trade, dungeon, combat, rewards, storage, admin, ...)
src/main/resources default configs, menus, language files, SQL schema
src/test/kotlin    unit and integration tests
resource-pack      custom item models and textures
map-design         island design generator scripts
scripts            build and visual verification helpers
```
