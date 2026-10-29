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

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Collections;
import java.util.List;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;

import org.openbravo.model.common.invoice.Invoice;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.enterprise.DocumentType;

import com.etendoerp.go.schemaforge.util.NeoActionContract;
import com.etendoerp.go.schemaforge.handlers.DocumentPostingService;
import com.etendoerp.go.schemaforge.handlers.ReactivationUnpost;
import com.etendoerp.go.schemaforge.handlers.PaymentMethodSelectorSupport;
import org.openbravo.base.structure.BaseOBObject;

/**
 * NeoHandler for the Sales Invoice header entity.
 *
 * <p>Extends {@link AbstractInvoiceHeaderHandler} to inherit shared document-type-lock
 * enforcement and GET enrichment logic.
 *
 * <p>ETP-5238: the {@code paymentMethod} SELECTOR is served by
 * {@link PaymentMethodSelectorSupport}, independent of Financial Account linkage.
 *
 * <p>Subtype resolution for AR invoices (ETP-4737 — unified "Factura Rectificativa"):
 * <ul>
 *   <li>{@code EM_Etsg_Isrectificative = 'Y'} → RECTIFICATIVA (new unified type)</li>
 *   <li>legacy fallback — {@code ARC} (Credit Memo) or {@code ARI_RM} (Return Invoice) → RECTIFICATIVA</li>
 *   <li>otherwise → FAC (Standard Invoice)</li>
 * </ul>
 *
 * <p>GET enrichment injects {@code arInvoiceSubtype} into every record (list and detail)
 * and negates {@code grandTotalAmount} / {@code outstandingAmount} for RECTIFICATIVA subtype.
 * {@code docTypeLocked} is injected only in detail-view responses.
 *
 * <p>Dispatches custom ACTION requests:
 * <ul>
 *   <li>{@code cloneRecord} → {@link NeoCloneRecordHandler}</li>
 *   <li>{@code registerPayment} / {@code invoicePayments} / {@code invoiceAccounts} → {@link RegisterPaymentHandler}</li>
 *   <li>{@code Em_Aeatsii_Send} → {@link SiiSendHandler}</li>
 *   <li>{@code Em_Tbai_Xmlgenerator} → {@link TbaiXmlgeneratorHandler}</li>
 *   <li>{@code createShipment} → {@link FollowUpActionHandler} with a {@link FollowUpFlow} of
 *       {@link InvoicePendingResolver} + {@link InOutFollowUpCreator} ({@code SALES}): the draft
 *       goods shipment for the still-pending quantities (ETP-5576)</li>
 * </ul>
 *
 * <p><b>Binding.</b> {@link NeoExtension} only (ETP-5588): the ETGO_SF_ENTITY row carries no
 * {@code Java_Qualifier} and the class no {@code @Named}. Every surface — REST single and batch,
 * MCP, callout, actions, field filter — reaches it through the annotation. See
 * {@code neo-headless.md} §5.3.a.
 */
@NeoExtension(spec = "sales-invoice", entity = "header")
public class SalesInvoiceHeaderHandler extends AbstractInvoiceHeaderHandler implements NeoHandler {

  private static final Logger log = LogManager.getLogger(SalesInvoiceHeaderHandler.class);

  private static final String FIELD_DOCUMENT_NO = "documentNo";

  @Inject
  private NeoCloneRecordHandler cloneRecordHandler;

  @Inject
  private RegisterPaymentHandler registerPaymentHandler;

  @Inject
  private SiiSendHandler siiSendHandler;

  @Inject
  private TbaiXmlgeneratorHandler tbaiXmlgeneratorHandler;

  @Inject
  private TotalDiscountService totalDiscountService;

  /**
   * ETP-5840: the rate is the one valid on the {@code accountingDate} ({@code DateAcct}) — not the
   * invoice date — shown as "Accounting date". Invoice-family rules in
   * {@link DocumentRateGuards#guardInvoiceHeader}.
   */
  static final DocumentCurrencyRate.RateType RATE_TYPE = new DocumentCurrencyRate.RateType(
      Invoice.ENTITY_NAME, Invoice.PROPERTY_ETGOCURRENCYRATE, Invoice.PROPERTY_ACCOUNTINGDATE,
      DocumentRateMessages.LABEL_ACCOUNTING_DATE);

  private final DocumentCurrencyRate currencyRate = new DocumentCurrencyRate(RATE_TYPE);


  @Inject
  private DocumentPostingService postingService;

  @Inject
  private CurrencyOptionsHandler currencyOptionsHandler;

