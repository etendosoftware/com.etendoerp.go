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

import java.time.LocalDate;
import java.time.Year;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.inject.Named;
import javax.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.criterion.Projections;
import org.hibernate.criterion.Restrictions;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.utility.OBCurrencyUtils;
import org.openbravo.erpCommon.utility.OBMessageUtils;
import org.openbravo.model.pricing.pricelist.PriceList;
import org.openbravo.model.pricing.pricelist.PriceListSchema;
import org.openbravo.model.pricing.pricelist.PriceListVersion;
import org.openbravo.model.pricing.pricelist.ProductPrice;

/**
 * NeoHandler for the Price List header entity.
 *
 * <p>Owns all GO-specific behavior around the hidden {@link PriceListVersion}
 * that backs each {@link PriceList} in the simplified interface:
 *
 * <ul>
 *   <li><b>POST (create)</b>: auto-creates one {@link PriceListVersion} and its
 *       required {@link PriceListSchema} so product prices can be added immediately.</li>
 *   <li><b>PATCH / PUT (update)</b>: keeps the version name in sync with the price list name.</li>
 *   <li><b>GET</b>: injects {@code priceListVersion} (the single version id) into each
 *       record so the frontend can locate product prices in one fetch instead of round-tripping
 *       through {@code priceListVersion?parentId=...}.</li>
 * </ul>
 *
 * <p>Living at the NEO Headless layer (rather than as an
 * {@code EntityPersistenceEventObserver}) ensures these GO-specific behaviors do not
 * affect Etendo Classic / Enterprise users that operate directly on the AD windows.
 */
@Named("priceListHeaderHandler")
public class PriceListHeaderHandler implements NeoHandler {

  private static final String FIELD_PRICE_LIST_VERSION = "priceListVersion";
  private static final String FIELD_CURRENCY = "currency";
  private static final String FIELD_PRODUCT_COUNT = "etgoProductcount";
  private static final String FIELD_ACTIVE = "active";
  private static final int MAX_SCHEMA_NAME_LENGTH = 60;
  private static final String DEFAULT_SCHEMA_NAME = "Esquema de Lista de Precios";
  private static final String MSG_CANNOT_DEACTIVATE_DEFAULT = "ETGO_PriceListCannotDeactivateDefault";

  private final Logger log = LogManager.getLogger(getClass());

  @Override
  public NeoResponse handle(NeoContext context) {
    if (context == null) {
      return null;
    }
    String method = context.getHttpMethod();
    if ("POST".equals(method)) {
      injectOrgCurrency(context);
      return null;
    }
    if (("PATCH".equals(method) || "PUT".equals(method))
        && isExplicitlyDeactivating(context.getRequestBody())) {
      return blockDeactivatingDefault(context);
    }
    return null;
  }

  /**
   * ETP-4592: a tariff marked as the organization's default cannot be deactivated — it must
   * be un-marked as default first (a separate, explicit action). Only short-circuits when the
   * request explicitly turns {@code active} off; requests that don't touch it (or that also
   * flip {@code active} back on) fall through unaffected.
   */
  private static boolean isExplicitlyDeactivating(JSONObject body) {
    if (body == null || !body.has(FIELD_ACTIVE)) {
      return false;
    }
    Object value = body.opt(FIELD_ACTIVE);
    if (value instanceof Boolean) {
      return !(Boolean) value;
    }
    if (value instanceof String) {
      return "false".equalsIgnoreCase((String) value) || "N".equalsIgnoreCase((String) value);
    }
    return false;
  }

