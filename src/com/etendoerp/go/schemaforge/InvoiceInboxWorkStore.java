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

package com.etendoerp.go.schemaforge;

import java.util.ArrayList;
import java.util.List;

import org.hibernate.Session;
import org.openbravo.dal.service.OBDal;

/**
 * The SQL the inbox worker runs on {@code ETGO_INCOMING_DOCUMENT} (ETP-6048): the atomic claim with
 * its lease, the write-once store of the extraction, the terminal error, and the sweep queries. Each
 * method is a transaction of its own, committed and closed here, so the worker never holds a
 * connection while it waits on the extraction service.
 *
 * <p>Native SQL on purpose: the lease compares against the database clock ({@code now()}), the
 * only clock the nodes of a cluster share, and every write is conditioned on
 * {@code STATUS = 'PROCESSING'} so a row discarded meanwhile is never overwritten. An instance, not
 * statics, so the processor is tested with a fake.</p>
 */
class InvoiceInboxWorkStore {

  private static final String P_ID = "id";
  private static final String P_MAX = "max";
  private static final String P_CLIENT = "client";
  private static final String P_USER = "user";
  private static final String UNLEASED = "(processing_lease IS NULL OR processing_lease < now())";

  /** What the worker needs to know about a row it has just claimed. */
  static final class Claimed {
    private final String userId;
    private final String clientId;
    private final String orgId;
    private final String targetType;
    private final long attempts;
    private final String extractedData;

    Claimed(String userId, String clientId, String orgId, String targetType, long attempts,
        String extractedData) {
      this.userId = userId;
      this.clientId = clientId;
      this.orgId = orgId;
      this.targetType = targetType;
      this.attempts = attempts;
      this.extractedData = extractedData;
    }

    String userId() {
      return userId;
    }

    String clientId() {
      return clientId;
    }

    String orgId() {
      return orgId;
    }

    String targetType() {
      return targetType;
    }

    long attempts() {
      return attempts;
    }

    /** The stored extraction, {@code null} when the OCR has not run (or its answer was not kept). */
    String extractedData() {
      return extractedData;
    }
  }

  /**
   * Takes the row for this pass: sets the lease and counts the pass, only when it is still
   * {@code PROCESSING}, unleased (or the lease expired) and under the pass cap.
   *
   * @param id           the row id
   * @param leaseMinutes how long the lease lasts
   * @param maxAttempts  the cap on passes
   * @return what the worker needs, {@code null} when another node has it or it is no longer pending
   */
  Claimed claim(String id, int leaseMinutes, int maxAttempts) {
    Session session = OBDal.getInstance().getSession();
    int updated = session.createNativeQuery("UPDATE etgo_incoming_document SET processing_lease ="
        + " now() + (:minutes * interval '1 minute'), processing_attempts = processing_attempts + 1"
        + " WHERE etgo_incoming_document_id = :id AND status = 'PROCESSING' AND " + UNLEASED
        + " AND processing_attempts < :max")
        .setParameter("minutes", leaseMinutes).setParameter(P_ID, id).setParameter(P_MAX, maxAttempts)
        .executeUpdate();
    if (updated == 0) {
      OBDal.getInstance().rollbackAndClose();
      return null;
    }
    Object[] row = (Object[]) session.createNativeQuery("SELECT createdby, ad_client_id, ad_org_id,"
        + " target_type, processing_attempts, extracted_data FROM etgo_incoming_document"
        + " WHERE etgo_incoming_document_id = :id").setParameter(P_ID, id).uniqueResult();
    OBDal.getInstance().commitAndClose();
    return new Claimed((String) row[0], (String) row[1], (String) row[2], (String) row[3],
        ((Number) row[4]).longValue(), (String) row[5]);
  }

  /**
   * The role the worker acts under: the user's default role when it belongs to the client, else
   * any active role of the user in that client. Access is not re-validated: the upload already was.
   *
   * @param userId   the user
   * @param clientId the client of the row
   * @return the role id, {@code null} when the user has none usable
   */
  String roleFor(String userId, String clientId) {
    Session session = OBDal.getInstance().getSession();
    try {
      List<?> roles = session.createNativeQuery("SELECT r.ad_role_id FROM ad_user u JOIN ad_role r"
          + " ON r.ad_role_id = u.default_ad_role_id WHERE u.ad_user_id = :user AND r.isactive = 'Y'"
          + " AND r.ad_client_id = :client").setParameter(P_USER, userId)
          .setParameter(P_CLIENT, clientId).list();
      if (roles.isEmpty()) {
        roles = session.createNativeQuery("SELECT r.ad_role_id FROM ad_user_roles ur JOIN ad_role r"
            + " ON r.ad_role_id = ur.ad_role_id WHERE ur.ad_user_id = :user AND ur.isactive = 'Y'"
            + " AND r.isactive = 'Y' AND r.ad_client_id = :client ORDER BY r.name, r.ad_role_id"
            + " LIMIT 1").setParameter(P_USER, userId).setParameter(P_CLIENT, clientId).list();
      }
      return roles.isEmpty() ? null : (String) roles.get(0);
    } finally {
      OBDal.getInstance().rollbackAndClose();
    }
  }

