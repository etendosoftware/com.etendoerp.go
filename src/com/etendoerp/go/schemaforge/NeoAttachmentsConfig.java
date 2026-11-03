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

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.data.SFSpec;

/**
 * The {@code attachments} section of {@code MCP_CONFIG} (ETP-5715, ETP-6013): how much of the
 * attachments surface the MCP offers on a document type.
 *
 * <h2>Shape</h2>
 * <pre>
 * { "attachments": { "enabled": false, "reason": "the app shows no Attachments tab here" } }
 * { "attachments": { "readOnly": true, "reason": "why", "messageKey": "ETGO_SomeMessage" } }
 * </pre>
 * <ul>
 *   <li><b>{@code enabled:false}</b> — no attachments at all: list, download, upload and delete are
 *       refused (ETP-5715). The answer is the constant {@link #MSG_UNAVAILABLE}, or the declared
 *       {@code messageKey}.</li>
 *   <li><b>{@code readOnly:true}</b> (ETP-6013) — the attachments can be listed and downloaded, and
 *       every write is refused: upload (link request, its PUT, a staged document), delete, and any
 *       other operation that authorizes as a write through {@link NeoAttachmentTarget} (the MCP email
 *       send does, because the application's send stores the PDF as an attachment first). The answer
 *       is the declared {@code messageKey}, or {@value #MSG_READ_ONLY_DEFAULT}.</li>
 *   <li><b>{@code reason}</b> — mandatory, for whoever reads the data; never part of the answer.</li>
 *   <li><b>{@code messageKey}</b> — optional: the {@code AD_MESSAGE} value of the refusal, resolved in
 *       the request language by {@link NeoMessageText} (the Spanish text is versioned in
 *       {@code messages/neo_hints_es.properties}). A key, never prose: the data names what to say,
 *       the catalogs say it in each language.</li>
 * </ul>
 *
 * <p>Written on the ROOT entity (tab level 0) of the spec — the record the attachments hang on.
 * {@code enabled:true} or no section leaves attachments fully available. A spec-level body applies to
 * the whole spec unless the entity declares its own ({@code REPLACE}, as every section). The refusal
 * is decided before any record is looked up, so it is a clear answer and never a 404.</p>
 *
 * <h2>Why it is data, and why it can drift</h2>
 * <p>The application decides the tab per window in {@code schema_forge/artifacts/<window>/decisions.json}
 * ({@code window.attachments:false}); the backend never sees that file. This section is the backend's
 * copy of the decision, kept in sourcedata. A window that sets {@code attachments:false} must also set
 * this section — {@code McpConfigSourcedataTest} lists the windows that do, as a drift guard — until the
 * pusher derives it from {@code decisions.json}. {@code readOnly} has no application counterpart: it is
 * an MCP-only product decision, and {@code McpConfigSourcedataTest} lists the specs that declare it.</p>
 *
 * <p>It lives in this package, not in {@code mcp}, because the PUT of the upload link
 * ({@link NeoAttachmentUploadEndpoint}) must re-check it and the schemaforge layer does not depend on
 * the mcp layer; the mcp layer registers the section for validation
 * ({@code McpAttachmentsSection}). Reading is <b>fail-closed</b>: a configuration that cannot be
 * parsed, or a section that is neither {@code enabled:true} nor a well-formed {@code readOnly:true},
 * refuses attachments rather than opening them.</p>
 */
public final class NeoAttachmentsConfig {

  /** The section name inside {@code MCP_CONFIG}. */
  public static final String NAME = "attachments";
  /** Key: {@code false} hides attachments for the document type. */
  public static final String KEY_ENABLED = "enabled";
  /** Key: {@code true} keeps list and download and refuses every write (ETP-6013). */
  public static final String KEY_READ_ONLY = "readOnly";
  /** Key: why, mandatory so every opt-out is auditable on the row that makes it. */
  public static final String KEY_REASON = "reason";
  /** Key: optional {@code AD_MESSAGE} value of the refusal, translated per request (ETP-6013). */
  public static final String KEY_MESSAGE_KEY = "messageKey";
  /** Every key the section body may carry. */
  public static final Set<String> ALLOWED_KEYS =
      Set.of(KEY_ENABLED, KEY_READ_ONLY, KEY_REASON, KEY_MESSAGE_KEY);

  /** The {@code AD_MESSAGE} a read-only refusal answers when the section declares none. */
  static final String MSG_READ_ONLY_DEFAULT = "ETGO_AttachmentsReadOnly";

  /** An {@code AD_MESSAGE} value: an identifier, never a sentence. */
  private static final Pattern MESSAGE_KEY_SHAPE = Pattern.compile("[A-Za-z]\\w*");

  /** The one sentence an agent gets: no configuration detail, no internal file names. */
  static final String MSG_UNAVAILABLE =
      "Attachments are not available for this document type, as in the application.";

  private static final Logger log = LogManager.getLogger(NeoAttachmentsConfig.class);

  private NeoAttachmentsConfig() {
  }

  /** What a document type allows, once its section is read. */
  private enum Mode {
    OPEN, READ_ONLY, CLOSED
  }

  /**
   * Why an attachment operation is refused on a document type.
   *
   * <p>{@link #isReadOnly()} tells a read-only type (reads allowed, this write refused) apart from a
   * type with no attachments at all, so the caller can word its advice.</p>
   */
  public static final class Refusal {
    private final String message;
    private final boolean readOnly;

