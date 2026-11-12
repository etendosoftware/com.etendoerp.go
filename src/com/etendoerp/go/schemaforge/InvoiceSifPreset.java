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

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.businesspartner.BusinessPartner;
import org.openbravo.model.common.enterprise.DocumentType;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.model.common.order.Order;
import org.openbravo.module.sii.data.AEATSIIConfig;
import org.openbravo.module.sii.utils.SIIUtils;

import com.etendoerp.go.schemaforge.util.NeoActionContract;

/**
 * The SIF ("Datos fiscales") fields of an invoice that is created AND completed in one call
 * (ETP-6179): the actions {@code createDraftInvoice} (sales order, quotation, goods shipment),
 * {@code createPurchaseInvoice} (purchase order, goods receipt) and {@code createReturnInvoice}
 * (both return windows).
 *
 * <h2>Why</h2>
 *
 * <p>Those actions build the invoice header with {@code OBProvider} and complete it directly, so
 * the user never saw the SIF tab and the completion never went through
 * {@link RectificationFiscalCodeGuard}: a rectificative invoice generated from a return was
 * completed as an ordinary {@code F1} (the entity default of {@code etvfacInvType}) with no SII
 * motive and no TicketBAI code, and a normal invoice whose document type configures {@code F2}
 * still left as {@code F1}, because the entity default made the completion's
 * {@code COALESCE(invoice, docType)} copy a no-op.
 *
 * <h2>What it does</h2>
 *
 * <ul>
 *   <li>{@link #preview} answers, for the invoice an action WOULD create, the fiscal systems
 *   active for its organization that have an editable field, and the value each field would get.
 *   It is the {@code sifDefaults} action every invoice-creating source exposes, so the screen
 *   prefills its "Datos fiscales" step from the backend and both sides never disagree.</li>
 *   <li>{@link #applyBeforeCompletion} runs right before the completion: it writes what the caller
 *   sent under {@code sifFields} (validated: only the editable fields of the active systems, only
 *   their allowed codes) and the previewed value of every field it did not send — so a caller
 *   that sends nothing gets exactly the preview (the document type's VERI*FACTU type and
 *   description; {@code R4} on a rectificative; …) — sets the structural
 *   fields (SII {@code R}/{@code I}, the TicketBAI and VERI*FACTU reverse types) and then asks
 *   {@link RectificationFiscalCodeGuard#evaluate} — the same per-system rule and 422 bodies the
 *   header's own completion uses.</li>
 * </ul>
 *
 * <h2>Defaults (decided by the user, ETP-6179)</h2>
 *
 * <ul>
 *   <li>Normal invoice: VERI*FACTU type from the document type that will be used, {@code F1} when
 *   it configures none (or configures a rectificative code). SII key: the customer's own default
 *   key when it declares one ({@code AEATSII_INVOICE_TRG}'s rule), else {@code F1}.</li>
 *   <li>Rectificative invoice: {@code R4} preselected for SII's motive, TicketBAI's code and
 *   VERI*FACTU's type, all editable. This supersedes, for these creation flows only, the ETP-5745
 *   CP-14 stance that no motive is ever preselected: the user still sees and confirms it in the
 *   step, and a caller that sends nothing gets the same value the screen would have offered.</li>
 * </ul>
 *
 * <p>A key the caller did not send takes its previewed value, so the invoice ends up with
 * exactly what {@code sifDefaults} shows — every editable field of the active systems, not only
 * the corrective ones (QA BUG-1: the VERI*FACTU description stayed the creation path's "Ventas"
 * while the preview said the document type's "Rectificaciones de Ventas"). The SPA always sends
 * every field of the step.
 *
 * <p>Structure only: nothing here names a spec or a table. The direction comes from the
 * invoice's own {@code IsSOTrx}, the rectificative flag from its document type, and each system
 * from its own active configuration — the same gates {@link RectificationFiscalCodeGuard} uses.
 * Purchase invoices only carry SII fields: VERI*FACTU never sends a purchase, and TicketBAI's
 * purchase side (Batuz) has no editable field on the SIF tab.
 */
final class InvoiceSifPreset {

  private static final Logger log = LogManager.getLogger(InvoiceSifPreset.class);

  /** Body key of the invoice-creating actions carrying the SIF values. */
  static final String PARAM_SIF_FIELDS = "sifFields";

