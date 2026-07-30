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

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;

import javax.servlet.ServletOutputStream;
import javax.servlet.WriteListener;
import javax.servlet.http.HttpServletResponse;

import org.junit.Ignore;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.openbravo.base.weld.WeldUtils;
import org.openbravo.client.application.attachment.AttachImplementationManager;
import org.openbravo.dal.security.SecurityChecker;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.utility.Attachment;

/**
 * SEC-11b attachment authorization matrix — deliverable <b>E5</b> of ETP-4569.
 *
 * <p>This class is the executable reproduction of the cross-organization attachment
 * IDOR described in the Client &amp; Delivery Security Hardening PRD, and the red
 * baseline for ETP-4570. It is the authoritative artifact for the finding; the
 * low-privilege black-box run is supplementary confirmation, per ADR-0003.</p>
 *
 * <h2>Coverage actually provided (no silent caps)</h2>
 *
 * <p>ADR-0003 specifies 8 caller scenarios × 6 operations = 48 cells. This class
 * covers the subset that is <b>decidable without a database</b>, and it is explicit
 * about the rest:</p>
 *
 * <ul>
 *   <li><b>Covered here (group A, green):</b> the structural fact that
 *       {@code handleDownload}, {@code handleDelete} and
 *       {@code handleUpdateDescription} resolve an attachment from a bare ID and
 *       act on it <b>without consulting any authorization primitive at all</b>.
 *       Because no check exists, scenarios S2–S5 (same-org without table access,
 *       non-readable organization, cross-client, inactive record) are
 *       <b>indistinguishable at this layer</b> — the code never examines the
 *       caller's context. That collapse <i>is</i> the finding, and proving the
 *       absence of the check covers all four scenarios at once.</li>
 *   <li><b>Covered here:</b> S6 (nonexistent ID) versus an unauthorized-but-existing
 *       ID, which demonstrates the enumeration oracle that ADR-0003 D5 closes with
 *       a uniform {@code 404}.</li>
 *   <li><b>Covered here:</b> the SEC-12 residue on the same response path — stored
 *       MIME echoed back and no {@code X-Content-Type-Options}.</li>
 *   <li><b>NOT covered here — requires {@code OBBaseTest} with seeded fixtures:</b>
 *       distinguishing S2 from S3 from S4 from S5 by actual role/organization data,
 *       and S8 (legitimate multi-org administrator must keep working). Those need
 *       two organizations in one client, a second client, a role without parent-table
 *       access, and an inactive record. Provisioning them is ETP-4570's setup cost;
 *       they are recorded as an external dependency in
 *       {@code docs/security/findings-status.md}, not represented as passing.</li>
 *   <li><b>NOT covered here:</b> {@code handleList}, {@code handleUpload} and
 *       {@code handleDownloadAll}, whose parent context is client-supplied. S7
 *       (supplied parent mismatching the attachment's real parent) is only
 *       meaningful once a parent-derivation step exists, so it belongs to ETP-4570.</li>
 * </ul>
 *
 * <h2>Why group A asserts insecure behavior</h2>
 *
 * <p>These tests <b>pin the vulnerable contract as it exists today</b> so the
 * reproduction is repeatable in CI without a provisioned environment. They are
 * expected to <b>fail once ETP-4570 lands</b> — that is the point. The target
 * contract is expressed in group B, disabled until the fix exists. Do not "repair"
 * a group A failure by relaxing it: invert it to the group B expectation.</p>
 *
 * @see <a href="https://etendoproject.atlassian.net/browse/ETP-4569">ETP-4569</a>
 */
public class NeoAttachmentAuthorizationMatrixTest {

  /** An attachment ID belonging to a record the caller has no access to. */
  private static final String FOREIGN_ATTACHMENT_ID = "8A1E7C0FF33D4B0E9C1A5D2E3F4B5C6D";
  private static final String MISSING_ATTACHMENT_ID = "00000000000000000000000000000000";
  private static final byte[] PAYLOAD = "cross-tenant attachment payload".getBytes(UTF_8);

  // ───────────────────────── Group A — reproduction (green today) ─────────────

