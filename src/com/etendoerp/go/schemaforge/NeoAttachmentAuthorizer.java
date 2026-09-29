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

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hibernate.criterion.Restrictions;
import org.openbravo.base.exception.OBException;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.datamodel.Table;
import org.openbravo.model.ad.ui.Tab;
import org.openbravo.model.ad.ui.Window;
import org.openbravo.model.ad.utility.Attachment;

import com.etendoerp.go.schemaforge.util.NeoAccessHelper;

/**
 * Authorization for NEO attachment operations — first slice of ADR-0003's centralized
 * authorizer ({@code docs/adr/0003-attachment-authorization.md}, D2).
 *
 * <p><b>Scope of this slice (ETP-5205):</b> only the window-access <i>tier</i> for WRITE
 * operations (upload, delete, update description, mark/unmark main). It answers "may the current
 * role write on a window that shows this table", so a Solo-Lectura role
 * ({@code AD_Window_Access.IsReadWrite = 'N'}) can no longer change attachments. READ
 * authorization, organization/client scoping of the parent record (SEC-11b), the uniform
 * {@code 404} and the admin-mode narrowing stay with ETP-4570, which extends this class.</p>
 *
 * <p><b>Rule.</b> Allowed when the current role has editable access
 * ({@link NeoAccessHelper#hasWindowAccess(String, String)} with {@code POST}) to <i>at least
 * one</i> active window that has an active tab on the attachment's table. Admin and client-admin
 * roles pass through that same call (no hardcoded exception, ADR-0003 D4). A table that no
 * window shows is allowed with a WARN, mirroring the permissive fallback of
 * {@link NeoAccessHelper#hasWindowAccessForSpec}; whether that should fail closed is ADR-0003
 * open question 3.</p>
 *
 * <p><b>Known residual.</b> The rule is per table, not per record: a role with full access to
 * sales-order but read-only on purchase-order can still write attachments of a purchase order,
 * because both windows show {@code C_Order}. Resolving the record's exact window is ETP-4570's.
 * The client's {@code tabId} hint is never used to decide (ADR-0003 D2: client context is never
 * the proof).</p>
 */
public final class NeoAttachmentAuthorizer {

  private static final Logger log = LogManager.getLogger(NeoAttachmentAuthorizer.class);

  /** Already mapped by the UI to a translated message ({@code backendError.accessDeniedToSpec}). */
  static final String ACCESS_DENIED = "Access denied to spec for current role";

  private NeoAttachmentAuthorizer() {
  }

  /**
   * Write check for an operation addressed by table name (upload).
   *
   * @param tableName physical table name from the request path, e.g. {@code C_Order}
   * @return {@code null} when allowed (or when the table is unknown — the operation reports that
   *         itself), otherwise a {@code 403} response
   */
  public static NeoResponse checkWriteOnTable(String tableName) {
    if (StringUtils.isBlank(tableName)) {
      return null;
    }
    String tableId;
    try {
      tableId = NeoAttachmentsHelper.resolveTableId(tableName);
    } catch (OBException e) {
      return null;
    }
    return checkWrite(tableId);
  }

  /**
   * Write check for an operation addressed by attachment id (delete, description, mark main). The
   * table is taken from the stored attachment row, never from the client.
   *
   * @param attachmentId the {@code C_File_ID}
   * @return {@code null} when allowed (or when the attachment does not exist — the operation
   *         answers its own {@code 404}), otherwise a {@code 403} response
   */
  public static NeoResponse checkWriteOnAttachment(String attachmentId) {
    if (StringUtils.isBlank(attachmentId)) {
      return null;
    }
    Attachment attachment = OBDal.getInstance().get(Attachment.class, attachmentId);
    if (attachment == null) {
      return null;
    }
    Table table = attachment.getTable();
    if (table == null) {
      return NeoResponse.error(403, ACCESS_DENIED);
    }
    return checkWrite(table.getId());
  }

  /**
   * Core rule, see the class comment.
   *
   * @param tableId the {@code AD_Table_ID} the attachment belongs to
   * @return {@code null} when allowed, otherwise a {@code 403} response
   */
  static NeoResponse checkWrite(String tableId) {
    Set<String> windowIds = findWindowIdsForTable(tableId);
    if (windowIds.isEmpty()) {
      log.warn("Attachment write on table {} allowed: no active window shows it", tableId);
      return null;
    }
    for (String windowId : windowIds) {
      if (NeoAccessHelper.hasWindowAccess(windowId, "POST")) {
        return null;
      }
    }
    return NeoResponse.error(403, ACCESS_DENIED);
  }

  /**
   * Distinct active windows with an active tab on {@code tableId}. Organization filtering is
   * disabled so the answer does not depend on the caller's organization, like
   * {@code NeoAttachmentsHelper.findFirstActiveTabId}.
   */
  static Set<String> findWindowIdsForTable(String tableId) {
    OBCriteria<Tab> criteria = OBDal.getInstance().createCriteria(Tab.class);
    criteria.add(Restrictions.eq(Tab.PROPERTY_TABLE + ".id", tableId));
    criteria.add(Restrictions.eq(Tab.PROPERTY_ACTIVE, true));
    criteria.setFilterOnReadableOrganization(false);
    @SuppressWarnings("unchecked")
    List<Tab> tabs = criteria.list();
    Set<String> windowIds = new LinkedHashSet<>();
    for (Tab tab : tabs) {
      Window window = tab.getWindow();
      if (window != null && Boolean.TRUE.equals(window.isActive())) {
        windowIds.add(window.getId());
      }
    }
    return windowIds;
  }
}