  /** The read action of every invoice-creating source answering {@link #preview}. */
  static final String ACTION_SIF_DEFAULTS = "sifDefaults";

  /** Stable identity of a refused SIF value — a plain string, not an AD_MESSAGE. */
  static final String MSG_FIELD_INVALID = "ETGO_InvoiceSifFieldInvalid";

  /** Stable identity of an authorization requested without an SII authorization number. */
  static final String MSG_AUTHORIZATION_UNAVAILABLE = "ETGO_InvoiceSiiAuthorizationUnavailable";

  static final String SYSTEM_SII = "SII";
  static final String SYSTEM_TBAI = "TicketBAI";
  static final String SYSTEM_VERIFACTU = "Verifactu";

  static final String F_OPERATION_DATE = Invoice.PROPERTY_ETSGDATEOPERATION;
  static final String F_SII_KEY = Invoice.PROPERTY_AEATSIICLAVETIPO;
  static final String F_SII_KEY_PURCHASE = Invoice.PROPERTY_AEATSIICLAVETIPOFC;
  static final String F_SII_MOTIVE = Invoice.PROPERTY_AEATSIIMOTIVORECTIF;
  static final String F_SII_DESCRIPTION = Invoice.PROPERTY_AEATSIIDESCRIPCIONSII;
  static final String F_SII_AUTHORIZATION = Invoice.PROPERTY_AEATSIIISAUTHORIZATION;
  static final String F_SII_ACCOUNTING_REG_DATE = Invoice.PROPERTY_AEATSIIFECHAREGCONT;
  static final String F_VF_INV_TYPE = Invoice.PROPERTY_ETVFACINVTYPE;
  static final String F_VF_DESCRIPTION = Invoice.PROPERTY_ETVFACVERIFACDESC;
  static final String F_VF_SIMPLIFIED_7273 = Invoice.PROPERTY_ETVFACSIMPINVART7273;
  static final String F_VF_NO_RECIPIENT_61D = Invoice.PROPERTY_ETVFACINVNOIDART61D;
  static final String F_TBAI_CODE = Invoice.PROPERTY_TBAIREVERSEINVOICECODE;

  static final String RECTIFICATION_DEFAULT = "R4";
  static final String DEFAULT_INV_TYPE = "F1";
  private static final String CLAVE_TIPO_RECTIFICATIVA = "R";
  private static final String TIPO_RECTIF_DEFAULT = "I";

  private static final List<String> RECTIFICATION_CODES = List.of("R1", "R2", "R3", "R4", "R5");
  private static final List<String> SALES_SII_KEYS = List.of("F1", "F2", "F4");
  private static final List<String> PURCHASE_SII_KEYS = List.of("F6", "LC", "F5", "F1");
  private static final List<String> VF_NORMAL_TYPES = List.of("F1", "F2");

  private static final String TYPE_DATE = "date";
  private static final String TYPE_TEXT = "text";
  private static final String TYPE_BOOLEAN = "boolean";
  private static final String TYPE_OPTION = "option";
  private static final String DATE_PATTERN = "yyyy-MM-dd";

  private static final String SII_DESCRIPTION_SQL =
      "SELECT d.description FROM aeatsii_description d"
          + " WHERE d.isactive = 'Y' AND d.isdefault = 'Y' AND d.ad_client_id = ?"
          + " AND (CASE WHEN ? = 'Y' THEN d.issales ELSE d.ispurchase END) = 'Y'"
          + " AND ad_isorgincluded(?, d.ad_org_id, d.ad_client_id) <> -1"
          + " ORDER BY ad_isorgincluded(?, d.ad_org_id, d.ad_client_id) LIMIT 1";

  private InvoiceSifPreset() {
  }

  // ---------------------------------------------------------------------------
  // What the invoice will be
  // ---------------------------------------------------------------------------

  /**
   * The invoice an action is about to create, as far as its SIF fields depend on it.
   *
   * @param org the invoice's organization
   * @param partner its business partner, may be {@code null}
   * @param docType the document type the action will use, may be {@code null}
   * @param sales its direction ({@code IsSOTrx})
   * @param order the order it comes from, may be {@code null}
   */
  record Target(Organization org, BusinessPartner partner, DocumentType docType, boolean sales,
      Order order) {

    static Target of(Invoice invoice) {
      return new Target(invoice.getOrganization(), invoice.getBusinessPartner(),
          invoice.getTransactionDocument(), Boolean.TRUE.equals(invoice.isSalesTransaction()),
          invoice.getSalesOrder());
    }
  }

