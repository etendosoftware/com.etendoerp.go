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

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.BinaryOperator;
import java.util.function.Supplier;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.base.weld.WeldUtils;
import org.openbravo.dal.core.OBContext;
import org.openbravo.model.ad.datamodel.Table;
import org.openbravo.model.ad.ui.Tab;

import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.data.SFSpec;
import com.etendoerp.go.schemaforge.util.NeoAccessHelper;

/**
 * Renders a document's printable on the server and stores it as the record's main attachment, the
 * way the SPA's preview / send stores it (ETP-6013). The sixth producer of a document's PDF (see
 * {@code docs/document-printables.md}): the only one that runs without a browser, so a document
 * completed or asked for through the MCP has a PDF the MCP can return
 * ({@code etendo_get_document_pdf}) and email ({@code etendo_send_document_email}). The GO app never
 * calls it; besides the MCP, the customer portal reaches it (ETP-6209) when a document it shows has
 * no stored PDF yet, through {@code NeoPrintAfterComplete#renderAndWaitAs}.
 *
 * <h2>What it produces</h2>
 * <p>The template is the one the deployed frontend publishes ({@link NeoPrintableTemplates}), the
 * data is built by the document's {@link NeoPrintableDataBuilder} in the browser's shape, and the
 * render is jsreport, as in the browser ({@link NeoJsreportClient}). The file is stored through
 * {@link NeoAttachmentStore} with {@code markAsMain}, named {@code <spec>-<documentNo>.pdf} like the
 * SPA's send path names it; a same-named main file is overwritten in place, any other main file is
 * replaced, so the stored file's {@code updatedAt} always moves and {@link NeoDocumentPdf} and the
 * SPA's {@code attachmentFreshness} read it as fresh.</p>
 *
 * <h2>When it does nothing</h2>
 * <ul>
 *   <li>the entity declares no {@code MCP_CONFIG.pdf.print} (or its PDF is the supplier's);</li>
 *   <li>the builder does not store a PDF for the document's status (a draft never does);</li>
 *   <li>the current role cannot write the document's attachments — the read-only tier never writes
 *       the cache (D23);</li>
 *   <li>the record's main attachment is a file a person attached — its name is not one Etendo
 *       gives the PDFs it generates ({@link #isEtendoPreviewName}). It is never replaced: storing
 *       as main would delete it;</li>
 *   <li>anything fails: no manifest, no jsreport, a timeout, an unreadable record. Logged, nothing
 *       stored; the MCP keeps answering {@code no_pdf} exactly as before.</li>
 * </ul>
 *
 * <p><b>Transactions.</b> It writes into the current session and does not commit. Its only caller is
 * {@link NeoPrintAfterComplete}'s worker — its own thread and session, after the completion
 * committed — which runs it in three phases: {@link #prepare} (database reads), {@link #renderPdf} (manifest
 * and jsreport over HTTP, which must run with <b>no transaction open</b>) and {@link #store} (the
 * only write, which the worker runs in a new transaction under an advisory lock on the document,
 * after re-checking the stored PDF, and commits itself). {@link #renderAndStore} chains the three in
 * the caller's session. It never runs in an MCP request's (or any business) transaction.</p>
 */
public final class NeoPrintableRenderer {

  private static final Logger log = LogManager.getLogger(NeoPrintableRenderer.class);
  private static final String METHOD_WRITE = "POST";
  private static final String MSG_RENDER_FAILED =
      "[print] {}/{}: server-side render failed, no PDF stored: {}";

  /** What a render attempt ended in. */
  public enum Outcome {
    /** The PDF was rendered and stored as the main attachment. */
    STORED,
    /** No jsreport or printables location is configured here (local without jsreport, CI). */
    NOT_CONFIGURED,
    /** The entity declares no printable, or no builder fills it. Nothing to do, ever. */
    NOT_PRINTABLE,
    /** The document's status gets no stored PDF (a draft...). */
    NOT_FOR_STATUS,
    /** The role may not write the document's attachments (read-only tier, D23). */
    NOT_ALLOWED,
    /** A current PDF was already stored (by another render) when this one got the lock. */
    ALREADY_CURRENT,
    /**
     * The record's main attachment is not a PDF Etendo generated (a user's own file marked main):
     * it is never replaced, so nothing is stored.
     */
    FOREIGN_MAIN,
    /** The render or the store failed; worth retrying later. */
    FAILED
  }

