Codex: Monument detection & completion (authoritative spec)

Files & canonical data

monument.yml (single source of truth)

Stores monument metadata (center coords, bedrock base coords, station offsets) and completion states for all 20 monuments.

Structure (conceptual):

monuments:
  PURPLE:
    center: world,x,y,z
    base: world,x,y,z   # exact bedrock block
    stationOffsets: [[0,1,0],[0,2,0],[0,3,0]]  # relative positions from base
    sign: world,x,y,z   # optional authoritative sign coord (for display)
    completed: false
    completedBy: null
    completedAt: null
  IRON:
    ...


monument.yml is generated once at world/generate time (or when you run regeneratearctic), and only changed by monument completion or admin clobbering.

Trigger & detection flow (authoritative)

Trigger event: BlockPlaceEvent for every block placement.

Scope check: If placed block is within 30 blocks of any stored monument center, continue; otherwise return.

Find base: From placed-block location, find the nearest registered monument base (the stored bedrock coordinate) that is within 2 blocks. If no base → ignore.

Sign & type identification: Read the stored sign or monument id from monument.yml. Do not rely on player-edited sign text as authoritative. If the sign must be used for display, treat it as display-only and protect it; the authoritative type is the monument id in monument.yml.

If you must parse sign text, parse case-insensitively, trim whitespace, strip color codes, and then validate match against monument.yml. If mismatch, log admin warning and fallback to monument.yml id.

Station validation: Each monument defines three station coordinates as offsets from base. Check the three absolute coordinates:

Confirm each of the three blocks above the base exactly matches the required material set for the monument type (e.g., PURPLE → PURPLE_TERRACOTTA).

Only exact coordinate matches count (no radius fuzzing).

Completion decision: If those three coordinates are all correct and monument.yml shows completed: false, proceed to completion. If completed: true → do nothing (no rewards).

Transaction (atomic): Acquire a per-monument lock → (validate again under lock) → set completed: true, completedBy: <playerUUID>, completedAt: <timestamp> in memory → persist to disk atomically (write temp file, rename) → then fire CompleteMonumentStandEvent → perform world-side effects (convert blocks to stained glass, play particles, reward the player) → release lock.

If persistence fails after marking in memory, roll back completed and notify admins. Never fire rewards before persistence is confirmed.

Event: CompleteMonumentStandEvent

Event payload (what listeners can expect):

monumentId (string) — canonical ID from monument.yml.

monumentType (enum) — e.g., PURPLE, BLUE, IRON, BROWN, DIAMOND.

standLoc (Location) — monument base center coords.

playerUUID (UUID, non-null) — the player who placed the final block (must be available).

timestamp (long/epoch ms) — completion time.

scope — GLOBAL (this design uses global completions per world).

Listeners should expect the event to represent a durable state (already persisted) and may run rewards, broadcast, and spawn effects without re-checking persistence.

Rewards

Each monument type has its own reward handler. On event:

Spawn the “reward item(s)” (for now, the same material as the monument type) near the stand; set the item to fly toward the completing player using a vector.

Play toast UI sound + spawn colored particles for 30s around the stand.

Reward handlers must be idempotent if called multiple times (but event should only be fired once by the transaction).

Visual / world-side behavior

On completion, convert the three station blocks into stained glass of the monument’s color (or otherwise mark visually). This prevents players from breaking and re-placing the same high-value block to re-trigger.

Alternatively animate: small delay → replace with glass → spawn persistent particle ring for 30s.

Anti-cheat & hardening (must-do)

Authoritative data: model everything off monument.yml (center/base/station offsets/id). Signs are display-only. If a player edits/destroys a sign, auto-restore it or block the action in the protected radius.

Protect region: cancel sign edits, sign breaks, and sign right-clicks within 30 blocks of each monument for non-admins.

Exact coordinate enforcement: only accept block placements at the exact station coordinates (no radius acceptance).

Creative/admin bypass: disallow completion actions from creative-mode players unless explicitly enabled by config flag.

Duplication & movement: disable piston/moving/block-transport interactions inside the protected radius, or validate final station blocks are placed by players (check BlockPlaceEvent cause; ignore block moves).

Item consumption: if you let players place blocks, make sure the item count in their hand is decremented exactly once, and that the placed block is converted to glass (so the player doesn’t retain the placed block). If you cancel the placement event and consume manually, do it atomically (prevent exploit where client thinks block placed).

Race-lock: single-monument lock during validate→persist→fire to prevent double awards.

Persistence, backups & regeneration rules

Atomic write: write monument.yml atomically (tmp file + rename) to avoid corruption on crash.

Backup: on each successful update make a timestamped backup (e.g., monument.yml.bak-YYYYMMDD-HHMMSS) for recovery.

Regenerate behavior: regeneratearctic is an explicit admin action:

It should recreate monument.yml and re-place structures only when explicitly invoked.

Option: require an admin flag to also reset completed states (default: preserve unless --reset passed).

Log who ran regeneration and when.

Server reloads: on plugin reload, re-validate that each monument’s base block still exists; if missing, log an admin alert and optionally auto-restore from schematic.

Edge-cases and decision points to finalize

Authoritative sign vs stored id — codex prefers stored monument.yml id. If you still want sign parsing, make it a fallback only, not authoritative.

What if a sign is destroyed? Auto-restore the sign from stored coords on next plugin tick or admin-run /ctm restore-signs.

Partial uninstall / schematic mismatch — ensure regeneratearctic can re-place the three station offsets exactly.

What if the completing player logs off mid-event? You still record completedBy and use playerUUID to give the reward later or when they next log on; rewards should not fail because the player is offline.

What happens if a monument is accidentally completed by grief/dupe? Admin rollback tooling must be available (/ctm rollback PURPLE) to reset completed=false and revert world-state if desired.

Localization & color codes — if you ever parse sign text, strip Minecraft color codes and localizations. Prefer ID strings instead of localized names.

API / method names (design-friendly, non-code)

Use these as your codex method names and guarantees:

monumentManager.load() — loads monument.yml into memory.

monumentManager.isCompleted(monumentId) -> boolean — authoritative query.

monumentManager.getMonumentAt(location) -> monument — returns monument if within 30 blocks.

monumentManager.getNearestBase(location, maxDistance) -> baseLocation — returns registered bedrock base.

monumentManager.validateStations(monument) -> boolean — checks exact coordinates/materials.

monumentManager.markCompleted(monumentId, playerUUID, timestamp) -> success/failure — does the atomic persist + backup.

events.fireCompleteMonumentEvent(monumentId, type, standLoc, playerUUID, timestamp) — called only after successful persistence.

rewards.handleCompletion(monumentType, playerUUID, standLoc) — idempotent reward routine that spawns item(s) flying to player, plays particles, and logs.

Testing checklist (before launch)

 Place final block with a test player; verify monument.yml changes and event fires.

 Attempt to re-complete same monument; verify no event/rewards.

 Try sign break/edit as non-admin; verify cancel and auto-restore.

 Try to trigger by block-move (piston/teleported block); verify ignored.

 Simulate two players placing final block simultaneously; verify only one completion and one reward.

 Simulate disk write failure (e.g., make config dir readonly) and verify rollback behavior and admin alert.

 Run regeneratearctic and confirm preserved or reset completion flags per chosen behavior.