  /**
   * Everything the defaults are computed from, resolved once from the database so the rules
   * themselves ({@link #fields}, {@link #defaults}, {@link #merge}) are pure.
   */
  record Facts(boolean sales, boolean rectificative, boolean siiActive, boolean tbaiActive,
      boolean verifactuActive, String docInvType, String docVerifacDesc, boolean docSimplified7273,
      boolean docNoRecipient61d, String partnerSiiKey, String siiDescription, String operationDate,
      String accountingRegDate, String authorizationNo) {
  }

  /** One editable field of the step: its type and, for a code, the codes it accepts. */
  record FieldSpec(String name, String system, String type, List<String> options) {
  }

  /** Resolves the facts of a target. Never throws: an unresolvable fact reads as absent. */
  static Facts resolveFacts(Target target) {
    String orgId = target.org() != null ? target.org().getId() : null;
    boolean sales = target.sales();
    boolean siiActive = SalesRectificativeFiscalDefaults.siiConfigActive(orgId);
    boolean tbaiActive = sales && StringUtils.isNotBlank(orgId)
        && SalesRectificativeFiscalDefaults.tbaiConfigActive(orgId);
    boolean verifactuActive = sales
        && SalesRectificativeFiscalDefaults.verifactuConfigActive(orgId);
    DocumentType docType = target.docType();
    String today = formatDate(new Date());
    AEATSIIConfig siiConfig = siiActive ? siiConfigOf(target.org()) : null;
    return new Facts(sales, RectificativeSupport.isRectificative(docType), siiActive, tbaiActive,
        verifactuActive,
        docType != null ? docType.getEtvfacInvType() : null,
        docType != null ? docType.getEtvfacVerifacDesc() : null,
        docType != null && Boolean.TRUE.equals(docType.isEtvfacSimpInvArt7273()),
        docType != null && Boolean.TRUE.equals(docType.isEtvfacInvNoIdArt61d()),
        sales ? partnerSiiKey(target.partner()) : null,
        siiActive ? siiDescription(target) : null,
        operationDate(target.order(), today),
        siiConfig != null && Boolean.TRUE.equals(siiConfig.isPostedInvoices()) ? null : today,
        siiConfig != null ? StringUtils.trimToNull(siiConfig.getAuthorizationno()) : null);
  }

  // ---------------------------------------------------------------------------
  // The rules (pure)
  // ---------------------------------------------------------------------------

  /** The editable fields of the step, in display order, for the active systems. */
  static List<FieldSpec> fields(Facts f) {
    List<FieldSpec> specs = new ArrayList<>();
    if (f.siiActive() || f.verifactuActive()) {
      specs.add(new FieldSpec(F_OPERATION_DATE, f.siiActive() ? SYSTEM_SII : SYSTEM_VERIFACTU,
          TYPE_DATE, null));
    }
    if (f.siiActive()) {
      if (!f.sales()) {
        specs.add(new FieldSpec(F_SII_KEY_PURCHASE, SYSTEM_SII, TYPE_OPTION, PURCHASE_SII_KEYS));
      } else if (f.rectificative()) {
        specs.add(new FieldSpec(F_SII_MOTIVE, SYSTEM_SII, TYPE_OPTION, RECTIFICATION_CODES));
      } else {
        specs.add(new FieldSpec(F_SII_KEY, SYSTEM_SII, TYPE_OPTION, SALES_SII_KEYS));
      }
      specs.add(new FieldSpec(F_SII_DESCRIPTION, SYSTEM_SII, TYPE_TEXT, null));
      specs.add(new FieldSpec(F_SII_AUTHORIZATION, SYSTEM_SII, TYPE_BOOLEAN, null));
      if (!f.sales()) {
        specs.add(new FieldSpec(F_SII_ACCOUNTING_REG_DATE, SYSTEM_SII, TYPE_DATE, null));
      }
    }
    if (f.verifactuActive()) {
      specs.add(new FieldSpec(F_VF_INV_TYPE, SYSTEM_VERIFACTU, TYPE_OPTION,
          f.rectificative() ? RECTIFICATION_CODES : VF_NORMAL_TYPES));
      specs.add(new FieldSpec(F_VF_DESCRIPTION, SYSTEM_VERIFACTU, TYPE_TEXT, null));
      specs.add(new FieldSpec(F_VF_SIMPLIFIED_7273, SYSTEM_VERIFACTU, TYPE_BOOLEAN, null));
      specs.add(new FieldSpec(F_VF_NO_RECIPIENT_61D, SYSTEM_VERIFACTU, TYPE_BOOLEAN, null));
    }
    if (f.tbaiActive() && f.rectificative()) {
      specs.add(new FieldSpec(F_TBAI_CODE, SYSTEM_TBAI, TYPE_OPTION, RECTIFICATION_CODES));
    }
    return specs;
  }

