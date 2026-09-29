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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;
import org.mockito.MockedStatic;
import org.openbravo.base.exception.OBException;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.datamodel.Table;
import org.openbravo.model.ad.ui.Tab;
import org.openbravo.model.ad.ui.Window;
import org.openbravo.model.ad.utility.Attachment;

import com.etendoerp.go.schemaforge.util.NeoAccessHelper;

/**
 * ETP-5205 — write-tier slice of {@link NeoAttachmentAuthorizer} (ADR-0003 D2). The rule: the
 * current role needs editable access to at least one active window showing the attachment's
 * table; a table no window shows is allowed; the table comes from the stored attachment row.
 */
public class NeoAttachmentAuthorizerTest {

  private static final String TABLE_ID = "259";
  private static final String SALES_ORDER_WINDOW = "143";
  private static final String PURCHASE_ORDER_WINDOW = "181";
  private static final String ATTACHMENT_ID = "ATT123";

  @Test
  public void fullAccessOnTheOnlyWindowIsAllowed() {
    try (Env env = new Env(window(SALES_ORDER_WINDOW, true))) {
      env.access.when(() -> NeoAccessHelper.hasWindowAccess(SALES_ORDER_WINDOW, "POST"))
          .thenReturn(true);

      assertNull(NeoAttachmentAuthorizer.checkWrite(TABLE_ID));
    }
  }

  @Test
  public void readOnlyOrNoAccessOnEveryWindowIsForbidden() {
    try (Env env = new Env(window(SALES_ORDER_WINDOW, true), window(PURCHASE_ORDER_WINDOW, true))) {
      // hasWindowAccess(…, "POST") is false for a Solo-Lectura tier and for no access alike.
      env.access.when(() -> NeoAccessHelper.hasWindowAccess(anyString(), anyString()))
          .thenReturn(false);

      NeoResponse denied = NeoAttachmentAuthorizer.checkWrite(TABLE_ID);

      assertNotNull(denied);
      assertEquals(403, denied.getHttpStatus());
    }
  }

  @Test
  public void editableAccessOnAnyOneWindowOfTheTableIsEnough() {
    try (Env env = new Env(window(SALES_ORDER_WINDOW, true), window(PURCHASE_ORDER_WINDOW, true))) {
      env.access.when(() -> NeoAccessHelper.hasWindowAccess(SALES_ORDER_WINDOW, "POST"))
          .thenReturn(false);
      env.access.when(() -> NeoAccessHelper.hasWindowAccess(PURCHASE_ORDER_WINDOW, "POST"))
          .thenReturn(true);

      assertNull(NeoAttachmentAuthorizer.checkWrite(TABLE_ID));
    }
  }

  @Test
  public void inactiveWindowsDoNotCount() {
    try (Env env = new Env(window(SALES_ORDER_WINDOW, false), window(PURCHASE_ORDER_WINDOW, true))) {
      // Full access only on the inactive window: it must not open the door.
      env.access.when(() -> NeoAccessHelper.hasWindowAccess(SALES_ORDER_WINDOW, "POST"))
          .thenReturn(true);
      env.access.when(() -> NeoAccessHelper.hasWindowAccess(PURCHASE_ORDER_WINDOW, "POST"))
          .thenReturn(false);

      assertNotNull(NeoAttachmentAuthorizer.checkWrite(TABLE_ID));
    }
  }

  @Test
  public void tableThatNoWindowShowsIsAllowed() {
    try (Env env = new Env()) {
      assertNull(NeoAttachmentAuthorizer.checkWrite(TABLE_ID));
      env.access.verifyNoInteractions();
    }
  }