  private NeoResponse blockDeactivatingDefault(NeoContext context) {
    String recordId = context.getRecordId();
    if (recordId == null) {
      return null;
    }
    PriceList priceList = OBDal.getInstance().get(PriceList.class, recordId);
    if (priceList != null && Boolean.TRUE.equals(priceList.isDefault())) {
      // messageBD reads the AD_Message base text (English) — same catalog pattern as
      // BusinessPartnerHandler / ContactsLocationAddressHandler, instead of a literal
      // string. Translation to the active locale happens on the FRONTEND, not here:
      // tools/app-shell/src/lib/backendErrors.js maps this exact English string to an
      // i18n key. That match is exact-string (see BACKEND_ERROR_MAP), so the AD_Message
      // MSGTEXT for ETGO_PriceListCannotDeactivateDefault must stay English and stay
      // byte-for-byte identical to the map's key — do NOT add an AD_Message_Trl es_ES row
      // for this message, it would silently break the frontend match.
      return NeoResponse.error(HttpServletResponse.SC_BAD_REQUEST,
          OBMessageUtils.messageBD(MSG_CANNOT_DEACTIVATE_DEFAULT));
    }
    return null;
  }

  /**
   * Tariffs have no currency field in the simplified interface — they inherit the
   * organization currency (see the "Monedas y Tarifas" spec §5). When a create request
   * omits {@code currency} (e.g. the inline "create tariff" flow from the product price
   * tab), resolve and inject the org currency so the mandatory {@code C_Currency_ID}
   * column is satisfied deterministically instead of relying on a session default.
   */
  private void injectOrgCurrency(NeoContext context) {
    JSONObject body = context.getRequestBody();
    if (body == null || body.has(FIELD_CURRENCY)) {
      return;
    }
    OBContext obContext = context.getObContext();
    if (obContext == null || obContext.getCurrentOrganization() == null) {
      return;
    }
    try {
      OBContext.setAdminMode();
      try {
        String orgId = obContext.getCurrentOrganization().getId();
        String currencyId = OBCurrencyUtils.getOrgCurrency(orgId);
        if (currencyId != null && !currencyId.isEmpty()) {
          body.put(FIELD_CURRENCY, currencyId);
        }
      } finally {
        OBContext.restorePreviousMode();
      }
    } catch (Exception e) {
      log.warn("Could not inject organization currency into price list create: {}", e.getMessage());
    }
  }

  @Override
  public NeoResponse afterHandle(NeoContext context) {
    NeoResponse previousResult = context.getPreviousResult();
    if (previousResult == null || previousResult.getBody() == null) {
      return null;
    }
    String method = context.getHttpMethod();
    if (!"GET".equals(method) && !"POST".equals(method)
        && !"PATCH".equals(method) && !"PUT".equals(method)) {
      return null;
    }
    try {
      JSONObject body = previousResult.getBody();
      JSONObject responseWrapper = body.optJSONObject("response");
      if (responseWrapper == null) {
        return null;
      }
      JSONArray dataArr = responseWrapper.optJSONArray("data");
      if (dataArr == null || dataArr.length() == 0) {
        return null;
      }
      if ("POST".equals(method)) {
        ensureDefaultVersionForFirstRecord(dataArr);
      } else if ("PATCH".equals(method) || "PUT".equals(method)) {
        syncVersionNameForFirstRecord(dataArr);
      }
      annotateRecords(context, dataArr);
      return NeoResponse.ok(body);
    } catch (Exception e) {
      log.error("Error post-processing price list response", e);
      return null;
    }
  }

  // ── version annotation (injects priceListVersion into the response) ───────

  private void annotateRecords(NeoContext context, JSONArray dataArr) throws Exception {
    if (context.getRecordId() != null || dataArr.length() == 1) {
      JSONObject rec = dataArr.getJSONObject(0);
      String versionId = resolveVersionId(rec.optString("id", null));
      rec.put(FIELD_PRICE_LIST_VERSION, versionId);
      rec.put(FIELD_PRODUCT_COUNT, countProductsForVersion(versionId));
    } else {
      annotateBatch(dataArr);
    }
  }

