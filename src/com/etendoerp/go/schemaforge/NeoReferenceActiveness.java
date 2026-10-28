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

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.base.model.Property;
import org.openbravo.base.structure.ActiveEnabled;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.ui.Tab;

/**
 * Refuses a NEO write that SETS a reference/FK column to a value whose target row is inactive
 * (ETP-5802).
 *
 * <p><b>The hole.</b> Core's {@code JsonToDataConverter} resolves a foreign-key value with a bare
 * {@code OBDal.get} and never looks at the referenced row's {@code IsActive} flag — any channel
 * (REST single, REST batch, MCP) could set a column such as {@code M_Product.C_TaxCategory_ID} to
 * an id whose record had since been deactivated, and the write succeeded with status 0. Reproduced
 * on {@code etendo_create} for {@code product.taxCategory} against a deactivated
 * {@code FinancialMgmtTaxCategory} row: the response echoed the inactive row's own identifier,
 * visually indistinguishable from an active one.
 *
 * <p><b>The rule — structure only.</b> A body key is checked when the DAL model says it is a
 * persisted reference of the written entity (mirrors {@code NeoReferenceOwnership}'s own
 * structural test) AND its target entity is {@link ActiveEnabled} — i.e. the target table declares
 * an {@code IsActive} column at all. No entity, table or property is named here: a reference to a
 * table with no {@code IsActive} column (most system/lookup tables) is structurally exempt, not
 * special-cased.
 *
 * <p><b>Changed, not merely present (ETP-5802 CP-6).</b> This class inspects exactly the
 * {@code JSONObject} it is handed — the caller decides what that is. Both write paths already build
 * that object from ONLY the keys the request actually set (REST: {@code NeoWritePreflight}'s
 * DAL-keyed copy of the body; MCP: {@code filteredBody} after {@code mapFieldsToDalProperties},
 * which only ever carries caller-sent keys). An update that leaves an already-inactive reference
 * untouched never has that key in the checked object, so it is not re-validated and does not break
 * (the regression QA's CP-6 guards against). Only a write that is itself setting the column is
 * refused.
 *
 * <p><b>Where it runs.</b> Before any customization is dispatched, exactly like
 * {@link NeoReferenceOwnership}: {@link NeoWritePreflight} for REST single writes and every
 * {@code /batch} create, and the MCP {@code etendo_create} / {@code etendo_update} pipelines, which
 * persist on their own.
 */
public final class NeoReferenceActiveness {

  private static final Logger log = LogManager.getLogger(NeoReferenceActiveness.class);

  /** An id that addresses no row: the UI "not set yet" sentinel. */
  private static final String SENTINEL_ID = "0";

  private static final String JSON_NULL = "null";

  private NeoReferenceActiveness() {
  }

  /**
   * Finds the first reference the request sets whose target row is inactive.
   *
   * @param adTab         the tab being written; its table names the written entity
   * @param requestFields the body keyed by DAL property name, holding ONLY what the request set
   * @return the offending field and the inactive row's entity, or {@code null} when every
   *     reference the request sets is either not a reference, not active-tracked, or active
   */
  public static InactiveReference findInactiveReference(Tab adTab, JSONObject requestFields) {
    if (adTab == null || adTab.getTable() == null || requestFields == null
        || OBContext.getOBContext() == null) {
      return null;
    }
    Entity written = ModelProvider.getInstance().getEntityByTableId(adTab.getTable().getId());
    if (written == null) {
      return null;
    }
    OBContext.setAdminMode(true);
    try {
      Iterator<?> keys = requestFields.keys();
      while (keys.hasNext()) {
        String key = String.valueOf(keys.next());
        InactiveReference found = checkKey(written, key, requestFields.opt(key));
        if (found != null) {
          log.warn("Refused a NEO write: '{}' was set to an inactive {} row", key,
              found.targetEntityName());
          return found;
        }
      }
      return null;
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  private static InactiveReference checkKey(Entity entity, String key, Object value) {
    if (!(value instanceof String || value instanceof Number)) {
      // Nested objects/arrays and non-scalar values are not addressed by this check; the default
      // write path (and NeoReferenceOwnership, for ownership) handles those shapes.
      return null;
    }
    Property property = entity.getProperty(key, false);
    if (property == null || property.isId() || !isPersistedReference(property)) {
      return null;
    }
    String id = String.valueOf(value);
    if (isNotAReference(id)) {
      return null;
    }
    Entity target = property.getTargetEntity();
    if (target == null || !target.isActiveEnabled()) {
      // Structural exemption: the target table carries no IsActive column at all.
      return null;
    }
    BaseOBObject row = lookUp(target, id);
    // A missing/foreign row is NeoReferenceOwnership's concern, not this one: say nothing here so
    // the two checks never disagree about the same value.
    if (!(row instanceof ActiveEnabled)) {
      return null;
    }
    Boolean active = ((ActiveEnabled) row).isActive();
    return Boolean.FALSE.equals(active) ? new InactiveReference(key, target.getName()) : null;
  }

  /**
   * True for a reference property core would actually write from JSON. Mirrors
   * {@code NeoReferenceOwnership#isPersistedReference}: one-to-many lists, inactive and proxy
   * properties are never converted, so they are never "set" by a write either.
   */
  private static boolean isPersistedReference(Property property) {
    return !property.isPrimitive() && !property.isOneToMany() && !property.isInactive()
        && !property.isProxy() && property.getTargetEntity() != null;
  }

  /**
   * Fails open: a lookup that throws (an id the target's key type cannot hold, a broken row) is
   * simply not a row this check can evaluate, so the write is left to the rest of the pipeline
   * (which will itself refuse an id that names nothing).
   */
  private static BaseOBObject lookUp(Entity target, String id) {
    try {
      return OBDal.getInstance().get(target.getName(), id);
    } catch (RuntimeException e) {
      log.debug("Inactive-reference lookup on {} failed: {}", target.getName(), e.getMessage());
      return null;
    }
  }

  /**
   * Values that address no row of any table: empty, the JSON null spelling, the "not set" sentinel,
   * a batch forward reference ({@code $ref:op}) BatchService substitutes before the op runs, and
   * placeholder literals no id can match (an unresolved AD default or a sequence preview).
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

  /** The field (DAL property name) the request set, and the entity name of its inactive target. */
  public static final class InactiveReference {
    private final String fieldName;
    private final String targetEntityName;

    InactiveReference(String fieldName, String targetEntityName) {
      this.fieldName = fieldName;
      this.targetEntityName = targetEntityName;
    }

    /** The DAL property name the write request set — the key to report back to the caller. */
    public String fieldName() {
      return fieldName;
    }

    /** The DAL entity name of the inactive row the field pointed at, for the refusal message. */
    public String targetEntityName() {
      return targetEntityName;
    }
  }
}
