# ADR-0007: SQLite + FTS5 + sqlite-vec (SQLCipher) as the single store

- Status: Accepted · 2026-11-27
- Context: We need relational data (agents, policies, audit), full-text search, vector search for
  memory/knowledge, encryption at rest, a tiny footprint and offline operation.
- Decision: One SQLite database, encrypted with SQLCipher (key in Android Keystore). FTS5 for BM25,
  sqlite-vec `vec0` for 256-d EmbeddingGemma vectors [R6], hybrid retrieval with RRF (SPEC §14).
  It is owned by the `:agent` process only; other processes go through AIDL.
- Alternatives rejected: ObjectBox/Realm (extra runtime, licensing/size), a separate vector DB (two stores to keep consistent).
- Consequences: (+) One file to back up, migrate and delete. (+) Transactional consistency between rows
  and vectors. (−) sqlite-vec brute-force scan is O(n). That's fine below ~100k chunks with int8 quantisation;
  revisit beyond that.