  /** Stores the extraction once: only while the row is {@code PROCESSING} and has none. */
  void storeExtracted(String id, String extractedData) {
    OBDal.getInstance().getSession().createNativeQuery("UPDATE etgo_incoming_document SET"
        + " extracted_data = :data WHERE etgo_incoming_document_id = :id AND status = 'PROCESSING'"
        + " AND extracted_data IS NULL").setParameter("data", extractedData)
        .setParameter(P_ID, id).executeUpdate();
    OBDal.getInstance().commitAndClose();
  }

  /** Ends the row in {@code ERROR} with a code, only while it is still {@code PROCESSING}. */
  void fail(String id, String errorCode) {
    OBDal.getInstance().getSession().createNativeQuery("UPDATE etgo_incoming_document SET status ="
        + " 'ERROR', error_code = :code, processing_lease = NULL, processed_at = now()"
        + " WHERE etgo_incoming_document_id = :id AND status = 'PROCESSING'")
        .setParameter("code", errorCode).setParameter(P_ID, id).executeUpdate();
    OBDal.getInstance().commitAndClose();
  }

  /**
   * After an unexpected failure: ends the row as {@code SERVICE_FAILURE} when this was its last
   * allowed pass, else releases the lease so the sweeper retries without waiting for it to expire.
   */
  void failOrRelease(String id, int maxAttempts) {
    Session session = OBDal.getInstance().getSession();
    int failed = session.createNativeQuery("UPDATE etgo_incoming_document SET status = 'ERROR',"
        + " error_code = 'SERVICE_FAILURE', processing_lease = NULL, processed_at = now()"
        + " WHERE etgo_incoming_document_id = :id AND status = 'PROCESSING'"
        + " AND processing_attempts >= :max").setParameter(P_ID, id)
        .setParameter(P_MAX, maxAttempts).executeUpdate();
    if (failed == 0) {
      session.createNativeQuery("UPDATE etgo_incoming_document SET processing_lease = NULL"
          + " WHERE etgo_incoming_document_id = :id AND status = 'PROCESSING'")
          .setParameter(P_ID, id).executeUpdate();
    }
    OBDal.getInstance().commitAndClose();
  }

  /**
   * Locks the row for the final write and says whether it is still {@code PROCESSING}. The lock
   * lasts until the caller's transaction ends.
   */
  boolean lockIfProcessing(String id) {
    List<?> status = OBDal.getInstance().getSession().createNativeQuery("SELECT status FROM"
        + " etgo_incoming_document WHERE etgo_incoming_document_id = :id FOR UPDATE")
        .setParameter(P_ID, id).list();
    return !status.isEmpty() && "PROCESSING".equals(status.get(0));
  }

  /** Ends, as {@code SERVICE_FAILURE}, the rows that ran out of passes and have no live lease. */
  int failExhausted(int maxAttempts) {
    int count = OBDal.getInstance().getSession().createNativeQuery("UPDATE etgo_incoming_document"
        + " SET status = 'ERROR', error_code = 'SERVICE_FAILURE', processing_lease = NULL,"
        + " processed_at = now() WHERE status = 'PROCESSING' AND processing_attempts >= :max AND "
        + UNLEASED).setParameter(P_MAX, maxAttempts).executeUpdate();
    OBDal.getInstance().commitAndClose();
    return count;
  }

  /**
   * Ids of the rows waiting for a worker: {@code PROCESSING}, no live lease, passes left, and older
   * than a minute (a fresh row is already on its way through the node that took the upload).
   */
  List<String> stalledIds(int maxAttempts, int limit) {
    try {
      List<?> ids = OBDal.getInstance().getSession().createNativeQuery("SELECT"
          + " etgo_incoming_document_id FROM etgo_incoming_document WHERE status = 'PROCESSING'"
          + " AND processing_attempts < :max AND " + UNLEASED
          + " AND created < now() - interval '1 minute' ORDER BY created LIMIT :limit")
          .setParameter(P_MAX, maxAttempts).setParameter("limit", limit).list();
      List<String> out = new ArrayList<>();
      for (Object id : ids) {
        out.add((String) id);
      }
      return out;
    } finally {
      OBDal.getInstance().rollbackAndClose();
    }
  }
}
