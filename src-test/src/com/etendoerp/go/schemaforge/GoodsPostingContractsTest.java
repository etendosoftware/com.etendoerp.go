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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.materialmgmt.transaction.ShipmentInOut;

/**
 * {@link GoodsPostingContracts#checkUnpost}: the per-entity refusal of an {@code unpost} of a goods
 * document that is not posted (ETP-5714). The four goods headers call it before the posting
 * service, which keeps its handled 200 for every other table (ETP-5445).
 *
 * @covers com.etendoerp.go.schemaforge.GoodsPostingContracts
 */
@DisplayName("Goods unpost gate (ETP-5714)")
class GoodsPostingContractsTest {

  private static NeoContext unpost(String id) {
    return NeoContext.builder().httpMethod("POST").endpointType(NeoEndpointType.ACTION)
        .fieldName("unpost").recordId(id).build();
  }

  /** Runs {@code body} with DAL answering {@code document} for record {@code r-1}. */
  private static NeoResponse check(NeoContext context, ShipmentInOut document) {
    try (MockedStatic<OBContext> ignored = mockStatic(OBContext.class);
        MockedStatic<OBDal> dal = mockStatic(OBDal.class)) {
      OBDal obDal = mock(OBDal.class);
      dal.when(OBDal::getInstance).thenReturn(obDal);
      when(obDal.get(ShipmentInOut.class, "r-1")).thenReturn(document);
      return GoodsPostingContracts.checkUnpost(context);
    }
  }

  private static ShipmentInOut posted(String posted) {
    ShipmentInOut document = mock(ShipmentInOut.class);
    when(document.getPosted()).thenReturn(posted);
    return document;
  }

  @Test
  @DisplayName("a document that is not posted (N, D, NC or null) is refused with 422 and its message key")
  void notPostedIsRefused() {
    for (String state : new String[] { "N", "D", "NC", null }) {
      NeoResponse refusal = check(unpost("r-1"), posted(state));

      assertEquals(422, refusal.getHttpStatus(), String.valueOf(state));
      assertTrue(refusal.getBody().toString().contains(GoodsPostingContracts.MSG_UNPOST_NOT_POSTED));
    }
  }

  @Test
  @DisplayName("a posted document, another action, another endpoint or an unreadable record delegates")
  void everythingElseDelegates() {
    assertNull(check(unpost("r-1"), posted("Y")));
    assertNull(check(unpost("r-1"), null));
    assertNull(check(NeoContext.builder().httpMethod("POST").endpointType(NeoEndpointType.ACTION)
        .fieldName("post").recordId("r-1").build(), posted("N")));
    assertNull(check(NeoContext.builder().httpMethod("GET").endpointType(NeoEndpointType.CRUD)
        .recordId("r-1").build(), posted("N")));
    assertNull(check(unpost(" "), posted("N")));
    assertNull(GoodsPostingContracts.checkUnpost(null));
  }
}
