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
import java.nio.file.Files;
import java.nio.file.Path;

import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.openbravo.model.ad.utility.Attachment;

import com.etendoerp.go.schemaforge.NeoStagedDocuments.Staged;

/**
 * Attaches a staged document to the record created from it (ETP-5715), so the document the user supplied
 * ends as the document's attachment — the main one, as the application's supplier-invoice upload
 * leaves it — without uploading it twice.
 *
 * <p>It is the upload the SPA and the upload link run, not a copy of it: the bytes go through
 * {@link NeoAttachmentStore#store}, so the policy, the storage and the main-document replacement are
 * the same. The staged file itself is never handed over (the store deletes what it is given): a copy
 * goes in a directory of its own, and the staged document is removed by the caller once this answers
 * success.</p>
 */
public final class NeoStagedAttachment {

  private NeoStagedAttachment() {
  }

  /**
   * Stores the staged file as an attachment of the target's record.
   *
   * @param target the authorized document (write tier)
   * @param staged the staged document; its bytes must have arrived
   * @param main   whether it becomes the record's main attachment
   * @return 201 with {@code {name, message, id, isMain}}, or the store's 400/404/500
   */
  public static NeoResponse attach(NeoAttachmentTarget target, Staged staged, boolean main) {
    Path source = staged.getFile();
    if (source == null) {
      return NeoResponse.error(409, "The staged document has no file yet.");
    }
    NeoResponse stored = NeoAttachmentStore.store(new NeoAttachmentUpload(target.getTableName(),
        target.getRecordId(), target.getTabId(), staged.getFileName(), sizeOf(source),
        name -> copy(source, name), main));
    if (stored.getHttpStatus() >= 400) {
      return stored;
    }
    Attachment attachment = NeoAttachmentsHelper.findLatestUploadedAttachment(target.getTableId(),
        target.getRecordId(), staged.getFileName());
    JSONObject body = stored.getBody();
    try {
      body.put("id", attachment == null ? JSONObject.NULL : attachment.getId());
      body.put("isMain", main);
    } catch (JSONException e) {
      throw new IllegalStateException(e);
    }
    return stored;
  }

  private static long sizeOf(Path file) {
    try {
      return Files.size(file);
    } catch (IOException e) {
      return -1L;
    }
  }

  private static File copy(Path source, String name) throws IOException {
    Path directory = NeoAttachmentUploadEndpoint.createOwnerOnlyTempDirectory();
    Path target = directory.resolve(name);
    try {
      Files.copy(source, target);
    } catch (IOException | RuntimeException e) {
      Files.deleteIfExists(target);
      Files.deleteIfExists(directory);
      throw e;
    }
    return target.toFile();
  }
}
