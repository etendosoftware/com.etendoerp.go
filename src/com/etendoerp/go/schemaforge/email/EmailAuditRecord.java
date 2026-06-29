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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import org.apache.commons.lang3.StringUtils;
import org.openbravo.base.exception.OBException;
import org.openbravo.base.util.Check;

/**
 * Audit event emitted by the transactional email executor.
 */
public final class EmailAuditRecord {

  private final String contractName;
  private final String idempotencyKey;
  private final String tenantId;
  private final String userId;
  private final String recordId;
  private final String template;
  private final String recipient;
  private final String recipientDomain;
  private final int httpStatus;
  private final String status;
  private final String message;
  private final Integer providerStatus;
  private final boolean duplicate;
  private final long createdAtMillis;
  private final List<String> finalToRecipientHashes;
  private final List<String> finalCcRecipientHashes;
  private final List<String> finalRecipientDomains;

  private EmailAuditRecord(EmailSendContext context, String idempotencyKey, int httpStatus,
      String status, String message, Integer providerStatus, boolean duplicate) {
    Check.isNotNull(context, "EmailSendContext cannot be null");
    this.contractName = context.getContractName();
    this.idempotencyKey = idempotencyKey;
    this.tenantId = context.getTenantId();
    this.userId = context.getUserId();
    this.recordId = context.getRecordId();
    this.template = context.getTemplate();
    this.recipient = context.getRecipientAddress();
    this.recipientDomain = context.getRecipientDomain();
    this.httpStatus = httpStatus;
    this.status = status;
    this.message = message;
    this.providerStatus = providerStatus;
    this.duplicate = duplicate;
    this.createdAtMillis = System.currentTimeMillis();
    EmailRecipientSet recipients = context.getRecipientSet();
    this.finalToRecipientHashes = hashList(recipients == null ? null : recipients.getTo());
    this.finalCcRecipientHashes = hashList(recipients == null ? null : recipients.getCc());
    this.finalRecipientDomains = domainList(recipients);
  }

  private EmailAuditRecord(Snapshot snapshot) {
    this.contractName = snapshot.contractName;
    this.idempotencyKey = snapshot.idempotencyKey;
    this.tenantId = snapshot.tenantId;
    this.userId = snapshot.userId;
    this.recordId = snapshot.recordId;
    this.template = snapshot.template;
    this.recipient = snapshot.recipient;
    this.recipientDomain = snapshot.recipientDomain;
    this.httpStatus = snapshot.httpStatus;
    this.status = snapshot.status;
    this.message = snapshot.message;
    this.providerStatus = snapshot.providerStatus;
    this.duplicate = snapshot.duplicate;
    this.createdAtMillis = snapshot.createdAtMillis;
    this.finalToRecipientHashes = Collections.emptyList();
    this.finalCcRecipientHashes = Collections.emptyList();
    this.finalRecipientDomains = Collections.emptyList();
  }

  /**
   * Creates an audit record from the resolved send context.
   *
   * @param context resolved send context
   * @param idempotencyKey resolved idempotency key
   * @param httpStatus response HTTP status
   * @param status response status
   * @param message response message
   * @param providerStatus provider HTTP status when available
   * @param duplicate whether this event represents a duplicate request
   * @return audit record
   */
  public static EmailAuditRecord create(EmailSendContext context, String idempotencyKey,
      int httpStatus, String status, String message, Integer providerStatus, boolean duplicate) {
    Check.isNotNull(context, "EmailSendContext cannot be null");
    return new EmailAuditRecord(context, idempotencyKey, httpStatus, status, message,
        providerStatus, duplicate);
  }

  static EmailAuditRecord persisted(Snapshot snapshot) {
    Check.isNotNull(snapshot, "Email audit snapshot cannot be null");
    return new EmailAuditRecord(snapshot);
  }

  static final class Snapshot {
    String contractName;
    String idempotencyKey;
    String tenantId;
    String userId;
    String recordId;
    String template;
    String recipient;
    String recipientDomain;
    int httpStatus;
    String status;
    String message;
    Integer providerStatus;
    boolean duplicate;
    long createdAtMillis;
  }

  /**
   * Returns the contract name.
   *
   * @return contract name
   */
  public String getContractName() {
    return contractName;
  }