  /** The active systems that have at least one editable field, in SII/TicketBAI/VERI*FACTU order. */
  static List<String> systems(Facts f) {
    List<String> systems = new ArrayList<>();
    if (f.siiActive()) {
      systems.add(SYSTEM_SII);
    }
    if (f.tbaiActive() && f.rectificative()) {
      systems.add(SYSTEM_TBAI);
    }
    if (f.verifactuActive()) {
      systems.add(SYSTEM_VERIFACTU);
    }
    return systems;
  }

  /** The value each editable field gets when the caller does not change it. */
  static Map<String, Object> defaults(Facts f) {
    Map<String, Object> values = new LinkedHashMap<>();
    for (FieldSpec spec : fields(f)) {
      values.put(spec.name(), defaultOf(spec.name(), f));
    }
    return values;
  }

  private static Object defaultOf(String field, Facts f) {
    switch (field) {
      case F_OPERATION_DATE:
        return f.operationDate();
      case F_SII_KEY:
        return StringUtils.defaultIfBlank(f.partnerSiiKey(), DEFAULT_INV_TYPE);
      case F_SII_KEY_PURCHASE:
        return DEFAULT_INV_TYPE;
      case F_SII_MOTIVE:
      case F_TBAI_CODE:
        return RECTIFICATION_DEFAULT;
      case F_SII_DESCRIPTION:
        return f.siiDescription();
      case F_SII_AUTHORIZATION:
        return false;
      case F_SII_ACCOUNTING_REG_DATE:
        return f.accountingRegDate();
      case F_VF_INV_TYPE:
        return defaultVerifactuInvType(f);
      case F_VF_DESCRIPTION:
        return f.docVerifacDesc();
      case F_VF_SIMPLIFIED_7273:
        return f.docSimplified7273();
      case F_VF_NO_RECIPIENT_61D:
        return f.docNoRecipient61d();
      default:
        return null;
    }
  }

  /**
   * VERI*FACTU type: {@code R4} on a rectificative; otherwise the document type's own type when
   * it is a normal ({@code F*}) one the step offers, else {@code F1}.
   */
  static String defaultVerifactuInvType(Facts f) {
    if (f.rectificative()) {
      return RECTIFICATION_DEFAULT;
    }
    String docInvType = StringUtils.trimToNull(f.docInvType());
    return docInvType != null && VF_NORMAL_TYPES.contains(docInvType) ? docInvType
        : DEFAULT_INV_TYPE;
  }

  /**
   * The values to write: every editable field of the active systems with its previewed value
   * ({@link #defaults}, exactly what {@code sifDefaults} answers), overridden by the caller's
   * {@code sifFields}, validated against those fields. A caller that sends nothing gets the
   * preview as it is.
   *
   * @param f the facts of the invoice
   * @param requested the caller's {@code sifFields}; {@code null} reads as empty
   * @return the field values to write, by DAL property name
   * @throws Refused 422 {@link #MSG_FIELD_INVALID} for a field that is not editable on this
   *     invoice or a value it does not accept
   */
  static Map<String, Object> merge(Facts f, JSONObject requested) {
    Map<String, FieldSpec> specs = new LinkedHashMap<>();
    for (FieldSpec spec : fields(f)) {
      specs.put(spec.name(), spec);
    }
    // QA BUG-1: start from EVERY previewed value, so a caller that sends nothing gets exactly
    // what sifDefaults showed; what the caller sends then overrides field by field.
    Map<String, Object> values = new LinkedHashMap<>(defaults(f));
    if (requested != null) {
      Iterator<?> keys = requested.keys();
      while (keys.hasNext()) {
        String key = String.valueOf(keys.next());
        FieldSpec spec = specs.get(key);
        if (spec == null) {
          throw invalid(key, "is not an editable SIF field of this invoice (active systems: "
              + String.join(", ", systems(f)) + ")");
        }
        values.put(key, coerce(spec, requested.opt(key)));
      }
    }
    return values;
  }

