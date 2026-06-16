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

import java.text.SimpleDateFormat;
import java.util.Date;

import javax.inject.Named;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.base.model.Property;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.client.kernel.RequestContext;
import org.openbravo.dal.service.OBDal;

/**
 * NeoHandler for the {@code header} entity of the {@code amortization} spec.
 *
 * <p>Computes the {@code name} default on new-record forms using the asset name and its
 * depreciation start date when an {@code assetId} query parameter is present:
 * {@code "Amortización - {assetName} - {amortizationstartdate}"}.
 *
 * <p>Falls back to {@code "Amortización"} when the parameter is absent, the asset cannot
 * be found, or any lookup error occurs — never crashes, never blocks the defaults call.
 *
 * <p>Only fires on the {@link NeoEndpointType#DEFAULTS} endpoint. All other endpoints
 * pass through unchanged.
 *
 * <p>Registered via {@code JAVA_QUALIFIER = 'amortizationHeaderHandler'} on the
 * {@code header} entity of the {@code amortization} ETGO_SF_SPEC record.
 */
@Named("amortizationHeaderHandler")
public class AmortizationHeaderHandler implements NeoHandler {

  private static final Logger log = LogManager.getLogger(AmortizationHeaderHandler.class);

  private static final String PARAM_ASSET_ID = "assetId";
  private static final String FIELD_DEFAULTS = "defaults";
  private static final String PROPERTY_NAME = "name";
  private static final String ASSET_ENTITY_NAME = "FinancialMgmtAsset";
  private static final String COLUMN_NAME = "Name";
  private static final String COLUMN_START_DATE = "Amortizationstartdate";
  private static final String DATE_FORMAT = "yyyy-MM-dd";
  private static final String NAME_PREFIX = "Amortización - ";
  private static final String NAME_FALLBACK = "Amortización";

  @Override
  public NeoResponse handle(NeoContext context) {
    // Pre-hook: nothing to intercept — let the defaults service run first
    return null;
  }

  /**
   * Post-hook: after the defaults service resolves AD defaults, inject a computed {@code name}
   * when {@code assetId} is present. Does not overwrite a name already set by the defaults service.
   */
  @Override
  public NeoResponse afterHandle(NeoContext context) {
    if (!NeoEndpointType.DEFAULTS.equals(context.getEndpointType())) {
      return null;
    }
    NeoResponse previous = context.getPreviousResult();
    if (previous == null || previous.getBody() == null) {
      return null;
    }
    try {
      JSONObject body = previous.getBody();
      JSONObject defaults = body.optJSONObject(FIELD_DEFAULTS);
      if (defaults == null) {
        defaults = new JSONObject();
        body.put(FIELD_DEFAULTS, defaults);
      }
      // Do not overwrite a name already resolved by the defaults service
      if (defaults.has(PROPERTY_NAME) && !defaults.isNull(PROPERTY_NAME)) {
        return null;
      }
      String computedName = computeNameDefault();
      defaults.put(PROPERTY_NAME, computedName);
      return NeoResponse.ok(body);
    } catch (Exception e) {
      log.error("Failed to inject name default for amortization header: {}", e.getMessage(), e);
      return null;
    }
  }

  /**
   * Reads {@code assetId} from the current HTTP request via {@link RequestContext} and computes
   * the name default. Returns the fallback value when no asset id is present or the asset is not
   * found.
   */
  private static String computeNameDefault() {
    String assetId = readAssetIdFromRequest();
    if (assetId == null) {
      return NAME_FALLBACK;
    }
    try {
      BaseOBObject asset = OBDal.getInstance().get(ASSET_ENTITY_NAME, assetId);
      if (asset == null) {
        log.debug("Asset not found for id '{}', using fallback name", assetId);
        return NAME_FALLBACK;
      }
      Entity assetEntity = ModelProvider.getInstance().getEntityByTableName("A_Asset");
      Property nameProp = assetEntity.getPropertyByColumnName(COLUMN_NAME, false);
      Property dateProp = assetEntity.getPropertyByColumnName(COLUMN_START_DATE, false);

      String assetName = nameProp != null ? (String) asset.get(nameProp.getName()) : null;
      Object rawDate = dateProp != null ? asset.get(dateProp.getName()) : null;

      if (assetName == null || assetName.isEmpty()) {
        return NAME_FALLBACK;
      }
      StringBuilder sb = new StringBuilder(NAME_PREFIX).append(assetName);
      if (rawDate != null) {
        sb.append(" - ").append(formatDate(rawDate));
      }
      return sb.toString();
    } catch (Exception e) {
      log.debug("Could not compute amortization name for assetId '{}': {}", assetId, e.getMessage());
      return NAME_FALLBACK;
    }
  }

  private static String readAssetIdFromRequest() {
    try {
      if (RequestContext.get() == null || RequestContext.get().getRequest() == null) {
        return null;
      }
      String assetId = RequestContext.get().getRequest().getParameter(PARAM_ASSET_ID);
      return (assetId != null && !assetId.isEmpty()) ? assetId : null;
    } catch (Exception e) {
      log.debug("Could not read assetId from request context: {}", e.getMessage());
      return null;
    }
  }

  private static String formatDate(Object rawDate) {
    try {
      if (rawDate instanceof Date) {
        return new SimpleDateFormat(DATE_FORMAT).format((Date) rawDate);
      }
      return rawDate.toString();
    } catch (Exception e) {
      return rawDate.toString();
    }
  }
}
