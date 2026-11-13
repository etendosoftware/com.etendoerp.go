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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.inject.Inject;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.ad_process.ConvertQuotationIntoOrder;
import org.openbravo.model.common.order.Order;

import com.etendoerp.go.schemaforge.handlers.PaymentMethodSelectorSupport;
import com.etendoerp.go.schemaforge.util.NeoActionAvailability;
import com.etendoerp.go.schemaforge.util.NeoActionContract;

/**
 * NeoHandler for the Sales Quotation header entity.
 *
 * <p>ETP-5238: the {@code paymentMethod} SELECTOR is served by
 * {@link PaymentMethodSelectorSupport}, independent of Financial Account linkage.
 *
 * Dispatches custom ACTION requests:
 * <ul>
 *   <li>{@code Convertquotation} (also reached as {@code createOrder}, the button's property name,
 *       on every MCP call — see {@link #isConvertQuotationAction}) → calls {@link ConvertQuotationIntoOrder#convertQuotationIntoSalesOrder}
 *       with {@code recalculatePrices=false} so that unit prices agreed in the quotation are
 *       preserved in the new sales order (default Etendo behaviour would re-fetch prices from
 *       the active price list, overwriting the quoted amounts), then reactivates the order so it
 *       is left in Draft (ETP-5528, see {@link #handleConvertQuotation}).</li>
 *   <li>{@code cloneRecord} → {@link NeoCloneRecordHandler}</li>
 *   <li>{@code currencyOptions} → {@link CurrencyOptionsHandler}</li>
 *   <li>{@code createDraftInvoice} / {@code checkDraftInvoice} / {@code listInvoices} → {@link CreateDraftInvoiceHandler}</li>
 *   <li>{@code rejectQuotation} → {@link RejectQuotationHandler}</li>
 *   <li>{@code createRejectReason} → {@link CreateRejectReasonHandler}</li>
 *   <li>{@code DocAction} with value {@code RJ} → {@link RejectQuotationHandler#reject} (ETP-5535,
 *       see {@link #isRejectDocAction})</li>
 * </ul>
 *
 * <p>ETP-5535: {@code rejectQuotation} and {@code createRejectReason} are declared through
 * {@link #actionContracts()}, so {@code etendo_schema(view:"actions")} lists them next to the AD
 * buttons. Before that they were reachable over MCP but undiscoverable.
 *
 * <p>Total discount is synced on two paths:
 * <ul>
 *   <li>{@code documentAction=CO} via CRUD or ACTION — handled by
 *       {@link AbstractOrderHeaderHandler#applyTotalDiscountBeforeComplete}</li>
 *   <li>{@code DocAction} process button — handled by
 *       {@link AbstractOrderHeaderHandler#syncTotalDiscountOnDocAction},
 *       used by {@code SendToEvaluationModal} when confirming a draft quotation (DR→UE)</li>
 * </ul>
 *
 * <p>Currency / price-list / exchange-rate behaviors are inherited from
 * {@link AbstractOrderHeaderHandler#afterCallout}.
 *
 * <p>ETP-5704 (QA CP-17) / ETP-6026: Reactivate ({@code RE}), Void ({@code VO}) and Close
 * ({@code CL}) are refused on every quotation, whatever its status — the window offers none of
 * them (its generated process list is empty, so {@code processOverrides.Reactivate}/{@code Void}
 * never render; a draft offers Delete and Confirm, one Under evaluation Confirm and Reject). Over
 * MCP a quotation Under evaluation went back to Draft (RE), a draft was voided and one Under
 * evaluation closed. ETP-5704 still let RE through on a voided quotation, but core's
 * {@code C_Order_Post} refuses any action on a {@code VO}/{@code CL} document
 * ({@code @AlreadyPosted@}), so that path only ended in a technical error.
 *
 * <p>ETP-6026: the {@code DocAction} button is an ALLOW-list of what the window offers: Confirm
 * ({@code CO}) on a draft only ({@link #ALLOWED_BY_STATUS}) — what {@code SendToEvaluationModal},
 * the row Confirmar and the bulk bar (drafts only) send. {@code RJ} never reaches it: it is routed
 * to the reject flow first. Every other effective value — Prepare ({@code PR}, which took a draft
 * to {@code IP} over MCP), {@code AP}, {@code CO} outside a draft, {@code --}, nothing at all — is
 * refused with 400 ({@code DocActionRefusalGuard#refuseUnlessAllowed}; RE, VO and CL keep their own
 * messages). An {@code IP} quotation accepts nothing: its Confirmar opens no modal. Same resolution
 * and fail-closed rules as the sales order ({@link DocActionRefusalGuard}); advertised by
 * {@link #unavailableActions}. It does not touch
 * {@code Convertquotation} (another button; the new order is reactivated through
 * {@link OrderDocActionSupport}, not through this pre-hook) or the DR→UE {@code DocAction} of
 * {@code SendToEvaluationModal} (it runs CO).
 *
 * <p><b>Binding (ETP-6002).</b> Bound by {@link NeoExtension} on every channel. The row's
 * {@code Java_Qualifier} ({@code salesQuotationHeaderHandler}) and the class's {@code @Named} were
 * removed in the same change (neo-extension-migration).
 */
