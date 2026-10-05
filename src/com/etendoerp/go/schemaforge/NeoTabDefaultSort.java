/*
 * *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"), you may not use this file except in compliance
 * with the License.
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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.base.model.Property;
import org.openbravo.model.ad.ui.Tab;
import org.openbravo.service.json.JsonConstants;

/**
 * Default order for child-tab lists that arrive with no explicit sort (ETP-5611).
 *
 * <p>With no {@code _sortBy}/{@code _orderBy}, {@code DefaultJsonDataService} orders by {@code id}
 * — random UUIDs — so lines came back in an arbitrary order and visibly jumped after a save. The AD
 * tab already declares the intended order in {@code HQL_OrderBy_Clause} (e.g. {@code lineNo}), the
 * order Classic always showed, but NEO never read it. This derives {@code _sortBy} from that
 * clause.
 *
 * <p>Deliberately conservative: only a plain, comma-separated list of property paths is used
 * ({@code e.} alias prefix, a leading {@code -} or a trailing {@code asc}/{@code desc} allowed),
 * and every path must exist on the DAL entity, compared case-sensitively. Anything else — a
 * function, a foreign alias, a stale or Classic-only name — skips the whole clause, so the list
 * behaves exactly as before instead of failing with a 500.
 *
 * <p>Branches on AD structure (tab level, tab metadata), never on entity identity. Shared by the
 * REST list ({@link NeoCrudHandler}) and the MCP {@code neo_list} path so both channels agree.
 */
public final class NeoTabDefaultSort {

  private static final Logger log = LogManager.getLogger(NeoTabDefaultSort.class);

  private static final Pattern TERM = Pattern.compile(
      "^(-)?(?:e\\.)?([A-Za-z]\\w*(?:\\.[A-Za-z]\\w*)*)(?:\\s+(asc|desc))?$",
      Pattern.CASE_INSENSITIVE);

  private NeoTabDefaultSort() {
  }

  /**
   * Puts the tab's default order into {@code params} as {@code _sortBy} when the tab is a child
   * tab ({@code tabLevel > 0}), the request carries no {@code _sortBy}/{@code _orderBy} of its
   * own, and the tab's order-by clause is usable. An explicit sort (column header click, MCP
   * {@code orderBy}, a handler pre-hook default) always wins.
   */
  public static void applyIfAbsent(Map<String, String> params, Tab tab, String dalEntityName) {
    if (params.containsKey(JsonConstants.SORTBY_PARAMETER)
        || params.containsKey(JsonConstants.ORDERBY_PARAMETER) || !isChildTab(tab)) {
      return;
    }
    applyIfAbsent(params, tab, ModelProvider.getInstance().getEntity(dalEntityName, false));
  }

  /** Same as {@link #applyIfAbsent(Map, Tab, String)} with the entity already resolved. */
  static void applyIfAbsent(Map<String, String> params, Tab tab, Entity entity) {
    if (params.containsKey(JsonConstants.SORTBY_PARAMETER)
        || params.containsKey(JsonConstants.ORDERBY_PARAMETER) || !isChildTab(tab)) {
      return;
    }
    String sortBy = deriveSortBy(tab.getHqlorderbyclause(), entity);
    if (sortBy != null) {
      params.put(JsonConstants.SORTBY_PARAMETER, sortBy);
    }
  }

  private static boolean isChildTab(Tab tab) {
    return tab != null && tab.getTabLevel() != null && tab.getTabLevel() > 0;
  }

  /**
   * {@code _sortBy} syntax for an HQL order-by clause ({@code "e.a desc, b"} → {@code "-a,b"}), or
   * null when the clause is blank or any term is not a plain, existing property path.
   */
  static String deriveSortBy(String hqlOrderBy, Entity entity) {
    if (StringUtils.isBlank(hqlOrderBy) || entity == null) {
      return null;
    }
    List<String> terms = new ArrayList<>();
    for (String raw : hqlOrderBy.split(",")) {
      String term = toSortTerm(raw.trim(), entity);
      if (term == null) {
        log.debug("Tab order-by '{}' skipped for {}: unusable term '{}'", hqlOrderBy,
            entity.getName(), raw.trim());
        return null;
      }
      terms.add(term);
    }
    return String.join(",", terms);
  }

  private static String toSortTerm(String raw, Entity entity) {
    Matcher m = TERM.matcher(raw);
    if (!m.matches()) {
      return null;
    }
    boolean leadingMinus = m.group(1) != null;
    String direction = m.group(3);
    boolean desc = "desc".equalsIgnoreCase(direction);
    if (leadingMinus && direction != null) {
      return null;
    }
    String path = m.group(2);
    if (!isPropertyPath(entity, path)) {
      return null;
    }
    return leadingMinus || desc ? "-" + path : path;
  }

  private static boolean isPropertyPath(Entity entity, String path) {
    Entity current = entity;
    String[] segments = path.split("\\.");
    for (int i = 0; i < segments.length; i++) {
      if (current == null || !current.hasProperty(segments[i])) {
        return false;
      }
      Property property = current.getProperty(segments[i]);
      current = i < segments.length - 1 && property != null ? property.getTargetEntity() : null;
    }
    return true;
  }
}
