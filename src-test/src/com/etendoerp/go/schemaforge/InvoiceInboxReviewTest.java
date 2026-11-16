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
 * All portions are Copyright (C) 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.openbravo.dal.core.OBContext;
import org.openbravo.erpCommon.utility.OBMessageUtils;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.ad.utility.Attachment;
import org.openbravo.model.common.currency.Currency;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.common.invoice.Invoice;

import com.etendoerp.go.schemaforge.data.IncomingDocument;
import com.etendoerp.go.schemaforge.ocr.OcrLookups;
import com.etendoerp.go.schemaforge.util.NeoDateFormat;

/**
 * The review answer of the invoice inbox (the {@code review} action): status gates, the
 * {@code editable} rule, the stored document with its verdict recomputed and the read-time
 * enrichment (one query per kind, never persisted), the invoice, attachment and currency blocks.
 * The DAL, the tenant scope and the lookups are mocked.
 *
 * @covers com.etendoerp.go.schemaforge.InvoiceInboxReview
 */
class InvoiceInboxReviewTest {

  private static final String ID = "ROW-1";

  private MockedStatic<TenantOwnership> tenant;
  private MockedStatic<NeoAttachmentsHelper> attachments;
  private MockedStatic<OBContext> obContext;
  private MockedStatic<OBMessageUtils> messages;
  private MockedStatic<OcrLookupsRegistry> registry;
  private IncomingDocument row;
  private OcrLookups lookups;
  private Client client;

  @BeforeEach
  void open() {
    row = mock(IncomingDocument.class);
    when(row.getId()).thenReturn(ID);
    when(row.getStatus()).thenReturn("PENDING_REVIEW");
    when(row.getTargetType()).thenReturn("PURCHASE_INVOICE");
    lookups = mock(OcrLookups.class);
    client = mock(Client.class);
    OBContext context = mock(OBContext.class);
    when(context.getCurrentClient()).thenReturn(client);
    tenant = mockStatic(TenantOwnership.class);
    tenant.when(() -> TenantOwnership.loadOwned(IncomingDocument.class, ID)).thenReturn(row);
    tenant.when(() -> TenantOwnership.isVisibleToCurrentTenant(any())).thenReturn(true);
    attachments = mockStatic(NeoAttachmentsHelper.class);
    attachments.when(() -> NeoAttachmentsHelper.resolveTableId(anyString())).thenReturn("T1");
    obContext = mockStatic(OBContext.class);
    obContext.when(OBContext::getOBContext).thenReturn(context);
    messages = mockStatic(OBMessageUtils.class);
    registry = mockStatic(OcrLookupsRegistry.class);
    registry.when(() -> OcrLookupsRegistry.forResolver(anyString()))
        .thenReturn(Optional.of(lookups));
  }

  @AfterEach
  void close() {
    registry.close();
    messages.close();
    obContext.close();
    attachments.close();
    tenant.close();
  }

  private JSONObject ok() throws Exception {
    NeoResponse response = InvoiceInboxReview.review(ID);
    assertEquals(200, response.getHttpStatus());
    return response.getBody();
  }

  private static String errorCode(NeoResponse response) throws Exception {
    return response.getBody().getJSONObject("error").getString("code");
  }

  // ------------------------------------------------------------------ gates

  @Test
  void anUnknownOrForeignIdIs404NotFound() throws Exception {
    tenant.when(() -> TenantOwnership.loadOwned(IncomingDocument.class, "OTHER")).thenReturn(null);

    NeoResponse response = InvoiceInboxReview.review("OTHER");

    assertEquals(404, response.getHttpStatus());
    assertEquals("not_found", errorCode(response));
  }

  @Test
  void aProcessingRowIs409AndIsNotRead() throws Exception {
    when(row.getStatus()).thenReturn("PROCESSING");

    NeoResponse response = InvoiceInboxReview.review(ID);

    assertEquals(409, response.getHttpStatus());
    assertEquals("processing", errorCode(response));
    verify(row, never()).getCurrentData();
  }

  // ------------------------------------------------------------------ editable

  @Test
  void editableIsTrueOnlyInTheStatesAPersonCanEdit() throws Exception {
    for (String status : List.of("PENDING_REVIEW", "ERROR", "READY")) {
      when(row.getStatus()).thenReturn(status);
      assertTrue(ok().getBoolean("editable"), status);
    }
    for (String status : List.of("CONFIRMED", "DISCARDED")) {
      when(row.getStatus()).thenReturn(status);
      assertFalse(ok().getBoolean("editable"), status);
    }
  }

