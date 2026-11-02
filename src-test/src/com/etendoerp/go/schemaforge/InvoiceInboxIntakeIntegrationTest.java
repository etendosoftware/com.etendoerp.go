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
 * All portions are Copyright (C) 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import javax.servlet.http.Part;

import org.hibernate.query.NativeQuery;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.openbravo.base.weld.test.WeldBaseTest;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.utility.Attachment;
import org.openbravo.test.base.TestConstants;

import com.etendoerp.go.schemaforge.data.IncomingDocument;

/**
 * Real-database specs for {@link InvoiceInboxIntake}: what is actually <em>committed</em> for an
 * uploaded file. A mock cannot prove that the {@code PROCESSING} row and the main attachment
 * commit together or not at all, that a rejected file leaves nothing behind, or that a commit that
 * closes the Hibernate session does not break the next file.
 *
 * <p><b>Why {@code WeldBaseTest}.</b> The attachment store resolves the core's
 * {@code AttachImplementationManager} through CDI ({@code NeoAttachmentsHelper.getAttachManager()}
 * via {@code WeldUtils}); under a plain {@code OBBaseTest} there is no container and every store
 * call would answer 500. {@code WeldBaseTest} installs the bean manager in its own
 * {@code setUp()}, so the {@code @Before} below must not be named {@code setUp}.
 *
 * <p>The fixture is the F&amp;B group client and its Spain organization from the sample data
 * ({@link TestConstants}); nothing here depends on records that exist only in a local database. The
 * {@code ETGO_InvoiceInbox*} {@code AD_MESSAGE} texts are never asserted: a refusal is identified by
 * its code, and the code falls back to the English default when the row is not installed.
 *
 * <p><b>Cleanup is explicit, not a rollback.</b> Every accepted file commits, so each fixture file
 * name carries a per-run marker and {@link #deleteCommittedFixtures()} removes the attachments (and
 * their bytes, through the core's attachment manager) and then the rows.
 *
 * @covers com.etendoerp.go.schemaforge.InvoiceInboxIntake
 */
public class InvoiceInboxIntakeIntegrationTest extends WeldBaseTest {

  private static final String CLIENT_ID = TestConstants.Clients.FB_GRP;
  private static final String ORG_ID = TestConstants.Orgs.ESP;

  /** Prefix of every fixture file name, unique per run, so cleanup is exact. */
  private final String marker = "etgo-inbox-it-" + UUID.randomUUID().toString().substring(0, 8) + "-";

  @Before
  public void prepareContext() {
    OBContext.clearAdminModeStack();
    login();
  }

  @After
  public void cleanUp() {
    try {
      deleteCommittedFixtures();
    } finally {
      OBDal.getInstance().rollbackAndClose();
      OBContext.setOBContext((OBContext) null);
    }
  }

  @Test
  public void twoFilesInSequenceEachCommitAProcessingRowWithItsMainAttachment() {
    long invoicesBefore = count("SELECT count(*) FROM c_invoice");

    InvoiceInboxIntake.Result first = ingest(pdf("first.pdf"));
    login(); // the previous commit closed the session: the next file starts from a fresh one
    InvoiceInboxIntake.Result second = ingest(pdf("second.pdf"));

    assertTrue(String.valueOf(first.getCode()), first.isAccepted());
    assertTrue(String.valueOf(second.getCode()), second.isAccepted());
    assertNotEquals(first.getIncomingDocumentId(), second.getIncomingDocumentId());

    login();
    for (InvoiceInboxIntake.Result result : List.of(first, second)) {
      assertRowIsProcessing(result.getIncomingDocumentId(), result.getFileName());
      assertEquals("exactly one main attachment per row", 1L, count(
          "SELECT count(*) FROM c_file WHERE ad_table_id = :t AND ad_record_id = :r "
              + "AND em_etgo_ispreviewmain = 'Y'", "t", tableId(), "r",
          result.getIncomingDocumentId()));
      assertEquals("no attachment besides the main one", 1L, count(
          "SELECT count(*) FROM c_file WHERE ad_table_id = :t AND ad_record_id = :r", "t",
          tableId(), "r", result.getIncomingDocumentId()));
    }
    assertEquals("the inbox never creates an invoice", invoicesBefore,
        count("SELECT count(*) FROM c_invoice"));
  }

  @Test
  public void aContentMismatchLeavesNoRowAndNoAttachment() {
    long invoicesBefore = count("SELECT count(*) FROM c_invoice");
    String name = marker + "not-really-a-pdf.pdf";

    InvoiceInboxIntake.Result result = ingest(part(name, "this is plain text, not a PDF".getBytes(
        StandardCharsets.UTF_8)));

    assertFalse(result.isAccepted());
    assertEquals("invalid_content", result.getCode());
    login();
    assertEquals("a refused file must leave no row", 0L,
        count("SELECT count(*) FROM etgo_incoming_document WHERE file_name = :n", "n", name));
    assertEquals("nor an attachment", 0L, count("SELECT count(*) FROM c_file WHERE name = :n",
        "n", name));
    assertEquals(invoicesBefore, count("SELECT count(*) FROM c_invoice"));
  }

