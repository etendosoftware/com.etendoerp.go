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

package com.etendoerp.go.schemaforge.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.hibernate.criterion.Order;
import org.hibernate.criterion.Restrictions;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;

import com.etendoerp.go.schemaforge.NeoHandler;
import com.etendoerp.go.schemaforge.NeoResponse;
import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.data.SFSpec;

/**
 * One named action a {@link NeoHandler} serves through {@code etendo_action} / the ACTION
 * sub-endpoint, with the parameters it accepts (ETP-5468).
 *
 * <p><b>Why the handler declares it.</b> Same argument as {@link NeoReportParam} (IMP-19) and
 * {@code NeoHandler#servesActions} (ETP-4254): a handler-served action has no AD button column and
 * no {@code ETGO_SF_FIELD} rows, so nothing in the configuration can describe it. Without a
 * declaration an agent can neither discover the action nor learn its parameters except by reading
 * refusals one at a time — which is exactly how agents ended up pressing Core's hidden APRM buttons
 * on {@code financial-account/account} instead of the real reconciliation routes.</p>
 *
 * <p><b>One declaration, three readers.</b> {@code etendo_schema} renders it ({@link #toJson()}),
 * {@code etendo_discover} lists its names, and {@link #validate} judges the call against it before the
 * handler runs — so what an agent is shown and what it is judged against cannot drift.</p>
 */
public final class NeoActionContract {

  private static final Logger log = LogManager.getLogger(NeoActionContract.class);

  /** JSON Schema type for a string parameter. */
  public static final String TYPE_STRING = "string";
  /** JSON Schema type for a true/false parameter. */
  public static final String TYPE_BOOLEAN = "boolean";
  /**
   * A number or numeric string. Accepts a JSON number or a numeric string, because the SPA sends
   * amounts as strings ({@code "121"}) and an agent naturally sends {@code 121}; both reach the
   * handler, which parses with {@code BigDecimal} (ETP-5558).
   */
  public static final String TYPE_NUMBER = "number";
  /** A {@code yyyy-MM-dd} date carried as a string (see {@link NeoReportParam#TYPE_DATE}). */
  public static final String TYPE_DATE = "date";
  /** JSON Schema type for a list parameter; the item type is declared separately. */
  public static final String TYPE_ARRAY = "array";
  /** JSON Schema type for a nested object item. */
  public static final String TYPE_OBJECT = "object";

  /** HTTP 422: the call does not match the declared contract. */
  public static final int SC_UNPROCESSABLE = 422;

  private static final Pattern DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
  private static final String KEY_TYPE = "type";
  private static final String KEY_DESCRIPTION = "description";
  private static final String KEY_MESSAGE = "message";
  private static final String KEY_STATUS = "status";
  private static final String KEY_ERROR = "error";

  private final String name;
  private final String description;
  private final boolean mutating;
  private final List<Param> params;
  private final String idDescription;
  private final String httpMethod;
  private final boolean fieldValuesBody;

  /** The method an action is called with unless it declares another — what the MCP always used. */
  public static final String DEFAULT_HTTP_METHOD = "POST";

  private NeoActionContract(String name, String description, boolean mutating,
      List<Param> params) {
    this(name, description, mutating, params, null, DEFAULT_HTTP_METHOD, false);
  }

  @SuppressWarnings("java:S107") // one value object: the seven fields are the contract itself
  private NeoActionContract(String name, String description, boolean mutating,
      List<Param> params, String idDescription, String httpMethod, boolean fieldValuesBody) {
    this.name = name;
    this.description = description;
    this.mutating = mutating;
    this.params = List.copyOf(params);
    this.idDescription = idDescription;
    this.httpMethod = httpMethod;
    this.fieldValuesBody = fieldValuesBody;
  }

