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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.businesspartner.BusinessPartner;
import org.openbravo.model.common.enterprise.DocumentType;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.module.sii.data.AEATSIIConfig;
import org.openbravo.module.sii.utils.SIIUtils;

import com.etendoerp.go.schemaforge.util.NeoActionContract;

/**
 * Tests for {@link InvoiceSifPreset}, the SIF fields of the invoices created AND completed in one
 * call (from an order, a quotation, a shipment, a receipt or a return): which fiscal systems
 * apply, the value each field defaults to, how the caller's {@code sifFields} are validated and
 * merged, and how a refusal rolls the whole creation back.
 *
 * @covers com.etendoerp.go.schemaforge.InvoiceSifPreset
 */
public class InvoiceSifPresetTest {

  private static final String ORG_ID = "org-6179";

  private static InvoiceSifPreset.Facts salesFacts(boolean rectificative, String docInvType) {
    return new InvoiceSifPreset.Facts(true, rectificative, true, true, true, docInvType, null,
        false, false, null, null, "2026-11-11", null, null);
  }

  /**
   * A rectificative generated from a return used to complete as F1 with no SII motive and no
   * TicketBAI code. With no sifFields at all (nobody reviewed the step), every active system
   * gets the R4 fallback.
   */
  @Test
  public void aRectificativeWithNothingSentGetsR4InEveryActiveSystem() {
    Map<String, Object> values = InvoiceSifPreset.merge(salesFacts(true, "F1"), null);

    assertEquals("R4", values.get(Invoice.PROPERTY_ETVFACINVTYPE));
    assertEquals("R4", values.get(Invoice.PROPERTY_AEATSIIMOTIVORECTIF));
    assertEquals("R4", values.get(Invoice.PROPERTY_TBAIREVERSEINVOICECODE));
  }

  /**
   * A document type configured F2 used to yield F1, because the entity default made the
   * completion's COALESCE(invoice, docType) copy a no-op.
   */
  @Test
  public void aNormalInvoiceTakesTheVerifactuTypeOfItsDocumentType() {
    Map<String, Object> values = InvoiceSifPreset.merge(salesFacts(false, "F2"), null);

    assertEquals("F2", values.get(Invoice.PROPERTY_ETVFACINVTYPE));
    assertFalse(values.containsKey(Invoice.PROPERTY_AEATSIIMOTIVORECTIF));
  }

  /**
   * QA BUG-1: a caller that sends no sifFields (MCP, REST) got only the corrective defaults, so the
   * invoice kept e.g. the creation path's VERI*FACTU description ("Ventas") while sifDefaults
   * previewed the document type's ("Rectificaciones de Ventas"). Every previewed field is applied;
   * the rectification codes, which the preview leaves empty, are the only ones that differ: they
   * take the unattended fallback.
   */
  @Test
  public void nothingSentAppliesEveryPreviewedFieldExactly() {
    InvoiceSifPreset.Facts facts = new InvoiceSifPreset.Facts(true, true, true, true, true, "R1",
        "Rectificaciones de Ventas", false, false, null, "Devoluciones", "2026-11-12", null, null);

    Map<String, Object> values = InvoiceSifPreset.merge(facts, null);

    Map<String, Object> expected = new java.util.LinkedHashMap<>(InvoiceSifPreset.defaults(facts));
    expected.putAll(InvoiceSifPreset.unattendedRectificationFallback(facts));
    assertEquals(expected, values);
    assertEquals("Rectificaciones de Ventas", values.get(Invoice.PROPERTY_ETVFACVERIFACDESC));
    assertEquals("Devoluciones", values.get(Invoice.PROPERTY_AEATSIIDESCRIPCIONSII));
    assertEquals("2026-11-12", values.get(Invoice.PROPERTY_ETSGDATEOPERATION));
  }

  /** A field that IS sent overrides the preview; the rest still take the preview's value. */
  @Test
  public void aSentFieldOverridesThePreviewAndTheRestKeepIt() throws Exception {
    InvoiceSifPreset.Facts facts = salesFacts(false, "F1");

    Map<String, Object> values = InvoiceSifPreset.merge(facts,
        new JSONObject().put(Invoice.PROPERTY_ETVFACINVTYPE, "F2"));

    assertEquals("F2", values.get(Invoice.PROPERTY_ETVFACINVTYPE));
    assertEquals("2026-11-11", values.get(Invoice.PROPERTY_ETSGDATEOPERATION));
    assertEquals(InvoiceSifPreset.defaults(facts).keySet(), values.keySet());
  }

