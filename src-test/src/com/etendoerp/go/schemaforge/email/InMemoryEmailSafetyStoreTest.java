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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import org.codehaus.jettison.json.JSONObject;
import org.junit.Test;

/**
 * Unit tests for {@link InMemoryEmailSafetyStore}. No database or Etendo container required.
 */
public class InMemoryEmailSafetyStoreTest {

  // ─── Helpers ─────────────────────────────────────────────────────────────────

  private static EmailSendContext context(String tenantId, String template,
      String recipient) throws Exception {
    JSONObject body = new JSONObject();
    if (tenantId != null) {
      body.put(EmailContractCommandSupport.FIELD_TENANT_ID, tenantId);
    }
    return new EmailSendContext(
        new EmailContractCommand("test-contract", body),
        EmailRecipientResolution.serverResolved(recipient),
        new EmailProviderRequest(recipient, template, new JSONObject(), null));
  }

  private static EmailSendContext simpleContext() throws Exception {
    return context("tenant-1", "test-template", "user@example.com");
  }

  private static EmailAuditRecord auditRecord(EmailSendContext ctx, String idempotencyKey,
      String status) {
    return EmailAuditRecord.create(ctx, idempotencyKey, 200, status, "ok", null, false);
  }

  // ─── Constructor ─────────────────────────────────────────────────────────────

  @Test
  public void defaultConstructorStartsEmpty() {
    InMemoryEmailSafetyStore store = new InMemoryEmailSafetyStore();
    assertTrue(store.getAuditRecords().isEmpty());
  }

  @Test(expected = NullPointerException.class)
  public void constructorRejectsNullClock() {
    new InMemoryEmailSafetyStore(null);
  }

  // ─── Kill switch: allowed ─────────────────────────────────────────────────

  @Test
  public void checkKillSwitchAllowsWhenNothingDisabled() throws Exception {
    InMemoryEmailSafetyStore store = new InMemoryEmailSafetyStore();
    assertTrue(store.checkKillSwitch(simpleContext()).isAllowed());
  }

  // ─── Kill switch: global ──────────────────────────────────────────────────

  @Test
  public void disableGlobalSuppressesAllContexts() throws Exception {
    InMemoryEmailSafetyStore store = new InMemoryEmailSafetyStore();
    store.disableGlobal();
    EmailKillSwitchResult result = store.checkKillSwitch(simpleContext());
    assertFalse(result.isAllowed());
    assertEquals(EmailThrottleRule.SCOPE_GLOBAL, result.getScope());
  }

  // ─── Kill switch: tenant ──────────────────────────────────────────────────

  @Test
  public void disableTenantSuppressesMatchingTenant() throws Exception {
    InMemoryEmailSafetyStore store = new InMemoryEmailSafetyStore();
    store.disableTenant("tenant-1");
    EmailKillSwitchResult suppressed = store.checkKillSwitch(
        context("tenant-1", "test-template", "user@example.com"));
    EmailKillSwitchResult allowed = store.checkKillSwitch(
        context("tenant-2", "test-template", "user@example.com"));
    assertFalse(suppressed.isAllowed());
    assertEquals(EmailThrottleRule.SCOPE_TENANT, suppressed.getScope());
    assertTrue(allowed.isAllowed());
  }

  @Test
  public void disableTenantWithNullIsNoOp() throws Exception {
    InMemoryEmailSafetyStore store = new InMemoryEmailSafetyStore();
    store.disableTenant(null);
    assertTrue(store.checkKillSwitch(simpleContext()).isAllowed());
  }

  @Test
  public void disableTenantWithBlankIsNoOp() throws Exception {
    InMemoryEmailSafetyStore store = new InMemoryEmailSafetyStore();
    store.disableTenant("   ");
    assertTrue(store.checkKillSwitch(simpleContext()).isAllowed());
  }

  // ─── Kill switch: template ────────────────────────────────────────────────

  @Test
  public void disableTemplateSuppressesMatchingTemplate() throws Exception {
    InMemoryEmailSafetyStore store = new InMemoryEmailSafetyStore();
    store.disableTemplate("test-template");
    EmailKillSwitchResult suppressed = store.checkKillSwitch(
        context("tenant-1", "test-template", "user@example.com"));
    EmailKillSwitchResult allowed = store.checkKillSwitch(
        context("tenant-1", "other-template", "user@example.com"));
    assertFalse(suppressed.isAllowed());
    assertEquals(EmailThrottleRule.SCOPE_TEMPLATE, suppressed.getScope());
    assertTrue(allowed.isAllowed());
  }

  // ─── findSentByIdempotencyKey ─────────────────────────────────────────────

  @Test
  public void findSentByIdempotencyKeyEmptyBeforeRecord() throws Exception {
    InMemoryEmailSafetyStore store = new InMemoryEmailSafetyStore();
    EmailSendContext ctx = simpleContext();
    assertFalse(store.findSentByIdempotencyKey(ctx, "key-1").isPresent());
  }

  @Test
  public void findSentByIdempotencyKeyWithNullContextReturnsEmpty() {
    InMemoryEmailSafetyStore store = new InMemoryEmailSafetyStore();
    assertFalse(store.findSentByIdempotencyKey(null, "key-1").isPresent());
  }

  @Test
  public void findSentByIdempotencyKeyWithNullKeyReturnsEmpty() throws Exception {
    InMemoryEmailSafetyStore store = new InMemoryEmailSafetyStore();
    assertFalse(store.findSentByIdempotencyKey(simpleContext(), null).isPresent());
  }