  @Inject
  private ImportOrderLinesIntoInvoiceHandler importOrderLinesHandler;

  @Inject
  private ImportInOutLinesIntoInvoiceHandler importShipmentLinesHandler;

  @Inject
  private ImportOriginInvoiceLinesHandler importOriginInvoiceLinesHandler;

  /** Package-private seam so unit tests can inject a mocked {@link DocumentPostingService}. */
  void setPostingService(DocumentPostingService postingService) {
    this.postingService = postingService;
  }

  /**
   * The actions this header serves through its delegates, declared for agents (ETP-5558): the
   * invoice payment actions, {@code currencyOptions}, the follow-up document actions
   * ({@link FollowUpSupport#actionContracts()}, ETP-5576) and {@code post} / {@code unpost}
   * (ETP-5692) and {@code cloneRecord} (ETP-5709). Published by the MCP next to the AD
   * buttons; REST and the SPA do not read it.
   */
  @Override
  public Map<String, NeoActionContract> actionContracts() {
    Map<String, NeoActionContract> contracts =
        new LinkedHashMap<>(PaymentActionHandlerSupport.actionContracts(true));
    contracts.put(CurrencyOptionsHandler.CONTRACT.getName(), CurrencyOptionsHandler.CONTRACT);
    contracts.putAll(followUp.actionContracts());
    // ETP-5692: post / unpost were served (DocumentPostingService) but undeclared, so an agent
    // had to guess them; the raw `posted` AD button is hidden by MCP_CONFIG in their favour.
    contracts.putAll(InvoicePostingGate.CONTRACTS);
    // ETP-5709: cloneRecord was served (see handle) but undeclared.
    contracts.put(NeoCloneRecordHandler.CONTRACT.getName(), NeoCloneRecordHandler.CONTRACT);
    // ETP-5713: import the pending lines of a completed order / goods shipment into this draft.
    contracts.putAll(ImportOrderLinesIntoInvoiceHandler.contracts(true));
    contracts.putAll(ImportInOutLinesIntoInvoiceHandler.contracts(true));
    // ETP-5876: a DRAFT rectificative takes its lines from a return or from the invoice it
    // rectifies instead (the window's "Import from return" / "Import from source invoice").
    contracts.putAll(ImportInOutLinesIntoInvoiceHandler.returnContracts(true));
    contracts.putAll(ImportOriginInvoiceLinesHandler.contracts(true));
    return contracts;
  }

  /**
   * {@inheritDoc} Reactivate is ruled out for an invoice {@link InvoiceReactivationGuard} would
   * refuse to reactivate (ETP-5714), and the import actions that do not suit the kind of invoice
   * it is: the order and goods ones on a rectificative, the return and origin ones on any other
   * (ETP-5876).
   */
  @Override
  public Map<String, String> unavailableActions(BaseOBObject subject) {
    return InvoiceDocumentAvailability.unavailable(subject,
        RectificativeImportPolicy.forHeader(this::classifyDocType, true));
  }

  /** The PIS actions: served to the SPA, never to an agent (ETP-5558). */
  @Override
  public Set<String> agentExcludedActions() {
    return PaymentActionHandlerSupport.AGENT_EXCLUDED_ACTIONS;
  }

