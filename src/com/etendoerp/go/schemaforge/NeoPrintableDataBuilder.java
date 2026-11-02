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

import org.codehaus.jettison.json.JSONObject;

/**
 * Builds the data of one printable — the Java port of that document's browser data builder
 * (ETP-6013).
 *
 * <p>The template comes from the published manifest ({@link NeoPrintableTemplates}); only the data is
 * built here, and it must have <b>the shape the browser builds</b> ({@code buildInvoiceData} for
 * {@code sales-invoice}, ...), key for key, because the same Handlebars template consumes it.</p>
 *
 * <p><b>Binding.</b> An entity opts in with {@code MCP_CONFIG.pdf.print.document = "<key>"}
 * ({@link NeoPdfSourceConfig#printDocumentOf}); the builder whose {@link #document()} returns that
 * key fills it. Shared code compares the declared key with the one each builder declares and names
 * no document itself: the document's identity lives in its own builder class, exactly as an entity's
 * behaviour lives in its own customization. Implementations are plain CDI beans (the module's
 * discovery mode is {@code all}); keep them {@code @Dependent}.</p>
 *
 * <p><b>Dual implementation.</b> Each implementation duplicates a JavaScript builder by hand. Its
 * javadoc must name the JS function it ports, and a change to either side must be mirrored in the
 * other — see each class's drift note.</p>
 */
public interface NeoPrintableDataBuilder {

  /**
   * The printable this builder fills: the key of the published manifest and of
   * {@code MCP_CONFIG.pdf.print.document}.
   *
   * @return the key, e.g. the window's spec name
   */
  String document();

  /**
   * The document's data, in the shape the browser's builder returns (labels excluded: they come
   * from the manifest).
   *
   * @param recordId the document's id, already authorized for the current user
   * @return the data
   * @throws Exception when the document cannot be read; the render is then skipped
   */
  JSONObject build(String recordId) throws Exception;

  /**
   * Whether a document in this status gets a stored PDF. The default is the completed status: the
   * PDF is stored when the document is completed (and again on a re-complete). A document whose
   * "printable" statuses differ (a quotation's under evaluation, ...) overrides it, mirroring the
   * {@code storeCondition} its preview uses. A draft never has one.
   *
   * @param documentStatus the document's status after the change that queued it
   * @return {@code true} to render and store
   */
  default boolean storesFor(String documentStatus) {
    return "CO".equals(documentStatus);
  }

  /**
   * The document number, for the stored file's name ({@code <spec>-<documentNo>.pdf}, as the SPA's
   * send path names it).
   *
   * @param data what {@link #build} returned
   * @return the document number, or {@code null}
   */
  default String documentNo(JSONObject data) {
    return data.optString("documentNo", null);
  }
}
