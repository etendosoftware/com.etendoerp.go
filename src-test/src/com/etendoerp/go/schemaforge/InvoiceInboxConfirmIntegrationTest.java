/*
 * *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"), you may not use this file except in compliance with
 * the License.
 * You may obtain a copy of the License at
 * https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * Software distributed under the License is distributed on an
 * "AS IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing rights
 * and limitations under the License.
 * All portions are Copyright © 2021–2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;


import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.query.NativeQuery;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.base.weld.test.WeldBaseTest;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.businesspartner.BusinessPartner;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.model.pricing.pricelist.PriceList;

import com.etendoerp.go.schemaforge.data.IncomingDocument;
import com.etendoerp.go.schemaforge.ocr.InvoiceInboxTotals;
import com.etendoerp.go.schemaforge.util.NeoAccessHelper;
import com.etendoerp.go.schemaforge.util.NeoDateFormat;
import com.etendoerp.go.schemaforge.util.NeoExtensionIndex;

/**
 * Real-database specs of the {@code confirm} action of the invoice inbox: the whole chain (new
 * vendor and address, new products, header and lines through the NEO create pipeline, totals check,
 * link of the row) against the real Application Dictionary, and the engine-versus-model parity of
 * the invoice total for the tax shapes the engine treats differently.
 *
 * <p>Everything runs in the test transaction and is rolled back, nothing is committed. That is the
 * limit of the class and it is deliberate: the completion ({@code ProcessInvoiceUtil.process})
 * commits by design, so {@link InvoiceCompletionService#completeInvoiceOrThrow} and the copy of the
 * original file ({@link InvoiceInboxConfirmAttachment}, which writes bytes to disk) are replaced by
 * recording stubs. What they stand for is covered by their own mock specs. The taxes the engine
 * needs to see in a given shape are inserted by native SQL on the session's own connection (System
 * client, so the row is also visible to the ownership checks) and undone by the rollback. The
 * snapshot of row counts and of the product-key sequence taken before is compared after the
 * rollback: the specs must leave no trace.</p>
 *
 * @covers com.etendoerp.go.schemaforge.InvoiceInboxConfirm
 * @covers com.etendoerp.go.schemaforge.InvoiceInboxConfirmWork
 * @covers com.etendoerp.go.schemaforge.InvoiceInboxConfirmVerify
 * @covers com.etendoerp.go.schemaforge.InvoiceInboxConfirmSeeds
 * @covers com.etendoerp.go.schemaforge.InvoiceInboxConfirmLines
 * @covers com.etendoerp.go.schemaforge.InvoiceInboxConfirmLookups
 */
public class InvoiceInboxConfirmIntegrationTest extends WeldBaseTest {

  private static final String CLIENT_ID = "802509E12436405C86BA1FD5B1DF508C";
  private static final String ORG_ID = "61849243BE89460EB70866880A545D50";
  private static final String USER_ID = "47EAF009B7BB42BBB663C7BA1792D958";
  private static final String ROLE_ID = "9B8D736190724807AB256DC95F20EC5E";
  private static final String EUR = "102";
  private static final String IVA_21 = "A5AE8250883C47498DA5BD73FB5376CB";
  private static final String UOM_METER = "54E1CF8449474628BEA191E0532EEC3E";
  private static final String CATEGORY = "EBAE46FD129049DEB26B948E160C6AD8";
  private static final String TAX_CATEGORY_IVA_21 = "FF80818123B7FC160123B804AB88000B";
  private static final String ID = "id";

  private final String marker = "etgo-confirm-it-" + UUID.randomUUID().toString().substring(0, 8);
  private int counter;
  private String before;
  private MockedStatic<NeoAccessHelper> access;
  private MockedStatic<InvoiceCompletionService> completion;
  private MockedStatic<InvoiceInboxConfirmAttachment> attachment;
  private MockedStatic<InvoiceInboxTelemetry> telemetry;

  @Before
  public void prepareContext() {
    // NeoExtensionIndex is JVM-static and is built from the first Weld container that asks for it.
    // goIsolatedDalTest runs every DAL class in one JVM, each with its own container, so an index
    // left by an earlier class holds beans of a dead container: resolving the header handler (which
    // has @Inject fields) from it fails silently and the invoice is created without the header
    // pre-hook (no accounting date, no exchange rate). Rebuild it for this class's container.
    NeoExtensionIndex.invalidate();
    OBContext.clearAdminModeStack();
    OBContext.setOBContext(USER_ID, ROLE_ID, CLIENT_ID, ORG_ID);
    before = snapshot();
    access = Mockito.mockStatic(NeoAccessHelper.class, inv -> {
      if ("hasWindowAccess".equals(inv.getMethod().getName()) && inv.getArguments().length == 2) {
        return true;
      }
      return inv.callRealMethod();
    });
    completion = Mockito.mockStatic(InvoiceCompletionService.class);
    attachment = Mockito.mockStatic(InvoiceInboxConfirmAttachment.class);
    telemetry = Mockito.mockStatic(InvoiceInboxTelemetry.class);
  }

