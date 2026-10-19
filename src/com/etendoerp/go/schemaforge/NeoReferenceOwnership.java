/*
 * *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"), you may not use this file except in compliance with
 * the License.
 * You may obtain a copy of the License at
 * https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * Software distributed under the License  is  distributed  on  an  "AS IS"
 * basis, WITHOUT WARRANTY OF ANY KIND, either express or implied. See the
 * License for the specific language governing rights and limitations under
 * the License.
 * All portions are Copyright © 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge;

import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.base.model.Property;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.client.kernel.KernelUtils;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.dal.service.OBQuery;
import org.openbravo.model.ad.ui.Tab;
import org.openbravo.service.json.JsonConstants;

/**
 * Refuses a NEO write whose request references a record of another tenant (ETP-5785).
 *
 * <p><b>The hole.</b> A foreign key value in a write body reaches core's
 * {@code JsonToDataConverter}, which resolves it with a bare {@code OBDal.get} — no client filter —
 * and {@code OBInterceptor} skips its referenced-organization check on insert. The parent of a
 * child create ({@code parentId}) is read the same way by {@link NeoParentValuesLoader}. So a
 * caller of tenant A who knows a UUID of tenant B could attach A's order line to B's order, or
 * point A's document at B's price list, tax, partner or user.
 *
 * <p><b>The rule.</b> Every reference the REQUEST sets — each foreign-key value of the body, plus
 * {@code parentId} — must resolve to a row of the caller's client or of System ({@code 0}), as
 * decided by {@link TenantOwnership#loadOwnedByClient(String, String)}. A row of another client and
 * a row that does not exist are refused with the very same answer: the response core gives for an
 * unknown reference (see {@link #unknownReferenceResponse}), so the refusal never tells the caller
 * that the id exists elsewhere.
 *
 * <p><b>Structure only.</b> A body key is a reference when the DAL model says so — a non-primitive
 * property of the written entity, reached directly or through a {@code a.b.id} path — and its target
 * is tenant-scoped ({@link TenantOwnership#isTenantScoped(Entity)}). No entity, table or property is
 * named here. A nested JSON object with an {@code id} is checked as the row it addresses, and its
 * own keys are walked the same way, because core writes into that row too.
 *
 * <p><b>Cost.</b> One {@code OBDal.get} per distinct reference the request sets — never per
 * foreign key of the entity, and server-derived values (defaults, callouts) are not looked at.
 * The row lands in the session's first-level cache, which is where core's own resolution of the
 * same id finds it a moment later, so a request that passes costs no extra query. Shared reference
 * data (not client-enabled) is skipped without a lookup.
 *
 * <p><b>Where it runs.</b> Every write channel that persists through NEO calls it before any
 * default, callout or parent read can consume the reference: {@link NeoCrudHandler} (REST single
 * and {@code /batch}, which reaches the same create path through {@code BatchService}, MCP
 * {@code etendo_batch} included) and the MCP {@code etendo_create} / {@code etendo_update} verbs,
 * which persist on their own. See {@code docs/neo-headless.md} §4.3.7.
 */
public final class NeoReferenceOwnership {

  private static final Logger log = LogManager.getLogger(NeoReferenceOwnership.class);

  /**
   * An id that no tenant can own: {@code 0} is reserved for System rows, and as a value it is the UI
   * "not set yet" sentinel, which the write paths already resolve or drop on their own.
   */
  private static final String SENTINEL_ID = "0";

  private static final String JSON_NULL = "null";

  private static final char PATH_SEPARATOR = '.';

  private NeoReferenceOwnership() {
  }

  /**
   * Checks the references a write request sets.
   *
   * @param adTab         the tab being written; its table names the written entity
   * @param requestFields the body keyed by DAL property name, holding ONLY what the request set —
   *                      taken before defaults or callouts add server-derived values. May be
   *                      {@code null} to check the parent alone
   * @param parentId      the parent record id of a child create, or {@code null}
   * @return {@code null} when every reference is acceptable; otherwise the JSON body core's write
   *         service returns for an unknown reference, to be handed to the caller's normal response
   *         handling in place of the write result
   */
  public static String refuseUnownedReferences(Tab adTab, JSONObject requestFields,
      String parentId) {
    if (adTab == null || adTab.getTable() == null || OBContext.getOBContext() == null) {
      // No session means no request (background work, unit tests); TenantOwnership fails open
      // there too.
      return null;
    }
    Entity written = ModelProvider.getInstance().getEntityByTableId(adTab.getTable().getId());
    if (written == null) {
      return null;
    }
    Set<Reference> references = new LinkedHashSet<>();
    collectParent(adTab, parentId, references);
    collectFields(written, requestFields, references);
    return refuseFirstUnowned(references);
  }

  // ── collection ──────────────────────────────────────────────────────────