  @Test
  void aForeignReceiverErrorIsDiscardOnlyNotEditable() throws Exception {
    when(row.getStatus()).thenReturn("ERROR");
    when(row.getErrorCode()).thenReturn("FOREIGN_RECEIVER");

    JSONObject body = ok();

    assertFalse(body.getBoolean("editable"));
    assertEquals("FOREIGN_RECEIVER", body.getString("errorCode"));
  }

  @Test
  void anErrorWithAnotherCodeStaysEditable() throws Exception {
    when(row.getStatus()).thenReturn("ERROR");
    when(row.getErrorCode()).thenReturn("MISSING_FIELDS");

    assertTrue(ok().getBoolean("editable"));
  }

  // ------------------------------------------------------------------ row block

  @Test
  void theRowBlockCopiesStatusFlagsTextsAndAuditTokens() throws Exception {
    Date updated = new Date(1_700_000_000_123L);
    Date created = new Date(1_690_000_000_000L);
    when(row.isReviewed()).thenReturn(true);
    when(row.isAdjusted()).thenReturn(null);
    when(row.getUpdated()).thenReturn(updated);
    when(row.getCreationDate()).thenReturn(created);
    when(row.getFileName()).thenReturn("a.pdf");
    when(row.getSender()).thenReturn("x@y.z");
    when(row.getSource()).thenReturn("EMAIL");

    JSONObject body = ok();

    assertEquals(ID, body.getString("id"));
    assertEquals("PENDING_REVIEW", body.getString("status"));
    assertTrue(body.isNull("errorCode"));
    assertTrue(body.getBoolean("reviewed"));
    assertFalse(body.getBoolean("adjusted"));
    assertEquals(NeoDateFormat.toAuditToken(updated), body.getString("updated"));
    assertEquals(NeoDateFormat.toAuditToken(created), body.getString("created"));
    assertTrue(body.getString("updated").contains(".123"));
    assertEquals("a.pdf", body.getString("fileName"));
    assertEquals("x@y.z", body.getString("sender"));
    assertEquals("EMAIL", body.getString("source"));
  }

  @Test
  void nullDatesAndTextsAnswerJsonNullAndEmptyStrings() throws Exception {
    JSONObject body = ok();

    assertTrue(body.isNull("updated"));
    assertTrue(body.isNull("created"));
    assertEquals("", body.getString("fileName"));
    assertEquals("", body.getString("sender"));
    assertEquals("", body.getString("source"));
  }

  @Test
  void invoiceIsNullWithoutALinkAndIdentifiedWithOne() throws Exception {
    assertTrue(ok().isNull("invoice"));

    Invoice invoice = mock(Invoice.class);
    when(invoice.getId()).thenReturn("INV-1");
    when(invoice.getDocumentNo()).thenReturn("F-001");
    when(invoice.getIdentifier()).thenReturn("F-001 - 2026-01-01 - 10.00");
    when(row.getInvoice()).thenReturn(invoice);

    JSONObject out = ok().getJSONObject("invoice");
    assertEquals("INV-1", out.getString("id"));
    assertEquals("F-001", out.getString("documentNo"));
    assertEquals("F-001 - 2026-01-01 - 10.00", out.getString("identifier"));
  }

  // ------------------------------------------------------------------ attachment

  private Attachment attachment(String name, String dataType) {
    Attachment attachment = mock(Attachment.class);
    when(attachment.getId()).thenReturn("ATT-1");
    when(attachment.getName()).thenReturn(name);
    when(attachment.getDataType()).thenReturn(dataType);
    attachments.when(() -> NeoAttachmentsHelper.findMainAttachment("T1", ID))
        .thenReturn(attachment);
    return attachment;
  }

  @Test
  void noMainAttachmentAnswersNull() throws Exception {
    attachments.when(() -> NeoAttachmentsHelper.findMainAttachment("T1", ID)).thenReturn(null);

    assertTrue(ok().isNull("attachment"));
  }

  @Test
  void anAttachmentNotVisibleToTheTenantAnswersNull() throws Exception {
    Attachment attachment = attachment("a.pdf", "application/pdf");
    tenant.when(() -> TenantOwnership.isVisibleToCurrentTenant(attachment)).thenReturn(false);

    assertTrue(ok().isNull("attachment"));
  }

  @Test
  void aFailingAttachmentLookupNeverFailsTheCall() throws Exception {
    attachments.when(() -> NeoAttachmentsHelper.findMainAttachment("T1", ID))
        .thenThrow(new IllegalStateException("boom"));

    assertTrue(ok().isNull("attachment"));
  }