  @Test
  public void attachmentWriteUsesTheTableOfTheStoredAttachment() {
    Attachment attachment = mock(Attachment.class);
    Table table = mock(Table.class);
    when(table.getId()).thenReturn(TABLE_ID);
    when(attachment.getTable()).thenReturn(table);
    try (Env env = new Env(window(SALES_ORDER_WINDOW, true))) {
      when(env.dal.get(Attachment.class, ATTACHMENT_ID)).thenReturn(attachment);
      env.access.when(() -> NeoAccessHelper.hasWindowAccess(SALES_ORDER_WINDOW, "POST"))
          .thenReturn(false);

      NeoResponse denied = NeoAttachmentAuthorizer.checkWriteOnAttachment(ATTACHMENT_ID);

      assertNotNull(denied);
      assertEquals(403, denied.getHttpStatus());
    }
  }

  @Test
  public void missingAttachmentIsLeftToTheOperationsOwn404() {
    try (Env env = new Env()) {
      when(env.dal.get(Attachment.class, ATTACHMENT_ID)).thenReturn(null);

      assertNull(NeoAttachmentAuthorizer.checkWriteOnAttachment(ATTACHMENT_ID));
      assertNull(NeoAttachmentAuthorizer.checkWriteOnAttachment(" "));
    }
  }

  @Test
  public void attachmentWithoutTableFailsClosed() {
    Attachment attachment = mock(Attachment.class);
    try (Env env = new Env()) {
      when(env.dal.get(Attachment.class, ATTACHMENT_ID)).thenReturn(attachment);

      NeoResponse denied = NeoAttachmentAuthorizer.checkWriteOnAttachment(ATTACHMENT_ID);

      assertNotNull(denied);
      assertEquals(403, denied.getHttpStatus());
    }
  }

  @Test
  public void uploadResolvesTheTableByName() {
    try (Env env = new Env(window(SALES_ORDER_WINDOW, true));
         MockedStatic<NeoAttachmentsHelper> helper = mockStatic(NeoAttachmentsHelper.class)) {
      helper.when(() -> NeoAttachmentsHelper.resolveTableId("C_Order")).thenReturn(TABLE_ID);
      env.access.when(() -> NeoAccessHelper.hasWindowAccess(SALES_ORDER_WINDOW, "POST"))
          .thenReturn(false);

      NeoResponse denied = NeoAttachmentAuthorizer.checkWriteOnTable("C_Order");

      assertNotNull(denied);
      assertEquals(403, denied.getHttpStatus());
    }
  }

  @Test
  public void unknownTableIsLeftToTheUploadsOwnError() {
    try (MockedStatic<NeoAttachmentsHelper> helper = mockStatic(NeoAttachmentsHelper.class)) {
      helper.when(() -> NeoAttachmentsHelper.resolveTableId("Nope"))
          .thenThrow(new OBException("Unknown table: Nope"));

      assertNull(NeoAttachmentAuthorizer.checkWriteOnTable("Nope"));
      assertNull(NeoAttachmentAuthorizer.checkWriteOnTable(null));
    }
  }

  // ───────────────────────────────── helpers ─────────────────────────────────

  private static Tab window(String windowId, boolean active) {
    Window window = mock(Window.class);
    when(window.getId()).thenReturn(windowId);
    when(window.isActive()).thenReturn(active);
    Tab tab = mock(Tab.class);
    when(tab.getWindow()).thenReturn(window);
    return tab;
  }

  /** OBDal returning the given tabs for the table, plus a static mock of the access helper. */
  private static final class Env implements AutoCloseable {
    final OBDal dal = mock(OBDal.class);
    final MockedStatic<OBDal> obDal = mockStatic(OBDal.class);
    final MockedStatic<NeoAccessHelper> access = mockStatic(NeoAccessHelper.class);

    @SuppressWarnings("unchecked")
    Env(Tab... tabs) {
      OBCriteria<Tab> criteria = mock(OBCriteria.class);
      List<Tab> list = tabs.length == 0 ? Collections.emptyList() : Arrays.asList(tabs);
      when(criteria.list()).thenReturn(list);
      when(dal.createCriteria(Tab.class)).thenReturn(criteria);
      obDal.when(OBDal::getInstance).thenReturn(dal);
    }

    @Override
    public void close() {
      access.close();
      obDal.close();
    }
  }
}