  private static Object coerce(FieldSpec spec, Object raw) {
    boolean blank = raw == null || JSONObject.NULL.equals(raw)
        || StringUtils.isBlank(String.valueOf(raw));
    switch (spec.type()) {
      case TYPE_BOOLEAN:
        if (raw instanceof Boolean) {
          return raw;
        }
        throw invalid(spec.name(), "must be a JSON boolean");
      case TYPE_DATE:
        if (blank) {
          return null;
        }
        parseDate(spec.name(), String.valueOf(raw));
        return String.valueOf(raw).substring(0, DATE_PATTERN.length());
      case TYPE_OPTION:
        if (blank) {
          // Left blank on purpose: a rectification code the guard then names as missing.
          return null;
        }
        String code = String.valueOf(raw);
        if (!spec.options().contains(code)) {
          throw invalid(spec.name(), "must be one of " + spec.options());
        }
        return code;
      default:
        return blank ? null : String.valueOf(raw);
    }
  }

  // ---------------------------------------------------------------------------
  // The preview (sifDefaults)
  // ---------------------------------------------------------------------------

  /**
   * The {@code sifDefaults} answer for the invoice an action would create:
   * {@code {systems, sales, rectificative, fields:{name:value}, fieldSpecs:[{name, system, type,
   * options}], siiAuthorizationAvailable}}. {@code systems} empty means the step does not apply.
   */
  static JSONObject preview(Target target) throws JSONException {
    Facts f = resolveFacts(target);
    JSONObject data = new JSONObject();
    data.put("systems", new JSONArray(systems(f)));
    data.put("sales", f.sales());
    data.put("rectificative", f.rectificative());
    JSONObject values = new JSONObject();
    for (Map.Entry<String, Object> e : defaults(f).entrySet()) {
      values.put(e.getKey(), e.getValue() != null ? e.getValue() : JSONObject.NULL);
    }
    data.put("fields", values);
    JSONArray specs = new JSONArray();
    for (FieldSpec spec : fields(f)) {
      JSONObject row = new JSONObject();
      row.put("name", spec.name());
      row.put("system", spec.system());
      row.put("type", spec.type());
      if (spec.options() != null) {
        row.put("options", new JSONArray(spec.options()));
      }
      specs.put(row);
    }
    data.put("fieldSpecs", specs);
    data.put("siiAuthorizationAvailable", f.authorizationNo() != null);
    return data;
  }

  /** {@link #preview} wrapped as the 200 every action of these handlers answers with. */
  static NeoResponse previewResponse(Target target) throws JSONException {
    JSONObject responseData = new JSONObject().put("data", preview(target));
    return NeoResponse.ok(new JSONObject().put("response", responseData));
  }

  // ---------------------------------------------------------------------------
  // Applying it before the completion
  // ---------------------------------------------------------------------------

  /**
   * Writes the SIF fields on an invoice about to be completed, then evaluates the rectification
   * guard. Must run after the header and its lines are persisted and before
   * {@code InvoiceCompletionService#completeInvoiceOrThrow}.
   *
   * @param invoice the draft invoice
   * @param body the action's request body; its {@code sifFields} object is read
   * @throws Refused a 422 for an invalid value, an unavailable authorization or a missing
   *     rectification code; the caller answers {@link Refused#toResponse()}, which rolls back
   */
  static void applyBeforeCompletion(Invoice invoice, JSONObject body) throws JSONException {
    if (invoice == null) {
      return;
    }
    JSONObject requested = body != null ? body.optJSONObject(PARAM_SIF_FIELDS) : null;
    if (body != null && body.has(PARAM_SIF_FIELDS) && requested == null
        && !JSONObject.NULL.equals(body.opt(PARAM_SIF_FIELDS))) {
      throw invalid(PARAM_SIF_FIELDS, "must be a JSON object");
    }
    Facts f = resolveFacts(Target.of(invoice));
    Map<String, Object> values = merge(f, requested);
    for (Map.Entry<String, Object> e : values.entrySet()) {
      write(invoice, e.getKey(), e.getValue(), f);
    }
    boolean structural = applyStructural(invoice, f);
    if (!values.isEmpty() || structural) {
      OBDal.getInstance().save(invoice);
      OBDal.getInstance().flush();
    }
    NeoResponse refusal = RectificationFiscalCodeGuard.evaluate(invoice);
    if (refusal != null) {
      throw new Refused(refusal);
    }
  }

