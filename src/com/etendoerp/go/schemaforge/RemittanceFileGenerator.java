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
 * All portions are Copyright © 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashMap;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.utility.OBError;
import org.openbravo.model.ad.utility.Attachment;
import org.openbravo.module.remittance.Remittance;
import org.openbravo.module.remittance.utility.REM_RemittanceCreateFile;
import org.openbravo.sepa.iso20022.util.SEPA_RemittanceCreateFile;

/**
 * ETP-6247 — writes a remittance's SEPA file with Classic's generator and stores it as the
 * record's main attachment, the way Classic's «Create File» button ({@code CreateFile
 * #getFilePrintPage}) runs it: instantiate the remittance type's {@code Classname}, {@code init},
 * {@code generate}, and read {@code getMyError()} / {@code getLineErrorDetail()} for a refusal.
 *
 * <p><b>Schema validation.</b> Classic's {@code SEPA_RemittanceCreateFile#generateSEPAXML} locates
 * the XSD with {@code new File(getClass().getResource(...).getPath())}, which does not exist once
 * the module is packaged in a jar, and {@code SEPAUtil} then silently skips the validation. So GO
 * validates the produced XML itself ({@link RemittanceSepaSchema}): against the XSD next to the
 * generator class, else the copy GO ships. A file whose XSD cannot be found is refused
 * ({@value #MSG_SCHEMA_MISSING}), never stored unvalidated.
 *
 * <p><b>Encoding.</b> {@code SEPAUtil} marshals into a {@code StringBuffer} one byte per char
 * ({@code StringBufferOutputStream}), so the UTF-8 bytes are recovered with ISO-8859-1.
 *
 * <p><b>Storage.</b> The file is uploaded through the shared attachment manager, the same path the
 * {@code /sws/neo/attachments} endpoints and the AEAT receipts use, and becomes the main attachment
 * without deleting the previous one ({@link NeoAttachmentsHelper#markAsMain} with {@code keepPrevious}): a
 * regenerated file gets its own name ({@code _2}, {@code _3}...) because the attachment manager
 * overwrites a same-named attachment of the record. Nothing here commits: the caller's transaction
 * owns the attachment row. The file bytes are written to the attachment store before the commit,
 * so a rollback afterwards leaves an orphan file and no row.
 */
final class RemittanceFileGenerator {

  private static final Logger log = LogManager.getLogger(RemittanceFileGenerator.class);

  static final String MSG_GENERATION_FAILED = "ETGO_RemittanceFileGenerationFailed";
  static final String MSG_INVALID = "ETGO_RemittanceFileInvalid";
  static final String MSG_SCHEMA_MISSING = "ETGO_RemittanceFileSchemaMissing";
  static final String MSG_NO_GENERATOR = RemittanceFileReadiness.MSG_NO_GENERATOR;

  private static final String EXTENSION = "." + RemittanceSepaFileSetup.FILE_EXTENSION;
  private static final int MAX_NAME_ATTEMPTS = 1000;
  private static final int MAX_BASE_LENGTH = 120;

  private RemittanceFileGenerator() {
  }

  /** A refusal of the generation: the message key and its placeholders. */
  static final class Refusal extends Exception {
    private static final long serialVersionUID = 1L;
    private final String key;
    private final transient Object[] args;

    Refusal(String key, Object... args) {
      super(key);
      this.key = key;
      this.args = args;
    }

    String getKey() {
      return key;
    }

    Object[] getArgs() {
      return args;
    }
  }

  /** A stored file: the attachment and the name it was stored under. */
  static final class Stored {
    final String attachmentId;
    final String fileName;

    Stored(String attachmentId, String fileName) {
      this.attachmentId = attachmentId;
      this.fileName = fileName;
    }
  }

  /**
   * Generates the file of {@code remittance}.
   *
   * @param remittance a processed remittance whose type has a SEPA generator
   * @return the XML, UTF-8
   * @throws Refusal when the type has no usable generator, the generator refuses the remittance or
   *                 the XML does not pass its schema
   */
  static byte[] generate(Remittance remittance) throws Refusal {
    REM_RemittanceCreateFile generator = instantiate(remittance);
    StringBuffer xml;
    try {
      generator.init(remittance);
      xml = generator.generate(remittance);
    } catch (RuntimeException e) {
      log.warn("SEPA generator failed on remittance {}", remittance.getId(), e);
      throw new Refusal(MSG_GENERATION_FAILED, StringUtils.defaultIfBlank(
          com.etendoerp.go.schemaforge.util.NeoMessageTranslator.safeParseTranslation(
              e.getMessage()), e.getClass().getSimpleName()));
    }
    OBError error = generator.getMyError();
    if (error != null || xml == null) {
      throw new Refusal(MSG_GENERATION_FAILED, refusalText(generator, error));
    }
    byte[] bytes = xml.toString().getBytes(StandardCharsets.ISO_8859_1);
    validate(generator, bytes);
    return bytes;
  }