  @Test
  void theStoredMimeTypeWinsWhenItHasASlash() throws Exception {
    attachment("a.bin", "image/webp");

    JSONObject out = ok().getJSONObject("attachment");
    assertEquals("ATT-1", out.getString("id"));
    assertEquals("a.bin", out.getString("name"));
    assertEquals("image/webp", out.getString("mimeType"));
  }

  @Test
  void theMimeTypeIsDerivedFromTheExtensionOtherwise() throws Exception {
    String[][] cases = { { "A.PDF", "pdf", "application/pdf" }, { "a.png", null, "image/png" },
        { "a.jpg", "", "image/jpeg" }, { "a.JPEG", "x", "image/jpeg" },
        { "a.txt", "txt", "application/octet-stream" }, { null, null, "application/octet-stream" } };
    for (String[] c : cases) {
      attachment(c[0], c[1]);
      assertEquals(c[2], ok().getJSONObject("attachment").getString("mimeType"), c[0]);
    }
  }

  // ------------------------------------------------------------------ currency

  private static Currency currency(String id, String iso, String symbol) {
    Currency currency = mock(Currency.class);
    when(currency.getId()).thenReturn(id);
    when(currency.getISOCode()).thenReturn(iso);
    when(currency.getSymbol()).thenReturn(symbol);
    return currency;
  }

  @Test
  void theOrganizationCurrencyWinsOverTheClientOne() throws Exception {
    Organization org = mock(Organization.class);
    Currency eur = currency("EUR-ID", "EUR", "E");
    Currency usd = currency("USD-ID", "USD", "$");
    when(org.getCurrency()).thenReturn(eur);
    when(row.getOrganization()).thenReturn(org);
    when(client.getCurrency()).thenReturn(usd);

    assertEquals("EUR", ok().getJSONObject("currency").getString("isoCode"));
  }

  @Test
  void theClientCurrencyIsTheFallback() throws Exception {
    Organization org = mock(Organization.class);
    Currency usd = currency("USD-ID", "USD", "$");
    when(row.getOrganization()).thenReturn(org);
    when(client.getCurrency()).thenReturn(usd);

    JSONObject out = ok().getJSONObject("currency");
    assertEquals("USD-ID", out.getString("id"));
    assertEquals("$", out.getString("symbol"));
  }

  @Test
  void noCurrencyAnywhereAnswersNulls() throws Exception {
    JSONObject out = ok().getJSONObject("currency");

    assertTrue(out.isNull("id"));
    assertTrue(out.isNull("isoCode"));
    assertTrue(out.isNull("symbol"));
  }

  // ------------------------------------------------------------------ document

  private static void assertSkeleton(JSONObject document) throws Exception {
    assertEquals(1, document.getInt("version"));
    JSONObject header = document.getJSONObject("header");
    assertEquals("none", header.getJSONObject("vendor").getString("state"));
    assertEquals(0, document.getJSONArray("lines").length());
    assertEquals("lines", header.getString("totalSource"));
    assertEquals("unknown", header.getJSONObject("duplicate").getString("state"));
    assertEquals("absent", header.getJSONObject("receiver").getString("check"));
    assertFalse(document.getJSONObject("validation").getBoolean("valid"));
    assertEquals(new JSONArray(List.of("vendor", "invoiceDate", "lines")).toString(),
        document.getJSONObject("validation").getJSONArray("missing").toString());
  }

  @Test
  void blankNullOrUnparseableCurrentDataAnswersTheSkeleton() throws Exception {
    for (String stored : new String[] { null, "", "   ", "{not json", "[1,2]" }) {
      when(row.getCurrentData()).thenReturn(stored);
      assertSkeleton(ok().getJSONObject("document"));
    }
  }

  @Test
  void theStoredVerdictIsRecomputedAndNothingIsPersisted() throws Exception {
    // a stale verdict claims valid; the document has no vendor, no date, no lines
    when(row.getCurrentData()).thenReturn("{\"version\":1,\"header\":{},\"lines\":[],"
        + "\"validation\":{\"valid\":true,\"missing\":[]}}");

    JSONObject document = ok().getJSONObject("document");

    assertFalse(document.getJSONObject("validation").getBoolean("valid"));
    assertTrue(document.getJSONObject("validation").getJSONArray("missing").length() > 0);
    verify(row, never()).setCurrentData(anyString());
    verify(row, never()).setStatus(anyString());
  }

