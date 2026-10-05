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
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.businesspartner.BusinessPartner;
import org.openbravo.model.common.businesspartner.Location;
import org.openbravo.model.common.currency.Currency;
import org.openbravo.model.common.enterprise.DocumentType;
import org.openbravo.model.common.enterprise.Locator;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.common.enterprise.Warehouse;
import org.openbravo.model.common.order.Order;
import org.openbravo.model.common.order.OrderLine;
import org.openbravo.model.common.plm.AttributeSetInstance;
import org.openbravo.model.common.plm.Product;
import org.openbravo.model.common.uom.UOM;
import org.openbravo.model.materialmgmt.transaction.ShipmentInOut;
import org.openbravo.model.materialmgmt.transaction.ShipmentInOutLine;

/**
 * Builds a DRAFT goods movement ({@code M_InOut}: goods shipment or goods receipt) from a
 * neutral description of its header and lines — the reusable target half of every
 * "… → shipment/receipt" follow-up (ETP-5576), driven by {@link InOutFollowUpCreator}. It knows
 * nothing about the source document: the source side maps its own header/lines to
 * {@link Header}/{@link Line} ({@link InOutFollowUpCreator.SourceMapper}) and passes a
 * {@link LineLinker} saying how each created line is linked back to its source line.
 *
 * <p><b>Target-builder contract</b> (the seam a future invoice target builder for order → invoice
 * or shipment → invoice follows): a static {@code build(direction, header, lines, linker)} that
 * (1) resolves every prerequisite and throws {@link FollowUpException.Reason#MISSING_SETUP}
 * BEFORE persisting anything, (2) persists header + lines, (3) flushes, (4) calls the linker once
 * per created line, (5) returns the persisted header. The transaction is the caller's.
 *
 * <p>What it does, for both directions:
 * <ul>
 *   <li>document type: active, non-return, default first, of {@link Direction#docBaseType()} and
 *       {@code IsSOTrx} ({@link NeoCommercialDocumentFactory#findInOutDocType});</li>
 *   <li>storage bin: resolved only when some line {@link Line#isStockable() is stockable}, from
 *       the header warehouse's default bin, and anchored per line to that warehouse
 *       ({@link NeoHandlerUtils#anchorLocatorToWarehouse}, ETP-4863); non-stockable lines get
 *       {@code null} and never go through the anchor (ETP-5276);</li>
 *   <li>documentNo: the sequence fallback {@link NeoCommercialDocumentFactory#ensureInOutDocumentNo}.</li>
 * </ul>
 */
final class InOutTargetBuilder {

  private static final Logger log = LogManager.getLogger(InOutTargetBuilder.class);

  private InOutTargetBuilder() {
  }

  /**
   * Transaction direction of a goods movement. Everything that differs between a shipment and a
   * receipt AS A MOVEMENT is here, selected by {@code IsSOTrx} — structure, not identity. How a
   * movement line is linked back to its source (e.g. the invoice match tables,
   * {@link InOutInvoiceLinks.MatchTable}) is the linker's business, not the builder's.
   */
  enum Direction {
    /** Sales side: goods shipment, {@code MMS}, movement {@code C-}. */
    SALES(true, "C-", "MMS"),
    /** Purchase side: goods receipt, {@code MMR}, movement {@code V+}. */
    PURCHASE(false, "V+", "MMR");

    private final boolean salesTransaction;
    private final String movementType;
    private final String docBaseType;

    Direction(boolean salesTransaction, String movementType, String docBaseType) {
      this.salesTransaction = salesTransaction;
      this.movementType = movementType;
      this.docBaseType = docBaseType;
    }

    static Direction forSalesTransaction(boolean salesTransaction) {
      return salesTransaction ? SALES : PURCHASE;
    }

    boolean isSalesTransaction() {
      return salesTransaction;
    }

    String movementType() {
      return movementType;
    }

    String docBaseType() {
      return docBaseType;
    }
  }

  /** Links one created movement line back to its source line; called after the flush. */
  @FunctionalInterface
  interface LineLinker {
    void link(Line line, ShipmentInOutLine created);
  }

  /**
   * Neutral header of the movement. {@code warehouse} is resolved by the caller (how is
   * source-specific); {@code null} is rejected as MISSING_SETUP. {@code order} may be
   * {@code null}.
   */
  static final class Header {
    private final Client client;
    private final Organization organization;
    private final BusinessPartner businessPartner;
    private final Location partnerAddress;
    private final Warehouse warehouse;
    private final Currency currency;
    private final Order order;

    Header(Client client, Organization organization, BusinessPartner businessPartner,
        Location partnerAddress, Warehouse warehouse, Currency currency, Order order) {
      this.client = client;
      this.organization = organization;
      this.businessPartner = businessPartner;
      this.partnerAddress = partnerAddress;
      this.warehouse = warehouse;
      this.currency = currency;
      this.order = order;
    }
  }

