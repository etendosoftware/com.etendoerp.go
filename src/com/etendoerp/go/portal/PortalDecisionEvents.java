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

package com.etendoerp.go.portal;

/**
 * The KPI hook points of the portal's quotation decisions (ETP-6209 Part C).
 *
 * <p>Deliberately empty: the PO is redefining the portal KPIs, so no event name or semantics is
 * fixed here. Each method is called exactly where its moment happens, with what an event would
 * need, so wiring the events later is a change to this class only — the same shape as
 * {@link PortalUsageEvents}, which carries the Part A/B events.</p>
 */
final class PortalDecisionEvents {

  private PortalDecisionEvents() {
  }

  /** A decision was requested and its confirmation email left (after the send's commit). */
  static void requested(PortalSession session, String quotationId, String decision) {
    // Hook point, see the class comment.
  }

  /** A request was confirmed and applied to the quotation (after the confirmation's commit). */
  static void confirmed(String decisionId, String quotationId, String decision) {
    // Hook point, see the class comment.
  }

  /** Other pending requests of the quotation were voided by a confirmation. */
  static void voided(String quotationId, int count) {
    // Hook point, see the class comment.
  }

  /** A confirmation was refused because the quotation was no longer pending or had expired. */
  static void notApplied(String decisionId, String quotationId, String reason) {
    // Hook point, see the class comment.
  }
}
