# vault-events (Kotlin)

Pure-Kotlin port of VaultSearch `crates/vault-events` (event format 1, keys, conflict rule, sync core)
plus the Zenoh transport (zenoh-kotlin 1.10.2). VaultHome's Settings → Event sync runs it in a
foreground service as Zenoh client, peer or router.

Tests: `./gradlew :vault-events:test` (golden vectors from the Rust crate, see
`tools/vault-events-golden`). With `VAULT_EVENTS_BIN=/path/to/vault-events` the Rust hub
integration tests run too.

## Large files (blob references + chunks)

Same protocol as the Rust crate (VaultSearch PR #9, `crates/vault-events/README.md`, "Large files"):
files above 1 MiB (`TransferOptions.inlineMax`) are published as a small reference (`blob: {size,
chunk_size, chunks}`, no `content`), kept in an in-memory [OutgoingCache] for 30 minutes, and served
on `vault/blob/<sha256>/<i>?cs=<chunk_size>` (`ZenohLink.serveBlobs`). Incoming references are fetched
chunk by chunk on a separate thread (`ZenohLink.fetchChunk`: best-matching queryable first, then all;
priority DATA_LOW, congestion control DROP) into `<app cache>/vault-events-partial/<sha>.part`
(outside the SAF tree), verified, and only then applied through the normal `writeAtomic` path. Missing
chunks are asked for again (resumable across restarts), a hash mismatch starts over, and a reference
whose bytes were not fetched is never applied or saved as a conflict copy. Catch-up uses the same path.

Older VaultHome builds drop references as `MissingContent` (nothing written).

## Transfer acks

After the engine writes a received file (inline or blob) and verifies its SHA-256, and after it
publishes a live file it has on disk, it puts `{status: ok|fail, reason?, path, bytes, duration_ms}`
on `vault/ack/<sha256>/<device>`. The box hub logs `<device> received <path> (<bytes>) ok in X.Xs`
(or `fail: <reason>`); `vault-events who <path>` lists the devices that confirmed the current
version. A failed blob fetch publishes `status: fail`. Older hubs ignore the key.

## Synced-note notifications

`SyncNotices` decides what VaultHome shows in its "Synced notes" channel:

- Only events from another device that changed the vault (`Decision.Apply`): create, update, delete,
  plus conflict copies they caused.
- Nothing for this device's own changes, echoes of its own puts (zenoh-kotlin has no
  `Locality::Remote`; the core returns `Ignore`), `Adopt`, `KeepLocal`, or the shared file below.
- Batches: one catch-up pass, or live notes within 2 s. Up to 3 notes get one notification each;
  more become one "N notes synced" summary.

## Shared endpoint list: `app_settings/VaultEvents/endpoints.json`

Same `app_settings/<App>/` layout as GymMonth. The file is a normal vault file, so it syncs over
events (and Drive) to every device.

```json
{
  "schemaVersion": 1,
  "source": { "note": "..." },
  "routers": [
    { "id": "note4", "endpoint": "tcp/note4.tailf52603.ts.net:7447", "dialable": true, "note": "planned always-on router" },
    { "id": "nitro5", "endpoint": "tcp/nitro5.tailf52603.ts.net:7447", "tailnetName": "nitro5.tailf52603.ts.net", "dialable": true },
    { "id": "box", "tailnetName": "grokbot-user-155710816.tailf52603.ts.net", "dialable": false,
      "note": "Grok Bot box hub; outbound-only, dials routers. Not a connect endpoint." }
  ],
  "devices": {
    "s24": {
      "app": "VaultHome",
      "mode": "router",
      "listen": ["tcp/0.0.0.0:7447"],
      "endpoint": "tcp/pawels-s24-ultra.tailf52603.ts.net:7447",
      "tailnetName": "pawels-s24-ultra.tailf52603.ts.net",
      "lastSeen": "2026-11-18T17:00:00Z"
    }
  }
}
```

Rules (`SharedEndpoints`):

- **routers**: hosts that can accept Zenoh connections. `dialable: false` means the host only dials
  out (the box). Written only when the file is seeded.
- **devices**: each device writes only `devices.<its device name>`. Every other key (other
  devices, `routers`, unknown fields) is kept as read. `endpoint` is set only in router/peer mode.
- Connect list = local Settings list, then dialable `routers[].endpoint`, then `devices[].endpoint`
  of other router/peer devices. Never the device's own endpoint, never invalid endpoints.
- VaultHome reads the file before each connect and merges its own entry after the first catch-up
  (so a newer copy from the hub lands first). It seeds the file only when it is missing, rewrites it
  only when its entry changed or `lastSeen` is older than 6 h, and never overwrites a file that is
  not valid JSON.
- Changes from other devices take effect at the next (re)connect.
- No keys or secrets in this file.