@NeoExtension(spec = "sales-quotation", entity = "quotation")
public class SalesQuotationHeaderHandler extends AbstractOrderHeaderHandler {

  private static final Logger log = LogManager.getLogger(SalesQuotationHeaderHandler.class);
  private static final String FIELD_DOCUMENT_STATUS = "documentStatus";
  private static final String DOC_STATUS_COMPLETED = "CO";
  private static final String DOC_ACTION_REJECT = "RJ";
  /** Reactivate, a {@code documentAction} value the quotation window never offers (ETP-6026). */
  static final String DOC_ACTION_REACTIVATE = "RE";
  /** The 400 a Reactivate on a quotation answers with (ETP-5704, every status since ETP-6026). */
  static final String MSG_REACTIVATE_NOT_OFFERED = "ETGO_QuotationReactivateNotOffered";
  /** Why {@code documentAction:RE} is unavailable on every quotation (ETP-6026). */
  static final String REASON_REACTIVATE_NOT_OFFERED = "The app does not offer Reactivate on a "
      + "quotation, so documentAction RE is refused on every quotation. A quotation Under "
      + "evaluation (UE) is accepted with Convertquotation or rejected with rejectQuotation; to "
      + "quote again after a voided or rejected one, create a new quotation.";
  /** Void, a {@code documentAction} value the quotation window never offers (ETP-6026). */
  static final String DOC_ACTION_VOID = "VO";
  /** Close, a {@code documentAction} value the quotation window never offers (ETP-6026). */
  static final String DOC_ACTION_CLOSE = "CL";
  /** The 400 a Void on a quotation answers with (ETP-6026). */
  static final String MSG_VOID_NOT_OFFERED = "ETGO_QuotationVoidNotOffered";
  /** The 400 a Close on a quotation answers with (ETP-6026). */
  static final String MSG_CLOSE_NOT_OFFERED = "ETGO_QuotationCloseNotOffered";
  /** Why {@code documentAction:VO} is unavailable on every quotation (ETP-6026). */
  static final String REASON_VOID_NOT_OFFERED = "The app does not offer Void on a quotation, so "
      + "documentAction VO is refused on every quotation. A draft quotation (DR) is deleted with "
      + "etendo_delete; one Under evaluation (UE) is rejected with rejectQuotation.";
  /** Why {@code documentAction:CL} is unavailable on every quotation (ETP-6026). */
  static final String REASON_CLOSE_NOT_OFFERED = "The app does not offer Close on a quotation, "
      + "so documentAction CL is refused on every quotation. A quotation Under evaluation (UE) is "
      + "accepted with Convertquotation or rejected with rejectQuotation.";
  /** The 400 for any other value the window does not offer in the quotation's status (ETP-6026). */
  static final String MSG_ACTION_NOT_OFFERED = "ETGO_QuotationActionNotOffered";
  /**
   * The {@code DocAction} values the pre-hook lets run, per stored {@code documentStatus}
   * (ETP-6026). A status that is not a key accepts nothing.
   */
  static final Map<String, Set<String>> ALLOWED_BY_STATUS = Map.of("DR", Set.of("CO"));
  /**
   * The values advertised per status: the allow-list plus {@code RJ} Under evaluation, which the
   * pre-hook routes to the reject flow before the allow-list (ETP-6026).
   */
  static final Map<String, Set<String>> ADVERTISED_BY_STATUS =
      Map.of("DR", Set.of("CO"), "UE", Set.of(DOC_ACTION_REJECT));
  /** The values of the {@code C_Order.DocAction} list an agent could send, for the advice. */
  static final List<String> DOC_ACTION_VALUES =
      List.of("CO", "RE", "VO", "CL", "PR", "AP", "RJ", "PO", "RA", "RC", "XL");
  /** The button's column name, what the SPA posts. */
  private static final String ACTION_CONVERT_COLUMN = "Convertquotation";
  /** A lower-case spelling of the column name, matched since ETP-5710; kept for any caller using it. */
  private static final String ACTION_CONVERT_FIELD = "convertquotation";
  /**
   * The button's DAL property name, {@code createOrder} (column {@code Convertquotation}): what
   * every MCP {@code etendo_action} call reaches the handler under, whichever name the agent typed
   * — {@code McpDeclaredActions.customizationNameOf} resolves the button to its property name
   * (ETP-5704; ETP-5710 had assumed {@code convertquotation}, so MCP never got here).
   */
  private static final String ACTION_CONVERT_PROPERTY = Order.PROPERTY_CREATEORDER;

