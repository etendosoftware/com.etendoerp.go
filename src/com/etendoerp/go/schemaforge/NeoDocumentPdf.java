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

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.base.model.Property;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.datamodel.Column;
import org.openbravo.model.ad.datamodel.Table;
import org.openbravo.model.ad.domain.Reference;
import org.openbravo.model.ad.utility.Image;

import com.etendoerp.go.common.CompanyLogoResolver;
import com.etendoerp.go.schemaforge.NeoPdfSourceConfig.Source;
import com.etendoerp.go.schemaforge.data.SFEntity;

/**
 * The PDF of a document, the way the application already has it stored (ETP-5715). It decides which
 * stored file answers "give me the PDF of this document" and whether the SPA would still serve it.
 * It never renders anything: the PDF is produced by the browser (jsreport) when a document's preview
 * is opened, and stored as the record's main attachment ({@code EM_ETGO_IsPreviewMain = 'Y'}).
 *
 * <p><b>One flag, two meanings, told apart by declaration.</b> The main-attachment flag marks BOTH
 * the PDF the preview rendered and stored (sales invoice, orders, quotation, shipments, returns) and
 * a document the user uploaded as main (the supplier's PDF of a purchase invoice, the delivery note
 * of a goods receipt, which have no printable). The column cannot tell them apart and the SPA tells
 * them apart per window, in a frontend decision the backend never sees. So the entity declares it:
 * {@code MCP_CONFIG.pdf.source} ({@link NeoPdfSourceConfig}). {@code supplier} returns the main file
 * as is and never calls it stale; the default {@code rendered} applies the freshness rule below to
 * the main file whatever its name, because that is what the SPA does ({@code fetchCachedBlob}).</p>
 *
 * <p><b>Freshness, the SPA's rule ({@code attachmentFreshness.js}).</b> A rendered cache written
 * before the record's last change, or before the company branding's last change, is stale. Both
 * comparisons are strict and in whole seconds, unknown timestamps read as fresh (fail-open), and the
 * file's own {@code updatedAt} is the write time (not its creation: a re-render overwrites the row).
 * The SPA's third reason — the renderer bundle changed — cannot be known here and is not applied.</p>
 */
public final class NeoDocumentPdf {

  private static final Logger log = LogManager.getLogger(NeoDocumentPdf.class);

  private static final String KEY_ID = "id";
  /** The AD_Ref_List value {@code DR} (Draft) of the document status lists. */
  public static final String DRAFT_STATUS = "DR";
  /**
   * AD_Reference ids of the document status lists: {@code 131} (All_Document Status: invoices,
   * receipts and shipments, journals...) and the Order_Document Status list the order windows use
   * ({@code FF80818130217A350130218D802B0011}). Both carry {@code DR} as their draft value.
   */
  static final Set<String> DOCUMENT_STATUS_REFERENCES =
      Set.of("131", "FF80818130217A350130218D802B0011");

  /** What the stored file is worth as the document's PDF. */
  public enum Status {
    /** A PDF to serve: a fresh rendered cache, or the user's own main document. */
    FOUND,
    /** A rendered cache that predates the document (or the branding): not current, not served. */
    STALE,
    /** The document has no main attachment. */
    MISSING,
    /** A supplier-document entity with no main attachment. */
    NO_SUPPLIER_DOCUMENT,
    /** The lookup itself failed. */
    FAILED
  }

  /** The outcome of {@link #resolve}: a status, the stored file's metadata and a failure message. */
  public static final class Result {
    private final Status status;
    private final JSONObject file;
    private final String message;
    private final String documentStatus;

    private Result(Status status, JSONObject file, String message, String documentStatus) {
      this.status = status;
      this.file = file;
      this.message = message;
      this.documentStatus = documentStatus;
    }

    /** A servable PDF; {@code file} is the attachment metadata the attachments endpoint gives. */
    public static Result found(JSONObject file) {
      return new Result(Status.FOUND, file, null, null);
    }

    /** A stored PDF that is out of date; {@code file} is kept so the caller can name it. */
    public static Result stale(JSONObject file) {
      return new Result(Status.STALE, file, null, null);
    }