  /** End to end on the invoice: the rectificative is written R4 before the completion. */
  @Test
  public void applyBeforeCompletionWritesR4OnARectificativeInvoice() throws Exception {
    Invoice invoice = mock(Invoice.class);
    Organization org = mock(Organization.class);
    when(org.getId()).thenReturn(ORG_ID);
    when(invoice.getOrganization()).thenReturn(org);
    when(invoice.isSalesTransaction()).thenReturn(true);
    DocumentType docType = mock(DocumentType.class);
    when(docType.getEtvfacInvType()).thenReturn("F1");
    when(invoice.getTransactionDocument()).thenReturn(docType);

    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class);
         MockedStatic<RectificativeSupport> rectifMock = Mockito.mockStatic(RectificativeSupport.class);
         MockedStatic<SalesRectificativeFiscalDefaults> defaultsMock =
             Mockito.mockStatic(SalesRectificativeFiscalDefaults.class);
         MockedStatic<RectificationFiscalCodeGuard> guardMock =
             Mockito.mockStatic(RectificationFiscalCodeGuard.class)) {
      OBDal dal = mock(OBDal.class);
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      rectifMock.when(() -> RectificativeSupport.isRectificative(any())).thenReturn(true);
      defaultsMock.when(() -> SalesRectificativeFiscalDefaults.siiConfigActive(anyString()))
          .thenReturn(false);
      defaultsMock.when(() -> SalesRectificativeFiscalDefaults.tbaiConfigActive(ORG_ID))
          .thenReturn(true);
      defaultsMock.when(() -> SalesRectificativeFiscalDefaults.verifactuConfigActive(ORG_ID))
          .thenReturn(true);
      guardMock.when(() -> RectificationFiscalCodeGuard.evaluate(invoice)).thenReturn(null);

      InvoiceSifPreset.applyBeforeCompletion(invoice, new JSONObject());

      verify(invoice).set(Invoice.PROPERTY_ETVFACINVTYPE, "R4");
      verify(invoice).set(Invoice.PROPERTY_TBAIREVERSEINVOICECODE, "R4");
      guardMock.verify(() -> RectificationFiscalCodeGuard.evaluate(invoice));
    }
  }

  // ---------------------------------------------------------------------------
  // Fixtures for the database-backed paths (resolveFacts / applyBeforeCompletion)
  // ---------------------------------------------------------------------------

  /**
   * The static boundaries {@link InvoiceSifPreset#resolveFacts} and
   * {@link InvoiceSifPreset#applyBeforeCompletion} cross, stubbed per test. Every fiscal system is
   * configured unless a test says otherwise; the SII configuration has no authorization number.
   */
  private static final class Env implements AutoCloseable {
    final OBDal dal = mock(OBDal.class);
    final AEATSIIConfig siiConfig = mock(AEATSIIConfig.class);
    private final MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class);
    private final MockedStatic<RectificativeSupport> rectifMock =
        Mockito.mockStatic(RectificativeSupport.class);
    final MockedStatic<SalesRectificativeFiscalDefaults> defaultsMock =
        Mockito.mockStatic(SalesRectificativeFiscalDefaults.class);
    /** {@code null} when the real guard runs ({@link #Env(boolean, boolean)}). */
    final MockedStatic<RectificationFiscalCodeGuard> guardMock;
    private final MockedStatic<SIIUtils> siiMock = Mockito.mockStatic(SIIUtils.class);

    Env(boolean rectificative) {
      this(rectificative, false);
    }

    /**
     * @param realGuard {@code true} lets {@link RectificationFiscalCodeGuard#evaluate} run for
     *     real, with VERI*FACTU's configuration active too, so its 422 is the one answered
     */
    Env(boolean rectificative, boolean realGuard) {
      guardMock = realGuard ? null : Mockito.mockStatic(RectificationFiscalCodeGuard.class);
      if (realGuard) {
        defaultsMock.when(() -> SalesRectificativeFiscalDefaults.configExists(anyString(),
            anyString())).thenReturn(true);
      }
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      rectifMock.when(() -> RectificativeSupport.isRectificative(any())).thenReturn(rectificative);
      defaultsMock.when(() -> SalesRectificativeFiscalDefaults.siiConfigActive(anyString()))
          .thenReturn(true);
      defaultsMock.when(() -> SalesRectificativeFiscalDefaults.tbaiConfigActive(anyString()))
          .thenReturn(true);
      defaultsMock.when(() -> SalesRectificativeFiscalDefaults.verifactuConfigActive(anyString()))
          .thenReturn(true);
      siiMock.when(() -> SIIUtils.getSiiConfigFromOrg(any())).thenReturn(siiConfig);
      if (guardMock != null) {
        guardMock.when(() -> RectificationFiscalCodeGuard.evaluate(any())).thenReturn(null);
      }
    }

    @Override
    public void close() {
      siiMock.close();
      if (guardMock != null) {
        guardMock.close();
      }
      defaultsMock.close();
      rectifMock.close();
      dalMock.close();
    }
  }

  private static Organization org() {
    Organization org = mock(Organization.class);
    when(org.getId()).thenReturn(ORG_ID);
    return org;
  }

  private static Invoice invoice(boolean sales, String docInvType) {
    Invoice invoice = mock(Invoice.class);
    Organization org = org();
    when(invoice.getOrganization()).thenReturn(org);
    when(invoice.isSalesTransaction()).thenReturn(sales);
    DocumentType docType = mock(DocumentType.class);
    when(docType.getEtvfacInvType()).thenReturn(docInvType);
    when(invoice.getTransactionDocument()).thenReturn(docType);
    return invoice;
  }

  private static InvoiceSifPreset.Facts purchaseFacts(boolean rectificative) {
    return new InvoiceSifPreset.Facts(false, rectificative, true, false, false, null, null,
        false, false, null, null, "2026-11-11", "2026-11-12", null);
  }

  /** A call expected to be refused (the module's JUnit 4.12 has no assertThrows). */
  private interface Call {
    void run() throws Exception;
  }

  private static InvoiceSifPreset.Refused refusal(String label, Call call) throws Exception {
    try {
      call.run();
    } catch (InvoiceSifPreset.Refused refused) {
      return refused;
    }
    fail((label != null ? label + ": " : "") + "expected a Refused");
    return null;
  }

  private static JSONObject body(InvoiceSifPreset.Refused refused) {
    return refused.toResponse().getBody();
  }

  private static void assertRefusedAs(String messageKey, String field,
      InvoiceSifPreset.Refused refused) throws JSONException {
    NeoResponse response = refused.toResponse();
    assertEquals(NeoActionContract.SC_UNPROCESSABLE, response.getHttpStatus());
    JSONObject body = response.getBody();
    assertFalse(body.getBoolean("success"));
    assertEquals(messageKey, body.getJSONArray("messageKeys").getString(0));
    if (field != null) {
      assertEquals(field, body.getJSONObject("messageParams").getString("field"));
    }
  }

  // ---------------------------------------------------------------------------
  // Active systems: SII both ways, TicketBAI and VERI*FACTU on sales only
  // ---------------------------------------------------------------------------

  /** A sales rectificative lists every active system, in SII/TicketBAI/VERI*FACTU order. */
  @Test
  public void aSalesRectificativeListsEveryActiveSystemInOrder() {
    assertEquals(List.of("SII", "TicketBAI", "Verifactu"),
        InvoiceSifPreset.systems(salesFacts(true, "F1")));
  }

  /** TicketBAI only asks for a rectification code, so a normal sales invoice has no TBAI step. */
  @Test
  public void ticketBaiHasNoFieldOnANormalSalesInvoice() {
    InvoiceSifPreset.Facts facts = salesFacts(false, "F1");

    assertEquals(List.of("SII", "Verifactu"), InvoiceSifPreset.systems(facts));
    assertFalse(InvoiceSifPreset.defaults(facts)
        .containsKey(Invoice.PROPERTY_TBAIREVERSEINVOICECODE));
  }

  /**
   * A purchase never carries VERI*FACTU or TicketBAI, even with both configured for its
   * organization: their configuration is not even read, and only SII's purchase fields remain.
   */
  @Test
  public void aPurchaseNeverResolvesVerifactuOrTicketBaiEvenWhenTheyAreConfigured() {
    try (Env env = new Env(true)) {
      InvoiceSifPreset.Facts facts = InvoiceSifPreset.resolveFacts(
          new InvoiceSifPreset.Target(org(), null, mock(DocumentType.class), false, null));

      assertTrue(facts.siiActive());
      assertFalse(facts.tbaiActive());
      assertFalse(facts.verifactuActive());
      assertEquals(List.of("SII"), InvoiceSifPreset.systems(facts));
      Map<String, Object> defaults = InvoiceSifPreset.defaults(facts);
      assertEquals("F1", defaults.get(Invoice.PROPERTY_AEATSIICLAVETIPOFC));
      assertTrue(defaults.containsKey(Invoice.PROPERTY_AEATSIIFECHAREGCONT));
      for (String key : defaults.keySet()) {
        assertFalse(key, key.startsWith("etvfac") || key.startsWith("tbai"));
      }
      env.defaultsMock.verify(
          () -> SalesRectificativeFiscalDefaults.tbaiConfigActive(anyString()), never());
      env.defaultsMock.verify(
          () -> SalesRectificativeFiscalDefaults.verifactuConfigActive(anyString()), never());
    }
  }

  /** A VERI*FACTU or TicketBAI field sent for a purchase is refused, not silently dropped. */
  @Test
  public void aPurchaseRefusesVerifactuAndTicketBaiFields() throws Exception {
    for (String field : List.of(Invoice.PROPERTY_ETVFACINVTYPE,
        Invoice.PROPERTY_TBAIREVERSEINVOICECODE)) {
      InvoiceSifPreset.Refused refused = refusal(field,
          () -> InvoiceSifPreset.merge(purchaseFacts(true), new JSONObject().put(field, "R4")));
      assertRefusedAs(InvoiceSifPreset.MSG_FIELD_INVALID, field, refused);
    }
  }

  // ---------------------------------------------------------------------------
  // Defaults
  // ---------------------------------------------------------------------------

  /** VERI*FACTU type of a normal invoice: the doc type's F1/F2, else F1 (never an R code). */
  @Test
  public void aNormalInvoiceFallsBackToF1WhenItsDocumentTypeHasNoNormalType() {
    assertEquals("F1", InvoiceSifPreset.defaultVerifactuInvType(salesFacts(false, null)));
    assertEquals("F1", InvoiceSifPreset.defaultVerifactuInvType(salesFacts(false, "  ")));
    assertEquals("F1", InvoiceSifPreset.defaultVerifactuInvType(salesFacts(false, "R1")));
    assertEquals("F1", InvoiceSifPreset.defaultVerifactuInvType(salesFacts(false, "F3")));
    assertEquals("F2", InvoiceSifPreset.defaultVerifactuInvType(salesFacts(false, " F2 ")));
    assertEquals("F1", InvoiceSifPreset.defaultVerifactuInvType(salesFacts(false, "F1")));
  }

  /**
   * A rectificative has no preselected VERI*FACTU type, whatever its document type configures:
   * the field carries the rectification code there, so it must not read as an ordinary F type.
   */
  @Test
  public void aRectificativeHasNoPreselectedVerifactuTypeWhateverItsDocumentTypeSays() {
    assertNull(InvoiceSifPreset.defaultVerifactuInvType(salesFacts(true, "F2")));
    assertNull(InvoiceSifPreset.defaultVerifactuInvType(salesFacts(true, "R1")));
  }

  private static final List<String> RECTIFICATION_KEYS = List.of(
      Invoice.PROPERTY_AEATSIIMOTIVORECTIF, Invoice.PROPERTY_TBAIREVERSEINVOICECODE,
      Invoice.PROPERTY_ETVFACINVTYPE);

  /**
   * The defaults of a sales rectificative carry the three rectification codes as present keys with
   * no value: never R4, and VERI*FACTU's type never F1, whatever the document type configures.
   */
  @Test
  public void theDefaultsOfASalesRectificativeLeaveEveryRectificationCodeEmpty() {
    for (String docInvType : java.util.Arrays.asList("F1", "R4", null)) {
      Map<String, Object> defaults = InvoiceSifPreset.defaults(salesFacts(true, docInvType));

      for (String key : RECTIFICATION_KEYS) {
        assertTrue(docInvType + "/" + key, defaults.containsKey(key));
        assertNull(docInvType + "/" + key, defaults.get(key));
      }
    }
  }

  /**
   * The sifDefaults preview of a sales rectificative shows the three rectification codes as JSON
   * null (not absent, not R4, not F1), while VERI*FACTU's type still offers R1-R5 only.
   */
  @Test
  public void thePreviewOfASalesRectificativeShowsNoCodeButOffersR1ToR5() throws Exception {
    try (Env env = new Env(true)) {
      JSONObject preview = InvoiceSifPreset.preview(
          new InvoiceSifPreset.Target(org(), null, invoice(true, "F1").getTransactionDocument(),
              true, null));

      assertTrue(preview.getBoolean("rectificative"));
      JSONObject fields = preview.getJSONObject("fields");
      for (String key : RECTIFICATION_KEYS) {
        assertTrue(key, fields.has(key));
        assertTrue(key, fields.isNull(key));
      }
      JSONArray specs = preview.getJSONArray("fieldSpecs");
      List<String> vfOptions = null;
      for (int i = 0; i < specs.length(); i++) {
        JSONObject spec = specs.getJSONObject(i);
        if (Invoice.PROPERTY_ETVFACINVTYPE.equals(spec.getString("name"))) {
          JSONArray options = spec.getJSONArray("options");
          vfOptions = new java.util.ArrayList<>();
          for (int j = 0; j < options.length(); j++) {
            vfOptions.add(options.getString(j));
          }
        }
      }
      assertEquals(List.of("R1", "R2", "R3", "R4", "R5"), vfOptions);
    }
  }

  // ---------------------------------------------------------------------------
  // The unattended fallback (no sifFields at all)
  // ---------------------------------------------------------------------------

  /** An empty sifFields object is "nothing sent": the rectificative gets R4 in every system. */
  @Test
  public void anEmptySifFieldsObjectGetsTheUnattendedFallback() {
    Map<String, Object> values = InvoiceSifPreset.merge(salesFacts(true, "F1"), new JSONObject());

    for (String key : RECTIFICATION_KEYS) {
      assertEquals(key, "R4", values.get(key));
    }
  }

  /** The fallback never applies to a normal sales invoice nor to a purchase. */
  @Test
  public void theFallbackIsEmptyOnAnInvoiceThatIsNotASalesRectificative() {
    assertTrue(InvoiceSifPreset.unattendedRectificationFallback(salesFacts(false, "F1")).isEmpty());
    assertTrue(InvoiceSifPreset.unattendedRectificationFallback(purchaseFacts(true)).isEmpty());

    Map<String, Object> values = InvoiceSifPreset.merge(salesFacts(false, "F2"), null);
    assertEquals("F2", values.get(Invoice.PROPERTY_ETVFACINVTYPE));
    assertFalse(values.containsValue("R4"));
  }

  /** The fallback names only the fiscal systems active for the invoice. */
  @Test
  public void theFallbackIsLimitedToTheActiveSystems() {
    assertEquals(Map.of(Invoice.PROPERTY_AEATSIIMOTIVORECTIF, "R4"),
        InvoiceSifPreset.unattendedRectificationFallback(rectificativeWith(true, false, false)));
    assertEquals(Map.of(Invoice.PROPERTY_TBAIREVERSEINVOICECODE, "R4"),
        InvoiceSifPreset.unattendedRectificationFallback(rectificativeWith(false, true, false)));
    assertEquals(Map.of(Invoice.PROPERTY_ETVFACINVTYPE, "R4"),
        InvoiceSifPreset.unattendedRectificationFallback(rectificativeWith(false, false, true)));
    assertTrue(InvoiceSifPreset.unattendedRectificationFallback(
        rectificativeWith(false, false, false)).isEmpty());

    Map<String, Object> values = InvoiceSifPreset.merge(rectificativeWith(false, true, false), null);
    assertEquals("R4", values.get(Invoice.PROPERTY_TBAIREVERSEINVOICECODE));
    assertFalse(values.containsKey(Invoice.PROPERTY_AEATSIIMOTIVORECTIF));
    assertFalse(values.containsKey(Invoice.PROPERTY_ETVFACINVTYPE));
  }

  private static InvoiceSifPreset.Facts rectificativeWith(boolean sii, boolean tbai,
      boolean verifactu) {
    return new InvoiceSifPreset.Facts(true, true, sii, tbai, verifactu, "F1", null, false, false,
        null, null, "2026-11-11", null, null);
  }

  /** sifFields {} on the request body is the same as sending none: R4 is written. */
  @Test
  public void applyBeforeCompletionWithAnEmptyOrNullSifFieldsWritesR4() throws Exception {
    for (Object sifFields : List.of(new JSONObject(), JSONObject.NULL)) {
      Invoice invoice = invoice(true, "F1");

      try (Env env = new Env(true)) {
        InvoiceSifPreset.applyBeforeCompletion(invoice,
            new JSONObject().put(InvoiceSifPreset.PARAM_SIF_FIELDS, sifFields));

        for (String key : RECTIFICATION_KEYS) {
          verify(invoice).set(key, "R4");
        }
      }
    }
  }

  /**
   * End to end on a sales rectificative with every system active and no sifFields: R4 is written
   * (the unattended fallback) as SII motive,
   * TicketBAI code and VERI*FACTU type, the structural SII key R and type I are set, and the
   * TicketBAI / VERI*FACTU reverse types are computed.
   */
  @Test
  public void aRectificativeGetsR4InEverySystemAndItsStructuralFields() throws Exception {
    Invoice invoice = invoice(true, "F1");

    try (Env env = new Env(true)) {
      InvoiceSifPreset.applyBeforeCompletion(invoice, null);

      verify(invoice).set(Invoice.PROPERTY_AEATSIIMOTIVORECTIF, "R4");
      verify(invoice).set(Invoice.PROPERTY_TBAIREVERSEINVOICECODE, "R4");
      verify(invoice).set(Invoice.PROPERTY_ETVFACINVTYPE, "R4");
      verify(invoice).setAeatsiiClaveTipo("R");
      verify(invoice).setAeatsiiTipoRectif("I");
      env.defaultsMock.verify(() -> SalesRectificativeFiscalDefaults.computeAndSet(invoice));
      verify(env.dal).save(invoice);
      verify(env.dal).flush();
    }
  }

  /** The structural SII type keeps a value the invoice already has (S stays S). */
  @Test
  public void aRectificativeKeepsItsOwnSiiRectificationType() throws Exception {
    Invoice invoice = invoice(true, "F1");
    when(invoice.getAeatsiiTipoRectif()).thenReturn("S");

    try (Env env = new Env(true)) {
      InvoiceSifPreset.applyBeforeCompletion(invoice, new JSONObject());

      verify(invoice).setAeatsiiClaveTipo("R");
      verify(invoice, never()).setAeatsiiTipoRectif(anyString());
    }
  }

  /** A normal invoice has no structural corrective field set. */
  @Test
  public void aNormalInvoiceGetsNoStructuralCorrectiveField() throws Exception {
    Invoice invoice = invoice(true, "F2");

    try (Env env = new Env(false)) {
      InvoiceSifPreset.applyBeforeCompletion(invoice, null);

      verify(invoice).set(Invoice.PROPERTY_ETVFACINVTYPE, "F2");
      verify(invoice, never()).setAeatsiiClaveTipo(anyString());
      env.defaultsMock.verify(() -> SalesRectificativeFiscalDefaults.computeAndSet(any()),
          never());
    }
  }

  /** The SII key is the customer's own default key when it declares a sales one, else F1. */
  @Test
  public void theSiiKeyIsTheCustomersOwnWhenItDeclaresOne() {
    try (Env env = new Env(false)) {
      assertEquals("F4", siiKeyFor(partner(true, true, "F4")));
      assertEquals("F1", siiKeyFor(partner(true, false, "F4")));
      assertEquals("F1", siiKeyFor(partner(false, true, "F4")));
      assertEquals("F1", siiKeyFor(partner(true, true, "F6")));
      assertEquals("F1", siiKeyFor(null));
    }
  }

  private static BusinessPartner partner(boolean customer, boolean ownKey, String key) {
    BusinessPartner partner = mock(BusinessPartner.class);
    when(partner.isCustomer()).thenReturn(customer);
    when(partner.isAeatsiiDefaultsiikey()).thenReturn(ownKey);
    when(partner.getAeatsiiSiikeylist()).thenReturn(key);
    return partner;
  }

  private static Object siiKeyFor(BusinessPartner partner) {
    InvoiceSifPreset.Facts facts = InvoiceSifPreset.resolveFacts(
        new InvoiceSifPreset.Target(org(), partner, mock(DocumentType.class), true, null));
    return InvoiceSifPreset.defaults(facts).get(Invoice.PROPERTY_AEATSIICLAVETIPO);
  }

  // ---------------------------------------------------------------------------
  // merge: what the caller sends
  // ---------------------------------------------------------------------------

  /**
   * Once sifFields is sent, the fallback is off: a rectification code that is sent is written,
   * and the ones left out stay empty, as the preview shows them, for the guard to refuse.
   */
  @Test
  public void aSentSifFieldsLeavesTheOmittedRectificationCodesEmpty() throws Exception {
    Map<String, Object> values = InvoiceSifPreset.merge(salesFacts(true, "F1"),
        new JSONObject().put(Invoice.PROPERTY_AEATSIIMOTIVORECTIF, "R1"));

    assertEquals("R1", values.get(Invoice.PROPERTY_AEATSIIMOTIVORECTIF));
    assertNull(values.get(Invoice.PROPERTY_TBAIREVERSEINVOICECODE));
    assertNull(values.get(Invoice.PROPERTY_ETVFACINVTYPE));
  }

  /**
   * Explicit nulls, or a sifFields carrying only non-rectification fields, are a reviewed step:
   * no fallback, the codes stay empty.
   */
  @Test
  public void explicitNullsOrAnUnrelatedFieldGetNoFallback() throws Exception {
    JSONObject nulls = new JSONObject();
    for (String key : RECTIFICATION_KEYS) {
      nulls.put(key, JSONObject.NULL);
    }
    JSONObject unrelated = new JSONObject().put(Invoice.PROPERTY_ETVFACVERIFACDESC, "Abono");

    for (JSONObject requested : List.of(nulls, unrelated)) {
      Map<String, Object> values = InvoiceSifPreset.merge(salesFacts(true, "F1"), requested);

      for (String key : RECTIFICATION_KEYS) {
        assertTrue(requested + "/" + key, values.containsKey(key));
        assertNull(requested + "/" + key, values.get(key));
      }
    }
  }

  /**
   * End to end with the real guard: a partial sifFields (SII motive sent, the rest omitted) gets no
   * fallback and the completion is refused with 422 naming the systems still missing a code.
   */
  @Test
  public void aPartialSifFieldsIsRefusedByTheGuardForTheOmittedSystems() throws Exception {
    Invoice invoice = recordingInvoice();
    JSONObject body = new JSONObject().put(InvoiceSifPreset.PARAM_SIF_FIELDS,
        new JSONObject().put(Invoice.PROPERTY_AEATSIIMOTIVORECTIF, "R1"));

    try (Env env = new Env(true, true)) {
      InvoiceSifPreset.Refused refused = refusal(null,
          () -> InvoiceSifPreset.applyBeforeCompletion(invoice, body));

      assertRectificationCodeRequired(refused, "TicketBAI", "Verifactu");
      verify(invoice).set(Invoice.PROPERTY_AEATSIIMOTIVORECTIF, "R1");
      verify(invoice, never()).set(anyString(), Mockito.eq("R4"));
    }
  }

  /** Explicit nulls for every code: no fallback, the guard names all three systems. */
  @Test
  public void explicitNullCodesAreRefusedByTheGuardInEverySystem() throws Exception {
    Invoice invoice = recordingInvoice();
    JSONObject nulls = new JSONObject();
    for (String key : RECTIFICATION_KEYS) {
      nulls.put(key, JSONObject.NULL);
    }
    JSONObject body = new JSONObject().put(InvoiceSifPreset.PARAM_SIF_FIELDS, nulls);

    try (Env env = new Env(true, true)) {
      InvoiceSifPreset.Refused refused = refusal(null,
          () -> InvoiceSifPreset.applyBeforeCompletion(invoice, body));

      assertRectificationCodeRequired(refused, "SII", "TicketBAI", "Verifactu");
      verify(invoice, never()).set(anyString(), Mockito.eq("R4"));
    }
  }

  /** A sales rectificative invoice whose getters read back what {@code set} wrote. */
  private static Invoice recordingInvoice() {
    Invoice invoice = invoice(true, "F1");
    Map<String, Object> written = new java.util.HashMap<>();
    Mockito.doAnswer(call -> written.put(call.getArgument(0), call.getArgument(1)))
        .when(invoice).set(anyString(), any());
    when(invoice.getAeatsiiMotivoRectif()).thenAnswer(
        call -> written.get(Invoice.PROPERTY_AEATSIIMOTIVORECTIF));
    when(invoice.getTbaiReverseinvoicecode()).thenAnswer(
        call -> written.get(Invoice.PROPERTY_TBAIREVERSEINVOICECODE));
    when(invoice.getEtvfacInvType()).thenAnswer(
        call -> written.get(Invoice.PROPERTY_ETVFACINVTYPE));
    return invoice;
  }

  private static void assertRectificationCodeRequired(InvoiceSifPreset.Refused refused,
      String... systems) throws JSONException {
    assertRefusedAs(RectificationFiscalCodeGuard.MSG_RECTIFICATION_CODE_REQUIRED, null, refused);
    JSONArray named = body(refused).getJSONObject("messageParams").getJSONArray("systems");
    assertEquals(systems.length, named.length());
    for (int i = 0; i < systems.length; i++) {
      assertEquals(systems[i], named.getString(i));
    }
  }

  /** A field that is not an editable SIF field of this invoice is refused with 422. */
  @Test
  public void anUnknownFieldIsRefused() throws Exception {
    InvoiceSifPreset.Refused refused = refusal(null,
        () -> InvoiceSifPreset.merge(salesFacts(false, "F1"),
            new JSONObject().put("documentNo", "X")));

    assertRefusedAs(InvoiceSifPreset.MSG_FIELD_INVALID, "documentNo", refused);
    assertTrue(body(refused).getString("message").contains("SII, Verifactu"));
  }

  /**
   * A code the field does not accept is refused: an unknown one, and a rectification code on a
   * normal invoice's VERI*FACTU type (which only offers F1/F2).
   */
  @Test
  public void anUnknownCodeIsRefused() throws Exception {
    for (String code : List.of("F9", "R1")) {
      InvoiceSifPreset.Refused refused = refusal(code,
          () -> InvoiceSifPreset.merge(salesFacts(false, "F1"),
              new JSONObject().put(Invoice.PROPERTY_ETVFACINVTYPE, code)));
      assertRefusedAs(InvoiceSifPreset.MSG_FIELD_INVALID, Invoice.PROPERTY_ETVFACINVTYPE,
          refused);
    }
  }

  /** A boolean sent as text and a date that is not yyyy-MM-dd are refused, not coerced. */
  @Test
  public void aMistypedValueIsRefused() throws Exception {
    InvoiceSifPreset.Refused bool = refusal(null,
        () -> InvoiceSifPreset.merge(salesFacts(false, "F1"),
            new JSONObject().put(Invoice.PROPERTY_ETVFACSIMPINVART7273, "true")));
    assertRefusedAs(InvoiceSifPreset.MSG_FIELD_INVALID, Invoice.PROPERTY_ETVFACSIMPINVART7273,
        bool);

    InvoiceSifPreset.Refused date = refusal(null,
        () -> InvoiceSifPreset.merge(salesFacts(false, "F1"),
            new JSONObject().put(Invoice.PROPERTY_ETSGDATEOPERATION, "11/11/2026")));
    assertRefusedAs(InvoiceSifPreset.MSG_FIELD_INVALID, Invoice.PROPERTY_ETSGDATEOPERATION,
        date);
  }

  /** A blank rectification code stays blank, so the guard names that system as missing. */
  @Test
  public void aBlankRectificationCodeIsLeftForTheGuardToName() throws Exception {
    Map<String, Object> values = InvoiceSifPreset.merge(salesFacts(true, "F1"),
        new JSONObject().put(Invoice.PROPERTY_TBAIREVERSEINVOICECODE, ""));

    assertTrue(values.containsKey(Invoice.PROPERTY_TBAIREVERSEINVOICECODE));
    assertNull(values.get(Invoice.PROPERTY_TBAIREVERSEINVOICECODE));
  }

  /**
   * Asking for the SII authorization when the organization's SII configuration has no
   * authorization number is refused with 422, before anything is saved or evaluated.
   */
  @Test
  public void anSiiAuthorizationSentWhileUnavailableIsRefused() throws Exception {
    Invoice invoice = invoice(true, "F1");
    JSONObject body = new JSONObject().put(InvoiceSifPreset.PARAM_SIF_FIELDS,
        new JSONObject().put(Invoice.PROPERTY_AEATSIIISAUTHORIZATION, true));

    try (Env env = new Env(false)) {
      InvoiceSifPreset.Refused refused = refusal(null,
          () -> InvoiceSifPreset.applyBeforeCompletion(invoice, body));

      assertRefusedAs(InvoiceSifPreset.MSG_AUTHORIZATION_UNAVAILABLE, null, refused);
      verify(env.dal, never()).save(any());
      env.guardMock.verify(() -> RectificationFiscalCodeGuard.evaluate(any()), never());
    }
  }

  /** With an authorization number configured, asking for it copies the number to the invoice. */
  @Test
  public void anSiiAuthorizationSentWhileAvailableCopiesTheNumber() throws Exception {
    Invoice invoice = invoice(true, "F1");
    JSONObject body = new JSONObject().put(InvoiceSifPreset.PARAM_SIF_FIELDS,
        new JSONObject().put(Invoice.PROPERTY_AEATSIIISAUTHORIZATION, true));

    try (Env env = new Env(false)) {
      when(env.siiConfig.getAuthorizationno()).thenReturn(" AUT-1 ");

      InvoiceSifPreset.applyBeforeCompletion(invoice, body);

      verify(invoice).setAeatsiiAuthorizationno("AUT-1");
      verify(invoice).set(Invoice.PROPERTY_AEATSIIISAUTHORIZATION, Boolean.TRUE);
    }
  }

  // ---------------------------------------------------------------------------
  // applyBeforeCompletion: a refusal rolls the whole creation back
  // ---------------------------------------------------------------------------

  /**
   * The guard's 422 is thrown as a Refused carrying that same response, and answering it rolls
   * back the transaction the creation wrote in, so nothing is left behind.
   */
  @Test
  public void aGuardRefusalRollsTheWholeCreationBack() throws Exception {
    Invoice invoice = invoice(true, "F1");
    NeoResponse guardRefusal = NeoResponse.error(NeoActionContract.SC_UNPROCESSABLE,
        new JSONObject().put("success", false));

    try (Env env = new Env(true)) {
      env.guardMock.when(() -> RectificationFiscalCodeGuard.evaluate(invoice))
          .thenReturn(guardRefusal);

      InvoiceSifPreset.Refused refused = refusal(null,
          () -> InvoiceSifPreset.applyBeforeCompletion(invoice, null));

      verify(env.dal, never()).rollbackAndClose();
      assertSame(guardRefusal, refused.toResponse());
      verify(env.dal).rollbackAndClose();
    }
  }

  /** Inside a batch the batch owns the transaction: the refusal leaves the rollback to it. */
  @Test
  public void aRefusalInsideABatchLeavesTheRollbackToTheBatch() {
    NeoResponse response = NeoResponse.error(NeoActionContract.SC_UNPROCESSABLE, "refused");
    OBDal dal = mock(OBDal.class);
    try (MockedStatic<OBDal> dalMock = Mockito.mockStatic(OBDal.class)) {
      dalMock.when(OBDal::getInstance).thenReturn(dal);
      BatchService.beginCallerOwnedTransaction();
      try {
        assertSame(response, new InvoiceSifPreset.Refused(response).toResponse());
      } finally {
        BatchService.endCallerOwnedTransaction();
      }
      verify(dal, never()).rollbackAndClose();
    }
  }

  /** A sifFields that is not a JSON object is refused before any fact is read. */
  @Test
  public void sifFieldsThatIsNotAnObjectIsRefused() throws Exception {
    Invoice invoice = invoice(true, "F1");
    JSONObject body = new JSONObject().put(InvoiceSifPreset.PARAM_SIF_FIELDS, "R4");

    try (Env env = new Env(true)) {
      InvoiceSifPreset.Refused refused = refusal(null,
          () -> InvoiceSifPreset.applyBeforeCompletion(invoice, body));

      assertRefusedAs(InvoiceSifPreset.MSG_FIELD_INVALID, InvoiceSifPreset.PARAM_SIF_FIELDS,
          refused);
      env.defaultsMock.verify(() -> SalesRectificativeFiscalDefaults.siiConfigActive(anyString()),
          never());
    }
  }
}