  /**
   * S2–S5 collapsed: the download path streams an attachment owned by another
   * tenant and never consults a single authorization primitive.
   *
   * <p>This is the SEC-11b reproduction required by ETP-4569 E5.</p>
   */
  @Test
  public void handleDownloadServesForeignAttachmentWithoutAnyAuthorizationCheck() throws Exception {
    ByteArrayOutputStream written = new ByteArrayOutputStream();
    HttpServletResponse response = mockResponseCapturing(written);
    Attachment foreign = foreignAttachment();
    OBDal dal = mock(OBDal.class);
    when(dal.get(Attachment.class, FOREIGN_ATTACHMENT_ID)).thenReturn(foreign);

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
         MockedStatic<WeldUtils> weld = mockStatic(WeldUtils.class);
         MockedStatic<SecurityChecker> security = mockStatic(SecurityChecker.class)) {
      obDal.when(OBDal::getInstance).thenReturn(dal);
      stubDownloadManager(weld);

      NeoAttachmentsHelper.handleDownload(FOREIGN_ATTACHMENT_ID, response);

      // The bytes of another tenant's file reach the caller.
      verify(response).setStatus(HttpServletResponse.SC_OK);
      assertArrayEquals(PAYLOAD, written.toByteArray());

      // The platform's record-level authorization was never invoked.
      security.verifyNoInteractions();

      // The only DAL access is the bare-ID load: no readable-org or role scoping.
      verify(dal).get(Attachment.class, FOREIGN_ATTACHMENT_ID);
    }
  }

  /**
   * SEC-12 residue on the same response: the stored, uploader-controlled MIME is
   * echoed back and no {@code nosniff} is emitted.
   */
  @Test
  public void handleDownloadEchoesStoredMimeAndOmitsNosniff() throws Exception {
    ByteArrayOutputStream written = new ByteArrayOutputStream();
    HttpServletResponse response = mockResponseCapturing(written);
    Attachment foreign = foreignAttachment();
    OBDal dal = mock(OBDal.class);
    when(dal.get(Attachment.class, FOREIGN_ATTACHMENT_ID)).thenReturn(foreign);

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
         MockedStatic<WeldUtils> weld = mockStatic(WeldUtils.class)) {
      obDal.when(OBDal::getInstance).thenReturn(dal);
      stubDownloadManager(weld);

      NeoAttachmentsHelper.handleDownload(FOREIGN_ATTACHMENT_ID, response);

      // Uploader-controlled content type is trusted verbatim.
      verify(response).setContentType("text/html");
      // No nosniff anywhere on this path.
      verify(response, org.mockito.Mockito.never())
          .setHeader(eq("X-Content-Type-Options"), any());
    }
  }

  /**
   * The enumeration oracle: a nonexistent ID and an existing-but-unauthorized ID
   * produce different responses, so attachment IDs can be probed for existence.
   * ADR-0003 D5 requires both to be a byte-identical {@code 404}.
   */
  @Test
  public void handleDownloadDistinguishesMissingFromUnauthorized() throws Exception {
    // Nonexistent ID → 404.
    HttpServletResponse missingResponse = mock(HttpServletResponse.class);
    StringWriter missingSink = stubWriter(missingResponse);
    OBDal missingDal = mock(OBDal.class);
    when(missingDal.get(Attachment.class, MISSING_ATTACHMENT_ID)).thenReturn(null);

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class)) {
      obDal.when(OBDal::getInstance).thenReturn(missingDal);
      NeoAttachmentsHelper.handleDownload(MISSING_ATTACHMENT_ID, missingResponse);
    }
    verify(missingResponse).setStatus(HttpServletResponse.SC_NOT_FOUND);

    // Existing but unauthorized ID → 200 with content. The difference is the oracle.
    ByteArrayOutputStream written = new ByteArrayOutputStream();
    HttpServletResponse foreignResponse = mockResponseCapturing(written);
    Attachment foreign = foreignAttachment();
    OBDal foreignDal = mock(OBDal.class);
    when(foreignDal.get(Attachment.class, FOREIGN_ATTACHMENT_ID)).thenReturn(foreign);

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
         MockedStatic<WeldUtils> weld = mockStatic(WeldUtils.class)) {
      obDal.when(OBDal::getInstance).thenReturn(foreignDal);
      stubDownloadManager(weld);
      NeoAttachmentsHelper.handleDownload(FOREIGN_ATTACHMENT_ID, foreignResponse);
    }
    verify(foreignResponse).setStatus(HttpServletResponse.SC_OK);
    assertEquals("Missing and unauthorized must not be distinguishable",
        PAYLOAD.length, written.toByteArray().length);
  }

  /**
   * WRITE operation, same defect: delete resolves a bare ID and destroys another
   * tenant's attachment with no authorization step.
   */
  @Test
  public void handleDeleteRemovesForeignAttachmentWithoutAnyAuthorizationCheck() {
    Attachment attachment = foreignAttachment();
    OBDal dal = mock(OBDal.class);
    when(dal.get(Attachment.class, FOREIGN_ATTACHMENT_ID)).thenReturn(attachment);
    AttachImplementationManager aim = mock(AttachImplementationManager.class);

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
         MockedStatic<WeldUtils> weld = mockStatic(WeldUtils.class);
         MockedStatic<SecurityChecker> security = mockStatic(SecurityChecker.class)) {
      obDal.when(OBDal::getInstance).thenReturn(dal);
      weld.when(() -> WeldUtils.getInstanceFromStaticBeanManager(AttachImplementationManager.class))
          .thenReturn(aim);

      NeoResponse result = NeoAttachmentsHelper.handleDelete(FOREIGN_ATTACHMENT_ID);

      assertEquals(204, result.getHttpStatus());
      verify(aim).delete(attachment);
      security.verifyNoInteractions();
    }
  }

  /**
   * WRITE operation, same defect: the description of another tenant's attachment
   * is mutated with no authorization step.
   */
  @Test
  public void handleUpdateDescriptionMutatesForeignAttachmentWithoutAuthorizationCheck() {
    Attachment attachment = foreignAttachment();
    OBDal dal = mock(OBDal.class);
    when(dal.get(Attachment.class, FOREIGN_ATTACHMENT_ID)).thenReturn(attachment);

    try (MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
         MockedStatic<SecurityChecker> security = mockStatic(SecurityChecker.class)) {
      obDal.when(OBDal::getInstance).thenReturn(dal);

      NeoResponse result =
          NeoAttachmentsHelper.handleUpdateDescription(FOREIGN_ATTACHMENT_ID, "tampered");

      assertEquals(200, result.getHttpStatus());
      verify(attachment).setText("tampered");
      verify(dal).save(attachment);
      security.verifyNoInteractions();
    }
  }

  // ───────────────── Group B — target contract (red until ETP-4570) ──────────

  /**
   * S3 — same client, non-readable organization. Must be {@code 404} and must not
   * stream any bytes.
   */
  @Test
  @Ignore("ETP-4570: red until centralized parent-record authorization lands")
  public void handleDownloadMustReturnNotFoundForNonReadableOrganization() {
    throw new UnsupportedOperationException(
        "Implement with ADR-0003's authorizer: derive the parent record from the "
            + "attachment, authorize it outside admin mode, and return a uniform 404.");
  }

  /**
   * S6 versus S2–S5 — the {@code 404} for nonexistent and for unauthorized must be
   * byte-identical, removing the enumeration oracle (ADR-0003 D5).
   */
  @Test
  @Ignore("ETP-4570: red until centralized parent-record authorization lands")
  public void handleDownloadMustReturnIdenticalNotFoundForMissingAndUnauthorized() {
    throw new UnsupportedOperationException(
        "Assert identical status, body and headers for a nonexistent ID and an "
            + "existing-but-unauthorized ID.");
  }

  /**
   * S8 — a legitimate multi-organization administrator must keep working, through
   * the normal permission model and with no hardcoded GOAdmin exception (ADR-0003 D4).
   */
  @Test
  @Ignore("ETP-4570: red until centralized parent-record authorization lands")
  public void handleDownloadMustStillAllowLegitimateMultiOrgRole() {
    throw new UnsupportedOperationException(
        "Requires OBBaseTest fixtures: a role with several readable organizations.");
  }

  /**
   * S7 — a client-supplied parent that disagrees with the attachment's real parent
   * must be rejected, never used as the authorization basis.
   */
  @Test
  @Ignore("ETP-4570: red until centralized parent-record authorization lands")
  public void suppliedParentMismatchingRealParentMustBeRejected() {
    throw new UnsupportedOperationException(
        "Applies to list, upload and download-all once parent derivation exists.");
  }

  // ───────────────────────────────── helpers ─────────────────────────────────

  /** An attachment whose parent record the caller is not entitled to reach. */
  private static Attachment foreignAttachment() {
    Attachment attachment = mock(Attachment.class);
    when(attachment.getId()).thenReturn(FOREIGN_ATTACHMENT_ID);
    when(attachment.getName()).thenReturn("other-tenant-invoice.html");
    when(attachment.getDataType()).thenReturn("text/html");
    return attachment;
  }

  private static void stubDownloadManager(MockedStatic<WeldUtils> weld) {
    AttachImplementationManager aim = mock(AttachImplementationManager.class);
    doAnswer(invocation -> {
      OutputStream target = invocation.getArgument(1);
      target.write(PAYLOAD);
      return null;
    }).when(aim).download(eq(FOREIGN_ATTACHMENT_ID), any(OutputStream.class));
    weld.when(() -> WeldUtils.getInstanceFromStaticBeanManager(AttachImplementationManager.class))
        .thenReturn(aim);
  }

  private static HttpServletResponse mockResponseCapturing(ByteArrayOutputStream sink)
      throws Exception {
    HttpServletResponse response = mock(HttpServletResponse.class);
    ServletOutputStream stream = new ServletOutputStream() {
      @Override
      public void write(int b) {
        sink.write(b);
      }

      @Override
      public boolean isReady() {
        return true;
      }

      @Override
      public void setWriteListener(WriteListener listener) {
        throw new UnsupportedOperationException();
      }
    };
    when(response.getOutputStream()).thenReturn(stream);
    return response;
  }

  private static StringWriter stubWriter(HttpServletResponse response) throws Exception {
    StringWriter sink = new StringWriter();
    when(response.getWriter()).thenReturn(new PrintWriter(sink));
    return sink;
  }
}
