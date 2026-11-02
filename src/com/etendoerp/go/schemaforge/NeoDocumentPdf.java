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
 * The PDF is produced by the browser (jsreport) when a document's preview is opened or the document
 * is sent, and stored as the record's main attachment ({@code EM_ETGO_IsPreviewMain = 'Y'}).
 *
 * <p><b>Server-side render, MCP only (ETP-6013, reverses ETP-5715's "never renders").</b> For an
 * entity that declares {@code MCP_CONFIG.pdf.print}, the server renders the same printable itself
 * ({@link NeoPrintableRenderer}): after an MCP Complete ({@link NeoPrintAfterComplete}), and in
 * {@link #resolve}, when the stored PDF is missing or stale and the document is in a status its
 * printable is stored for. {@link #resolve} is called only by the two MCP tools
 * ({@code etendo_get_document_pdf}, {@code etendo_send_document_email}); the GO app never reaches it
 * and keeps producing its PDFs in the browser exactly as before. Without the declaration — or when
 * rendering is not possible — the answer is the stored verdict, unchanged.</p>
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
  public static final Set<String> DOCUMENT_STATUS_REFERENCES =
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

    /**
     * A servable PDF.
     *
     * @param file the attachment metadata the attachments endpoint gives
     * @return a result with status {@code FOUND}
     */
    public static Result found(JSONObject file) {
      return new Result(Status.FOUND, file, null, null);
    }

    /**
     * A stored PDF that is out of date.
     *
     * @param file the attachment metadata, kept so the caller can name the stale file
     * @return a result with status {@code STALE}
     */
    public static Result stale(JSONObject file) {
      return new Result(Status.STALE, file, null, null);
    }

    /**
     * No stored PDF.
     *
     * @param supplier {@code true} when the entity's PDF is the user's own document (the
     *        supplier's), so the caller cannot generate it
     * @return a result with status {@code NO_SUPPLIER_DOCUMENT} or {@code MISSING}
     */
    public static Result notStored(boolean supplier) {
      return new Result(supplier ? Status.NO_SUPPLIER_DOCUMENT : Status.MISSING, null, null, null);
    }

    /**
     * No stored PDF, with the document's status when it could be read.
     *
     * @param documentStatus the record's {@code documentStatus}, or {@code null} when the entity
     *        has none or it could not be read
     * @return a result with status {@code MISSING} that carries the document status
     */
    public static Result notStoredInStatus(String documentStatus) {
      return new Result(Status.MISSING, null, null, documentStatus);
    }

    /**
     * The lookup failed.
     *
     * @param message why the lookup failed
     * @return a result with status {@code FAILED}
     */
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
    // MCP-only: this may render and store (ETP-6013). Its only callers are the two MCP tools,
    // McpDocumentPdfTool and McpDocumentEmailTool; the GO app never reaches it. A REST/SPA path that
    // needs the verdict must call resolveStored, which never renders.
    Result stored = resolveStored(target, entity);
    if (stored.getStatus() != Status.MISSING && stored.getStatus() != Status.STALE) {
      return stored;
    }
    // ETP-6013: a document whose entity declares a printable has its PDF rendered on the server
    // (in isolation, on NeoPrintAfterComplete's worker) when it is missing or stale — a document
    // completed in the GO app, by a create-and-complete handler, in Classic, or whose eager render
    // did not run. Any failure (nothing configured, no write access (D23), a person's own main
    // file, a status that stores nothing, a render error, the wait expired) keeps the stored
    // verdict, so the caller answers no_pdf / pdf_stale exactly as before.
    return renderOnRequest(target, entity, stored, NeoPrintAfterComplete::renderAndWait);
  }

  /** A per-call render budget scope; closing it restores the previous one. Never throws. */
  @FunctionalInterface
  public interface RenderBudget extends AutoCloseable {
    @Override
    void close();
  }

  /** How many documents one MCP call may render synchronously; the rest are queued. */
  static final int SYNC_RENDERS_PER_CALL = 3;

  /** The renders left to the current MCP call, or {@code null} outside a budgeted call (no limit). */
  private static final ThreadLocal<int[]> SYNC_RENDER_BUDGET = new ThreadLocal<>();

  /**
   * Opens a per-call budget of synchronous renders: within it, {@link #resolve} renders at most
   * {@value #SYNC_RENDERS_PER_CALL} documents and queues the rest (they answer {@code no_pdf} now,
   * with the "ask again in a few seconds" hint, and are rendered in the background). For the
   * multi-document {@code etendo_get_document_pdf}, so 50 ids cannot hold a request for minutes.
   *
   * @return the scope to close when the call ends
   */
  public static RenderBudget syncRenderBudget() {
    int[] previous = SYNC_RENDER_BUDGET.get();
    SYNC_RENDER_BUDGET.set(new int[] { SYNC_RENDERS_PER_CALL });
    return () -> {
      if (previous == null) {
        SYNC_RENDER_BUDGET.remove();
      } else {
        SYNC_RENDER_BUDGET.set(previous);
      }
    };
  }

  /** Takes one synchronous render from the call's budget; {@code true} when there was one left. */
  static boolean takeSyncRender() {
    int[] budget = SYNC_RENDER_BUDGET.get();
    if (budget == null) {
      return true;
    }
    if (budget[0] <= 0) {
      return false;
    }
    budget[0]--;
    return true;
  }

  /** An isolated render of one document, waited for: its outcome, or {@code null} past the wait. */
  @FunctionalInterface
  interface IsolatedRender {
    /**
     * Renders one document and waits for it.
     *
     * @param entity   the document's root entity
     * @param recordId the document's id
     * @param language the language to print in
     * @return the outcome, or {@code null} when it did not finish within the wait
     */
    NeoPrintableRenderer.Outcome render(SFEntity entity, String recordId, String language);
  }

  /** The language a server render prints in, set by the calling MCP tool; null outside one. */
  private static final ThreadLocal<String> RENDER_LANGUAGE = new ThreadLocal<>();

  /** A render-language scope; closing it restores the previous one. Never throws. */
  @FunctionalInterface
  public interface RenderLanguage extends AutoCloseable {
    @Override
    void close();
  }

  /**
   * Sets the language a server render triggered by {@link #resolve} prints in, for the current MCP
   * tool call: {@code etendo_get_document_pdf} passes the MCP default ({@code es_ES}, the email tool's
   * default), {@code etendo_send_document_email} the email's language, so an email and the PDF it
   * carries always match. A stored PDF that is current is served as it is, whatever its language.
   *
   * @param language an Etendo language code ({@code es_ES})
   * @return the scope to close when the call ends
   */
  public static RenderLanguage renderLanguage(String language) {
    String previous = RENDER_LANGUAGE.get();
    RENDER_LANGUAGE.set(language);
    return () -> {
      if (previous == null) {
        RENDER_LANGUAGE.remove();
      } else {
        RENDER_LANGUAGE.set(previous);
      }
    };
  }

  /** The current tool call's render language, or {@code null} (the context's language). */
  static String currentRenderLanguage() {
    return RENDER_LANGUAGE.get();
  }

  /**
   * The fallback render: only for a declared printable that is not a draft.
   *
   * <p><b>Isolated (B1).</b> The render runs on {@link NeoPrintAfterComplete}'s worker — own thread,
   * own session, own commit — never in the MCP request's transaction, and this waits for it with a
   * bound. A render already queued or running for the document (the eager one of the Complete just
   * made) is joined, not duplicated; across nodes the worker's advisory lock and re-check keep a
   * second store out (B2). Beyond the call's budget the render is queued and this answers the stored
   * verdict at once.</p>
   *
   * @param stored the stored-file verdict (MISSING or STALE)
   * @param render the isolated render ({@link NeoPrintAfterComplete#renderAndWait} in production)
   * @return the verdict read after a successful render, else {@code stored}
   */
  static Result renderOnRequest(NeoAttachmentTarget target, SFEntity entity, Result stored,
      IsolatedRender render) {
    if (entity == null || NeoPdfSourceConfig.printDocumentOf(entity) == null || stored.isDraft()) {
      return stored;
    }
    String language = currentRenderLanguage();
    if (!takeSyncRender()) {
      NeoPrintAfterComplete.queue(entity, target.getRecordId(), language);
      return stored;
    }
    NeoPrintableRenderer.Outcome outcome = render.render(entity, target.getRecordId(), language);
    if (outcome != NeoPrintableRenderer.Outcome.STORED
        && outcome != NeoPrintableRenderer.Outcome.ALREADY_CURRENT) {
      log.debug("PDF not rendered on request ({}); answering the stored verdict", outcome);
      return stored;
    }
    // The worker committed the file; this session may still hold the old row of an attachment that
    // was overwritten in place, so it is forgotten before the verdict is read again.
    forgetCachedFile(stored);
    Result rendered = resolveStored(target, entity);
    return rendered.getStatus() == Status.FOUND ? rendered : stored;
  }

  /** Evicts the stored verdict's attachment from this session, so a re-read sees its new row. */
  private static void forgetCachedFile(Result stored) {
    String id = stored.getFile() == null ? null : stored.getFile().optString(KEY_ID, null);
    if (StringUtils.isBlank(id)) {
      return;
    }
    OBContext.setAdminMode(true);
    try {
      org.openbravo.model.ad.utility.Attachment cached =
          OBDal.getInstance().get(org.openbravo.model.ad.utility.Attachment.class, id);
      if (cached != null) {
        OBDal.getInstance().getSession().evict(cached);
      }
    } catch (RuntimeException e) {
      log.debug("Could not forget the cached attachment: {}", e.getClass().getSimpleName());
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * The stored-file verdict, without rendering: what {@link #resolve} answered before ETP-6013.
   *
   * @param target the document, already authorized for reading
   * @param entity the spec's document entity
   * @return the verdict; never throws for a missing PDF
   */
  static Result resolveStored(NeoAttachmentTarget target, SFEntity entity) {
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
      return decided.getStatus() == Status.MISSING ? Result.notStoredInStatus(documentStatus(target))
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
      return Result.notStored(source == Source.SUPPLIER);
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
  static String documentStatus(NeoAttachmentTarget target) {
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
