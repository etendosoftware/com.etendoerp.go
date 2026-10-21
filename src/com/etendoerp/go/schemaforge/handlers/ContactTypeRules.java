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

package com.etendoerp.go.schemaforge.handlers;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;

import org.apache.commons.lang3.StringUtils;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;

import com.etendoerp.go.schemaforge.MessageKeyedException;
import com.etendoerp.go.schemaforge.NeoContext;
import com.etendoerp.go.schemaforge.NeoEndpointType;
import com.etendoerp.go.schemaforge.NeoResponse;
import com.etendoerp.go.schemaforge.util.NeoBooleanFormat;

/**
 * Contact-type rules shared by the contact ({@code C_BPartner}) and contact-category
 * ({@code C_BP_Group}) customizations (ETP-5791).
 *
 * <p>This is an entity-specific util, not shared infrastructure: it is called explicitly by the
 * customizations that own these two tables ({@code BusinessPartnerHandler},
 * {@link ContactTypeFlagGuardHandler} and its subclasses, {@link BusinessPartnerCategoryHandler}),
 * never by a generic NEO service.
 *
 * <h2>The rules</h2>
 * <ul>
 *   <li><b>At least one type.</b> A category ({@code EM_Etgo_Iscustomer} /
 *       {@code EM_Etgo_Isvendor}) and a contact ({@code IsCustomer} / {@code IsVendor}) cannot be
 *       saved with both flags off. Insert and update; an existing contact without a type fails on
 *       its next edit, which is accepted by the story.</li>
 *   <li><b>Inheritance on create.</b> A new contact takes {@code customer}/{@code vendor} from its
 *       category, unless the request carries the flag explicitly — an explicit value always wins.
 *       Existing contacts are never touched. A category with neither flag (legacy data, not yet
 *       migrated) defines nothing, so nothing is inherited from it and the AD defaults apply.</li>
 * </ul>
 *
 * <h2>"Explicit" is presence in the request, not a value</h2>
 * <p>{@code IsCustomer} defaults to {@code 'Y'} in AD, so a {@code customer:true} cannot say
 * whether the caller chose it. The REST single write, the REST batch and MCP {@code etendo_batch}
 * all run the pre-hook <i>before</i> the create-defaults pass, so the body they hand over holds
 * exactly what the caller sent and presence of the key is the answer.</p>
 *
 * <p>MCP {@code etendo_create} injects the mandatory defaults ({@code customer=true},
 * {@code vendor=false}) <i>before</i> it dispatches the pre-hook, so there the body cannot answer.
 * That channel hands over the keys the agent actually sent under
 * {@link NeoContext#ATTR_CLIENT_PROVIDED_FIELDS}, and when that attribute is present it is the
 * answer instead of the body. Every channel therefore honours an explicit flag the same way.</p>
 */
public final class ContactTypeRules {

  /** {@code AD_MESSAGE.Value} raised when a category is saved with no contact type. */
  public static final String MSG_CATEGORY_TYPE_REQUIRED = "ETGO_BPGroupContactTypeRequired";
  /** {@code AD_MESSAGE.Value} raised when a contact is saved with no contact type. */
  public static final String MSG_CONTACT_TYPE_REQUIRED = "ETGO_BPartnerContactTypeRequired";

  /** {@code C_BPartner.IsCustomer}. */
  public static final String FIELD_CUSTOMER = "customer";
  /** {@code C_BPartner.IsVendor}. */
  public static final String FIELD_VENDOR = "vendor";
  /** {@code C_BPartner.C_BP_Group_ID}. */
  static final String FIELD_CATEGORY = "businessPartnerCategory";
  /** {@code AD_Org_ID} of the record being written. */
  static final String FIELD_ORGANIZATION = "organization";
  /** {@code C_BP_Group.EM_Etgo_Iscustomer}. */
  static final String FIELD_CATEGORY_CUSTOMER = "etgoIscustomer";
  /** {@code C_BP_Group.EM_Etgo_Isvendor}. */
  static final String FIELD_CATEGORY_VENDOR = "etgoIsvendor";

  /** {@code C_BPartner.IsCustomer} AD default — what a create without the key ends up with. */
  static final boolean CONTACT_CUSTOMER_CREATE_DEFAULT = true;
  /** {@code C_BPartner.IsVendor} has no AD default; the mandatory pass fills {@code false}. */
  static final boolean CONTACT_VENDOR_CREATE_DEFAULT = false;
  /** {@code C_BP_Group.EM_Etgo_Is*} AD default. */
  static final boolean CATEGORY_FLAG_CREATE_DEFAULT = false;

