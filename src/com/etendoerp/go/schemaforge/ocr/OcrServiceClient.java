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

import org.codehaus.jettison.json.JSONObject;

/**
 * The service that reads a document (ETP-5715): given the PDF and what to ask, it answers with the
 * JSON the tool returned. An interface so the review can be tested without the paid external model
 * behind the real one ({@link CopilotOcrServiceClient}).
 */
public interface OcrServiceClient {

  /**
   * Sends the document to the extraction tool and returns its answer as it came.
   *
   * @param pdf  the PDF to read
   * @param type the document type: which tool, which question, which answer schema
   * @return the tool's JSON body ({@code answer}, or {@code error} when the tool itself failed)
   * @throws OcrUnavailableException when the service cannot be reached, does not answer in time or
   *         answers with a failure status
   */
  JSONObject extract(File pdf, OcrDocType type) throws OcrUnavailableException;
}
