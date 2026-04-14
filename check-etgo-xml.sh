#!/usr/bin/env bash
# Check ETGO sourcedata XMLs: order, unique constraints, referential integrity
set -euo pipefail

DIR="$(cd "$(dirname "$0")" && pwd)"
SD="$DIR/src-db/database/sourcedata"

PASS="\033[32m✔\033[0m"
FAIL="\033[31m✘\033[0m"
GLOBAL_EXIT=0

# ── 1. Record order (UUID ascending) ────────────────────────────
echo "── Record order ──"
for xml_file in "$SD"/ETGO_*.xml; do
  [ -f "$xml_file" ] || continue
  basename="$(basename "$xml_file")"

  ids=$(grep -oE '<!--[A-Fa-f0-9]+--><[A-Z_]+>' "$xml_file" \
        | sed 's/<!--\([A-Fa-f0-9]*\)-->.*/\1/' \
        | tr '[:lower:]' '[:upper:]')

  [ -z "$ids" ] && continue

  sorted=$(echo "$ids" | sort)

  if [ "$ids" != "$sorted" ]; then
    echo -e "  $FAIL $basename — desordenado"
    prev=""
    while IFS= read -r current; do
      if [ -n "$prev" ] && [ "$(printf '%s\n%s' "$prev" "$current" | sort | head -1)" != "$prev" ]; then
        echo "    Primera violación: $prev debería ir antes de $current"
        break
      fi
      prev="$current"
    done <<< "$ids"
    GLOBAL_EXIT=1
  else
    echo -e "  $PASS $basename"
  fi
done

# ── 2. Unique constraint: ETGO_SF_ENTITY (ETGO_SF_SPEC_ID, NAME) ─
echo ""
echo "── Unique constraint (ETGO_SF_ENTITY) ──"
python3 - "$SD" <<'PYEOF'
import xml.etree.ElementTree as ET, sys, os

sd = sys.argv[1]
xml_file = os.path.join(sd, "ETGO_SF_ENTITY.xml")
root = ET.parse(xml_file).getroot()

seen = {}
errors = []

for entity in root.findall("ETGO_SF_ENTITY"):
    eid  = (entity.findtext("ETGO_SF_ENTITY_ID") or "").strip()
    spec = (entity.findtext("ETGO_SF_SPEC_ID")   or "").strip()
    name = (entity.findtext("NAME")               or "").strip()
    key  = (spec, name)
    if key in seen:
        errors.append(f"  Duplicate (SPEC={spec}, NAME={name}): {seen[key]} vs {eid}")
    else:
        seen[key] = eid

if errors:
    print(f"  \033[31m✘\033[0m ETGO_SF_ENTITY — duplicados encontrados")
    for e in errors:
        print(e)
    sys.exit(1)
else:
    print(f"  \033[32m✔\033[0m ETGO_SF_ENTITY")
PYEOF
[ $? -ne 0 ] && GLOBAL_EXIT=1 || true

# ── 3. Referential integrity (orphans) ──────────────────────────
echo ""
echo "── Integridad referencial ──"
python3 - "$SD" <<'PYEOF'
import xml.etree.ElementTree as ET, sys, os

sd = sys.argv[1]

def get_ids(filename, tag, id_field):
    path = os.path.join(sd, filename)
    return {(e.findtext(id_field) or "").strip() for e in ET.parse(path).getroot().findall(tag)}

spec_ids   = get_ids("ETGO_SF_SPEC.xml",   "ETGO_SF_SPEC",   "ETGO_SF_SPEC_ID")
entity_ids = get_ids("ETGO_SF_ENTITY.xml", "ETGO_SF_ENTITY", "ETGO_SF_ENTITY_ID")

errors = []

for e in ET.parse(os.path.join(sd, "ETGO_SF_ENTITY.xml")).getroot().findall("ETGO_SF_ENTITY"):
    eid  = (e.findtext("ETGO_SF_ENTITY_ID") or "").strip()
    spec = (e.findtext("ETGO_SF_SPEC_ID")   or "").strip()
    if spec not in spec_ids:
        errors.append(f"  ENTITY {eid} -> SPEC {spec} (no existe)")

for f in ET.parse(os.path.join(sd, "ETGO_SF_FIELD.xml")).getroot().findall("ETGO_SF_FIELD"):
    fid    = (f.findtext("ETGO_SF_FIELD_ID")  or "").strip()
    entity = (f.findtext("ETGO_SF_ENTITY_ID") or "").strip()
    if entity not in entity_ids:
        errors.append(f"  FIELD {fid} -> ENTITY {entity} (no existe)")

if errors:
    print(f"  \033[31m✘\033[0m Registros huérfanos encontrados")
    for e in errors:
        print(e)
    sys.exit(1)
else:
    print(f"  \033[32m✔\033[0m Sin huérfanos")
PYEOF
[ $? -ne 0 ] && GLOBAL_EXIT=1 || true

echo ""
if [ $GLOBAL_EXIT -eq 0 ]; then
  echo -e "\033[32mTodo OK.\033[0m"
else
  echo -e "\033[31mHay errores.\033[0m"
fi
exit $GLOBAL_EXIT