  /**
   * The same contract, served under another HTTP method (ETP-5558).
   *
   * <p>For a handler action that only answers one method: {@code currencyOptions} refuses anything
   * but {@code GET}, while the MCP builds every action call as a {@code POST}. Declaring it here
   * keeps that knowledge in the customization that owns it; the MCP reads it and calls the action
   * the way the handler expects.</p>
   *
   * @param method {@code GET} or {@code POST}
   * @return a copy carrying the method
   */
  public NeoActionContract withHttpMethod(String method) {
    return new NeoActionContract(name, description, mutating, params, idDescription, method,
        fieldValuesBody);
  }

  /** @return the HTTP method the action is called with; {@link #DEFAULT_HTTP_METHOD} unless declared */
  public String getHttpMethod() {
    return httpMethod;
  }

  /**
   * The same contract, stating what the {@code etendo_action} {@code id} argument must be for it (for
   * example "the financial account id"). Keeps window-specific wording out of the generic MCP
   * classes, which render it verbatim.
   *
   * @param idDescription what {@code id} identifies
   * @return a copy carrying the description
   */
  public NeoActionContract withIdDescription(String idDescription) {
    return new NeoActionContract(name, description, mutating, params, idDescription, httpMethod,
        fieldValuesBody);
  }

  /** @return what the {@code id} argument identifies, or {@code null} when not declared */
  public String getIdDescription() {
    return idDescription;
  }

  /**
   * The same contract, read by its customization from the {@code fieldValues} object of the
   * request body (ETP-5558, ETP-5587).
   *
   * <p>For an action behind an AD button whose parameters the SPA collects in its process dialog:
   * the SPA posts them as {@code {"fieldValues": {...}}}, and the customization reads them there
   * ({@code PeriodOpenCloseHandler} reads {@code fieldValues.openClose}). The agent still passes
   * the declared parameters flat; {@code etendo_action} wraps them, so the customization receives the
   * body the SPA sends instead of answering "Missing required parameter".</p>
   *
   * @return a copy whose parameters travel under {@code fieldValues}
   */
  public NeoActionContract withFieldValuesBody() {
    return new NeoActionContract(name, description, mutating, params, idDescription, httpMethod,
        true);
  }

  /** @return {@code true} when the parameters travel under {@code fieldValues} in the body */
  public boolean isFieldValuesBody() {
    return fieldValuesBody;
  }

  /**
   * An action that changes data (reconciles, undoes, applies).
   *
   * @param name        the action name the caller passes as {@code action}
   * @param description what it does, its preconditions and what it returns
   * @param params      the parameters it accepts
   * @return the contract
   */
  public static NeoActionContract write(String name, String description, Param... params) {
    return new NeoActionContract(name, description, true, List.of(params));
  }

  /**
   * An action that only reads (lists, previews). Never persists anything.
   *
   * @param name        the action name the caller passes as {@code action}
   * @param description what it returns
   * @param params      the parameters it accepts
   * @return the contract
   */
  public static NeoActionContract read(String name, String description, Param... params) {
    return new NeoActionContract(name, description, false, List.of(params));
  }

  /** @return the action name */
  public String getName() {
    return name;
  }

  /** @return what the action does */
  public String getDescription() {
    return description;
  }

  /** @return {@code true} when the action changes data */
  public boolean isMutating() {
    return mutating;
  }

  /** @return the declared parameters, in declaration order */
  public List<Param> getParams() {
    return params;
  }

  /**
   * Renders the contract for {@code etendo_schema(view:"actions")}: the action plus a JSON Schema of
   * its {@code parameters} object.
   *
   * @return {@code {action, description, mutating, invokeVia, parameters:{type, properties,
   *         required, additionalProperties}}}
   * @throws JSONException if the JSON cannot be built
   */
  public JSONObject toJson() throws JSONException {
    JSONObject properties = new JSONObject();
    JSONArray required = new JSONArray();
    for (Param p : params) {
      properties.put(p.name, p.toJsonSchema());
      if (p.required) {
        required.put(p.name);
      }
    }
    JSONObject schema = new JSONObject();
    schema.put(KEY_TYPE, TYPE_OBJECT);
    schema.put("properties", properties);
    schema.put("required", required);
    schema.put("additionalProperties", false);

    JSONObject out = new JSONObject();
    out.put("action", name);
    out.put(KEY_DESCRIPTION, description);
    out.put("mutating", mutating);
    out.put("invokeVia", "etendo_action");
    if (idDescription != null) {
      out.put("idDescription", idDescription);
    }
    out.put("parameters", schema);
    return out;
  }

