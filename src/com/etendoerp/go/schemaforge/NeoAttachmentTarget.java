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
 * All portions are Copyright © 2021–2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge;

import org.apache.commons.lang3.StringUtils;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.datamodel.Table;
import org.openbravo.model.ad.ui.Tab;
import org.openbravo.model.ad.utility.Attachment;

import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.data.SFSpec;
import com.etendoerp.go.schemaforge.util.NeoAccessHelper;

/**
 * The document an MCP attachment operation is about, authorized (ETP-5715).
 *
 * <p><b>Why it exists.</b> REST attachments are addressed by table name and record id and check only
 * the window-access tier for writes ({@link NeoAttachmentAuthorizer}, ADR-0003 slice one): nothing
 * decides that the record belongs to the caller's client and organization, and a read is not
 * authorized at all. The MCP does not inherit that gap, so every MCP attachment operation resolves
 * its parent here first.</p>
 *
 * <p><b>The rule — structure only, no spec named.</b> The parent is the root record of the entity's
 * own AD tab ({@code SFEntity#getADTab()}, tab level 0 — the document the SPA's "Adjuntos" tab
 * hangs on), never a line. The role must have access to the spec (read tier for reading, write tier
 * for writing — the same tiers the router applies to {@code etendo_get}/{@code etendo_update}) and,
 * for writing, to a window that shows the table ({@link NeoAttachmentAuthorizer#checkWrite}). The
 * record must exist and belong to a client and organization the session can read
 * ({@link TenantOwnership}); a missing record and another tenant's record answer the same
 * {@value #MSG_RECORD_NOT_FOUND}, so an id cannot be probed for existence. An attachment id is only
 * ever honoured when it belongs to that very record ({@link #requireOwnAttachment}).</p>
 */
public final class NeoAttachmentTarget {

  /** Same text for a missing and for a foreign record, on purpose. */
  public static final String MSG_RECORD_NOT_FOUND = NeoActionRecordGuard.MSG_RECORD_NOT_FOUND;

  /** Same text for a missing attachment and for one that belongs to another record. */
  public static final String MSG_ATTACHMENT_NOT_FOUND = NeoAttachmentsHelper.ERR_ATTACHMENT_NOT_FOUND;

  private static final String MSG_NO_ATTACHABLE_RECORD =
      "This entity has no record that can carry attachments.";
  private static final String METHOD_READ = "GET";
  private static final String METHOD_WRITE = "POST";

  /** A refusal an operation answers as-is: the status and the message carry the whole answer. */
  public static final class Refused extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final int status;

    /**
     * Builds a refusal.
     *
     * @param status  the HTTP-style status: 403 role, 404 missing or foreign, 422 not attachable
     * @param message what the caller is told
     */
    public Refused(int status, String message) {
      super(message);
      this.status = status;
    }