  /** The named actions published to agents, in presentation order (ETP-5535). */
  private static final Map<String, NeoActionContract> ACTION_CONTRACTS = buildActionContracts();

  @Inject
  private NeoCloneRecordHandler cloneRecordHandler;

  @Inject
  private CurrencyOptionsHandler currencyOptionsHandler;

  @Inject
  private RejectQuotationHandler rejectQuotationHandler;

  @Inject
  private CreateRejectReasonHandler createRejectReasonHandler;

  @Inject
  private CreateDraftInvoiceHandler createDraftInvoiceHandler;

  @Inject
  private TotalDiscountService totalDiscountService;

  /**
   * The order-header post-hook, plus, on the detail GET, the quotation's goods movements
   * ({@code linkedShipments}, ETP-6002 IMP-3: shipments whose lines point at this quotation's lines)
   * so its related documents list them — see {@link QuotationShipmentLinks}.
   */
  @Override
  public NeoResponse afterHandle(NeoContext context) {
    NeoResponse result = super.afterHandle(context);
    if (!"GET".equals(context.getHttpMethod()) || context.getRecordId() == null) {
      return result;
    }
    NeoResponse effective = result != null ? result : context.getPreviousResult();
    if (effective == null || effective.getBody() == null || effective.getHttpStatus() >= 400) {
      return result;
    }
    JSONObject wrapper = effective.getBody().optJSONObject("response");
    org.codehaus.jettison.json.JSONArray data = wrapper == null ? null : wrapper.optJSONArray("data");
    JSONObject rec = data == null || data.length() == 0 ? null : data.optJSONObject(0);
    if (rec == null) {
      return result;
    }
    QuotationShipmentLinks.enrich(rec, context.getRecordId());
    return NeoResponse.ok(effective.getBody());
  }

  /** {@code linkedShipments} is injected on every detail record (ETP-6002). */
  @Override
  public java.util.Set<String> responseEnrichedFields() {
    java.util.Set<String> fields = new java.util.LinkedHashSet<>(super.responseEnrichedFields());
    fields.add(QuotationShipmentLinks.FIELD_LINKED_SHIPMENTS);
    return fields;
  }

  @Inject
  private ConvertQuotationIntoOrder convertQuotationProcess;

