/*
 * *************************************************************************
 * The contents of this file are subject to the Etendo License (the
 * "License"), you may not use this file except in compliance with the
 * License. You may obtain a copy of the License at
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

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.businessUtility.Preferences;
import org.openbravo.model.ad.access.User;
import org.openbravo.model.ad.domain.Preference;
import org.openbravo.model.ad.system.Client;

import com.etendoerp.go.common.MalformedJsonException;

/**
 * Persists one personal list-column view per authenticated user, spec and entity.
 *
 * <p>The value is held in a user-scoped {@code ETGO_GridViews} AD_Preference attribute. The
 * attribute includes a collision-resistant hash of spec and entity; identity always comes from
 * {@link OBContext}, never from the request. The contract contains presentation state only.</p>
 */
class NeoGridViewsService {

  private static final Logger log = LogManager.getLogger(NeoGridViewsService.class);
  private static final String PREF_ATTRIBUTE_PREFIX = "ETGO_GridViews:";
  private static final int MAX_BODY_CHARACTERS = 65536;
  private static final int MAX_COLUMNS = 300;
  private static final int MAX_ID_LENGTH = 128;
  private static final double MIN_WIDTH = 0;
  private static final double MAX_WIDTH = 1200;
  private static final String AMBIGUOUS_SCOPE_MESSAGE = "Grid view preference scope is ambiguous";
  private static final String INVALID_WIDTH_MESSAGE = "Grid view contains an invalid column width";
  private static final String SURFACE_LIST = "list";
  private static final String KEY_VERSION = "version";
  private static final String KEY_SURFACE = "surface";
  private static final String KEY_COLUMNS = "columns";
  private static final String KEY_ID = "id";
  private static final String KEY_VISIBLE = "visible";
  private static final String KEY_ORDER = "order";
  private static final String KEY_WIDTH = "width";
  private static final Pattern SAFE_SPEC_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");
  private static final Pattern SAFE_ENTITY_NAME =
      Pattern.compile("[\\p{L}\\p{N}][\\p{L}\\p{N}._()\\-]{0,127}");

  private NeoGridViewsService() {
  }

  /** Returns the validated view, or {@code null} when no valid view is stored. */
  static JSONObject getView(String specName, String entityName) {
    validateScope(specName, entityName);
    List<Preference> preferences = findScopedPreferences(attributeFor(specName, entityName));
    if (preferences.isEmpty()) {
      return null;
    }
    if (preferences.size() != 1) {
      log.error("Found {} duplicate grid view preferences for spec {}, entity {}",
          preferences.size(), specName, entityName);
      throw new IllegalStateException(AMBIGUOUS_SCOPE_MESSAGE);
    }
    try {
      return validateAndCanonicalize(preferences.get(0).getSearchKey());
    } catch (IllegalArgumentException e) {
      log.warn("Ignoring invalid stored grid view for spec {}, entity {}: {}", specName,
          entityName, e.getMessage());
      return null;
    }
  }

  /** Validates the entire request before replacing only the selected scope. */
  static JSONObject saveView(String specName, String entityName, String body) {
    validateScope(specName, entityName);
    JSONObject canonical = validateAndCanonicalize(body);
    String attribute = attributeFor(specName, entityName);
    List<Preference> existing = findScopedPreferences(attribute);
    if (existing.size() > 1) {
      throw new IllegalStateException(AMBIGUOUS_SCOPE_MESSAGE);
    }
    OBContext context = currentUserContext();
    Preferences.setPreferenceValue(attribute, canonical.toString(), false,
        context.getCurrentClient(), null, context.getUser(), null, null, null);
    return canonical;
  }

  /** Removes only the current user's view for the supplied spec/entity. */
  static void deleteView(String specName, String entityName) {
    validateScope(specName, entityName);
    List<Preference> preferences = findScopedPreferences(attributeFor(specName, entityName));
    if (preferences.size() > 1) {
      throw new IllegalStateException(AMBIGUOUS_SCOPE_MESSAGE);
    }
    for (Preference preference : preferences) {
      OBDal.getInstance().remove(preference);
    }
  }

  private static JSONObject validateAndCanonicalize(String body) {
    if (body == null || body.length() > MAX_BODY_CHARACTERS) {
      throw new IllegalArgumentException("Grid view body is missing or too large");
    }
    final JSONObject input;
    try {
      input = NeoRequestBodyParser.parseJsonObjectSafely(body);
    } catch (MalformedJsonException e) {
      throw new IllegalArgumentException("Invalid grid view JSON body", e);
    }
    try {
      rejectUnknownKeys(input, KEY_VERSION, KEY_SURFACE, KEY_COLUMNS);
      requireSupportedVersionAndSurface(input);
      JSONArray columns = input.getJSONArray(KEY_COLUMNS);
      if (columns.length() == 0 || columns.length() > MAX_COLUMNS) {
        throw new IllegalArgumentException("Grid view must contain between 1 and 300 columns");
      }
      return buildCanonicalView(columns);
    } catch (JSONException e) {
      throw new IllegalArgumentException("Invalid grid view contract", e);
    }
  }

  private static void requireSupportedVersionAndSurface(JSONObject input) throws JSONException {
    Object version = input.get(KEY_VERSION);
    Object surface = input.get(KEY_SURFACE);
    if (!(version instanceof Number) || ((Number) version).doubleValue() != 1
        || !SURFACE_LIST.equals(surface)) {
      throw new IllegalArgumentException("Unsupported grid view version or surface");
    }
  }

