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
import java.text.SimpleDateFormat;
import java.util.List;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.criterion.Order;
import org.hibernate.criterion.Restrictions;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.ui.Process;
import org.openbravo.model.ad.ui.Tab;
import org.openbravo.model.common.currency.Currency;
import org.openbravo.model.financialmgmt.assetmgmt.Amortization;
import org.openbravo.model.financialmgmt.assetmgmt.AmortizationLine;
import org.openbravo.model.financialmgmt.assetmgmt.Asset;

import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.data.SFSpec;

/**
 * Service that generates an asset amortization plan by firing the native
 * {@code A_Asset_Post} DB-procedure process.
 *
 * <p>The single public entry point is {@link #generatePlan(String)}, which:
 * <ol>
 *   <li>Validates the asset ID and loads the {@link Asset} record.</li>
 *   <li>Checks that the asset is configured for depreciation and has not already
 *       been processed.</li>
 *   <li>For time-based amortization ({@code calculateType = "TI"}), verifies that
 *       {@code usableLifeMonths} is positive.</li>
 *   <li>Resolves the {@code A_Asset_Post} AD_Process at runtime by its search key
 *       and the Assets AD_Tab from the {@code assets} ETGO_SF_Entity record.</li>
 *   <li>Fires {@code A_Asset_Post} via {@link NeoProcessService}.</li>
 *   <li>Reads back the generated {@link Amortization} header and its lines, then
 *       returns a summary JSON response.</li>
 * </ol>
 *
 * <p><b>Output fields:</b> {@code success}, {@code amortizationId}, {@code calculateType},
 * {@code totalAmortization}, {@code currency} (ISO code, e.g. {@code "EUR"}),
 * {@code periodsGenerated}, {@code periodAmount}, {@code startDate}, {@code endDate}.
 * Accounting entries are posted later, manually by the finance team — this endpoint
 * does NOT create them and does NOT report them.
 *
 * <p><b>Period amount:</b> {@code periodAmount} is the amount of the first amortization
 * line. Due to rounding, the last period may differ slightly.
 */
public class AmortizationPlanService {

  private static final Logger log = LogManager.getLogger(AmortizationPlanService.class);

  /**
   * AD_Process search key for the asset-post procedure that generates the amortization plan.
   * Resolved at runtime via {@link Process#PROPERTY_SEARCHKEY} — never hardcoded as an ID.
   */
  private static final String ASSET_POST_PROCESS_SEARCH_KEY = "A_Asset_Post";

  /**
   * SF spec name used to resolve the {@code assets} entity at runtime.
   * Looked up by natural key (name + active) — never by hardcoded primary key.
   */
  private static final String ASSETS_SPEC_NAME = "assets";

  /**
   * Entity name within the assets spec used to resolve the AD_Tab at runtime.
   */
  private static final String ASSETS_ENTITY_NAME = "assets";

  private static final String DATE_FORMAT = "yyyy-MM-dd";

  private AmortizationPlanService() {
  }

  /**
   * Generate an amortization plan for the given asset.
   *
   * @param assetId
   *     the {@code A_Asset_ID} (primary key) of the asset to process
   * @return a {@link NeoResponse} with HTTP 200 and a JSON summary on success,
   *     or an appropriate 4xx/5xx error response on failure
   */
  public static NeoResponse generatePlan(String assetId) {
    try {
      // Steps 1-5 — validate asset ID, existence, and depreciation configuration
      NeoResponse validationError = validateAssetInput(assetId);
      if (validationError != null) {
        return validationError;
      }
      Asset asset = OBDal.getInstance().get(Asset.class, assetId);

      // Step 6 — resolve A_Asset_Post process at runtime by search key (no hardcoded ID)
      Process process = resolveAssetPostProcess();
      if (process == null) {
        return NeoResponse.error(500,
            "A_Asset_Post process not found in AD (searchKey=" + ASSET_POST_PROCESS_SEARCH_KEY + ")");
      }

      // Step 7 — resolve the Assets AD_Tab at runtime from the ETGO_SF_Entity record (no hardcoded ID)
      String tabId = resolveAssetsTabId();
      if (tabId == null) {
        return NeoResponse.error(500,
            "Assets AD_Tab could not be resolved from ETGO_SF_Entity " + "(spec=" + ASSETS_SPEC_NAME + ", entity=" + ASSETS_ENTITY_NAME + ")");
      }

      // Step 8 — fire the process
      NeoResponse processResult = NeoProcessService.executeProcess(process, null, assetId, tabId);
      if (processResult.getHttpStatus() >= 400) {
        return processResult;
      }

      // Step 9 — refresh the asset to flush the Hibernate first-level cache.
      // A_Asset_Post0 is a PL/pgSQL procedure that commits internally; the session
      // may hold a stale view of the asset (e.g. empty amortization-line list) from
      // before the proc ran. refresh() forces a re-read from the DB after the commit.
      // NOTE: full correctness of this path requires integration verification on a
      // running server — it cannot be proven by a local build alone.
      OBDal.getInstance().getSession().refresh(asset);

      // Step 10 — read back amortization lines via an explicit ordered OBCriteria query.
      // We avoid walking the lazy Hibernate collection on asset, which may be stale after
      // A_Asset_Post0's internal commit. An OBCriteria query always reads from the DB directly.
      List<AmortizationLine> lines = queryAmortizationLines(asset);

      if (lines == null || lines.isEmpty()) {
        return NeoResponse.error(500,
            "Amortization plan was not generated: no amortization lines found after process execution");
      }

      // Step 11 — resolve the amortization header and build the result JSON.
      Amortization header = resolveAmortizationHeader(lines, assetId);
      List<AmortizationLine> headerLines = lines.stream().filter(
          l -> header.getId().equals(l.getAmortization().getId())).collect(Collectors.toList());

      return buildPlanResult(asset, header, headerLines);

    } catch (Exception e) {
      log.error("Unexpected error generating amortization plan for asset '{}': {}", assetId, e.getMessage(), e);
      return NeoResponse.error(500, "Unexpected error generating amortization plan: " + e.getMessage());
    }
  }

