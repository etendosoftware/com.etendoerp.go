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

import java.math.BigDecimal;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import javax.servlet.http.HttpServletResponse;
import jakarta.xml.soap.MessageFactory;
import jakarta.xml.soap.SOAPElement;
import jakarta.xml.soap.SOAPEnvelope;
import jakarta.xml.soap.SOAPException;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.geography.Location;
import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.model.common.invoice.InvoiceTax;
import org.openbravo.module.sii.data.AEATSIIConfig;
import org.openbravo.module.sii.utils.SIISOAPUtils;
import org.openbravo.module.sii.utils.SIIUtils;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import com.etendoerp.go.schemaforge.util.NeoActionContract;

/**
 * ETP-6284 — the SII records the send of a purchase invoice would produce, without sending it.
 *
 * <pre>GET /sws/neo/purchase-invoice/header/{id}/action/siiRecordsPreview</pre>
 *
 * <p><b>Preview equals send by construction.</b> The records are not recomputed: this class picks
 * the builder exactly as the Classic send does ({@code MultiEnvioFactura#createSOAPMessage} and
 * {@code SendInvoicesGroupsToAEAT}, same branch for both) and calls the same public
 * {@link SIISOAPUtils} builder on a SOAP body of its own — {@code createDeferredVATInvoicesRegistry}
 * for an F5/LC of an IVA organization that {@link SIIUtils#isDeferredVATInvoice} classifies as
 * deferred VAT, {@code createRegistroFacturasImportDUA} for any other F5/LC (forwarder F1 +
 * one F5 per DUA), {@code createRegistroFacturasRecibidas} for the rest. The resulting
 * {@code RegistroLRFacturasRecibidas} elements are then read back. Those builders only read the
 * database and build in-memory {@code GenericTax} objects: no save, no flush, no network, no state
 * change (checked when this was written; the one hook that could, the SII module's
 * {@code PostXMLInvoiceGeneration} extension point, has no class registered).
 *
 * <p><b>What it does not call.</b> {@code SIIUtils.invoicePreviousValidations} is deliberately left
 * out: on failure it WRITES ({@code AeatsiiEstado = 'EE'} and an {@code AEATSII_Facturas} row), and
 * it always fails on a draft ("must be completed"). The one rule of it that concerns the DUA, Fecha
 * DUA later than Fecha registro contable, is mirrored as a warning instead.
 *
 * <p><b>Never a 500.</b> A builder exception becomes a {@code buildError} warning; data that would
 * make a builder throw a bare {@code NullPointerException} is reported by a specific warning and
 * the build is skipped. Warning texts are GO texts ({@link NeoMessageText}); a Classic sentence
 * quoted inside {@code buildError} is reworded on the way out by {@link ClassicMessageRemap}.
 *
 * <p>Called explicitly by {@link PurchaseInvoiceHeaderHandler}, never from shared code.
 */
final class PurchaseInvoiceSiiRecordsPreview {

  static final String ACTION_NAME = "siiRecordsPreview";

  static final String KEY_APPLICABLE = "applicable";
  static final String KEY_DEFERRED_VAT = "deferredVat";
  static final String KEY_RECORDS = "records";
  static final String KEY_WARNINGS = "warnings";

  static final String WARN_DUA_AFTER_ACCOUNTING_DATE = "duaDateAfterAccountingDate";
  static final String WARN_MISSING_DUA = "missingDua";
  static final String WARN_MISSING_TAX_LINE_DUA = "missingTaxLineDua";
  static final String WARN_MISSING_ACCOUNTING_DATE = "missingAccountingRegisterDate";
  static final String WARN_MISSING_OPERATION_DATE = "missingOperationDate";
  static final String WARN_MISSING_PARTNER_ADDRESS = "missingPartnerAddress";
  static final String WARN_BUILD_ERROR = "buildError";
  static final String WARN_NO_RECORDS = "noRecords";

  private static final String KEY_F5 = "F5";
  private static final String KEY_LC = "LC";
  private static final String RECORD_ELEMENT = "RegistroLRFacturasRecibidas";
  private static final String SII_DATE_FORMAT = "dd-MM-yyyy";
  private static final String ISO_DATE_FORMAT = "yyyy-MM-dd";
  private static final String MESSAGE_DATE_FORMAT = "dd/MM/yyyy";