    /** No stored PDF; {@code supplier} when the entity's PDF is the user's own document. */
    public static Result missing(boolean supplier) {
      return new Result(supplier ? Status.NO_SUPPLIER_DOCUMENT : Status.MISSING, null, null, null);
    }

    /**
     * No stored PDF, with the document's status when it could be read.
     *
     * @param documentStatus the record's {@code documentStatus}, or {@code null} when the entity
     *        has none or it could not be read
     */
    public static Result missing(String documentStatus) {
      return new Result(Status.MISSING, null, null, documentStatus);
    }

    /** The lookup failed with this message. */
    public static Result failed(String message) {
      return new Result(Status.FAILED, null, message, null);
    }

    /** Returns the status. */
    public Status getStatus() {
      return status;
    }

    /** Returns the attachment metadata (id, name, size, dataType, updatedAt, ...), or null. */
    public JSONObject getFile() {
      return file;
    }

    /**
     * Returns the document's status for a {@code MISSING} result (e.g. {@code DR}), or null when
     * unknown: a caller words the "no PDF" advice differently for a draft, which never has one.
     */
    public String getDocumentStatus() {
      return documentStatus;
    }

    /** Whether the document is a draft: its status is the draft value of the status list. */
    public boolean isDraft() {
      return DRAFT_STATUS.equals(documentStatus);
    }

    /** Returns the failure message, or null. */
    public String getMessage() {
      return message;
    }
  }

  private NeoDocumentPdf() {
  }

  /**
   * Finds the stored PDF of an authorized document.
   *
   * <p>The main attachment is the one the SPA reads ({@link NeoAttachmentsHelper#handleGetMain}), so
   * the preview and this answer cannot name different files.</p>
   *
   * @param target the document, already authorized for reading
   * @param entity the spec's document entity, whose {@code MCP_CONFIG.pdf} says where its PDF comes from
   * @return the verdict; never throws for a missing PDF
   */
  public static Result resolve(NeoAttachmentTarget target, SFEntity entity) {
    NeoResponse answer = NeoAttachmentsHelper.handleGetMain(target.getTableName(),
        target.getRecordId());
    if (answer.getHttpStatus() >= 400) {
      return Result.failed(failureMessage(answer));
    }
    JSONObject main = answer.getBody();
    Source source = NeoPdfSourceConfig.sourceOf(entity);
    if (main == null || StringUtils.isBlank(main.optString(KEY_ID, null))
        || source == Source.SUPPLIER) {
      Result decided = decide(main, source, null, null);
      return decided.getStatus() == Status.MISSING ? Result.missing(documentStatus(target))
          : decided;
    }
    return decide(main, source, recordUpdated(target), brandingUpdated());
  }

  /**
   * The pure decision: is this main file the document's current PDF.
   *
   * @param main            the main attachment's metadata, or null/empty when there is none
   * @param source          where the entity's PDF comes from
   * @param recordUpdated   the record's last change, or null when unknown
   * @param brandingUpdated the company branding's last change, or null when unknown
   * @return FOUND, STALE, MISSING or NO_SUPPLIER_DOCUMENT
   */
  static Result decide(JSONObject main, Source source, Date recordUpdated, Date brandingUpdated) {
    if (main == null || StringUtils.isBlank(main.optString(KEY_ID, null))) {
      return Result.missing(source == Source.SUPPLIER);
    }
    if (source == Source.SUPPLIER) {
      // The user's own document: nothing of ours can outdate it.
      return Result.found(main);
    }
    Instant writtenAt = writtenAt(main);
    // The SPA's guard, kept in its place: without the record's own timestamp nothing invalidates
    // (the branding is only consulted once the record's change is known).
    if (recordUpdated != null && writtenAt != null
        && (isOlder(writtenAt, recordUpdated) || isOlder(writtenAt, brandingUpdated))) {
      return Result.stale(main);
    }
    return Result.found(main);
  }

  /** The file's write time: {@code updatedAt}, or its creation when the payload has no such field. */
  private static Instant writtenAt(JSONObject main) {
    for (String key : new String[] {"updatedAt", "uploadedAt"}) {
      Instant parsed = parse(main.optString(key, null));
      if (parsed != null) {
        return parsed;
      }
    }
    return null;
  }

