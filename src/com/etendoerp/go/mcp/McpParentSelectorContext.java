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

import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.function.Predicate;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.Property;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.base.structure.ClientEnabled;
import org.openbravo.base.structure.OrganizationEnabled;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;

import com.etendoerp.go.schemaforge.data.SFEntity;

/**
 * The parent record of a child create, as selector context for FK-by-name resolution (ETP-5535).
 *
 * <p><b>The gap.</b> {@code neo_selectors} takes the header's values as {@code parentContext}, and
 * its tool description tells the agent to pass them for "line selectors that depend on header
 * values". {@code neo_create} and {@code neo_batch} have no such argument, and resolved FK names on
 * a child with context built from the tab and the child's own body only (IMP-22). A selector whose
 * validation rule reads a header value therefore saw it unset. Measured on
 * {@code sales-quotation/quotationLine}: the tax rule ({@code C_Tax_IsSOTrx_Date}) compares
 * {@code ValidFrom} against {@code COALESCE(@DateInvoiced@, @DateOrdered@)}, the line carries
 * neither date, the selector answered no rows, and {@code tax: "Entregas IVA 21%"} came back as
 * {@code not_found} — while {@code neo_selectors} with {@code recordContext.orderDate} matched it.</p>
 *
 * <p><b>The fix is structural.</b> The parent is identified by the entity's parent scope
 * ({@link McpParentScope}), never by name, and its record is read with {@code OBDal#get}. Every
 * scalar and FK value it holds is handed to
 * {@link McpSelectorContextHelper#buildSelectorContextParams} as the {@code parentContext} an
 * agent would have passed to {@code neo_selectors}. The helper keeps deciding which keys mean
 * anything to a selector, so nothing here knows which header field a given rule reads.</p>
 *
 * <p><b>Tenant boundary.</b> The MCP router runs tool calls in admin mode, so {@code OBDal#get}
 * alone does NOT apply the caller's read access. The record is therefore checked explicitly: a
 * parent that carries a client must belong to the current client, and one that carries an
 * organization must be in the role's readable organizations ({@code OBContext}), or it is not
 * used. Otherwise a foreign header id would leak its values into the selectors.</p>
 *
 * <p><b>The child's own value always wins.</b> A parent property whose name is also a key of the
 * child body is left out, even while that key still holds an unresolved name. The FK resolver
 * withholds pending body keys from the context (IMP-22); if the parent's value under the same name
 * filled the gap, a line sending {@code businessPartner:"X"} and {@code partnerAddress:"Y"} by name
 * would resolve the address against the header's partner instead of X.</p>
 *
 * <p>Every failure — no parent scope, no parent id yet, a parent that is missing or outside the
 * caller's tenant — answers {@code null}, which is exactly the context used before this class
 * existed.</p>
 */
final class McpParentSelectorContext {

  private static final Logger log = LogManager.getLogger(McpParentSelectorContext.class);
  private static final String ISO_DATE = "yyyy-MM-dd";

  private McpParentSelectorContext() {
    // utility class — no instances
  }