  private void annotateBatch(JSONArray dataArr) throws Exception {
    List<JSONObject> records = extractRecords(dataArr);
    List<String> priceListIds = extractIds(records);
    if (priceListIds.isEmpty()) {
      return;
    }
    Map<String, String> versionByPriceListId = PriceListVersionResolver
        .findSingleVersionIds(priceListIds);
    Map<String, Long> countByVersionId = countProductsByVersionIds(
        new ArrayList<>(versionByPriceListId.values()));
    for (JSONObject rec : records) {
      String plId = rec.optString("id", null);
      String versionId = plId != null ? versionByPriceListId.get(plId) : null;
      rec.put(FIELD_PRICE_LIST_VERSION, versionId != null ? versionId : "");
      rec.put(FIELD_PRODUCT_COUNT,
          versionId != null ? countByVersionId.getOrDefault(versionId, 0L) : 0L);
    }
  }

  // ── product count (ETP-4592) ─────────────────────────────────────────────
  // Computed on every response instead of read from a stored column: M_ProductPrice
  // rows can be added/removed through many paths (UI, imports, batch processes, direct
  // SQL), and a stored counter would need every one of them to keep it in sync. Recomputing
  // here has a small query cost but can never go stale.

  /**
   * Counts the active {@link ProductPrice} rows for a single price list version.
   *
   * @param versionId
   *     the {@code M_PriceList_Version_ID}, possibly {@code null}/empty
   * @return the number of active product prices, or {@code 0} if the version is unknown
   */
  private static long countProductsForVersion(String versionId) {
    if (versionId == null || versionId.isEmpty()) {
      return 0L;
    }
    OBCriteria<ProductPrice> crit = OBDal.getInstance().createCriteria(ProductPrice.class);
    crit.add(Restrictions.eq(ProductPrice.PROPERTY_PRICELISTVERSION + ".id", versionId));
    crit.add(Restrictions.eq(ProductPrice.PROPERTY_ACTIVE, true));
    crit.setProjection(Projections.rowCount());
    Object result = crit.uniqueResult();
    return result instanceof Number ? ((Number) result).longValue() : 0L;
  }

  /**
   * Counts active {@link ProductPrice} rows for a batch of price list versions in a single
   * grouped query, avoiding N+1 for list GET responses.
   *
   * @param versionIds
   *     the {@code M_PriceList_Version_ID} values to count for
   * @return map of {@code versionId → count}; versions with no product prices are absent
   */
  @SuppressWarnings("unchecked")
  private static Map<String, Long> countProductsByVersionIds(List<String> versionIds) {
    Map<String, Long> counts = new HashMap<>();
    if (versionIds == null || versionIds.isEmpty()) {
      return counts;
    }
    OBCriteria<ProductPrice> crit = OBDal.getInstance().createCriteria(ProductPrice.class);
    crit.add(Restrictions.in(ProductPrice.PROPERTY_PRICELISTVERSION + ".id", versionIds));
    crit.add(Restrictions.eq(ProductPrice.PROPERTY_ACTIVE, true));
    crit.setProjection(
        Projections.projectionList().add(Projections.groupProperty(ProductPrice.PROPERTY_PRICELISTVERSION + ".id")).add(
            Projections.rowCount()));
    List<Object[]> rows = (List<Object[]>) (List<?>) crit.list();
    for (Object[] row : rows) {
      counts.put((String) row[0], (Long) row[1]);
    }
    return counts;
  }

  private static List<JSONObject> extractRecords(JSONArray dataArr) throws Exception {
    List<JSONObject> list = new ArrayList<>(dataArr.length());
    for (int i = 0; i < dataArr.length(); i++) {
      list.add(dataArr.getJSONObject(i));
    }
    return list;
  }

  private static List<String> extractIds(List<JSONObject> records) {
    List<String> ids = new ArrayList<>();
    for (JSONObject rec : records) {
      String id = rec.optString("id", null);
      if (id != null && !id.isEmpty()) {
        ids.add(id);
      }
    }
    return ids;
  }

