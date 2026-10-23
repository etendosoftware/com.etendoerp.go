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

/**
 * One attachment upload with its bytes still to be materialized: everything
 * {@link NeoAttachmentStore#store} needs to run the SPA's upload, whoever the caller is
 * (the multipart REST endpoint or the MCP upload link, ETP-5715).
 *
 * <p>The bytes are not held here: {@link #materialize} is called only after the cheap checks
 * (declared size, extension, table, tab, record) pass, which is what keeps a rejected upload from
 * writing a single byte to disk.</p>
 */
public final class NeoAttachmentUpload {

  /** Writes the uploaded bytes to a temporary file carrying the given name. */
  @FunctionalInterface
  public interface FileMaterializer {
    /**
     * Writes the bytes to a temporary file.
     *
     * @param fileName the sanitized file name the attachment will carry
     * @return the file holding the bytes
     * @throws IOException when the bytes cannot be written
     */
    File materialize(String fileName) throws IOException;
  }

  private final String tableName;
  private final String recordId;
  private final String tabIdHint;
  private final String fileName;
  private final long declaredSize;
  private final FileMaterializer materializer;
  private final boolean markAsMain;

  /**
   * Describes one upload.
   *
   * @param tableName    the AD physical table name of the owning record
   * @param recordId     the owning record's primary key
   * @param tabIdHint    optional AD tab id; blank resolves the table's first standard tab
   * @param fileName     the sanitized submitted file name
   * @param declaredSize the size the caller declares, or a negative value when unknown
   * @param materializer writes the bytes to a temporary file once the cheap checks passed
   * @param markAsMain   whether the new attachment becomes the record's main document
   */
  public NeoAttachmentUpload(String tableName, String recordId, String tabIdHint,
      String fileName, long declaredSize, FileMaterializer materializer, boolean markAsMain) {
    this.tableName = tableName;
    this.recordId = recordId;
    this.tabIdHint = tabIdHint;
    this.fileName = fileName;
    this.declaredSize = declaredSize;
    this.materializer = materializer;
    this.markAsMain = markAsMain;
  }

  String getTableName() {
    return tableName;
  }

  String getRecordId() {
    return recordId;
  }

  String getTabIdHint() {
    return tabIdHint;
  }

  String getFileName() {
    return fileName;
  }

  long getDeclaredSize() {
    return declaredSize;
  }

  FileMaterializer getMaterializer() {
    return materializer;
  }

  boolean isMarkAsMain() {
    return markAsMain;
  }
}
