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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.Property;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.dal.service.OBDal;

import com.etendoerp.go.usage.quota.QuotaClientCache;
import com.etendoerp.go.usage.quota.QuotaEvaluation;
import com.etendoerp.go.usage.quota.QuotaWriteGuard;

/**
 * ETP-5051 — the plan-quota check of a NEO <b>action</b> that creates records itself (a document
 * generated from another one, a clone, a conversion), the counterpart of
 * {@link NeoQuotaWriteGate} for creates that do not go through the CRUD POST.
 *
 * <p>The creator calls {@link #requireAllowed} right after it saved the rows it created and
 * <b>before anything irreversible</b>: before a completion or posting that commits
 * ({@code ProcessInvoiceUtil} does), and before the response is built. The rows are flushed and
 * tested by the same {@link QuotaWriteGuard} the CRUD gate uses — the full HQL restriction against
 * the inserted row, the counted window, the advisory lock and the live count — so a restriction
 * may join ({@code e.transactionDocument.documentCategory = 'ARI'}).
 *
 * <p>A refusal is thrown as {@link Refused}, which carries the response: 403
 * {@code QUOTA_EXCEEDED} with the {@code quota} body, exactly the CRUD gate's envelope, or
 * {@value NeoQuotaWriteGate#CHECK_FAILED_STATUS} {@code QUOTA_CHECK_FAILED} when the check failed on
 * the database. A handler with its own catch-all answers {@link Refused#toResponse()} from a
 * {@code catch (Refused e)} placed before it; {@link NeoExtensionDispatcher} maps a {@link Refused}
 * that escapes a handler, wrapped or not ({@link #responseFor}). Either way the transaction is
 * rolled back, so nothing of the action is left behind; inside a batch the batch rolls back.
 *
 * <p>Failure handling is the CRUD gate's: an unreadable client quota entry, or a check failure that
 * poisoned the transaction, fails closed; a failure raised before any SQL ran (an invalid
 * restriction fragment) is logged and the rows stand.
 *
 * <p>Structural only: which rows are checked is decided by the catalog — the created row's DAL
 * entity against the client's cached {@code block} quotas — never by an entity or spec name. A
 * create of an entity no {@code block} quota of the client counts costs a map lookup and no query.
 */
public final class NeoQuotaCreateCheck {

  private static final Logger log = LogManager.getLogger(NeoQuotaCreateCheck.class);

  private NeoQuotaCreateCheck() {
  }

  /**
   * Checks rows a NEO action just created and saved.
   *
   * @param created the created rows; nulls and rows without an id are ignored
   * @throws Refused when a row would take its tenant over a {@code block} quota, or when the check
   *     failed in a way that lost the transaction
   */
  public static void requireAllowed(BaseOBObject... created) {
    requireAllowed(created == null ? List.of() : Arrays.asList(created));
  }

  /**
   * Checks rows a NEO action just created and saved.
   *
   * @param created the created rows; nulls and rows without an id are ignored
   * @throws Refused when a row would take its tenant over a {@code block} quota, or when the check
   *     failed in a way that lost the transaction
   */
  public static void requireAllowed(Collection<? extends BaseOBObject> created) {
    requireAllowed(created, QuotaWriteGuard.getDefault());
  }

  /**
   * Checks a document a NEO action just created and saved: the header and every row of its child
   * collections (its lines), all of which are new because the header is.
   *
   * <p>The children are found from the DAL model — the header entity's one-to-many child
   * properties — so the creator needs no list of its lines, and a child entity no {@code block}
   * quota counts costs no query. Not for rows added to an EXISTING header: its older children
   * would be charged again; pass those rows to {@link #requireAllowed(Collection)} instead.
   *
   * @param header the created header, saved in the current transaction
   * @throws Refused when a row would take its tenant over a {@code block} quota, or when the check
   *     failed in a way that lost the transaction
   */
  public static void requireAllowedDocument(BaseOBObject header) {
    requireAllowedDocument(header, QuotaWriteGuard.getDefault());
  }

