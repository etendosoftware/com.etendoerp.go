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

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.base.model.Property;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.model.ad.ui.Tab;
import org.openbravo.model.financialmgmt.accounting.coa.AccountingCombination;
import org.openbravo.model.financialmgmt.accounting.coa.AcctSchema;
import org.openbravo.model.financialmgmt.accounting.coa.ElementValue;

import com.etendoerp.go.schemaforge.MessageKeyedException;
import com.etendoerp.go.schemaforge.NeoActionRecordGuard;
import com.etendoerp.go.schemaforge.NeoContext;
import com.etendoerp.go.schemaforge.NeoEndpointType;
import com.etendoerp.go.schemaforge.NeoFieldFilter;
import com.etendoerp.go.schemaforge.NeoResponse;

/**
 * ETP-5693: the write side of {@link PostingAccountCombinations}. An account combination written
 * to an accounting field must be active, with an active posting (non-summary) account, and belong
 * to the row's ledger; and the ledger of an existing row cannot change.
 *
 * <ul>
 *   <li>R1 posting rule — {@code ETGO_AccountIsSummary} / {@code ETGO_AccountIsInactive};</li>
 *   <li>R2 a value equal to the stored one is not checked, so pre-existing rows stay saveable;</li>
 *   <li>R3 an id that does not load for the caller is left to the default path;</li>
 *   <li>R4 {@code null} (clearing) is allowed;</li>
 *   <li>R5 the combination belongs to the ledger the customization's {@link LedgerResolver}
 *       names — {@code ETGO_AccountWrongLedger};</li>
 *   <li>R6 on update, the resolver's ledger property cannot change — {@code ETGO_LedgerNotUpdatable}.</li>
 * </ul>
 *
 * <p>Used from the CRUD pre-hook of each accounting customization ({@link #checkWrite}) and from
 * the aggregate handlers that write combinations themselves ({@link #requirePosting}). The util
 * never names a business property: the customization chooses which one carries the ledger.
 */
public final class PostingAccountGuard {

  private static final Logger log = LogManager.getLogger(PostingAccountGuard.class);

  static final String MSG_SUMMARY = "ETGO_AccountIsSummary";
  static final String MSG_INACTIVE = "ETGO_AccountIsInactive";
  static final String MSG_WRONG_LEDGER = "ETGO_AccountWrongLedger";
  static final String MSG_LEDGER_NOT_UPDATABLE = "ETGO_LedgerNotUpdatable";

  private static final String DATA_ENVELOPE = "data";

  private PostingAccountGuard() {
  }

  /**
   * Names the ledger the combinations of one write must belong to.
   */
  public interface LedgerResolver {

    /**
     * @param context the write being checked
     * @param stored  the stored record on update, {@code null} on create
     * @param body    the submitted values keyed by DAL property name
     * @return the ledger, or {@code null} to skip the ledger check (R5)
     */
    AcctSchema resolve(NeoContext context, BaseOBObject stored, Map<String, Object> body);

    /**
     * @return the DAL property that carries the row's ledger and cannot change on update (R6), or
     *         {@code null} when there is none
     */
    default String ledgerProperty() {
      return null;
    }
  }

  /**
   * The resolver for a row that carries its own ledger in {@code property}: the stored value on
   * update (R6 keeps it from changing in the same request), the submitted one on create.
   *
   * @param property the DAL property of the row's {@link AcctSchema}
   * @return the resolver
   */
  public static LedgerResolver ledgerOfRow(String property) {
    return new LedgerResolver() {
      @Override
      public AcctSchema resolve(NeoContext context, BaseOBObject stored, Map<String, Object> body) {
        if (stored != null) {
          return (AcctSchema) stored.get(property);
        }
        Object id = body.get(property);
        return id instanceof String ? NeoActionRecordGuard.loadOwned(AcctSchema.class, (String) id) : null;
      }

      @Override
      public String ledgerProperty() {
        return property;
      }
    };
  }

  /**
   * Refuses a combination that cannot receive postings or belongs to another ledger.
   *
   * @param combo  the combination being written; must not be {@code null}
   * @param ledger the row's ledger; {@code null} skips the ledger check
   * @param field  the field as the caller named it, for the message
   * @throws MessageKeyedException with the first rule that fails
   */
  public static void requirePosting(AccountingCombination combo, AcctSchema ledger, String field) {
    ElementValue account = combo.getAccount();
    if (account != null && Boolean.TRUE.equals(account.isSummaryLevel())) {
      throw refusal(MSG_SUMMARY, field, account);
    }
    if (!Boolean.TRUE.equals(combo.isActive())
        || (account != null && !Boolean.TRUE.equals(account.isActive()))) {
      throw refusal(MSG_INACTIVE, field, account);
    }
    if (ledger != null && combo.getAccountingSchema() != null
        && !ledger.getId().equals(combo.getAccountingSchema().getId())) {
      throw refusal(MSG_WRONG_LEDGER, field, account);
    }
  }