  @Test
  public void aRefusedFileBetweenTwoGoodOnesDoesNotAffectThem() {
    InvoiceInboxIntake.Result before = ingest(pdf("before.pdf"));
    login();
    InvoiceInboxIntake.Result refused = ingest(part(marker + "fake.pdf",
        "plain text".getBytes(StandardCharsets.UTF_8)));
    login();
    InvoiceInboxIntake.Result after = ingest(pdf("after.pdf"));

    assertTrue(String.valueOf(before.getCode()), before.isAccepted());
    assertEquals("invalid_content", refused.getCode());
    assertTrue(String.valueOf(after.getCode()), after.isAccepted());
    login();
    assertEquals("only the two good files were persisted", 2L, count(
        "SELECT count(*) FROM etgo_incoming_document WHERE file_name LIKE :p", "p", marker + "%"));
    assertRowIsProcessing(before.getIncomingDocumentId(), before.getFileName());
    assertRowIsProcessing(after.getIncomingDocumentId(), after.getFileName());
  }

  // ---------------------------------------------------------------- helpers

  /** Installs the F&B group admin context; also what a commit's closed session needs next. */
  private static void login() {
    OBContext.setOBContext(TestConstants.Users.ADMIN, TestConstants.Roles.FB_GRP_ADMIN, CLIENT_ID,
        ORG_ID);
  }

  /** Runs the intake the way the NEO built-in path does: in admin mode. */
  private static InvoiceInboxIntake.Result ingest(Part part) {
    OBContext.setAdminMode(false);
    try {
      return InvoiceInboxIntake.ingest(part, part.getSubmittedFileName(), CLIENT_ID, ORG_ID);
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  private Part pdf(String shortName) {
    byte[] content = ("%PDF-1.4\n1 0 obj\n<< /Type /Catalog >>\nendobj\ntrailer\n<< /Root 1 0 R >>\n"
        + "%%EOF\n" + shortName).getBytes(StandardCharsets.UTF_8);
    return part(marker + shortName, content);
  }

  private static Part part(String submittedName, byte[] content) {
    Part part = mock(Part.class);
    when(part.getSubmittedFileName()).thenReturn(submittedName);
    when(part.getSize()).thenReturn((long) content.length);
    try {
      when(part.getInputStream()).thenReturn(new ByteArrayInputStream(content));
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
    return part;
  }

  private void assertRowIsProcessing(String id, String fileName) {
    NativeQuery query = OBDal.getInstance().getSession().createNativeQuery(
        "SELECT status, source, target_type, adjusted, reviewed, createdby, file_name, "
            + "ad_client_id, ad_org_id FROM etgo_incoming_document "
            + "WHERE etgo_incoming_document_id = :id");
    query.setParameter("id", id);
    Object[] row = (Object[]) query.uniqueResult();
    assertTrue("the row " + id + " is committed", row != null);
    // CHAR(1) columns come back as Character, VARCHAR as String: compare as text either way.
    assertEquals("PROCESSING", String.valueOf(row[0]));
    assertEquals("UPLOAD", String.valueOf(row[1]));
    assertEquals("PURCHASE_INVOICE", String.valueOf(row[2]));
    assertEquals("N", String.valueOf(row[3]));
    assertEquals("N", String.valueOf(row[4]));
    assertEquals("the audit user is filled by the DAL, not set by the intake",
        TestConstants.Users.ADMIN, String.valueOf(row[5]));
    assertEquals(fileName, String.valueOf(row[6]));
    assertEquals(CLIENT_ID, String.valueOf(row[7]));
    assertEquals(ORG_ID, String.valueOf(row[8]));
  }

  private static String tableId() {
    NativeQuery query = OBDal.getInstance().getSession().createNativeQuery(
        "SELECT ad_table_id FROM ad_table WHERE upper(tablename) = upper(:t)");
    query.setParameter("t", IncomingDocument.TABLE_NAME);
    return (String) query.uniqueResult();
  }

  /** A count query; {@code namesAndValues} alternate parameter name and value. */
  private static long count(String sql, Object... namesAndValues) {
    NativeQuery query = OBDal.getInstance().getSession().createNativeQuery(sql);
    for (int i = 0; i + 1 < namesAndValues.length; i += 2) {
      query.setParameter((String) namesAndValues[i], namesAndValues[i + 1]);
    }
    return ((Number) query.uniqueResult()).longValue();
  }

  /**
   * Removes everything this run committed: the attachments (through the core's manager, so the
   * stored bytes go too), then the rows. Every step is best effort so one failure cannot strand
   * the rest.
   */
  @SuppressWarnings("unchecked")
  private void deleteCommittedFixtures() {
    login();
    OBContext.setAdminMode(false);
    try {
      NativeQuery rows = OBDal.getInstance().getSession().createNativeQuery(
          "SELECT etgo_incoming_document_id FROM etgo_incoming_document WHERE file_name LIKE :p");
      rows.setParameter("p", marker + "%");
      List<String> ids = rows.list();
      for (String id : ids) {
        NativeQuery files = OBDal.getInstance().getSession().createNativeQuery(
            "SELECT c_file_id FROM c_file WHERE ad_table_id = :t AND ad_record_id = :r");
        files.setParameter("t", tableId());
        files.setParameter("r", id);
        for (String fileId : (List<String>) files.list()) {
          Attachment attachment = OBDal.getInstance().get(Attachment.class, fileId);
          if (attachment != null) {
            NeoAttachmentsHelper.getAttachManager().delete(attachment);
          }
        }
      }
      OBDal.getInstance().flush();
      NativeQuery delete = OBDal.getInstance().getSession().createNativeQuery(
          "DELETE FROM etgo_incoming_document WHERE file_name LIKE :p");
      delete.setParameter("p", marker + "%");
      delete.executeUpdate();
      OBDal.getInstance().commitAndClose();
    } finally {
      OBContext.restorePreviousMode();
    }
  }
}
