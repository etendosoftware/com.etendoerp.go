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
 * All portions are Copyright © 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.mcp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hibernate.criterion.Order;
import org.hibernate.criterion.Restrictions;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;

import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.data.SFSpec;

/**
 * The DAL read behind the required-context hint of the {@code etendo_defaults} catalog description
 * (ETP-5852), the same shape as {@link McpNamedFilterCatalog} for {@code etendo_list}: read on every
 * {@code tools/list}, one query, cached nowhere.
 */
final class McpDefaultsContextCatalog {

  private static final Logger LOG = LogManager.getLogger(McpDefaultsContextCatalog.class);

  private McpDefaultsContextCatalog() {
  }

  /**
   * One line per exposed entity whose {@code MCP_CONFIG} declares a {@code defaults} section, in
   * spec order. An entity with an unusable configuration is skipped (and logged by
   * {@link McpEntityConfig}); a failure to read costs the hint, never the catalog.
   *
   * @param specNames the window specs {@code etendo_defaults} advertises
   * @return the lines joined by newlines, or {@code null} when none applies
   */
  static String summary(List<String> specNames) {
    try {
      McpConfigSections.ensureRegistered();
      OBCriteria<SFEntity> criteria = OBDal.getInstance().createCriteria(SFEntity.class);
      criteria.add(Restrictions.eq(SFEntity.PROPERTY_ISACTIVE, true));
      criteria.add(Restrictions.eq(SFEntity.PROPERTY_ISINCLUDED, true));
      criteria.add(Restrictions.like(SFEntity.PROPERTY_MCPCONFIG, "%\"" + McpDefaultsSection.NAME + "\"%"));
      criteria.addOrder(Order.asc(SFEntity.PROPERTY_SEQNO));
      Map<String, List<String>> linesBySpec = new LinkedHashMap<>();
      for (String specName : specNames) {
        linesBySpec.put(specName, new ArrayList<>());
      }
      for (SFEntity entity : criteria.list()) {
        SFSpec spec = entity.getETGOSFSpec();
        List<String> lines = spec == null ? null : linesBySpec.get(spec.getName());
        if (lines == null) {
          continue;
        }
        McpEntityConfig.Resolved config = McpEntityConfig.forEntity(entity);
        String line = config.section(McpDefaultsSection.NAME)
            .map(body -> McpDefaultsSection.catalogLine(spec.getName(), entity.getName(), body))
            .orElse(null);
        if (line != null) {
          lines.add(line);
        }
      }
      List<String> ordered = new ArrayList<>();
      linesBySpec.values().forEach(ordered::addAll);
      return ordered.isEmpty() ? null : String.join("\n", ordered);
    } catch (RuntimeException e) {
      LOG.warn("Could not list the defaults context for etendo_defaults: {}", e.getMessage());
      return null;
    }
  }
}
