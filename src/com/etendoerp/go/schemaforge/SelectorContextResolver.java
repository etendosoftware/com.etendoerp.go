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

import java.util.HashMap;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.client.application.ApplicationUtils;
import org.openbravo.client.kernel.KernelUtils;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.ui.Tab;
import org.openbravo.model.ad.ui.Window;

import com.etendoerp.go.schemaforge.data.SFEntity;

/**
 * Resolves selector context parameters from request metadata and server-side AD context.
 */
final class SelectorContextResolver {

  private static final Logger log = LogManager.getLogger(SelectorContextResolver.class);
  private static final String AD_ORG_ID = "AD_Org_ID";
  private static final String PROP_ORGANIZATION = "organization";
  private static final String PARAM_INPUT_ORG_ID = "inpadOrgId";
  private static final String PARAM_IS_SO_TRX = "IsSOTrx";
  private static final String PARAM_IS_SO_TRX_LOWER = "isSOTrx";
  private static final String PARAM_IS_RECEIPT = "IsReceipt";
  private static final String PARAM_IS_RECEIPT_LOWER = "isReceipt";
  private static final String PARAM_FIN_IS_RECEIPT = "FIN_ISRECEIPT";
  private static final String PARAM_PRICE_LIST = "PriceList";
  private static final String PARAM_PRICE_LIST_LOWER = "priceList";

  private SelectorContextResolver() {
  }

  static Map<String, String> buildComboSelectorParams(SFEntity sourceEntity,
      Map<String, String> contextParams) {
    Map<String, String> selectorParams = new HashMap<>();
    if (contextParams != null) {
      selectorParams.putAll(contextParams);
    }

    String resolvedOrganizationId = resolveContextOrganizationId(sourceEntity, contextParams);
    copyIfAbsent(selectorParams, AD_ORG_ID, resolvedOrganizationId);
    copyIfAbsent(selectorParams, PARAM_INPUT_ORG_ID, resolvedOrganizationId);

    // Normalise casing variants so ComboTableData can find them by their canonical names.
    copyIfAbsent(selectorParams, PARAM_IS_SO_TRX, selectorParams.get(PARAM_IS_SO_TRX_LOWER));
    copyIfAbsent(selectorParams, PARAM_IS_SO_TRX_LOWER, selectorParams.get(PARAM_IS_SO_TRX));

    if (!selectorParams.containsKey(PARAM_IS_SO_TRX)
        || StringUtils.isBlank(selectorParams.get(PARAM_IS_SO_TRX))) {
      String windowIsSOTrx = resolveIsSOTrxFromWindow(sourceEntity);
      if (windowIsSOTrx != null) {
        selectorParams.put(PARAM_IS_SO_TRX, windowIsSOTrx);
        selectorParams.put(PARAM_IS_SO_TRX_LOWER, windowIsSOTrx);
      }
    }

    copyIfAbsent(selectorParams, PARAM_IS_RECEIPT, selectorParams.get(PARAM_IS_RECEIPT_LOWER));
    copyIfAbsent(selectorParams, PARAM_IS_RECEIPT_LOWER, selectorParams.get(PARAM_IS_RECEIPT));
    copyIfAbsent(selectorParams, PARAM_FIN_IS_RECEIPT, selectorParams.get(PARAM_FIN_IS_RECEIPT));
    copyIfAbsent(selectorParams, PARAM_FIN_IS_RECEIPT, selectorParams.get(PARAM_IS_RECEIPT_LOWER));
    copyIfAbsent(selectorParams, PARAM_PRICE_LIST_LOWER, selectorParams.get(PARAM_PRICE_LIST));
    copyIfAbsent(selectorParams, PARAM_PRICE_LIST, selectorParams.get(PARAM_PRICE_LIST_LOWER));
    return selectorParams;
  }

