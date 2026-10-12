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
 * The DAL read behind the named-filter hint of the {@code etendo_list} catalog description
 * (IMP-50), split out of {@link ToolRegistry} (java:S1448). Parsing and formatting stay in the
 * DAL-free {@link McpNamedFilters}; this class only queries the configured entities.
 */
final class McpNamedFilterCatalog {

  private static final Logger LOG = LogManager.getLogger(McpNamedFilterCatalog.class);

  private McpNamedFilterCatalog() {
  }

  /**
   * The named filters configured on the entities of the specs the catalog exposes, one line per
   * entity, capped at {@link McpNamedFilters#CATALOG_CAP} (IMP-50). Read from the data on every
   * {@code tools/list}, in one query, and cached nowhere: a {@code NAMED_FILTERS} change shows up
   * on the next catalog request with nothing to invalidate.
   *
   * <p>Visibility follows the spec enum: only entities of {@code specNames} — the specs this role
   * reaches — are listed. A failure to read them costs the hint, never the catalog.</p>
   *
   * @param specNames the window specs {@code etendo_list} advertises, in catalog order
   * @return the summary, or {@code null} when no exposed entity declares a named filter
   */
  static String summary(List<String> specNames) {
    try {
      OBCriteria<SFEntity> criteria = OBDal.getInstance().createCriteria(SFEntity.class);
      criteria.add(Restrictions.eq(SFEntity.PROPERTY_ISACTIVE, true));
      criteria.add(Restrictions.eq(SFEntity.PROPERTY_ISINCLUDED, true));
      criteria.add(Restrictions.isNotNull(SFEntity.PROPERTY_NAMEDFILTERS));
      criteria.addOrder(Order.asc(SFEntity.PROPERTY_SEQNO));
      Map<String, List<String>> linesBySpec = new LinkedHashMap<>();
      for (String specName : specNames) {
        linesBySpec.put(specName, new ArrayList<>());
      }
      for (SFEntity entity : criteria.list()) {
        SFSpec spec = entity.getETGOSFSpec();
        List<String> lines = spec == null ? null : linesBySpec.get(spec.getName());
        String line = lines == null ? null
            : McpNamedFilters.catalogLine(spec.getName(), entity.getName(),
                entity.getNamedFilters());
        if (line != null) {
          lines.add(line);
        }
      }
      List<String> ordered = new ArrayList<>();
      linesBySpec.values().forEach(ordered::addAll);
      return McpNamedFilters.catalogSummary(ordered, McpNamedFilters.CATALOG_CAP);
    } catch (RuntimeException e) {
      LOG.warn("Could not list the configured named filters for etendo_list: {}", e.getMessage());
      return null;
    }
  }
}