  @Test
  void aHugeDocumentIsAnsweredWithoutFailing() throws Exception {
    StringBuilder lines = new StringBuilder("[");
    for (int i = 0; i < 6000; i++) {
      lines.append(i == 0 ? "" : ",").append("{\"description\":\"")
          .append("x".repeat(100)).append("\",\"quantity\":1}");
    }
    lines.append("]");
    when(row.getCurrentData()).thenReturn("{\"version\":1,\"header\":{},\"lines\":" + lines + "}");

    JSONObject document = ok().getJSONObject("document");

    assertEquals(6000, document.getJSONArray("lines").length());
    assertFalse(document.getJSONObject("validation").getBoolean("valid"));
  }

  // ------------------------------------------------------------------ enrichment

  private static String doc(String vendor, String lines) {
    return "{\"version\":1,\"header\":{\"vendor\":" + vendor + "},\"lines\":" + lines + "}";
  }

  @Test
  void vendorAndProductsAreEnrichedWithOneQueryPerKind() throws Exception {
    when(row.getCurrentData()).thenReturn(doc(
        "{\"state\":\"linked\",\"id\":\"V1\",\"name\":\"Acme\",\"candidates\":["
            + "{\"id\":\"V2\",\"name\":\"Other\"},{\"id\":\"V3\",\"name\":\"Third\"}]}",
        "[{\"product\":{\"state\":\"linked\",\"id\":\"P1\",\"candidates\":["
            + "{\"id\":\"P2\",\"name\":\"Old\"}]}},"
            + "{\"product\":{\"state\":\"ambiguous\",\"candidates\":[{\"id\":\"P3\"}]}}]"));
    when(lookups.vendorDetails(anyCollection())).thenReturn(Map.of(
        "V1", new OcrLookups.VendorDetails("Acme SL", "B123", "Madrid"),
        "V2", new OcrLookups.VendorDetails("Other SL", "B2", "Sevilla")));
    when(lookups.productDetails(anyCollection())).thenReturn(Map.of(
        "P1", new OcrLookups.ProductDetails("Pen", "PEN-1", "Office", "Unit"),
        "P2", new OcrLookups.ProductDetails("Pencil", "PNC-1", "Office", "Box")));

    JSONObject document = ok().getJSONObject("document");

    verify(lookups, times(1)).vendorDetails(anyCollection());
    verify(lookups, times(1)).productDetails(anyCollection());
    @SuppressWarnings("unchecked")
    ArgumentCaptor<Collection<String>> vendorIds = ArgumentCaptor.forClass(Collection.class);
    verify(lookups).vendorDetails(vendorIds.capture());
    assertEquals(List.of("V1", "V2", "V3"), List.copyOf(vendorIds.getValue()));
    @SuppressWarnings("unchecked")
    ArgumentCaptor<Collection<String>> productIds = ArgumentCaptor.forClass(Collection.class);
    verify(lookups).productDetails(productIds.capture());
    assertEquals(List.of("P1", "P2", "P3"), List.copyOf(productIds.getValue()));

    JSONObject vendor = document.getJSONObject("header").getJSONObject("vendor");
    JSONObject details = vendor.getJSONObject("details");
    assertEquals("Acme SL", details.getString("name"));
    assertEquals("B123", details.getString("taxId"));
    assertEquals("Madrid", details.getString("city"));
    assertEquals("Sevilla", vendor.getJSONArray("candidates").getJSONObject(0).getString("city"));
    assertFalse(vendor.getJSONArray("candidates").getJSONObject(1).has("city"));

    JSONArray lines = document.getJSONArray("lines");
    JSONObject p1 = lines.getJSONObject(0).getJSONObject("product");
    assertEquals("Pen", p1.getJSONObject("details").getString("name"));
    assertEquals("PEN-1", p1.getJSONObject("details").getString("searchKey"));
    assertEquals("Office", p1.getJSONObject("details").getString("category"));
    assertEquals("Unit", p1.getJSONObject("details").getString("uom"));
    JSONObject candidate = p1.getJSONArray("candidates").getJSONObject(0);
    assertEquals("PNC-1", candidate.getString("searchKey"));
    assertEquals("Office", candidate.getString("category"));
    assertEquals("Box", candidate.getString("uom"));
    assertEquals("Pencil", candidate.getString("name"));
    // the unresolved candidate (other tenant / inactive) gets nothing and nothing fails
    JSONObject unresolved = lines.getJSONObject(1).getJSONObject("product")
        .getJSONArray("candidates").getJSONObject(0);
    assertFalse(unresolved.has("searchKey"));
    verify(row, never()).setCurrentData(anyString());
  }