  private static final String METHOD_POST = "POST";
  private static final String METHOD_PATCH = "PATCH";
  private static final String METHOD_PUT = "PUT";

  // Mirrors the AD default of C_BPartner.C_BP_Group_ID exactly, so a create that omits the
  // category inherits from the category the create-defaults pass is about to assign.
  private static final String DEFAULT_CATEGORY_SQL =
      "SELECT MAX(c_bp_group_id) FROM c_bp_group"
          + " WHERE ad_isorgincluded(?, ad_org_id, ?) <> -1 AND isdefault = 'Y' AND ad_client_id = ?";
  private static final String CATEGORY_TYPES_SQL =
      "SELECT em_etgo_iscustomer, em_etgo_isvendor FROM c_bp_group WHERE c_bp_group_id = ?";
  private static final String CONTACT_TYPES_SQL =
      "SELECT iscustomer, isvendor FROM c_bpartner WHERE c_bpartner_id = ?";

  private ContactTypeRules() {
  }

  /** The two type flags of a contact or a category. */
  static final class Types {
    final boolean customer;
    final boolean vendor;

    Types(boolean customer, boolean vendor) {
      this.customer = customer;
      this.vendor = vendor;
    }

    boolean any() {
      return customer || vendor;
    }
  }

  /**
   * Whether the request is a write on the CRUD surface of the entity (not a selector, action,
   * defaults or callout request that happens to carry a body).
   */
  public static boolean isCrudWrite(NeoContext ctx) {
    if (ctx == null || ctx.getRequestBody() == null) {
      return false;
    }
    NeoEndpointType type = ctx.getEndpointType();
    if (type != null && type != NeoEndpointType.CRUD) {
      return false;
    }
    String method = ctx.getHttpMethod();
    return METHOD_POST.equals(method) || METHOD_PATCH.equals(method) || METHOD_PUT.equals(method);
  }

  /** Whether the CRUD write is a create. */
  public static boolean isCreate(NeoContext ctx) {
    return METHOD_POST.equals(ctx.getHttpMethod());
  }

  // ── Contact (C_BPartner) ─────────────────────────────────────────────────────────────────────

  /**
   * Create of a contact: fills every type flag the request did not carry explicitly from the
   * contact's category, then rejects the write when it would leave the contact with no type.
   *
   * @return the rejection, or {@code null} to continue with the (possibly enriched) body
   */
  public static NeoResponse inheritAndValidateOnCreate(NeoContext ctx, JSONObject body)
      throws SQLException, JSONException {
    boolean customerExplicit = isExplicit(ctx, body, FIELD_CUSTOMER);
    boolean vendorExplicit = isExplicit(ctx, body, FIELD_VENDOR);

    if (!customerExplicit || !vendorExplicit) {
      Types inherited = queryCategoryTypes(resolveCategoryId(ctx, body));
      if (inherited != null && inherited.any()) {
        if (!customerExplicit) {
          body.put(FIELD_CUSTOMER, inherited.customer);
        }
        if (!vendorExplicit) {
          body.put(FIELD_VENDOR, inherited.vendor);
        }
      }
    }

    boolean customer = effective(body, FIELD_CUSTOMER, CONTACT_CUSTOMER_CREATE_DEFAULT);
    boolean vendor = effective(body, FIELD_VENDOR, CONTACT_VENDOR_CREATE_DEFAULT);
    return customer || vendor ? null : reject(MSG_CONTACT_TYPE_REQUIRED);
  }

  /**
   * Update of a contact: rejects the write when the flags it leaves on the record (the request's
   * own, else the persisted ones) are both off.
   *
   * @param onlyWhenTouched {@code true} to check only a write that sends {@code customer} or
   *     {@code vendor} (the Customer / Vendor tabs, which also save unrelated fields);
   *     {@code false} to check every update (the contact header, per the story)
   * @return the rejection, or {@code null} to continue
   */
  public static NeoResponse validateContactUpdate(NeoContext ctx, JSONObject body,
      boolean onlyWhenTouched) throws SQLException {
    if (onlyWhenTouched && !body.has(FIELD_CUSTOMER) && !body.has(FIELD_VENDOR)) {
      return null;
    }
    Types persisted = queryTypes(CONTACT_TYPES_SQL, ctx.getRecordId());
    if (persisted == null) {
      return null; // no record to judge (concurrent delete); the generic path reports it
    }
    boolean customer = effective(body, FIELD_CUSTOMER, persisted.customer);
    boolean vendor = effective(body, FIELD_VENDOR, persisted.vendor);
    return customer || vendor ? null : reject(MSG_CONTACT_TYPE_REQUIRED);
  }