  private static Instant parse(String value) {
    if (StringUtils.isBlank(value)) {
      return null;
    }
    try {
      return Instant.parse(value).truncatedTo(ChronoUnit.SECONDS);
    } catch (DateTimeParseException e) {
      return null;
    }
  }

  /** Strictly older, in whole seconds; an unknown reference is never newer (fail-open). */
  private static boolean isOlder(Instant writtenAt, Date reference) {
    return reference != null
        && writtenAt.isBefore(reference.toInstant().truncatedTo(ChronoUnit.SECONDS));
  }

  /**
   * The record's document status, or null when the entity has no single status column or it cannot
   * be read. Read only to word the advice for a missing PDF.
   *
   * <p>Structural: the status column is the one whose AD column points at a document status list
   * ({@link #DOCUMENT_STATUS_REFERENCES}), whatever the column or the DAL property is called; none or
   * more than one is unknown, never a guess.</p>
   */
  private static String documentStatus(NeoAttachmentTarget target) {
    try {
      OBContext.setAdminMode(true);
      try {
        Table table = OBDal.getInstance().get(Table.class, target.getTableId());
        Column statusColumn = table == null ? null : singleStatusColumn(table);
        Entity dalEntity = statusColumn == null ? null
            : ModelProvider.getInstance().getEntityByTableId(target.getTableId());
        if (dalEntity == null) {
          return null;
        }
        Property property = dalEntity.getPropertyByColumnName(statusColumn.getDBColumnName());
        Object row = OBDal.getInstance().get(dalEntity.getName(), target.getRecordId());
        Object value = row instanceof BaseOBObject && property != null
            ? ((BaseOBObject) row).get(property.getName()) : null;
        return value == null ? null : String.valueOf(value);
      } finally {
        OBContext.restorePreviousMode();
      }
    } catch (RuntimeException e) {
      log.debug("Document status unavailable for the PDF advice: {}", e.getClass().getName());
      return null;
    }
  }

  /** The table's only active column that uses a document status list, or null. */
  private static Column singleStatusColumn(Table table) {
    Column found = null;
    for (Column column : table.getADColumnList()) {
      Reference list = Boolean.TRUE.equals(column.isActive())
          ? column.getReferenceSearchKey() : null;
      if (list != null && DOCUMENT_STATUS_REFERENCES.contains(list.getId())) {
        if (found != null) {
          return null;
        }
        found = column;
      }
    }
    return found;
  }

  /** The record's {@code updated}, or null when it cannot be read. */
  private static Date recordUpdated(NeoAttachmentTarget target) {
    OBContext.setAdminMode(true);
    try {
      Entity dalEntity = ModelProvider.getInstance().getEntityByTableId(target.getTableId());
      Object row = dalEntity == null ? null
          : OBDal.getInstance().get(dalEntity.getName(), target.getRecordId());
      Object updated = row instanceof BaseOBObject ? ((BaseOBObject) row).get("updated") : null;
      return updated instanceof Date ? (Date) updated : null;
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /** The company branding's last change for the session's organization (the SPA's session field). */
  private static Date brandingUpdated() {
    OBContext.setAdminMode(true);
    try {
      OBContext context = OBContext.getOBContext();
      String clientId = context.getCurrentClient().getId();
      String orgId = context.getCurrentOrganization().getId();
      Image logo = CompanyLogoResolver.resolve(clientId, orgId);
      return NeoSessionService.resolveBrandingUpdated(orgId, logo);
    } catch (RuntimeException e) {
      // Unknown beats wrong: a branding that cannot be read never makes a PDF stale.
      log.warn("Branding timestamp unavailable, not applied to the PDF freshness: {}",
          e.getMessage());
      return null;
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  private static String failureMessage(NeoResponse answer) {
    JSONObject error = answer.getBody() == null ? null : answer.getBody().optJSONObject("error");
    return error == null ? "The stored PDF could not be looked up."
        : error.optString("message", "The stored PDF could not be looked up.");
  }
}