  @After
  public void cleanUp() {
    NeoExtensionIndex.invalidate();
    telemetry.close();
    attachment.close();
    completion.close();
    access.close();
    OBDal.getInstance().rollbackAndClose();
    OBContext.setOBContext((OBContext) null);
    OBContext.setOBContext(USER_ID, ROLE_ID, CLIENT_ID, ORG_ID);
    try {
      assertEquals("the spec left a trace in the database", before, snapshot());
    } finally {
      OBDal.getInstance().rollbackAndClose();
      OBContext.setOBContext((OBContext) null);
    }
  }

  // ---------------------------------------------------------------- the whole chain

  @Test
  public void aNewVendorWithNewProductsADiscountAndTwoTaxModesBecomesAnInvoiceWithTheLinesTotal()
      throws Exception {
    String lTax = tax("L-10", "L", "10");
    JSONArray lines = new JSONArray()
        .put(newLine("Widget " + marker, "2", "10.00", "12.5", IVA_21, "21", "D"))
        .put(newLine("Gadget " + marker, "3", "7.333333", null, IVA_21, "21", "D"))
        .put(newLine("Service " + marker, "1", "100.10", null, lTax, "10", "L"));
    String id = row("PENDING_REVIEW", document(newVendor(), lines, "EUR-1"), false);
    long vendorsBefore = count("c_bpartner");
    long productsBefore = count("m_product");

    NeoResponse response = InvoiceInboxConfirm.confirm(id, null);

    assertEquals(response.getBody().toString(), 200, response.getHttpStatus());
    JSONObject body = response.getBody();
    assertEquals("CONFIRMED", body.getString("status"));
    assertTrue(body.getBoolean("createdVendor"));
    assertEquals(3, body.getInt("createdProducts"));
    assertEquals(false, body.getBoolean("alreadyConfirmed"));
    String invoiceId = body.getJSONObject("invoice").getString(ID);

    // the row is linked and clean
    assertEquals("CONFIRMED", text("SELECT status FROM etgo_incoming_document WHERE "
        + "etgo_incoming_document_id = :id", id));
    assertEquals(invoiceId, text("SELECT c_invoice_id FROM etgo_incoming_document WHERE "
        + "etgo_incoming_document_id = :id", id));
    assertEquals(null, text("SELECT error_code FROM etgo_incoming_document WHERE "
        + "etgo_incoming_document_id = :id", id));
    assertEquals(null, text("SELECT error_detail FROM etgo_incoming_document WHERE "
        + "etgo_incoming_document_id = :id", id));

    // the vendor, its address and the products are the tenant's, in the row's organization
    assertEquals(vendorsBefore + 1, count("c_bpartner"));
    assertEquals(productsBefore + 3, count("m_product"));
    String vendorId = text("SELECT c_bpartner_id FROM c_invoice WHERE c_invoice_id = :id",
        invoiceId);
    assertEquals(ORG_ID, text("SELECT ad_org_id FROM c_invoice WHERE c_invoice_id = :id",
        invoiceId));
    assertEquals(CLIENT_ID, text("SELECT ad_client_id FROM c_bpartner WHERE c_bpartner_id = :id",
        vendorId));
    assertEquals(marker + " Proveedor, S.L.", text(
        "SELECT name FROM c_bpartner WHERE c_bpartner_id = :id", vendorId));
    assertEquals("01099ABCE98A40BBA20022ABDD10FD2F", text(
        "SELECT c_bp_group_id FROM c_bpartner WHERE c_bpartner_id = :id", vendorId));
    assertEquals(vendorId, text("SELECT c_bpartner_id FROM c_bpartner_location WHERE "
        + "c_bpartner_location_id = (SELECT c_bpartner_location_id FROM c_invoice WHERE "
        + "c_invoice_id = :id)", invoiceId));
    // the three lines reference three NEW products with three distinct, sequential search keys
    assertEquals("3", text("SELECT count(DISTINCT p.value) FROM c_invoiceline l JOIN m_product p "
        + "ON p.m_product_id = l.m_product_id WHERE l.c_invoice_id = :id AND p.name LIKE '%"
        + marker + "'", invoiceId));

    // the invoice total is the lines' total, the number the review panel computes
    JSONObject stored = new JSONObject(text("SELECT current_data FROM etgo_incoming_document "
        + "WHERE etgo_incoming_document_id = :id", id));
    BigDecimal model = InvoiceInboxTotals.totals(stored.getJSONArray("lines"), 2).total();
    BigDecimal engine = new BigDecimal(text("SELECT grandtotal FROM c_invoice WHERE "
        + "c_invoice_id = :id", invoiceId));
    assertTrue("model " + model + " vs engine " + engine,
        model.subtract(engine).abs().compareTo(new BigDecimal("0.02")) <= 0);
    assertEquals(0, new BigDecimal(text("SELECT totallines FROM c_invoice WHERE c_invoice_id = :id",
        invoiceId)).compareTo(new BigDecimal("20.00").multiply(new BigDecimal("0.875"))
            .add(new BigDecimal("22.00")).add(new BigDecimal("100.10"))));

    // the unit of work asked for the completion once, on this invoice, and for the file copy once
    completion.verify(() -> InvoiceCompletionService.completeInvoiceOrThrow(invoiceId,
        OBContext.getOBContext()), Mockito.times(1));
    attachment.verify(() -> InvoiceInboxConfirmAttachment.attach(id, invoiceId),
        Mockito.times(1));
    telemetry.verify(() -> InvoiceInboxTelemetry.documentConfirmed(false, CLIENT_ID),
        Mockito.times(1));
  }

