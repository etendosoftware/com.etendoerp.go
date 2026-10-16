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

import java.util.function.IntSupplier;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.base.model.Property;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.client.kernel.KernelUtils;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.ui.Tab;
import org.openbravo.model.common.currency.Currency;

/**
 * The number of decimals a derived amount of a record is rounded to: the standard precision
 * ({@code C_Currency.StdPrecision}) of the currency the record is expressed in (ETP-5703).
 *
 * <p><b>Structure, not identity.</b> The currency is found by TYPE, never by name: the first
 * property whose target entity is {@link Currency}, on the record itself, and failing that on the
 * record's parent (the property flagged {@code IsParent} in AD). No entity, table or business
 * property is named, so every document line — order, quotation, invoice, sales or purchase —
 * resolves the same way, whichever channel wrote it.
 *
 * <p><b>Why it exists.</b> {@link NeoCommercialLinePolicy} derived line amounts in {@code double}
 * and never rounded them, so a line of 120 × 1.50 at 21 % persisted
 * {@code grossAmount = 217.79999999999998} and one of 3 × 1.50 persisted {@code 5.445}. Core's own
 * amount callouts round to the document currency's standard precision; this is what lets the
 * shared policy do the same.
 *
 * <p>Total: never throws. A record whose currency cannot be established gets
 * {@link #DEFAULT_SCALE}, the scale the policy already used for {@code lineGrossAmount}.
 */
final class NeoCurrencyPrecision {

  private static final Logger log = LogManager.getLogger(NeoCurrencyPrecision.class);

  /** The scale used when no currency can be resolved. */
  static final int DEFAULT_SCALE = 2;

  /** Key under which a create body may still carry its parent's id (REST/batch pre-hook). */
  private static final String PARAM_PARENT_ID = "parentId";

  private NeoCurrencyPrecision() {
  }

  /**
   * A lazy, memoized precision for a write on {@code adTab}'s entity: nothing is read unless an
   * amount is actually derived.
   *
   * @param adTab    the tab being written; {@code null} yields {@link #DEFAULT_SCALE}
   * @param body     the write body, keyed by DAL property name; may be {@code null}
   * @param recordId the record being updated, or {@code null} on a create
   */
  static IntSupplier forWrite(Tab adTab, JSONObject body, String recordId) {
    return memoize(() -> resolve(adTab, body, recordId));
  }

  /**
   * A lazy, memoized precision of the currency {@code currencyId} (ETP-5703). A blank id (or
   * {@code "null"}) answers {@link #DEFAULT_SCALE} without touching the DAL; any lookup failure, or
   * an unknown currency, answers it too. Never throws.
   */
  static IntSupplier forCurrencyId(String currencyId) {
    if (StringUtils.isBlank(currencyId) || "null".equals(currencyId.trim())) {
      return () -> DEFAULT_SCALE;
    }
    return memoize(() -> scaleOfCurrencyId(currencyId.trim()));
  }

  /**
   * {@link #forCurrencyId}'s lookup; total. The admin mode is restored even when
   * {@code setAdminMode} itself throws, since it has already been pushed by then.
   */
  static int scaleOfCurrencyId(String currencyId) {
    try {
      OBContext.setAdminMode(true);
    } catch (Exception | LinkageError e) {
      log.debug("[CURRENCY-PRECISION] No DAL context, using {} decimals: {}", DEFAULT_SCALE,
          e.getMessage());
      // setAdminMode pushes the mode before it can throw: pop it, or it leaks to the thread.
      restoreQuietly();
      return DEFAULT_SCALE;
    }
    try {
      return scaleOf(OBDal.getInstance().get(Currency.class, currencyId));
    } catch (Exception | LinkageError e) {
      log.debug("[CURRENCY-PRECISION] Currency {} not resolvable, using {} decimals: {}",
          currencyId, DEFAULT_SCALE, e.getMessage());
      return DEFAULT_SCALE;
    } finally {
      restoreQuietly();
    }
  }

  /** {@code supplier}, evaluated at most once. */
  static IntSupplier memoize(IntSupplier supplier) {
    if (supplier == null) {
      return () -> DEFAULT_SCALE;
    }
    int[] cached = new int[1];
    boolean[] done = new boolean[1];
    return () -> {
      if (!done[0]) {
        cached[0] = supplier.getAsInt();
        done[0] = true;
      }
      return cached[0];
    };
  }