  /**
   * Judges a call against the declared contracts.
   *
   * @param contracts  the handler's declared actions, by name
   * @param actionName the requested action
   * @param parameters the call's parameters (may be {@code null})
   * @return {@code null} when the call matches; otherwise a 422 naming what is wrong —
   *         {@code availableActions} for an unknown action, {@code unknownParameters} +
   *         {@code acceptedParameters} for an undeclared key, {@code missingParameters} for absent
   *         required ones, or {@code field} + {@code expectedType}/{@code allowedValues} for a value
   *         of the wrong shape
   */
  public static NeoResponse validate(Map<String, NeoActionContract> contracts, String actionName,
      JSONObject parameters) {
    try {
      NeoActionContract contract = actionName != null ? contracts.get(actionName) : null;
      if (contract == null) {
        JSONObject err = errorBody("Unknown action '" + actionName + "'. Available actions: "
            + String.join(", ", contracts.keySet()) + ".");
        err.put("availableActions", new JSONArray(contracts.keySet()));
        return wrap(err);
      }
      JSONObject body = parameters != null ? parameters : new JSONObject();
      NeoResponse unknown = contract.checkUnknown(body);
      if (unknown != null) {
        return unknown;
      }
      NeoResponse missing = contract.checkMissing(body);
      if (missing != null) {
        return missing;
      }
      for (Param p : contract.params) {
        if (body.has(p.name) && !body.isNull(p.name)) {
          NeoResponse typeError = p.check(body.get(p.name));
          if (typeError != null) {
            return typeError;
          }
        }
      }
      return null;
    } catch (JSONException e) {
      return NeoResponse.error(SC_UNPROCESSABLE, "Invalid action parameters: " + e.getMessage());
    }
  }

  private NeoResponse checkUnknown(JSONObject body) throws JSONException {
    List<String> unknown = new ArrayList<>();
    for (Iterator<?> it = body.keys(); it.hasNext();) {
      String key = String.valueOf(it.next());
      if (params.stream().noneMatch(p -> p.name.equals(key))) {
        unknown.add(key);
      }
    }
    if (unknown.isEmpty()) {
      return null;
    }
    Collections.sort(unknown);
    JSONArray accepted = new JSONArray();
    params.forEach(p -> accepted.put(p.name));
    JSONObject err = errorBody("Action '" + name + "' does not accept parameter(s) "
        + String.join(", ", unknown) + ".");
    err.put("unknownParameters", new JSONArray(unknown));
    err.put("acceptedParameters", accepted);
    return wrap(err);
  }

  private NeoResponse checkMissing(JSONObject body) throws JSONException {
    List<String> missing = new ArrayList<>();
    for (Param p : params) {
      if (p.required && isAbsent(body, p.name)) {
        missing.add(p.name);
      }
    }
    if (missing.isEmpty()) {
      return null;
    }
    JSONObject err = errorBody("Action '" + name + "' requires parameter(s) "
        + String.join(", ", missing) + ".");
    err.put("missingParameters", new JSONArray(missing));
    return wrap(err);
  }

  private static boolean isAbsent(JSONObject body, String key) {
    if (!body.has(key) || body.isNull(key)) {
      return true;
    }
    Object v = body.opt(key);
    return (v instanceof String && StringUtils.isBlank((String) v))
        || (v instanceof JSONArray && ((JSONArray) v).length() == 0);
  }

  private static JSONObject errorBody(String message) throws JSONException {
    JSONObject err = new JSONObject();
    err.put(KEY_MESSAGE, message);
    err.put(KEY_STATUS, SC_UNPROCESSABLE);
    return err;
  }

  private static NeoResponse wrap(JSONObject err) throws JSONException {
    JSONObject body = new JSONObject();
    body.put(KEY_ERROR, err);
    return NeoResponse.error(SC_UNPROCESSABLE, body);
  }