  @Test
  public void adjustedIsTheSameBeforeAndAfterAConfirmForBothValues() throws Exception {
    for (boolean adjusted : new boolean[] { false, true }) {
      JSONArray lines = new JSONArray()
          .put(newLine("K1 " + marker + adjusted, "1", "10.00", null, IVA_21, "21", "D"));
      String id = row("READY", document(newVendor(), lines, "K1-" + adjusted), adjusted);

      NeoResponse response = InvoiceInboxConfirm.confirm(id, null);

      assertEquals(response.getBody().toString(), 200, response.getHttpStatus());
      assertEquals(adjusted, response.getBody().getBoolean("adjusted"));
      assertEquals(adjusted ? "Y" : "N", text("SELECT adjusted FROM etgo_incoming_document WHERE "
          + "etgo_incoming_document_id = :id", id));
    }
  }

  // ---------------------------------------------------------------- the unit rolls back as a whole

  @Test
  public void aRefusedCompletionLeavesNeitherVendorNorProductsNorInvoiceNorSequenceBump()
      throws Exception {
    completion.when(() -> InvoiceCompletionService.completeInvoiceOrThrow(anyString(), any()))
        .thenThrow(new org.openbravo.base.exception.OBException("The period is closed"));
    String sequenceBefore = sequence("DocumentNo_M_Product");
    String bpSequenceBefore = sequence("BusinessPartner-EM_Etgo_Identifier");
    JSONArray lines = new JSONArray()
        .put(newLine("RB-1 " + marker, "1", "10.00", null, IVA_21, "21", "D"))
        .put(newLine("RB-2 " + marker, "1", "20.00", null, IVA_21, "21", "D"));
    String id = row("PENDING_REVIEW", document(newVendor(), lines, "RB-1"), false);
    OBDal.getInstance().commitAndClose();
    long invoicesBefore = count("c_invoice");
    long productsBefore = count("m_product");
    long vendorsBefore = count("c_bpartner");
    long locationsBefore = count("c_bpartner_location");
    try {
      NeoResponse response = InvoiceInboxConfirm.confirm(id, null);

      assertEquals(422, response.getHttpStatus());
      JSONObject error = response.getBody().getJSONObject("error");
      assertEquals("confirm_failed", error.getString("code"));
      assertEquals("The period is closed", error.getString("detail"));
      // nothing the unit created survived: the whole rollback, the sequence included
      assertEquals(0L, scalar("SELECT count(*) FROM c_bpartner WHERE name LIKE '" + marker
          + "%'"));
      assertEquals(0L, scalar("SELECT count(*) FROM m_product WHERE name LIKE '%" + marker
          + "'"));
      assertEquals(invoicesBefore, count("c_invoice"));
      assertEquals(productsBefore, count("m_product"));
      assertEquals(vendorsBefore, count("c_bpartner"));
      assertEquals(locationsBefore, count("c_bpartner_location"));
      assertEquals(sequenceBefore, sequence("DocumentNo_M_Product"));
      assertEquals(bpSequenceBefore, sequence("BusinessPartner-EM_Etgo_Identifier"));
      // the failure is recorded on the row, in its own transaction
      assertEquals("ERROR", text("SELECT status FROM etgo_incoming_document WHERE "
          + "etgo_incoming_document_id = :id", id));
      assertEquals("CONFIRM_FAILED", text("SELECT error_code FROM etgo_incoming_document WHERE "
          + "etgo_incoming_document_id = :id", id));
      assertNotNull(text("SELECT error_detail FROM etgo_incoming_document WHERE "
          + "etgo_incoming_document_id = :id", id));
      // the retry the user asks for works on the same row, and consumes the SAME product keys
      completion.reset();
      NeoResponse retry = InvoiceInboxConfirm.confirm(id, null);

      assertEquals(retry.getBody().toString(), 200, retry.getHttpStatus());
      assertTrue(retry.getBody().getBoolean("createdVendor"));
      assertEquals(sequenceBefore, plain("SELECT min(value) FROM m_product WHERE name LIKE '%"
          + marker + "'").replaceAll("^\\D*", ""));
      assertEquals(null, text("SELECT error_code FROM etgo_incoming_document WHERE "
          + "etgo_incoming_document_id = :id", id));
    } finally {
      deleteCommitted(id);
    }
  }

  // ------------------------------------------------- engine against the model, with real taxes

  @Test
  public void twoTaxesOfTheSameRateInDocumentMode() throws Exception {
    String sameRate = tax("D-21", "D", "21");
    JSONArray lines = new JSONArray()
        .put(newLine("TW-1 " + marker, "1", "0.50", null, IVA_21, "21", "D"))
        .put(newLine("TW-2 " + marker, "1", "0.50", null, sameRate, "21", "D"));

    BigDecimal engine = confirmedTotal(lines, newVendor(), EUR);

    // the model groups by rate (0.21); the engine by tax record (0.11 + 0.11): one unit apart
    assertEquals(0, engine.compareTo(new BigDecimal("1.22")));
  }

