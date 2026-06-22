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
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import org.apache.commons.lang3.StringUtils;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.exception.OBException;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.base.structure.DynamicOBObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.dal.service.OBQuery;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.enterprise.Organization;

/**
 * DAL-backed transactional email safety store.
 */
public class DalEmailSafetyStore implements EmailSafetyStore {

  static final String ENTITY_EMAIL_SAFETY = "ETGO_Email_Safety";

  private static final String RECORD_AUDIT = "AUDIT";
  private static final String RECORD_THROTTLE = "THROTTLE";
  private static final String RECORD_KILL_SWITCH = "KILL_SWITCH";
  private static final String RECORD_SUPPRESSION = "SUPPRESSION";
  static final String SCOPE_ADDRESS = "ADDRESS";
  static final String SCOPE_DOMAIN = "DOMAIN";
  private static final String GLOBAL_TENANT = "global";
  private static final String ZERO_ID = "0";

  private static final String PROP_ACTIVE = "active";
  private static final String PROP_ATTEMPT_COUNT = "attemptCount";
  private static final String PROP_AUDIT_TIME = "auditTime";
  private static final String PROP_BUCKET_KEY = "bucketKey";
  private static final String PROP_CLIENT = "client";
  private static final String PROP_CONTRACT_NAME = "contractName";
  private static final String PROP_IDEMPOTENCY_KEY = "idempotencyKey";
  private static final String PROP_MAX_ATTEMPTS = "maxAttempts";
  private static final String PROP_ORGANIZATION = "organization";
  private static final String PROP_PAYLOAD = "payload";
  private static final String PROP_RECORD_TYPE = "recordType";
  private static final String PROP_SCOPE = "scope";
  private static final String PROP_STATUS = "status";
  private static final String PROP_TEMPLATE = "template";
  private static final String PROP_TENANT_ID = "tenantID";
  private static final String PROP_WINDOW_SECONDS = "windowSeconds";
  private static final String PROP_WINDOW_START = "windowStart";

  private static final String PARAM_CONTRACT_NAME = "contractName";
  private static final String PARAM_IDEMPOTENCY_KEY = "idempotencyKey";
  private static final String PARAM_RECORD_TYPE = "recordType";
  private static final String PARAM_SCOPE = "scope";
  private static final String PARAM_STATUS = "status";
  private static final String PARAM_TENANT_ID = "tenantId";
  private static final String PARAM_BUCKET_KEY = "bucketKey";
  private static final String PARAM_MAX_ATTEMPTS = "maxAttempts";
  private static final String PARAM_WINDOW_SECONDS = "windowSeconds";
  private static final String QUERY_PREFIX = "as safety where safety.";
  private static final String QUERY_AND = " and safety.";
  private static final String PAYLOAD_PROVIDER_STATUS = "providerStatus";

  private final LongSupplier clock;
  private final Supplier<BaseOBObject> recordSupplier;

  /**
   * Creates a DAL-backed store using the system clock.
   */
  public DalEmailSafetyStore() {
    this(System::currentTimeMillis, DalEmailSafetyStore::newSafetyRecord);
  }

  DalEmailSafetyStore(LongSupplier clock, Supplier<BaseOBObject> recordSupplier) {
    this.clock = Objects.requireNonNull(clock, "Email safety store clock cannot be null");
    this.recordSupplier = Objects.requireNonNull(recordSupplier,
        "Email safety record supplier cannot be null");
  }

  @Override
  public EmailKillSwitchResult checkKillSwitch(EmailSendContext context) {
    Optional<BaseOBObject> global = findKillSwitch(EmailThrottleRule.SCOPE_GLOBAL, GLOBAL_TENANT);
    if (global.isPresent()) {
      return EmailKillSwitchResult.suppressed(EmailThrottleRule.SCOPE_GLOBAL, GLOBAL_TENANT,
          reason(global.get(), "Transactional email is disabled globally"));
    }
    Optional<BaseOBObject> tenant = findKillSwitch(EmailThrottleRule.SCOPE_TENANT,
        context.getTenantId());
    if (tenant.isPresent()) {
      return EmailKillSwitchResult.suppressed(EmailThrottleRule.SCOPE_TENANT,
          context.getTenantId(), reason(tenant.get(),
              "Transactional email is disabled for this tenant"));
    }
    Optional<BaseOBObject> template = findKillSwitch(EmailThrottleRule.SCOPE_TEMPLATE,
        context.getTemplate());
    if (template.isPresent()) {
      return EmailKillSwitchResult.suppressed(EmailThrottleRule.SCOPE_TEMPLATE,
          context.getTemplate(), reason(template.get(),
              "Transactional email is disabled for this template"));
    }
    return EmailKillSwitchResult.allowed();
  }