  @Test
  void theNamesOfANewProductsCategoryAndUnitAreResolvedFromTheStoredIdsAtReadTime()
      throws Exception {
    when(row.getCurrentData()).thenReturn(doc("{\"state\":\"none\",\"candidates\":[]}",
        "[{\"product\":{\"state\":\"new\",\"new\":{\"name\":\"X\",\"categoryId\":\"C1\","
            + "\"category\":\"Stale\",\"uomId\":\"U1\",\"uom\":\"Stale\"}}},"
            + "{\"product\":{\"state\":\"new\",\"new\":{\"categoryId\":\"GONE\","
            + "\"category\":\"Stale\"}}}]"));
    when(lookups.productCategoryById("C1"))
        .thenReturn(Optional.of(new OcrLookups.Party("C1", "Bebidas")));
    when(lookups.uomById("U1")).thenReturn(Optional.of(new OcrLookups.Party("U1", "Unidad")));
    when(lookups.productCategoryById("GONE")).thenReturn(Optional.empty());

    org.codehaus.jettison.json.JSONArray lines = ok().getJSONObject("document")
        .getJSONArray("lines");
    JSONObject first = lines.getJSONObject(0).getJSONObject("product").getJSONObject("new");
    JSONObject second = lines.getJSONObject(1).getJSONObject("product").getJSONObject("new");

    assertEquals("Bebidas", first.getString("category"));
    assertEquals("Unidad", first.getString("uom"));
    assertFalse(second.has("category"), "an id that no longer resolves leaves no (stale) name");
    verify(row, never()).setCurrentData(anyString());
  }

  @Test
  void theNameOfALinesTaxIsResolvedFromItsIdAtReadTime() throws Exception {
    when(row.getCurrentData()).thenReturn(doc("{\"state\":\"none\",\"candidates\":[]}",
        "[{\"tax\":{\"state\":\"resolved\",\"id\":\"T1\",\"name\":\"Stale\",\"rate\":21}},"
            + "{\"tax\":{\"state\":\"pending_vendor\"}}]"));
    when(lookups.taxNames(anyCollection())).thenReturn(Map.of("T1", "IVA 21%"));

    org.codehaus.jettison.json.JSONArray lines = ok().getJSONObject("document")
        .getJSONArray("lines");

    assertEquals("IVA 21%", lines.getJSONObject(0).getJSONObject("tax").getString("name"));
    assertFalse(lines.getJSONObject(1).getJSONObject("tax").has("name"));
    verify(row, never()).setCurrentData(anyString());
  }

  @Test
  void theCurrencyBlockCarriesTheScaleOfTheStoredCurrencyOrOfTheOneDerivedWithoutWriting()
      throws Exception {
    when(lookups.currencyById("JPY")).thenReturn(Optional.of(
        new OcrLookups.DocCurrency("JPY", "JPY", "Y", 0)));
    when(lookups.documentCurrency(eq("V1"), any())).thenReturn(Optional.of(
        new OcrLookups.DocCurrency("USD", "USD", "$", 2)));
    String vendor = "{\"state\":\"linked\",\"id\":\"V1\",\"candidates\":[]}";

    when(row.getCurrentData()).thenReturn(doc(vendor, "[]")
        .replace("\"header\":{", "\"header\":{\"currencyId\":\"JPY\","));
    JSONObject stored = ok().getJSONObject("currency");
    assertEquals("JPY", stored.getString("id"));
    assertEquals(0, stored.getInt("scale"));

    when(row.getCurrentData()).thenReturn(doc(vendor, "[]"));
    JSONObject derived = ok().getJSONObject("currency");
    assertEquals("USD", derived.getString("isoCode"));
    assertEquals(2, derived.getInt("scale"));
    verify(row, never()).setCurrentData(anyString());
  }

  @Test
  void theTaxRoundingModeIsStampedOnTheLinesOfTheAnswer() throws Exception {
    when(row.getCurrentData()).thenReturn(doc("{\"state\":\"none\",\"candidates\":[]}",
        "[{\"tax\":{\"state\":\"resolved\",\"id\":\"T1\"}},{\"tax\":{\"state\":\"resolved\","
            + "\"id\":\"T2\"}}]"));
    when(lookups.taxDocAmountModes(anyCollection())).thenReturn(Map.of("T1", "L"));

    org.codehaus.jettison.json.JSONArray lines = ok().getJSONObject("document")
        .getJSONArray("lines");

    assertEquals("L", lines.getJSONObject(0).getJSONObject("tax").getString("docTaxAmount"));
    assertEquals("D", lines.getJSONObject(1).getJSONObject("tax").getString("docTaxAmount"));
  }