  @Test
  public void aLineBasedTaxIsRoundedPerLineAsTheModelDoes() throws Exception {
    String lineTax = tax("L-10", "L", "10");
    JSONArray lines = new JSONArray()
        .put(newLine("LB-1 " + marker, "1", "0.05", null, lineTax, "10", "L"))
        .put(newLine("LB-2 " + marker, "1", "0.05", null, lineTax, "10", "L"))
        .put(newLine("LB-3 " + marker, "1", "0.05", null, lineTax, "10", "L"));

    BigDecimal engine = confirmedTotal(lines, newVendor(), EUR);

    assertEquals(0, engine.compareTo(new BigDecimal("0.18")));
    assertEquals(0, engine.compareTo(InvoiceInboxTotals.totals(lines, 2).total()));
  }

  @Test
  public void aSixDecimalDiscountGivesTheModelsTotal() throws Exception {
    JSONArray lines = new JSONArray()
        .put(newLine("SD-1 " + marker, "7", "9.999999", "12.345678", IVA_21, "21", "D"));

    BigDecimal engine = confirmedTotal(lines, newVendor(), EUR);

    assertEquals(0, engine.compareTo(InvoiceInboxTotals.totals(lines, 2).total()));
  }

  @Test
  public void aSummaryTaxWithChildRatesStaysWithinTheTolerance() throws Exception {
    String summary = "EE2A833CD42247C5BD6ABC41BE0ECD49";
    JSONArray lines = new JSONArray()
        .put(newLine("SM-1 " + marker, "1", "0.50", null, summary, "15", "D"))
        .put(newLine("SM-2 " + marker, "3", "33.33", null, summary, "15", "D"));

    BigDecimal engine = confirmedTotal(lines, newVendor(), EUR);

    BigDecimal model = InvoiceInboxTotals.totals(lines, 2).total();
    assertTrue(engine + " vs " + model,
        engine.subtract(model).abs().compareTo(new BigDecimal("0.01")) <= 0);
  }

  @Test
  public void aNegativeCreditGivesTheNegativeTotalOfTheModel() throws Exception {
    JSONArray lines = new JSONArray()
        .put(newLine("NC-1 " + marker, "-1", "100.00", null, IVA_21, "21", "D"));

    BigDecimal engine = confirmedTotal(lines, newVendor(), EUR);

    assertEquals(0, engine.compareTo(new BigDecimal("-121.00")));
  }

  @Test
  public void aCurrencyWithoutDecimalsIsVerifiedAtItsOwnScale() throws Exception {
    rate("102", "113", "160");
    rate("113", "102", "0.00625");
    JSONArray lines = new JSONArray()
        .put(newLine("JP-1 " + marker, "3", "1234.5", null, IVA_21, "21", "D"))
        .put(newLine("JP-2 " + marker, "1", "99.5", "7.5", IVA_21, "21", "D"));

    BigDecimal engine = confirmedTotal(lines,
        persistedVendor(purchaseList("JPY", "113", false)), "113");

    assertEquals(0, engine.compareTo(engine.setScale(0, java.math.RoundingMode.HALF_UP)));
    assertEquals(0, engine.compareTo(InvoiceInboxTotals.totals(lines, 0).total()));
  }

  // ------------------------------------------------- price lists of a linked vendor

  @Test
  public void aVendorWithATaxIncludedPurchaseListIsRefusedAndNothingIsCreated() throws Exception {
    JSONArray lines = new JSONArray()
        .put(newLine("TI-1 " + marker, "1", "10.00", null, IVA_21, "21", "D"));
    JSONObject vendor = persistedVendor(purchaseList("TI", EUR, true));
    String id = row("PENDING_REVIEW", document(vendor, lines, "TI-1"), false);
    long invoicesBefore = count("c_invoice");
    long productsBefore = count("m_product");

    NeoResponse response = InvoiceInboxConfirm.confirm(id, null);

    assertEquals(response.getBody().toString(), 422, response.getHttpStatus());
    assertEquals("price_list_tax_included",
        response.getBody().getJSONObject("error").getString("code"));
    assertEquals(invoicesBefore, count("c_invoice"));
    assertEquals(productsBefore, count("m_product"));
    assertEquals("PENDING_REVIEW", text("SELECT status FROM etgo_incoming_document WHERE "
        + "etgo_incoming_document_id = :id", id));
  }

  @Test
  public void aVendorWithoutAPurchaseListUsesTheDefaultPurchaseList() throws Exception {
    JSONArray lines = new JSONArray()
        .put(newLine("DL-1 " + marker, "1", "10.00", null, IVA_21, "21", "D"));
    assertTrue("the tenant needs a default purchase price list "
        + "(referencedata/sampledata/GOClient/M_PRICELIST.xml, 'Tarifa de compra principal')",
        scalar("SELECT count(*) FROM m_pricelist WHERE ad_client_id = '" + CLIENT_ID
            + "' AND issopricelist = 'N' AND isdefault = 'Y' AND isactive = 'Y'") >= 1);
    JSONObject vendor = persistedVendor(null);
    String id = row("PENDING_REVIEW", document(vendor, lines, "DL-1"), false);

    NeoResponse response = InvoiceInboxConfirm.confirm(id, null);

    assertEquals(response.getBody().toString(), 200, response.getHttpStatus());
    assertEquals(false, response.getBody().getBoolean("createdVendor"));
    String invoicePriceList = text("SELECT m_pricelist_id FROM c_invoice WHERE c_invoice_id = :id",
        response.getBody().getJSONObject("invoice").getString(ID));
    assertEquals("the invoice takes a default purchase price list of the tenant", 1L,
        scalar("SELECT count(*) FROM m_pricelist WHERE m_pricelist_id = '" + invoicePriceList
            + "' AND ad_client_id = '" + CLIENT_ID + "' AND issopricelist = 'N'"
            + " AND isdefault = 'Y'"));
  }


