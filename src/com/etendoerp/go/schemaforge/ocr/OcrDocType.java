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

package com.etendoerp.go.schemaforge.ocr;

import java.util.Collections;
import java.util.List;

import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

/**
 * One document type the OCR extraction supports (ETP-5715): the question the extraction tool is
 * asked, the strict JSON schema its answer must follow, and the keys of that answer whose presence
 * means "something was read".
 *
 * <p>Immutable. It is built from {@code ocr-config.json} by {@link OcrConfig}, the one source the
 * application's upload panel (through {@code GET /sws/neo/ocrconfig}) and the MCP tools share, so
 * what the model is asked cannot drift between the two.</p>
 */
public final class OcrDocType {

  /** Key of the schema property that lists the document's lines. */
  public static final String LINES_KEY = "line_items";

  private final String spec;
  private final String toolName;
  private final String resolver;
  private final String question;
  private final String schemaText;
  private final List<String> dataKeys;

  /**
   * Describes one document type.
   *
   * @param spec     the window spec the document is created in; also the id of the type
   * @param toolName the copilot tool that reads the document
   * @param resolver the name that binds the type to its lookups (vendor, taxes, products...)
   * @param question the question sent with the document
   * @param schema   the strict JSON schema of the answer
   * @param dataKeys the answer keys whose being filled means the document was readable
   */
  public OcrDocType(String spec, String toolName, String resolver, String question,
      JSONObject schema, List<String> dataKeys) {
    this.spec = spec;
    this.toolName = toolName;
    this.resolver = resolver;
    this.question = question;
    this.schemaText = schema.toString();
    this.dataKeys = Collections.unmodifiableList(List.copyOf(dataKeys));
  }

  /** Returns the spec name. */
  public String getSpec() {
    return spec;
  }

  /** Returns the copilot tool name. */
  public String getToolName() {
    return toolName;
  }

  /** Returns the name of the lookups that serve this type. */
  public String getResolver() {
    return resolver;
  }

  /** Returns the question sent with the document. */
  public String getQuestion() {
    return question;
  }

  /** Returns the answer keys that, when filled, mean something was read. */
  public List<String> getDataKeys() {
    return dataKeys;
  }

  /**
   * Returns a copy of the answer schema.
   *
   * @return the schema, never shared with another caller
   * @throws JSONException when the stored schema text cannot be read back
   */
  public JSONObject getSchema() throws JSONException {
    return new JSONObject(schemaText);
  }

  /**
   * The configuration the application's upload panel needs to ask the same question.
   *
   * @return {@code {docType, toolName, question, schema}}
   * @throws JSONException when the JSON cannot be built
   */
  public JSONObject toClientJson() throws JSONException {
    JSONObject out = new JSONObject();
    out.put("docType", spec);
    out.put("toolName", toolName);
    out.put("question", question);
    out.put("schema", getSchema());
    return out;
  }
}
