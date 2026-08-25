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

import java.util.List;

/**
 * Pure, Hibernate-free reconciliation decision shared by {@code
 * com.etendoerp.go.roles.WindowAccessOverlapCorruptionGuard}, {@code ProcessAccessOverlap
 * CorruptionGuard}, and {@code ObuiappProcessAccessOverlapCorruptionGuard} (ETP-4830 item 7). See
 * {@code WindowAccessOverlapCorruptionGuard}'s own class javadoc, "A sixth trigger" and "Why
 * InheritedFrom must track core's own SeqNo precedence" sections, for the full live-reproduced
 * root-cause write-up this method's rule comes from — extracted here verbatim, not re-derived,
 * so a future change to the rule only has to happen once.
 */
public final class OverlapReconciliationCore {

  private OverlapReconciliationCore() {
    // static utility
  }

  /**
   * {@code candidatesOrderedBySeqNoDescending} must contain, for ONE window/process/report item,
   * every remaining template that currently, actively grants it — in the SAME order {@link
   * ActiveTemplateInheritance#findActiveTemplatesFor(org.openbravo.model.ad.access.Role, String)}
   * already returns templates (highest {@code AD_Role_Inheritance.SeqNo} first), filtered down to
   * just the ones granting THIS item, with that relative order preserved. Returns {@code null}
   * when the list is empty — no remaining template grants this item at all, so there is nothing
   * to repoint; the caller either leaves the dependent's row untouched or falls back to core's
   * own natural CREATE.
   *
   * <p><b>Winner (who becomes {@code InheritedFrom}) is ALWAYS the first candidate in the
   * list</b> — by construction the highest-{@code SeqNo} remaining grantor, regardless of its own
   * access level. Picking anything else is NOT safe: core's own {@code calculateAccesses} walks
   * every remaining template ascending by {@code SeqNo} in ONE call, and its {@code isPrecedent}
   * check only ever compares list index (== {@code SeqNo} order), never access level —
   * repointing to a lower-index template leaves the row exposed to being overridden by core's own
   * later pass over the true highest-{@code SeqNo} grantor, reopening the exact ownership
   * corruption this mechanism exists to prevent.
   *
   * <p><b>Level ({@code winnerLevel}) is a SEPARATE, most-permissive-wins decision</b>,
   * independent of which candidate is the winner: {@code true} the moment ANY candidate in the
   * list grants full access, regardless of that candidate's own {@code SeqNo}.
   */
  public static OverlapWinner computeWinner(
      List<GrantCandidate> candidatesOrderedBySeqNoDescending) {
    if (candidatesOrderedBySeqNoDescending == null || candidatesOrderedBySeqNoDescending.isEmpty()) {
      return null;
    }
    String winnerTemplateId = candidatesOrderedBySeqNoDescending.get(0).getTemplateId();
    boolean winnerLevel = false;
    for (GrantCandidate candidate : candidatesOrderedBySeqNoDescending) {
      if (candidate.isFullAccess()) {
        winnerLevel = true;
        break;
      }
    }
    return new OverlapWinner(winnerTemplateId, winnerLevel);
  }

  /**
   * "Does some OTHER active template (excluding {@code excludedTemplateId}) still grant full
   * access to this item" — the survey both the ADD-path widening trigger and the update-path
   * most-permissive-wins survey need. Extracted from {@code WindowAccessOverlapCorruption
   * Guard#findActiveTemplateGrantingFullAccess}'s own loop (ETP-4906) — same SeqNo-descending
   * tie-break as {@link #computeWinner(List)}: returns the FIRST candidate (in list order) that
   * grants full access, after skipping {@code excludedTemplateId} — not necessarily the
   * highest-SeqNo grantor overall, just the highest-SeqNo one that is BOTH non-excluded AND full.
   *
   * <p>Returns {@code null} when {@code candidatesOrderedBySeqNoDescending} is {@code null}, or
   * when no non-excluded candidate grants full access.
   */
  public static String findJustifyingFullGrant(
      List<GrantCandidate> candidatesOrderedBySeqNoDescending, String excludedTemplateId) {
    if (candidatesOrderedBySeqNoDescending == null) {
      return null;
    }
    for (GrantCandidate candidate : candidatesOrderedBySeqNoDescending) {
      if (excludedTemplateId != null && excludedTemplateId.equals(candidate.getTemplateId())) {
        continue;
      }
      if (candidate.isFullAccess()) {
        return candidate.getTemplateId();
      }
    }
    return null;
  }
}