    /** Returns the status. */
    public int getStatus() {
      return status;
    }
  }

  private final String specName;
  private final String entityName;
  private final String tableName;
  private final String tableId;
  private final String tabId;
  private final String recordId;

  private NeoAttachmentTarget(SFSpec spec, SFEntity entity, Tab tab, String recordId) {
    Table table = tab.getTable();
    this.specName = spec.getName();
    this.entityName = entity.getName();
    this.tableName = table.getDBTableName();
    this.tableId = table.getId();
    this.tabId = tab.getId();
    this.recordId = recordId;
  }

  /**
   * Resolves and authorizes the document an attachment operation addresses.
   *
   * @param spec     the active spec of the call
   * @param entity   the spec's entity whose record is the parent; must be a root (tab level 0)
   * @param recordId the parent record's id
   * @param write    {@code true} for upload and delete, {@code false} for list and download
   * @return the authorized target
   * @throws Refused 405 when the document type is declared attachment-less in {@code MCP_CONFIG}
   *         (checked first, before any lookup), 403 when the role lacks the access tier, 404 when the record is missing or not
   *         the tenant's, 422 when the entity has no attachable record
   */
  public static NeoAttachmentTarget resolve(SFSpec spec, SFEntity entity, String recordId,
      boolean write) {
    OBContext.setAdminMode(true);
    try {
      Tab tab = entity == null ? null : entity.getADTab();
      if (tab == null || tab.getTable() == null || StringUtils.isBlank(recordId)) {
        throw new Refused(422, MSG_NO_ATTACHABLE_RECORD);
      }
      requireAttachable(entity, tab);
      requireRoleAccess(spec, tab.getTable().getId(), write);
      requireVisibleRecord(tab.getTable().getId(), recordId, write);
      return new NeoAttachmentTarget(spec, entity, tab, recordId);
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /** The document itself, not a line, and not a type the application gives no Attachments tab. */
  private static void requireAttachable(SFEntity entity, Tab tab) {
    if (tab.getTabLevel() != null && tab.getTabLevel() > 0) {
      throw new Refused(422, "Attachments belong to the document itself, not to its lines. "
          + "Use the document's own entity.");
    }
    String unavailable = NeoAttachmentsConfig.refusalFor(entity);
    if (unavailable != null) {
      // Declared in MCP_CONFIG, not a secret: a clear refusal, before any role or record lookup.
      throw new Refused(405, unavailable);
    }
  }

  /**
   * Checks that the role may write attachments to documents of this spec, without any record in
   * view: the part of {@link #resolve} that does not depend on one (ETP-5715, staged documents).
   *
   * @param spec   the active spec
   * @param entity the spec's root entity
   * @throws Refused 405 when the document type has no Attachments tab, 403 when the role lacks the
   *         write tier, 422 when the entity has no attachable table or is a line
   */
  public static void requireWriteAccess(SFSpec spec, SFEntity entity) {
    OBContext.setAdminMode(true);
    try {
      Tab tab = entity == null ? null : entity.getADTab();
      if (tab == null || tab.getTable() == null) {
        throw new Refused(422, MSG_NO_ATTACHABLE_RECORD);
      }
      requireAttachable(entity, tab);
      requireRoleAccess(spec, tab.getTable().getId(), true);
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  private static void requireRoleAccess(SFSpec spec, String tableId, boolean write) {
    if (!NeoAccessHelper.hasWindowAccessForSpec(spec, write ? METHOD_WRITE : METHOD_READ)) {
      throw new Refused(403, NeoAttachmentAuthorizer.ACCESS_DENIED);
    }
    NeoResponse denied = write ? NeoAttachmentAuthorizer.checkWrite(tableId) : null;
    if (denied != null) {
      throw new Refused(403, NeoAttachmentAuthorizer.ACCESS_DENIED);
    }
  }

  /**
   * The record exists and is readable by the session's client and organization — or 404. For a
   * change (upload, delete) its organization must also be one the role may WRITE in; the answer is
   * the same 404, so a read-only organization is not an oracle for existing records.
   */
  private static void requireVisibleRecord(String tableId, String recordId, boolean write) {
    Entity dalEntity = ModelProvider.getInstance().getEntityByTableId(tableId);
    if (dalEntity == null) {
      // No DAL entity: ownership cannot be proven, so the record is not offered.
      throw new Refused(404, MSG_RECORD_NOT_FOUND);
    }
    BaseOBObject row = (BaseOBObject) OBDal.getInstance().get(dalEntity.getName(), recordId);
    boolean allowed = row != null && (write ? TenantOwnership.isWritableByCurrentTenant(row)
        : TenantOwnership.isVisibleToCurrentTenant(row));
    if (!allowed) {
      throw new Refused(404, MSG_RECORD_NOT_FOUND);
    }
  }

  /**
   * The attachment with this id, only if it belongs to this target's record.
   *
   * @param attachmentId the {@code C_File_ID} the caller sent
   * @return the attachment
   * @throws Refused 404 when it does not exist or belongs to another table or record — the same
   *         answer, so an id of another document is indistinguishable from an unknown one
   */
  public Attachment requireOwnAttachment(String attachmentId) {
    Attachment attachment = StringUtils.isBlank(attachmentId) ? null
        : OBDal.getInstance().get(Attachment.class, attachmentId);
    if (attachment == null || attachment.getTable() == null
        || !tableId.equals(attachment.getTable().getId())
        || !recordId.equals(attachment.getRecord())) {
      throw new Refused(404, MSG_ATTACHMENT_NOT_FOUND);
    }
    return attachment;
  }

  /** Returns the spec name. */
  public String getSpecName() {
    return specName;
  }

  /** Returns the entity name. */
  public String getEntityName() {
    return entityName;
  }

  /** Returns the table name. */
  public String getTableName() {
    return tableName;
  }

  /** Returns the table id. */
  public String getTableId() {
    return tableId;
  }

  /** Returns the tab id. */
  public String getTabId() {
    return tabId;
  }

  /** Returns the record id. */
  public String getRecordId() {
    return recordId;
  }
}