  @Override
  public NeoResponse handle(NeoContext context) {
    NeoResponse paymentMethodSelector = PaymentMethodSelectorSupport.handleIfPaymentMethodSelector(context,
        PaymentMethodSelectorSupport.DirectionFallback.WINDOW);
    if (paymentMethodSelector != null) {
      return paymentMethodSelector;
    }
    // ETP-5692: completed-invoice write fence + unpost status gate, before anything is captured.
    NeoResponse invoiceGuard =
        InvoicePostingGate.checkHeaderRequest(context, completedEditableHeaderFields());
    if (invoiceGuard != null) {
      return invoiceGuard;
    }
    NeoHandlerUtils.mirrorAccountingDateOnCreate(context, "invoiceDate", "accountingDate");
    // ETP-5703: server-side defaults for a write that leaves them out (an agent; the SPA sends both).
    resolveServerCreateFields(context);
    // ETP-5840: exchange rate — recalculation on the accounting date, save and completion
    // validation — before anything with side effects runs.
    NeoResponse rateRefusal = DocumentRateGuards.guardInvoiceHeader(context, currencyRate);
    if (rateRefusal != null) {
      return rateRefusal;
    }
    rectificationLinks.captureOriginInvoice(context);
    NeoResponse siiAuthError = captureAndValidateSiiAuthorization(context);
    if (siiAuthError != null) {
      return siiAuthError;
    }
    NeoResponse posting = postingService != null ? postingService.handleAction(context) : null;
    if (posting != null) {
      return posting;
    }
    // ETP-5381: the invoice quantity is NOT capped against the shipment any more. The order is the
    // commitment; a shipment — especially an unconfirmed one — is not. See
    // AbstractInvoiceHeaderHandler's "removed" note for the full rationale. Over-invoicing relative
    // to the ORDER is still rejected, by the core, in C_INVOICE_POST (@QtyInvoicedHigherOrdered@).
    // ETP-5710: refuse to complete an invoice with a line that kept a net amount of 0 next to a
    // quantity and a price — before the discount is recalculated and before the document action runs.
    NeoResponse unpricedLines = InvoiceCompletionLineGuard.refuseIfLinesHaveNoAmount(context);
    if (unpricedLines != null) {
      return unpricedLines;
    }
    // Must run BEFORE completeInvoiceIfNeeded: the discount line has to reflect the final set of
    // product lines before ProcessInvoiceUtil.process() completes/posts the document (ETP-4388).
    AbstractOrderHeaderHandler.applyTotalDiscountBeforeComplete(context, totalDiscountService, true);
    NeoResponse completionResponse = completeInvoiceIfNeeded(context);
    if (completionResponse != null) {
      return completionResponse;
    }
    if (NeoEndpointType.CRUD.equals(context.getEndpointType())) {
      NeoResponse lockError = validateDocTypeLock(context);
      if (lockError != null) {
        return lockError;
      }
    }
    // ETP-5745: refuse reactivation of a fiscally-accepted invoice already rectified by a
    // completed rectificativa.
    NeoResponse reactivationGuard = InvoiceReactivationGuard.checkReactivation(context);
    if (reactivationGuard != null) {
      return reactivationGuard;
    }
    // ETP-5876: the import actions go with the KIND of invoice, as in the window — order and
    // shipment on a normal one, return and origin invoice on a rectificative — and the delegates
    // classify through this header's own rule, handed in on the request.
    RectificativeImportPolicy importPolicy =
        RectificativeImportPolicy.forHeader(this::classifyDocType, true);
    context.setAttribute(RectificativeImportPolicy.ATTRIBUTE, importPolicy);
    NeoResponse importKindRefusal = RectificativeImportGate.refuse(context, importPolicy);
    if (importKindRefusal != null) {
      return importKindRefusal;
    }
    NeoResponse unpostRefusal = unpostBeforeReactivation(context, postingService);
    if (unpostRefusal != null) {
      return unpostRefusal;
    }
    return NeoHeaderActionRouter.dispatch(context, currencyOptionsHandler, cloneRecordHandler,
        registerPaymentHandler, siiSendHandler, tbaiXmlgeneratorHandler, importOrderLinesHandler,
        importShipmentLinesHandler, importOriginInvoiceLinesHandler, followUp.actionHandler());
  }

  /**
   * Post-callout hook: blocks callout-driven currency updates (ETP-4029), returns the exchange rate
   * recalculated for the accounting date when the currency or that date changes (ETP-5840), and
   * blocks
   * callout-driven document type updates once the invoice has been saved (ETP-4535). Mirrors
   * {@code AbstractOrderHeaderHandler#afterCallout}.
   */
  @Override
  public NeoResponse afterCallout(NeoContext context) {
    return handleInvoiceAfterCallout(context, currencyRate);
  }