  @Test
  void aTaxThatDoesNotResolveKeepsWhatTheDocumentHoldsAsPlainText() throws Exception {
    String hostile = "<script>alert(1)</script> ' OR 1=1 --";
    when(row.getCurrentData()).thenReturn(doc("{\"state\":\"none\",\"candidates\":[]}",
        "[{\"tax\":{\"state\":\"resolved\",\"id\":\"GONE\",\"name\":"
            + JSONObject.quote(hostile) + ",\"rate\":21}}]"));
    when(lookups.taxNames(anyCollection())).thenReturn(Map.of());

    JSONObject tax = ok().getJSONObject("document").getJSONArray("lines").getJSONObject(0)
        .getJSONObject("tax");

    assertEquals(hostile, tax.getString("name"), "copied as data, never interpreted");
    assertEquals("GONE", tax.getString("id"));
    verify(row, never()).setCurrentData(anyString());
  }

  @Test
  void aLineWithoutATaxIdIsNeverAskedForANameNorAMode() throws Exception {
    when(row.getCurrentData()).thenReturn(doc("{\"state\":\"none\",\"candidates\":[]}",
        "[{\"tax\":{\"state\":\"pending_vendor\"}},{\"description\":\"x\"}]"));

    ok();

    verify(lookups, never()).taxNames(anyCollection());
    verify(lookups, never()).taxDocAmountModes(anyCollection());
  }

  @Test
  void aFailingTaxModeLookupNeverFailsTheCallAndEveryTaxReadsAsD() throws Exception {
    when(row.getCurrentData()).thenReturn(doc("{\"state\":\"none\",\"candidates\":[]}",
        "[{\"tax\":{\"state\":\"resolved\",\"id\":\"T1\"}}]"));
    when(lookups.taxNames(anyCollection())).thenReturn(Map.of("T1", "IVA 21%"));
    when(lookups.taxDocAmountModes(anyCollection())).thenThrow(new IllegalStateException("boom"));

    JSONObject tax = ok().getJSONObject("document").getJSONArray("lines").getJSONObject(0)
        .getJSONObject("tax");

    assertEquals("IVA 21%", tax.getString("name"), "the enrichment before it is kept");
    assertFalse(tax.has("docTaxAmount"), "nothing is stamped: the totals read it as D");
  }

  @Test
  void aFailingTaxNameLookupNeverFailsTheCall() throws Exception {
    when(row.getCurrentData()).thenReturn(doc("{\"state\":\"none\",\"candidates\":[]}",
        "[{\"tax\":{\"state\":\"resolved\",\"id\":\"T1\",\"name\":\"Kept\"}}]"));
    when(lookups.taxNames(anyCollection())).thenThrow(new IllegalStateException("boom"));

    JSONObject tax = ok().getJSONObject("document").getJSONArray("lines").getJSONObject(0)
        .getJSONObject("tax");

    assertEquals("Kept", tax.getString("name"));
  }

  @Test
  void aStoredCurrencyThatCannotBeReadFallsBackToTheOrganizationWithTwoDecimals()
      throws Exception {
    Organization org = mock(Organization.class);
    Currency eur = currency("EUR-ID", "EUR", "E");
    when(org.getCurrency()).thenReturn(eur);
    when(row.getOrganization()).thenReturn(org);
    when(row.getCurrentData()).thenReturn(doc("{\"state\":\"none\",\"candidates\":[]}", "[]")
        .replace("\"header\":{", "\"header\":{\"currencyId\":\"GONE\","));
    when(lookups.currencyById("GONE")).thenReturn(Optional.empty());

    JSONObject currency = ok().getJSONObject("currency");

    assertEquals("EUR", currency.getString("isoCode"));
    assertEquals(2, currency.getInt("scale"));
  }

  @Test
  void aFailingCurrencyLookupFallsBackToTheOrganizationAndNeverFailsTheCall() throws Exception {
    Organization org = mock(Organization.class);
    Currency eur = currency("EUR-ID", "EUR", "E");
    when(org.getCurrency()).thenReturn(eur);
    when(row.getOrganization()).thenReturn(org);
    when(lookups.documentCurrency(any(), any())).thenThrow(new IllegalStateException("boom"));

    JSONObject currency = ok().getJSONObject("currency");

    assertEquals("EUR", currency.getString("isoCode"));
    assertEquals(2, currency.getInt("scale"));
  }

