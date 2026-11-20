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

package com.etendoerp.go.mcp;

import java.util.Optional;

import org.openbravo.dal.core.OBContext;

import com.etendoerp.go.schemaforge.email.DocumentDownloadTokenService;

/**
 * The signed, short-lived link to one attachment of a record (ETP-5715): the document-download
 * token with the attachment id, served by {@code /sws/neo/document-download/}, on the app's public
 * base. Public since ETP-6247 so an action outside this package (a remittance's SEPA file) hands
 * out the very link {@code etendo_list_attachments} does.
 */
public final class McpAttachmentLinks {

  private McpAttachmentLinks() {
  }

  /**
   * The signed, short-lived download link of one attachment of a record, for the current client.
   *
   * @param specName     the NEO spec of the record
   * @param recordId     the record the attachment belongs to
   * @param attachmentId the {@code C_File_ID} the link serves
   * @return the absolute link, or {@code null} when the signing secret is not configured
   */
  public static String downloadUrl(String specName, String recordId, String attachmentId) {
    String clientId = (String) OBContext.getOBContext().getCurrentClient().getId();
    Optional<String> token = DocumentDownloadTokenService.createAttachmentToken(specName,
        recordId, clientId, attachmentId, McpAttachmentTools.DOWNLOAD_LINK_TTL.getSeconds());
    return token.map(value -> McpImageTools.publicUrl(McpImageTools.NEO_SERVLET_PATH
        + DocumentDownloadTokenService.DOWNLOAD_PATH + value)).orElse(null);
  }
}
