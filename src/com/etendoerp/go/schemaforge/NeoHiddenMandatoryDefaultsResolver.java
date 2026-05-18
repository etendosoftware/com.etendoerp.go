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

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.Property;
import org.openbravo.model.ad.datamodel.Column;
import org.openbravo.model.ad.ui.Tab;

/**
 * Resolves defaults for mandatory AD columns that are not exposed as Schema Forge fields.
 */
final class NeoHiddenMandatoryDefaultsResolver {

  private static final Logger log = LogManager.getLogger(NeoHiddenMandatoryDefaultsResolver.class);

  private NeoHiddenMandatoryDefaultsResolver() {
  }

  static void resolve(Request request) {
    if (request.adTab == null || request.adTab.getTable() == null) {
      return;
    }
    for (Column column : request.adTab.getTable().getADColumnList()) {
      resolveColumn(request, column);
    }
  }

  private static void resolveColumn(Request request, Column column) {
    String dbColumnName = resolveColumnName(column);
    if (dbColumnName == null) {
      return;
    }
    if (!shouldResolveColumn(request, column, dbColumnName)) {
      return;
    }

    Property property = request.dalEntity != null
        ? request.dalEntity.getPropertyByColumnName(dbColumnName)
        : null;
    if (property != null && property.isAuditInfo()) {
      return;
    }

    try {
      resolveProperty(request, column, dbColumnName, property);
    } catch (Exception e) {
      log.warn("Could not resolve hidden mandatory default for {}", dbColumnName, e);
    }
  }

  private static boolean shouldResolveColumn(Request request, Column column, String dbColumnName) {
    return column.isActive()
        && column.isMandatory()
        && !request.sfFieldColumns.contains(dbColumnName.toUpperCase(Locale.ROOT))
        && !Boolean.TRUE.equals(column.isKeyColumn());
  }

  private static String resolveColumnName(Column column) {
    String dbColumnName = column.getDBColumnName();
    if (dbColumnName == null || dbColumnName.trim().isEmpty()) {
      dbColumnName = resolveFallbackColumnName(column);
    }
    return dbColumnName == null || dbColumnName.trim().isEmpty() ? null : dbColumnName;
  }

  private static String resolveFallbackColumnName(Column column) {
    try {
      Method getColumnName = column.getClass().getMethod("getColumnName");
      Object value = getColumnName.invoke(column);
      return value instanceof String ? (String) value : null;
    } catch (ReflectiveOperationException e) {
      log.debug("No getColumnName fallback available for AD column {}", column.getId(), e);
      return null;
    }
  }

  private static void resolveProperty(Request request, Column column, String dbColumnName,
      Property property) throws Exception {
    String propertyName = property != null ? property.getName() : dbColumnName;
    if (request.defaults.has(propertyName)) {
      return;
    }

    Object resolved = request.resolveDefault(column);
    if (resolved == null) {
      return;
    }

    request.defaults.put(propertyName, resolved);
    if (request.dalEntity != null && request.identifierInjector != null) {
      request.identifierInjector.inject(request.defaults, request.dalEntity, propertyName,
          resolved);
    }
    log.debug("Resolved hidden mandatory default: {} = {}", propertyName, resolved);
  }

  static final class Request {
    private final JSONObject defaults;
    private final Entity dalEntity;
    private final Tab adTab;
    private Map<String, Object> parentValues = Collections.emptyMap();
    private DefaultResolver defaultResolver;
    private IdentifierInjector identifierInjector;
    private Set<String> sfFieldColumns = Collections.emptySet();

    Request(JSONObject defaults, Entity dalEntity, Tab adTab) {
      this.defaults = defaults;
      this.dalEntity = dalEntity;
      this.adTab = adTab;
    }

    Request withParentValues(Map<String, Object> parentValues) {
      this.parentValues = parentValues != null ? parentValues : Collections.emptyMap();
      return this;
    }

    Request withDefaultResolver(DefaultResolver defaultResolver) {
      this.defaultResolver = defaultResolver;
      return this;
    }

    Request withIdentifierInjector(IdentifierInjector identifierInjector) {
      this.identifierInjector = identifierInjector;
      return this;
    }

    Request withSfFieldColumns(Set<String> sfFieldColumns) {
      this.sfFieldColumns = sfFieldColumns != null ? sfFieldColumns : Collections.emptySet();
      return this;
    }

    private Object resolveDefault(Column column) {
      return defaultResolver != null ? defaultResolver.resolve(column, parentValues) : null;
    }
  }

  interface DefaultResolver {
    /**
     * Resolves the default value for the given AD column using the parent values loaded for
     * the current child-tab request.
     *
     * @param column       AD column being resolved
     * @param parentValues parent DB-column values keyed by uppercase column name
     * @return resolved default value, or null when no default can be resolved
     */
    Object resolve(Column column, Map<String, Object> parentValues);
  }

  interface IdentifierInjector {
    /**
     * Adds the display identifier for a resolved FK default when the target record can be found.
     *
     * @param defaults      defaults JSON object being populated
     * @param dalEntity     DAL entity that owns the property
     * @param propertyName  DAL property name for the resolved default
     * @param resolvedValue resolved FK value
     */
    void inject(JSONObject defaults, Entity dalEntity, String propertyName, Object resolvedValue);
  }
}