  private final NeoPrintableTemplates templates;
  private final NeoJsreportClient jsreport;
  private final Supplier<Iterable<NeoPrintableDataBuilder>> builders;
  private final BinaryOperator<String> mainFileName;

  /** The production renderer. */
  public NeoPrintableRenderer() {
    this(NeoPrintableTemplates.getInstance(), new NeoJsreportClient(),
        () -> WeldUtils.getInstances(NeoPrintableDataBuilder.class),
        NeoPrintableRenderer::storedMainFileName);
  }

  /**
   * @param mainFileName (tableId, recordId) → the name of the record's main attachment, or
   *                     {@code null} when it has none
   */
  NeoPrintableRenderer(NeoPrintableTemplates templates, NeoJsreportClient jsreport,
      Supplier<Iterable<NeoPrintableDataBuilder>> builders,
      BinaryOperator<String> mainFileName) {
    this.templates = templates;
    this.jsreport = jsreport;
    this.builders = builders;
    this.mainFileName = mainFileName;
  }

  /**
   * What {@link #prepare} read from the database, as plain values (no entity survives a closed
   * session): either a terminal {@link #outcome} or everything {@link #renderPdf} and {@link #store}
   * need.
   */
  public static final class Prepared {
    private final Outcome outcome;
    private final String document;
    private final JSONObject data;
    private final String documentNo;
    private final String[] attachmentTarget;
    private final String specName;
    private final String recordId;

    /** @param attachmentTarget {table name, table id, tab id} the PDF is attached to */
    private Prepared(Outcome outcome, String document, JSONObject data, String documentNo,
        String[] attachmentTarget, String specName, String recordId) {
      this.outcome = outcome;
      this.document = document;
      this.data = data;
      this.documentNo = documentNo;
      this.attachmentTarget = attachmentTarget;
      this.specName = specName;
      this.recordId = recordId;
    }

    private String tableName() {
      return attachmentTarget[0];
    }

    private String tableId() {
      return attachmentTarget[1];
    }

    private String tabId() {
      return attachmentTarget[2];
    }

    private static Prepared terminal(Outcome outcome, String specName, String recordId) {
      return new Prepared(outcome, null, null, null, null, specName, recordId);
    }

    /**
     * @return the outcome when there is nothing to render or store, {@code null} when the document
     *         is ready to render
     */
    public Outcome getOutcome() {
      return outcome;
    }
  }

  /**
   * Renders and stores one document's PDF.
   *
   * @param spec           the active spec the record belongs to
   * @param entity         the spec's root entity holding the record
   * @param recordId       the document's id
   * @param documentStatus the document's status after the change that queued it
   * @param locale         the language to print in (an Etendo language code, e.g. {@code es_ES})
   * @return what happened; never throws
   */
  public Outcome renderAndStore(SFSpec spec, SFEntity entity, String recordId,
      String documentStatus, String locale) {
    Prepared prepared = prepare(spec, entity, recordId, documentStatus);
    if (prepared.outcome != null) {
      return prepared.outcome;
    }
    byte[] pdf = renderPdf(prepared, locale);
    return pdf.length == 0 ? Outcome.FAILED : store(prepared, pdf);
  }

  /**
   * Phase 1: every database read of a render — the checks, the printable's data and the main
   * attachment's name. Never calls jsreport or the manifest.
   *
   * @param spec           the active spec the record belongs to
   * @param entity         the spec's root entity holding the record
   * @param recordId       the document's id
   * @param documentStatus the document's status after the change that queued it
   * @return a {@link Prepared} with a terminal outcome, or ready for {@link #renderPdf}; never throws
   */
  public Prepared prepare(SFSpec spec, SFEntity entity, String recordId, String documentStatus) {
    String specName = spec.getName();
    String document = NeoPdfSourceConfig.printDocumentOf(entity);
    if (document != null && !isConfigured()) {
      return Prepared.terminal(Outcome.NOT_CONFIGURED, specName, recordId);
    }
    NeoPrintableDataBuilder builder = document == null ? null : builderFor(document);
    if (builder == null) {
      if (document != null) {
        log.warn("[print] {} declares printable '{}' but no NeoPrintableDataBuilder fills it",
            specName, document);
      }
      return Prepared.terminal(Outcome.NOT_PRINTABLE, specName, recordId);
    }
    if (!builder.storesFor(documentStatus)) {
      return Prepared.terminal(Outcome.NOT_FOR_STATUS, specName, recordId);
    }
    Tab tab = entity.getADTab();
    Table table = tab == null ? null : tab.getTable();
    if (table == null || !mayStore(spec, table.getId())) {
      log.info("[print] {}/{}: not stored, the role cannot write its attachments", specName,
          recordId);
      return Prepared.terminal(Outcome.NOT_ALLOWED, specName, recordId);
    }
    try {
      JSONObject data = builder.build(recordId);
      String documentNo = builder.documentNo(data);
      if (isForeignMain(table.getId(), recordId, specName, documentNo)) {
        return Prepared.terminal(Outcome.FOREIGN_MAIN, specName, recordId);
      }
      return new Prepared(null, document, data, documentNo,
          new String[] { table.getDBTableName(), table.getId(), tab.getId() }, specName, recordId);
    } catch (Exception e) {
      log.warn(MSG_RENDER_FAILED, specName, recordId,
          failureOf(e));
      return Prepared.terminal(Outcome.FAILED, specName, recordId);
    }
  }