  private static final Logger log = LogManager.getLogger(PurchaseInvoiceSiiRecordsPreview.class);

  /** Published for agents next to the header's other actions; served over GET only. */
  static final NeoActionContract CONTRACT = NeoActionContract.read(ACTION_NAME,
      "Previews the SII records the send of this purchase invoice would produce, without sending "
          + "it: {applicable, deferredVat, records:[{type, number, date, counterpartyName, "
          + "counterpartyTaxId, specialRegime, lines:[{rate, base, taxAmount}], total}], "
          + "warnings:[{code, message}]}. Built by the same SII module builders the send uses, so "
          + "it matches the real send. Read-only; works on a draft. applicable=false when the "
          + "organization is not in SII or the invoice has no SII invoice type.")
      .withIdDescription("the id of the purchase invoice to preview")
      .withHttpMethod("GET");

  private PurchaseInvoiceSiiRecordsPreview() {
  }

  /**
   * Serves the action when the request is it, else {@code null}.
   *
   * @param context the header request
   * @return the preview response, or {@code null} when the request is not this action
   */
  static NeoResponse handleIfPreview(NeoContext context) {
    if (context == null || !NeoEndpointType.ACTION.equals(context.getEndpointType())
        || !ACTION_NAME.equals(context.getFieldName())) {
      return null;
    }
    if (!"GET".equals(context.getHttpMethod())) {
      return NeoResponse.error(HttpServletResponse.SC_METHOD_NOT_ALLOWED,
          ACTION_NAME + " only supports GET");
    }
    String recordId = context.getRecordId();
    // Read with the caller's own access, so an invoice the role cannot see is a 404.
    Invoice invoice = StringUtils.isBlank(recordId) ? null
        : OBDal.getInstance().get(Invoice.class, recordId);
    if (invoice == null) {
      return NeoResponse.error(HttpServletResponse.SC_NOT_FOUND,
          "Purchase invoice not found: " + recordId);
    }
    try {
      return NeoResponse.ok(preview(invoice));
    } catch (JSONException e) {
      log.error("Could not serialize the SII preview of invoice {}", recordId, e);
      return NeoResponse.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
          "Could not build the SII preview");
    }
  }

  /**
   * The preview body of {@code invoice}.
   *
   * @param invoice the invoice, read with the caller's access
   * @return {@code {applicable, deferredVat, records, warnings}}
   * @throws JSONException if the body cannot be written
   */
  static JSONObject preview(Invoice invoice) throws JSONException {
    OBContext.setAdminMode(true);
    try {
      JSONObject body = emptyBody();
      AEATSIIConfig config = applicableConfig(invoice);
      if (config == null) {
        return body;
      }
      body.put(KEY_APPLICABLE, true);
      boolean importInvoice = isImportKey(invoice.getAeatsiiClaveTipoFc());
      boolean deferredVat = importInvoice && isIvaOrg(config) && isDeferredVat(invoice);
      body.put(KEY_DEFERRED_VAT, deferredVat);

      JSONArray warnings = body.getJSONArray(KEY_WARNINGS);
      List<JSONObject> blocking = blockingWarnings(invoice, importInvoice, deferredVat);
      for (JSONObject w : advisoryWarnings(invoice, importInvoice)) {
        warnings.put(w);
      }
      for (JSONObject w : blocking) {
        warnings.put(w);
      }
      if (!blocking.isEmpty()) {
        return body;
      }
      JSONArray records = body.getJSONArray(KEY_RECORDS);
      try {
        SOAPElement supply = buildRegistry(invoice, config, importInvoice, deferredVat);
        for (JSONObject rec : parseRecords(supply)) {
          records.put(rec);
        }
      } catch (Exception e) {
        log.info("SII preview of invoice {}: the builder refused it: {}", invoice.getId(),
            e.getMessage());
        warnings.put(warning(WARN_BUILD_ERROR, NeoMessageText.text("ETGO_SiiPreviewBuildError",
            StringUtils.defaultIfBlank(e.getMessage(), e.getClass().getSimpleName()))));
        return body;
      }
      if (records.length() == 0) {
        warnings.put(warning(WARN_NO_RECORDS, NeoMessageText.text("ETGO_SiiPreviewNoRecords")));
      }
      return body;
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  static JSONObject emptyBody() throws JSONException {
    JSONObject body = new JSONObject();
    body.put(KEY_APPLICABLE, false);
    body.put(KEY_DEFERRED_VAT, false);
    body.put(KEY_RECORDS, new JSONArray());
    body.put(KEY_WARNINGS, new JSONArray());
    return body;
  }

  /**
   * The SII configuration the send would use, or {@code null} when the invoice is not sent to SII:
   * a sales invoice, no SII invoice type, an organization that is not in SII (or has no adoption
   * date), or an invoice older than the adoption year ({@link SIIUtils#notSendingByDateCause}).
   */
  static AEATSIIConfig applicableConfig(Invoice invoice) {
    if (invoice == null || Boolean.TRUE.equals(invoice.isSalesTransaction())
        || StringUtils.isBlank(invoice.getAeatsiiClaveTipoFc())) {
      return null;
    }
    AEATSIIConfig config = SIIUtils.getSiiConfigFromOrg(invoice.getOrganization());
    if (config == null || !Boolean.TRUE.equals(config.isAcogidaAlSII())
        || config.getFechaAcogidaSII() == null) {
      return null;
    }
    try {
      if (invoice.getAccountingDate() != null && SIIUtils.notSendingByDateCause(config, invoice)) {
        return null;
      }
    } catch (RuntimeException e) {
      log.debug("Could not apply the SII adoption-date rule to invoice {}: {}", invoice.getId(),
          e.getMessage());
    }
    return config;
  }

  static boolean isImportKey(String key) {
    return KEY_F5.equals(key) || KEY_LC.equals(key);
  }

  static boolean isIvaOrg(AEATSIIConfig config) {
    return config != null && SIIUtils.getIvaTaxType().equals(config.getTaxtype());
  }

  private static boolean isDeferredVat(Invoice invoice) {
    if (partnerLocation(invoice) == null) {
      return false;
    }
    try {
      return SIIUtils.isDeferredVATInvoice(invoice);
    } catch (RuntimeException e) {
      return false;
    }
  }

  private static Location partnerLocation(Invoice invoice) {
    return invoice.getPartnerAddress() == null ? null
        : invoice.getPartnerAddress().getLocationAddress();
  }

  /**
   * Data the builders cannot do without: each of these would make them throw a bare
   * {@code NullPointerException}, so it is reported in GO words and the build is skipped.
   */
  static List<JSONObject> blockingWarnings(Invoice invoice, boolean importInvoice,
      boolean deferredVat) throws JSONException {
    List<JSONObject> warnings = new ArrayList<>();
    Location location = partnerLocation(invoice);
    if (location == null || location.getCountry() == null) {
      warnings.add(warning(WARN_MISSING_PARTNER_ADDRESS,
          NeoMessageText.text("ETGO_SiiPreviewMissingPartnerAddress")));
      return warnings;
    }
    boolean firstSemester2017 = SIIUtils.isFromFirstSemester(invoice);
    if (invoice.getAeatsiiFechaRegCont() == null && !firstSemester2017) {
      warnings.add(warning(WARN_MISSING_ACCOUNTING_DATE,
          NeoMessageText.text("ETGO_SiiPreviewMissingAccountingRegisterDate")));
    }
    if (!importInvoice) {
      return warnings;
    }
    if (!Boolean.TRUE.equals(invoice.isAeatsiiMultiDua())) {
      if (StringUtils.isBlank(invoice.getAeatsiiDua()) || invoice.getAeatsiiFechaDua() == null) {
        warnings.add(warning(WARN_MISSING_DUA, NeoMessageText.text("ETGO_SiiPreviewMissingDua")));
      }
    }
    // The forwarder F1 (non-deferred path) is built when the supplier is Spanish and the invoice
    // has lines; it reads the operation date.
    boolean forwarderF1 = !deferredVat && "ES".equals(location.getCountry().getISOCountryCode())
        && !invoice.getInvoiceLineList().isEmpty();
    if (forwarderF1 && invoice.getEtsgDateOperation() == null) {
      warnings.add(warning(WARN_MISSING_OPERATION_DATE,
          NeoMessageText.text("ETGO_SiiPreviewMissingOperationDate")));
    }
    return warnings;
  }

  /**
   * Problems that do not stop the preview but would stop (or silently trim) the real send: the
   * {@code SIIUtils.invoicePreviousValidations} DUA-date rule, and multi-DUA tax lines without a
   * DUA (skipped by the builder) or without a Fecha DUA (refused by it).
   */
  static List<JSONObject> advisoryWarnings(Invoice invoice, boolean importInvoice)
      throws JSONException {
    List<JSONObject> warnings = new ArrayList<>();
    if (!importInvoice) {
      return warnings;
    }
    Date regDate = SIIUtils.isFromFirstSemester(invoice) ? new Date()
        : invoice.getAeatsiiFechaRegCont();
    boolean multiDua = Boolean.TRUE.equals(invoice.isAeatsiiMultiDua());
    Date lateDuaDate = null;
    boolean taxLineWithoutDua = false;
    if (multiDua) {
      for (InvoiceTax tax : invoice.getInvoiceTaxList()) {
        Date duaDate = tax.getAeatsiiFechaDua();
        if (StringUtils.isBlank(tax.getAeatsiiDua()) || duaDate == null) {
          taxLineWithoutDua = true;
        }
        if (regDate != null && duaDate != null && regDate.before(duaDate)
            && (lateDuaDate == null || duaDate.after(lateDuaDate))) {
          lateDuaDate = duaDate;
        }
      }
    } else {
      Date duaDate = invoice.getAeatsiiFechaDua();
      if (regDate != null && duaDate != null && regDate.before(duaDate)) {
        lateDuaDate = duaDate;
      }
    }
    if (lateDuaDate != null) {
      SimpleDateFormat fmt = new SimpleDateFormat(MESSAGE_DATE_FORMAT);
      warnings.add(warning(WARN_DUA_AFTER_ACCOUNTING_DATE,
          NeoMessageText.text("ETGO_SiiPreviewDuaAfterAccountingDate", fmt.format(lateDuaDate),
              fmt.format(regDate))));
    }
    if (taxLineWithoutDua) {
      warnings.add(warning(WARN_MISSING_TAX_LINE_DUA,
          NeoMessageText.text("ETGO_SiiPreviewMissingTaxLineDua")));
    }
    return warnings;
  }

  /**
   * Builds the {@code SuministroLRFacturasRecibidas} element through the builder the send would
   * pick (the branch of {@code MultiEnvioFactura#createSOAPMessage}, purchase side).
   */
  static SOAPElement buildRegistry(Invoice invoice, AEATSIIConfig config, boolean importInvoice,
      boolean deferredVat) throws SOAPException {
    SOAPEnvelope envelope = MessageFactory.newInstance().createMessage().getSOAPPart().getEnvelope();
    envelope.addNamespaceDeclaration("siiLR",
        SIISOAPUtils.getUriSuministroLR(config.getTaxtype(), Boolean.TRUE.equals(config.isGuipuzcoa())));
    envelope.addNamespaceDeclaration("sii", SIISOAPUtils
        .getUriSuministroInformacion(config.getTaxtype(), Boolean.TRUE.equals(config.isGuipuzcoa())));
    SOAPElement supply = envelope.getBody().addChildElement("SuministroLRFacturasRecibidas", "siiLR");
    if (importInvoice && deferredVat) {
      return SIISOAPUtils.createDeferredVATInvoicesRegistry(supply, invoice);
    }
    if (importInvoice) {
      return SIISOAPUtils.createRegistroFacturasImportDUA(supply, invoice);
    }
    return SIISOAPUtils.createRegistroFacturasRecibidas(supply, invoice);
  }

  /**
   * Reads every {@code RegistroLRFacturasRecibidas} under {@code supply} into the contract shape.
   * Pure DOM reading, no database: unit-testable on a hand-built element.
   *
   * @param supply the {@code SuministroLRFacturasRecibidas} element (or any ancestor of records)
   * @return one JSON record per registry element, in document order
   * @throws JSONException if a record cannot be written
   */
  static List<JSONObject> parseRecords(Element supply) throws JSONException {
    List<JSONObject> records = new ArrayList<>();
    NodeList nodes = supply.getElementsByTagNameNS("*", RECORD_ELEMENT);
    for (int i = 0; i < nodes.getLength(); i++) {
      records.add(parseRecord((Element) nodes.item(i)));
    }
    return records;
  }

  private static JSONObject parseRecord(Element registry) throws JSONException {
    Element idFactura = child(registry, "IDFactura");
    Element factura = child(registry, "FacturaRecibida");
    Element contraparte = child(factura, "Contraparte");
    JSONObject rec = new JSONObject();
    rec.put("type", text(child(factura, "TipoFactura")));
    rec.put("number", text(child(idFactura, "NumSerieFacturaEmisor")));
    rec.put("date", isoDate(text(child(idFactura, "FechaExpedicionFacturaEmisor"))));
    rec.put("counterpartyName", text(child(contraparte, "NombreRazon")));
    String taxId = text(child(contraparte, "NIF"));
    if (taxId == null) {
      taxId = text(child(child(contraparte, "IDOtro"), "ID"));
    }
    rec.put("counterpartyTaxId", taxId);
    rec.put("specialRegime", text(child(factura, "ClaveRegimenEspecialOTrascendencia")));
    JSONArray lines = new JSONArray();
    Element breakdown = child(factura, "DesgloseFactura");
    if (breakdown != null) {
      addDetailLines(breakdown, "DetalleIVA", lines);
      addDetailLines(breakdown, "DetalleIGIC", lines);
    }
    rec.put("lines", lines);
    rec.put("total", number(text(child(factura, "ImporteTotal"))));
    return rec;
  }

  private static void addDetailLines(Element breakdown, String detailName, JSONArray lines)
      throws JSONException {
    NodeList details = breakdown.getElementsByTagNameNS("*", detailName);
    for (int i = 0; i < details.getLength(); i++) {
      Element detail = (Element) details.item(i);
      JSONObject line = new JSONObject();
      line.put("rate", number(text(child(detail, "TipoImpositivo"))));
      line.put("base", number(text(child(detail, "BaseImponible"))));
      line.put("taxAmount", number(text(child(detail, "CuotaSoportada"))));
      lines.put(line);
    }
  }

  /** The first direct child of {@code parent} with that local name, or {@code null}. */
  static Element child(Element parent, String localName) {
    if (parent == null) {
      return null;
    }
    for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
      if (n instanceof Element && localName.equals(localNameOf(n))) {
        return (Element) n;
      }
    }
    return null;
  }

  private static String localNameOf(Node n) {
    String local = n.getLocalName();
    if (local != null) {
      return local;
    }
    String name = n.getNodeName();
    int colon = name.indexOf(':');
    return colon >= 0 ? name.substring(colon + 1) : name;
  }

  private static String text(Element e) {
    return e == null ? null : StringUtils.trimToNull(e.getTextContent());
  }

  /** {@code dd-MM-yyyy} (the SII format) to {@code yyyy-MM-dd}; anything else as it came. */
  static String isoDate(String siiDate) {
    if (siiDate == null) {
      return null;
    }
    try {
      SimpleDateFormat in = new SimpleDateFormat(SII_DATE_FORMAT);
      in.setLenient(false);
      return new SimpleDateFormat(ISO_DATE_FORMAT).format(in.parse(siiDate));
    } catch (ParseException e) {
      return siiDate;
    }
  }

  /** The SII decimal text as a number, or JSON null when empty or unreadable. */
  static Object number(String value) {
    if (value == null) {
      return JSONObject.NULL;
    }
    try {
      return new BigDecimal(value);
    } catch (NumberFormatException e) {
      return JSONObject.NULL;
    }
  }

  static JSONObject warning(String code, String message) throws JSONException {
    return new JSONObject().put("code", code).put("message", message);
  }
}
