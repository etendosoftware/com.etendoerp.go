/*
 *************************************************************************
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
 *************************************************************************
 */
package com.etendoerp.go.schemaforge;

import java.util.Set;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;

import javax.inject.Named;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.exception.OBException;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.utility.OBCurrencyUtils;
import org.openbravo.erpCommon.utility.OBMessageUtils;

/**
 * Pre/post-save hook for the businessPartner entity in the contacts spec.
 *
 * <p>On POST (new record):
 * <ul>
 *   <li>{@code handle()} derives {@code name} from {@code etgoFirstname} + {@code etgoLastname}
 *       when {@code name} is blank and at least one of the name parts is present (person mode).</li>
 *   <li>{@code handle()} then injects {@code searchKey = name} as a temporary placeholder so the
 *       mandatory field passes validation before the record is saved.</li>
 *   <li>{@code afterHandle()} overwrites {@code searchKey} with the auto-generated
 *       {@code em_etgo_identifier} value once Etendo has assigned it during save.</li>
 * </ul>
 *
 * <p>On PATCH/PUT (update):
 * <ul>
 *   <li>{@code handle()} derives {@code name} from the incoming firstname/lastname values
 *       (merging with the persisted values for whichever part is absent from the body)
 *       only when the record's current {@code name} is blank in the database.</li>
 * </ul>
 *
 * <p>On GET (single record, i.e. {@code /contacts/businessPartner/{id}}):
 * <ul>
 *   <li>{@code afterHandle()} fills the {@code etgoEmail} field with the email of one
 *       of the partner's contacts ({@code ad_user.email}) when the partner's own email
 *       field ({@code EM_Etgo_Email}) is blank. Company partners normally hold the email
 *       on their contacts, not on the partner record itself; the document Send modal
 *       autofills this value as the default recipient.</li>
 * </ul>
 *
 * <p>Registered via {@code JAVA_QUALIFIER = 'businessPartnerHandler'} on the
 * ETGO_SF_ENTITY record for the contacts spec's businessPartner entity.
 */
@Named("businessPartnerHandler")
public class BusinessPartnerHandler extends AbstractPersonNameHandler {

  private static final Logger log = LogManager.getLogger(BusinessPartnerHandler.class);
  private static final String RESPONSE_KEY = "response";
  private static final String FIELD_SEARCH_KEY = "searchKey";
  private static final String SQLSTATE_UNIQUE_VIOLATION = "23505";
  /** {@code C_BPartner.Value} is {@code VARCHAR(40)}. */
  private static final int SEARCH_KEY_MAX_LENGTH = 40;

  private static final Set<String> PRECREATE_BILLING_FIELDS = Set.of("priceList", "paymentMethod", "paymentTerms",
      "account", "customerBlocking", "purchasePricelist", "pOPaymentMethod", "pOPaymentTerms", "pOFinancialAccount",
      "vendorBlocking");
  private static final String FIELD_FIRSTNAME = "etgoFirstname";
  private static final String FIELD_LASTNAME = "etgoLastname";
  private static final String FIELD_EMAIL = "etgoEmail";
  private static final String FIELD_CURRENCY = "bPCurrencyID";
  private static final String FIELD_CUSTOMER = "customer";
  private static final String FIELD_VENDOR = "vendor";

