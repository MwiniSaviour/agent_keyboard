#!/usr/bin/env python3
"""Applies every migration in order to a fresh SQLite (FTS5 required) and checks invariants of SPEC §16.

SQLCipher is SQLite plus encryption, so schema validity here implies validity on device. Exits non-zero
on any failure. Usage: verify-schema.py <migrations-dir>
"""
import pathlib
import re
import sqlite3
import sys

mig_dir = pathlib.Path(sys.argv[1])
files = sorted(mig_dir.glob("[0-9][0-9][0-9][0-9]_*.sql"))
assert files, f"no migrations in {mig_dir}"
for i, f in enumerate(files, start=1):
    assert int(f.name[:4]) == i, f"{f.name}: expected version {i}"

db = sqlite3.connect(":memory:")
db.execute("PRAGMA foreign_keys = ON")
for f in files:
    with db:
        db.executescript(f.read_text())
    db.execute(f"PRAGMA user_version = {int(f.name[:4])}")

tables = {r[0] for r in db.execute("SELECT name FROM sqlite_master WHERE type = 'table'")}
expected = {"agents", "agent_resources", "chunks", "chunks_fts", "memory_facts", "style_profile", "contacts",
            "conversations", "messages", "autonomy_policies", "audit_log", "clipboard_items", "notes",
            "profile_vault", "model_registry", "settings"}
missing = expected - tables
assert not missing, f"missing tables: {missing}"

def must_fail(sql, args, why):
    try:
        with db:
            db.execute(sql, args)
    except sqlite3.DatabaseError:
        return
    raise AssertionError(f"expected failure: {why}")

# FR-AG-01: General cannot be deleted; check constraints hold.
with db:
    db.execute("INSERT INTO agents VALUES ('general','general','General','{}','draft',1,0,0)")
    db.execute("INSERT INTO agents VALUES ('sales','custom','Sales Bot','{}','auto',1,0,0)")
must_fail("DELETE FROM agents WHERE id = ?", ("general",), "General agent is undeletable")
must_fail("INSERT INTO agents VALUES ('x','custom','X','{}','yolo',1,0,0)", (), "autonomy_ceiling CHECK")
must_fail("INSERT INTO agents VALUES ('y','custom','','{}','off',1,0,0)", (), "empty name CHECK")

# FTS stays in sync through triggers; cascade removes chunks with their agent.
with db:
    db.execute("INSERT INTO chunks(agent_id, ord, text, tokens) VALUES ('sales', 0, 'We deliver to Kumasi within 24 hours', 8)")
hits = db.execute("SELECT rowid FROM chunks_fts WHERE chunks_fts MATCH 'kumasi'").fetchall()
assert len(hits) == 1, hits
with db:
    db.execute("DELETE FROM agents WHERE id = 'sales'")
assert db.execute("SELECT count(*) FROM chunks").fetchone()[0] == 0, "cascade"
assert db.execute("SELECT count(*) FROM chunks_fts WHERE chunks_fts MATCH 'kumasi'").fetchone()[0] == 0, "fts delete trigger"

# FR-AU-06: audit log is append-only.
with db:
    db.execute("INSERT INTO audit_log(at, action) VALUES (1, 'sent')")
must_fail("UPDATE audit_log SET action = 'edited'", (), "audit_log append-only")

stmts = sum(len(re.findall(r";\s*$", f.read_text(), re.M)) for f in files)
print(f"  schema v{len(files)}: {len(tables)} tables, constraints/triggers verified ({stmts} statements)")