  private static JSONObject buildCanonicalView(JSONArray columns) throws JSONException {
    List<JSONObject> canonicalColumns = new ArrayList<>();
    Set<String> ids = new HashSet<>();
    Set<Integer> orders = new HashSet<>();
    for (int index = 0; index < columns.length(); index++) {
      JSONObject normalized = canonicalColumn(columns.getJSONObject(index), columns.length(), ids);
      if (!orders.add(normalized.getInt(KEY_ORDER))) {
        throw new IllegalArgumentException("Grid view contains duplicate column order values");
      }
      canonicalColumns.add(normalized);
    }
    canonicalColumns.sort(
        (left, right) -> Integer.compare(left.optInt(KEY_ORDER), right.optInt(KEY_ORDER)));
    JSONArray sorted = new JSONArray();
    for (JSONObject column : canonicalColumns) {
      sorted.put(column);
    }
    JSONObject result = new JSONObject();
    result.put(KEY_VERSION, 1);
    result.put(KEY_SURFACE, SURFACE_LIST);
    result.put(KEY_COLUMNS, sorted);
    return result;
  }

  /** Validates one column entry and returns its canonical form; registers its id in {@code ids}. */
  private static JSONObject canonicalColumn(JSONObject column, int columnCount, Set<String> ids)
      throws JSONException {
    rejectUnknownKeys(column, KEY_ID, KEY_VISIBLE, KEY_ORDER, KEY_WIDTH);
    Object rawId = column.get(KEY_ID);
    if (!(rawId instanceof String)) {
      throw new IllegalArgumentException("Grid view contains an invalid column id");
    }
    String id = (String) rawId;
    if (id.length() == 0 || id.length() > MAX_ID_LENGTH || !ids.add(id)) {
      throw new IllegalArgumentException("Grid view contains an invalid or duplicate column id");
    }
    Object rawVisible = column.get(KEY_VISIBLE);
    Object rawOrder = column.get(KEY_ORDER);
    if (!(rawVisible instanceof Boolean) || !(rawOrder instanceof Number)) {
      throw new IllegalArgumentException("Grid view contains an invalid column value");
    }
    JSONObject normalized = new JSONObject();
    normalized.put(KEY_ID, id);
    normalized.put(KEY_VISIBLE, ((Boolean) rawVisible).booleanValue());
    normalized.put(KEY_ORDER, validOrder(((Number) rawOrder).doubleValue(), columnCount));
    if (column.has(KEY_WIDTH)) {
      normalized.put(KEY_WIDTH, validWidth(column.get(KEY_WIDTH)));
    }
    return normalized;
  }

  private static int validOrder(double order, int columnCount) {
    if (!Double.isFinite(order) || order < 0 || order >= columnCount || order != Math.rint(order)) {
      throw new IllegalArgumentException("Grid view contains an invalid column order");
    }
    return (int) order;
  }

  private static double validWidth(Object widthValue) {
    if (!(widthValue instanceof Number)) {
      throw new IllegalArgumentException(INVALID_WIDTH_MESSAGE);
    }
    double width = ((Number) widthValue).doubleValue();
    if (!Double.isFinite(width) || width < MIN_WIDTH || width > MAX_WIDTH
        || width != Math.rint(width)) {
      throw new IllegalArgumentException(INVALID_WIDTH_MESSAGE);
    }
    return width;
  }

  private static void rejectUnknownKeys(JSONObject object, String... allowed) throws JSONException {
    Set<String> allowedKeys = new HashSet<>();
    Collections.addAll(allowedKeys, allowed);
    Iterator<?> keys = object.keys();
    while (keys.hasNext()) {
      if (!allowedKeys.contains(keys.next())) {
        throw new IllegalArgumentException("Unsupported grid view property");
      }
    }
  }

  private static void validateScope(String specName, String entityName) {
    if (specName == null || entityName == null || !SAFE_SPEC_NAME.matcher(specName).matches()
        || !SAFE_ENTITY_NAME.matcher(entityName).matches()) {
      throw new IllegalArgumentException("Invalid grid view scope");
    }
  }

  private static List<Preference> findScopedPreferences(String attribute) {
    OBContext context = currentUserContext();
    Client client = context.getCurrentClient();
    User user = context.getUser();
    return Preferences.getPreferences(attribute, false, client.getId(), null, user.getId(), null, null);
  }

  /**
   * AD_PREFERENCE's check constraint makes PROPERTY and ATTRIBUTE mutually exclusive. Use the
   * attribute namespace with a full SHA-256 scope digest so each spec/entity has its own row and
   * concurrent saves to different scopes cannot overwrite one another.
   */
  private static String attributeFor(String specName, String entityName) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256")
          .digest((specName + "\u0000" + entityName).getBytes(StandardCharsets.UTF_8));
      return PREF_ATTRIBUTE_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is unavailable", e);
    }
  }

  private static OBContext currentUserContext() {
    OBContext context = OBContext.getOBContext();
    if (context == null || context.getCurrentClient() == null || context.getUser() == null) {
      throw new IllegalStateException("Authenticated user context is required for grid views");
    }
    return context;
  }
}
