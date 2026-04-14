#!/usr/bin/env bash
# Sort ETGO sourcedata XML records by UUID ascending.
# Usage: ./fix-etgo-xml-order.sh [file.xml ...]
#   No args → fixes all ETGO_*.xml in src-db/database/sourcedata/
set -euo pipefail

DIR="$(cd "$(dirname "$0")" && pwd)"
SD="$DIR/src-db/database/sourcedata"

if [ $# -gt 0 ]; then
  files=("$@")
else
  files=("$SD"/*.xml)
fi

for xml_file in "${files[@]}"; do
  [ -f "$xml_file" ] || continue
  basename="$(basename "$xml_file")"

  python3 - "$xml_file" <<'PYEOF'
import re, sys

filepath = sys.argv[1]
basename = filepath.rsplit("/", 1)[-1]

with open(filepath, "r", encoding="utf-8") as f:
    content = f.read()

# Split: header (<?xml ...> + <data>) and footer (</data>)
header_match = re.match(r"(.*?<data>\n?)", content, re.DOTALL)
footer_match = re.search(r"(\n?</data>\s*)$", content)

if not header_match or not footer_match:
    print(f"  SKIP {basename} — could not parse header/footer")
    sys.exit(0)

header = header_match.group(1)
footer = footer_match.group(1)
body = content[len(header):footer_match.start()]

# Detect the table name/tag dynamically from the first record
tag_match = re.search(r"<!--[A-Fa-f0-9]+--><([A-Z0-9_]+)>", body)
if not tag_match:
    print(f"  SKIP {basename} — could not detect record tag")
    sys.exit(0)

tag_name = tag_match.group(1)

# Split body into blocks, each starting with <!--UUID--><tag_name>
blocks = re.split(fr"\n+(?=<!--[A-Fa-f0-9]+--><{tag_name}>)", body.strip())
blocks = [b.strip() for b in blocks if b.strip()]

if not blocks:
    print(f"  SKIP {basename} — no records found")
    sys.exit(0)

# Extract UUID from the very first line of the block
def get_uuid(block):
    m = re.match(r"<!--([A-Fa-f0-9]+)-->", block)
    return m.group(1).upper() if m else "ZZZZZZ"

# Snapshot of IDs before sorting
ids_before = set(get_uuid(b) for b in blocks)

# Detect duplicates
duplicates = [get_uuid(b) for b in blocks if blocks.count(b) > 1]
if duplicates:
    print(f"  WARNING {basename} — UUIDs duplicados: {', '.join(set(duplicates))}")

# Sort blocks by UUID
sorted_blocks = sorted(blocks, key=get_uuid)

# Reassemble with exactly ONE blank line between records
header_clean = header.strip()
footer_clean = footer.strip()
new_content = header_clean + "\n" + "\n\n".join(sorted_blocks) + "\n\n" + footer_clean + "\n"

with open(filepath, "w", encoding="utf-8") as f:
    f.write(new_content)

# Validate: re-read and check IDs after write
with open(filepath, "r", encoding="utf-8") as f:
    content_after = f.read()

ids_after = set(re.findall(fr"<!--([A-Fa-f0-9]+)--><{tag_name}>", content_after))

lost = ids_before - ids_after
gained = ids_after - ids_before

if lost:
    print(f"  ERROR {basename} — se perdieron {len(lost)} IDs: {', '.join(sorted(lost))}")
    sys.exit(1)
if gained:
    print(f"  ERROR {basename} — aparecieron {len(gained)} IDs nuevos inesperados: {', '.join(sorted(gained))}")
    sys.exit(1)

was_sorted = blocks == sorted_blocks
if was_sorted:
    print(f"  {basename} — ya estaba ordenado ({len(blocks)} registros) ✓")
else:
    print(f"  {basename} — ordenado ({len(blocks)} registros) ✓")
PYEOF

done