  /**
   * Stores {@code content} as the remittance's main attachment, keeping the earlier ones.
   *
   * @param remittance the remittance
   * @param content    the XML
   * @return the stored attachment
   * @throws IOException when the temporary file cannot be written
   */
  static Stored store(Remittance remittance, byte[] content) throws IOException {
    String tableId = RemittanceSepaProvisioning.REMITTANCE_TABLE_ID;
    String tabId = NeoAttachmentsHelper.resolveTabId(tableId, null);
    if (tabId == null) {
      throw new IOException("No tab of REM_Remittance to attach the SEPA file to");
    }
    String fileName = freeFileName(remittance);
    Path tempDir = null;
    try {
      FileAttribute<Set<PosixFilePermission>> ownerOnly =
          PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"));
      tempDir = Files.createTempDirectory("etgo-sepa-", ownerOnly);
      Path file = tempDir.resolve(fileName);
      Files.write(file, content);
      NeoAttachmentsHelper.getAttachManager().upload(new HashMap<>(), tabId,
          remittance.getId(), remittance.getOrganization().getId(), file.toFile());
      Attachment stored =
          NeoAttachmentsHelper.findLatestUploadedAttachment(tableId, remittance.getId(), fileName);
      if (stored == null) {
        throw new IOException("The SEPA file was uploaded but its attachment cannot be found");
      }
      NeoAttachmentsHelper.markAsMain(stored, true);
      return new Stored(stored.getId(), fileName);
    } finally {
      deleteQuietly(tempDir);
    }
  }

  // ---------------------------------------------------------------------------
  // Generator
  // ---------------------------------------------------------------------------

  private static REM_RemittanceCreateFile instantiate(Remittance remittance) throws Refusal {
    String typeName = remittance.getRemittanceType() != null
        ? remittance.getRemittanceType().getName()
        : null;
    String className = remittance.getRemittanceType() != null
        ? StringUtils.trimToNull(remittance.getRemittanceType().getJavaClassName())
        : null;
    if (className == null) {
      throw new Refusal(MSG_NO_GENERATOR, typeName);
    }
    try {
      Object instance = Class.forName(className).getDeclaredConstructor().newInstance();
      if (instance instanceof REM_RemittanceCreateFile) {
        return (REM_RemittanceCreateFile) instance;
      }
    } catch (ReflectiveOperationException | LinkageError e) {
      log.error("Cannot instantiate the remittance file generator {}", className, e);
    }
    throw new Refusal(MSG_NO_GENERATOR, typeName);
  }

  /** The generator's refusal, worded as Classic's button words it: line detail, then message. */
  private static String refusalText(REM_RemittanceCreateFile generator, OBError error) {
    String message = error != null
        ? StringUtils.defaultIfBlank(error.getMessage(), error.getTitle())
        : null;
    String translated = com.etendoerp.go.schemaforge.util.NeoMessageTranslator
        .safeParseTranslation(message);
    String detail = StringUtils.trimToNull(generator.getLineErrorDetail());
    String text = detail != null ? detail + " " + StringUtils.defaultString(translated)
        : translated;
    return StringUtils.defaultIfBlank(StringUtils.trim(text), "-");
  }

  // ---------------------------------------------------------------------------
  // Schema validation
  // ---------------------------------------------------------------------------

  private static void validate(REM_RemittanceCreateFile generator, byte[] xml) throws Refusal {
    if (!(generator instanceof SEPA_RemittanceCreateFile)) {
      return;
    }
    String version = ((SEPA_RemittanceCreateFile) generator).getSchema_version();
    URL xsd = RemittanceSepaSchema.locate(generator.getClass(), version);
    if (xsd == null) {
      log.error("No XSD {}.xsd next to {} nor bundled with GO: the SEPA file is refused",
          version, generator.getClass().getName());
      throw new Refusal(MSG_SCHEMA_MISSING, StringUtils.defaultIfBlank(version, "-"));
    }
    String failure = RemittanceSepaSchema.failure(xsd, xml);
    if (failure != null) {
      throw new Refusal(MSG_INVALID, version, failure);
    }
  }

  // ---------------------------------------------------------------------------
  // File name
  // ---------------------------------------------------------------------------

  /**
   * Classic's name ({@code <documentNo>_<name>.xml}) restricted to safe characters, with a numeric
   * suffix when the record already has an attachment of that name.
   */
  static String freeFileName(Remittance remittance) {
    String base = baseName(remittance);
    String candidate = base + EXTENSION;
    for (int attempt = 2; attempt <= MAX_NAME_ATTEMPTS && nameTaken(remittance, candidate);
        attempt++) {
      candidate = base + "_" + attempt + EXTENSION;
    }
    return candidate;
  }

  static String baseName(Remittance remittance) {
    String raw = StringUtils.defaultString(remittance.getDocumentNo()) + "_"
        + StringUtils.defaultString(remittance.getName());
    String safe = StringUtils.stripAccents(raw)
        .replaceAll("[^A-Za-z0-9._-]+", "_")
        .replaceAll("_+", "_");
    safe = StringUtils.strip(safe, "._");
    return StringUtils.left(StringUtils.defaultIfBlank(safe, "remittance"), MAX_BASE_LENGTH);
  }

  private static boolean nameTaken(Remittance remittance, String fileName) {
    Long count = OBDal.getInstance().getSession().createQuery(
        "select count(a.id) from " + Attachment.ENTITY_NAME + " as a where a.table.id = :table"
            + " and a.record = :record and a.name = :name", Long.class)
        .setParameter("table", RemittanceSepaProvisioning.REMITTANCE_TABLE_ID)
        .setParameter("record", remittance.getId())
        .setParameter("name", fileName)
        .uniqueResult();
    return count != null && count > 0;
  }

  private static void deleteQuietly(Path dir) {
    if (dir == null) {
      return;
    }
    try {
      File[] children = dir.toFile().listFiles();
      if (children != null) {
        for (File child : children) {
          Files.deleteIfExists(child.toPath());
        }
      }
      Files.deleteIfExists(dir);
    } catch (IOException e) {
      log.warn("Could not clean up temporary directory {}", dir, e);
    }
  }
}
