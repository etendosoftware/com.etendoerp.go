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
import java.util.Objects;

import org.openbravo.model.materialmgmt.transaction.ShipmentInOut;

/**
 * Reusable {@link TargetCreator} for goods movements (ETP-5576): creates the DRAFT goods shipment
 * ({@code SALES}) or goods receipt ({@code PURCHASE}) of ANY source. Identity-free: it never reads
 * the source itself. The source side supplies
 * <ul>
 *   <li>a {@link SourceMapper} — source header/lines → the neutral {@link InOutTargetBuilder.Header}
 *       and {@link InOutTargetBuilder.Line}s (warehouse and order resolution included, since how
 *       they are found is source-specific), and</li>
 *   <li>an {@link InOutTargetBuilder.LineLinker} — how each created movement line is linked back
 *       to its source line.</li>
 * </ul>
 * Building is delegated to {@link InOutTargetBuilder#build}, which resolves every prerequisite
 * (doc type, storage bin) before persisting anything.
 *
 * <p>Today: invoice → shipment/receipt, with {@link InvoiceInOutMapping}. Order → shipment/receipt
 * reuses this class as is, with an order mapper and a linker calling
 * {@link InvoiceLineLinker#linkPendingInvoiceLinesToInout}.
 */
final class InOutFollowUpCreator implements TargetCreator {

  /**
   * Maps one source and its pending lines to the movement's neutral input. Throws
   * {@link FollowUpException.Reason#NOT_FOUND} when the source no longer exists. Must NOT
   * persist anything: a missing warehouse is reported by leaving it {@code null} in the header
   * (the builder rejects it as {@code MISSING_SETUP}). A source without a warehouse of its own
   * resolves it through {@link InOutWarehouseResolver}, which honors the caller's
   * {@code warehouseId} input and may reject with {@code WAREHOUSE_REQUIRED} /
   * {@code INVALID_INPUT}.
   */
  @FunctionalInterface
  interface SourceMapper {
    /**
     * Maps a source and its pending lines to the movement's neutral header and lines.
     *
     * @param sourceId the source record
     * @param pendingLines the lines {@link PendingResolver#loadSources} declared for this source
     * @param inputs the caller's optional choices (never {@code null})
     * @return the neutral header and one {@link InOutTargetBuilder.Line} per pending line
     * @throws FollowUpException with {@code NOT_FOUND} when the source no longer exists
     */
    Mapping map(String sourceId, List<PendingResolver.SourceLine> pendingLines,
        FollowUpInputs inputs);
  }

  /** The neutral movement input produced by a {@link SourceMapper}. */
  static final class Mapping {
    private final InOutTargetBuilder.Header header;
    private final List<InOutTargetBuilder.Line> lines;

    Mapping(InOutTargetBuilder.Header header, List<InOutTargetBuilder.Line> lines) {
      this.header = header;
      this.lines = Collections.unmodifiableList(lines);
    }

    InOutTargetBuilder.Header getHeader() {
      return header;
    }

    List<InOutTargetBuilder.Line> getLines() {
      return lines;
    }
  }

  private final InOutTargetBuilder.Direction direction;
  private final SourceMapper mapper;
  private final InOutTargetBuilder.LineLinker linker;

  /**
   * @param direction the movement direction ({@code SALES} = goods shipment)
   * @param mapper source → neutral header/lines
   * @param linker created movement line → its source line
   */
  InOutFollowUpCreator(InOutTargetBuilder.Direction direction, SourceMapper mapper,
      InOutTargetBuilder.LineLinker linker) {
    this.direction = Objects.requireNonNull(direction, "direction");
    this.mapper = Objects.requireNonNull(mapper, "mapper");
    this.linker = Objects.requireNonNull(linker, "linker");
  }

  @Override
  public Result createTarget(String sourceId, List<PendingResolver.SourceLine> pendingLines,
      FollowUpInputs inputs) {
    Mapping mapping = mapper.map(sourceId, pendingLines, inputs);
    ShipmentInOut inout = InOutTargetBuilder.build(direction, mapping.getHeader(),
        mapping.getLines(), linker);
    return new Result(inout.getId(), inout.getDocumentNo(), mapping.getLines().size());
  }
}