  /**
   * Resolves the precision, most specific first: a currency the body itself carries, the stored
   * record's own currency (update), then the parent's currency — from the stored record, from the
   * parent link in the body, or from a {@code parentId} still in the body. The admin mode is
   * restored even when {@code setAdminMode} itself throws, since it has already been pushed.
   */
  static int resolve(Tab adTab, JSONObject body, String recordId) {
    // A tab without a real table id (a unit-test mock, a half-built context) has nothing to resolve
    // against: short-circuit before touching OBContext or ModelProvider at all.
    if (adTab == null || adTab.getTable() == null
        || StringUtils.isBlank(adTab.getTable().getId())) {
      return DEFAULT_SCALE;
    }
    try {
      OBContext.setAdminMode(true);
    } catch (Exception | LinkageError e) {
      log.debug("[CURRENCY-PRECISION] No DAL context, using {} decimals: {}", DEFAULT_SCALE,
          e.getMessage());
      // setAdminMode pushes the mode before it can throw: pop it, or it leaks to the thread.
      restoreQuietly();
      return DEFAULT_SCALE;
    }
    try {
      Entity entity = ModelProvider.getInstance().getEntityByTableId(adTab.getTable().getId());
      if (entity == null) {
        return DEFAULT_SCALE;
      }
      Currency currency = currencyReferencedBy(entity, body);
      if (currency == null && StringUtils.isNotBlank(recordId)) {
        currency = currencyOfRecordOrParent(entity, OBDal.getInstance().get(entity.getName(),
            recordId));
      }
      if (currency == null) {
        currency = currencyOfParentInBody(entity, body);
      }
      if (currency == null && body != null) {
        currency = currencyOfParentId(adTab, body.optString(PARAM_PARENT_ID, null));
      }
      return scaleOf(currency);
    } catch (Exception | LinkageError e) {
      // LinkageError too: an uninitialised DAL (ExceptionInInitializerError, NoClassDefFoundError)
      // must cost a rounding default, never the write that asked for it.
      log.debug("[CURRENCY-PRECISION] Falling back to {} decimals: {}", DEFAULT_SCALE,
          e.getMessage());
      return DEFAULT_SCALE;
    } finally {
      restoreQuietly();
    }
  }

  private static void restoreQuietly() {
    try {
      OBContext.restorePreviousMode();
    } catch (Exception | LinkageError e) {
      log.debug("[CURRENCY-PRECISION] Could not restore the previous mode: {}", e.getMessage());
    }
  }

  /** The standard precision of {@code currency}, or {@link #DEFAULT_SCALE} when unknown. */
  static int scaleOf(Currency currency) {
    if (currency == null || currency.getStandardPrecision() == null) {
      return DEFAULT_SCALE;
    }
    return currency.getStandardPrecision().intValue();
  }

  private static boolean isCurrencyReference(Property property) {
    return !property.isOneToMany() && property.getTargetEntity() != null
        && Currency.ENTITY_NAME.equals(property.getTargetEntity().getName());
  }

  private static boolean isParentReference(Property property) {
    return property.isParent() && !property.isOneToMany() && property.getTargetEntity() != null;
  }

  /** The currency whose id the body carries under a currency-typed property. */
  private static Currency currencyReferencedBy(Entity entity, JSONObject body) {
    if (body == null) {
      return null;
    }
    for (Property property : entity.getProperties()) {
      if (isCurrencyReference(property)) {
        String id = idValue(body, property.getName());
        Currency currency = id != null ? OBDal.getInstance().get(Currency.class, id) : null;
        if (currency != null) {
          return currency;
        }
      }
    }
    return null;
  }

  /** The record's own currency, else the currency of its parent record. */
  private static Currency currencyOfRecordOrParent(Entity entity, BaseOBObject stored) {
    if (stored == null) {
      return null;
    }
    Currency own = currencyOf(entity, stored);
    if (own != null) {
      return own;
    }
    for (Property property : entity.getProperties()) {
      if (isParentReference(property)) {
        Object parent = stored.get(property.getName());
        if (parent instanceof BaseOBObject) {
          Currency currency = currencyOf(property.getTargetEntity(), (BaseOBObject) parent);
          if (currency != null) {
            return currency;
          }
        }
      }
    }
    return null;
  }

  /** The currency of the parent whose id the body carries under its parent link. */
  private static Currency currencyOfParentInBody(Entity entity, JSONObject body) {
    if (body == null) {
      return null;
    }
    for (Property property : entity.getProperties()) {
      if (isParentReference(property)) {
        String id = idValue(body, property.getName());
        if (id != null) {
          Entity parentEntity = property.getTargetEntity();
          Currency currency = currencyOf(parentEntity,
              OBDal.getInstance().get(parentEntity.getName(), id));
          if (currency != null) {
            return currency;
          }
        }
      }
    }
    return null;
  }

  /** The currency of the parent tab's record {@code parentId}. */
  private static Currency currencyOfParentId(Tab adTab, String parentId) {
    if (StringUtils.isBlank(parentId)) {
      return null;
    }
    Tab parentTab = KernelUtils.getInstance().getParentTab(adTab);
    if (parentTab == null || parentTab.getTable() == null) {
      return null;
    }
    Entity parentEntity = ModelProvider.getInstance()
        .getEntityByTableId(parentTab.getTable().getId());
    if (parentEntity == null) {
      return null;
    }
    return currencyOf(parentEntity, OBDal.getInstance().get(parentEntity.getName(), parentId));
  }

  /** The first non-null currency-typed property value of {@code stored}. */
  private static Currency currencyOf(Entity entity, BaseOBObject stored) {
    if (entity == null || stored == null) {
      return null;
    }
    for (Property property : entity.getProperties()) {
      if (isCurrencyReference(property)) {
        Object value = stored.get(property.getName());
        if (value instanceof Currency) {
          return (Currency) value;
        }
      }
    }
    return null;
  }

  /** A usable id held under {@code key}: not blank, not {@code "null"}, not a batch reference. */
  private static String idValue(JSONObject body, String key) {
    if (!body.has(key) || body.isNull(key)) {
      return null;
    }
    String value = body.optString(key, null);
    if (StringUtils.isBlank(value) || "null".equals(value)
        || value.startsWith(BatchService.REF_PREFIX)) {
      return null;
    }
    return value;
  }
}
