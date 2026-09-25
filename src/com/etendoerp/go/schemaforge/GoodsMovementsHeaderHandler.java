/*
 * *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"), you may not use this file except in compliance
 * with the License.
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

import javax.inject.Inject;
import javax.inject.Named;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.utility.Utility;
import org.openbravo.service.db.DalConnectionProvider;

import com.etendoerp.go.schemaforge.handlers.DocumentPostingService;

/**
 * Hooks for the Goods Movements (M_Movement) header entity.
 *
 * <p><b>DocumentNo materialization (create only, ETP-5491):</b> M_Movement has no document type
 * column, so its DocumentNo falls outside the core {@code SetDocumentNoHandler} observer, which only
 * recomputes a {@code <preview>}/null value for the entities it observes (orders, invoices, …).
 * Through the NEO create path the field would otherwise reach persistence as the literal
 * {@code <preview>} placeholder or an empty string and the movement would be saved with an empty
 * DocumentNo. This pre-hook takes the number <b>once, on the CRUD create</b> ({@code POST} with no
 * record id) from the {@code DocumentNo_M_Movement} table sequence (updateNext=true) — the same
 * sequence Classic uses, since without a document type core falls back to the table sequence — and
 * injects it into the create body before the INSERT, only when the caller did not supply a real
 * value (a real value always wins; null, blank and {@code <...>} placeholders are replaced).
 * Actions on an existing record ({@code processNow}, {@code post}, {@code unpost}) never consume a
 * number, matching Classic, which assigns one number on save and none on process. The
 * {@code /defaults} preview comes from the same sequence. See
 * {@code docs/neo-headless-extensibility.md}.
 *
 * <p><b>Cumulative stock validation before "Procesar" (ETP-5037):</b> delegates to
 * {@link GoodsMovementProcessGuard}, which runs first and short-circuits with a
 * {@link NeoResponse} error when any (product, source warehouse) pair across the movement's
 * lines would exceed on-hand stock — before the request ever reaches the classic completion
 * process. See {@link GoodsMovementProcessGuard} for details.
 *
 * <p><b>Post / Unpost (ETP-5436):</b> the {@code post}/{@code unpost} kebab and bulk-list actions
 * are declared in {@code decisions.json} as plain {@code menuActions}, not as an
 * {@code AD_Column} button — so the generic {@code NeoButtonActionHelper} dispatcher can never
 * resolve them and every request 404s with "Action not found: post". Routed here, after the stock
 * guard and before the documentNo materialization, exactly like {@link GoodsReceiptHeaderHandler}
 * and {@link GoodsShipmentHeaderHandler}.
 */
@Named("goodsMovementsHeaderHandler")
public class GoodsMovementsHeaderHandler implements NeoHandler {

  private static final Logger log = LogManager.getLogger(GoodsMovementsHeaderHandler.class);
  private static final String FIELD_DOCUMENT_NO = "documentNo";
  private static final String TABLE_M_MOVEMENT = "M_Movement";

  @Inject
  private DocumentPostingService postingService;

  /** Package-private seam so unit tests can inject a mocked {@link DocumentPostingService}. */
  void setPostingService(DocumentPostingService postingService) {
    this.postingService = postingService;
  }

  @Override
  public NeoResponse handle(NeoContext context) {
    NeoResponse processRejection = GoodsMovementProcessGuard.validateBeforeProcess(context);
    if (processRejection != null) {
      return processRejection;
    }
    NeoResponse posting = postingService != null ? postingService.handleAction(context) : null;
    if (posting != null) {
      return posting;
    }
    if (isCreateRequest(context)) {
      materializeDocumentNo(context.getRequestBody());
    }
    return null;
  }

  /** True only for the CRUD create ({@code POST} without a record id) — never for actions. */
  private static boolean isCreateRequest(NeoContext context) {
    return NeoEndpointType.CRUD.equals(context.getEndpointType())
        && "POST".equalsIgnoreCase(context.getHttpMethod())
        && context.getRecordId() == null;
  }

  /**
   * Injects the DocumentNo into the create body unless the caller already sent a real value,
   * consuming one number from the {@code DocumentNo_M_Movement} table sequence. Failures are
   * logged, never propagated, so the create itself is not blocked.
   */
  private static void materializeDocumentNo(JSONObject body) {
    if (body == null) {
      return;
    }
    try {
      // Only materialize when the caller has no real value: absent, JSON-null, blank, or the
      // <preview> placeholder produced by the defaults endpoint. A real DocumentNo always wins.
      String current = body.has(FIELD_DOCUMENT_NO) && !body.isNull(FIELD_DOCUMENT_NO)
          ? StringUtils.trimToNull(body.optString(FIELD_DOCUMENT_NO, ""))
          : null;
      if (current != null && !current.startsWith("<")) {
        return;
      }
      String clientId = OBContext.getOBContext().getCurrentClient().getId();
      String docNo = Utility.getDocumentNoConnection(OBDal.getInstance().getConnection(false),
          new DalConnectionProvider(false), clientId, TABLE_M_MOVEMENT, true);
      if (StringUtils.isBlank(docNo)) {
        log.warn("[GOODS-MOVEMENTS] Could not generate documentNo for client {} — activate "
            + "AD_Sequence 'DocumentNo_M_Movement' for the client.", clientId);
        return;
      }
      body.put(FIELD_DOCUMENT_NO, docNo);
      log.debug("[GOODS-MOVEMENTS] Injected documentNo={} on create", docNo);
    } catch (Exception e) {
      log.warn("[GOODS-MOVEMENTS] Could not inject documentNo: {}", e.getMessage(), e);
    }
  }
}