  /**
   * Returns the idempotency key.
   *
   * @return idempotency key
   */
  public String getIdempotencyKey() {
    return idempotencyKey;
  }

  /**
   * Returns the tenant id.
   *
   * @return tenant id
   */
  public String getTenantId() {
    return tenantId;
  }

  /**
   * Returns the user id.
   *
   * @return user id
   */
  public String getUserId() {
    return userId;
  }

  /**
   * Returns the business record id.
   *
   * @return record id
   */
  public String getRecordId() {
    return recordId;
  }

  /**
   * Returns the provider template.
   *
   * @return template name
   */
  public String getTemplate() {
    return template;
  }

  /**
   * Returns the destination address.
   *
   * @return recipient address
   */
  public String getRecipient() {
    return recipient;
  }

  /**
   * Returns the destination domain.
   *
   * @return recipient domain
   */
  public String getRecipientDomain() {
    return recipientDomain;
  }

  /**
   * Returns the SHA-256 hashes of the final to-channel recipients (never raw addresses).
   *
   * @return immutable list of to-recipient hashes
   */
  public List<String> getFinalToRecipientHashes() {
    return finalToRecipientHashes;
  }

  /**
   * Returns the SHA-256 hashes of the final cc-channel recipients (never raw addresses).
   *
   * @return immutable list of cc-recipient hashes
   */
  public List<String> getFinalCcRecipientHashes() {
    return finalCcRecipientHashes;
  }

  /**
   * Returns the plaintext lower-cased domains of the final recipient set.
   *
   * @return immutable list of recipient domains
   */
  public List<String> getFinalRecipientDomains() {
    return finalRecipientDomains;
  }

  /**
   * Returns the HTTP status.
   *
   * @return HTTP status
   */
  public int getHttpStatus() {
    return httpStatus;
  }

  /**
   * Returns the executor status.
   *
   * @return executor status
   */
  public String getStatus() {
    return status;
  }

  /**
   * Returns the response message.
   *
   * @return response message
   */
  public String getMessage() {
    return message;
  }

  /**
   * Returns the provider status.
   *
   * @return provider status when available
   */
  public Integer getProviderStatus() {
    return providerStatus;
  }

  /**
   * Indicates whether this audit event is a duplicate.
   *
   * @return {@code true} for duplicate events
   */
  public boolean isDuplicate() {
    return duplicate;
  }

  /**
   * Returns the creation timestamp.
   *
   * @return creation time in epoch milliseconds
   */
  public long getCreatedAtMillis() {
    return createdAtMillis;
  }

  private static List<String> hashList(List<String> addresses) {
    if (addresses == null || addresses.isEmpty()) {
      return Collections.emptyList();
    }
    List<String> hashes = new ArrayList<>(addresses.size());
    for (String address : addresses) {
      String hashed = hash(address);
      if (hashed != null) {
        hashes.add(hashed);
      }
    }
    return Collections.unmodifiableList(hashes);
  }

  private static List<String> domainList(EmailRecipientSet recipients) {
    if (recipients == null) {
      return Collections.emptyList();
    }
    List<String> domains = new ArrayList<>();
    addDomains(domains, recipients.getTo());
    addDomains(domains, recipients.getCc());
    return Collections.unmodifiableList(domains);
  }

  private static void addDomains(List<String> domains, List<String> addresses) {
    for (String address : addresses) {
      int at = address.lastIndexOf('@');
      if (at >= 0 && at < address.length() - 1) {
        String domain = address.substring(at + 1).toLowerCase(Locale.ROOT);
        if (!domains.contains(domain)) {
          domains.add(domain);
        }
      }
    }
  }

  private static String hash(String value) {
    String normalized = StringUtils.trimToNull(value);
    if (normalized == null) {
      return null;
    }
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] bytes = digest.digest(normalized.toLowerCase(Locale.ROOT)
          .getBytes(StandardCharsets.UTF_8));
      StringBuilder hex = new StringBuilder(bytes.length * 2);
      for (byte b : bytes) {
        hex.append(String.format("%02x", b));
      }
      return hex.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new OBException("SHA-256 unavailable", e);
    }
  }
}