  // ── Category (C_BP_Group) ────────────────────────────────────────────────────────────────────

  /**
   * Create or update of a contact category: rejects the write when it leaves the category with
   * neither {@code etgoIscustomer} nor {@code etgoIsvendor}.
   *
   * @return the rejection, or {@code null} to continue
   */
  public static NeoResponse validateCategoryWrite(NeoContext ctx, JSONObject body) throws SQLException {
    Types base;
    if (isCreate(ctx)) {
      base = new Types(CATEGORY_FLAG_CREATE_DEFAULT, CATEGORY_FLAG_CREATE_DEFAULT);
    } else {
      base = queryCategoryTypes(ctx.getRecordId());
      if (base == null) {
        return null; // no record to judge (concurrent delete); the generic path reports it
      }
    }
    boolean customer = effective(body, FIELD_CATEGORY_CUSTOMER, base.customer);
    boolean vendor = effective(body, FIELD_CATEGORY_VENDOR, base.vendor);
    return customer || vendor ? null : reject(MSG_CATEGORY_TYPE_REQUIRED);
  }

  // ── Helpers ──────────────────────────────────────────────────────────────────────────────────

  /**
   * Whether the request chose {@code key} itself: the channel's own record of the keys the caller
   * sent when it provides one ({@link NeoContext#ATTR_CLIENT_PROVIDED_FIELDS}, MCP
   * {@code etendo_create}), otherwise presence of the key in the body.
   */
  static boolean isExplicit(NeoContext ctx, JSONObject body, String key) {
    Object clientProvided = ctx.getAttribute(NeoContext.ATTR_CLIENT_PROVIDED_FIELDS);
    if (clientProvided instanceof Collection) {
      return ((Collection<?>) clientProvided).contains(key);
    }
    return body.has(key);
  }

  /** The body's value for {@code key} when it carries one, else {@code fallback}. */
  static boolean effective(JSONObject body, String key, boolean fallback) {
    return body.has(key) ? flag(body.opt(key)) : fallback;
  }

  /** Lenient boolean read of a body value: JSON boolean, {@code "Y"/"N"} or {@code "true"/"false"}. */
  static boolean flag(Object value) {
    if (value instanceof Boolean) {
      return (Boolean) value;
    }
    return value != null && value != JSONObject.NULL && NeoBooleanFormat.toLenientBoolean(value.toString());
  }

  private static NeoResponse reject(String messageKey) {
    return NeoResponse.error(400, new MessageKeyedException(messageKey));
  }

  /**
   * The category a new contact will belong to: the request's own, else the default category the
   * create-defaults pass assigns ({@link #DEFAULT_CATEGORY_SQL}).
   */
  static String resolveCategoryId(NeoContext ctx, JSONObject body) throws SQLException {
    String explicit = idOf(body.opt(FIELD_CATEGORY));
    if (explicit != null) {
      return explicit;
    }
    OBContext obContext = ctx.getObContext();
    if (obContext == null || obContext.getCurrentClient() == null) {
      return null;
    }
    String orgId = idOf(body.opt(FIELD_ORGANIZATION));
    if (orgId == null && obContext.getCurrentOrganization() != null) {
      orgId = obContext.getCurrentOrganization().getId();
    }
    if (orgId == null) {
      return null;
    }
    String clientId = obContext.getCurrentClient().getId();
    Connection conn = OBDal.getInstance().getConnection();
    try (PreparedStatement ps = conn.prepareStatement(DEFAULT_CATEGORY_SQL)) {
      ps.setString(1, orgId);
      ps.setString(2, clientId);
      ps.setString(3, clientId);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? StringUtils.trimToNull(rs.getString(1)) : null;
      }
    }
  }

  /** An FK value as sent: a plain id, or an object carrying {@code id}. */
  private static String idOf(Object value) {
    if (value instanceof JSONObject) {
      return StringUtils.trimToNull(((JSONObject) value).optString("id", null));
    }
    if (value == null || value == JSONObject.NULL) {
      return null;
    }
    return StringUtils.trimToNull(value.toString());
  }

  /** The category's two flags, or {@code null} when the id is blank or the row does not exist. */
  static Types queryCategoryTypes(String categoryId) throws SQLException {
    return queryTypes(CATEGORY_TYPES_SQL, categoryId);
  }

  private static Types queryTypes(String sql, String id) throws SQLException {
    if (StringUtils.isBlank(id)) {
      return null;
    }
    Connection conn = OBDal.getInstance().getConnection();
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
      ps.setString(1, id);
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) {
          return null;
        }
        return new Types("Y".equals(rs.getString(1)), "Y".equals(rs.getString(2)));
      }
    }
  }
}