  @Test
  public void findSentByIdempotencyKeyWithBlankKeyReturnsEmpty() throws Exception {
    InMemoryEmailSafetyStore store = new InMemoryEmailSafetyStore();
    assertFalse(store.findSentByIdempotencyKey(simpleContext(), "   ").isPresent());
  }

  @Test
  public void recordAuditWithSentStatusIndexesByIdempotencyKey() throws Exception {
    InMemoryEmailSafetyStore store = new InMemoryEmailSafetyStore();
    EmailSendContext ctx = simpleContext();
    EmailAuditRecord record = auditRecord(ctx, "key-1", TransactionalEmailService.STATUS_SENT);
    store.recordAudit(record);
    Optional<EmailAuditRecord> found = store.findSentByIdempotencyKey(ctx, "key-1");
    assertTrue(found.isPresent());
    assertSame(record, found.get());
  }

  @Test
  public void recordAuditWithNonSentStatusDoesNotIndex() throws Exception {
    InMemoryEmailSafetyStore store = new InMemoryEmailSafetyStore();
    EmailSendContext ctx = simpleContext();
    store.recordAudit(auditRecord(ctx, "key-1", TransactionalEmailService.STATUS_PROVIDER_FAILED));
    assertFalse(store.findSentByIdempotencyKey(ctx, "key-1").isPresent());
  }

  @Test
  public void idempotencyIndexKeepsFirstRecord() throws Exception {
    InMemoryEmailSafetyStore store = new InMemoryEmailSafetyStore();
    EmailSendContext ctx = simpleContext();
    EmailAuditRecord first = auditRecord(ctx, "key-1", TransactionalEmailService.STATUS_SENT);
    EmailAuditRecord second = auditRecord(ctx, "key-1", TransactionalEmailService.STATUS_SENT);
    store.recordAudit(first);
    store.recordAudit(second);
    Optional<EmailAuditRecord> found = store.findSentByIdempotencyKey(ctx, "key-1");
    assertTrue(found.isPresent());
    assertSame(first, found.get());
    assertEquals(2, store.getAuditRecords().size());
  }

  // ─── recordAudit ─────────────────────────────────────────────────────────

  @Test(expected = NullPointerException.class)
  public void recordAuditWithNullThrows() throws Exception {
    new InMemoryEmailSafetyStore().recordAudit(null);
  }

  @Test
  public void getAuditRecordsReturnsAllCapturedRecords() throws Exception {
    InMemoryEmailSafetyStore store = new InMemoryEmailSafetyStore();
    EmailSendContext ctx = simpleContext();
    store.recordAudit(auditRecord(ctx, "k1", TransactionalEmailService.STATUS_SENT));
    store.recordAudit(auditRecord(ctx, "k2", TransactionalEmailService.STATUS_PROVIDER_FAILED));
    assertEquals(2, store.getAuditRecords().size());
  }

  @Test
  public void getAuditRecordsReturnsDefensiveCopy() throws Exception {
    InMemoryEmailSafetyStore store = new InMemoryEmailSafetyStore();
    store.recordAudit(auditRecord(simpleContext(), "k1", TransactionalEmailService.STATUS_SENT));
    List<EmailAuditRecord> snapshot = store.getAuditRecords();
    snapshot.clear();
    assertEquals(1, store.getAuditRecords().size());
  }

  // ─── checkAndIncrement ────────────────────────────────────────────────────

  @Test
  public void checkAndIncrementWithNullRulesReturnsAllowed() throws Exception {
    InMemoryEmailSafetyStore store = new InMemoryEmailSafetyStore();
    assertTrue(store.checkAndIncrement(simpleContext(), null).isAllowed());
  }

  @Test
  public void checkAndIncrementWithEmptyRulesReturnsAllowed() throws Exception {
    InMemoryEmailSafetyStore store = new InMemoryEmailSafetyStore();
    assertTrue(store.checkAndIncrement(simpleContext(), Collections.emptyList()).isAllowed());
  }

  @Test
  public void checkAndIncrementAllowsUntilLimitThenThrottles() throws Exception {
    InMemoryEmailSafetyStore store = new InMemoryEmailSafetyStore();
    EmailSendContext ctx = simpleContext();
    List<EmailThrottleRule> rules = Arrays.asList(EmailThrottleRule.perTenant(2, 60));
    assertTrue(store.checkAndIncrement(ctx, rules).isAllowed());
    assertTrue(store.checkAndIncrement(ctx, rules).isAllowed());
    EmailThrottleResult throttled = store.checkAndIncrement(ctx, rules);
    assertFalse(throttled.isAllowed());
    assertEquals(EmailThrottleRule.SCOPE_TENANT, throttled.getScope());
  }

  @Test
  public void checkAndIncrementResetsCounterAfterWindowExpires() throws Exception {
    AtomicLong clock = new AtomicLong(0L);
    InMemoryEmailSafetyStore store = new InMemoryEmailSafetyStore(clock::get);
    EmailSendContext ctx = simpleContext();
    List<EmailThrottleRule> rules = Arrays.asList(EmailThrottleRule.perTenant(1, 10));
    // Consume the single allowed slot
    assertTrue(store.checkAndIncrement(ctx, rules).isAllowed());
    // Still in window: throttled
    assertFalse(store.checkAndIncrement(ctx, rules).isAllowed());
    // Advance clock past window (10 seconds = 10 000 ms)
    clock.set(11_000L);
    // Window reset: allowed again
    assertTrue(store.checkAndIncrement(ctx, rules).isAllowed());
  }
}