  /**
   * The actions a spec's handler declares, resolved the way {@link NeoReportCallability} resolves a
   * report contract: the first included entity whose {@code Java_Qualifier} names a handler with a
   * non-empty {@link NeoHandler#actionContracts()}.
   *
   * @param spec the spec to inspect
   * @return the entity name and its contracts, or empty when no handler of the spec declares any
   */
  public static Optional<SpecActions> resolve(SFSpec spec) {
    if (spec == null) {
      return Optional.empty();
    }
    try {
      OBCriteria<SFEntity> criteria = OBDal.getInstance().createCriteria(SFEntity.class);
      criteria.add(Restrictions.eq(SFEntity.PROPERTY_ETGOSFSPEC + ".id", spec.getId()));
      criteria.add(Restrictions.eq(SFEntity.PROPERTY_ISINCLUDED, true));
      criteria.add(Restrictions.eq(SFEntity.PROPERTY_ISACTIVE, true));
      criteria.addOrder(Order.asc(SFEntity.PROPERTY_NAME));
      for (SFEntity entity : criteria.list()) {
        NeoHandler handler = NeoHandlerLookup.byQualifierQuietly(entity.getJavaQualifier());
        Map<String, NeoActionContract> contracts = handler != null
            ? handler.actionContracts() : Collections.emptyMap();
        if (contracts != null && !contracts.isEmpty()) {
          return Optional.of(new SpecActions(entity.getName(), contracts));
        }
      }
    } catch (Exception e) {
      log.warn("Could not resolve the declared actions of spec '{}': {}", spec.getName(),
          e.getMessage());
    }
    return Optional.empty();
  }

  /** The entity that serves a spec's declared actions, and the actions themselves. */
  public static final class SpecActions {
    private final String entityName;
    private final Map<String, NeoActionContract> contracts;

    SpecActions(String entityName, Map<String, NeoActionContract> contracts) {
      this.entityName = entityName;
      this.contracts = contracts;
    }

    /** @return the entity to pass as {@code entity} to {@code etendo_action} / {@code etendo_schema} */
    public String getEntityName() {
      return entityName;
    }

    /** @return the declared actions, by name */
    public Map<String, NeoActionContract> getContracts() {
      return contracts;
    }
  }

  /** One declared parameter of an action. */
  public static final class Param {
    private final String name;
    private final String type;
    private final String itemType;
    private final boolean required;
    private final String description;
    private final List<String> allowedValues;

    private Param(String name, String type, String itemType, boolean required, String description,
        List<String> allowedValues) {
      this.name = name;
      this.type = type;
      this.itemType = itemType;
      this.required = required;
      this.description = description;
      this.allowedValues = allowedValues == null ? Collections.emptyList()
          : List.copyOf(allowedValues);
    }

    /**
     * A required scalar parameter.
     *
     * @param name        the body key
     * @param type        {@link #TYPE_STRING}, {@link #TYPE_BOOLEAN}, {@link #TYPE_NUMBER} or
     *                    {@link #TYPE_DATE}
     * @param description meaning and expected shape
     * @return the descriptor
     */
    public static Param required(String name, String type, String description) {
      return new Param(name, type, null, true, description, null);
    }

    /**
     * An optional scalar parameter. State the default in the description.
     *
     * @param name        the body key
     * @param type        {@link #TYPE_STRING}, {@link #TYPE_BOOLEAN}, {@link #TYPE_NUMBER} or
     *                    {@link #TYPE_DATE}
     * @param description meaning, expected shape and default
     * @return the descriptor
     */
    public static Param optional(String name, String type, String description) {
      return new Param(name, type, null, false, description, null);
    }

    /**
     * An optional string restricted to a closed set.
     *
     * @param name          the body key
     * @param description   meaning and default
     * @param allowedValues every value the handler distinguishes
     * @return the descriptor
     */
    public static Param options(String name, String description, List<String> allowedValues) {
      return new Param(name, TYPE_STRING, null, false, description, allowedValues);
    }

