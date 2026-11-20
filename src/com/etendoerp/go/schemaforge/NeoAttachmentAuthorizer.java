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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hibernate.criterion.Restrictions;
import org.openbravo.base.exception.OBException;
import org.openbravo.base.exception.OBSecurityException;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.base.structure.ActiveEnabled;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.datamodel.Table;
import org.openbravo.model.ad.ui.Tab;
import org.openbravo.model.ad.ui.Window;
import org.openbravo.model.ad.utility.Attachment;

import com.etendoerp.go.schemaforge.util.NeoAccessHelper;
import com.etendoerp.go.schemaforge.util.RoleAccessMatrix;

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
 * <p><b>Proxy windows.</b> Some tables are only shown by a technical support window that no role
 * template grants, while the feature itself is granted through a different, proxy window. For those
 * tables {@link #PROXY_WINDOWS_BY_TABLE} adds the proxy to the candidate set, so the rule matches the
 * access the feature already has. Today: {@code ETGO_Fiscal_Decl} (fiscal models 303/349 — the
 * justificante / acuse de recibo upload) is shown only by "Fiscal Declarations NEO Support", while
 * Finance reaches "Modelos fiscales" through its Tax Report grant (ETP-5116, {@code
 * TemplateRoleWindowAccess#financeGrants()}). The proxy window id is the shared {@link
 * RoleAccessMatrix#TAX_MODELS_PROXY_WINDOW_ID} (ETP-5540); the table-to-feature link stays
 * hardcoded here until ETP-4802 lets a GO feature declare its support tables.</p>
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

  /** {@code ETGO_Fiscal_Decl} — fiscal model declarations (303/349). */
  static final String FISCAL_DECL_TABLE_ID = "F8B5925363A64980B7522FFD98A1628B";

  /** Extra windows whose editable access also authorizes writes on a table; see class comment. */
  static final Map<String, List<String>> PROXY_WINDOWS_BY_TABLE =
      Map.of(FISCAL_DECL_TABLE_ID, List.of(RoleAccessMatrix.TAX_MODELS_PROXY_WINDOW_ID));

  /** What an operation needs on the attachment's parent business record. */
  public enum Access {
    READ, WRITE
  }

  private NeoAttachmentAuthorizer() {
  }

  /**
   * The one answer for a nonexistent attachment and for one the caller may not reach (ADR-0003 D5):
   * same status, same body, so an id cannot be probed for existence.
   *
   * @return a {@code 404 Attachment not found} response
   */
  public static NeoResponse notFound() {
    return NeoResponse.error(404, NeoAttachmentsHelper.ERR_ATTACHMENT_NOT_FOUND);
  }

  /**
   * Parent-record authorization for an operation addressed by attachment id (ETP-4570, SEC-11b).
   * The table and record are read off the stored attachment row.
   *
   * @param attachmentId the {@code C_File_ID} taken from the request
   * @param access       whether the operation reads or writes the parent record
   * @return the attachment, or {@code null} when it does not exist or the caller may not reach its
   *         parent record; the caller answers {@link #notFound()} for both
   */
  public static Attachment authorizeById(String attachmentId, Access access) {
    return authorizeById(attachmentId, null, null, access);
  }

  /**
   * Same as {@link #authorizeById(String, Access)}, additionally rejecting a client-supplied
   * {@code tableName}/{@code recordId} that disagrees with the attachment's real parent. The
   * supplied values never decide anything: a mismatch is a rejection, not a fallback.
   *
   * @param attachmentId      the {@code C_File_ID} taken from the request
   * @param suppliedTableName the table name the client sent, or {@code null} when none
   * @param suppliedRecordId  the record id the client sent, or {@code null} when none
   * @param access            whether the operation reads or writes the parent record
   * @return the attachment, or {@code null} when it does not exist, the caller may not reach its
   *         parent record, or the supplied context does not match it
   */
  public static Attachment authorizeById(String attachmentId, String suppliedTableName,
      String suppliedRecordId, Access access) {
    if (StringUtils.isBlank(attachmentId)) {
      return null;
    }
    Attachment attachment = loadAttachment(attachmentId);
    if (attachment == null || attachment.getTable() == null) {
      return null;
    }
    String tableId = attachment.getTable().getId();
    String recordId = attachment.getRecord();
    if (StringUtils.isNotEmpty(suppliedTableName) && !isSameTable(suppliedTableName, tableId)) {
      return null;
    }
    if (StringUtils.isNotEmpty(suppliedRecordId) && !suppliedRecordId.equals(recordId)) {
      return null;
    }
    return canAccessParent(tableId, recordId, access) ? attachment : null;
  }

  /**
   * Parent-record authorization for an operation addressed by table and record (list, count, main,
   * upload, download all).
   *
   * <p>Three cases are left to the operation instead of denied, and none discloses data: a blank
   * table or record, and a table name that resolves to no table, are answered by the operation
   * itself ({@code 400} / {@code 404 Unknown table}) before it reads any attachment; a table with no
   * DAL entity is handled in {@link #canAccessParent}.</p>
   *
   * @param tableName the physical table name from the request path
   * @param recordId  the record id from the request path
   * @param access    whether the operation reads or writes the parent record
   * @return {@code true} when the caller may perform {@code access} on that record
   */
  public static boolean authorizeByRecord(String tableName, String recordId, Access access) {
    String tableId = StringUtils.isBlank(recordId) ? null : tableIdOrNull(tableName);
    return tableId == null || canAccessParent(tableId, recordId, access);
  }

  /**
   * Loads the attachment row inside a narrow admin scope — reading the row needs no entity access
   * of the caller's role, the decision is taken afterwards on its parent — and keeps only a row of
   * the caller's tenant. A security failure while loading reads as "not found".
   */
  private static Attachment loadAttachment(String attachmentId) {
    OBContext.setAdminMode(true);
    try {
      return TenantOwnership.loadOwned(Attachment.class, attachmentId);
    } catch (OBSecurityException e) {
      return null;
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /** The table id for a request's table name, or {@code null} when it is blank or unknown. */
  private static String tableIdOrNull(String tableName) {
    if (StringUtils.isBlank(tableName)) {
      return null;
    }
    try {
      return NeoAttachmentsHelper.resolveTableId(tableName);
    } catch (OBException e) {
      return null;
    }
  }

  private static boolean isSameTable(String tableName, String tableId) {
    try {
      return tableId.equals(NeoAttachmentsHelper.resolveTableId(tableName));
    } catch (OBException e) {
      return false;
    }
  }

  /** Role-level access to the entity; a seam, because {@code EntityAccessChecker} cannot be mocked. */
  static boolean hasEntityAccess(OBContext context, Entity entity, Access access) {
    try {
      if (access == Access.WRITE) {
        context.getEntityAccessChecker().checkWritableAccess(entity);
      } else {
        context.getEntityAccessChecker().checkReadableAccess(entity);
      }
      return true;
    } catch (OBSecurityException e) {
      return false;
    }
  }

  /**
   * Decides on the parent business record with the caller's real privileges: role access to the
   * entity, then readable (or writable) client/organization of the row, and the row must exist and
   * be active. Both primitives read the session's role data, which admin mode does not widen
   * ({@code EntityAccessChecker} "does not take into account admin mode"), so the surrounding
   * {@code NeoServlet} admin mode cannot weaken the decision (ADR-0003 D3). A table with no DAL
   * entity has no record to judge (the platform's own attach manager skips it too), so it is
   * allowed here. WRITE operations are then still subject to the window tier
   * ({@link #checkWrite}); READ operations have no further check for such a table. The decision
   * is pinned by a test.
   */
  static boolean canAccessParent(String tableId, String recordId, Access access) {
    Entity entity = ModelProvider.getInstance().getEntityByTableId(tableId);
    if (entity == null) {
      return true;
    }
    OBContext context = OBContext.getOBContext();
    if (context == null || StringUtils.isBlank(recordId)) {
      return false;
    }
    if (!hasEntityAccess(context, entity, access)) {
      return false;
    }
    try {
      BaseOBObject parent = OBDal.getInstance().get(entity.getName(), recordId);
      if (parent == null || (parent instanceof ActiveEnabled
          && !Boolean.TRUE.equals(((ActiveEnabled) parent).isActive()))) {
        return false;
      }
      return access == Access.WRITE ? TenantOwnership.isWritableByCurrentTenant(parent)
          && TenantOwnership.isVisibleToCurrentTenant(parent)
          : TenantOwnership.isVisibleToCurrentTenant(parent);
    } catch (OBSecurityException e) {
      return false;
    }
  }

  /**
   * Write check for an operation addressed by table name (upload).
   *
   * @param tableName physical table name from the request path, e.g. {@code C_Order}
   * @return {@code null} when allowed (or when the table is unknown — the operation reports that
   *         itself), otherwise a {@code 403} response
   */
  public static NeoResponse checkWriteOnTable(String tableName) {
    String tableId = tableIdOrNull(tableName);
    return tableId == null ? null : checkWrite(tableId);
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
    windowIds.addAll(PROXY_WINDOWS_BY_TABLE.getOrDefault(tableId, Collections.emptyList()));
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
