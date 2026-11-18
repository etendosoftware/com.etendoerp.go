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

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.BinaryOperator;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.dal.core.OBContext;

import com.etendoerp.go.usageevents.UsageEvent;
import com.etendoerp.go.usageevents.UsageEventRecorder;
import com.etendoerp.go.usageevents.UsageEventTypes;

/**
 * ETP-5993 (OKR O4.KR2): the one shape of a {@code document.pdf.generate} usage event, the row the
 * monthly "documents generated per template" figure counts ({@link DocumentPdfMonthlyReport}).
 *
 * <p><b>What counts.</b> An effective PDF of a document: downloaded, printed or emailed from the app
 * (the browser renders it, so the app reports it through {@code POST /sws/neo/usage}), or rendered
 * and stored by the server for the MCP ({@link NeoPrintAfterComplete}). A preview never counts: the
 * channel is a closed list without one, and an event naming any other channel is dropped.</p>
 *
 * <p><b>What a row carries.</b> The tenant and organization (the who-columns, from the token, never
 * the body), the date, {@code target} = the document type (one of {@link #DOC_TYPES}),
 * {@code action} = the channel and {@code properties.variant} = the template (classic, compact,
 * modern). Nothing of the document: no id, number, amount, partner or file name. Whatever else a
 * client sends — properties, an error code, a duration — is not kept, so a careless caller cannot
 * leak document data into the table.</p>
 */
public final class DocumentPdfUsage {

  private static final Logger log = LogManager.getLogger(DocumentPdfUsage.class);

  /** The app's download button (the preview's, the list's or a menu item's). */
  public static final String CHANNEL_DOWNLOAD = "download";
  /** The app's print drawer, one document or several. */
  public static final String CHANNEL_PRINT = "print";
  /** The PDF the app attached to an email it sent. */
  public static final String CHANNEL_EMAIL = "email";
  /** A PDF the server rendered and stored as the document's main attachment (MCP only). */
  public static final String CHANNEL_SERVER_RENDER = "server_render";

  /** The channels a client may report. The server render is the backend's own. */
  static final Set<String> CLIENT_CHANNELS = Collections.unmodifiableSet(new LinkedHashSet<>(
      Arrays.asList(CHANNEL_DOWNLOAD, CHANNEL_PRINT, CHANNEL_EMAIL)));

  /** Every channel a row may name. */
  static final Set<String> CHANNELS = Collections.unmodifiableSet(new LinkedHashSet<>(
      Arrays.asList(CHANNEL_DOWNLOAD, CHANNEL_PRINT, CHANNEL_EMAIL, CHANNEL_SERVER_RENDER)));

  /** The template a row was printed with, in {@code properties}. */
  static final String PROP_VARIANT = "variant";

  /**
   * The document types: the configurable ones of {@link DocTemplateConfigSupport#DOC_TYPES} except
   * {@code listing}, the print of a window's list, which is not a document.
   */
  static final Set<String> DOC_TYPES = documentTypes();

  private static final String LISTING = "listing";

  private DocumentPdfUsage() {
  }

  /**
   * Rebuilds an event a client sent into the strict shape, or drops it.
   *
   * @param event         the event as the usage endpoint read it (who-columns from the token)
   * @param savedVariant  (orgId, docType) → the organization's saved variant, or {@code null} when
   *                      it saved none; asked only when the client named no valid variant
   * @return the event to record, or {@code null} to drop it: an unknown document type, a channel
   *         that is not a client one (a preview included), or a failed generation
   */
  static UsageEvent fromClient(UsageEvent event, BinaryOperator<String> savedVariant) {
    if (event == null) {
      return null;
    }
    String docType = StringUtils.trimToNull(event.target());
    String channel = StringUtils.trimToNull(event.action());
    if (docType == null || !DOC_TYPES.contains(docType) || channel == null
        || !CLIENT_CHANNELS.contains(channel)
        || (event.outcome() != null && !UsageEvent.OUTCOME_OK.equals(event.outcome()))) {
      return null;
    }
    String variant = variantOf(event.properties());
    if (variant == null) {
      variant = resolveVariant(event.orgId(), docType, savedVariant);
    }
    if (variant == null) {
      return null;
    }
    return UsageEvent.builder()
        .clientId(event.clientId())
        .orgId(event.orgId())
        .userId(event.userId())
        .roleId(event.roleId())
        .eventType(UsageEventTypes.DOCUMENT_PDF_GENERATE)
        .source(event.source())
        .sessionKey(event.sessionKey())
        .target(docType)
        .action(channel)
        .outcome(UsageEvent.OUTCOME_OK)
        .occurredAt(event.occurredAt())
        .appVersion(event.appVersion())
        .property(PROP_VARIANT, variant)
        .build();
  }