  // ETP-4565 posting-account backfill (see provisionMissingBpAcctRows()): mirrors
  // OnboardingAccountingWiringService.BP_CUSTOMER_ACCT_SQL/BP_VENDOR_ACCT_SQL, but scoped to a
  // single already-persisted business partner (bound by ? = c_bpartner_id) instead of a
  // client-wide sweep, and driven by AD_Org_AcctSchema/ad_isorgincluded off the BP's OWN org
  // (mirroring c_bpartner_trg's own org-tree resolution) rather than a single onboarding-time
  // schema id.
  private static final String BP_CUSTOMER_ACCT_FOR_BP_SQL =
      "INSERT INTO c_bp_customer_acct ("
      + "  c_bp_customer_acct_id, ad_client_id, ad_org_id, isactive, created, createdby, updated, updatedby,"
      + "  c_bpartner_id, c_acctschema_id, c_receivable_acct, c_prepayment_acct) "
      + "SELECT get_uuid(), bp.ad_client_id, bp.ad_org_id, 'Y', now(), '0', now(), '0',"
      + "  bp.c_bpartner_id, d.c_acctschema_id, d.c_receivable_acct, d.c_prepayment_acct "
      + "FROM c_bpartner bp"
      + "  JOIN ad_org_acctschema oa ON oa.ad_client_id = bp.ad_client_id AND oa.isactive = 'Y'"
      + "  JOIN c_acctschema_default d ON d.c_acctschema_id = oa.c_acctschema_id "
      + "WHERE bp.c_bpartner_id = ? AND bp.iscustomer = 'Y'"
      + "  AND (ad_isorgincluded(oa.ad_org_id, bp.ad_org_id, bp.ad_client_id) <> -1"
      + "    OR ad_isorgincluded(bp.ad_org_id, oa.ad_org_id, bp.ad_client_id) <> -1)"
      + "  AND NOT EXISTS (SELECT 1 FROM c_bp_customer_acct a"
      + "    WHERE a.c_bpartner_id = bp.c_bpartner_id AND a.c_acctschema_id = d.c_acctschema_id)";

  private static final String BP_VENDOR_ACCT_FOR_BP_SQL =
      "INSERT INTO c_bp_vendor_acct ("
      + "  c_bp_vendor_acct_id, ad_client_id, ad_org_id, isactive, created, createdby, updated, updatedby,"
      + "  c_bpartner_id, c_acctschema_id, v_liability_acct, v_prepayment_acct) "
      + "SELECT get_uuid(), bp.ad_client_id, bp.ad_org_id, 'Y', now(), '0', now(), '0',"
      + "  bp.c_bpartner_id, d.c_acctschema_id, d.v_liability_acct, d.v_prepayment_acct "
      + "FROM c_bpartner bp"
      + "  JOIN ad_org_acctschema oa ON oa.ad_client_id = bp.ad_client_id AND oa.isactive = 'Y'"
      + "  JOIN c_acctschema_default d ON d.c_acctschema_id = oa.c_acctschema_id "
      + "WHERE bp.c_bpartner_id = ? AND bp.isvendor = 'Y'"
      + "  AND (ad_isorgincluded(oa.ad_org_id, bp.ad_org_id, bp.ad_client_id) <> -1"
      + "    OR ad_isorgincluded(bp.ad_org_id, oa.ad_org_id, bp.ad_client_id) <> -1)"
      + "  AND NOT EXISTS (SELECT 1 FROM c_bp_vendor_acct a"
      + "    WHERE a.c_bpartner_id = bp.c_bpartner_id AND a.c_acctschema_id = d.c_acctschema_id)";

  @Override
  protected String firstnameField() {
    return FIELD_FIRSTNAME;
  }

  @Override
  protected String lastnameField() {
    return FIELD_LASTNAME;
  }

  @Override
  protected String persistedNamePartsSql() {
    return "SELECT name, em_etgo_firstname, em_etgo_lastname FROM c_bpartner WHERE c_bpartner_id = ?";
  }

  /**
   * Returns the first record under {@code response.data} (or top-level {@code data}),
   * or {@code null} when the body has no record array.
   */
  private static JSONObject firstRecord(JSONObject body) {
    try {
      JSONObject response = body.optJSONObject(RESPONSE_KEY);
      JSONArray data = (response != null) ? response.optJSONArray("data") : body.optJSONArray("data");
      if (data == null || data.length() == 0) {
        return null;
      }
      return data.getJSONObject(0);
    } catch (Exception e) {
      return null;
    }
  }

  private static String extractRecordId(JSONObject body) {
    JSONObject recordNode = firstRecord(body);
    if (recordNode == null) {
      return null;
    }
    String id = recordNode.optString("id", null);
    return StringUtils.isNotBlank(id) ? id : null;
  }

  /**
   * Looks up the email of one active contact ({@code ad_user}) of the given business
   * partner. Returns the oldest active contact's email, or {@code null} when no
   * contact has a valid email.
   */
  private static String queryContactEmail(String bPartnerId) throws Exception {
    Connection conn = OBDal.getInstance().getConnection();
    try (PreparedStatement ps = conn.prepareStatement(
        "SELECT email FROM ad_user"
            + " WHERE c_bpartner_id = ? AND isactive = 'Y'"
            + "   AND email IS NOT NULL AND position('@' in email) > 0"
            + " ORDER BY created"
            + " LIMIT 1")) {
      ps.setString(1, bPartnerId);
      try (ResultSet rs = ps.executeQuery()) {
        if (rs.next()) {
          return StringUtils.trimToNull(rs.getString(1));
        }
      }
    }
    return null;
  }