  private static void write(Invoice invoice, String field, Object value, Facts f) {
    if (F_OPERATION_DATE.equals(field) || F_SII_ACCOUNTING_REG_DATE.equals(field)) {
      invoice.set(field, value != null ? parseDate(field, (String) value) : null);
      return;
    }
    if (F_SII_AUTHORIZATION.equals(field) && Boolean.TRUE.equals(value)) {
      if (f.authorizationNo() == null) {
        throw refused(MSG_AUTHORIZATION_UNAVAILABLE, "The organization has no SII configuration "
            + "with an authorization number, so aeatsiiIsauthorization cannot be true.", null);
      }
      invoice.setAeatsiiAuthorizationno(f.authorizationNo());
    }
    invoice.set(field, value);
  }

  /**
   * The fields that say "this is a correction", never "why": SII's key {@code R} and type
   * {@code I}, and the TicketBAI / VERI*FACTU reverse types
   * ({@link SalesRectificativeFiscalDefaults#computeAndSet}).
   *
   * @return whether the invoice is a sales rectificative (its structural fields were set)
   */
  private static boolean applyStructural(Invoice invoice, Facts f) {
    if (!f.sales() || !f.rectificative()) {
      return false;
    }
    if (f.siiActive()) {
      invoice.setAeatsiiClaveTipo(CLAVE_TIPO_RECTIFICATIVA);
      if (StringUtils.isBlank(invoice.getAeatsiiTipoRectif())) {
        invoice.setAeatsiiTipoRectif(TIPO_RECTIF_DEFAULT);
      }
    }
    SalesRectificativeFiscalDefaults.computeAndSet(invoice);
    return true;
  }

  // ---------------------------------------------------------------------------
  // Contracts
  // ---------------------------------------------------------------------------

  /** The {@code sifFields} parameter every invoice-creating action declares. */
  static NeoActionContract.Param sifFieldsParam() {
    return NeoActionContract.Param.optional(PARAM_SIF_FIELDS, NeoActionContract.TYPE_OBJECT,
        "The invoice's SIF fields (\"Datos fiscales\"), {field: value}, written before it is "
            + "completed. Only the editable fields of the fiscal systems active for its "
            + "organization are accepted, with their codes; read them and their defaults with "
            + ACTION_SIF_DEFAULTS + " first. A field you omit takes exactly the value "
            + ACTION_SIF_DEFAULTS + " shows for it (send nothing to get the preview as it is): "
            + "a rectificative invoice gets R4 as the SII motive, TicketBAI code and VERI*FACTU "
            + "type, a normal one the VERI*FACTU type and description of its document type (F1 "
            + "when it has none). Refused with 422 " + MSG_FIELD_INVALID + " for an unknown field or code, "
            + "and with 422 ETGO_InvoiceRectificationCodeRequired when a rectificative invoice "
            + "is left without a code for an active system.");
  }

  /**
   * The {@code sifDefaults} read action of an invoice-creating source.
   *
   * @param actionName the invoice-creating action it previews
   * @param idDescription what the record id is
   * @param params the body keys that select what is invoiced, the same the action reads
   */
  static NeoActionContract defaultsContract(String actionName, String idDescription,
      NeoActionContract.Param... params) {
    return NeoActionContract.read(ACTION_SIF_DEFAULTS,
        "Previews the SIF fields (\"Datos fiscales\") of the invoice " + actionName
            + " would create from this record, without creating anything: {data:{systems, "
            + "sales, rectificative, fields:{name: default value}, fieldSpecs:[{name, system, "
            + "type, options}], siiAuthorizationAvailable}}. systems lists the fiscal systems "
            + "active for the organization (SII, TicketBAI, Verifactu) that have an editable "
            + "field; empty means there is nothing to choose. Send the values you want in "
            + PARAM_SIF_FIELDS + " of " + actionName + ".", params)
        .withIdDescription(idDescription);
  }