  /**
   * The parent of a child create is the record of the parent tab — the same row
   * {@link NeoParentValuesLoader} reads and the link-to-parent column receives.
   */
  private static void collectParent(Tab adTab, String parentId, Set<Reference> out) {
    if (StringUtils.isBlank(parentId) || adTab.getTabLevel() == null
        || adTab.getTabLevel() <= 0) {
      // A header ignores parentId on every write path, so there is nothing to protect.
      return;
    }
    Tab parentTab = KernelUtils.getInstance().getParentTab(adTab);
    if (parentTab == null || parentTab.getTable() == null) {
      return;
    }
    Entity parent = ModelProvider.getInstance().getEntityByTableId(parentTab.getTable().getId());
    addReference(parent, null, parentId, out);
  }

  /** Walks every key of a body (or of a nested record) against the entity it belongs to. */
  static void collectFields(Entity entity, JSONObject fields, Set<Reference> out) {
    if (entity == null || fields == null) {
      return;
    }
    Iterator<?> keys = fields.keys();
    while (keys.hasNext()) {
      String key = String.valueOf(keys.next());
      collectKey(entity, key, fields.opt(key), out);
    }
  }

  /**
   * Resolves one key structurally. A plain key is a property of {@code entity}; a dotted key
   * ({@code businessPartner.id}) walks reference properties, which is how core's converter reads it
   * too. Keys that resolve to nothing are left to the write path, which ignores or rejects them.
   */
  private static void collectKey(Entity entity, String key, Object value, Set<Reference> out) {
    String[] path = StringUtils.split(key, PATH_SEPARATOR);
    if (path == null || path.length == 0) {
      return;
    }
    Entity owner = entity;
    for (int i = 0; i < path.length - 1; i++) {
      Property hop = owner.getProperty(path[i], false);
      if (hop == null || hop.isPrimitive() || hop.getTargetEntity() == null) {
        return;
      }
      owner = hop.getTargetEntity();
    }
    Property property = owner.getProperty(path[path.length - 1], false);
    if (property == null) {
      return;
    }
    if (property.isId()) {
      // A plain `id` is the written record itself, not a reference: a create discards it and an
      // update replaces it with the path id. Reached through a path it names the referenced row.
      if (path.length > 1) {
        addReference(owner, null, value, out);
      }
      return;
    }
    if (isPersistedReference(property)) {
      collectValue(property, value, out);
    }
  }

  /**
   * True for a reference property core would actually write from JSON. Mirrors
   * {@code JsonToDataConverter#isNotConvertable}: one-to-many lists, inactive and proxy properties
   * are never converted. Audit references stay in: {@code OBInterceptor} keeps a sent
   * {@code createdBy} when the body also carries the other audit values.
   */
  private static boolean isPersistedReference(Property property) {
    return !property.isPrimitive() && !property.isOneToMany() && !property.isInactive()
        && !property.isProxy() && property.getTargetEntity() != null;
  }

  private static void collectValue(Property property, Object value, Set<Reference> out) {
    Entity target = property.getTargetEntity();
    if (value instanceof JSONObject) {
      collectNested(target, (JSONObject) value, out);
    } else if (value instanceof JSONArray) {
      JSONArray array = (JSONArray) value;
      for (int i = 0; i < array.length(); i++) {
        Object element = array.opt(i);
        if (element instanceof JSONObject) {
          collectNested(target, (JSONObject) element, out);
        }
      }
    } else {
      addReference(target, nonIdReferencedProperty(property), value, out);
    }
  }

  /**
   * A reference sent as an object. Core loads the row it names — by its own {@code _entityName}
   * when it carries one — and writes the object's other keys into it, so both the row and those
   * keys are checked.
   */
  private static void collectNested(Entity declared, JSONObject record, Set<Reference> out) {
    Entity entity = declared;
    String named = record.optString(JsonConstants.ENTITYNAME, null);
    if (StringUtils.isNotBlank(named)) {
      Entity resolved = ModelProvider.getInstance().getEntity(named, false);
      if (resolved != null) {
        entity = resolved;
      }
    }
    boolean isNew = record.optBoolean(JsonConstants.NEW_INDICATOR, false);
    if (!isNew && record.has(JsonConstants.ID)) {
      addReference(entity, null, record.opt(JsonConstants.ID), out);
    }
    collectFields(entity, record, out);
  }

  /** The referenced property when it is not the target's id (rare), else {@code null}. */
  private static Property nonIdReferencedProperty(Property property) {
    Property referenced = property.getReferencedProperty();
    return referenced == null || referenced.isId() ? null : referenced;
  }

  private static void addReference(Entity target, Property referencedProperty, Object raw,
      Set<Reference> out) {
    if (!(raw instanceof String) || !TenantOwnership.isTenantScoped(target)) {
      return;
    }
    String value = (String) raw;
    if (isNotAReference(value)) {
      return;
    }
    out.add(new Reference(target, referencedProperty, value));
  }