  @Override
  public Optional<EmailAuditRecord> findSentByIdempotencyKey(EmailSendContext context,
      String idempotencyKey) {
    String normalizedKey = StringUtils.trimToNull(idempotencyKey);
    if (normalizedKey == null) {
      return Optional.empty();
    }
    OBQuery<BaseOBObject> query = OBDal.getInstance().createQuery(ENTITY_EMAIL_SAFETY,
        QUERY_PREFIX + PROP_RECORD_TYPE + " = :" + PARAM_RECORD_TYPE
            + QUERY_AND + PROP_CONTRACT_NAME + " = :" + PARAM_CONTRACT_NAME
            + QUERY_AND + PROP_TENANT_ID + " = :" + PARAM_TENANT_ID
            + QUERY_AND + PROP_IDEMPOTENCY_KEY + " = :" + PARAM_IDEMPOTENCY_KEY
            + QUERY_AND + PROP_STATUS + " = :" + PARAM_STATUS
            + QUERY_AND + PROP_ACTIVE + " = true"
            + " order by safety." + PROP_AUDIT_TIME + " desc");
    query.setNamedParameter(PARAM_RECORD_TYPE, RECORD_AUDIT);
    query.setNamedParameter(PARAM_CONTRACT_NAME, context.getContractName());
    query.setNamedParameter(PARAM_TENANT_ID, tenantKey(context.getTenantId()));
    query.setNamedParameter(PARAM_IDEMPOTENCY_KEY, normalizedKey);
    query.setNamedParameter(PARAM_STATUS, TransactionalEmailService.STATUS_SENT);
    query.setFilterOnReadableClients(false);
    query.setFilterOnReadableOrganization(false);
    query.setMaxResult(1);
    List<BaseOBObject> records = query.list();
    return records.isEmpty() ? Optional.empty() : Optional.of(toAuditRecord(records.get(0)));
  }

  @Override
  public EmailThrottleResult checkAndIncrement(EmailSendContext context,
      List<EmailThrottleRule> rules) {
    if (rules == null || rules.isEmpty()) {
      return EmailThrottleResult.allowed();
    }

    long now = clock.getAsLong();
    for (EmailThrottleRule rule : rules) {
      String key = StringUtils.trimToNull(rule.resolveKey(context));
      if (key == null) {
        continue;
      }
      Optional<BaseOBObject> throttleRecord = findThrottle(rule, key);
      if (throttleRecord.isPresent() && isInsideWindow(throttleRecord.get(), rule, now)
          && attemptCount(throttleRecord.get()) >= rule.getMaxAttempts()) {
        long retryAt = windowStart(throttleRecord.get()) + rule.getWindowSeconds() * 1000L;
        int retryAfterSeconds = (int) Math.max(1, (retryAt - now + 999L) / 1000L);
        return EmailThrottleResult.throttled(rule.getScope(), key, retryAfterSeconds);
      }
    }

    // Writing client-0 throttle records from a user request requires admin mode.
    OBContext.setAdminMode();
    try {
      for (EmailThrottleRule rule : rules) {
        String key = StringUtils.trimToNull(rule.resolveKey(context));
        if (key != null) {
          incrementThrottle(rule, key, context, now);
        }
      }
      OBDal.getInstance().flush();
    } finally {
      OBContext.restorePreviousMode();
    }
    return EmailThrottleResult.allowed();
  }

  @Override
  public boolean isRecipientSuppressed(String tenantId, String emailAddress) {
    String normalized = EmailRecipientSet.normalizeAddress(emailAddress);
    if (normalized == null) {
      return false;
    }
    String addressHash = hashAddress(normalized);
    if (addressHash != null && findSuppression(SCOPE_ADDRESS, addressHash).isPresent()) {
      return true;
    }
    String domain = domainOf(normalized);
    return domain != null && findSuppression(SCOPE_DOMAIN, domain).isPresent();
  }

