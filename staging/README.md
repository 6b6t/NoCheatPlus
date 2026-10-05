NoFall v2 replaces deferred damage with the native landing pipeline. Its configuration is `checks.moving.nofall.active: true` and `dealdamage: false`; `resetonteleport` and all other live settings stay as supplied.

The measured deployment verdict is in [NOFALL-V2.md](../../NOFALL-V2.md). The shaded jar, exact live configuration diff, hashes, and rollback instructions are in [NOFALL-V2-RELEASE](../../NOFALL-V2-RELEASE/).

The v2 source removes the global damage tick listener and the withdrawn entity-deferred prototype. Accepted movement checkpoints carry unfinished geometric descent across Fox workers; landing damage, immunity, and modifiers belong to the native server.

`nofall-experimental.diff` belongs to the withdrawn `dealdamage: true` experiment. Use the v2 release diff for this implementation.