  /**
   * ETP-5840: the rate is the one valid on the {@code orderDate} ({@code DateOrdered}), shown as
   * "Quotation date". Order-family rules in {@link DocumentRateGuards#guardOrderHeader}.
   */
  static final DocumentCurrencyRate.RateType RATE_TYPE = new DocumentCurrencyRate.RateType(
      Order.ENTITY_NAME, Order.PROPERTY_ETGOCURRENCYRATE, Order.PROPERTY_ORDERDATE,
      DocumentRateMessages.LABEL_QUOTATION_DATE);

  private final DocumentCurrencyRate currencyRate = new DocumentCurrencyRate(RATE_TYPE);

  @Override
  protected DocumentCurrencyRate currencyRate() {
    return currencyRate;
  }

  @Override
  public NeoResponse handle(NeoContext context) {
    NeoResponse paymentMethodSelector = PaymentMethodSelectorSupport.handleIfPaymentMethodSelector(context,
        PaymentMethodSelectorSupport.DirectionFallback.WINDOW);
    if (paymentMethodSelector != null) {
      return paymentMethodSelector;
    }
    // ETP-5703: the partner's only ship-to (and its bill-to) on a create without an address.
    PartnerLocationDefaults.fillOrderLocations(context);
    // ETP-5535: ahead of the total-discount sync, which recalculates on every DocAction — a
    // rejection does not touch the lines, exactly as the UI's rejectQuotation does not.
    if (isRejectDocAction(context)) {
      return rejectQuotationHandler.reject(context);
    }
    NeoResponse docActionRefusal = refuseDocActionNotOffered(context);
    if (docActionRefusal != null) {
      return docActionRefusal;
    }
    // ETP-5840: exchange rate — recalculation, save and confirmation validation — before the
    // total-discount sync and the actions run.
    NeoResponse rateRefusal = DocumentRateGuards.guardOrderHeader(context, currencyRate);
    if (rateRefusal != null) {
      return rateRefusal;
    }
    AbstractOrderHeaderHandler.applyTotalDiscountBeforeComplete(context, totalDiscountService, false);
    AbstractOrderHeaderHandler.syncTotalDiscountOnDocAction(context, totalDiscountService, false);

    if (NeoEndpointType.ACTION.equals(context.getEndpointType())
        && isConvertQuotationAction(context.getFieldName())) {
      // ETP-5840: a foreign-currency quotation without a rate is not converted; the client gets the
      // translated completion refusal. The SPA's confirm modal only pre-checks — this is the rule.
      NeoResponse missingRate = currencyRate.refuseCompletionWithoutRate(context.getRecordId());
      if (missingRate != null) {
        return missingRate;
      }
      return handleConvertQuotation(context.getRecordId());
    }

    return NeoHeaderActionRouter.dispatch(
        context,
        currencyOptionsHandler,
        cloneRecordHandler,
        rejectQuotationHandler,
        createRejectReasonHandler,
        createDraftInvoiceHandler);
  }

  /**
   * The refusal of a {@code DocAction} value the quotation window does not offer in the
   * quotation's status, or {@code null} (ETP-5704, ETP-6026): an allow-list
   * ({@link #ALLOWED_BY_STATUS}) decided on the value that will actually run, with
   * {@link DocActionRefusalGuard}'s fail-closed rules.
   *
   * @param context the request reaching the pre-hook
   * @return the error response, or {@code null} when the request may continue
   */
  private static NeoResponse refuseDocActionNotOffered(NeoContext context) {
    return DocActionRefusalGuard.refuseUnlessAllowed(context,
        SalesQuotationHeaderHandler::allowedDocActions, SalesQuotationHeaderHandler::refusalKeyOf);
  }

  /** The refusal key for a value the pre-hook does not allow (ETP-6026). */
  static String refusalKeyOf(String value) {
    if (DOC_ACTION_REACTIVATE.equals(value)) {
      return MSG_REACTIVATE_NOT_OFFERED;
    }
    if (DOC_ACTION_VOID.equals(value)) {
      return MSG_VOID_NOT_OFFERED;
    }
    if (DOC_ACTION_CLOSE.equals(value)) {
      return MSG_CLOSE_NOT_OFFERED;
    }
    return MSG_ACTION_NOT_OFFERED;
  }