  // ---------------------------------------------------------------------------
  // Refusal
  // ---------------------------------------------------------------------------

  /** A 422 refusal raised while the invoice is already persisted: answering it rolls back. */
  static final class Refused extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient NeoResponse response;

    Refused(NeoResponse response) {
      super("SIF fields refused");
      this.response = response;
    }

    /**
     * The 422 to answer, after rolling back what the action wrote (unless a batch owns the
     * transaction: the batch then fails the operation and rolls everything back).
     */
    NeoResponse toResponse() {
      if (!BatchService.callerOwnsTransaction()) {
        try {
          OBDal.getInstance().rollbackAndClose();
        } catch (RuntimeException e) {
          log.warn("Rollback after a refused SIF field did not complete: {}", e.getMessage());
        }
      }
      return response;
    }
  }

  private static Refused invalid(String field, String reason) {
    return refused(MSG_FIELD_INVALID, "The SIF field " + field + " " + reason + ".", field);
  }

  private static Refused refused(String key, String message, String field) {
    try {
      JSONObject body = new JSONObject();
      body.put("success", false);
      body.put("message", message);
      body.put("messageKeys", new JSONArray(List.of(key)));
      if (field != null) {
        body.put("messageParams", new JSONObject().put("field", field));
      }
      return new Refused(NeoResponse.error(NeoActionContract.SC_UNPROCESSABLE, body));
    } catch (JSONException e) {
      return new Refused(NeoResponse.error(NeoActionContract.SC_UNPROCESSABLE, message));
    }
  }

  // ---------------------------------------------------------------------------
  // Database facts
  // ---------------------------------------------------------------------------

  private static AEATSIIConfig siiConfigOf(Organization org) {
    try {
      return org != null ? SIIUtils.getSiiConfigFromOrg(org) : null;
    } catch (Exception e) {
      log.debug("SII config lookup failed: {}", e.getMessage());
      return null;
    }
  }

  /** {@code AEATSII_INVOICE_TRG}'s rule: a customer with its own default SII key. */
  private static String partnerSiiKey(BusinessPartner partner) {
    if (partner == null || !Boolean.TRUE.equals(partner.isCustomer())
        || !Boolean.TRUE.equals(partner.isAeatsiiDefaultsiikey())) {
      return null;
    }
    String key = StringUtils.trimToNull(partner.getAeatsiiSiikeylist());
    return key != null && SALES_SII_KEYS.contains(key) ? key : null;
  }

  /** The order's own SII description, else the organization's default one for the direction. */
  private static String siiDescription(Target target) {
    Order order = target.order();
    if (order != null && StringUtils.isNotBlank(order.getAeatsiiDescripcionSii())) {
      return order.getAeatsiiDescripcionSii();
    }
    Organization org = target.org();
    if (org == null || org.getClient() == null) {
      return null;
    }
    try {
      Connection conn = OBDal.getInstance().getConnection();
      try (PreparedStatement ps = conn.prepareStatement(SII_DESCRIPTION_SQL)) {
        ps.setString(1, org.getClient().getId());
        ps.setString(2, target.sales() ? "Y" : "N");
        ps.setString(3, org.getId());
        ps.setString(4, org.getId());
        try (ResultSet rs = ps.executeQuery()) {
          return rs.next() ? rs.getString(1) : null;
        }
      }
    } catch (Exception e) {
      log.debug("SII description lookup failed for org {}: {}", org.getId(), e.getMessage());
      return null;
    }
  }

  private static String operationDate(Order order, String fallback) {
    if (order != null && order.getAeatsiiFechaOperacion() != null) {
      return formatDate(order.getAeatsiiFechaOperacion());
    }
    return fallback;
  }

  private static String formatDate(Date date) {
    return new SimpleDateFormat(DATE_PATTERN).format(date);
  }

  private static Date parseDate(String field, String raw) {
    try {
      SimpleDateFormat format = new SimpleDateFormat(DATE_PATTERN);
      format.setLenient(false);
      if (raw.length() < DATE_PATTERN.length()) {
        throw new ParseException(raw, 0);
      }
      return format.parse(raw.substring(0, DATE_PATTERN.length()));
    } catch (ParseException e) {
      throw invalid(field, "must be a date (yyyy-MM-dd)");
    }
  }
}