  /**
   * Phase 2: the printable's manifest and jsreport, over HTTP. Touches no database, so the caller
   * can (and must) have no transaction open meanwhile.
   *
   * @param prepared what {@link #prepare} returned, ready to render
   * @param locale   the language to print in (an Etendo language code, e.g. {@code es_ES})
   * @return the PDF, or an empty array when it could not be rendered; never throws
   */
  public byte[] renderPdf(Prepared prepared, String locale) {
    try {
      NeoPrintableTemplates.Template template = templates.get(prepared.document);
      byte[] pdf = jsreport.render(template.payload(prepared.data, locale));
      log.debug("[print] {}/{}: rendered from the printable built at {}", prepared.specName,
          prepared.recordId, template.getBuiltAt());
      return pdf;
    } catch (Exception e) {
      // Only the class and message: the message of a render failure names no record data, the
      // record's own content never reaches the log.
      log.warn(MSG_RENDER_FAILED, prepared.specName,
          prepared.recordId, failureOf(e));
      return new byte[0];
    }
  }

  /**
   * Phase 3: stores the rendered PDF as the main attachment, after checking again that the main
   * attachment is not a person's file (it may have changed during the render).
   *
   * @param prepared what {@link #prepare} returned, ready to render
   * @param pdf      the rendered PDF
   * @return what happened; never throws
   */
  public Outcome store(Prepared prepared, byte[] pdf) {
    try {
      if (isForeignMain(prepared.tableId(), prepared.recordId, prepared.specName,
          prepared.documentNo)) {
        return Outcome.FOREIGN_MAIN;
      }
      String fileName = previewFileName(prepared.specName, prepared.documentNo, prepared.recordId);
      NeoResponse stored = NeoAttachmentStore.store(new NeoAttachmentUpload(prepared.tableName(),
          prepared.recordId, prepared.tabId(), fileName, pdf.length, name -> write(pdf, name), true));
      if (stored.getHttpStatus() >= 300) {
        log.warn("[print] {}/{}: rendered but not stored (HTTP {})", prepared.specName,
            prepared.recordId, stored.getHttpStatus());
        return Outcome.FAILED;
      }
      log.info("[print] {}/{}: server-rendered PDF stored as the main attachment ({} bytes)",
          prepared.specName, prepared.recordId, pdf.length);
      return Outcome.STORED;
    } catch (Exception e) {
      log.warn(MSG_RENDER_FAILED, prepared.specName,
          prepared.recordId, failureOf(e));
      return Outcome.FAILED;
    }
  }

  /**
   * Never replace a file a person marked main (e.g. a customer's own document): storing with
   * markAsMain would delete it. Only no main file, or one Etendo generated, may be replaced.
   */
  private boolean isForeignMain(String tableId, String recordId, String specName,
      String documentNo) {
    String currentMain = mainFileName.apply(tableId, recordId);
    if (currentMain != null && !isEtendoPreviewName(currentMain, specName, documentNo, recordId)) {
      log.info("[print] {}/{}: not stored, the main attachment is not an Etendo-generated PDF",
          specName, recordId);
      return true;
    }
    return false;
  }

