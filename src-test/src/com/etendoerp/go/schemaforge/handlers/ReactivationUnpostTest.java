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

package com.etendoerp.go.schemaforge.handlers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;

import org.codehaus.jettison.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.base.model.Property;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.datamodel.Table;
import org.openbravo.model.ad.ui.Tab;

import com.etendoerp.go.schemaforge.NeoContext;
import com.etendoerp.go.schemaforge.NeoResponse;
import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.util.NeoButtonActionHelper;

/**
 * The server-side un-post a reactivation runs first (ETP-5684): only a posted record is un-posted,
 * a failed un-post is the answer, and the result says what ran.
 *
 * @covers com.etendoerp.go.schemaforge.handlers.ReactivationUnpost
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReactivationUnpostTest {

  private static final String TABLE_ID = "318";
  private static final String RECORD_ID = "INV-1";

  private MockedStatic<OBContext> obContextMock;
  private MockedStatic<OBDal> obDalMock;
  private MockedStatic<ModelProvider> modelMock;
  private BaseOBObject row;
  private DocumentPostingService posting;
  private NeoContext context;
  private final Map<String, Object> attributes = new HashMap<>();

  @BeforeEach
  void setUp() {
    obContextMock = mockStatic(OBContext.class);
    obDalMock = mockStatic(OBDal.class);
    OBDal obDal = mock(OBDal.class);
    obDalMock.when(OBDal::getInstance).thenReturn(obDal);
    row = mock(BaseOBObject.class);
    when(obDal.get("Invoice", RECORD_ID)).thenReturn(row);

    modelMock = mockStatic(ModelProvider.class);
    ModelProvider provider = mock(ModelProvider.class);
    modelMock.when(ModelProvider::getInstance).thenReturn(provider);
    Entity dal = mock(Entity.class);
    when(dal.getName()).thenReturn("Invoice");
    Property posted = mock(Property.class);
    when(posted.getName()).thenReturn("posted");
    when(dal.getPropertyByColumnName("Posted", false)).thenReturn(posted);
    when(provider.getEntityByTableId(TABLE_ID)).thenReturn(dal);

    Table table = mock(Table.class);
    when(table.getId()).thenReturn(TABLE_ID);
    Tab tab = mock(Tab.class);
    when(tab.getTable()).thenReturn(table);
    context = mock(NeoContext.class);
    when(context.getAdTab()).thenReturn(tab);
    when(context.getRecordId()).thenReturn(RECORD_ID);
    doAnswer(inv -> attributes.put(inv.getArgument(0), inv.getArgument(1)))
        .when(context).setAttribute(anyString(), any());
    when(context.getAttribute(anyString())).thenAnswer(inv -> attributes.get(inv.getArgument(0)));

    posting = mock(DocumentPostingService.class);
  }

  @AfterEach
  void tearDown() {
    obContextMock.close();
    obDalMock.close();
    modelMock.close();
  }

  @Test
  void aRecordThatIsNotPostedIsLeftAlone() {
    when(row.get("posted")).thenReturn("N");

    assertNull(ReactivationUnpost.unpostFirst(context, posting));
    verify(posting, never()).unpost(anyString(), anyString());
    assertTrue(attributes.isEmpty());
    assertFalse(ReactivationUnpost.ran(context));
  }

  @Test
  void aPostedRecordIsUnpostedAndTheReactivationGoesOn() {
    when(row.get("posted")).thenReturn("Y");
    when(posting.unpost(TABLE_ID, RECORD_ID))
        .thenReturn(new DocumentPostingService.PostResult(true, "Unposted (3 entries removed)"));

    assertNull(ReactivationUnpost.unpostFirst(context, posting));
    assertEquals(Boolean.TRUE, attributes.get(ReactivationUnpost.ATTRIBUTE_UNPOSTED));
    assertTrue(ReactivationUnpost.ran(context));
  }

  @Test
  void aRoleThatCannotRunTheReactivationGetsNoUnpost() {
    when(row.get("posted")).thenReturn("Y");
    SFEntity entity = mock(SFEntity.class);
    when(context.getSfEntity()).thenReturn(entity);
    when(context.getFieldName()).thenReturn("DocAction");
    NeoResponse forbidden = NeoResponse.error(403, "Access denied to process for current role");
    try (MockedStatic<NeoButtonActionHelper> helper = mockStatic(NeoButtonActionHelper.class)) {
      helper.when(() -> NeoButtonActionHelper.refusalForButtonProcess(entity, "DocAction"))
          .thenReturn(forbidden);

      assertSame(forbidden, ReactivationUnpost.unpostFirst(context, posting));
    }
    verify(posting, never()).unpost(anyString(), anyString());
    assertFalse(ReactivationUnpost.ran(context));
  }

  @Test
  void aFailedUnpostIsTheAnswer() {
    when(row.get("posted")).thenReturn("Y");
    DocumentPostingService.PostResult failure =
        new DocumentPostingService.PostResult(false, "Period closed");
    when(posting.unpost(TABLE_ID, RECORD_ID)).thenReturn(failure);
    NeoResponse refusal = NeoResponse.error(422, "Period closed");
    when(posting.toResponse(failure, RECORD_ID)).thenReturn(refusal);

    assertSame(refusal, ReactivationUnpost.unpostFirst(context, posting));
    assertTrue(attributes.isEmpty());
  }

  @Test
  void noPostingServiceChangesNothing() {
    assertNull(ReactivationUnpost.unpostFirst(context, null));
  }

  @Test
  void aSuccessfulReactivationListsBothSteps() throws Exception {
    attributes.put(ReactivationUnpost.ATTRIBUTE_UNPOSTED, Boolean.TRUE);
    NeoResponse ok = NeoResponse.ok(new JSONObject().put("message", "Reactivated"));
    when(context.getPreviousResult()).thenReturn(ok);

    ReactivationUnpost.notePartial(context);

    assertEquals("[\"unpost\",\"reactivate\"]",
        ok.getBody().getJSONArray(ReactivationUnpost.KEY_STEPS).toString());
    assertFalse(ok.getBody().has(ReactivationUnpost.KEY_PARTIAL));
  }

  @Test
  void aRefusedReactivationSaysTheUnpostStays() throws Exception {
    attributes.put(ReactivationUnpost.ATTRIBUTE_UNPOSTED, Boolean.TRUE);
    NeoResponse refused = new NeoResponse(400, new JSONObject().put("message", "Has payments"));
    when(context.getPreviousResult()).thenReturn(refused);

    ReactivationUnpost.notePartial(context);

    assertEquals(ReactivationUnpost.PARTIAL_NOTE,
        refused.getBody().getString(ReactivationUnpost.KEY_PARTIAL));
    assertEquals("[\"unpost\"]",
        refused.getBody().getJSONArray(ReactivationUnpost.KEY_STEPS).toString());
  }

  @Test
  void withoutAnUnpostTheResultIsUntouched() throws Exception {
    NeoResponse ok = NeoResponse.ok(new JSONObject().put("message", "Reactivated"));
    when(context.getPreviousResult()).thenReturn(ok);

    ReactivationUnpost.notePartial(context);

    assertFalse(ok.getBody().has(ReactivationUnpost.KEY_STEPS));
  }
}
