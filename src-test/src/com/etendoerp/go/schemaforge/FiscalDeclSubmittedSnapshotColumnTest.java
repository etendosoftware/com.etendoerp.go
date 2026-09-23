/*
 *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"), you may not use this file except in compliance
 * with the License.
 * You may obtain a copy of the License at
 * https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * Software distributed under the License is distributed on an
 * "AS IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing rights
 * and limitations under the License.
 * All portions are Copyright (C) 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 *************************************************************************
 */
package com.etendoerp.go.schemaforge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.Property;
import org.openbravo.base.model.domaintype.StringDomainType;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.base.validation.StringPropertyValidator;
import org.openbravo.base.validation.ValidationException;

/**
 * ETP-5438 QA BUG-1 regression — the {@code Submitted_Snapshot} column must accept a REAL
 * submission snapshot.
 *
 * <p>The column first shipped with {@code FIELDLENGTH = 2000}. {@code BaseOBObject#set} runs
 * {@code Property#checkIsValidValue}, whose {@code StringPropertyValidator} rejects anything longer
 * than the AD length, so a real period made manual presentation fail and left a telematic filing
 * without its snapshot. Every other test mocks {@code set}/{@code setSubmittedSnapshot} with tiny
 * fixtures, which is how it slipped. This test goes through the real Openbravo
 * {@link Property}/{@link StringPropertyValidator} with the length read from the committed
 * {@code src-db} XML, and proves the snapshot itself stays size-bounded: the per-invoice arrays of
 * the live payload are never kept (scope decision), even for a period with thousands of invoices.
 */
public class FiscalDeclSubmittedSnapshotColumnTest {

  private static final String SUBMITTED_SNAPSHOT_COLUMN_ID = "3CAB7563FAEB427487E2E5BE3774D70C";
  private static final String AD_COLUMN_XML = "src-db/database/sourcedata/AD_COLUMN.xml";
  private static final String MODULE_DIR = "com.etendoerp.go";
  /** Same length as the module's other free-text JSON columns (Feedback_Text, EM_ETGO_Payment_Intent). */
  private static final int MIN_FIELD_LENGTH = 1_000_000;

  /**
   * Locates a module file from any working directory: walks up from the working directory and
   * from this test class's code-source location, accepting either an Etendo root
   * ({@code <dir>/modules/com.etendoerp.go/<relative>}) or the module dir itself. The Etendo-root
   * form is tried first at every level, because the root also has a core {@code src-db} that a
   * bare relative path would silently resolve to. Fails explicitly when nothing is found.
   */
  private static Path moduleFile(String relative) {
    java.util.List<Path> starts = new java.util.ArrayList<>();
    starts.add(Paths.get("").toAbsolutePath());
    try {
      starts.add(Paths.get(FiscalDeclSubmittedSnapshotColumnTest.class.getProtectionDomain()
          .getCodeSource().getLocation().toURI()));
    } catch (Exception e) {
      // no usable code-source location: the working directory is still tried
    }
    for (Path start : starts) {
      for (Path dir = start; dir != null; dir = dir.getParent()) {
        Path viaRoot = dir.resolve("modules").resolve(MODULE_DIR).resolve(relative);
        if (Files.exists(viaRoot)) {
          return viaRoot;
        }
        if (dir.getFileName() != null && MODULE_DIR.equals(dir.getFileName().toString())
            && Files.exists(dir.resolve(relative))) {
          return dir.resolve(relative);
        }
      }
    }
    fail("Cannot locate " + MODULE_DIR + "/" + relative + " from " + starts
        + " — run the tests from the Etendo root or the module directory");
    return null;
  }

  /** The committed {@code FIELDLENGTH} of the Submitted_Snapshot AD_Column. */
  private static int committedFieldLength() throws IOException {
    String xml = new String(Files.readAllBytes(moduleFile(AD_COLUMN_XML)), StandardCharsets.UTF_8);
    Matcher m = Pattern.compile("<!--" + SUBMITTED_SNAPSHOT_COLUMN_ID
        + "-->\\s*<FIELDLENGTH><!\\[CDATA\\[(\\d+)]]></FIELDLENGTH>").matcher(xml);
    assertTrue("Submitted_Snapshot AD_Column must be in " + AD_COLUMN_XML, m.find());
    return Integer.parseInt(m.group(1));
  }

  /** A real Openbravo property for the column, validated exactly like the runtime model does. */
  private static Property snapshotProperty(int fieldLength) {
    Property p = new Property();
    p.setName(FiscalDeclCrudHandler.PROPERTY_SUBMITTED_SNAPSHOT);
    p.setColumnName("Submitted_Snapshot");
    p.setDomainType(new StringDomainType());
    p.setFieldLength(fieldLength);
    // An owning entity, as in the runtime model: ValidationException builds its message from it.
    Entity owner = new Entity();
    owner.setName(FiscalDeclCrudHandler.ENTITY_FISCAL_DECL);
    p.setEntity(owner);
    StringPropertyValidator validator = new StringPropertyValidator();
    validator.setProperty(p);
    validator.initialize();
    p.setValidator(validator);
    return p;
  }