  /**
   * Adjusts grandTotalAmount / outstandingAmount for draft invoices with a total discount.
   *
   * <p>ETP-5216: the former {@code tbaiSyncEstado} injection is gone. The TicketBAI status is now
   * the stored computed AD column {@code EM_ETGO_Tbai_Status} on {@code C_Invoice}, so it travels
   * in the contract like any other column and is filterable and sortable — an injected field never
   * was, and its failures were invisible from the UI (ETP-4391).
   *
   * <p>In detail view, this also injects {@code aeatsiiFacturaId} / {@code tbaiSyncInvoiceId} /
   * {@code invoiceVerifactuId} (see {@link SifSubRecordAttachments}) so the SIF tab's Adjuntos
   * sections can list/download the fiscal XML attached to each sub-record (ETP-4888).
   */
  @Override
  public NeoResponse afterHandle(NeoContext context) {
    // ETP-5684: say what ran when the reactivation un-posted the invoice first.
    ReactivationUnpost.notePartial(context);
    // ETP-5840: a create whose currency or accounting date only the create cascade derived; before
    // the rate-document sync, which must see the resolved rate.
    NeoResponse rateRefusal = currencyRate.afterWrite(context);
    if (rateRefusal != null) {
      return rateRefusal;
    }
    autoCreateOrUpdateConversionRateDocument(context);
    // POST/PUT/PATCH: persist origin invoice relationship after the record is saved.
    // Mirrors PurchaseInvoiceHeaderHandler#afterHandle (ETP-4737) — the manual
    // "Import from Source Invoice" flow links a rectificativa back to its source via
    // C_Invoice_Reverse, same mechanism shared through AbstractInvoiceHeaderHandler.
    if (NeoEndpointType.CRUD.equals(context.getEndpointType())
        && NeoHandlerUtils.isWriteMethod(context.getHttpMethod())) {
      rectificationLinks.persistOriginInvoice(context);
      persistSiiAuthorizationno(context);
    }
    if (!"GET".equals(context.getHttpMethod()) || !NeoEndpointType.CRUD.equals(context.getEndpointType())) {
      // ETP-4783: the main CRUD write captures its response before afterHandle() runs, so
      // aeatsiiAuthorizationno (written by persistSiiAuthorizationno()) is absent from the
      // PATCH response. Inject it so the frontend sees the correct value without a re-fetch.
      return injectAuthorizationnoIntoSaveResponse(context);
    }
    NeoResponse prev = context.getPreviousResult();
    if (prev == null || prev.getBody() == null) {
      return null;
    }
    try {
      JSONObject body = prev.getBody();
      JSONArray dataArr = NeoHandlerUtils.extractGetDataArray(context);
      if (dataArr == null || dataArr.length() == 0) {
        // ETP-5757: an empty page still carries the (zeroed) whole-set footer totals.
        return DocumentListTotals.injectInto(context, true) ? NeoResponse.ok(body) : null;
      }
      for (int i = 0; i < dataArr.length(); i++) {
        JSONObject rec = dataArr.getJSONObject(i);
        enrichInvoiceSubtype(rec, getInvoiceSubtypeKey());
        // ETP-4841: no sign rewriting here. The stored sign IS the truth — a Factura
        // Rectificativa can legitimately be positive (an under-invoiced correction, which
        // is payable), and forcing it negative made the grid contradict the detail page.
        applyTotalDiscountToRecord(rec);
      }
      // ETP-5576: followUp.{available, <key>}, one batch query per flow per page.
      followUp.annotate(dataArr);
      if (context.getRecordId() != null) {
        JSONObject rec = dataArr.getJSONObject(0);
        rectificationLinks.enrichOriginInvoice(rec, context.getRecordId());
        rectificationLinks.enrichRectifyingInvoices(rec, context.getRecordId());
        enrichDocTypeLocked(rec);
        enrichIsRectificative(rec);
        enrichTbaiConfigActive(rec);
        rectificationLinks.enrichHasRectifications(rec, context.getRecordId());
        InvoiceExemptTaxes.enrich(rec, context.getRecordId());
        enrichLinkedShipments(rec, context.getRecordId());
        InvoiceDetailEnrichment.enrichLinkedOrders(rec, context.getRecordId()); // ETP-5828
        SifSubRecordAttachments.enrich(rec, context.getRecordId());
        InvoiceReactivationGuard.enrichReactivationBlock(rec, context.getRecordId());
      }
      DocumentListTotals.injectInto(context, true); // ETP-5757, last: see AbstractInvoiceHeaderHandler
      return NeoResponse.ok(body);
    } catch (Exception e) {
      log.error("Error enriching sales invoice response", e);
      return null;
    }
  }

  // ---------------------------------------------------------------------------
  // AR-specific subtype resolution
  // ---------------------------------------------------------------------------

  /**
   * {@inheritDoc} AR: {@code EM_Etsg_Isrectificative = 'Y'} (ETP-4737 unified "Factura
   * Rectificativa") → RECTIFICATIVA; legacy fallback for pre-existing invoices — ARC (Credit
   * Memo) or ARI_RM (Return Material Sales Invoice) → RECTIFICATIVA; otherwise FAC.
   *
   * <p>{@code ETGO_GET_DELIVERY_STATUS} mirrors this rule in SQL to answer {@code NULL} for a
   * sales rectificative (ETP-5706): keep both in sync.
   */
  @Override
  protected String classifyDocType(DocumentType dt) {
    return isRectificativeDocType(dt) ? SUBTYPE_RECTIFICATIVA : SUBTYPE_FAC;
  }