  @Override
  public void recordAudit(EmailAuditRecord auditRecord) {
    Objects.requireNonNull(auditRecord, "Email audit record cannot be null");
    // Writing a client-0 record from a user request requires admin mode (see checkWriteAccess).
    OBContext.setAdminMode();
    try {
      BaseOBObject auditEntry = newRecord();
      auditEntry.set(PROP_RECORD_TYPE, RECORD_AUDIT);
      auditEntry.set(PROP_CONTRACT_NAME, auditRecord.getContractName());
      auditEntry.set(PROP_TEMPLATE, auditRecord.getTemplate());
      auditEntry.set(PROP_TENANT_ID, tenantKey(auditRecord.getTenantId()));
      auditEntry.set(PROP_IDEMPOTENCY_KEY, auditRecord.getIdempotencyKey());
      auditEntry.set(PROP_STATUS, auditRecord.getStatus());
      auditEntry.set(PROP_AUDIT_TIME, new Date(auditRecord.getCreatedAtMillis()));
      auditEntry.set(PROP_PAYLOAD, auditPayload(auditRecord).toString());
      OBDal.getInstance().save(auditEntry);
      OBDal.getInstance().flush();
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  private Optional<BaseOBObject> findKillSwitch(String scope, String key) {
    String normalizedKey = StringUtils.trimToNull(key);
    if (normalizedKey == null) {
      return Optional.empty();
    }
    OBQuery<BaseOBObject> query = OBDal.getInstance().createQuery(ENTITY_EMAIL_SAFETY,
        QUERY_PREFIX + PROP_RECORD_TYPE + " = :" + PARAM_RECORD_TYPE
            + QUERY_AND + PROP_SCOPE + " = :" + PARAM_SCOPE
            + QUERY_AND + PROP_BUCKET_KEY + " = :" + PARAM_BUCKET_KEY
            + QUERY_AND + PROP_ACTIVE + " = true"
            + " order by safety.updated desc");
    query.setNamedParameter(PARAM_RECORD_TYPE, RECORD_KILL_SWITCH);
    query.setNamedParameter(PARAM_SCOPE, scope);
    query.setNamedParameter(PARAM_BUCKET_KEY, normalizedKey);
    query.setFilterOnReadableClients(false);
    query.setFilterOnReadableOrganization(false);
    query.setMaxResult(1);
    List<BaseOBObject> records = query.list();
    return records.isEmpty() ? Optional.empty() : Optional.of(records.get(0));
  }

  private Optional<BaseOBObject> findSuppression(String scope, String bucketKey) {
    OBQuery<BaseOBObject> query = OBDal.getInstance().createQuery(ENTITY_EMAIL_SAFETY,
        QUERY_PREFIX + PROP_RECORD_TYPE + " = :" + PARAM_RECORD_TYPE
            + QUERY_AND + PROP_SCOPE + " = :" + PARAM_SCOPE
            + QUERY_AND + PROP_BUCKET_KEY + " = :" + PARAM_BUCKET_KEY
            + QUERY_AND + PROP_ACTIVE + " = true");
    query.setNamedParameter(PARAM_RECORD_TYPE, RECORD_SUPPRESSION);
    query.setNamedParameter(PARAM_SCOPE, scope);
    query.setNamedParameter(PARAM_BUCKET_KEY, bucketKey);
    query.setFilterOnReadableClients(false);
    query.setFilterOnReadableOrganization(false);
    query.setMaxResult(1);
    List<BaseOBObject> records = query.list();
    return records.isEmpty() ? Optional.empty() : Optional.of(records.get(0));
  }

  private Optional<BaseOBObject> findThrottle(EmailThrottleRule rule, String key) {
    OBQuery<BaseOBObject> query = OBDal.getInstance().createQuery(ENTITY_EMAIL_SAFETY,
        QUERY_PREFIX + PROP_RECORD_TYPE + " = :" + PARAM_RECORD_TYPE
            + QUERY_AND + PROP_SCOPE + " = :" + PARAM_SCOPE
            + QUERY_AND + PROP_BUCKET_KEY + " = :" + PARAM_BUCKET_KEY
            + QUERY_AND + PROP_MAX_ATTEMPTS + " = :" + PARAM_MAX_ATTEMPTS
            + QUERY_AND + PROP_WINDOW_SECONDS + " = :" + PARAM_WINDOW_SECONDS
            + QUERY_AND + PROP_ACTIVE + " = true");
    query.setNamedParameter(PARAM_RECORD_TYPE, RECORD_THROTTLE);
    query.setNamedParameter(PARAM_SCOPE, rule.getScope());
    query.setNamedParameter(PARAM_BUCKET_KEY, key);
    query.setNamedParameter(PARAM_MAX_ATTEMPTS, Long.valueOf(rule.getMaxAttempts()));
    query.setNamedParameter(PARAM_WINDOW_SECONDS, Long.valueOf(rule.getWindowSeconds()));
    query.setFilterOnReadableClients(false);
    query.setFilterOnReadableOrganization(false);
    query.setMaxResult(1);
    List<BaseOBObject> records = query.list();
    return records.isEmpty() ? Optional.empty() : Optional.of(records.get(0));
  }

  private void incrementThrottle(EmailThrottleRule rule, String key, EmailSendContext context,
      long now) {
    BaseOBObject throttleEntry = findThrottle(rule, key).orElseGet(() -> {
      BaseOBObject created = newRecord();
      created.set(PROP_RECORD_TYPE, RECORD_THROTTLE);
      created.set(PROP_SCOPE, rule.getScope());
      created.set(PROP_BUCKET_KEY, key);
      created.set(PROP_TENANT_ID, tenantKey(context.getTenantId()));
      created.set(PROP_MAX_ATTEMPTS, Long.valueOf(rule.getMaxAttempts()));
      created.set(PROP_WINDOW_SECONDS, Long.valueOf(rule.getWindowSeconds()));
      return created;
    });
    if (!isInsideWindow(throttleEntry, rule, now)) {
      throttleEntry.set(PROP_WINDOW_START, new Date(now));
      throttleEntry.set(PROP_ATTEMPT_COUNT, 0L);
    }
    throttleEntry.set(PROP_ATTEMPT_COUNT, attemptCount(throttleEntry) + 1L);
    OBDal.getInstance().save(throttleEntry);
  }

  private BaseOBObject newRecord() {
    BaseOBObject safetyRecord = recordSupplier.get();
    // ETGO_Email_Safety is a SYSTEM-level table (AD access level 4): every record must be owned
    // by client 0. Per-tenant scoping is tracked in the tenantId column, not via the client.
    safetyRecord.set(PROP_CLIENT, OBDal.getInstance().get(Client.class, ZERO_ID));
    safetyRecord.set(PROP_ORGANIZATION, OBDal.getInstance().get(Organization.class, ZERO_ID));
    safetyRecord.set(PROP_ACTIVE, true);
    return safetyRecord;
  }

  private static BaseOBObject newSafetyRecord() {
    DynamicOBObject safetyRecord = new DynamicOBObject();
    safetyRecord.setEntityName(ENTITY_EMAIL_SAFETY);
    return safetyRecord;
  }

  private static boolean isInsideWindow(BaseOBObject safetyRecord, EmailThrottleRule rule,
      long now) {
    long start = windowStart(safetyRecord);
    return start > 0L && now - start < rule.getWindowSeconds() * 1000L;
  }

  private static long windowStart(BaseOBObject safetyRecord) {
    Object value = safetyRecord.get(PROP_WINDOW_START);
    return value instanceof Date ? ((Date) value).getTime() : 0L;
  }

  private static long attemptCount(BaseOBObject safetyRecord) {
    Object value = safetyRecord.get(PROP_ATTEMPT_COUNT);
    return value instanceof Number ? ((Number) value).longValue() : 0L;
  }

  private static String tenantKey(String tenantId) {
    return StringUtils.defaultIfBlank(tenantId, GLOBAL_TENANT);
  }

  private static String reason(BaseOBObject safetyRecord, String fallback) {
    JSONObject payload = payload(safetyRecord);
    String reason = payload == null ? null : StringUtils.trimToNull(payload.optString("reason"));
    return reason == null ? fallback : reason;
  }

  private static EmailAuditRecord toAuditRecord(BaseOBObject safetyRecord) {
    JSONObject payload = payload(safetyRecord);
    int httpStatus = payload == null ? 200 : payload.optInt("httpStatus", 200);
    Integer providerStatus = payload == null || !payload.has(PAYLOAD_PROVIDER_STATUS)
        || payload.isNull(PAYLOAD_PROVIDER_STATUS) ? null
            : payload.optInt(PAYLOAD_PROVIDER_STATUS);
    EmailAuditRecord.Snapshot snapshot = new EmailAuditRecord.Snapshot();
    snapshot.contractName = (String) safetyRecord.get(PROP_CONTRACT_NAME);
    snapshot.idempotencyKey = (String) safetyRecord.get(PROP_IDEMPOTENCY_KEY);
    snapshot.tenantId = (String) safetyRecord.get(PROP_TENANT_ID);
    snapshot.userId = payload == null ? null : StringUtils.trimToNull(payload.optString("userId"));
    snapshot.recordId = payload == null ? null
        : StringUtils.trimToNull(payload.optString("recordId"));
    snapshot.template = (String) safetyRecord.get(PROP_TEMPLATE);
    snapshot.recipientDomain = payload == null ? null
        : StringUtils.trimToNull(payload.optString("recipientDomain"));
    snapshot.httpStatus = httpStatus;
    snapshot.status = (String) safetyRecord.get(PROP_STATUS);
    snapshot.message = payload == null ? null
        : StringUtils.trimToNull(payload.optString("message"));
    snapshot.providerStatus = providerStatus;
    snapshot.duplicate = payload != null && payload.optBoolean("duplicate");
    snapshot.createdAtMillis = ((Date) safetyRecord.get(PROP_AUDIT_TIME)).getTime();
    return EmailAuditRecord.persisted(snapshot);
  }

  private static JSONObject payload(BaseOBObject safetyRecord) {
    String payloadValue = (String) safetyRecord.get(PROP_PAYLOAD);
    if (StringUtils.isBlank(payloadValue)) {
      return null;
    }
    try {
      return new JSONObject(payloadValue);
    } catch (JSONException e) {
      throw new OBException("Invalid email safety payload", e);
    }
  }

  private static JSONObject auditPayload(EmailAuditRecord auditRecord) {
    try {
      JSONObject payload = new JSONObject();
      payload.put("version", EmailContractCommandSupport.VERSION);
      payload.put("userId", nullToJson(auditRecord.getUserId()));
      payload.put("recordId", nullToJson(auditRecord.getRecordId()));
      payload.put("recipientHash", nullToJson(hash(auditRecord.getRecipient())));
      payload.put("recipientDomain", nullToJson(auditRecord.getRecipientDomain()));
      payload.put("httpStatus", auditRecord.getHttpStatus());
      payload.put("message", nullToJson(auditRecord.getMessage()));
      payload.put(PAYLOAD_PROVIDER_STATUS, auditRecord.getProviderStatus() == null
          ? JSONObject.NULL : auditRecord.getProviderStatus());
      payload.put("duplicate", auditRecord.isDuplicate());
      return payload;
    } catch (JSONException e) {
      throw new OBException("Could not build email audit payload", e);
    }
  }

  private static Object nullToJson(String value) {
    return value == null ? JSONObject.NULL : value;
  }

  private static String hashAddress(String normalizedAddress) {
    return hash(normalizedAddress);
  }

  private static String domainOf(String normalizedAddress) {
    int at = normalizedAddress.lastIndexOf('@');
    if (at < 0 || at == normalizedAddress.length() - 1) {
      return null;
    }
    return normalizedAddress.substring(at + 1).toLowerCase();
  }

  private static String hash(String value) {
    String normalized = StringUtils.trimToNull(value);
    if (normalized == null) {
      return null;
    }
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] bytes = digest.digest(normalized.toLowerCase().getBytes(StandardCharsets.UTF_8));
      StringBuilder result = new StringBuilder(bytes.length * 2);
      for (byte b : bytes) {
        result.append(String.format("%02x", b));
      }
      return result.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 not available", e);
    }
  }
}