  /**
   * Builds the {@code buildSelectorContextParams} arguments for a child write.
   *
   * @param sfEntity  the entity being written
   * @param dalEntity its DAL entity
   * @param body      the write body, keyed by DAL property name; when {@code parentId} is
   *                  {@code null} the parent id is read from its {@code parentId} key, else from
   *                  the parent-link field
   * @param parentId  the parent id the caller already resolved, which wins over the body; for a
   *                  batch op this is {@code OperationContext#parentId()}, because a
   *                  {@code parentRef} op carries its parent nowhere in the body at preprocessing
   *                  time (BatchService injects it only when the record is created).
   *                  {@code null} or blank falls back to the body
   * @param skipValue values that are not ids yet (a batch {@code $ref}); applied to whichever id
   *                  was chosen; {@code null} skips none
   * @return {@code {parentContext: {...}}}, or {@code null} when there is no readable parent
   */
  static JSONObject selectorArgs(SFEntity sfEntity, Entity dalEntity, JSONObject body,
      String parentId, Predicate<String> skipValue) {
    if (sfEntity == null || dalEntity == null || body == null) {
      return null;
    }
    try {
      String parentField = McpParentScope.forEntity(sfEntity).getParentField();
      if (parentField == null) {
        return null;
      }
      String resolvedParentId = StringUtils.trimToNull(parentId);
      if (resolvedParentId == null) {
        resolvedParentId = parentIdOf(body, parentField);
      }
      if (resolvedParentId == null
          || (skipValue != null && skipValue.test(resolvedParentId))) {
        return null;
      }
      Property link = dalEntity.getProperty(parentField, false);
      Entity parentEntity = link != null ? link.getTargetEntity() : null;
      if (parentEntity == null) {
        return null;
      }
      BaseOBObject parent = OBDal.getInstance().get(parentEntity.getName(), resolvedParentId);
      if (parent == null || !isWithinCallerTenant(parent)) {
        return null;
      }
      JSONObject args = new JSONObject();
      args.put(McpConstants.PARAM_PARENT_CONTEXT, describe(parent, parentEntity, body));
      return args;
    } catch (Exception e) {
      log.warn("No parent selector context for {}: {}", sfEntity.getName(), e.getMessage());
      return null;
    }
  }

  /**
   * Whether the caller may see the parent record, checked on the record itself because the router's
   * admin mode bypasses the DAL's own filter (see the class Javadoc). Structural: it reads only the
   * {@link ClientEnabled} / {@link OrganizationEnabled} contracts every tenant-scoped entity
   * implements; an entity implementing neither carries no tenant to check.
   */
  private static boolean isWithinCallerTenant(BaseOBObject parent) {
    OBContext context = OBContext.getOBContext();
    if (parent instanceof ClientEnabled) {
      Object clientId = idOf(((ClientEnabled) parent).getClient());
      Object currentClientId = context != null ? idOf(context.getCurrentClient()) : null;
      if (clientId == null || !clientId.equals(currentClientId)) {
        return false;
      }
    }
    if (parent instanceof OrganizationEnabled) {
      Object orgId = idOf(((OrganizationEnabled) parent).getOrganization());
      String[] readable = context != null ? context.getReadableOrganizations() : null;
      return orgId != null && readable != null && Arrays.asList(readable).contains(orgId);
    }
    return true;
  }

  private static Object idOf(BaseOBObject object) {
    return object != null ? object.getId() : null;
  }

  private static String parentIdOf(JSONObject body, String parentField) {
    String explicit = StringUtils.trimToNull(body.optString(McpConstants.PARAM_PARENT_ID, null));
    if (explicit != null) {
      return explicit;
    }
    Object linked = body.opt(parentField);
    return linked instanceof String ? StringUtils.trimToNull((String) linked) : null;
  }

  /**
   * The parent's own values, keyed by DAL property name: an FK as its id, a date as
   * {@code yyyy-MM-dd}, any other scalar as its string form. Collections and computed columns are
   * skipped — neither is selector context, and reading them costs a query each. So is any property
   * whose name is a key of the child body: the child's own value wins, resolved or not (see the
   * class Javadoc).
   */
  private static JSONObject describe(BaseOBObject parent, Entity parentEntity, JSONObject body)
      throws JSONException {
    JSONObject values = new JSONObject();
    for (Property property : parentEntity.getProperties()) {
      if (property.isOneToMany() || property.isComputedColumn() || body.has(property.getName())) {
        continue;
      }
      Object value = parent.get(property.getName());
      String text = asContextValue(value);
      if (text != null) {
        values.put(property.getName(), text);
      }
    }
    return values;
  }

  private static String asContextValue(Object value) {
    if (value == null) {
      return null;
    }
    if (value instanceof BaseOBObject) {
      Object id = ((BaseOBObject) value).getId();
      return id != null ? id.toString() : null;
    }
    if (value instanceof Date) {
      return new SimpleDateFormat(ISO_DATE).format((Date) value);
    }
    if (value instanceof Boolean) {
      return Boolean.TRUE.equals(value) ? "Y" : "N";
    }
    return value.toString();
  }
}
