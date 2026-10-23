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

import java.io.File;

import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

/**
 * Reads a document through an {@link OcrServiceClient} and turns the tool's answer into the clean
 * payload the review works from (ETP-5715), or into "nothing was read".
 *
 * <p>"Nothing was read" covers, as in the application, every way a document can fail to yield data:
 * the tool reporting its own failure, an answer that cannot be parsed, a payload whose every field is
 * empty (a blank or unreadable PDF), and one that is empty once sanitized. A service that cannot be
 * used is not that: it surfaces as {@link OcrUnavailableException}, because the document may be fine.</p>
 */
public final class OcrExtractionService {

  private final OcrServiceClient client;

  /**
   * Creates the service.
   *
   * @param client the extraction service client
   */
  public OcrExtractionService(OcrServiceClient client) {
    this.client = client;
  }

  /**
   * Reads one document.
   *
   * @param pdf  the PDF
   * @param type the document type
   * @return the sanitized payload, {@code null} when nothing was read
   * @throws OcrUnavailableException when the extraction service cannot be used
   * @throws JSONException when the type's schema cannot be read
   */
  public JSONObject extract(File pdf, OcrDocType type) throws OcrUnavailableException,
      JSONException {
    JSONObject response = client.extract(pdf, type);
    if (OcrExtractionParser.isToolError(response)) {
      return null;
    }
    JSONObject payload = OcrExtractionParser.payloadOf(response);
    if (!OcrExtractionParser.hasData(type, payload)) {
      return null;
    }
    JSONObject sanitized = OcrSanitizer.sanitize(payload, type.getSchema());
    return OcrExtractionParser.hasData(type, sanitized) ? sanitized : null;
  }
}
