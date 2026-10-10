# Database

PostgreSQL 16, SQLAlchemy 2 (async), Alembic migrations. SQLite is used by the
test suite only.

## Schema

```
users ──┬── user_preferences (1:1)
        ├── tackle_items
        ├── fishing_spots ──┐
        ├── fishing_sessions ◀┘ (spot_id, SET NULL)
        │      ├── session_observations
        │      ├── weather_snapshots
        │      ├── ai_recommendations
        │      └── catches ── catch_photos
        ├── catches  (also user-owned directly, so a catch survives
        │             session deletion)
        ├── tool_executions
        └── conversation_summaries
```

12 tables, 24 indexes.

### Tables

| Table | Holds |
|---|---|
| `users` | email, bcrypt hash, display name, active flag |
| `user_preferences` | language, units, **location_precision**, defaults, voice, verbosity, camera fps |
| `tackle_items` | category, name, weight range, line rating, styles, target species, `ai_identified` |
| `fishing_spots` | name, optional coordinates, `location_precision`, structure notes, `is_private` |
| `fishing_sessions` | status, start/end, location, targets, style, tackle used, notes, generated `summary`, denormalised `catch_count` |
| `session_observations` | kind (bite / missed strike / condition change / note), text, timestamp, `from_ai` |
| `catches` | `species_key` + `species_label`, `ai_confidence`, `ai_reasoning`, `species_confirmed_by_user`, length, weight, released, lure, technique, time, location, weather summary |
| `catch_photos` | local URI, optional remote key, primary flag |
| `weather_snapshots` | air/water temp, wind, gusts, direction, pressure, cloud, precipitation, **wave height, swell period**, summary |
| `ai_recommendations` | what was advised, the reasoning, the inputs, whether a catch followed |
| `tool_executions` | audit: tool, validated args, outcome, duration, live session ref |
| `conversation_summaries` | durable compact memory of a Live conversation |

---

## Design decisions

### String UUID primary keys

```python
id: Mapped[str] = mapped_column(String(36), primary_key=True, default=new_uuid)
```

Chosen over `bigserial` so the **Android client can create rows offline** and
keep the same id after syncing. With sequential server ids, every offline write
would need id remapping across the local database.

### Species: key + label

`species_key` is a stable machine key (`dicentrarchus_labrax`); `species_label`
is what the user sees. The same fish logged as "levrek", "seabass" or
"Sea Bass" normalises to one key, so history queries work regardless of
wording. Unknown species become `unknown_<slug>` rather than NULL — an
unrecognised fish is still recorded consistently and still matches later.

### AI provenance is a first-class column

`ai_confidence`, `ai_reasoning` and `species_confirmed_by_user` exist so the UI
can always distinguish an unreviewed model guess from a fact the angler
asserted. Correcting the species sets `species_confirmed_by_user = True` **and
clears `ai_confidence`**, because that number no longer describes the row.

### Location precision is stored, not just applied

Every table that carries coordinates also carries `location_precision`, so we
know what the user consented to at write time. With `approximate`, coordinates
are rounded to two decimals (~1 km) **before insertion** — not just before
display. See [PRIVACY.md](PRIVACY.md).

### `UtcDateTime`

```python
class UtcDateTime(TypeDecorator):
    impl = DateTime(timezone=True)
```

Postgres `timestamptz` returns timezone-aware datetimes; SQLite returns naive
ones. That asymmetry produced a real crash — `can't subtract offset-naive and
offset-aware datetimes` when computing a session's duration — that appeared
only under the test suite. Normalising in one type decorator fixed it for every
model, query and service on both backends.

### Denormalised `catch_count`

Kept on `fishing_sessions` so history lists and the memory service avoid
counting per row. Recomputed by `_recount_session()` after any catch insert or
delete, so it cannot drift.

### Plain strings instead of Postgres enums

Enum-ish columns are `String` with a Python `StrEnum` in front. Postgres enums
are painful to migrate (`ALTER TYPE` is not transactional in older versions)
and this app gains nothing from them.

---

## Indexes

Each one answers a real query:

| Index | Query it serves |
|---|---|
| `ix_users_email` (unique) | login |
| `ix_tackle_user_category` | the Tackle screen, grouped by category |
| `ix_session_user_started` | session history, newest first |
| `ix_fishing_sessions_status` | "is a session running?" on every Home load |
| `ix_catch_user_species_time` | **the fishing-memory query**: this user, this species, newest first |
| `ix_catches_session_id` | a session's catches |
| `ix_tool_exec_user_time` | audit review |
| `uq_spot_user_name` | one spot per name per user, so upsert is safe |

---

## Migrations

```bash
make migrate                       # alembic upgrade head
make migration m="add tide table"  # autogenerate
make downgrade                     # back one revision
```

Alembic runs synchronously against a sync URL derived from `DATABASE_URL`, so
there is one source of truth for the connection string and no credentials in
`alembic.ini`.

Two tests guard the schema:

* `test_migrations_match_models` — fails if the models have drifted from the
  migrations. Without it, a new column works on a freshly-created test database
  and then fails in production, where the schema comes from Alembic.
* `test_migration_is_reversible` — `upgrade head` then `downgrade base` must
  leave no tables behind.

### No destructive fallback on Android

```kotlin
Room.databaseBuilder(context, FishMateDatabase::class.java, NAME).build()
```

Deliberately no `fallbackToDestructiveMigration()`. The local database holds
the angler's **unsynced** catches; wiping it on a schema bump would be data
loss. Future versions must ship a real Room migration.

---

## Cascades

| Relationship | On delete |
|---|---|
| user → everything owned | `CASCADE` — account deletion must be complete |
| session → observations, snapshots, recommendations | `CASCADE` |
| session → catches | `SET NULL` — a catch outlives its session |
| spot → sessions | `SET NULL` — deleting a spot must not erase history |
| catch → photos | `CASCADE` |

`DELETE /api/v1/me` relies on the first row: the cascade is what makes the
privacy promise real rather than aspirational.