  private static String queryIdentifier(String recordId) throws Exception {
    Connection conn = OBDal.getInstance().getConnection();
    try (PreparedStatement ps = conn.prepareStatement(
        "SELECT em_etgo_identifier FROM c_bpartner WHERE c_bpartner_id = ?")) {
      ps.setString(1, recordId);
      try (ResultSet rs = ps.executeQuery()) {
        if (rs.next()) {
          return rs.getString(1);
        }
      }
    }
    return null;
  }

  private static void updateSearchKey(String recordId, String identifier) throws Exception {
    Connection conn = OBDal.getInstance().getConnection();
    try (PreparedStatement ps = conn.prepareStatement("UPDATE c_bpartner SET value = ? WHERE c_bpartner_id = ?")) {
      ps.setString(1, identifier);
      ps.setString(2, recordId);
      ps.executeUpdate();
    }
  }

  /**
   * Runs {@link #updateSearchKey(String, String)} under a savepoint so a duplicate-key
   * hit (two concurrent rows racing on the same not-yet-committed sequence value) only
   * rolls back the failed UPDATE instead of poisoning the whole shared {@code /batch}
   * transaction for every other operation that follows on the same connection.
   *
   * @return {@code true} when the identifier was applied, {@code false} when it was
   *         skipped due to a duplicate-key race (the placeholder {@code searchKey} is
   *         kept in that case).
   */
  private static boolean applySearchKeyUpdate(String recordId, String identifier) throws Exception {
    Connection conn = OBDal.getInstance().getConnection();
    Savepoint savepoint = conn.setSavepoint();
    try {
      updateSearchKey(recordId, identifier);
      return true;
    } catch (SQLException e) {
      if (!SQLSTATE_UNIQUE_VIOLATION.equals(e.getSQLState())) {
        throw e;
      }
      log.warn("BusinessPartnerHandler: searchKey race on bp={} (duplicate identifier {}), "
          + "keeping placeholder searchKey", recordId, identifier, e);
      conn.rollback(savepoint);
      return false;
    }
  }

  private static void patchSearchKeyInResponse(JSONObject body, String identifier) {
    try {
      JSONObject response = body.optJSONObject(RESPONSE_KEY);
      if (response == null) {
        return;
      }
      JSONArray data = response.optJSONArray("data");
      if (data == null || data.length() == 0) {
        return;
      }
      data.getJSONObject(0).put(FIELD_SEARCH_KEY, identifier);
    } catch (Exception e) {
      log.warn("BusinessPartnerHandler: could not patch searchKey in response", e);
    }
  }

  @Override
  public NeoResponse handle(NeoContext ctx) {
    String method = ctx.getHttpMethod();
    boolean isWrite = "POST".equals(method) || "PATCH".equals(method) || "PUT".equals(method);
    if (!isWrite) {
      return null;
    }
    JSONObject body = ctx.getRequestBody();
    if (body == null) {
      return null;
    }
    try {
      deriveName(ctx, body);

      if ("POST".equals(method)) {
        stripPreCreateBillingDefaults(body);
        String name = body.optString(FIELD_NAME, null);
        if (StringUtils.isNotBlank(name) && !body.has(FIELD_SEARCH_KEY)) {
          // C_BPartner.Value is VARCHAR(40) while Name has 60, so a long commercial name
          // cannot be used verbatim as the placeholder ("Value too long. Length 48,
          // maximum allowed 40"). afterHandle() replaces it with em_etgo_identifier
          // anyway, so truncating here is lossless. ETP-4156: the app-shell used to
          // pre-truncate this client-side, which masked the missing guard.
          body.put(FIELD_SEARCH_KEY, StringUtils.substring(name, 0, SEARCH_KEY_MAX_LENGTH));
        }
        injectOrgCurrency(ctx, body);
      }
    } catch (Exception e) {
      log.error("BusinessPartnerHandler: error in handle()", e);
      throw new OBException("Error processing BusinessPartner name derivation", e);
    }
    return null;
  }