  static void requireAllowedDocument(BaseOBObject header, QuotaWriteGuard guard) {
    Map<String, List<String>> idsByEntity = idsByEntity(Collections.singletonList(header));
    if (idsByEntity.isEmpty()) {
      return;
    }
    String headerId = header.getId().toString();
    Map<String, QuotaClientCache.Entry> checked = checkedEntities(guard, idsByEntity.keySet());
    Map<Property, QuotaClientCache.Entry> checkedChildren = new LinkedHashMap<>();
    for (Property child : childCollections(header.getEntity())) {
      String childEntity = child.getTargetEntity().getName();
      entryIfChecked(guard, childEntity).ifPresent(entry -> checkedChildren.put(child, entry));
    }
    if (checked.isEmpty() && checkedChildren.isEmpty()) {
      return;
    }
    flush();
    for (Map.Entry<Property, QuotaClientCache.Entry> child : checkedChildren.entrySet()) {
      String childEntity = child.getKey().getTargetEntity().getName();
      // Merge, never replace: a child collection may target the header's own entity, or two
      // collections the same entity, and each must keep the ids the others contributed.
      idsByEntity.computeIfAbsent(childEntity, name -> new ArrayList<>())
          .addAll(childIds(child.getKey(), headerId));
      checked.put(childEntity, child.getValue());
    }
    checkAll(guard, checked, idsByEntity);
  }

  /**
   * Checks rows of one entity a NEO action just added, by id — for rows added to an existing
   * document (imported lines), where {@link #requireAllowedDocument} would charge the older
   * children again.
   *
   * @param entityName the DAL entity name of the rows
   * @param ids the ids of the created rows, saved in the current transaction
   * @throws Refused when a row would take its tenant over a {@code block} quota, or when the check
   *     failed in a way that lost the transaction
   */
  public static void requireAllowed(String entityName, Collection<String> ids) {
    requireAllowed(entityName, ids, QuotaWriteGuard.getDefault());
  }

  static void requireAllowed(String entityName, Collection<String> ids, QuotaWriteGuard guard) {
    if (StringUtils.isBlank(entityName) || ids == null || ids.isEmpty()) {
      return;
    }
    Map<String, List<String>> idsByEntity = new LinkedHashMap<>();
    idsByEntity.put(entityName, new ArrayList<>(ids));
    Map<String, QuotaClientCache.Entry> checked = checkedEntities(guard, idsByEntity.keySet());
    if (checked.isEmpty()) {
      return;
    }
    flush();
    checkAll(guard, checked, idsByEntity);
  }

  static void requireAllowed(Collection<? extends BaseOBObject> created, QuotaWriteGuard guard) {
    Map<String, List<String>> idsByEntity = idsByEntity(created);
    Map<String, QuotaClientCache.Entry> checked = checkedEntities(guard, idsByEntity.keySet());
    if (checked.isEmpty()) {
      return;
    }
    flush();
    checkAll(guard, checked, idsByEntity);
  }

  /** The entities among {@code entityNames} a {@code block} quota of the client counts. */
  private static Map<String, QuotaClientCache.Entry> checkedEntities(QuotaWriteGuard guard,
      Collection<String> entityNames) {
    Map<String, QuotaClientCache.Entry> checked = new LinkedHashMap<>();
    for (String entityName : entityNames) {
      entryIfChecked(guard, entityName).ifPresent(entry -> checked.put(entityName, entry));
    }
    return checked;
  }

  private static void checkAll(QuotaWriteGuard guard, Map<String, QuotaClientCache.Entry> checked,
      Map<String, List<String>> idsByEntity) {
    for (Map.Entry<String, QuotaClientCache.Entry> entry : checked.entrySet()) {
      List<String> ids = idsByEntity.get(entry.getKey());
      if (ids != null && !ids.isEmpty()) {
        check(guard, entry.getValue(), entry.getKey(), ids);
      }
    }
  }

  /**
   * The guard matches the inserted rows with HQL on this session, so they must be in the database:
   * the session runs with {@code FlushMode.COMMIT}, a query never flushes on its own.
   */
  private static void flush() {
    OBDal.getInstance().flush();
  }

  /** The header entity's one-to-many child collections: the properties its lines hang from. */
  static List<Property> childCollections(Entity entity) {
    List<Property> children = new ArrayList<>();
    if (entity == null) {
      return children;
    }
    for (Property property : entity.getProperties()) {
      if (property.isOneToMany() && property.isChild() && property.getReferencedProperty() != null
          && property.getTargetEntity() != null) {
        children.add(property);
      }
    }
    return children;
  }

  /** The ids of the rows of one child collection of the header, read after the flush. */
  private static List<String> childIds(Property childCollection, String headerId) {
    String hql = "select e.id from " + childCollection.getTargetEntity().getName() + " e where e."
        + childCollection.getReferencedProperty().getName() + ".id = :headerId";
    return OBDal.getInstance().getSession().createQuery(hql, String.class)
        .setParameter("headerId", headerId)
        .list();
  }

