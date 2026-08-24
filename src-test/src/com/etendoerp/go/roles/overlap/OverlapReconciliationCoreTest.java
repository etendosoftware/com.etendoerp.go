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
package com.etendoerp.go.roles.overlap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Plain, DB-free unit tests for {@link OverlapReconciliationCore#computeWinner(List)} — the
 * SeqNo-descending winner + most-permissive-wins level decision extracted from {@code
 * WindowAccessOverlapCorruptionGuard}'s own sixth-trigger fix (ETP-4906) so {@code
 * ProcessAccessOverlapCorruptionGuard} and {@code ObuiappProcessAccessDuplicateGuard} reuse the
 * exact same, already-proven algorithm instead of re-deriving it (ETP-4830 item 7).
 */
class OverlapReconciliationCoreTest {

  @Test
  void emptyListReturnsNull() {
    assertNull(OverlapReconciliationCore.computeWinner(Collections.emptyList()));
  }

  @Test
  void nullListReturnsNull() {
    assertNull(OverlapReconciliationCore.computeWinner(null));
  }

  @Test
  void singleCandidateWinsAndKeepsItsOwnLevel() {
    List<GrantCandidate> candidates = Collections.singletonList(
        new GrantCandidate("template-a", true));

    OverlapWinner winner = OverlapReconciliationCore.computeWinner(candidates);

    assertEquals("template-a", winner.getWinnerTemplateId());
    assertTrue(winner.isWinnerLevel());
  }

  @Test
  void firstCandidateInListOrderWinsRegardlessOfItsOwnLevel() {
    // Caller is responsible for SeqNo-descending order — the FIRST entry is always the winner,
    // even when a LATER entry is the one granting full access. This is the exact rule
    // WindowAccessOverlapCorruptionGuard's own "Why InheritedFrom must track core's own SeqNo
    // precedence" section proves is required — picking the most-permissive one instead
    // reproduces the OBSecurityException that section documents.
    List<GrantCandidate> candidates = Arrays.asList(
        new GrantCandidate("highest-seqno-readonly", false),
        new GrantCandidate("lower-seqno-full", true));

    OverlapWinner winner = OverlapReconciliationCore.computeWinner(candidates);

    assertEquals("highest-seqno-readonly", winner.getWinnerTemplateId());
  }

  @Test
  void levelIsFullWhenAnyCandidateGrantsFullAccessRegardlessOfWinner() {
    List<GrantCandidate> candidates = Arrays.asList(
        new GrantCandidate("highest-seqno-readonly", false),
        new GrantCandidate("lower-seqno-full", true));

    OverlapWinner winner = OverlapReconciliationCore.computeWinner(candidates);

    assertTrue(winner.isWinnerLevel(),
        "Most-permissive-wins is independent of which candidate is the SeqNo winner");
  }

  @Test
  void levelIsReadOnlyWhenNoCandidateGrantsFullAccess() {
    List<GrantCandidate> candidates = Arrays.asList(
        new GrantCandidate("template-a", false),
        new GrantCandidate("template-b", false));

    OverlapWinner winner = OverlapReconciliationCore.computeWinner(candidates);

    assertFalse(winner.isWinnerLevel());
  }

  @Test
  void threeCandidatesWinnerIsAlwaysTheFirstInOrder() {
    // Mirrors the real ETP-4906 6th-round reproduction shape: 3 remaining templates all granting
    // the same item, winner must be the first (highest-SeqNo) regardless of how many follow.
    List<GrantCandidate> candidates = Arrays.asList(
        new GrantCandidate("purchasing", false),
        new GrantCandidate("sales", true),
        new GrantCandidate("inventory", false));

    OverlapWinner winner = OverlapReconciliationCore.computeWinner(candidates);

    assertEquals("purchasing", winner.getWinnerTemplateId());
    assertTrue(winner.isWinnerLevel());
  }

  @Test
  void findJustifyingFullGrantReturnsNullForNullList() {
    assertNull(OverlapReconciliationCore.findJustifyingFullGrant(null, null));
  }

  @Test
  void findJustifyingFullGrantReturnsNullWhenNoCandidateGrantsFullAccess() {
    List<GrantCandidate> candidates = Arrays.asList(
        new GrantCandidate("template-a", false),
        new GrantCandidate("template-b", false));
    assertNull(OverlapReconciliationCore.findJustifyingFullGrant(candidates, null));
  }

  @Test
  void findJustifyingFullGrantReturnsFirstFullGrantorInOrder() {
    List<GrantCandidate> candidates = Arrays.asList(
        new GrantCandidate("readonly-first", false),
        new GrantCandidate("full-second", true),
        new GrantCandidate("full-third", true));
    assertEquals("full-second",
        OverlapReconciliationCore.findJustifyingFullGrant(candidates, null));
  }

  @Test
  void findJustifyingFullGrantSkipsExcludedTemplateEvenWhenItGrantsFullAccess() {
    List<GrantCandidate> candidates = Arrays.asList(
        new GrantCandidate("full-excluded", true),
        new GrantCandidate("full-other", true));
    assertEquals("full-other",
        OverlapReconciliationCore.findJustifyingFullGrant(candidates, "full-excluded"));
  }

  @Test
  void findJustifyingFullGrantReturnsNullWhenOnlyFullGrantorIsExcluded() {
    List<GrantCandidate> candidates = Collections.singletonList(
        new GrantCandidate("full-excluded", true));
    assertNull(OverlapReconciliationCore.findJustifyingFullGrant(candidates, "full-excluded"));
  }
}
