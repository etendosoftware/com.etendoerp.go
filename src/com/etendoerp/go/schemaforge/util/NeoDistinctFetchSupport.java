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

package com.etendoerp.go.schemaforge.util;

import java.util.ArrayList;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.Property;
import org.openbravo.base.structure.BaseOBObject;

/**
 * Support helpers for the {@code _distinct} fetch endpoint served by
 * {@code NeoCrudHandler}: resolving a distinct field name against a DAL entity,
 * building the HQL search predicate, and serializing distinct values.
 *
 * <p>Extracted from {@code NeoCrudHandler} to keep that class within SonarQube's
 * method-count limit. These are pure, stateless helpers.</p>
 */
public final class NeoDistinctFetchSupport {

  private static final Logger log = LogManager.getLogger(NeoDistinctFetchSupport.class);

  private static final String JSON_IDENTIFIER = "_identifier";

  private NeoDistinctFetchSupport() {
  }

  /**
   * Resolves a distinct field name against the DAL entity, trying the raw name
   * first and falling back to case-insensitive matches against property names
   * and AD column names.
   *
   * @param entityDef
   *     the DAL entity whose properties are searched (may be {@code null})
   * @param fieldName
   *     the requested field name, matched against property and AD column names
   * @return the matching {@link Property}, or {@code null} when none matches or
   *     {@code entityDef} is {@code null}
   */
  public static Property resolveDistinctProperty(Entity entityDef, String fieldName) {
    if (entityDef == null) {
      return null;
    }
    Property direct = entityDef.getProperty(fieldName, false);
    if (direct != null) {
      return direct;
    }
    for (Property p : entityDef.getProperties()) {
      if (p.getName().equalsIgnoreCase(fieldName)) {
        return p;
      }
      if (p.getColumnName() != null && p.getColumnName().equalsIgnoreCase(fieldName)) {
        return p;
      }
    }
    return null;
  }

  /**
   * Builds the HQL LIKE predicate for {@code _distinctSearch} against the resolved
   * property.
   * <p>
   * Scalar (primitive) properties are searched by casting the column value itself —
   * {@code CAST(e.status AS string)}. Relation (foreign-key) properties cannot be
   * meaningfully cast to string: {@code CAST(e.productCategory AS string)} does not
   * resolve to the related record's display text, so it silently matches nothing
   * (or throws, depending on dialect). For those, the search is redirected to the
   * target entity's identifier properties instead — e.g.
   * {@code LOWER(CAST(e.productCategory.name AS string)) LIKE :search} — mirroring
   * how {@link BaseOBObject#getIdentifier()} resolves a record's display text.
   * <p>
   * Returns {@code null} (no predicate, search term ignored) when there is no
   * search term, or when a relation's target entity exposes no usable identifier
   * property, so the request still succeeds instead of failing with an HQL error.
   *
   * @param prop
   *     the resolved property being searched (scalar or relation)
   * @param resolvedProperty
   *     the property name to reference in the HQL {@code e.<name>} path
   * @param search
   *     the raw {@code _distinctSearch} term; blank means no predicate
   * @return the HQL LIKE predicate, or {@code null} when the search term is
   *     blank or no usable identifier property exists on a relation target
   */
  public static String buildDistinctSearchPredicate(Property prop, String resolvedProperty, String search) {
    if (StringUtils.isBlank(search)) {
      return null;
    }
    if (prop.isPrimitive()) {
      return "LOWER(CAST(e." + resolvedProperty + " AS string)) LIKE :search";
    }
    Entity targetEntity = prop.getTargetEntity();
    if (targetEntity == null) {
      return null;
    }
    List<Property> idProps = targetEntity.getIdentifierProperties();
    if (idProps == null || idProps.isEmpty()) {
      return null;
    }
    List<String> clauses = new ArrayList<>();
    for (Property idProp : idProps) {
      if (idProp.isPrimitive()) {
        clauses.add("LOWER(CAST(e." + resolvedProperty + "." + idProp.getName() + " AS string)) LIKE :search");
      }
    }
    if (clauses.isEmpty()) {
      return null;
    }
    return "(" + String.join(" OR ", clauses) + ")";
  }

  /**
   * Builds a {@code {"id": ..., "_identifier": ...}} entry for a single distinct
   * value. Scalar values (String enum codes, numbers, dates) use the stringified
   * value for both fields so the frontend can render a label without a second
   * lookup. FK references expose the target entity's id and its DAL identifier.
   *
   * @param value
   *     the distinct value to serialize: {@code null}, a {@link BaseOBObject}
   *     reference, or a scalar
   * @return a {@link JSONObject} with {@code id} and {@code _identifier} fields
   */
  public static JSONObject toDistinctEntry(Object value) {
    JSONObject entry = new JSONObject();
    try {
      if (value == null) {
        entry.put("id", "");
        entry.put(JSON_IDENTIFIER, "");
      } else if (value instanceof BaseOBObject) {
        BaseOBObject bob = (BaseOBObject) value;
        Object id = bob.getId();
        String idStr = id == null ? "" : id.toString();
        String identifier = safeIdentifier(bob, idStr);
        entry.put("id", idStr);
        entry.put(JSON_IDENTIFIER, StringUtils.isBlank(identifier) ? idStr : identifier);
      } else {
        String str = value.toString();
        entry.put("id", str);
        entry.put(JSON_IDENTIFIER, str);
      }
    } catch (Exception e) {
      log.error("Failed to serialize distinct entry: {}", e.getMessage(), e);
    }
    return entry;
  }

  /**
   * Resolves a record's DAL identifier, falling back to the given id string when
   * computing the identifier throws (e.g. a broken derived-identifier property).
   *
   * @param bob
   *     the record whose identifier is resolved
   * @param fallback
   *     the value returned when {@link BaseOBObject#getIdentifier()} throws
   * @return the record identifier, or {@code fallback} on error
   */
  private static String safeIdentifier(BaseOBObject bob, String fallback) {
    try {
      return bob.getIdentifier();
    } catch (Exception e) {
      return fallback;
    }
  }
}