  // ------------------------------------------------- concurrent actions on one committed row

  @Test
  public void twoConfirmsOfTheSameRowAreSerialisedAndTheSecondOneIsAlreadyConfirmed()
      throws Exception {
    String id = committedRow("PENDING_REVIEW");
    try {
      Race race = new Race(id);
      race.holdTheLock();

      Future<NeoResponse> second = race.pool.submit(() -> inOwnSession(() -> {
        InvoiceInboxConfirmClaim.Opened opened = InvoiceInboxConfirmClaim.open(id, "");
        return opened.response() == null && opened.alreadyConfirmed()
            ? NeoResponse.ok(new JSONObject().put("alreadyConfirmed", true))
            : NeoResponse.error(500, "not the idempotent answer");
      }));
      race.assertBlocked(second);
      race.finish(true);

      assertTrue(second.get(20, TimeUnit.SECONDS).getBody().getBoolean("alreadyConfirmed"));
      assertEquals("CONFIRMED", committedText("status", id));
      assertEquals(race.invoiceId, committedText("c_invoice_id", id));
    } finally {
      deleteCommitted(id);
    }
  }

  @Test
  public void aDiscardRacingAConfirmLosesAndTheRowStaysConfirmed() throws Exception {
    String id = committedRow("PENDING_REVIEW");
    try {
      Race race = new Race(id);
      race.holdTheLock();

      Future<NeoResponse> discard = race.pool.submit(() -> inOwnSession(
          () -> discardAndCommit(id)));
      race.assertBlocked(discard);
      race.finish(true);

      NeoResponse response = discard.get(20, TimeUnit.SECONDS);
      assertEquals(response.getBody().toString(), 409, response.getHttpStatus());
      assertEquals("not_discardable", response.getBody().getJSONObject("error").getString("code"));
      assertEquals("CONFIRMED", committedText("status", id));
      assertNull(committedText("discarded_at", id));
    } finally {
      deleteCommitted(id);
    }
  }

  @Test
  public void aDiscardRacingAConfirmThatFailsWinsTheRow() throws Exception {
    String id = committedRow("PENDING_REVIEW");
    try {
      Race race = new Race(id);
      race.holdTheLock();

      Future<NeoResponse> discard = race.pool.submit(() -> inOwnSession(
          () -> discardAndCommit(id)));
      race.assertBlocked(discard);
      race.finish(false);

      assertEquals(200, discard.get(20, TimeUnit.SECONDS).getHttpStatus());
      assertEquals("DISCARDED", committedText("status", id));
    } finally {
      deleteCommitted(id);
    }
  }

  @Test
  public void anApplyRacingAConfirmIsRefusedAndTheDocumentIsNotTouched() throws Exception {
    String id = committedRow("PENDING_REVIEW");
    try {
      String token = committedToken(id);
      String documentBefore = committedText("current_data", id);
      Race race = new Race(id);
      race.holdTheLock();

      Future<NeoResponse> apply = race.pool.submit(() -> inOwnSession(() -> {
        JSONObject body = new JSONObject().put("updated", token).put("operations",
            new JSONArray().put(new JSONObject("{op:'setHeader',documentNo:'RACE-2'}")));
        return InvoiceInboxApply.apply(id, body);
      }));
      race.assertBlocked(apply);
      race.finish(true);

      NeoResponse response = apply.get(20, TimeUnit.SECONDS);
      assertEquals(response.getBody().toString(), 409, response.getHttpStatus());
      assertEquals("not_editable", response.getBody().getJSONObject("error").getString("code"));
      assertEquals(documentBefore, committedText("current_data", id));
    } finally {
      deleteCommitted(id);
    }
  }

  @Test
  public void aConfirmRacingADiscardIsRefusedAsNotConfirmable() throws Exception {
    String id = committedRow("PENDING_REVIEW");
    try {
      assertEquals(200, onAnotherThread(() -> discardAndCommit(id)).getHttpStatus());

      InvoiceInboxConfirmClaim.Opened opened = onAnotherThread(
          () -> InvoiceInboxConfirmClaim.open(id, ""));

      assertEquals(409, opened.response().getHttpStatus());
      assertEquals("not_confirmable",
          opened.response().getBody().getJSONObject("error").getString("code"));
      assertEquals("DISCARDED", opened.response().getBody().getJSONObject("error")
          .getString("documentStatus"));
    } finally {
      deleteCommitted(id);
    }
  }

  /**
   * One thread that claims the row (taking its lock) and then confirms it or lets it go, on its own
   * session and connection: the test thread and the others contend with it for real.
   */
  private final class Race {
    final ExecutorService pool = Executors.newFixedThreadPool(3);
    final String rowId;
    final String invoiceId = plain("SELECT c_invoice_id FROM c_invoice WHERE ad_client_id = '"
        + CLIENT_ID + "' AND issotrx = 'N' ORDER BY created LIMIT 1");
    private final CountDownLatch locked = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);
    private volatile boolean confirm;
    private Future<Boolean> holder;

    Race(String rowId) {
      this.rowId = rowId;
    }

