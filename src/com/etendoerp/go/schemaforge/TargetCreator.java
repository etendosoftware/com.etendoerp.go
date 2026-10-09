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

import java.util.Collections;
import java.util.List;

import com.etendoerp.go.schemaforge.util.NeoActionContract;

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
   * <p><b>Caller choices.</b> {@code inputs} carries the optional choices of the request body. A
   * creator reads only the keys it defines, validates them before persisting anything
   * ({@link FollowUpException.Reason#INVALID_INPUT} when unacceptable), and ignores the rest.
   * When it needs a choice it cannot make on its own, it throws a {@link FollowUpException}
   * carrying a {@link FollowUpException.RequiredInput} (key + options) so the client can ask the
   * user and retry.
   *
   * @param sourceId the source record
   * @param pendingLines the lines {@link PendingResolver#loadSources} declared for this source
   *     (non-empty)
   * @param inputs the caller's optional choices; never {@code null} ({@link FollowUpInputs#none()})
   * @return the created target document (id, documentNo, line count)
   */
  Result createTarget(String sourceId, List<PendingResolver.SourceLine> pendingLines,
      FollowUpInputs inputs);

  /**
   * The request-body keys this creator reads from {@code inputs}, as action-contract parameters —
   * published to agents by {@link FollowUpSupport#actionContracts()} (ETP-5576, MCP-8). The creator
   * is the only authority on them: it is the one that reads and validates them. Every key is
   * optional; a required choice is asked for through {@link FollowUpException.RequiredInput}.
   *
   * @return the declared inputs; none by default
   */
  default List<NeoActionContract.Param> inputParams() {
    return Collections.emptyList();
  }

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
