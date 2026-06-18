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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Unit tests for the per-address and per-domain suppression list in
 * {@link InMemoryEmailSafetyStore}.
 */
public class InMemoryEmailSafetyStoreSuppressionTest {

  @Test
  public void suppressesByExactAddressCaseInsensitively() {
    InMemoryEmailSafetyStore store = new InMemoryEmailSafetyStore();
    store.suppressAddress("Blocked@Example.com");

    assertTrue(store.isRecipientSuppressed("tenant-1", " blocked@example.com "));
    assertFalse(store.isRecipientSuppressed("tenant-1", "other@example.com"));
  }

  @Test
  public void suppressesByDomainCaseInsensitively() {
    InMemoryEmailSafetyStore store = new InMemoryEmailSafetyStore();
    store.suppressDomain("Blocked.COM");

    assertTrue(store.isRecipientSuppressed("tenant-1", "anyone@blocked.com"));
    assertTrue(store.isRecipientSuppressed("tenant-1", "someone@BLOCKED.com"));
    assertFalse(store.isRecipientSuppressed("tenant-1", "anyone@allowed.com"));
  }

  @Test
  public void unsuppressedAddressPasses() {
    InMemoryEmailSafetyStore store = new InMemoryEmailSafetyStore();

    assertFalse(store.isRecipientSuppressed("tenant-1", "free@example.com"));
    assertFalse(store.isRecipientSuppressed("tenant-1", " "));
  }
}