    void holdTheLock() throws Exception {
      holder = pool.submit(() -> inOwnSession(() -> {
        InvoiceInboxConfirmClaim.Opened opened = InvoiceInboxConfirmClaim.open(rowId, "");
        assertNull(opened.response());
        locked.countDown();
        assertTrue(release.await(30, TimeUnit.SECONDS));
        if (confirm) {
          OBContext.setAdminMode(false);
          try {
            IncomingDocument row = opened.row();
            row.setStatus("CONFIRMED");
            row.setInvoice(OBDal.getInstance().get(Invoice.class, invoiceId));
            OBDal.getInstance().save(row);
            OBDal.getInstance().flush();
          } finally {
            OBContext.restorePreviousMode();
          }
          OBDal.getInstance().commitAndClose();
        } else {
          OBDal.getInstance().rollbackAndClose();
        }
        return true;
      }));
      assertTrue("the first confirm never took the lock", locked.await(30, TimeUnit.SECONDS));
    }

    /** The contender must still be waiting for the lock while the holder has not let go. */
    void assertBlocked(Future<?> contender) throws Exception {
      try {
        contender.get(1500, TimeUnit.MILLISECONDS);
        throw new AssertionError("the contender was not blocked by the lock of the first one");
      } catch (TimeoutException expected) {
        assertFalse(contender.isDone());
      }
    }

