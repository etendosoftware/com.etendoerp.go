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

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.base.model.Property;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.client.kernel.KernelUtils;
import org.openbravo.dal.core.DalUtil;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.ui.Tab;

import com.etendoerp.go.schemaforge.util.NeoTypeCoercionHelper;

/**
 * Resolves the HQL filter a child tab needs to constrain its records to a given parent record —
 * both the token substitution inside a tab's own {@code hqlwhereclause} ({@code @token@}
 * placeholders) and the separate link-to-parent filter expression.
 *
 * <p>Extracted out of {@link NeoCrudHandler}, which had grown to 38 methods (Sonar's
 * method-count-per-class limit is 35). This group of methods is a natural, self-contained seam:
 * every method here answers the same question — "what value should replace this token / what
 * filter constrains this child tab, given the parent record?" — and none of them touch
 * {@code NeoCrudHandler}'s own instance state ({@code servlet}, {@code telemetryService}). Two
 * independent call sites in {@code NeoCrudHandler} share this logic: the ordinary list GET's where
 * clause ({@code applyWhereClause}) and the {@code ?_distinct=} fetch's predicate list
 * ({@code buildDistinctPredicates}).
 *
 * <p>Moved verbatim — signatures, order of operations, exception handling and logging are
 * unchanged from their previous home in {@code NeoCrudHandler}.</p>
 */
final class NeoParentTabFilterResolver {

  private static final Logger log = LogManager.getLogger(NeoParentTabFilterResolver.class);

  private static final Pattern TAB_WHERE_TOKEN_PATTERN = Pattern.compile("@([A-Za-z_.]+)@");

  private NeoParentTabFilterResolver() {
  }

  /**
   * Replaces {@code @token@} placeholders in a tab HQL where clause using the actual column
   * values from the parent record rather than substituting all tokens with the same parentId.
   *
   * <p>Classic Etendo resolves each {@code @ColumnName@} against the corresponding column on the
   * parent record. NEO previously replaced every token with the same parentId, which broke cases
   * where a tab has two different tokens — e.g. {@code @AD_Org_ID@} (the parent's organization
   * UUID) and {@code @aeatsii_config_id@} (the parent record's primary key).</p>
   *
   * <p>Resolution order for each token:</p>
   * <ol>
   *   <li>{@code @AD_Org_ID@} / {@code @Org_ID@} → parent record's {@code organization.id}</li>
   *   <li>{@code @AD_Client_ID@} / {@code @Client_ID@} → parent record's {@code client.id}</li>
   *   <li>{@code @<tableName>_id@} → {@code parentId} (the parent PK)</li>
   *   <li>Any other token → matched against parent entity property column names</li>
   *   <li>Fallback → {@code parentId}</li>
   * </ol>
   */
  static String resolveTabWhereTokens(Tab adTab, String tabWhere, String parentId) {
    Tab parentTab = null;
    BaseOBObject parentRecord = null;
    Entity parentEntity = null;
    String parentTableName = null;

    try {
      parentTab = KernelUtils.getInstance().getParentTab(adTab);
      if (parentTab != null && parentTab.getTable() != null) {
        parentTableName = parentTab.getTable().getName().toLowerCase();
        parentEntity = ModelProvider.getInstance()
            .getEntityByTableId(parentTab.getTable().getId());
        if (parentEntity != null) {
          parentRecord = OBDal.getInstance().get(parentEntity.getName(), parentId);
        }
      }
    } catch (Exception e) {
      log.warn("Could not load parent record for tab '{}' parentId='{}': {}",
          adTab.getName(), parentId, e.getMessage());
    }

    final BaseOBObject finalParentRecord = parentRecord;
    final Entity finalParentEntity = parentEntity;
    final String finalParentTableName = parentTableName;

    Matcher matcher = TAB_WHERE_TOKEN_PATTERN.matcher(tabWhere);
    StringBuilder result = new StringBuilder();
    while (matcher.find()) {
      String token = matcher.group(1);
      String value = resolveTokenFromParent(
          token, parentId, finalParentRecord, finalParentEntity, finalParentTableName);
      matcher.appendReplacement(result, Matcher.quoteReplacement("'" + value.replace("'", "''") + "'"));
    }
    matcher.appendTail(result);
    return result.toString();
  }

  /**
   * Resolves a single {@code @token@} value from the parent record.
   */
  private static String resolveTokenFromParent(String token, String parentId,
      BaseOBObject parentRecord, Entity parentEntity, String parentTableName) {
    if (parentRecord == null) {
      return parentId;
    }
    String tokenLower = token.toLowerCase(Locale.ROOT);

    if ("ad_org_id".equals(tokenLower) || "org_id".equals(tokenLower)) {
      return resolveRelatedObjectId(parentRecord, "organization", parentId);
    }

    if ("ad_client_id".equals(tokenLower) || "client_id".equals(tokenLower)) {
      return resolveRelatedObjectId(parentRecord, "client", parentId);
    }

    if (parentTableName != null && tokenLower.equals(parentTableName + "_id")) {
      return parentId;
    }

    if (parentEntity != null) {
      String entityValue = resolveTokenFromEntityProperty(token, parentRecord, parentEntity);
      return entityValue != null ? entityValue : parentId;
    }

    return parentId;
  }

  private static String resolveRelatedObjectId(BaseOBObject parentRecord, String propertyName,
      String fallbackValue) {
    try {
      Object relatedObject = parentRecord.get(propertyName);
      if (relatedObject instanceof BaseOBObject) {
        return DalUtil.getId((BaseOBObject) relatedObject).toString();
      }
    } catch (Exception e) {
      log.debug("Could not resolve parent {} token", propertyName, e);
    }
    return fallbackValue;
  }

  private static String resolveTokenFromEntityProperty(String token, BaseOBObject parentRecord,
      Entity parentEntity) {
    try {
      for (Property prop : parentEntity.getProperties()) {
        if (prop.getColumnName() != null && prop.getColumnName().equalsIgnoreCase(token)) {
          return stringifyParentValue(parentRecord.get(prop.getName()));
        }
      }
    } catch (Exception e) {
      log.debug("Could not resolve parent token {}", token, e);
    }
    return null;
  }

  private static String stringifyParentValue(Object value) {
    if (value instanceof BaseOBObject) {
      return DalUtil.getId((BaseOBObject) value).toString();
    }
    return value != null ? value.toString() : "";
  }

  /**
   * Resolves the HQL filter expression that constrains child tab records by parent record ID.
   */
  static String resolveParentFilter(Tab childTab, String parentId) {
    try {
      NeoTypeCoercionHelper.ParentFilter parentFilter =
          NeoTypeCoercionHelper.buildParentWhereClause(childTab, parentId);
      return parentFilter != null ? parentFilter.resolveForStringApi() : null;
    } catch (Exception e) {
      log.error("Error resolving parent filter for tab '{}': {}", childTab.getName(), e.getMessage(), e);
      return null;
    }
  }
}
