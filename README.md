# com.etendoerp.go

## XML Sourcedata Scripts

Two helper scripts are available in the module root for working with the XML files under `src-db/database/sourcedata/`.

---

### `fix-etgo-xml-order.sh` — Reorder & validate

Sorts all records inside each XML file by UUID (ascending), which is required by the CI order check. After writing, it re-reads the file and verifies that no records were lost.

**Reorder all XML files in `src-db/database/sourcedata/`:**
```bash
./fix-etgo-xml-order.sh
```

**Reorder one or more specific files:**
```bash
./fix-etgo-xml-order.sh src-db/database/sourcedata/ETGO_SF_FIELD.xml
./fix-etgo-xml-order.sh src-db/database/sourcedata/ETGO_SF_FIELD.xml src-db/database/sourcedata/ETGO_SF_SPEC.xml
```

Output legend:
- `ya estaba ordenado (N registros) ✓` — file was already in order, integrity confirmed.
- `ordenado (N registros) ✓` — file was reordered, integrity confirmed.
- `WARNING ... UUIDs duplicados` — duplicate records detected (file is still written).
- `ERROR ... se perdieron N IDs` — records were lost during processing (script exits with code 1).
- `SKIP ...` — file could not be parsed (malformed XML or no records found).

---

### `check-etgo-xml.sh` — Validate order, uniqueness & referential integrity

Read-only check that validates XML files without modifying them. Run this before committing to catch issues early.

**Check all XML files:**
```bash
./check-etgo-xml.sh
```

**Check a specific file:**
```bash
./check-etgo-xml.sh src-db/database/sourcedata/ETGO_SF_FIELD.xml
```

---

### Typical workflow

```bash
# 1. Fix order and validate integrity
./fix-etgo-xml-order.sh

# 2. Run the CI checks locally
./check-etgo-xml.sh

# 3. Commit
git add src-db/database/sourcedata/
git commit -m "Fix XML sourcedata order"
```