    void finish(boolean confirmed) throws Exception {
      confirm = confirmed;
      release.countDown();
      try {
        assertTrue(holder.get(30, TimeUnit.SECONDS));
      } catch (ExecutionException e) {
        throw new AssertionError("the first confirm failed", e.getCause());
      } finally {
        pool.shutdown();
      }
    }
  }

  /** What the request end does for a discard: it commits the answer that wrote. */
  private static NeoResponse discardAndCommit(String id) {
    NeoResponse response = InvoiceInboxDiscard.discard(id);
    if (response.getHttpStatus() == 200) {
      OBDal.getInstance().commitAndClose();
    }
    return response;
  }

  private <T> T onAnotherThread(Callable<T> task) throws Exception {
    ExecutorService single = Executors.newSingleThreadExecutor();
    try {
      return single.submit(() -> inOwnSession(task)).get(30, TimeUnit.SECONDS);
    } finally {
      single.shutdown();
    }
  }

  /** Runs the task on the calling thread with its own session, window access and rolled-back end. */
  private <T> T inOwnSession(Callable<T> task) throws Exception {
    OBContext.clearAdminModeStack();
    OBContext.setOBContext(USER_ID, ROLE_ID, CLIENT_ID, ORG_ID);
    try (MockedStatic<NeoAccessHelper> grant = Mockito.mockStatic(NeoAccessHelper.class, inv -> {
      if ("hasWindowAccess".equals(inv.getMethod().getName()) && inv.getArguments().length == 2) {
        return true;
      }
      return inv.callRealMethod();
    })) {
      return task.call();
    } finally {
      OBDal.getInstance().rollbackAndClose();
      OBContext.setOBContext((OBContext) null);
    }
  }

  /** The row, committed: the one spec fixture that outlives the test transaction. */
  private String committedRow(String status) throws Exception {
    JSONArray lines = new JSONArray()
        .put(newLine("RC-1 " + marker, "1", "10.00", null, IVA_21, "21", "D"));
    String id = row(status, document(newVendor(), lines, "RACE-1"), false);
    OBDal.getInstance().commitAndClose();
    return id;
  }

  private static String committedText(String column, String id) {
    OBDal.getInstance().rollbackAndClose();
    return text("SELECT " + column + " FROM etgo_incoming_document WHERE "
        + "etgo_incoming_document_id = :id", id);
  }

  private static String committedToken(String id) {
    OBDal.getInstance().rollbackAndClose();
    return NeoDateFormat.toAuditToken(
        OBDal.getInstance().get(IncomingDocument.class, id).getUpdated());
  }

  // ---------------------------------------------------------------- helpers

  /** Confirms a document built from the lines and returns the grand total the engine gave it. */
  private BigDecimal confirmedTotal(JSONArray lines, JSONObject vendor, String currencyId)
      throws Exception {
    JSONObject document = document(vendor, lines, "PAR-" + (++counter));
    document.getJSONObject("header").put("currencyId", currencyId);
    String id = row("PENDING_REVIEW", document, false);

    NeoResponse response = InvoiceInboxConfirm.confirm(id, null);

    assertEquals(response.getBody().toString(), 200, response.getHttpStatus());
    String invoiceId = response.getBody().getJSONObject("invoice").getString(ID);
    return new BigDecimal(text("SELECT grandtotal FROM c_invoice WHERE c_invoice_id = :id",
        invoiceId));
  }

  private JSONObject linkedVendor(String bpartnerId) throws Exception {
    return new JSONObject().put("state", "linked").put("id", bpartnerId)
        .put("candidates", new JSONArray()).put("matchedBy", "taxId").put("addressState", "FOUND")
        .put("extracted", new JSONObject().put("name", "linked").put("taxId", ""));
  }

  /**
   * A vendor of the tenant that is already in the database, as a linked vendor of a document. It is
   * created by confirming a throwaway document with a new vendor (the production path, so the address
   * is real), then given the purchase price list, or none. No sampledata vendor is assumed.
   */
  private JSONObject persistedVendor(String purchaseListId) throws Exception {
    JSONArray seed = new JSONArray()
        .put(newLine("FX-V " + marker + counter, "1", "1.00", null, IVA_21, "21", "D"));
    String seedId = row("PENDING_REVIEW", document(newVendor(), seed, "FX-" + (++counter)), false);
    NeoResponse created = InvoiceInboxConfirm.confirm(seedId, null);
    assertEquals(created.getBody().toString(), 200, created.getHttpStatus());
    String vendorId = text("SELECT c_bpartner_id FROM c_invoice WHERE c_invoice_id = :id",
        created.getBody().getJSONObject("invoice").getString(ID));
    OBContext.setAdminMode(false);
    try {
      BusinessPartner partner = OBDal.getInstance().get(BusinessPartner.class, vendorId);
      partner.setPurchasePricelist(purchaseListId == null ? null
          : OBDal.getInstance().get(PriceList.class, purchaseListId));
      OBDal.getInstance().save(partner);
      OBDal.getInstance().flush();
    } finally {
      OBContext.restorePreviousMode();
    }
    return linkedVendor(vendorId);
  }

  /** A purchase price list of the tenant, in the given currency, inserted in the test transaction. */
  private String purchaseList(String label, String currencyId, boolean includesTax) {
    OBContext.setAdminMode(false);
    try {
      String id = (String) OBDal.getInstance().getSession()
          .createNativeQuery("SELECT get_uuid()").uniqueResult();
      OBDal.getInstance().getSession().createNativeQuery(
          "INSERT INTO m_pricelist (m_pricelist_id, ad_client_id, ad_org_id, isactive, createdby,"
              + " updatedby, name, istaxincluded, issopricelist, isdefault, c_currency_id)"
              + " VALUES (:id, :client, :org, 'Y', :user, :user, :name, :tax, 'N', 'N', :currency)")
          .setParameter(ID, id).setParameter("client", CLIENT_ID).setParameter("org", ORG_ID)
          .setParameter("user", USER_ID).setParameter("name", marker + " " + label)
          .setParameter("tax", includesTax ? "Y" : "N").setParameter("currency", currencyId)
          .executeUpdate();
      return id;
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /** A conversion rate valid for the whole of 2026, inserted in the test transaction. */
  private void rate(String from, String to, String multiply) {
    OBContext.setAdminMode(false);
    try {
      OBDal.getInstance().getSession().createNativeQuery(
          "INSERT INTO c_conversion_rate (c_conversion_rate_id, ad_client_id, ad_org_id, isactive,"
              + " createdby, updatedby, c_currency_id, c_currency_id_to, validfrom, validto,"
              + " conversionratetype, multiplyrate, dividerate) VALUES (get_uuid(), :client, '0',"
              + " 'Y', :user, :user, :from, :to, CAST('2026-01-01' AS date),"
              + " CAST('2026-12-31' AS date), 'S', CAST(:mul AS numeric),"
              + " 1 / CAST(:mul AS numeric))")
          .setParameter("client", CLIENT_ID).setParameter("user", USER_ID)
          .setParameter("from", from).setParameter("to", to).setParameter("mul", multiply)
          .executeUpdate();
    } finally {
      OBContext.restorePreviousMode();
    }
  }


  /** A vendor that is new, with an address printed in Spain. */
  private JSONObject newVendor() throws Exception {
    return new JSONObject().put("state", "new").put("candidates", new JSONArray())
        .put("locationId", JSONObject.NULL).put("addressState", "UNKNOWN")
        .put("extracted", new JSONObject().put("name", marker + " Proveedor, S.L.")
            .put("taxId", cif(Math.abs(marker.hashCode()) % 10000000))
            .put("address", "Calle Mayor 1").put("postalCode", "50004").put("city", "Zaragoza")
            .put("country", "").put("email", "").put("phone", ""));
  }

  /** A Spanish company tax id of type B with a valid control digit, from a seven-digit number. */
  private static String cif(int sevenDigits) {
    String digits = String.format("%07d", sevenDigits);
    int sum = 0;
    for (int i = 0; i < 7; i++) {
      int digit = digits.charAt(i) - '0';
      if (i % 2 == 0) {
        digit *= 2;
        sum += digit / 10 + digit % 10;
      } else {
        sum += digit;
      }
    }
    return "B" + digits + (10 - sum % 10) % 10;
  }

  private JSONObject newLine(String name, String qty, String price, String discount, String taxId,
      String rate, String mode) throws Exception {
    JSONObject line = new JSONObject().put("index", counter++)
        .put("description", name).put("quantity", new BigDecimal(qty))
        .put("unitPrice", new BigDecimal(price)).put("taxRate", new BigDecimal(rate))
        .put("taxLabel", "")
        .put("tax", new JSONObject().put(ID, taxId).put("rate", new BigDecimal(rate))
            .put("state", "resolved").put("matchedBy", "manual").put("docTaxAmount", mode))
        .put("product", new JSONObject().put("id", JSONObject.NULL).put("state", "new")
            .put("candidates", new JSONArray())
            .put("new", new JSONObject().put("name", name).put("uomId", UOM_METER)
                .put("categoryId", CATEGORY)));
    if (discount != null) {
      line.put("discount", new BigDecimal(discount));
    }
    return line;
  }

  private JSONObject document(JSONObject vendor, JSONArray lines, String number)
      throws Exception {
    BigDecimal total = InvoiceInboxTotals.totals(lines, 2).total();
    return new JSONObject().put("version", 1).put("lines", lines).put("notices", new JSONArray())
        .put("header", new JSONObject().put("vendor", vendor)
            .put("receiver", new JSONObject().put("check", "match"))
            .put("duplicate", new JSONObject().put("state", "none")
                .put("inbox", new JSONArray()).put("invoices", new JSONArray()))
            .put("currencyId", EUR).put("documentNo", number).put("invoiceDate", "2026-11-02")
            .put("totalAmount", total).put("totalSource", "printed"))
        .put("validation", new JSONObject().put("valid", true).put("missing", new JSONArray()));
  }

  /** A System tax of purchases, in the given document-amount mode, inserted in the test transaction. */
  private String tax(String label, String mode, String rate) {
    OBContext.setAdminMode(false);
    try {
      String id = (String) OBDal.getInstance().getSession()
          .createNativeQuery("SELECT get_uuid()").uniqueResult();
      NativeQuery<?> insert = OBDal.getInstance().getSession().createNativeQuery(
          "INSERT INTO c_tax (c_tax_id, ad_client_id, ad_org_id, isactive, createdby, updatedby, "
              + "name, validfrom, rate, c_taxcategory_id, sopotype, doctaxamount) "
              + "VALUES (:id, '0', '0', 'Y', :user, :user, :name, CAST('2026-01-01' AS date), "
              + "CAST(:rate AS numeric), :category, 'P', :mode)");
      insert.setParameter(ID, id);
      insert.setParameter("user", USER_ID);
      insert.setParameter("name", marker + " " + label);
      insert.setParameter("rate", rate);
      insert.setParameter("category", TAX_CATEGORY_IVA_21);
      insert.setParameter("mode", mode);
      insert.executeUpdate();
      return id;
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  private String row(String status, JSONObject document, boolean adjusted) throws Exception {
    OBContext.setAdminMode(false);
    try {
      IncomingDocument row = OBProvider.getInstance().get(IncomingDocument.class);
      row.setClient(OBDal.getInstance().get(Client.class, CLIENT_ID));
      row.setOrganization(OBDal.getInstance().get(Organization.class, ORG_ID));
      row.setActive(true);
      row.setFileName(marker + "-" + (++counter) + ".pdf");
      row.setSource("UPLOAD");
      row.setTargetType("PURCHASE_INVOICE");
      row.setStatus(status);
      row.setAdjusted(adjusted);
      row.setReviewed(false);
      row.setProcessingAttempts(0L);
      row.setCurrentData(document.toString());
      row.setExtractedData(document.toString());
      OBDal.getInstance().save(row);
      OBDal.getInstance().flush();
      return row.getId();
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  private static String text(String sql, String id) {
    NativeQuery<?> query = OBDal.getInstance().getSession().createNativeQuery(sql);
    query.setParameter(ID, id);
    Object value = query.uniqueResult();
    return value == null ? null : String.valueOf(value);
  }

  private static String plain(String sql) {
    return String.valueOf(OBDal.getInstance().getSession().createNativeQuery(sql).uniqueResult());
  }

  private static long scalar(String sql) {
    return ((Number) OBDal.getInstance().getSession().createNativeQuery(sql).uniqueResult())
        .longValue();
  }

  private static long count(String table) {
    return scalar("SELECT count(*) FROM " + table);
  }

  private static String sequence(String name) {
    return String.valueOf(OBDal.getInstance().getSession().createNativeQuery(
        "SELECT currentnext FROM ad_sequence WHERE name = '" + name + "' AND ad_client_id = '"
            + CLIENT_ID + "'").uniqueResult());
  }

  /** The rows and sequences a confirmation could leave behind, as one comparable string. */
  private static String snapshot() {
    return String.valueOf(OBDal.getInstance().getSession().createNativeQuery(
        "SELECT (SELECT count(*) FROM c_invoice) || '/' || (SELECT count(*) FROM c_bpartner)"
            + " || '/' || (SELECT count(*) FROM c_bpartner_location)"
            + " || '/' || (SELECT count(*) FROM m_product) || '/' || (SELECT count(*) FROM c_file)"
            + " || '/' || (SELECT string_agg(name || '=' || currentnext, ',' ORDER BY name)"
            + " FROM ad_sequence WHERE name IN ('DocumentNo_M_Product', 'BusinessPartner-EM_Etgo_Identifier', 'AP Invoice')"
            + " AND ad_client_id = '" + CLIENT_ID + "')"
            + " || '/' || (SELECT string_agg(status || '=' || n, ',' ORDER BY status) FROM"
            + " (SELECT status, count(*) n FROM etgo_incoming_document GROUP BY status) s)")
        .uniqueResult());
  }

  /** Removes a row the spec committed, by its exact id, in its own transaction. */
  private static void deleteCommitted(String id) {
    OBDal.getInstance().rollbackAndClose();
    OBContext.setAdminMode(false);
    try {
      OBDal.getInstance().getSession().createNativeQuery(
          "DELETE FROM etgo_incoming_document WHERE etgo_incoming_document_id = :id")
          .setParameter(ID, id).executeUpdate();
      OBDal.getInstance().commitAndClose();
    } finally {
      OBContext.restorePreviousMode();
    }
  }
}