  /** Neutral movement line. {@code sourceLineId} is opaque to the builder — for the linker. */
  static final class Line {
    private final String sourceLineId;
    private final Product product;
    private final UOM uom;
    private final AttributeSetInstance attributeSetValue;
    private final BigDecimal quantity;
    private final OrderLine orderLine;
    private final String description;
    private final boolean stockable;

    Line(String sourceLineId, Product product, UOM uom, AttributeSetInstance attributeSetValue,
        BigDecimal quantity, OrderLine orderLine, String description, boolean stockable) {
      this.sourceLineId = sourceLineId;
      this.product = product;
      this.uom = uom;
      this.attributeSetValue = attributeSetValue;
      this.quantity = quantity;
      this.orderLine = orderLine;
      this.description = description;
      this.stockable = stockable;
    }

    String getSourceLineId() {
      return sourceLineId;
    }

    boolean isStockable() {
      return stockable;
    }
  }

  /**
   * Builds and persists the movement; see the class Javadoc for the contract.
   *
   * @throws FollowUpException with {@code MISSING_SETUP} when warehouse, document type or a
   *     needed storage bin is missing — before anything is persisted
   */
  static ShipmentInOut build(Direction direction, Header header, List<Line> lines,
      LineLinker linker) {
    if (header.warehouse == null) {
      throw new FollowUpException(FollowUpException.Reason.MISSING_SETUP,
          "Could not determine the warehouse for the new document");
    }
    DocumentType docType = NeoCommercialDocumentFactory.findInOutDocType(header.client,
        direction.docBaseType(), direction.isSalesTransaction());
    if (docType == null) {
      throw new FollowUpException(FollowUpException.Reason.MISSING_SETUP,
          "No document type found (docBaseType=" + direction.docBaseType() + ", isSOTrx="
              + direction.isSalesTransaction() + ", isReturn=false)");
    }
    Locator locator = null;
    if (lines.stream().anyMatch(Line::isStockable)) {
      locator = NeoCommercialDocumentFactory.findDefaultLocator(header.warehouse);
      if (locator == null) {
        throw new FollowUpException(FollowUpException.Reason.MISSING_SETUP,
            "No storage bin found for warehouse: " + header.warehouse.getName());
      }
    }

    ShipmentInOut inout = createHeader(direction, header, docType);
    OBDal.getInstance().save(inout);
    List<ShipmentInOutLine> created = new ArrayList<>(lines.size());
    long lineNo = 10;
    for (Line line : lines) {
      created.add(createLine(inout, line, locator, lineNo));
      lineNo += 10;
    }
    // Linkers typically write native SQL keyed by the new line ids: the lines must be in the DB.
    OBDal.getInstance().flush();
    for (int i = 0; i < created.size(); i++) {
      linker.link(lines.get(i), created.get(i));
    }
    NeoCommercialDocumentFactory.ensureInOutDocumentNo(inout, log);
    OBDal.getInstance().flush();
    return inout;
  }

  private static ShipmentInOut createHeader(Direction direction, Header header,
      DocumentType docType) {
    ShipmentInOut inout = OBProvider.getInstance().get(ShipmentInOut.class);
    inout.setClient(header.client);
    inout.setOrganization(header.organization);
    inout.setBusinessPartner(header.businessPartner);
    inout.setPartnerAddress(header.partnerAddress);
    inout.setWarehouse(header.warehouse);
    Date now = new Date();
    inout.setMovementDate(now);
    inout.setAccountingDate(now);
    inout.setDocumentType(docType);
    inout.setDocumentNo("<*>");
    inout.setSalesTransaction(direction.isSalesTransaction());
    inout.setSalesOrder(header.order);
    inout.setProcessed(false);
    inout.setDocumentStatus("DR");
    inout.setMovementType(direction.movementType());
    // ETP-4028: EM_Etgo_Currency_ID is mandatory on M_InOut — every new record must carry it.
    inout.setEtgoCurrency(header.currency);
    return inout;
  }

  private static ShipmentInOutLine createLine(ShipmentInOut inout, Line source, Locator locator,
      long lineNo) {
    ShipmentInOutLine line = OBProvider.getInstance().get(ShipmentInOutLine.class);
    line.setClient(inout.getClient());
    line.setOrganization(inout.getOrganization());
    line.setShipmentReceipt(inout);
    line.setLineNo(lineNo);
    line.setProduct(source.product);
    line.setUOM(source.uom);
    line.setAttributeSetValue(source.attributeSetValue);
    line.setStorageBin(source.stockable
        ? NeoHandlerUtils.anchorLocatorToWarehouse(locator, inout.getWarehouse(), log)
        : null);
    line.setMovementQuantity(source.quantity);
    line.setSalesOrderLine(source.orderLine);
    line.setDescription(source.description);
    OBDal.getInstance().save(line);
    return line;
  }
}