  /**
   * A new Business Partner (contact) with no currency breaks purchase invoice confirmation
   * later on ({@code ProcessInvoiceUtil} in the core validates {@code businessPartner
   * .getCurrency() == null} with no fallback). When a create request omits the currency
   * field, resolve and inject the organization's currency so {@code BP_Currency_ID} is
   * populated deterministically instead of relying on a session default that may be null.
   * Never overwrites a currency the caller explicitly set.
   */
  private void injectOrgCurrency(NeoContext ctx, JSONObject body) {
    if (body.has(FIELD_CURRENCY) && StringUtils.isNotBlank(body.optString(FIELD_CURRENCY, null))) {
      return;
    }
    OBContext obContext = ctx.getObContext();
    if (obContext == null || obContext.getCurrentOrganization() == null) {
      return;
    }
    try {
      OBContext.setAdminMode();
      try {
        String orgId = obContext.getCurrentOrganization().getId();
        String currencyId = OBCurrencyUtils.getOrgCurrency(orgId);
        if (StringUtils.isNotBlank(currencyId)) {
          body.put(FIELD_CURRENCY, currencyId);
        }
      } finally {
        OBContext.restorePreviousMode();
      }
    } catch (Exception e) {
      log.warn("BusinessPartnerHandler: could not inject organization currency", e);
    }
  }

  private void stripPreCreateBillingDefaults(JSONObject body) {
    for (String key : PRECREATE_BILLING_FIELDS) {
      body.remove(key);
      body.remove(key + "$_identifier");
    }
  }

  @Override
  public NeoResponse afterHandle(NeoContext ctx) {
    String method = ctx.getHttpMethod();
    if ("GET".equals(method)) {
      return fillContactEmailFallback(ctx);
    }
    boolean isWrite = "POST".equals(method) || "PUT".equals(method) || "PATCH".equals(method);
    if (!isWrite) {
      return null;
    }
    NeoResponse previousResult = ctx.getPreviousResult();
    if (previousResult == null || previousResult.getBody() == null) {
      return null;
    }
    try {
      JSONObject body = previousResult.getBody();
      boolean modified = false;

      if ("POST".equals(method)) {
        String recordId = extractRecordId(body);
        if (recordId != null) {
          String identifier = queryIdentifier(recordId);
          if (StringUtils.isNotBlank(identifier) && applySearchKeyUpdate(recordId, identifier)) {
            patchSearchKeyInResponse(body, identifier);
            modified = true;
          }
        }
      } else {
        // PUT/PATCH: flipping vendor/customer to Y on an already-persisted BP never runs through
        // c_bpartner_trg (it only fires on TG_OP='INSERT'), so backfill whichever posting-account
        // row is now missing. See provisionMissingBpAcctRows() for the full rationale (ETP-4565).
        provisionMissingBpAcctRows(ctx);
      }

      modified |= injectViesMessage(body);
      return modified ? NeoResponse.ok(body) : null;
    } catch (Exception e) {
      log.error("BusinessPartnerHandler: error in afterHandle()", e);
      return null;
    }
  }

