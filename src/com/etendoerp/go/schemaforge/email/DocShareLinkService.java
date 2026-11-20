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

package com.etendoerp.go.schemaforge.email;

import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hibernate.Session;
import org.openbravo.base.exception.OBException;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;

import com.etendoerp.go.schemaforge.email.DocumentDownloadTokenService.Claims;

/**
 * Short, opaque, revocable share links for document downloads (ETP-5035): one
 * {@code ETGO_Doc_Share_Link} row per link, addressed by a random code instead of a signed token
 * that carries its payload in clear.
 *
 * <p>The code is drawn from a {@link SecureRandom}, never from a sequence, so a link cannot be
 * guessed from another. Rows are native SQL, like the rest of the anonymous download path, which
 * runs without a session and so cannot rely on the DAL client filter.</p>
 */
public final class DocShareLinkService {

  /** Characters of a share code: 62 symbols, 10 positions, about 60 bits. */
  static final String ALPHABET =
      "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
  static final int CODE_LENGTH = 10;
  /** Rows expired for longer than this are deleted by the opportunistic purge. */
  static final long PURGE_RETENTION_DAYS = 30L;

  private static final Logger log = LogManager.getLogger(DocShareLinkService.class);
  private static final SecureRandom RANDOM = new SecureRandom();
  private static final int MAX_CODE_ATTEMPTS = 5;
  private static final long PURGE_INTERVAL_MILLIS = TimeUnit.HOURS.toMillis(1);
  private static final String SYSTEM_ORG_ID = "0";
  private static final String FALLBACK_USER_ID = "100";
  private static final AtomicLong lastPurgeMillis = new AtomicLong();

  private static final String CODE_EXISTS_SQL =
      "SELECT 1 FROM etgo_doc_share_link WHERE share_code = :code";
  private static final String INSERT_SQL = "INSERT INTO etgo_doc_share_link"
      + " (etgo_doc_share_link_id, ad_client_id, ad_org_id, isactive, created, createdby,"
      + " updated, updatedby, share_code, contract_name, spec_name, record_id, idempotency_key,"
      + " expires_at, revoked, open_count)"
      + " VALUES (:id, :client, :org, 'Y', :now, :user, :now, :user, :code, :contract, :spec,"
      + " :record, :idem, :expires, 'N', 0)";
  private static final String SELECT_SQL = "SELECT contract_name, spec_name, record_id,"
      + " ad_client_id, idempotency_key, expires_at FROM etgo_doc_share_link"
      + " WHERE share_code = :code AND revoked = 'N' AND expires_at >= :now";
  private static final String OPEN_SQL = "UPDATE etgo_doc_share_link"
      + " SET open_count = open_count + 1, first_opened_at = COALESCE(first_opened_at, :now),"
      + " updated = :now WHERE share_code = :code";
  private static final String REVOKE_SQL = "UPDATE etgo_doc_share_link"
      + " SET revoked = 'Y', updated = :now WHERE share_code = :code AND revoked = 'N'";
  private static final int PURGE_BATCH_SIZE = 500;
  private static final String PURGE_SQL = "DELETE FROM etgo_doc_share_link"
      + " WHERE etgo_doc_share_link_id IN (SELECT etgo_doc_share_link_id FROM etgo_doc_share_link"
      + " WHERE expires_at < :cutoff LIMIT " + PURGE_BATCH_SIZE + ")";

  private DocShareLinkService() {
  }

  /**
   * Whether a path segment is a share code rather than a signed token. Signed tokens always hold a
   * {@code .}, which is not in the code alphabet.
   *
   * @param value the segment after the download path
   * @return {@code true} for a well-formed share code
   */
  public static boolean isShareCode(String value) {
    if (value == null || value.length() != CODE_LENGTH) {
      return false;
    }
    for (int i = 0; i < value.length(); i++) {
      if (ALPHABET.indexOf(value.charAt(i)) < 0) {
        return false;
      }
    }
    return true;
  }

  /**
   * Draws a new random share code.
   *
   * @return {@value #CODE_LENGTH} characters of {@link #ALPHABET}
   */
  static String generateCode() {
    StringBuilder code = new StringBuilder(CODE_LENGTH);
    for (int i = 0; i < CODE_LENGTH; i++) {
      code.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
    }
    return code.toString();
  }

  /**
   * Inserts the row of a new link and returns its code.
   *
   * @param contractName email contract name
   * @param specName NEO document spec name
   * @param recordId document record id
   * @param clientId client that owns the document
   * @param idempotencyKey send the link belongs to
   * @param expiresAtMillis expiry instant
   * @return the share code
   */
  static String create(String contractName, String specName, String recordId, String clientId,
      String idempotencyKey, long expiresAtMillis) {
    Session session = OBDal.getInstance().getSession();
    // A failed INSERT aborts the whole PostgreSQL transaction, which is the email send's own, so
    // it runs behind a savepoint: the caller can fall back to a signed token and carry on.
    String code = inSavepoint(session, () -> insertRow(session, contractName, specName,
        recordId, clientId, idempotencyKey, expiresAtMillis));
    purgeExpiredThrottled(session, System.currentTimeMillis());
    return code;
  }

