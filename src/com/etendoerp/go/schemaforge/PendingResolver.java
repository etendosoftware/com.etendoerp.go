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

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.openbravo.base.structure.BaseOBObject;

/**
 * The "what is missing" half of a follow-up flow (ETP-5576): for a source document, is the
 * follow-up available, and which lines would it carry with what quantity. The "how to create" half
 * is a {@link TargetCreator}; the two are composed, with the follow-up's identity, by a
 * {@link FollowUpFlow}. Identity-free as a contract: implementations are the source entity's
 * customization (e.g. {@link InvoicePendingResolver}), the interface names no entity.
 *
 * <p><b>The resolver alone defines "pending"</b> — by line quantities (invoice → movement), by
 * amounts (order → invoice), or anything else — and the resolver alone decides how an existing
 * draft of the target counts (as already moved, or as
 * {@link FollowUpException.Reason#DRAFT_IN_PROGRESS}). It also owns the eligibility rules
 * (direction, status, document type…). The shared layer never recomputes any of it;
 * {@link FollowUpDocumentService#pendingQuantity} is an opt-in helper, nothing more.
 *
 * <p>A resolver is independent of the target it feeds: the same resolver can be composed with
 * different creators, and the same creator with different resolvers (e.g. an order resolver
 * reusing {@link InOutFollowUpCreator}).
 */
interface PendingResolver {

  /**
   * The DAL entity class of the source header (e.g. {@code Invoice.class}). Used ONLY by
   * {@link FollowUpActionHandler} to check, through {@link TenantOwnership}, that the requested
   * record belongs to the caller's readable clients and organizations before anything runs in
   * admin mode.
   *
   * @return the DAL entity class of the source header
   */
  Class<? extends BaseOBObject> sourceEntity();

  /**
   * Decides, for every id, whether the follow-up is available and which lines it would carry —
   * in a fixed number of queries regardless of how many ids are passed. Read-only. An id with no
   * matching source record is absent from the returned map. Build the entries with
   * {@link Source#available}, {@link Source#unavailable} or {@link Source#fromLines}.
   *
   * <p>Must query through {@code OBDal.getInstance()}'s connection: on the GET path
   * {@link FollowUpDocumentService#annotatePage} wraps this call in a savepoint on that
   * connection, so a failure degrades to {@code FOLLOW_UP_LOOKUP_FAILED} without aborting the
   * request's transaction.
   *
   * @param sourceIds the source record ids to evaluate
   * @return one verdict per known id, keyed by id; unknown ids are absent
   */
  Map<String, Source> loadSources(Collection<String> sourceIds);

  /**
   * Takes a row lock on the source record (and whatever else its pending depends on) until the
   * current transaction ends, so that two simultaneous requests cannot both see the same quantity
   * as pending. Called BEFORE {@link #loadSources} on the create path.
   *
   * @param sourceId the source record to lock
   */
  void lockSource(String sourceId);

  // ---------------------------------------------------------------------------------------------
  // Value types
  // ---------------------------------------------------------------------------------------------

  /**
   * The resolver's verdict on one source record: available (with the lines to carry), or not
   * available and why.
   */
  final class Source {
    private final String id;
    private final FollowUpException.Reason unavailability;
    private final List<SourceLine> lines;

    private Source(String id, FollowUpException.Reason unavailability, List<SourceLine> lines) {
      this.id = id;
      this.unavailability = unavailability;
      this.lines = lines != null ? Collections.unmodifiableList(lines) : Collections.emptyList();
    }

    /**
     * Available, carrying {@code lines}. An empty list is accepted for resolvers whose
     * availability is not line-based (e.g. "an amount is still pending"), but
     * {@link FollowUpDocumentService#create} still refuses to create a document with no line.
     */
    static Source available(String id, List<SourceLine> lines) {
      return new Source(id, null, lines);
    }

    /** Not available, for {@code reason}. */
    static Source unavailable(String id, FollowUpException.Reason reason) {
      return new Source(id, reason, null);
    }

    /**
     * The usual verdict of a line-quantity resolver: {@code ineligibility} when not {@code null};
     * otherwise {@link FollowUpException.Reason#NOTHING_PENDING} when {@code pendingLines} is
     * empty; otherwise available with those lines.
     */
    static Source fromLines(String id, FollowUpException.Reason ineligibility,
        List<SourceLine> pendingLines) {
      if (ineligibility != null) {
        return unavailable(id, ineligibility);
      }
      if (pendingLines == null || pendingLines.isEmpty()) {
        return unavailable(id, FollowUpException.Reason.NOTHING_PENDING);
      }
      return available(id, pendingLines);
    }

    String getId() {
      return id;
    }

    boolean isAvailable() {
      return unavailability == null;
    }

    /** {@code null} when available. */
    FollowUpException.Reason getUnavailability() {
      return unavailability;
    }

    /** The lines to carry when available; empty otherwise. */
    List<SourceLine> getLines() {
      return lines;
    }
  }

  /**
   * One line the follow-up would carry, with the quantity the RESOLVER decided is pending. Pure
   * data, and deliberately minimal and target-independent: everything else the target needs
   * (product, UOM, upstream order line, whether a movement line needs a storage bin, …) is read by
   * the creator's source mapping from its source line, so it never has to travel through — or be
   * kept in sync with — the shared layer.
   */
  final class SourceLine {
    private final String sourceLineId;
    private final BigDecimal pendingQty;

    /**
     * @param sourceLineId the source line id
     * @param pendingQty the quantity to carry, non-zero. Its sign is the resolver's call: the
     *     invoice resolver only carries positive quantities, an order resolver reusing
     *     {@link InOutLineFromOrderFactory#pendingQuantityFor} may carry negative ones (ETP-4722)
     */
    SourceLine(String sourceLineId, BigDecimal pendingQty) {
      this.sourceLineId = sourceLineId;
      this.pendingQty = pendingQty;
    }

    String getSourceLineId() {
      return sourceLineId;
    }

    BigDecimal getPendingQty() {
      return pendingQty;
    }
  }
}