  /**
   * The AR rectificative rule behind {@link #classifyDocType}, callable without a handler instance
   * — {@link SalesInvoiceLineHandler} needs it to decide whether a created line links its invoice
   * to the one it rectifies (ETP-6002).
   *
   * @param dt the document type, may be {@code null}
   * @return {@code true} for the unified rectificative type and the legacy ARC/ARI_RM categories
   */
  static boolean isRectificativeDocType(DocumentType dt) {
    if (dt == null) {
      return false;
    }
    if (RectificativeSupport.isRectificative(dt)) {
      return true;
    }
    String category = dt.getDocumentCategory();
    return "ARC".equals(category) || "ARI_RM".equals(category);
  }

  /**
   * {@inheritDoc}
   *
   * @return {@code "arInvoiceSubtype"}
   */
  @Override
  protected String getInvoiceSubtypeKey() {
    return "arInvoiceSubtype";
  }

  @Override
  protected TotalDiscountService getTotalDiscountService() {
    return totalDiscountService;
  }

  /** {@inheritDoc} A sales invoice is followed by a goods shipment (ETP-5576). */
  @Override
  protected List<FollowUpFlow> followUpFlows() {
    return Collections.singletonList(FollowUpFlow.of(FollowUpTarget.GOODS_SHIPMENT,
        new InvoicePendingResolver(InOutTargetBuilder.Direction.SALES,
            this::isStandardInvoiceDocType),
        new InOutFollowUpCreator(InOutTargetBuilder.Direction.SALES, InvoiceInOutMapping::map,
            InvoiceInOutMapping.linker(InOutTargetBuilder.Direction.SALES))));
  }

  /**
   * Injects {@code linkedShipments} into the invoice detail record: every sales goods movement
   * (M_InOut) linked to one of the invoice's lines.
   *
   * <p>"Linked" is {@link InOutInvoiceLinks#linkedInOutLineIdsSql}: the
   * {@code C_InvoiceLine.M_InOutLine_ID} column and the {@code M_MatchSI} match table — never
   * the order line (ETP-6002 dropped that fallback on the sales side: it made an invoice show
   * every shipment of its order, including ones another invoice billed). Before ETP-5576
   * the match table was not read, so the second and later partial shipments of an invoice line —
   * which the column cannot hold — never showed up.
   *
   * <p>Joins C_DocType to expose {@code isReturn}, the actual discriminator between a
   * regular delivery and a customer return: M_InOut.MovementType is NOT usable for this
   * (see M_INOUT_TRG_PROV.xml) — it only ever takes 'C-' for every sales-side movement
   * (shipments AND returns alike) or 'V+' for purchase-side, never branching on
   * C_DocType.IsReturn. ETP-4534.</p>
   */
  // The sub-select is built from a fixed enum literal; every value is bound — no injection risk.
  @SuppressWarnings("java:S2077")
  private void enrichLinkedShipments(JSONObject rec, String invoiceId) {
    String sql =
        "SELECT DISTINCT io.m_inout_id, io.documentno, io.docstatus, io.movementtype, dt.isreturn " +
        "FROM (" + InOutInvoiceLinks.linkedInOutLineIdsSql(InOutInvoiceLinks.MatchTable.SALES) + ") lk " +
        "JOIN m_inoutline iol ON iol.m_inoutline_id = lk.m_inoutline_id " +
        "JOIN m_inout io ON io.m_inout_id = iol.m_inout_id " +
        "JOIN c_doctype dt ON dt.c_doctype_id = io.c_doctype_id " +
        "WHERE io.isactive = 'Y' AND io.docstatus NOT IN ('VO','CL') " +
        "  AND io.issotrx = 'Y'";
    Connection conn = OBDal.getReadOnlyInstance().getConnection();
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
      InOutInvoiceLinks.bindRepeated(ps, 1, invoiceId,
          InOutInvoiceLinks.linkedInOutLinesParams(InOutInvoiceLinks.MatchTable.SALES));
      JSONArray shipments = new JSONArray();
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          JSONObject s = new JSONObject();
          s.put("id", rs.getString(1));
          s.put(FIELD_DOCUMENT_NO, rs.getString(2));
          s.put("documentStatus", rs.getString(3));
          s.put("movementType", rs.getString(4));
          s.put("isReturn", "Y".equalsIgnoreCase(rs.getString(5)));
          shipments.put(s);
        }
      }
      rec.put("linkedShipments", shipments);
    } catch (Exception e) {
      log.warn("Could not enrich linked shipments for invoice {}: {}", invoiceId, e.getMessage());
    }
  }
}
