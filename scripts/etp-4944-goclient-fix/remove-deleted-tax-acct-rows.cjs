#!/usr/bin/env node
// ETP-4944 — GOClient sampledata FK fix.
//
// The sibling repo (com.etendoerp.go.localization.es.data,
// feature/ETP-4944) deleted 144 obsolete Spanish fiscal tax rate ids from
// the system reference-data catalog. This module's demo-tenant sampledata
// (referencedata/sampledata/GOClient/C_TAX_ACCT.xml) still ships 87 rows
// that map one of those now-deleted ids to a GL account, which breaks
// ImportSampledata on a fresh install with:
//   insert or update on table "c_tax_acct" violates foreign key
//   constraint "c_tax_acct_c_tax" ... Key (c_tax_id)=(...) is not present
//   in table "c_tax".
//
// This script removes every <C_TAX_ACCT> block whose <C_TAX_ID> is in the
// deleted-ids set (fetched from the sibling schema_forge repo's
// scripts/etp-4944-tax-cleanup/resolved-scope.json — see DELETE_IDS_PATH).
// It edits the target XML file in place, but only after asserting the
// before/after record counts match the expected drop exactly.
//
// This file's shape is a raw DB export (`<data><C_TAX_ACCT>...` with plain
// child elements, no `id=`/`entity-name=` attributes) — NOT the AD
// reference-data shape used by transform-xml.cjs in the sibling repo, so a
// simpler block-level regex is enough here.

const fs = require('fs');
const path = require('path');

const XML_PATH = path.join(__dirname, '../../referencedata/sampledata/GOClient/C_TAX_ACCT.xml');
const DELETE_IDS_PATH = process.argv[2];

if (!DELETE_IDS_PATH) {
  console.error('Usage: node remove-deleted-tax-acct-rows.cjs <path-to-delete-ids.json>');
  console.error('  <path-to-delete-ids.json> must be a JSON array of C_TAX_ID strings to remove.');
  process.exit(1);
}

const deleteIds = new Set(JSON.parse(fs.readFileSync(DELETE_IDS_PATH, 'utf8')));
if (deleteIds.size === 0) {
  console.error('deleteIds set is empty — refusing to run (nothing to do, or bad input).');
  process.exit(1);
}

const xml = fs.readFileSync(XML_PATH, 'utf8');

const blockRe = /<C_TAX_ACCT>[\s\S]*?<\/C_TAX_ACCT>\n?/g;
const totalBefore = (xml.match(blockRe) || []).length;

let removed = 0;
const removedIds = [];
const kept = xml.replace(blockRe, (block) => {
  const m = block.match(/<C_TAX_ID><!\[CDATA\[(.*?)\]\]><\/C_TAX_ID>/);
  if (!m) throw new Error(`C_TAX_ACCT block with no <C_TAX_ID> found — inspect manually:\n${block}`);
  const taxId = m[1];
  if (deleteIds.has(taxId)) {
    removed++;
    removedIds.push(taxId);
    return '';
  }
  return block;
});

const totalAfter = (kept.match(blockRe) || []).length;

if (totalAfter !== totalBefore - removed) {
  throw new Error(`Count mismatch after removal: before=${totalBefore} removed=${removed} after=${totalAfter}`);
}

// Assert no remaining C_TAX_ACCT block still references a deleted id.
const remainingTaxIds = [...kept.matchAll(/<C_TAX_ID><!\[CDATA\[(.*?)\]\]><\/C_TAX_ID>/g)].map((x) => x[1]);
const stillBroken = remainingTaxIds.filter((id) => deleteIds.has(id));
if (stillBroken.length) {
  throw new Error(`${stillBroken.length} remaining C_TAX_ACCT rows still reference a deleted C_TAX_ID: ${stillBroken.join(', ')}`);
}

fs.writeFileSync(XML_PATH, kept);

console.log(`Total C_TAX_ACCT blocks before: ${totalBefore}`);
console.log(`Removed: ${removed}`);
console.log(`Total C_TAX_ACCT blocks after: ${totalAfter}`);
console.log(`Removed C_TAX_IDs (unique): ${new Set(removedIds).size}`);