    private Refusal(String message, boolean readOnly) {
      this.message = message;
      this.readOnly = readOnly;
    }

    /** Returns the text the caller is told, in the request language when declared by key. */
    public String getMessage() {
      return message;
    }

    /** Returns whether the type keeps its attachments readable and only the write is refused. */
    public boolean isReadOnly() {
      return readOnly;
    }
  }

  /**
   * Validates a section body on its own terms.
   *
   * @param body the section body
   * @return the problems found, empty when the body is usable
   */
  public static List<String> validate(JSONObject body) {
    List<String> problems = new ArrayList<>();
    Object enabled = body.opt(KEY_ENABLED);
    Object readOnly = body.opt(KEY_READ_ONLY);
    if (enabled != null && !(enabled instanceof Boolean)) {
      problems.add(KEY_ENABLED + " must be a JSON boolean (false hides attachments)");
    }
    if (readOnly != null && !(readOnly instanceof Boolean)) {
      problems.add(KEY_READ_ONLY + " must be a JSON boolean (true keeps list and download only)");
    }
    if (enabled == null && !Boolean.TRUE.equals(readOnly)) {
      problems.add(KEY_ENABLED + " (a JSON boolean) or " + KEY_READ_ONLY
          + ":true is required: the section must declare a mode");
    }
    if (Boolean.FALSE.equals(enabled) && Boolean.TRUE.equals(readOnly)) {
      problems.add(KEY_READ_ONLY + ":true contradicts " + KEY_ENABLED
          + ":false (which already refuses everything): declare one mode");
    }
    if (StringUtils.isBlank(body.optString(KEY_REASON, null))) {
      problems.add(KEY_REASON + " is required, so every opt-out is auditable");
    }
    if (body.has(KEY_MESSAGE_KEY) && !isMessageKey(body.opt(KEY_MESSAGE_KEY))) {
      problems.add(KEY_MESSAGE_KEY + " must be an AD_MESSAGE value (letters, digits, _), not prose");
    }
    return problems;
  }

  /**
   * Why the MCP refuses this attachment operation on this entity, if it does.
   *
   * @param entity the entity whose record would carry the attachments
   * @param write  {@code true} for an operation that changes the attachments (upload, delete, and
   *               anything that authorizes as a write), {@code false} for list and download
   * @return the refusal, or {@code null} when the operation is available
   */
  public static Refusal refusalFor(SFEntity entity, boolean write) {
    if (entity == null) {
      return null;
    }
    JSONObject section;
    try {
      section = sectionOf(entity.getMCPConfig());
      if (section == null) {
        SFSpec spec = entity.getETGOSFSpec();
        section = spec == null ? null : sectionOf(spec.getMCPConfig());
      }
    } catch (JSONException | RuntimeException e) {
      log.warn("Attachments refused on entity '{}': its MCP_CONFIG cannot be read ({})",
          entity.getName(), e.getClass().getSimpleName());
      return new Refusal(MSG_UNAVAILABLE, false);
    }
    Mode mode = modeOf(section);
    if (mode == Mode.OPEN || (mode == Mode.READ_ONLY && !write)) {
      return null;
    }
    String declaredKey = declaredMessageKey(section);
    if (mode == Mode.READ_ONLY) {
      return new Refusal(NeoMessageText.text(
          declaredKey != null ? declaredKey : MSG_READ_ONLY_DEFAULT), true);
    }
    return new Refusal(declaredKey != null ? NeoMessageText.text(declaredKey) : MSG_UNAVAILABLE,
        false);
  }

  /**
   * The mode a section declares. Only {@code enabled:true} opens everything, and only a boolean
   * {@code readOnly:true} that no {@code enabled} other than {@code true} contradicts opens the
   * reads: anything else — {@code enabled:false}, a malformed value, a section with no mode — closes.
   */
  private static Mode modeOf(JSONObject section) {
    if (section == null) {
      return Mode.OPEN;
    }
    boolean hasEnabled = section.has(KEY_ENABLED);
    Object enabled = section.opt(KEY_ENABLED);
    boolean readOnly = Boolean.TRUE.equals(section.opt(KEY_READ_ONLY));
    if (readOnly && (!hasEnabled || Boolean.TRUE.equals(enabled))) {
      return Mode.READ_ONLY;
    }
    return Boolean.TRUE.equals(enabled) ? Mode.OPEN : Mode.CLOSED;
  }

  /** The declared message key, or {@code null} when absent or not shaped as a key. */
  private static String declaredMessageKey(JSONObject section) {
    Object key = section == null ? null : section.opt(KEY_MESSAGE_KEY);
    return isMessageKey(key) ? (String) key : null;
  }

  private static boolean isMessageKey(Object value) {
    return value instanceof String && MESSAGE_KEY_SHAPE.matcher((String) value).matches();
  }

  /** The {@code attachments} body of a raw {@code MCP_CONFIG}, or {@code null} when it has none. */
  private static JSONObject sectionOf(String rawConfig) throws JSONException {
    if (StringUtils.isBlank(rawConfig)) {
      return null;
    }
    JSONObject config = new JSONObject(rawConfig);
    return config.has(NAME) ? config.getJSONObject(NAME) : null;
  }
}
