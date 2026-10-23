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

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.data.SFSpec;

/**
 * The {@code attachments} section of {@code MCP_CONFIG} (ETP-5715): declares that a window has NO
 * Attachments tab in the application, so the MCP offers no attachments on it either.
 *
 * <h2>Shape</h2>
 * <pre>
 * { "attachments": { "enabled": false, "reason": "the app shows no Attachments tab here" } }
 * </pre>
 * <p>The {@code reason} is for whoever reads the data; it is never part of the answer, which is the
 * constant {@link #MSG_UNAVAILABLE}.</p>
 *
 * <p>Written on the ROOT entity (tab level 0) of the spec — the record the tab would hang on. Only
 * the opt-out is meaningful: {@code enabled:true} or no section leaves attachments available. A
 * spec-level body applies to the whole spec unless the entity declares its own ({@code REPLACE}, as
 * every section).</p>
 *
 * <h2>Why it is data, and why it can drift</h2>
 * <p>The application decides the tab per window in {@code schema_forge/artifacts/<window>/decisions.json}
 * ({@code window.attachments:false}); the backend never sees that file. This section is the backend's
 * copy of the decision, kept in sourcedata. A window that sets {@code attachments:false} must also set
 * this section — {@code McpConfigSourcedataTest} lists the windows that do, as a drift guard — until the
 * pusher derives it from {@code decisions.json}.</p>
 *
 * <p>It lives in this package, not in {@code mcp}, because the PUT of the upload link
 * ({@link NeoAttachmentUploadEndpoint}) must re-check it and the schemaforge layer does not depend on
 * the mcp layer; the mcp layer registers the section for validation
 * ({@code McpAttachmentsSection}). Reading is <b>fail-closed</b>: a configuration that cannot be
 * parsed refuses attachments rather than opening them.</p>
 */
public final class NeoAttachmentsConfig {

  /** The section name inside {@code MCP_CONFIG}. */
  public static final String NAME = "attachments";
  /** Key: {@code false} hides attachments for the document type. */
  public static final String KEY_ENABLED = "enabled";
  /** Key: why, mandatory so every opt-out is auditable on the row that makes it. */
  public static final String KEY_REASON = "reason";
  /** Every key the section body may carry. */
  public static final Set<String> ALLOWED_KEYS = Set.of(KEY_ENABLED, KEY_REASON);

  /** The one sentence an agent gets: no configuration detail, no internal file names. */
  static final String MSG_UNAVAILABLE =
      "Attachments are not available for this document type, as in the application.";

  private static final Logger log = LogManager.getLogger(NeoAttachmentsConfig.class);

  private NeoAttachmentsConfig() {
  }

  /**
   * Validates a section body on its own terms.
   *
   * @param body the section body
   * @return the problems found, empty when the body is usable
   */
  public static List<String> validate(JSONObject body) {
    List<String> problems = new ArrayList<>();
    if (!(body.opt(KEY_ENABLED) instanceof Boolean)) {
      problems.add(KEY_ENABLED + " is required and must be a JSON boolean (false hides attachments)");
    }
    if (StringUtils.isBlank(body.optString(KEY_REASON, null))) {
      problems.add(KEY_REASON + " is required, so every opt-out is auditable");
    }
    return problems;
  }

  /**
   * Why the MCP offers no attachments on this entity, if it does not.
   *
   * @param entity the entity whose record would carry the attachments
   * @return the refusal text, or {@code null} when attachments are available
   */
  public static String refusalFor(SFEntity entity) {
    if (entity == null) {
      return null;
    }
    try {
      JSONObject section = sectionOf(entity.getMCPConfig());
      if (section == null) {
        SFSpec spec = entity.getETGOSFSpec();
        section = spec == null ? null : sectionOf(spec.getMCPConfig());
      }
      // Fail closed: only a section-less entity or an explicit boolean true opens attachments.
      // "false" as a string, a number, null or a missing key all refuse.
      if (section == null || Boolean.TRUE.equals(section.opt(KEY_ENABLED))) {
        return null;
      }
      return MSG_UNAVAILABLE;
    } catch (JSONException | RuntimeException e) {
      log.warn("Attachments refused on entity '{}': its MCP_CONFIG cannot be read ({})",
          entity.getName(), e.getClass().getSimpleName());
      return MSG_UNAVAILABLE;
    }
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