  /**
   * ETP-4565 — closes the "vendor/customer flag flipped after creation" accounting gap.
   *
   * <p>Classic's native {@code c_bpartner_trg} trigger auto-creates {@code C_BP_Customer_Acct}/
   * {@code C_BP_Vendor_Acct} rows unconditionally for every new {@code C_BPartner}, but ONLY on
   * {@code TG_OP='INSERT'} — flipping {@code IsCustomer}/{@code IsVendor} from N to Y on an
   * already-persisted BP via {@code UPDATE} never creates the newly-relevant row (confirmed live:
   * a BP created as customer-only, later updated to also be a vendor, is left with a permanently
   * empty Vendor accounting tab). This backfills whichever row is missing, right after a
   * successful PUT/PATCH, only when the request actually touched the {@code vendor}/{@code
   * customer} flag — so the common case (updating unrelated fields) never pays for the extra
   * lookup. Mirrors {@code OnboardingAccountingWiringService}'s posting-account backfill idiom:
   * default accounts copied from {@code C_AcctSchema_Default}, one row per {@code AcctSchema}
   * wired to the BP's own org (via {@code AD_Org_AcctSchema} / {@code ad_isorgincluded}), guarded
   * by {@code NOT EXISTS} so it is idempotent and a no-op once the row exists.
   */
  private void provisionMissingBpAcctRows(NeoContext ctx) {
    JSONObject requestBody = ctx.getRequestBody();
    String recordId = ctx.getRecordId();
    if (requestBody == null || StringUtils.isBlank(recordId)) {
      return;
    }
    try {
      if (requestBody.has(FIELD_CUSTOMER)) {
        runBpAcctBackfill(BP_CUSTOMER_ACCT_FOR_BP_SQL, recordId);
      }
      if (requestBody.has(FIELD_VENDOR)) {
        runBpAcctBackfill(BP_VENDOR_ACCT_FOR_BP_SQL, recordId);
      }
    } catch (Exception e) {
      log.warn("BusinessPartnerHandler: could not backfill customer/vendor posting accounts for bp={}",
          recordId, e);
    }
  }

  private static void runBpAcctBackfill(String sql, String recordId) throws SQLException {
    Connection conn = OBDal.getInstance().getConnection();
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
      ps.setString(1, recordId);
      ps.executeUpdate();
    }
  }

  /**
   * If the saved record has {@code oBTIKTaxIDKey = '2'} and a resolved VIES status (V or I),
   * injects a {@code messages} array at the root of the response body so the frontend
   * (useEntity.js, line: {@code data?.messages}) can show the toast.
   */
  private static boolean injectViesMessage(JSONObject body) {
    try {
      JSONObject response = body.optJSONObject(RESPONSE_KEY);
      if (response == null) {
        return false;
      }
      JSONArray data = response.optJSONArray("data");
      if (data == null || data.length() == 0) {
        return false;
      }
      JSONObject savedRecord = data.getJSONObject(0);
      String taxIdKey = savedRecord.optString("oBTIKTaxIDKey", null);
      if (!"2".equals(taxIdKey)) {
        return false;
      }
      String viesStatus = savedRecord.optString("oBTIKVIESStatus", null);
      if (viesStatus == null || "P".equals(viesStatus)) {
        return false;
      }

      boolean valid = "V".equals(viesStatus);
      JSONObject msg = new JSONObject();
      msg.put("type", valid ? "success" : "warning");
      msg.put("title", OBMessageUtils.messageBD(valid ? "OBTIK_ViesValidTitle" : "OBTIK_ViesInvalidTitle"));
      msg.put("text", OBMessageUtils.messageBD(valid ? "OBTIK_ViesValidText" : "OBTIK_ViesInvalidText"));

      JSONArray messages = new JSONArray();
      messages.put(msg);
      body.put("messages", messages);
      return true;
    } catch (Exception e) {
      log.warn("BusinessPartnerHandler: could not inject VIES message", e);
      return false;
    }
  }

  /**
   * On a single-record GET, injects a contact email into {@code etgoEmail} when the
   * partner's own email field is blank, so the document Send modal has a default
   * recipient to propose. Returns {@code null} (keeping the default CRUD result) for
   * list fetches, partners that already carry an email, or when no contact email exists.
   */
  private NeoResponse fillContactEmailFallback(NeoContext ctx) {
    String recordId = ctx.getRecordId();
    if (StringUtils.isBlank(recordId)) {
      return null; // list fetch — no single partner to resolve
    }
    NeoResponse previousResult = ctx.getPreviousResult();
    if (previousResult == null || previousResult.getBody() == null) {
      return null;
    }
    JSONObject recordNode = firstRecord(previousResult.getBody());
    if (recordNode == null) {
      return null;
    }
    if (recordNode.optString(FIELD_EMAIL, "").contains("@")) {
      return null; // partner already has its own email
    }
    try {
      String contactEmail = queryContactEmail(recordId);
      if (StringUtils.isBlank(contactEmail)) {
        return null;
      }
      recordNode.put(FIELD_EMAIL, contactEmail);
      return NeoResponse.ok(previousResult.getBody());
    } catch (Exception e) {
      log.warn("BusinessPartnerHandler: could not resolve contact email fallback for bp={}", recordId, e);
      return null;
    }
  }
}