  /**
   * Records a PDF the server rendered and stored for the MCP. Call it after the store committed, on
   * the thread whose {@link OBContext} is the document's (the render worker's). Never throws.
   *
   * @param docType the printable the entity declares ({@code MCP_CONFIG.pdf.print.document})
   */
  static void recordServerRender(String docType) {
    try {
      UsageEvent event = serverRender(docType);
      if (event != null) {
        UsageEventRecorder.submit(event);
      }
    } catch (RuntimeException e) {
      log.debug("Could not record a server-rendered PDF: {}", e.getClass().getSimpleName());
    }
  }

  /**
   * The server render's event. The server prints the base design and applies no saved variant, so
   * the template is the default one, whatever the organization configured for the app.
   *
   * @return the event, or {@code null} when the document type is not one of {@link #DOC_TYPES}
   */
  static UsageEvent serverRender(String docType) {
    if (docType == null || !DOC_TYPES.contains(docType)) {
      return null;
    }
    return UsageEvent.builder()
        .fromContext()
        .eventType(UsageEventTypes.DOCUMENT_PDF_GENERATE)
        .source(UsageEvent.SOURCE_MCP)
        .target(docType)
        .action(CHANNEL_SERVER_RENDER)
        .outcome(UsageEvent.OUTCOME_OK)
        .property(PROP_VARIANT, DocTemplateConfigSupport.DEFAULT_VARIANT)
        .build();
  }

  /**
   * The organization's saved variant for the type, read the way the app reads it, or {@code null}
   * when it saved none. For {@link #fromClient}, on the request thread.
   */
  static String storedVariant(String orgId, String docType) {
    OBContext.setAdminMode(true);
    try {
      DocTemplateConfig saved = new DocTemplateConfigStore().find(orgId, docType);
      return saved == null ? null : saved.getVariant();
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /** The variant the client named, when it is one of the three; otherwise {@code null}. */
  static String variantOf(String properties) {
    if (StringUtils.isBlank(properties)) {
      return null;
    }
    try {
      Object value = new JSONObject(properties).opt(PROP_VARIANT);
      return value instanceof String && DocTemplateConfigSupport.VARIANTS.contains(value)
          ? (String) value : null;
    } catch (JSONException e) {
      return null;
    }
  }

  /**
   * What the app prints when it was not told: the saved variant, or the default when none is saved.
   * A failed read is {@code null} (the event is dropped): unknown beats a wrong template.
   */
  private static String resolveVariant(String orgId, String docType,
      BinaryOperator<String> savedVariant) {
    try {
      String saved = savedVariant.apply(orgId, docType);
      if (saved == null) {
        return DocTemplateConfigSupport.DEFAULT_VARIANT;
      }
      return DocTemplateConfigSupport.VARIANTS.contains(saved) ? saved : null;
    } catch (RuntimeException e) {
      log.debug("Template of a generated PDF unknown, event dropped: {}",
          e.getClass().getSimpleName());
      return null;
    }
  }

  private static Set<String> documentTypes() {
    Set<String> types = new LinkedHashSet<>(DocTemplateConfigSupport.DOC_TYPES);
    types.remove(LISTING);
    return Collections.unmodifiableSet(types);
  }
}