  /**
   * What a failure is, for the log: the exception class, plus the HTTP status of a jsreport answer —
   * never a message, which may carry record data or a page excerpt.
   */
  static String failureOf(Exception e) {
    if (e instanceof NeoJsreportClient.RenderException
        && ((NeoJsreportClient.RenderException) e).getHttpStatus() > 0) {
      return e.getClass().getSimpleName() + " (HTTP "
          + ((NeoJsreportClient.RenderException) e).getHttpStatus() + ")";
    }
    return e.getClass().getSimpleName();
  }

  /**
   * Whether this environment can render at all: a jsreport URL and a printables location resolve
   * (explicitly or from the public app URL). Without them every render is a no-op.
   *
   * @return {@code true} when both are configured
   */
  public static boolean isConfigured() {
    return NeoJsreportClient.baseUrl() != null && NeoPrintableTemplates.baseUrl() != null;
  }

  /** The builder whose declared key is the entity's, or {@code null}. */
  NeoPrintableDataBuilder builderFor(String document) {
    for (NeoPrintableDataBuilder candidate : builders.get()) {
      if (document.equals(candidate.document())) {
        return candidate;
      }
    }
    return null;
  }

  /**
   * The write tier the attachments endpoint asks for (D23): the role may write the spec's window and
   * the document's table. The read-only tier never writes the cache.
   */
  private static boolean mayStore(SFSpec spec, String tableId) {
    OBContext.setAdminMode(true);
    try {
      return NeoAccessHelper.hasWindowAccessForSpec(spec, METHOD_WRITE)
          && NeoAttachmentAuthorizer.checkWrite(tableId) == null;
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /** Writes the PDF into a directory of its own, so two renders of one name never collide. */
  private static File write(byte[] pdf, String fileName) throws IOException {
    Path directory = NeoAttachmentUploadEndpoint.createOwnerOnlyTempDirectory();
    Path file = directory.resolve(fileName);
    Files.write(file, pdf);
    return file.toFile();
  }

  /**
   * Whether a main attachment's name is one Etendo gives the PDFs it generates for this record: the
   * send path's {@code <spec>-<documentNo>.pdf} (or {@code <spec>-<id>.pdf} without a number), and
   * the preview cache's {@code <id>.pdf} ({@code GenericPreviewModal}). Anything else is a file a
   * person attached as main, which the server render never replaces.
   *
   * @param fileName   the current main attachment's name
   * @param specName   the record's spec
   * @param documentNo the record's document number, or {@code null}
   * @param recordId   the record's id
   * @return {@code true} when the server render may replace it
   */
  static boolean isEtendoPreviewName(String fileName, String specName, String documentNo,
      String recordId) {
    return fileName.equals(previewFileName(specName, documentNo, recordId))
        || fileName.equals(previewFileName(specName, null, recordId))
        || fileName.equals(recordId + ".pdf");
  }

  /** The name of the record's main attachment, or {@code null} when it has none. */
  private static String storedMainFileName(String tableId, String recordId) {
    OBContext.setAdminMode(true);
    try {
      org.openbravo.model.ad.utility.Attachment main =
          NeoAttachmentsHelper.findMainAttachment(tableId, recordId);
      return main == null ? null : StringUtils.defaultString(main.getName());
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * {@code buildPreviewFileName} of {@code documentEmailSend.js}: {@code <spec>-<documentNo>.pdf},
   * each part reduced to {@code [0-9A-Za-z._-]} with runs of anything else collapsed to one dash and
   * leading/trailing dashes trimmed. The same name the SPA's send stores, so a later send from the
   * application overwrites this file in place rather than adding a second one.
   */
  static String previewFileName(String specName, String documentNo, String recordId) {
    String safeSpec = StringUtils.defaultIfEmpty(sanitize(specName), "document");
    String safeDocument = StringUtils.defaultIfEmpty(
        sanitize(documentNo != null ? documentNo : recordId), recordId);
    return safeSpec + "-" + safeDocument + ".pdf";
  }

  private static String sanitize(String value) {
    String raw = StringUtils.defaultString(value);
    StringBuilder out = new StringBuilder();
    boolean lastWasDash = false;
    for (int i = 0; i < raw.length(); i++) {
      char c = raw.charAt(i);
      boolean safe = (c >= '0' && c <= '9') || (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
          || c == '.' || c == '_' || c == '-';
      if (safe) {
        out.append(c);
        lastWasDash = false;
      } else if (!lastWasDash) {
        out.append('-');
        lastWasDash = true;
      }
    }
    return StringUtils.strip(out.toString(), "-");
  }
}
