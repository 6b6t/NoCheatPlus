# Set-back telemetry

Disabled by default. This is an observer: check actions, thresholds, exemptions, set-back locations and teleport decisions are unchanged. No data is sent over the network.

## Configuration

In `plugins/NoCheatPlus/config.yml`:

```yaml
telemetry:
  enabled: true
  sample-percent: 1.0
  move-sample-real-percent: 1.1
  queue: 50000
  min-free-bytes: 20000000000
  max-folder-bytes: 5000000000
  folder: ncp-telemetry
  recent-ticks: 100
```

This example uses the v2 speed-gated proposal of 1.1% real-player sampling. Defaults match this block except `enabled: false` and `move-sample-real-percent: 0.0` (opt-in). Both sampling percentages accept finite values in 0–100; queue accepts 1–1,000,000. Thresholds are bytes (20 GB free and 5 GB folder; decimal bytes by default). `recent-ticks` is measured in the player's entity ticks, not a global Folia tick. Folder is resolved relative to the server working directory (the worker data-volume/PVC root). Absolute paths within that root are also accepted. It must be a dedicated descendant outside `plugins/`; empty paths, the root itself, escapes outside the root and symlink destinations/ancestors are refused. Reload closes the old observer and applies the new configuration.

Worker identity comes from `net.blockhost.fox.worker.Worker.getWorkerName()`, initialized from Fox's `--worker-name`. No hostname, coordinator name, node name or region name fallback is used. Optional `FOX_WORKER_NAME`, if present, must agree with Fox. Missing/unsafe/conflicting identity disables telemetry with a warning. Valid names match `[A-Za-z0-9_-]{1,100}`.

## Files and worker isolation

Files are relative to this worker's server data-volume root:

`ncp-telemetry/<worker>-YYYYMMDD-HH.jsonl`

UTC hours, UTF-8, one JSON object per line. Player records use `v: 2`; unchanged writer counters use `v: 1`. Rotation gzip-compresses the old hour to `.jsonl.gz`; the current hour stays open. Shutdown leaves the current hour as JSONL, and a subsequent startup compresses closed older hours. Same-hour restarts append safely; gzip preserves previous compressed segments as concatenated gzip members. A `<worker>.lock` prevents competing writers of the same identity in one folder. `.jsonl.gz.tmp` is a temporary compression file. Only this worker's matching telemetry files are compressed or removed; no legacy diagnostic files are changed.

Fox workers have separate data PVCs. Their launcher copies plugin data from the primary with `rsync -a --delete`. The production folder `ncp-telemetry/` is outside `plugins/`, so the sync neither copies primary archives nor deletes secondary history. No chart change is required. The working directory must be this worker's own data-volume root, as in the Fox launcher; do not configure a shared volume.

## Privacy boundary

No player names, account UUIDs, world identifiers or absolute positions reach the writer in v2 player records. The random `player` token is unrelated to the account and resets on quit, restart or reload; it permits only temporary within-observer linkage. UUIDs remain internal for the existing hourly sampler and cached bot classification. This is data minimization, not a guarantee against inference from timing or a distinctive movement pattern.

Setbacks use a fresh integer block frame for every record: the origin is the floored original `from` and is never serialized. `from` retains its fractional position within that block; `to`, history and all block origins are offsets from that same private origin. Move `pos` remains the event displacement from its original `from`. Coordinates and angles are rounded to 0.001. There is no persistent spatial anchor to reconstruct global positions across records. All block origins, including inherited CreativeFly/check-cache origins, are copied into the same anonymous block frame; originals are never mutated. Absolute positions remain internal to the owning-region move context and bounded accepted history; none reaches the writer.

## Common fields

Player records include `v`, `kind`, `time` (epoch milliseconds), `worker`, `session` (writer UUID, changes on restart/reload), `player` (random connection/observer-local token), `bot`, `bot_meta`, and `tempbot`.

`bot_meta` is the effective cached LuckPerms `6b6t-bot` metadata; `tempbot` includes primary/inherited active groups resolved through current LuckPerms query options. Results are cached for 30 seconds and removed on quit. `bot` combines both markers. Unknown/unloaded/unavailable LuckPerms state is `null`, never silently classified as human. Analysis can exclude `bot: true` and treat unknowns separately. No permissions or wildcard-based bot inference, blocking user loads, or remote calls are used.

`move_id` identifies a processed movement scope within `worker` + `session`. More than one rejecting check can report the same movement; count rates per check, and use this ID to deduplicate movement incidents. Player and vehicle checks are separate processed scopes, including vehicle checks nested inside a player move.

## Set-back records (`kind: setback`)