  /**
   * The {@code DocAction} values the quotation accepts, from its stored {@code documentStatus}
   * (ETP-6026). Read under admin mode, as the DocAction resolution reads the record.
   *
   * @param quotationId the quotation the button runs on
   * @return the accepted values
   * @throws IllegalStateException when the quotation cannot be loaded — the guard then fails closed
   */
  static Set<String> allowedDocActions(String quotationId) {
    OBContext.setAdminMode(true);
    try {
      Order quotation = OBDal.getInstance().get(Order.class, quotationId);
      if (quotation == null) {
        throw new IllegalStateException("Quotation " + quotationId + " cannot be loaded");
      }
      String status = quotation.getDocumentStatus();
      return status == null ? Set.of() : ALLOWED_BY_STATUS.getOrDefault(status, Set.of());
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * {@inheritDoc} Every {@code documentAction} value the window does not offer in the record's
   * status ({@link #ADVERTISED_BY_STATUS}) is ruled out, with Reactivate's, Void's and Close's own
   * reasons and a generic one for the rest (ETP-5704, ETP-6026).
   */
  @Override
  public Map<String, String> unavailableActions(BaseOBObject subject) {
    if (!(subject instanceof Order)) {
      return Collections.emptyMap();
    }
    String status = ((Order) subject).getDocumentStatus();
    Set<String> advertised =
        status == null ? Set.of() : ADVERTISED_BY_STATUS.getOrDefault(status, Set.of());
    Map<String, String> unavailable = new LinkedHashMap<>();
    for (String value : DOC_ACTION_VALUES) {
      if (!advertised.contains(value)) {
        unavailable.put(documentActionKey(value), reasonNotOffered(value));
      }
    }
    return unavailable;
  }

  /** Why {@code value} is unavailable: RE's, VO's and CL's own reason, else the generic one. */
  static String reasonNotOffered(String value) {
    if (DOC_ACTION_REACTIVATE.equals(value)) {
      return REASON_REACTIVATE_NOT_OFFERED;
    }
    if (DOC_ACTION_VOID.equals(value)) {
      return REASON_VOID_NOT_OFFERED;
    }
    if (DOC_ACTION_CLOSE.equals(value)) {
      return REASON_CLOSE_NOT_OFFERED;
    }
    return "The app does not offer documentAction " + value + " on a quotation in this status: "
        + "it sends a draft to evaluation (CO) and rejects one Under evaluation (RJ, or "
        + "rejectQuotation); acceptance is the Convertquotation action.";
  }

  /** The availability key of one {@code documentAction} value, e.g. {@code documentAction:VO}. */
  private static String documentActionKey(String docAction) {
    return NeoActionAvailability.valueKey(OrderDocumentAvailability.ACTION_DOCUMENT_ACTION,
        docAction);
  }

  /**
   * The quotation's handler-served actions (ETP-5535). Published by {@code etendo_schema} next to the
   * AD buttons; each request is still judged by its own handler, so what the React modals send is
   * accepted exactly as before.
   */
  @Override
  public Map<String, NeoActionContract> actionContracts() {
    return ACTION_CONTRACTS;
  }

  private static Map<String, NeoActionContract> buildActionContracts() {
    Map<String, NeoActionContract> contracts = new LinkedHashMap<>();
    contracts.put(CreateDraftInvoiceHandler.QUOTATION_CONTRACT.getName(),
        CreateDraftInvoiceHandler.QUOTATION_CONTRACT);
    // ETP-6179: the SIF fields the generated invoice would get.
    contracts.put(CreateDraftInvoiceHandler.QUOTATION_SIF_DEFAULTS_CONTRACT.getName(),
        CreateDraftInvoiceHandler.QUOTATION_SIF_DEFAULTS_CONTRACT);
    contracts.put(RejectQuotationHandler.CONTRACT.getName(), RejectQuotationHandler.CONTRACT);
    contracts.put(CreateRejectReasonHandler.CONTRACT.getName(), CreateRejectReasonHandler.CONTRACT);
    return Collections.unmodifiableMap(contracts);
  }

  /**
   * Whether the request runs the {@code DocAction} button with the value {@code RJ} (Reject)
   * (ETP-5535).
   *
   * <p>The button's value list offers "Reject", so an agent reads it as the way to reject a
   * quotation. Through {@code C_Order_Post} that cannot work for any caller: core requires
   * {@code C_Reject_Reason_ID} on the header ({@code @NoRejectReason@}), and {@code rejectReason}
   * is read-only, so nothing can set it first. The request is routed instead to the flow the UI
   * uses, {@link RejectQuotationHandler#reject}, which takes the reason in the same body. Both the
   * MCP and the REST action reach this handler, so both channels get it.</p>
   *
   * <p>Where the value and the button name are read from is {@link DocActionRequestSupport#requests}
   * (extracted from this class in ETP-5704): root {@code docAction} first — the key the process
   * itself runs — then {@code fieldValues.documentAction}, then root {@code documentAction}. Until
   * ETP-5704 {@code fieldValues.documentAction} was read first, so a body naming RJ there and CO in
   * {@code docAction} was rejected instead of completed. The record's stored DocAction never
   * triggers the rejection. A {@code DocAction} request without an explicit {@code RJ} — the SPA's
   * {@code SendToEvaluationModal} sends {@code fieldValues: {}} — is not affected.</p>
   *
   * @param context the request
   * @return {@code true} for a POST ACTION on the DocAction button asking for {@code RJ}
   */
  static boolean isRejectDocAction(NeoContext context) {
    return DocActionRequestSupport.requests(context, DOC_ACTION_REJECT);
  }

  /**
   * Whether the ACTION names the {@code Convertquotation} button under any of the names a channel
   * reaches this handler with: the column name (the SPA's REST post), the DAL property name
   * {@code createOrder} (every MCP call, single or batch, and a REST post using it — ETP-5704), or
   * the lower-case column spelling (ETP-5710).
   */
  static boolean isConvertQuotationAction(String action) {
    return ACTION_CONVERT_COLUMN.equals(action) || ACTION_CONVERT_PROPERTY.equals(action)
        || ACTION_CONVERT_FIELD.equals(action);
  }

  /**
   * Intercepts the {@code Convertquotation} button action and calls
   * {@link ConvertQuotationIntoOrder#convertQuotationIntoSalesOrder} with
   * {@code recalculatePrices=false}.
   *
   * <p>Returning a non-null response short-circuits the default NEO button handler, which
   * would otherwise invoke {@code ConvertQuotationIntoOrder.doExecute} with
   * {@code recalculatePrices=true} (the default when the parameter is absent from the HTTP
   * request), causing all order-line prices to be re-fetched from the active price list and
   * the quoted amounts to be lost.
   *
   * <p><b>ETP-5528 — the order is left in Draft.</b> Core always completes the order it creates
   * ({@code c_order_post1}). Until ETP-5528 the SPA then reactivated it from the browser with a
   * second request ({@code QuotationConfirmModal}, ETP-3570), which is why an order created through
   * the MCP ({@code etendo_action Convertquotation}) stayed Completed while the same action in the UI
   * ended in Draft. The reactivation now runs here, through the same {@code C_Order_Post 'RE'} the
   * UI request reaches ({@link OrderDocActionSupport}), so both channels share one flow. The UI's own
   * call is guarded by {@code documentStatus === 'CO'}, so against an order that is already Draft
   * it simply does not fire — no double reactivation.
   *
   * <p><b>ETP-5704 — MCP did not actually reach this.</b> MCP hands the customization the button's
   * property name ({@code createOrder}), which was not matched, so an MCP conversion ran core's
   * classic process: order left Completed and its prices recalculated from the price list. Matching
   * {@code createOrder} too brings every MCP call (single and batch) here.
   *
   * <p>Best-effort only for failures the procedure REPORTS (an {@code AD_PInstance} result other
   * than 1, or no access to {@code C_Order_Post}): the conversion is kept, the order stays
   * Completed, the failure is logged and {@code documentStatus} in the response says which state the
   * order is in. An exception thrown on the way (the flush, {@code CallProcess} wrapping an
   * {@code SQLException}, a statement timeout) is NOT swallowed: it leaves the PostgreSQL
   * transaction aborted, so the conversion would be rolled back at commit anyway, and answering 200
   * with a {@code salesOrderId} that never gets persisted would lie to the client. It propagates to
   * the catch below and the whole request fails as one unit.
   *
   * <p>ETP-5848: that catch answers through {@link NeoErrorResponses#fromException}, like every
   * other NEO error funnel. A refusal core's {@code c_order_post1} RAISEs — a quotation with no
   * payment method ({@code @APRM_PAYMENTMETHOD_MISSING@}) — is then a 422 with its translated text
   * and {@code messageKeys}; it was a 500 carrying the raw {@code PSQLException}, which told an
   * agent the server had failed when the quotation only lacked a field.
   */
  private NeoResponse handleConvertQuotation(String quotationId) {
    try {
      Order newOrder = convertQuotationProcess.convertQuotationIntoSalesOrder(false, quotationId);
      log.info("[ETP-4027] Created sales order {} from quotation {} with quoted prices preserved",
          newOrder.getDocumentNo(), quotationId);
      reactivateConvertedOrder(newOrder);
      recomputeConvertedOrderRate(newOrder);
      // ETP-5051: the plan-quota check of the order created from the quotation and its lines.
      // Nothing is committed before this point, so a refusal rolls the conversion back whole.
      NeoQuotaCreateCheck.requireAllowedDocument(newOrder);
      JSONObject result = new JSONObject();
      result.put("salesOrderId", newOrder.getId());
      result.put(FIELD_DOCUMENT_STATUS, newOrder.getDocumentStatus());
      return NeoResponse.ok(result);
    } catch (NeoQuotaCreateCheck.Refused e) {
      return e.toResponse();
    } catch (Exception e) {
      return NeoErrorResponses.fromException(e, "POST",
          "Convertquotation of quotation " + quotationId, null);
    }
  }

  /**
   * Reactivates the order core just completed, leaving it in Draft (ETP-5528).
   *
   * <p>A failure the procedure reports is logged and the order is left as the procedure left it.
   * A thrown exception is deliberately not caught here — see {@link #handleConvertQuotation}.
   *
   * @throws Exception when the reactivation call itself fails; the transaction is then unusable
   */
  private static void reactivateConvertedOrder(Order order) throws Exception {
    if (!DOC_STATUS_COMPLETED.equals(order.getDocumentStatus())) {
      return;
    }
    if (OrderDocActionSupport.runDocAction(order, OrderDocActionSupport.DOC_ACTION_REACTIVATE)) {
      log.info("[ETP-5528] Reactivated sales order {} created from a quotation (status {})",
          order.getDocumentNo(), order.getDocumentStatus());
    } else {
      log.warn("[ETP-5528] C_Order_Post reported a failure reactivating sales order {}; it stays {}",
          order.getDocumentNo(), order.getDocumentStatus());
    }
  }

  @Override
  protected TotalDiscountService getTotalDiscountService() {
    return totalDiscountService;
  }

  /**
   * Recomputes the exchange rate of the order {@code Convertquotation} just created (ETP-5840).
   *
   * <p>Core's {@code DalUtil.copy} carries the quotation's {@code EM_ETGO_Currency_Rate} over, but
   * the order is dated today, so the quotation's rate is not the order's: it is recomputed from the
   * order's own reference date with the sales order's rate rules, never copied. No rate configured →
   * {@code null}, and the order's next save is refused until one is entered. Runs after the
   * reactivation, on the Draft order; an order core left Completed (reactivation reported a failure)
   * is left as it is.
   */
  private static void recomputeConvertedOrderRate(Order order) {
    OBDal.getInstance().getSession().refresh(order);
    new DocumentCurrencyRate(SalesOrderHeaderHandler.RATE_TYPE).recompute(order);
  }
}
