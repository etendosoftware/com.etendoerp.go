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

import javax.inject.Named;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.utility.Utility;
import org.openbravo.service.db.DalConnectionProvider;

/**
 * Hooks for the Goods Movements (M_Movement) header entity.
 *
 * <p><b>DocumentNo materialization (POST create):</b> M_Movement has no document type, so its
 * DocumentNo falls outside the core {@code SetDocumentNoHandler} observer, which only recomputes a
 * {@code <preview>}/null value for the entities it observes (orders, invoices, …). Through the NEO
 * create path the field therefore reaches persistence as the literal {@code <preview>} placeholder
 * or an empty string — neither of which the observer rewrites — and the movement is saved with an
 * empty DocumentNo. This pre-hook resolves the real value from the {@code DocumentNo_M_Movement}
 * sequence (updateNext=true, consuming the number) and injects it into the create body before the
 * INSERT, but only when the caller did not supply a real value. A real value always wins, and a
 * value already materialized here makes the core observer skip the record, so the sequence is
 * never consumed twice. See {@code docs/neo-headless-extensibility.md}.
 */
@Named("goodsMovementsHeaderHandler")
public class GoodsMovementsHeaderHandler implements NeoHandler {

  private static final Logger log = LogManager.getLogger(GoodsMovementsHeaderHandler.class);
  private static final String FIELD_DOCUMENT_NO = "documentNo";
  private static final String TABLE_M_MOVEMENT = "M_Movement";

  @Override
  public NeoResponse handle(NeoContext context) {
    if (!"POST".equalsIgnoreCase(context.getHttpMethod())) {
      return null;
    }
    JSONObject body = context.getRequestBody();
    if (body == null) {
      return null;
    }
    try {
      // Only materialize when the caller has no real value: absent, JSON-null, blank, or the
      // <preview> placeholder produced by the defaults endpoint. A real DocumentNo always wins.
      String current = body.has(FIELD_DOCUMENT_NO) && !body.isNull(FIELD_DOCUMENT_NO) ? StringUtils.trimToNull(
          body.optString(FIELD_DOCUMENT_NO, "")) : null;
      if (current != null && !current.startsWith("<")) {
        return null;
      }
      String clientId = OBContext.getOBContext().getCurrentClient().getId();
      String docNo = Utility.getDocumentNoConnection(OBDal.getInstance().getConnection(false),
          new DalConnectionProvider(false), clientId, TABLE_M_MOVEMENT, true);
      if (StringUtils.isBlank(docNo)) {
        log.warn(
            "[GOODS-MOVEMENTS] Could not generate documentNo for client {} — activate " + "AD_Sequence 'DocumentNo_M_Movement' for the client.",
            clientId);
        return null;
      }
      body.put(FIELD_DOCUMENT_NO, docNo);
      log.debug("[GOODS-MOVEMENTS] Injected documentNo={} on create", docNo);
    } catch (Exception e) {
      log.warn("[GOODS-MOVEMENTS] Could not inject documentNo: {}", e.getMessage(), e);
    }
    return null;
  }
}