  @Test
  void withoutLookupsTheCurrencyIsTheOrganizationsWithTwoDecimals() throws Exception {
    registry.when(() -> OcrLookupsRegistry.forResolver(anyString())).thenReturn(Optional.empty());
    Organization org = mock(Organization.class);
    Currency eur = currency("EUR-ID", "EUR", "E");
    when(org.getCurrency()).thenReturn(eur);
    when(row.getOrganization()).thenReturn(org);

    JSONObject currency = ok().getJSONObject("currency");

    assertEquals("EUR", currency.getString("isoCode"));
    assertEquals(2, currency.getInt("scale"));
  }

  @Test
  void theCurrencyIsDerivedWithTheOrganizationOfTheRowNotTheSessions() throws Exception {
    Organization rowOrganization = mock(Organization.class);
    when(rowOrganization.getId()).thenReturn("ROW-ORG");
    when(row.getOrganization()).thenReturn(rowOrganization);
    when(lookups.documentCurrency(any(), any())).thenReturn(Optional.empty());
    when(row.getCurrentData()).thenReturn(doc("{\"state\":\"none\"}", "[]"));

    ok();

    verify(lookups).documentCurrency(isNull(), eq("ROW-ORG"));
  }

  @Test
  void noCurrencyAnywhereStillAnswersAScaleOfTwo() throws Exception {
    assertEquals(2, ok().getJSONObject("currency").getInt("scale"));
  }

  @Test
  void theDerivedCurrencyIsAskedWithTheLinkedVendorOnlyAndNothingIsWritten() throws Exception {
    when(lookups.documentCurrency(any(), any())).thenReturn(Optional.empty());

    when(row.getCurrentData()).thenReturn(doc("{\"state\":\"linked\",\"id\":\"V1\"}", "[]"));
    ok();
    verify(lookups).documentCurrency(eq("V1"), any());

    when(row.getCurrentData()).thenReturn(doc("{\"state\":\"ambiguous\",\"id\":\"V1\"}",
        "[]"));
    ok();
    verify(lookups).documentCurrency(isNull(), any());
    verify(row, never()).setCurrentData(anyString());
  }

  @Test
  void aStoredCurrencyIsReadByIdAndTheVendorIsNotConsulted() throws Exception {
    when(lookups.currencyById("JPY")).thenReturn(Optional.of(
        new OcrLookups.DocCurrency("JPY", "JPY", null, 0)));
    when(row.getCurrentData()).thenReturn(doc("{\"state\":\"linked\",\"id\":\"V1\"}", "[]")
        .replace("\"header\":{", "\"header\":{\"currencyId\":\"JPY\","));

    JSONObject currency = ok().getJSONObject("currency");

    assertTrue(currency.isNull("symbol"));
    assertEquals(0, currency.getInt("scale"));
    verify(lookups, never()).documentCurrency(any(), any());
  }

  @Test
  void aCreditDocumentWithNegativeLinesAndTotalIsAValidDocumentInTheAnswer() throws Exception {
    when(row.getCurrentData()).thenReturn("{\"version\":1,\"header\":{\"vendor\":{\"state\":"
        + "\"linked\",\"id\":\"V1\"},\"invoiceDate\":\"2026-11-02\",\"totalAmount\":-121,"
        + "\"receiver\":{\"check\":\"absent\"}},\"lines\":[{\"index\":0,\"quantity\":-2,"
        + "\"unitPrice\":50,\"product\":{\"state\":\"linked\",\"id\":\"P1\"},"
        + "\"tax\":{\"state\":\"resolved\",\"id\":\"T21\"}}]}");

    JSONObject validation = ok().getJSONObject("document").getJSONObject("validation");

    assertTrue(validation.getBoolean("valid"), validation.toString());
  }

  @Test
  void anUnlinkedVendorGetsNoDetailsBlock() throws Exception {
    when(row.getCurrentData()).thenReturn(doc(
        "{\"state\":\"new\",\"id\":\"V1\",\"candidates\":[]}", "[]"));
    when(lookups.vendorDetails(anyCollection()))
        .thenReturn(Map.of("V1", new OcrLookups.VendorDetails("A", "B", "C")));

    JSONObject vendor = ok().getJSONObject("document").getJSONObject("header")
        .getJSONObject("vendor");

    assertFalse(vendor.has("details"));
  }