  /** Live GET /fiscal303/boxes payload: 40 boxes plus {@code sourceRows} per-invoice rows. */
  private static JSONObject live303Payload(int sourceRows) throws Exception {
    JSONObject boxes = new JSONObject();
    for (int i = 1; i <= 40; i++) {
      boxes.put(String.valueOf(i), "12345.67");
    }
    JSONArray sources = new JSONArray();
    for (int i = 0; i < sourceRows; i++) {
      sources.put(new JSONObject().put("id", "7CF823B7ACC4404DADAF0F658F1172BD")
          .put("ref", "FV2026/" + String.format("%05d", i)).put("date", "2026-09-15")
          .put("accountingDate", "2026-09-15").put("type", "Venta")
          .put("party", "Cliente Ejemplo de Pruebas SL").put("base", "1000.00").put("vat", "210.00")
          .put("total", "1210.00").put("boxes", "7,9"));
    }
    return new JSONObject().put("boxes", boxes)
        .put("summary", new JSONObject().put("accrued", "1").put("deductible", "1").put("result", "0"))
        .put("sources", sources);
  }

  /** Live GET /fiscal349/operators payload: {@code operators} partners, {@code invoiceRows} invoices. */
  private static JSONObject live349Payload(int operators, int invoiceRows) throws Exception {
    JSONArray ops = new JSONArray();
    for (int i = 0; i < operators; i++) {
      ops.put(new JSONObject().put("bpId", "BP" + i).put("nif", "FR4012345678" + i)
          .put("name", "Operador Intracomunitario " + i + " SARL").put("key", "E")
          .put("base", "1500.00").put("vies", "valid").put("rectificative", false));
    }
    JSONArray invoices = new JSONArray();
    JSONArray rectifications = new JSONArray();
    for (int i = 0; i < invoiceRows; i++) {
      invoices.put(new JSONObject().put("ref", "FV2026/" + i).put("date", "2026-09-15")
          .put("type", "Venta").put("party", "Operador " + i).put("nifIva", "FR40123456780")
          .put("base", "1500.00").put("key", "E"));
      rectifications.put(new JSONObject().put("ref", "AB2026/" + i).put("originalRef", "FV" + i));
    }
    JSONObject keyTotals = new JSONObject().put("totalE", "1").put("totalS", "0")
        .put("totalA", "0").put("totalI", "0");
    return new JSONObject().put("operators", ops).put("summary", keyTotals)
        .put("rectificativeSummary", keyTotals).put("invoices", invoices)
        .put("rectifications", rectifications).put("orgNif", "B12345678")
        .put("orgName", "Empresa de Pruebas SL");
  }

  @Test
  public void committedFieldLengthFitsRealSnapshots() throws IOException {
    int length = committedFieldLength();
    assertTrue("Submitted_Snapshot FIELDLENGTH must be >= " + MIN_FIELD_LENGTH + " (was " + length
        + ")", length >= MIN_FIELD_LENGTH);
  }

  /**
   * A 303 period with 20,000 invoices (~4 MB live payload) yields a snapshot without
   * {@code sources} — only boxes, summary and the row count — which passes the real validator at
   * the committed AD length.
   */
  @Test
  public void snapshot303ExcludesSourcesAndStaysBoundedForThousandsOfInvoices() throws Exception {
    JSONObject live = live303Payload(20_000);
    Fiscal303BoxesHandler handler = new Fiscal303BoxesHandler(mock(NeoServlet.class)) {
      @Override
      JSONObject computeLivePayload(String orgId, int year, String period) {
        return live;
      }
    };

    JSONObject snapshot = handler.computeSubmittedSnapshot("org1", 2026, "T3");
    String stored = snapshot.toString();

    assertFalse(snapshot.has("sources"));
    assertEquals(20_000, snapshot.getInt("sourceCount"));
    assertEquals("12345.67", snapshot.getJSONObject("boxes").getString("7"));
    assertEquals("0", snapshot.getJSONObject("summary").getString("result"));
    assertTrue("303 snapshot must be size-bounded, was " + stored.length(), stored.length() < 2000);
    snapshotProperty(committedFieldLength()).checkIsValidValue(stored);
  }

  /**
   * A 349 period with 20,000 invoices yields a snapshot without {@code invoices}/{@code
   * rectifications} — operators, key totals and row counts only — accepted at the AD length.
   */
  @Test
  public void snapshot349ExcludesInvoiceListsAndStaysBoundedForThousandsOfInvoices()
      throws Exception {
    JSONObject live = live349Payload(40, 20_000);
    Fiscal349BoxesHandler handler = new Fiscal349BoxesHandler(mock(NeoServlet.class)) {
      @Override
      JSONObject computeLivePayload(String orgId, int year, String period) {
        return live;
      }
    };

    JSONObject snapshot = handler.computeSubmittedSnapshot("org1", 2026, "T3");
    String stored = snapshot.toString();

    assertFalse(snapshot.has("invoices"));
    assertFalse(snapshot.has("rectifications"));
    assertEquals(20_000, snapshot.getInt("invoiceCount"));
    assertEquals(20_000, snapshot.getInt("rectificationCount"));
    assertEquals(40, snapshot.getJSONArray("operators").length());
    assertTrue(snapshot.has("summary"));
    assertTrue(snapshot.has("rectificativeSummary"));
    assertTrue("349 snapshot must not scale with invoices, was " + stored.length(),
        stored.length() < 10_000);
    snapshotProperty(committedFieldLength()).checkIsValidValue(stored);
  }

