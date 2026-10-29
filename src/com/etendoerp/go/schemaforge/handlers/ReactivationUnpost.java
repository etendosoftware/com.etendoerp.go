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

package com.etendoerp.go.schemaforge.handlers;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.base.model.Property;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.ui.Tab;

import com.etendoerp.go.schemaforge.NeoContext;
import com.etendoerp.go.schemaforge.NeoResponse;
import com.etendoerp.go.schemaforge.util.NeoButtonActionHelper;

/**
 * Reactivating a posted document reverses its accounting first, on the server (ETP-5684).
 *
 * <p><b>Why on the server.</b> The core refuses to reactivate a posted document, so the SPA
 * ({@code runPreUnpost}) and the MCP ({@code etendo_action}/{@code etendo_action_batch}) used to send an {@code unpost}
 * action before the reactivation. Since ETP-5684 that action needs the {@code showAccountingFields}
 * capability, which a Sales or Purchasing role does not have, while reactivating stays theirs to
 * do. Un-posting is therefore no longer something a caller asks for: it is a step of the
 * reactivation, run here through {@link DocumentPostingService#unpost} directly — the posting gate
 * guards the action endpoint, not this internal API — for every channel alike.</p>
 *
 * <p><b>Order and commit.</b> {@code ResetAccounting.delete} commits the request's connection, so
 * an un-post cannot be undone. A customization calls {@link #unpostFirst} as the LAST check before
 * the reactivation process runs, after every guard of its own, and {@link #notePartial} from
 * {@code afterHandle}, which adds {@link #KEY_PARTIAL} when the reactivation was refused after the
 * un-post had already been committed.</p>
 */
public final class ReactivationUnpost {

  private static final Logger log = LogManager.getLogger(ReactivationUnpost.class);

  /** Request attribute set when the un-post ran for this reactivation. */
  static final String ATTRIBUTE_UNPOSTED = ReactivationUnpost.class.getName() + ".unposted";

  /** Response key listing the steps that ran, present only when the un-post ran. */
  public static final String KEY_STEPS = "steps";
  /** Response key explaining that a committed un-post stays after a failed reactivation. */
  public static final String KEY_PARTIAL = "partial";
  /** What {@link #KEY_PARTIAL} says. */
  public static final String PARTIAL_NOTE = "The un-post step was committed before the "
      + "reactivation was refused and is not undone: the document stays Completed and un-posted.";

  private static final String STEP_UNPOST = "unpost";
  private static final String STEP_REACTIVATE = "reactivate";
  private static final String POSTED_COLUMN = "Posted";
  private static final String POSTED_YES = "Y";

  private ReactivationUnpost() {
  }

  /**
   * Un-posts the request's record when it is posted. Call it only for a reactivation, as the last
   * step before the reactivation process.
   *
   * @param context the reactivation request
   * @param posting the posting service; {@code null} leaves the request untouched
   * @return the un-post's failure (the reactivation must not run), or {@code null} to go on
   */
  public static NeoResponse unpostFirst(NeoContext context, DocumentPostingService posting) {
    if (posting == null || context == null || StringUtils.isBlank(context.getRecordId())) {
      return null;
    }
    Tab tab = context.getAdTab();
    if (tab == null || tab.getTable() == null || !isPosted(tab, context.getRecordId())) {
      return null;
    }
    // The un-post commits: a role that may not run the reactivation itself must not get it.
    NeoResponse denied = NeoButtonActionHelper.refusalForButtonProcess(context.getSfEntity(),
        context.getFieldName());
    if (denied != null) {
      return denied;
    }
    DocumentPostingService.PostResult result =
        posting.unpost(tab.getTable().getId(), context.getRecordId());
    if (!result.ok()) {
      return posting.toResponse(result, context.getRecordId());
    }
    context.setAttribute(ATTRIBUTE_UNPOSTED, Boolean.TRUE);
    return null;
  }

  /**
   * Whether this request already un-posted the record before reactivating it.
   *
   * @param context the reactivation request
   * @return whether {@link #unpostFirst} un-posted the record (a committed step) in this request
   */
  public static boolean ran(NeoContext context) {
    return context != null && Boolean.TRUE.equals(context.getAttribute(ATTRIBUTE_UNPOSTED));
  }

  /**
   * Tells the caller what ran when {@link #unpostFirst} un-posted the record: adds the steps, and
   * the partial note when the reactivation then failed, to the {@code previousResult} body in
   * place. Does nothing when the un-post did not run, so {@code afterHandle} can call it first
   * and carry on.
   *
   * @param context the reactivation request, with its {@code previousResult}
   */
  public static void notePartial(NeoContext context) {
    NeoResponse previous = context == null ? null : context.getPreviousResult();
    if (previous == null || previous.getBody() == null || !ran(context)) {
      return;
    }
    JSONObject body = previous.getBody();
    try {
      boolean succeeded = previous.getHttpStatus() < 400;
      JSONArray steps = new JSONArray().put(STEP_UNPOST);
      if (succeeded) {
        steps.put(STEP_REACTIVATE);
      } else {
        body.put(KEY_PARTIAL, PARTIAL_NOTE);
      }
      body.put(KEY_STEPS, steps);
    } catch (JSONException e) {
      log.debug("Could not add the un-post steps to the reactivation result: {}", e.getMessage());
    }
  }

  private static boolean isPosted(Tab tab, String recordId) {
    OBContext.setAdminMode(true);
    try {
      Entity dal = ModelProvider.getInstance().getEntityByTableId(tab.getTable().getId());
      Property posted = dal == null ? null : dal.getPropertyByColumnName(POSTED_COLUMN, false);
      if (posted == null) {
        return false;
      }
      BaseOBObject row = OBDal.getInstance().get(dal.getName(), recordId);
      return row != null && POSTED_YES.equals(row.get(posted.getName()));
    } finally {
      OBContext.restorePreviousMode();
    }
  }
}
