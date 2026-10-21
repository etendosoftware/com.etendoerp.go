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

package com.etendoerp.go.schemaforge;

import java.util.List;
import java.util.Map;

import com.etendoerp.go.schemaforge.util.NeoActionAvailability;

/**
 * The availability declarations the document customizations share (ETP-5714): in which
 * {@code documentStatus} (and, for accounting, {@code posted}) an action applies.
 *
 * <p>These are the customizations' own vocabulary — each handler names this class explicitly from
 * its {@code actionContracts()}; nothing selects an entity by it. All of it is advisory
 * ({@link NeoActionAvailability}): the handlers' guards stay the enforcement.</p>
 */
final class DocumentAvailability {

  /** The status property every document header exposes. */
  static final String PROP_DOCUMENT_STATUS = "documentStatus";
  /** The accounting posting state of a document header. */
  static final String PROP_POSTED = "posted";
  /** Document status: draft. */
  static final String STATUS_DRAFT = "DR";
  /** Document status: completed. */
  static final String STATUS_COMPLETED = "CO";
  /** Posted value: booked in the general ledger. */
  static final String POSTED_YES = "Y";

  /** The action applies to a draft. */
  static final NeoActionAvailability WHEN_DRAFT =
      NeoActionAvailability.whenIn(PROP_DOCUMENT_STATUS, STATUS_DRAFT);

  /** The action applies to a completed document. */
  static final NeoActionAvailability WHEN_COMPLETED =
      NeoActionAvailability.whenIn(PROP_DOCUMENT_STATUS, STATUS_COMPLETED);

  /** {@code post}: a completed document that is not booked yet. */
  static final NeoActionAvailability WHEN_POSTABLE =
      WHEN_COMPLETED.andWhen(PROP_POSTED, NeoActionAvailability.NOT_PREFIX + POSTED_YES);

  /** {@code unpost}: a completed document that is booked. */
  static final NeoActionAvailability WHEN_UNPOSTABLE =
      WHEN_COMPLETED.andWhen(PROP_POSTED, POSTED_YES);

  /**
   * What every goods document (receipt, shipment, return) says about {@code documentAction}: only
   * a draft can be completed, and a completed document cannot be reactivated or voided through
   * MCP (ETP-5714, decision 4).
   */
  static final String GOODS_COMPLETED_NOTE = "A completed document cannot be reactivated or voided "
      + "through MCP; it must be corrected with a return.";

  /**
   * {@code documentAction} of a goods document: CO for a draft, nothing once completed. Any other
   * status (voided, waiting approval...) is not judged, and the completed-document note is only
   * ever given for a completed one.
   */
  static final NeoActionAvailability GOODS_DOCUMENT_ACTION = NeoActionAvailability
      .valuesByStatus(PROP_DOCUMENT_STATUS,
          Map.of(STATUS_DRAFT, List.of("CO"), STATUS_COMPLETED, List.of()))
      .withNote(GOODS_COMPLETED_NOTE);

  /** The hint a refused {@code docAction} value carries on a goods document. */
  static final String GOODS_REFUSAL_HINT = "Only CO is offered through MCP, to complete a draft. "
      + GOODS_COMPLETED_NOTE;

  private DocumentAvailability() {
  }
}