  /**
   * ETP-5438 review W1 — the operators' "Origen" counts survive the stripping: they are folded
   * into each operator row (per nif|key, rectifications per non-zero base) before the invoice
   * and rectification rows are dropped.
   */
  @Test
  public void snapshot349KeepsPerOperatorOriginCounts() throws Exception {
    JSONObject live = new JSONObject();
    live.put("operators", new JSONArray()
        .put(new JSONObject().put("nif", "FR1").put("key", "E").put("rectificative", false))
        .put(new JSONObject().put("nif", "IT2").put("key", "A").put("rectificative", false))
        .put(new JSONObject().put("nif", "FR1").put("key", "S").put("rectificative", true))
        .put(new JSONObject().put("nif", "DE3").put("key", "E").put("rectificative", false)));
    live.put("invoices", new JSONArray()
        .put(new JSONObject().put("nifIva", "FR1").put("key", "E").put("type", "Venta"))
        .put(new JSONObject().put("nifIva", "FR1").put("key", "E").put("type", "Venta"))
        .put(new JSONObject().put("nifIva", "IT2").put("key", "A").put("type", "Compra"))
        .put(new JSONObject().put("nifIva", "IT2").put("key", "A").put("type", "Venta")));
    live.put("rectifications", new JSONArray()
        .put(new JSONObject().put("nifIva", "FR1").put("type", "Venta")
            .put("baseProducts", "0.00").put("baseServices", "-10.00")));
    Fiscal349BoxesHandler handler = new Fiscal349BoxesHandler(mock(NeoServlet.class)) {
      @Override
      JSONObject computeLivePayload(String orgId, int year, String period) {
        return live;
      }
    };

    JSONArray ops = handler.computeSubmittedSnapshot("org1", 2026, "T3").getJSONArray("operators");

    assertEquals(0, ops.getJSONObject(0).getInt(Fiscal349SnapshotSupport.ORIGIN_PURCHASES));
    assertEquals(2, ops.getJSONObject(0).getInt(Fiscal349SnapshotSupport.ORIGIN_SALES));
    assertEquals(1, ops.getJSONObject(1).getInt(Fiscal349SnapshotSupport.ORIGIN_PURCHASES));
    assertEquals(1, ops.getJSONObject(1).getInt(Fiscal349SnapshotSupport.ORIGIN_SALES));
    // corrective row: resolved only against the rectifications (services base -> key S)
    assertEquals(1, ops.getJSONObject(2).getInt(Fiscal349SnapshotSupport.ORIGIN_SALES));
    // no backing row -> no counts, the column reads "—" exactly as it would live
    assertFalse(ops.getJSONObject(3).has(Fiscal349SnapshotSupport.ORIGIN_SALES));
  }

  /** Guards the guard: the original 2000 length rejects a value longer than it. */
  @Test
  public void theOriginal2000LengthRejectsALongerValue() {
    try {
      snapshotProperty(2000).checkIsValidValue(String.join("", java.util.Collections.nCopies(2001, "x")));
      fail("a 2000-char column must reject 2001 chars");
    } catch (ValidationException expected) {
      // the exact failure QA reproduced
    }
  }

  /**
   * {@link FiscalDeclCrudHandler#validateSubmittedSnapshot} — the pre-AEAT check the telematic
   * submission runs — delegates to the entity's real property validator.
   */
  @Test
  public void validateSubmittedSnapshotUsesTheEntityPropertyValidator() throws Exception {
    BaseOBObject decl = mock(BaseOBObject.class);
    Entity entity = mock(Entity.class);
    when(decl.getEntity()).thenReturn(entity);
    String snapshot = String.join("", java.util.Collections.nCopies(5000, "x"));

    when(entity.getProperty(FiscalDeclCrudHandler.PROPERTY_SUBMITTED_SNAPSHOT))
        .thenReturn(snapshotProperty(committedFieldLength()));
    FiscalSubmittedSnapshotSupport.validateSubmittedSnapshot(decl, snapshot);

    when(entity.getProperty(FiscalDeclCrudHandler.PROPERTY_SUBMITTED_SNAPSHOT))
        .thenReturn(snapshotProperty(2000));
    try {
      FiscalSubmittedSnapshotSupport.validateSubmittedSnapshot(decl, snapshot);
      fail("must reject a snapshot the column cannot hold");
    } catch (ValidationException expected) {
      // rejected before anything is filed
    }
  }
}