  /**
   * Validates the asset ID and the asset's depreciation configuration (steps 1–5 of
   * {@link #generatePlan}).
   *
   * @param assetId
   *     the raw asset ID from the request
   * @return a {@link NeoResponse} error if any guard clause fails, or {@code null} if all pass
   */
  private static NeoResponse validateAssetInput(String assetId) {
    if (StringUtils.isBlank(assetId)) {
      return NeoResponse.error(400, "assetId is required");
    }
    Asset asset = OBDal.getInstance().get(Asset.class, assetId);
    if (asset == null) {
      return NeoResponse.error(404, "Asset not found: " + assetId);
    }
    if (!Boolean.TRUE.equals(asset.isDepreciate())) {
      return NeoResponse.error(400, "Asset is not configured for depreciation");
    }
    if ("Y".equals(asset.getProcessed())) {
      return NeoResponse.error(409, "Asset already has a generated amortization plan");
    }
    // Only TI usable life is pre-validated here; PE percentage is checked by A_Asset_Post.
    if ("TI".equals(asset.getCalculateType())) {
      Long usableLifeMonths = asset.getUsableLifeMonths();
      if (usableLifeMonths == null || usableLifeMonths <= 0) {
        return NeoResponse.error(400,
            "Asset has no valid usable life configured " + "(usableLifeMonths must be > 0 for time-based amortization)");
      }
    }
    return null;
  }

  /**
   * Queries {@link AmortizationLine} records for the given asset, ordered by line number.
   * Uses an explicit OBCriteria query to bypass any stale Hibernate first-level cache.
   *
   * @param asset
   *     the asset whose lines are fetched
   * @return ordered list of amortization lines (may be empty but never null)
   */
  private static List<AmortizationLine> queryAmortizationLines(Asset asset) {
    OBCriteria<AmortizationLine> criteria = OBDal.getInstance().createCriteria(AmortizationLine.class);
    criteria.add(Restrictions.eq(AmortizationLine.PROPERTY_ASSET, asset));
    criteria.addOrder(Order.asc(AmortizationLine.PROPERTY_LINENO));
    return criteria.list();
  }

  /**
   * Resolves the single {@link Amortization} header from a list of lines.
   * When multiple distinct headers are found (defensive path), picks the most-recently-created
   * and logs a warning.
   *
   * @param lines
   *     non-empty list of amortization lines
   * @param assetId
   *     used only for the warning log message
   * @return the resolved amortization header
   */
  private static Amortization resolveAmortizationHeader(List<AmortizationLine> lines, String assetId) {
    // Because the endpoint rejects already-processed assets with 409 BEFORE firing the process,
    // a successful run means the asset had no prior plan and now has exactly ONE amortization header.
    // All queried lines therefore belong to the single new plan — the header is unambiguous.
    // Defensively: if more than one distinct header is present, pick the most-recently-created one
    // and log a warning so the anomaly is visible in logs.
    List<Amortization> distinctHeaders = lines.stream().map(AmortizationLine::getAmortization).distinct().collect(
        Collectors.toList());
    if (distinctHeaders.size() > 1) {
      log.warn(
          "Multiple amortization headers found for asset '{}' after plan generation; " + "picking the most-recently-created. Count: {}",
          assetId, distinctHeaders.size());
      distinctHeaders.sort((a, b) -> {
        java.util.Date da = a.getCreationDate();
        java.util.Date db = b.getCreationDate();
        if (da == null && db == null) return 0;
        if (da == null) return 1;
        if (db == null) return -1;
        return db.compareTo(da); // descending: newest first
      });
    }
    return distinctHeaders.get(0);
  }