  private static Optional<QuotaClientCache.Entry> entryIfChecked(QuotaWriteGuard guard,
      String entityName) {
    try {
      return guard.entryIfChecked(entityName);
    } catch (RuntimeException e) {
      log.error("[QUOTA] Could not read the quotas of the request's client for a created {}; "
          + "nothing is saved ({})", entityName, NeoQuotaWriteGate.CHECK_FAILED_CODE, e);
      throw new Refused(NeoQuotaWriteGate.checkFailedResponse(), e);
    }
  }

  private static void check(QuotaWriteGuard guard, QuotaClientCache.Entry entry,
      String entityName, List<String> ids) {
    Optional<QuotaEvaluation> refusal;
    try {
      refusal = guard.refusalFor(entry, entityName, ids);
    } catch (RuntimeException e) {
      if (NeoQuotaWriteGate.isTransactionPoisoned(e)) {
        log.error("[QUOTA] The quota check of a created {} failed on the database; the "
            + "transaction is lost, so nothing is saved ({})", entityName,
            NeoQuotaWriteGate.CHECK_FAILED_CODE, e);
        throw new Refused(NeoQuotaWriteGate.checkFailedResponse(), e);
      }
      log.error("[QUOTA] The quota check of a created {} failed before reaching the database; "
          + "the create stands", entityName, e);
      return;
    }
    if (refusal.isPresent()) {
      log.info("[QUOTA] Refused an action creating {}: {}", entityName, refusal.get());
      throw new Refused(NeoQuotaWriteGate.refusalResponse(refusal.get()), null);
    }
  }

  private static Map<String, List<String>> idsByEntity(Collection<? extends BaseOBObject> rows) {
    Map<String, List<String>> ids = new LinkedHashMap<>();
    if (rows == null) {
      return ids;
    }
    for (BaseOBObject row : rows) {
      Object id = row == null ? null : row.getId();
      if (id != null && StringUtils.isNotBlank(id.toString())
          && StringUtils.isNotBlank(row.getEntityName())) {
        ids.computeIfAbsent(row.getEntityName(), name -> new ArrayList<>()).add(id.toString());
      }
    }
    return ids;
  }

  /**
   * The response of a {@link Refused} anywhere in {@code error}'s cause chain, with the
   * transaction rolled back ({@link Refused#toResponse()}); null when there is none. The mapper for
   * a refusal that escaped a handler, or that a handler wrapped.
   *
   * @param error the handler's failure
   * @return the refusal's response, or null
   */
  public static NeoResponse responseFor(Throwable error) {
    Throwable cause = error;
    int depth = 0;
    while (cause != null && depth < 32) {
      if (cause instanceof Refused) {
        return ((Refused) cause).toResponse();
      }
      cause = cause.getCause() == cause ? null : cause.getCause();
      depth++;
    }
    return null;
  }

  /**
   * A NEO action's created rows were refused by a plan quota, or their check failed on the
   * database. Unchecked on purpose: it crosses the creators' own signatures to the handler (or the
   * dispatcher) that answers it.
   */
  public static final class Refused extends RuntimeException {

    private static final long serialVersionUID = 1L;
    private static final String DEFAULT_MESSAGE = "Quota refusal";

    private final transient NeoResponse response;

    Refused(NeoResponse response, Throwable cause) {
      super(messageOf(response), cause);
      this.response = response;
    }

    private static String messageOf(NeoResponse response) {
      if (response == null || response.getBody() == null) {
        return DEFAULT_MESSAGE;
      }
      JSONObject error = response.getBody().optJSONObject("error");
      return error == null ? response.getBody().toString()
          : error.optString("message", DEFAULT_MESSAGE);
    }

    /**
     * The response to answer, after rolling back what the action wrote: the whole transaction,
     * unless a batch owns it (the batch then fails the operation and rolls everything back).
     *
     * @return the refusal (403 {@code QUOTA_EXCEEDED}) or the failed check
     *     ({@value NeoQuotaWriteGate#CHECK_FAILED_STATUS} {@code QUOTA_CHECK_FAILED})
     */
    public NeoResponse toResponse() {
      if (!BatchService.callerOwnsTransaction()) {
        OBDal.getInstance().rollbackAndClose();
      }
      return response;
    }

    /**
     * The response without touching the transaction, for callers and tests that roll back
     * themselves.
     *
     * @return the response
     */
    public NeoResponse response() {
      return response;
    }
  }
}
