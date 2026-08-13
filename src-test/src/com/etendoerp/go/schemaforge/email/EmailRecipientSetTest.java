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
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;

import org.junit.Test;

/**
 * Unit tests for {@link EmailRecipientSet}.
 */
public class EmailRecipientSetTest {

  @Test
  public void normalizesAndDeduplicatesWithinChannel() {
    EmailRecipientSet set = EmailRecipientSet.of(
        Arrays.asList(" Ana@Acme.COM ", "ana@acme.com"), Collections.emptyList());
    assertEquals(Collections.singletonList("Ana@acme.com"), set.getTo());
  }

  @Test
  public void crossChannelDedupPrefersTo() {
    EmailRecipientSet set = EmailRecipientSet.of(
        Collections.singletonList("ap@acme.com"),
        Arrays.asList("ap@acme.com", "pm@acme.com"));
    assertEquals(Collections.singletonList("pm@acme.com"), set.getCc());
  }

  @Test
  public void totalCountSpansChannels() {
    EmailRecipientSet set = EmailRecipientSet.of(
        Arrays.asList("a@x.com", "b@x.com"), Collections.singletonList("c@x.com"));
    assertEquals(3, set.totalCount());
  }

  @Test
  public void hashIsStableAcrossOrderingAndChannelAware() {
    EmailRecipientSet a = EmailRecipientSet.of(
        Arrays.asList("a@x.com", "b@x.com"), Collections.emptyList());
    EmailRecipientSet b = EmailRecipientSet.of(
        Arrays.asList("b@x.com", "a@x.com"), Collections.emptyList());
    EmailRecipientSet c = EmailRecipientSet.of(
        Collections.singletonList("a@x.com"), Collections.singletonList("b@x.com"));
    assertEquals(a.recipientSetHash(), b.recipientSetHash());
    assertNotEquals(a.recipientSetHash(), c.recipientSetHash());
  }

  @Test
  public void emptyToIsReportedEvenWithCc() {
    EmailRecipientSet set = EmailRecipientSet.of(
        Collections.emptyList(), Collections.singletonList("c@x.com"));
    assertTrue(set.isToEmpty());
  }

  @Test
  public void singleToBuildsToChannelOnly() {
    EmailRecipientSet set = EmailRecipientSet.singleTo(" Person@Example.COM ");
    assertEquals(Collections.singletonList("Person@example.com"), set.getTo());
    assertTrue(set.getCc().isEmpty());
  }

  @Test
  public void normalizeAddressLowercasesDomainOnly() {
    assertEquals("Ana@acme.com", EmailRecipientSet.normalizeAddress(" Ana@Acme.COM "));
  }
}
