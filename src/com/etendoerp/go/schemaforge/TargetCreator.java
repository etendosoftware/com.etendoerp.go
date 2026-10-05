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

/**
 * The "how to create" half of a follow-up flow (ETP-5576): builds, persists and links the target
 * document from the pending lines a {@link PendingResolver} declared. Composed with a resolver and
 * the follow-up's identity by a {@link FollowUpFlow}.
 *
 * <p>A creator is reusable across sources: {@link InOutFollowUpCreator} builds a goods movement
 * from ANY source through a source mapping and a line-link callback the source side supplies; a
 * future invoice creator would do the same for order → invoice and shipment → invoice (and may
 * delegate to an existing NEO action). Any other process is just another creator.
 */
@FunctionalInterface
interface TargetCreator {

  /**
   * Builds, persists and links the target from the (non-empty) pending lines of an eligible
   * source. Every remaining precondition (warehouse, document type, bin…) is resolved BEFORE
   * anything is persisted, failing with {@link FollowUpException.Reason#MISSING_SETUP}; the
   * caller rolls the transaction back on any exception.
   *
   * @param sourceId the source record
   * @param pendingLines the lines {@link PendingResolver#loadSources} declared for this source
   *     (non-empty)
   */
  Result createTarget(String sourceId, List<PendingResolver.SourceLine> pendingLines);

  /**
   * The created target document. Which follow-up it is (key, spec, entity) is the
   * {@link FollowUpFlow}'s {@link FollowUpTarget}, not the creator's: one creator serves several
   * flows.
   */
  final class Result {
    private final String id;
    private final String documentNo;
    private final int lineCount;

    Result(String id, String documentNo, int lineCount) {
      this.id = id;
      this.documentNo = documentNo;
      this.lineCount = lineCount;
    }

    String getId() {
      return id;
    }

    String getDocumentNo() {
      return documentNo;
    }

    int getLineCount() {
      return lineCount;
    }
  }
}