    /**
     * A required string restricted to a closed set (ETP-5558). For a choice the handler defaults
     * but the agent must make explicitly — because what the handler does without it differs from
     * what the contract promises.
     *
     * @param name          the body key
     * @param description   meaning of each value
     * @param allowedValues every value the handler distinguishes
     * @return the descriptor
     */
    public static Param requiredOptions(String name, String description,
        List<String> allowedValues) {
      return new Param(name, TYPE_STRING, null, true, description, allowedValues);
    }

    /**
     * A list parameter.
     *
     * @param name        the body key
     * @param itemType    {@link #TYPE_STRING} or {@link #TYPE_OBJECT}
     * @param required    whether it must be present and non-empty
     * @param description meaning and item shape
     * @return the descriptor
     */
    public static Param array(String name, String itemType, boolean required,
        String description) {
      return new Param(name, TYPE_ARRAY, itemType, required, description, null);
    }

    /** @return the body key */
    public String getName() {
      return name;
    }

    /** @return the declared type */
    public String getType() {
      return type;
    }

    /** @return whether the parameter is required */
    public boolean isRequired() {
      return required;
    }

    JSONObject toJsonSchema() throws JSONException {
      JSONObject prop = new JSONObject();
      if (TYPE_DATE.equals(type)) {
        prop.put(KEY_TYPE, TYPE_STRING);
        prop.put("format", "date");
      } else {
        prop.put(KEY_TYPE, type);
      }
      if (TYPE_ARRAY.equals(type)) {
        prop.put("items", new JSONObject().put(KEY_TYPE, itemType));
      }
      if (!allowedValues.isEmpty()) {
        prop.put("enum", new JSONArray(allowedValues));
      }
      prop.put(KEY_DESCRIPTION, description);
      return prop;
    }

    NeoResponse check(Object value) throws JSONException {
      String expected = expectedShape(value);
      if (expected != null) {
        JSONObject err = errorBody("Parameter '" + name + "' must be " + expected + ".");
        err.put("field", name);
        err.put("expectedType", TYPE_DATE.equals(type) ? "date (yyyy-MM-dd)" : type);
        return wrap(err);
      }
      if (!allowedValues.isEmpty() && !allowedValues.contains(String.valueOf(value))) {
        JSONObject err = errorBody("Parameter '" + name + "' must be one of "
            + String.join(", ", new TreeSet<>(allowedValues)) + ".");
        err.put("field", name);
        err.put("allowedValues", new JSONArray(allowedValues));
        return wrap(err);
      }
      return null;
    }

    /** @return {@code null} when {@code value} has the declared shape, else a description of it */
    private String expectedShape(Object value) {
      switch (type) {
        case TYPE_BOOLEAN:
          return value instanceof Boolean ? null : "a boolean";
        case TYPE_NUMBER:
          return isNumeric(value) ? null : "a number or numeric string";
        case TYPE_DATE:
          return value instanceof String && DATE.matcher((String) value).matches() ? null
              : "a date in yyyy-MM-dd format";
        case TYPE_ARRAY:
          return isArrayOf(value) ? null : "an array of " + itemType + "s";
        default:
          return value instanceof String ? null : "a string";
      }
    }

    private static boolean isNumeric(Object value) {
      if (value instanceof Number) {
        return true;
      }
      if (!(value instanceof String) || StringUtils.isBlank((String) value)) {
        return false;
      }
      try {
        new java.math.BigDecimal(((String) value).trim());
        return true;
      } catch (NumberFormatException e) {
        return false;
      }
    }

    private boolean isArrayOf(Object value) {
      if (!(value instanceof JSONArray)) {
        return false;
      }
      JSONArray arr = (JSONArray) value;
      for (int i = 0; i < arr.length(); i++) {
        Object item = arr.opt(i);
        boolean ok = TYPE_OBJECT.equals(itemType) ? item instanceof JSONObject
            : item instanceof String && StringUtils.isNotBlank((String) item);
        if (!ok) {
          return false;
        }
      }
      return true;
    }
  }
}