  /**
   * Values that address no row of any tenant, left to the write path exactly as before: empty, the
   * JSON null spelling, the System/"not set" sentinel, a batch forward reference ({@code $ref:op})
   * that has not been substituted yet — BatchService replaces those with ids created in the same
   * transaction before the op runs — and placeholder literals no id can match: an unresolved AD
   * default ({@code @OnHandLocatorDefault@}, which customizations replace) or a sequence preview
   * ({@code <1000>}).
   */
  private static boolean isNotAReference(String value) {
    return StringUtils.isBlank(value) || JSON_NULL.equals(value) || SENTINEL_ID.equals(value)
        || value.startsWith(BatchService.REF_PREFIX) || isWrapped(value, '@', '@')
        || isWrapped(value, '<', '>');
  }

  private static boolean isWrapped(String value, char open, char close) {
    return value.length() > 2 && value.charAt(0) == open
        && value.charAt(value.length() - 1) == close;
  }

  // ── verification ────────────────────────────────────────────────────────

  private static String refuseFirstUnowned(Set<Reference> references) {
    if (references.isEmpty()) {
      return null;
    }
    // Admin mode so that only tenant ownership decides here: entity read access is still enforced
    // by core when it resolves the same reference, with the same outcome as before this guard.
    OBContext.setAdminMode(true);
    try {
      for (Reference reference : references) {
        if (!isOwned(reference)) {
          // The id is not logged: it may be another tenant's.
          log.warn("Refused a NEO write: a reference to {} set by the request is not a row of the"
              + " caller's client or of System", reference.entity.getName());
          return unknownReferenceResponse(reference);
        }
      }
      return null;
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  private static boolean isOwned(Reference reference) {
    if (reference.referencedProperty == null) {
      return TenantOwnership.loadOwnedByClient(reference.entity.getName(), reference.value) != null;
    }
    // A reference by a non-id property resolves through a query, exactly as core does it — with
    // the client filter off, which is the hole. Every match must be acceptable, since core takes
    // whichever comes first.
    OBQuery<BaseOBObject> query = OBDal.getInstance().createQuery(reference.entity.getName(),
        reference.referencedProperty.getName() + " = :reference");
    query.setNamedParameter("reference", reference.value);
    query.setFilterOnActive(false);
    query.setFilterOnReadableClients(false);
    query.setFilterOnReadableOrganization(false);
    List<BaseOBObject> rows = query.list();
    return !rows.isEmpty() && rows.stream().allMatch(TenantOwnership::belongsToReadableClient);
  }

  /**
   * The body core's {@code DefaultJsonDataService} returns when a reference names no row:
   * status {@code RPCREQUEST_STATUS_VALIDATION_ERROR}, one error keyed by the target's id property,
   * worded exactly as {@code JsonToDataConverter#hasErrors} words it. Each channel already turns
   * that body into its own answer (REST: a 400; MCP: the 422 validation envelope), so a foreign
   * reference and a missing one cannot be told apart.
   */
  static String unknownReferenceResponse(Reference reference) {
    String entityName = reference.entity.getName();
    String key = reference.value + "_" + entityName;
    String message = "New object " + placeholderOf(entityName) + " (key: " + key
        + ") refered to but not present in the import set";
    List<Property> idProperties = reference.entity.getIdProperties();
    String errorKey = idProperties == null || idProperties.isEmpty() ? JsonConstants.ID
        : idProperties.get(0).getName();
    try {
      JSONObject errors = new JSONObject().put(errorKey, message);
      JSONObject response = new JSONObject()
          .put(JsonConstants.RESPONSE_STATUS, JsonConstants.RPCREQUEST_STATUS_VALIDATION_ERROR)
          .put(JsonConstants.RESPONSE_ERRORS, errors);
      return new JSONObject().put(JsonConstants.RESPONSE_RESPONSE, response).toString();
    } catch (JSONException e) {
      // Unreachable with string keys; a refusal must never turn into a pass.
      throw new IllegalStateException("Could not build the refusal body", e);
    }
  }

  /**
   * How core prints the placeholder object it creates for an unknown reference: a fresh instance
   * of the target entity. Built the same way so the wording matches byte for byte.
   */
  private static String placeholderOf(String entityName) {
    try {
      return String.valueOf(OBProvider.getInstance().get(entityName));
    } catch (RuntimeException e) {
      return entityName + "(null) ";
    }
  }

  /** One distinct reference: the target entity, how it is addressed, and the value sent. */
  static final class Reference {
    final Entity entity;
    final Property referencedProperty;
    final String value;

    Reference(Entity entity, Property referencedProperty, String value) {
      this.entity = entity;
      this.referencedProperty = referencedProperty;
      this.value = value;
    }

    private String addressedBy() {
      return referencedProperty == null ? null : referencedProperty.getName();
    }

    @Override
    public boolean equals(Object other) {
      if (this == other) {
        return true;
      }
      if (!(other instanceof Reference)) {
        return false;
      }
      Reference that = (Reference) other;
      return entity.getName().equals(that.entity.getName())
          && Objects.equals(addressedBy(), that.addressedBy()) && value.equals(that.value);
    }

    @Override
    public int hashCode() {
      return Objects.hash(entity.getName(), addressedBy(), value);
    }
  }
}