  @Test
  void nonStringIdsAndMissingFieldsAreTreatedAsData() throws Exception {
    when(row.getCurrentData()).thenReturn(doc(
        "{\"state\":\"linked\",\"id\":42,\"candidates\":[null,{\"id\":\"\"},{\"id\":{\"a\":1}},7]}",
        "[{\"description\":\"no product\"},null,{\"product\":{\"state\":\"linked\"}},"
            + "{\"product\":{\"candidates\":\"oops\"}}]"));

    JSONObject document = ok().getJSONObject("document");

    assertEquals(4, document.getJSONArray("lines").length());
    assertTrue(document.has("validation"));
  }

  @Test
  void textsInTheDocumentAreCopiedNotInterpreted() throws Exception {
    String hostile = "<script>alert(1)</script> ${jndi:x} =cmd|' /c calc'!A0 {{x}}";
    when(row.getCurrentData()).thenReturn(doc(
        "{\"state\":\"none\",\"extracted\":{\"name\":" + JSONObject.quote(hostile) + "}}", "[]"));

    JSONObject extracted = ok().getJSONObject("document").getJSONObject("header")
        .getJSONObject("vendor").getJSONObject("extracted");

    assertEquals(hostile, extracted.getString("name"));
  }

  @Test
  void aFailingLookupNeverFailsTheCall() throws Exception {
    when(row.getCurrentData()).thenReturn(doc("{\"state\":\"linked\",\"id\":\"V1\"}", "[]"));
    when(lookups.vendorDetails(anyCollection())).thenThrow(new IllegalStateException("boom"));

    JSONObject vendor = ok().getJSONObject("document").getJSONObject("header")
        .getJSONObject("vendor");

    assertFalse(vendor.has("details"));
  }

  @Test
  void noLookupsForTheTargetMeansNoEnrichmentButAnAnswer() throws Exception {
    registry.when(() -> OcrLookupsRegistry.forResolver(anyString())).thenReturn(Optional.empty());
    when(row.getCurrentData()).thenReturn(doc("{\"state\":\"linked\",\"id\":\"V1\"}", "[]"));

    JSONObject vendor = ok().getJSONObject("document").getJSONObject("header")
        .getJSONObject("vendor");

    assertFalse(vendor.has("details"));
    verify(lookups, never()).vendorDetails(anyCollection());
  }

  @Test
  void anUnknownTargetTypeSkipsTheEnrichment() throws Exception {
    when(row.getTargetType()).thenReturn("SOMETHING_ELSE");
    when(row.getCurrentData()).thenReturn(doc("{\"state\":\"linked\",\"id\":\"V1\"}", "[]"));

    ok();

    verify(lookups, never()).vendorDetails(anyCollection());
    verify(lookups, never()).productDetails(anyCollection());
  }

  // ---------------------------------------------------------------- shared with apply (ETP-6050)

  @Test
  void isEditableIsTheOneRuleOfTheEditingActions() {
    assertTrue(InvoiceInboxReview.isEditable("PENDING_REVIEW", null));
    assertTrue(InvoiceInboxReview.isEditable("READY", null));
    assertTrue(InvoiceInboxReview.isEditable("ERROR", "UNREADABLE"));
    assertTrue(InvoiceInboxReview.isEditable("ERROR", "MISSING_FIELDS"));
    assertFalse(InvoiceInboxReview.isEditable("ERROR", "FOREIGN_RECEIVER"));
    for (String status : new String[] { "PROCESSING", "CONFIRMED", "DISCARDED", "", "NOPE" }) {
      assertFalse(InvoiceInboxReview.isEditable(status, null), status);
    }
  }

  @Test
  void theSkeletonIsTheEmptyVersionOneDocumentWithTotalFromTheLines() throws Exception {
    JSONObject skeleton = InvoiceInboxReview.skeleton();

    assertEquals(1, skeleton.getInt("version"));
    assertEquals(0, skeleton.getJSONArray("lines").length());
    assertEquals(0, skeleton.getJSONArray("notices").length());
    JSONObject header = skeleton.getJSONObject("header");
    assertEquals("none", header.getJSONObject("vendor").getString("state"));
    assertEquals("lines", header.getString("totalSource"));
    assertEquals("unknown", header.getJSONObject("duplicate").getString("state"));
    assertTrue(header.isNull("invoiceDate"));
    assertFalse(InvoiceInboxValidator.validate(skeleton).isValid());
  }

  @Test
  void lookupsForAnUnknownOrMissingTargetTypeIsEmpty() {
    assertTrue(InvoiceInboxReview.lookupsFor(null).isEmpty());
    assertTrue(InvoiceInboxReview.lookupsFor("NOT_A_TARGET").isEmpty());
  }
}
