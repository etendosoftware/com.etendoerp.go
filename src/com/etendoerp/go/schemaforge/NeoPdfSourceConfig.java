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
 * The {@code pdf} section of {@code MCP_CONFIG} (ETP-5715): where the PDF of a document comes from.
 *
 * <h2>Shape</h2>
 * <pre>
 * { "pdf": { "source": "supplier", "reason": "no printable: the PDF is the document the user uploads" } }
 * </pre>
 * <ul>
 *   <li>{@code rendered} (the default, also when there is no section): the application renders the PDF
 *       in the browser when the document's preview is opened and stores it as the record's main
 *       attachment. {@code etendo_get_document_pdf} returns that file under the SPA's own freshness
 *       rule (older than the record or the company branding: stale).</li>
 *   <li>{@code print} (ETP-6013, optional, only with {@code rendered}): {@code {"document": "<key>"}}
 *       names the printable the backend renders by itself when the document has no current PDF —
 *       the key of the manifest the frontend build publishes ({@code printables/<key>.json}, see
 *       {@link NeoPrintableTemplates}) and of the {@link NeoPrintableDataBuilder} that fills it.
 *       Without it the entity keeps the browser-only behaviour: the PDF exists only once someone
 *       opens the preview or sends the document.</li>
 *   <li>{@code supplier}: the document has no printable. Its "PDF" is the file the user uploaded as
 *       the main attachment (a purchase invoice's supplier document, a goods receipt's delivery
 *       note). It is returned as is and is never stale, because no edit of ours can outdate a
 *       counterparty's document.</li>
 * </ul>
 * <p>Written on the ROOT entity (tab level 0); a spec-level body applies to the whole spec unless the
 * entity declares its own ({@code REPLACE}, as every section). The {@code reason} is for whoever reads
 * the data and never reaches an agent.</p>
 *
 * <h2>Why it is data, and the rule that goes with it</h2>
 * <p>The application decides this per window in its preview component (no {@code recordUpdated}, no
 * rendered blob: the window shows an uploaded document), a frontend fact the backend never sees, and
 * the main-attachment flag cannot tell the two kinds of file apart. So it is declared here. <b>A window
 * whose preview shows an uploaded document instead of a rendered one MUST declare
 * {@code pdf.source:"supplier"}</b>; {@code McpConfigSourcedataTest} lists the entities that do.
 * Reading is fail-safe in the opposite direction to attachments: an unreadable section reads as
 * {@code rendered}, the stricter rule, so a stale PDF is never handed out as current.</p>
 */
public final class NeoPdfSourceConfig {

  /** The section name inside {@code MCP_CONFIG}. */
  public static final String NAME = "pdf";
  /** Key: where the PDF comes from, {@value #SOURCE_SUPPLIER} or {@value #SOURCE_RENDERED}. */
  public static final String KEY_SOURCE = "source";
  /** Key: why, mandatory so every declaration is auditable on the row that makes it. */
  public static final String KEY_REASON = "reason";
  /** Value: the PDF is the user's uploaded main attachment. */
  public static final String SOURCE_SUPPLIER = "supplier";
  /** Value: the PDF is the one the application rendered and stored (the default). */
  public static final String SOURCE_RENDERED = "rendered";
  /** Key (ETP-6013): the printable the backend renders, {@code {"document": "<key>"}}. */
  public static final String KEY_PRINT = "print";
  /** Key inside {@value #KEY_PRINT}: the published printable's key. */
  public static final String KEY_PRINT_DOCUMENT = "document";
  /** Every key the section body may carry. */
  public static final Set<String> ALLOWED_KEYS = Set.of(KEY_SOURCE, KEY_REASON, KEY_PRINT);

  private static final Logger log = LogManager.getLogger(NeoPdfSourceConfig.class);

  /** Where the PDF of a document comes from. */
  public enum Source {
    /** The PDF the application rendered and stored as the record's main attachment. */
    RENDERED,
    /** The document the user uploaded as the main attachment; never stale. */
    SUPPLIER
  }

  private NeoPdfSourceConfig() {
  }