  private static String insertRow(Session session, String contractName, String specName,
      String recordId, String clientId, String idempotencyKey, long expiresAtMillis) {
    Timestamp now = new Timestamp(System.currentTimeMillis());
    String user = currentUserId();
    String code = uniqueCode(session);
    session.createNativeQuery(INSERT_SQL)
        .setParameter("id", newId())
        .setParameter("client", clientId)
        .setParameter("org", SYSTEM_ORG_ID)
        .setParameter("now", now)
        .setParameter("user", user)
        .setParameter("code", code)
        .setParameter("contract", contractName)
        .setParameter("spec", specName)
        .setParameter("record", recordId)
        .setParameter("idem", idempotencyKey)
        .setParameter("expires", new Timestamp(expiresAtMillis))
        .executeUpdate();
    return code;
  }

  /**
   * Runs a statement behind a savepoint and rolls back to it when it fails, leaving the caller's
   * transaction usable.
   */
  static <T> T inSavepoint(Session session, Supplier<T> action) {
    Savepoint[] savepoint = new Savepoint[1];
    session.doWork(connection -> savepoint[0] = connection.setSavepoint());
    try {
      T result = action.get();
      session.doWork(connection -> release(connection, savepoint[0]));
      return result;
    } catch (RuntimeException e) {
      try {
        session.doWork(connection -> connection.rollback(savepoint[0]));
      } catch (RuntimeException rollbackFailure) {
        e.addSuppressed(rollbackFailure);
      }
      throw e;
    }
  }

  private static void release(Connection connection, Savepoint savepoint)
      throws SQLException {
    if (savepoint != null) {
      connection.releaseSavepoint(savepoint);
    }
  }

  /**
   * Resolves a code to the claims of its link. A missing, expired or revoked code is the same
   * empty answer, so the caller cannot tell them apart.
   *
   * @param code the share code
   * @return the claims of a live link
   */
  public static Optional<Claims> resolve(String code) {
    if (!isShareCode(code)) {
      return Optional.empty();
    }
    List<?> rows = OBDal.getInstance().getSession().createNativeQuery(SELECT_SQL)
        .setParameter("code", code)
        .setParameter("now", new Timestamp(System.currentTimeMillis()))
        .list();
    if (rows.isEmpty()) {
      return Optional.empty();
    }
    Object[] row = (Object[]) rows.get(0);
    return Optional.of(Claims.ofShareLink((String) row[0], (String) row[1], (String) row[2],
        (String) row[3], (String) row[4], ((Timestamp) row[5]).getTime() / 1000L));
  }

  /**
   * Counts one open of a link: bumps the counter and stamps the first open.
   *
   * @param code the share code
   */
  public static void recordOpen(String code) {
    Session session = OBDal.getInstance().getSession();
    inSavepoint(session, () -> session.createNativeQuery(OPEN_SQL)
        .setParameter("now", new Timestamp(System.currentTimeMillis()))
        .setParameter("code", code)
        .executeUpdate());
  }

  /**
   * Revokes a link, keeping its row for audit.
   *
   * @param code the share code
   * @return {@code true} when a live link was revoked
   */
  public static boolean revoke(String code) {
    return OBDal.getInstance().getSession().createNativeQuery(REVOKE_SQL)
        .setParameter("now", new Timestamp(System.currentTimeMillis()))
        .setParameter("code", code)
        .executeUpdate() > 0;
  }

  /**
   * Deletes, in a bounded batch, the rows that expired before the retention window.
   *
   * @param session the session whose connection runs the delete
   * @param nowMillis the current instant
   * @return the number of rows deleted
   */
  static int purgeExpired(Session session, long nowMillis) {
    long cutoff = nowMillis - TimeUnit.DAYS.toMillis(PURGE_RETENTION_DAYS);
    return session.createNativeQuery(PURGE_SQL)
        .setParameter("cutoff", new Timestamp(cutoff))
        .executeUpdate();
  }

  private static void purgeExpiredThrottled(Session session, long nowMillis) {
    long last = lastPurgeMillis.get();
    if (nowMillis - last < PURGE_INTERVAL_MILLIS || !lastPurgeMillis.compareAndSet(last, nowMillis)) {
      return;
    }
    try {
      int purged = inSavepoint(session, () -> purgeExpired(session, nowMillis));
      if (purged > 0) {
        log.info("Purged {} expired document share links", purged);
      }
    } catch (RuntimeException e) {
      log.warn("Could not purge expired document share links: {}", e.getMessage(), e);
    }
  }

  private static String uniqueCode(Session session) {
    for (int attempt = 0; attempt < MAX_CODE_ATTEMPTS; attempt++) {
      String code = generateCode();
      if (session.createNativeQuery(CODE_EXISTS_SQL).setParameter("code", code).list().isEmpty()) {
        return code;
      }
    }
    throw new OBException("Could not generate a unique document share code");
  }

  private static String newId() {
    return UUID.randomUUID().toString().replace("-", "").toUpperCase();
  }

  private static String currentUserId() {
    OBContext context = OBContext.getOBContext();
    return context != null && context.getUser() != null ? context.getUser().getId()
        : FALLBACK_USER_ID;
  }
}