- `check`: lowercase NCP CheckType name (`moving_survivalfly`, `moving_creativefly`, `moving_nofall`, `moving_passable`, `moving_morepackets`, `moving_vehicle_envelope`, fight/blockinteract types). Direct policy corrections use `moving.listener`, `moving.vehicle`, `moving.nofall`, `fight.listener`, or `blockinteract.listener`; tags identify the path.
- `tags`: existing NCP sub-tags, using the same `+` joins as NCPDIAG. A separate telemetry slot captures them even when the log action does not request parameters. Existing action/log parameter gates are preserved.
- `vl`: the check's violation level; `null` for policy corrections without a ViolationData. `movement_kind` is `player`, `vehicle`, or `outside-move`.
- `from`, `to`: `[dx,dy,dz,yaw,pitch]` relative to this record’s floored original `from`, rounded to 0.001. Cached-rich-location hooks preserve the actual offending check segment, including split moves. Otherwise an active event supplies its original endpoints. Outside a move, use the last accepted endpoint and current player position (or current position for both before any accepted move). Owner corrections, including poll-origin corrections, explicitly supply current location and the already-computed actual teleport destination, even if trusted state advanced without an accepted move. Observation never changes that destination.
- `dt`: milliseconds since the previous processed move, zero before the first. `h_distance` and `v_distance` are blocks per observed segment. `h_speed` / `v_speed` are blocks per second using that interval; `null` when the interval is zero/unavailable. These are observed event rates, not guaranteed client tick timing.
- `ping` in milliseconds, `game_mode`.
- `flags`: `on_ground`, `gliding`, `elytra_equipped`, `fall_distance`, `vehicle` type (or `unowned` if vehicle ownership cannot be established), `rocket_active`, `rocket_remaining_ticks` (NCP boost expiry minus NCP tick), `rocket_remaining_moves` (NCP's separately decremented boost duration), cached `in_liquid` / `in_bubble_column`, `wind_impulse_age_ticks`, `potions` as `[type,amplifier,remaining_ticks]`, `held` as `[main_hand,off_hand]`, `armour` as `[boots,leggings,chestplate,helmet]` types.
- Recent activity: `recent_window_ticks`, `mace_attack_age_ticks`, `wind_charge_launch_age_ticks`, `wind_burst_attack_age_ticks`. Null means no event observed in the window. `wind_burst_observation: enchanted-mace-attack` identifies an attack with Wind Burst equipped, rather than asserting that every such attack actually triggered a smash explosion. Native wind-charge impulse age is captured independently from NCP's existing source context. Telemetry does not probe world entities to infer explosions.
- `region`: owning Folia region's 15-second TPS (`tps_15s`) and `utilisation`, taken from the current region scheduling handle. Unsupported/not-ready reports carry an explicit `unavailable` reason; global TPS is never substituted.
- `history`: up to 20 previous accepted processed moves, oldest first, `[dt,dx,dy,dz,yaw,pitch,onGround]` relative to the record origin, rounded to 0.001. History resets on world changes; rejected/corrected moves do not enter history. No rejected endpoint is promoted to an accepted move.
- `blocks_from`, `blocks_to`: check-cache or owned loaded-block neighbourhoods, or `null` with `blocks_skipped` explaining missing snapshots. Each neighbourhood has `origin` as an integer grid offset in the same anonymous frame as `from`/`to`, `size: [3,4,3]` (x,y,z), and 36 `cells`, traversed y, then z, then x. A cell is `[namespaced_material_id,unsigned_decimal_NCP_block_flags,bounds_state]`; bounds state 0=not fetched or unavailable, 1=fetched empty, 2=full cube, 3=partial shape. Missing cache nodes remain null on cache-only paths. SurvivalFly/Passable/MorePackets/vehicle envelope copy available check caches synchronously without world fetches or node creation. CreativeFly rejects alone additionally read block types from already-loaded chunks on the owning region, with an ownership check before each loaded-state/block read. No chunk loads, check-cache mutations, or live shape calculations occur; cached matching shapes are copied, otherwise bounds state is 0. Foreign-region, unloaded, and out-of-height cells remain null; if no cells can be read the neighbourhood is null with `neighbourhood-unowned-or-unloaded`. Accepted CreativeFly moves perform no neighbourhood reads. `nofall-native-air-landing`, `owner-correction` (including poll-origin owner corrections), and `poll-correction` also read only owned, already-loaded cells using this same reader, without check caches or shape queries. Their origins use that same common record frame: the from neighbourhood begins at `[-1,-1,-1]`, while the to neighbourhood follows its endpoint’s block offset. Size, cell order and fields are identical. Rounded positions can differ from the original by up to 0.0005; cell origins use the original block grid, retaining alignment even at rounding boundaries. All existing snapshot origins are relativized too. If neither endpoint is accessible, the existing skipped reason is `neighbourhood-unowned-or-unloaded`. An exception while reading either policy endpoint counts a capture error and marks that neighbourhood unavailable; the incident and any successful other endpoint remain queued. Other paths retain their existing capture behaviour.

All values handed to the writer are deeply immutable scalars/maps/lists. No Player, World, Location, item, effect, cache or event reaches the writer.

## Movement samples (`kind: move`)

UUID/hour hashing selects the same players across workers for an entire UTC hour. Default general selection is 1%, independently refreshed hourly. `move-sample-real-percent` sets a higher total cohort for explicitly known non-bots (`bot: false`, both effective LuckPerms metadata and tempbot state known). The same hash gives nested cohorts: effective percentage is max(general, real) for non-bots, and general for bots or unknowns. Thus 1% general plus 10% real selects 10% of real players, with no duplicate records and no loss of the original general cohort. Bot-state changes take effect on the existing 30-second cache deadline; UUID/hour selection stays sticky while the classification is unchanged. Each move record carries its effective `sample_percent`. Every processed move for selected players includes `dt`, `pos: [dx,dy,dz,yaw,pitch]` (event displacement, rounded to 0.001), `flags: [onGround,gliding,inVehicle,fallDistance]`, `accepted`, `sample_percent`, `move_id`, and `movement_kind`, plus common token/bot fields. Rejected samples are labelled `accepted: false`; a later cancellation by another plugin does not produce an NCP set-back record.

## Counters (`kind: counter`)

Written directly by the writer once per minute and at shutdown, independently of the record queue:

`time`, `worker`, `session`, `interval_start`, `moves`, `moves_total`, `setbacks` (per-check interval deltas), `setbacks_total` (per-check lifetime totals), `drops`, `drops_total`, `capture_errors`, `capture_errors_total`, `queue_depth`, `v`.

Atomic cumulative counters include unsampled players and incidents whose queued records were dropped. Snapshot deltas have no lost increments under concurrent region producers. Each total has its own atomic snapshot point; snapshots are not a cross-region transactional barrier. During a disk pause no files are written; the next counter covers the longer paused interval and preserves cumulative totals. Reset totals are distinguished by `session`.

## Failure and performance model

Producers reserve bounded queue capacity with CAS and offer to a lock-free queue. A full queue immediately counts a drop; no IO, serialization, waiting, or queue locks run in the check path. Every observer call is exception-isolated. All entity reads occur on the player's owning region, and a vehicle is read only when it is also owned. A single daemon writer serializes, writes, rotates, compresses, scans disk usage, and handles IO failures.

The disk guard pauses below the free-space threshold or when the telemetry folder reaches the cap, warns once per pause, and resumes automatically when both conditions clear. Each JSONL record, including its UTF-8 newline, must fit within the remaining folder budget; records that do not fit count as drops, including during shutdown. It does not delete old archives to evade the cap. Producer queues remain bounded during pauses. Drops and capture errors remain visible in counters. Abrupt process termination can lose queued data; telemetry is observational and does not block enforcement for durability.

`TestTelemetryBenchmark` measures immutable record/payload construction plus offer, and writer serialization/IO/close/gzip throughput. The representative set-back includes 20 history moves and two 36-cell cache neighbourhoods. It excludes Bukkit/Fox/LuckPerms API reads; timings are local microbenchmarks, not a live Fox lab speed-test result. A production-equivalent Fox lab speed test must still pass before staging.

Run the offline JDK 25 Maven build and `python3 tools/verify-telemetry-decisions.py`. The latter allows only the two existing owner-correction telemetry calls to gain the already-computed destination argument and rejects every other check-source edit.

## Writer guard and hot-path costs

Ownership methods are resolved once to MethodHandles. Per-thread move context deques are retained empty between callbacks, and endpoints/history use primitive coordinates, materializing JSON lists only on sampled moves or set-backs. Region-report reflection caches positive and unsupported lookups per runtime class.

The writer caches its FileStore and maintains folder byte totals including unbuffered UTF-8 writes and compression/temporary-file changes. Failed writes account for the actual file size when readable, otherwise retain the attempted byte charge; failed output is closed before retrying, with no buffer to replay. A recursive scan reconciles external additions/deletions at most once every 60 seconds (so externally freed space may take up to 60 seconds to resume a folder-cap pause). Free space is checked before each batch. Recovery compiles its own-worker filename pattern once and lists closed-hour JSONL files only at startup and hour changes, recovering all eligible files in that listing. Archive count therefore adds no directory traversal to ordinary idle polls. Initial/hourly recovery and minute reconciliation still scale with file count.
