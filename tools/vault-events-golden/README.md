# vault-events golden vectors

Generates `vault-events/src/test/resources/vault-events-golden.json` from the **Rust** crate
`crates/vault-events` in [VaultSearch](https://github.com/pawelkat/VaultSearch) (branch
`feat/vault-events-stage1`, PR #9). `GoldenVectorsTest` checks that the Kotlin port in
`vault-events/` produces byte-identical event JSON, the same keys, ordering, conflict names, ignore
rules and `conflict::decide` results (600-case matrix).

```sh
# VaultSearch checked out next to VaultHome (edit the path in Cargo.toml otherwise)
cd tools/vault-events-golden
cargo run --release > ../../vault-events/src/test/resources/vault-events-golden.json
```

Regenerate whenever the Rust event format, key encoding or conflict rule changes.

The wire-level test (`RustHubIntegrationTest`) runs the real Rust hub and agent against the Kotlin
engine on zenoh-kotlin 1.10.2 (JVM jar):

```sh
VAULT_EVENTS_BIN=/path/to/VaultSearch/target/release/vault-events ./gradlew :vault-events:test
```