  /**
   * CRUD pre-hook check of one create/update.
   *
   * <p>Total on purpose: anything other than a business refusal is logged and skipped, never
   * thrown, or the hook pipeline would answer every write of the entity with a 500.
   *
   * @param context  the hook context
   * @param ledgerOf names the row's ledger
   * @return a 400 with the refusal's {@code messageKeys}, or {@code null} to continue
   */
  public static NeoResponse checkWrite(NeoContext context, LedgerResolver ledgerOf) {
    if (context.getEndpointType() != NeoEndpointType.CRUD || !isWrite(context.getHttpMethod())
        || context.getRequestBody() == null || context.getAdTab() == null) {
      return null;
    }
    // Admin mode only spares the role's entity-access check: every id goes through loadOwned,
    // which applies the caller's readable clients and organizations.
    OBContext.setAdminMode(true);
    try {
      check(context, ledgerOf);
      return null;
    } catch (MessageKeyedException e) {
      return NeoResponse.error(400, e);
    } catch (Exception e) {
      // Greppable marker: a skip here means a write went unchecked.
      log.warn("posting-guard-skipped: posting-account check skipped for {}/{}: {}",
          context.getSpecName(), context.getEntityName(), e.getMessage(), e);
      return null;
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  private static void check(NeoContext context, LedgerResolver ledgerOf) throws Exception {
    Tab adTab = context.getAdTab();
    String dalEntityName = adTab.getTable().getName();
    Entity entity = ModelProvider.getInstance().getEntity(dalEntityName);
    Map<String, String> apiKeys = new LinkedHashMap<>();
    Map<String, Object> body = byProperty(context, entity, dalEntityName, apiKeys);
    BaseOBObject stored = loadStored(entity, context.getRecordId());

    String ledgerProperty = ledgerOf.ledgerProperty();
    if (stored != null && ledgerProperty != null && body.containsKey(ledgerProperty)
        && changes(body.get(ledgerProperty), stored.get(ledgerProperty))) {
      throw new MessageKeyedException(MSG_LEDGER_NOT_UPDATABLE,
          Map.of("field", apiKeys.get(ledgerProperty)));
    }

    AcctSchema ledger = null;
    boolean ledgerResolved = false;
    for (Map.Entry<String, Object> entry : body.entrySet()) {
      Property property = entity.getProperty(entry.getKey(), false);
      if (!isCombination(property) || !(entry.getValue() instanceof String)) {
        continue;
      }
      String id = (String) entry.getValue();
      if (stored != null && !changes(id, stored.get(entry.getKey()))) {
        continue; // R2
      }
      AccountingCombination combo = NeoActionRecordGuard.loadOwned(AccountingCombination.class, id);
      if (combo == null) {
        continue; // R3
      }
      if (!ledgerResolved) {
        ledger = ledgerOf.resolve(context, stored, body);
        ledgerResolved = true;
      }
      requirePosting(combo, ledger, apiKeys.get(entry.getKey()));
    }
  }

  /** The submitted values keyed by DAL property, unwrapped and renamed as NeoFieldFilter does. */
  private static Map<String, Object> byProperty(NeoContext context, Entity entity, String dalEntityName,
      Map<String, String> apiKeys) throws Exception {
    JSONObject raw = context.getRequestBody();
    if (raw.optJSONObject(DATA_ENVELOPE) != null) {
      raw = raw.getJSONObject(DATA_ENVELOPE);
    }
    NeoFieldFilter filter = context.getSfEntity() == null ? null
        : NeoFieldFilter.forEntity(context.getSfEntity(), dalEntityName);
    Map<String, Object> body = new LinkedHashMap<>();
    Iterator<?> keys = raw.keys();
    while (keys.hasNext()) {
      String key = (String) keys.next();
      String name = filter == null ? key : filter.resolveWritablePropName(key);
      if (entity.getProperty(name, false) != null) {
        body.put(name, raw.isNull(key) ? null : idOf(raw.get(key)));
        apiKeys.put(name, key);
      }
    }
    return body;
  }

  /** Core also accepts an FK as {@code {"id": ...}}; checking only strings would let that shape through. */
  private static Object idOf(Object value) {
    return value instanceof JSONObject ? ((JSONObject) value).optString("id", null) : value;
  }

  @SuppressWarnings("unchecked")
  private static BaseOBObject loadStored(Entity entity, String recordId) {
    if (StringUtils.isBlank(recordId)) {
      return null;
    }
    return NeoActionRecordGuard.loadOwned((Class<? extends BaseOBObject>) entity.getMappingClass(), recordId);
  }

  private static boolean isCombination(Property property) {
    return property != null && property.getTargetEntity() != null
        && AccountingCombination.ENTITY_NAME.equals(property.getTargetEntity().getName());
  }

  private static boolean changes(Object submitted, Object stored) {
    String storedId = stored instanceof BaseOBObject ? (String) ((BaseOBObject) stored).getId() : null;
    String submittedId = submitted instanceof String ? (String) submitted : null;
    return !StringUtils.equals(submittedId, storedId);
  }

  private static boolean isWrite(String method) {
    return "POST".equalsIgnoreCase(method) || "PUT".equalsIgnoreCase(method)
        || "PATCH".equalsIgnoreCase(method);
  }

  private static MessageKeyedException refusal(String key, String field, ElementValue account) {
    String label = account == null ? "" : account.getSearchKey() + " - " + account.getName();
    return new MessageKeyedException(key, Map.of("field", field, "account", label));
  }
}