  /**
   * Builds the success {@link NeoResponse} for an amortization plan (step 11 of
   * {@link #generatePlan}).
   *
   * @param asset
   *     the asset that was processed
   * @param header
   *     the amortization header record
   * @param headerLines
   *     lines belonging to {@code header}, ordered by line number
   * @return a 200 OK response containing the plan summary JSON
   */
  private static NeoResponse buildPlanResult(Asset asset, Amortization header,
      List<AmortizationLine> headerLines) throws JSONException {
    int periodsGenerated = headerLines.size();

    BigDecimal totalAmortization = BigDecimal.ZERO;
    for (AmortizationLine line : headerLines) {
      BigDecimal amt = line.getAmortizationAmount();
      if (amt != null) {
        totalAmortization = totalAmortization.add(amt);
      }
    }

    // periodAmount is the first ordered line's amount; the last period may differ slightly due to rounding.
    AmortizationLine firstLine = headerLines.get(0);
    BigDecimal periodAmount = firstLine.getAmortizationAmount() != null ? firstLine.getAmortizationAmount() : BigDecimal.ZERO;

    SimpleDateFormat sdf = new SimpleDateFormat(DATE_FORMAT);
    String startDate = header.getStartingDate() != null ? sdf.format(header.getStartingDate()) : null;
    String endDate = header.getEndingDate() != null ? sdf.format(header.getEndingDate()) : null;

    // accountingEntriesCreated is intentionally omitted: accounting entries are posted
    // later, manually by the finance team, and are not part of plan generation.
    // Resolve currency ISO code from the asset (guaranteed non-null by the native procedure).
    Currency assetCurrency = asset.getCurrency();
    String currencyIsoCode = assetCurrency != null ? assetCurrency.getISOCode() : null;

    JSONObject result = new JSONObject();
    result.put("success", true);
    result.put("amortizationId", header.getId());
    result.put("calculateType", asset.getCalculateType());
    result.put("totalAmortization", totalAmortization);
    if (currencyIsoCode != null) {
      result.put("currency", currencyIsoCode);
    }
    result.put("periodsGenerated", periodsGenerated);
    result.put("periodAmount", periodAmount);
    result.put("startDate", startDate);
    result.put("endDate", endDate);

    return NeoResponse.ok(result);
  }

  /**
   * Resolves the {@code A_Asset_Post} AD_Process at runtime by its search key.
   * Returns {@code null} if the process does not exist in the current installation.
   */
  private static Process resolveAssetPostProcess() {
    OBCriteria<Process> criteria = OBDal.getInstance().createCriteria(Process.class);
    criteria.add(Restrictions.eq(Process.PROPERTY_SEARCHKEY, ASSET_POST_PROCESS_SEARCH_KEY));
    criteria.setMaxResults(1);
    return (Process) criteria.uniqueResult();
  }

  /**
   * Resolves the AD_Tab ID linked to the {@code assets} ETGO_SF_Entity record at runtime.
   * Uses a two-step natural-key lookup: first finds the active {@link SFSpec} by name,
   * then finds the active included {@link SFEntity} within that spec by entity name.
   * No primary key is hardcoded anywhere in this path.
   * Returns {@code null} if the spec, entity, or its linked tab cannot be found.
   */
  private static String resolveAssetsTabId() {
    // Step A — resolve the active SFSpec by name (natural key, no hardcoded ID)
    OBCriteria<SFSpec> specCriteria = OBDal.getInstance().createCriteria(SFSpec.class);
    specCriteria.add(Restrictions.eq(SFSpec.PROPERTY_NAME, ASSETS_SPEC_NAME));
    specCriteria.add(Restrictions.eq(SFSpec.PROPERTY_ISACTIVE, true));
    specCriteria.setMaxResults(1);
    SFSpec sfSpec = (SFSpec) specCriteria.uniqueResult();
    if (sfSpec == null) {
      log.warn("Assets SFSpec not found (name={})", ASSETS_SPEC_NAME);
      return null;
    }

    // Step B — resolve the active included SFEntity by spec + entity name
    OBCriteria<SFEntity> entityCriteria = OBDal.getInstance().createCriteria(SFEntity.class);
    entityCriteria.add(Restrictions.eq(SFEntity.PROPERTY_ETGOSFSPEC + ".id", sfSpec.getId()));
    entityCriteria.add(Restrictions.eq(SFEntity.PROPERTY_NAME, ASSETS_ENTITY_NAME));
    entityCriteria.add(Restrictions.eq(SFEntity.PROPERTY_ISACTIVE, true));
    entityCriteria.add(Restrictions.eq(SFEntity.PROPERTY_ISINCLUDED, true));
    entityCriteria.setMaxResults(1);
    SFEntity sfEntity = (SFEntity) entityCriteria.uniqueResult();
    if (sfEntity == null) {
      log.warn("Assets SFEntity not found (spec={}, entity={})", ASSETS_SPEC_NAME, ASSETS_ENTITY_NAME);
      return null;
    }

    Tab adTab = sfEntity.getADTab();
    if (adTab == null) {
      log.warn("Assets SFEntity has no linked AD_Tab (spec={}, entity={})", ASSETS_SPEC_NAME, ASSETS_ENTITY_NAME);
      return null;
    }
    return adTab.getId();
  }
}