  /**
   * Validates a section body on its own terms.
   *
   * @param body the section body
   * @return the problems found, empty when the body is usable
   */
  public static List<String> validate(JSONObject body) {
    List<String> problems = new ArrayList<>();
    Object source = body.opt(KEY_SOURCE);
    if (!SOURCE_SUPPLIER.equals(source) && !SOURCE_RENDERED.equals(source)) {
      problems.add(KEY_SOURCE + " is required and must be the string \"" + SOURCE_SUPPLIER
          + "\" or \"" + SOURCE_RENDERED + "\"");
    }
    if (StringUtils.isBlank(body.optString(KEY_REASON, null))) {
      problems.add(KEY_REASON + " is required, so every declaration is auditable");
    }
    if (body.has(KEY_PRINT)) {
      problems.addAll(validatePrint(body.opt(KEY_PRINT), source));
    }
    return problems;
  }

  /** {@code print} must be {@code {"document": "<non-blank>"}} and only names a rendered PDF. */
  private static List<String> validatePrint(Object print, Object source) {
    List<String> problems = new ArrayList<>();
    if (!(print instanceof JSONObject)
        || StringUtils.isBlank(((JSONObject) print).optString(KEY_PRINT_DOCUMENT, null))) {
      problems.add(KEY_PRINT + " must be an object with a non-blank \"" + KEY_PRINT_DOCUMENT
          + "\" string");
    } else if (((JSONObject) print).length() != 1) {
      problems.add(KEY_PRINT + " carries only \"" + KEY_PRINT_DOCUMENT + "\"");
    }
    if (SOURCE_SUPPLIER.equals(source)) {
      problems.add(KEY_PRINT + " cannot be declared with source \"" + SOURCE_SUPPLIER
          + "\": a supplier document is never rendered");
    }
    return problems;
  }

  /**
   * Where the PDF of this entity's document comes from.
   *
   * @param entity the document's root entity
   * @return {@link Source#SUPPLIER} only for an explicit {@code source:"supplier"}; otherwise
   *         {@link Source#RENDERED}
   */
  public static Source sourceOf(SFEntity entity) {
    if (entity == null) {
      return Source.RENDERED;
    }
    try {
      JSONObject section = sectionOf(entity.getMCPConfig());
      if (section == null) {
        SFSpec spec = entity.getETGOSFSpec();
        section = spec == null ? null : sectionOf(spec.getMCPConfig());
      }
      return section != null && SOURCE_SUPPLIER.equals(section.opt(KEY_SOURCE)) ? Source.SUPPLIER
          : Source.RENDERED;
    } catch (JSONException | RuntimeException e) {
      log.warn("PDF source of entity '{}' read as rendered: its MCP_CONFIG cannot be read ({})",
          entity.getName(), e.getClass().getSimpleName());
      return Source.RENDERED;
    }
  }

  /**
   * The printable the backend renders for this entity's document (ETP-6013), or {@code null} when
   * the entity declares none — or its PDF is the supplier's, which is never rendered.
   *
   * @param entity the document's root entity
   * @return the published printable's key (e.g. the manifest {@code printables/<key>.json}), or null
   */
  public static String printDocumentOf(SFEntity entity) {
    if (entity == null) {
      return null;
    }
    try {
      JSONObject section = sectionOf(entity.getMCPConfig());
      if (section == null) {
        SFSpec spec = entity.getETGOSFSpec();
        section = spec == null ? null : sectionOf(spec.getMCPConfig());
      }
      if (section == null || SOURCE_SUPPLIER.equals(section.opt(KEY_SOURCE))) {
        return null;
      }
      JSONObject print = section.optJSONObject(KEY_PRINT);
      return print == null ? null : StringUtils.trimToNull(print.optString(KEY_PRINT_DOCUMENT, null));
    } catch (JSONException | RuntimeException e) {
      // Not printable beats a wrong render: the entity keeps the browser-only behaviour.
      log.warn("Printable of entity '{}' not read: its MCP_CONFIG cannot be read ({})",
          entity.getName(), e.getClass().getSimpleName());
      return null;
    }
  }

  /** The {@code pdf} body of a raw {@code MCP_CONFIG}, or {@code null} when it has none. */
  private static JSONObject sectionOf(String rawConfig) throws JSONException {
    if (StringUtils.isBlank(rawConfig)) {
      return null;
    }
    JSONObject config = new JSONObject(rawConfig);
    return config.has(NAME) ? config.getJSONObject(NAME) : null;
  }
}