  private String resolveVersionId(String priceListId) {
    if (priceListId == null || priceListId.isEmpty()) {
      return "";
    }
    PriceList priceList = OBDal.getInstance().get(PriceList.class, priceListId);
    String versionId = PriceListVersionResolver.findSingleVersionId(priceList);
    return versionId != null ? versionId : "";
  }

  // ── version lifecycle (auto-create on POST, sync name on UPDATE) ─────────

  private void ensureDefaultVersionForFirstRecord(JSONArray dataArr) throws Exception {
    String priceListId = dataArr.getJSONObject(0).optString("id", null);
    if (priceListId == null || priceListId.isEmpty()) {
      return;
    }
    PriceList priceList = OBDal.getInstance().get(PriceList.class, priceListId);
    if (priceList == null) {
      return;
    }
    if (PriceListVersionResolver.findSingleVersion(priceList) != null) {
      return;
    }
    createDefaultVersion(priceList);
  }

  private void createDefaultVersion(PriceList priceList) {
    PriceListSchema schema = findOrCreateSchema(priceList);

    PriceListVersion version = OBProvider.getInstance().get(PriceListVersion.class);
    version.setNewOBObject(true);
    version.setClient(priceList.getClient());
    version.setOrganization(priceList.getOrganization());
    version.setName(priceList.getName());
    version.setPriceList(priceList);
    version.setPriceListSchema(schema);
    version.setValidFromDate(java.sql.Date.valueOf(LocalDate.of(Year.now().getValue(), 1, 1)));
    OBDal.getInstance().save(version);
    // Flush so the criteria query in the subsequent annotateRecords step sees the new version.
    OBDal.getInstance().flush();

    log.debug("Auto-created price list version '{}' for price list '{}'",
        version.getName(), priceList.getName());
  }

  /**
   * Reuses an existing active {@link PriceListSchema} for the same client, falling back to
   * the standard "Esquema de Lista de Precios" if none exists yet (first-run bootstrap).
   * This avoids creating a new schema per price list, which clutters the AD window.
   */
  private PriceListSchema findOrCreateSchema(PriceList priceList) {
    OBCriteria<PriceListSchema> crit = OBDal.getInstance()
        .createCriteria(PriceListSchema.class);
    crit.add(Restrictions.eq(PriceListSchema.PROPERTY_CLIENT, priceList.getClient()));
    crit.add(Restrictions.eq(PriceListSchema.PROPERTY_ACTIVE, true));
    crit.setMaxResults(1);
    List<PriceListSchema> existing = crit.list();
    if (!existing.isEmpty()) {
      return existing.get(0);
    }
    PriceListSchema schema = OBProvider.getInstance().get(PriceListSchema.class);
    schema.setNewOBObject(true);
    schema.setClient(priceList.getClient());
    schema.setOrganization(priceList.getOrganization());
    schema.setName(truncate(DEFAULT_SCHEMA_NAME, MAX_SCHEMA_NAME_LENGTH));
    OBDal.getInstance().save(schema);
    return schema;
  }

  private static String truncate(String value, int maxLength) {
    return value.length() <= maxLength ? value : value.substring(0, maxLength - 3) + "...";
  }

  private void syncVersionNameForFirstRecord(JSONArray dataArr) throws Exception {
    String priceListId = dataArr.getJSONObject(0).optString("id", null);
    if (priceListId == null || priceListId.isEmpty()) {
      return;
    }
    PriceList priceList = OBDal.getInstance().get(PriceList.class, priceListId);
    if (priceList == null) {
      return;
    }
    PriceListVersion version = PriceListVersionResolver.findSingleVersion(priceList);
    if (version == null) {
      return;
    }
    if (!priceList.getName().equals(version.getName())) {
      version.setName(priceList.getName());
      OBDal.getInstance().save(version);
      log.debug("Synced price list version name to '{}'", priceList.getName());
    }
  }
}