  static String resolveContextOrganizationId(SFEntity sourceEntity,
      Map<String, String> contextParams) {
    if (contextParams == null) {
      return null;
    }
    String organizationId = StringUtils.trimToNull(contextParams.get(AD_ORG_ID));
    if (organizationId == null) {
      organizationId = StringUtils.trimToNull(contextParams.get(PARAM_INPUT_ORG_ID));
    }
    if (organizationId == null) {
      organizationId = resolveOrgFromParentRecord(sourceEntity, contextParams.get("parentId"));
    }
    if ("0".equals(organizationId)) {
      return null;
    }
    return organizationId;
  }

  static void copyIfAbsent(Map<String, String> target, String key, String value) {
    if (StringUtils.isBlank(key) || StringUtils.isBlank(value) || target.containsKey(key)) {
      return;
    }
    target.put(key, value);
  }

  private static String resolveOrgFromParentRecord(SFEntity sourceEntity, String parentId) {
    if (sourceEntity == null || StringUtils.isBlank(parentId)) {
      return null;
    }
    try {
      Tab childTab = sourceEntity.getADTab();
      if (childTab == null) {
        return null;
      }
      if (childTab.getTabLevel() == null || childTab.getTabLevel() <= 0) {
        return resolveOrgFromSelfRecord(childTab, parentId);
      }
      return resolveOrgFromParentTab(childTab, parentId);
    } catch (Exception e) {
      log.debug("Could not resolve parent organization for selector context: {}", e.getMessage());
      return null;
    }
  }

  private static String resolveOrgFromSelfRecord(Tab childTab, String parentId) {
    if (childTab.getTable() == null) {
      return null;
    }
    Entity selfEntity = ModelProvider.getInstance().getEntityByTableId(childTab.getTable().getId());
    if (selfEntity == null || !selfEntity.hasProperty(PROP_ORGANIZATION)) {
      return null;
    }
    BaseOBObject selfRecord = OBDal.getInstance().get(selfEntity.getName(), parentId);
    return extractOrganizationId(selfRecord);
  }

  private static String resolveOrgFromParentTab(Tab childTab, String parentId) {
    Tab parentTab = KernelUtils.getInstance().getParentTab(childTab);
    if (parentTab == null || parentTab.getTable() == null) {
      return null;
    }
    String parentProperty = ApplicationUtils.getParentProperty(childTab, parentTab);
    if (StringUtils.isBlank(parentProperty)) {
      return null;
    }
    Entity parentEntity = ModelProvider.getInstance().getEntityByTableId(parentTab.getTable().getId());
    if (parentEntity == null || !parentEntity.hasProperty(PROP_ORGANIZATION)) {
      return null;
    }
    BaseOBObject parentRecord = OBDal.getInstance().get(parentEntity.getName(), parentId);
    return extractOrganizationId(parentRecord);
  }

  private static String extractOrganizationId(BaseOBObject recordObject) {
    if (recordObject == null) {
      return null;
    }
    Object organization = recordObject.get(PROP_ORGANIZATION);
    if (organization instanceof BaseOBObject) {
      Object organizationId = ((BaseOBObject) organization).getId();
      return organizationId != null ? organizationId.toString() : null;
    }
    return organization != null ? organization.toString() : null;
  }

  private static String resolveIsSOTrxFromWindow(SFEntity sourceEntity) {
    try {
      if (sourceEntity == null) {
        return null;
      }
      Tab tab = sourceEntity.getADTab();
      if (tab == null) {
        return null;
      }
      Window window = tab.getWindow();
      if (window == null) {
        return null;
      }
      Boolean isSalesTransaction = window.isSalesTransaction();
      if (isSalesTransaction == null) {
        return null;
      }
      return isSalesTransaction ? "Y" : "N";
    } catch (Exception e) {
      log.debug("Could not resolve IsSOTrx from window for entity {}: {}",
          sourceEntity != null ? sourceEntity.getName() : "null", e.getMessage());
      return null;
    }
  }
}
