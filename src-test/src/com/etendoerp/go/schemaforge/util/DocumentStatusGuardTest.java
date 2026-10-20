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

package com.etendoerp.go.schemaforge.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import org.junit.Test;
import org.openbravo.base.exception.OBException;

/**
 * Unit tests for {@link DocumentStatusGuard}.
 *
 * @covers com.etendoerp.go.schemaforge.util.DocumentStatusGuard
 */
public class DocumentStatusGuardTest {

  @Test
  public void requireCompleted_acceptsCompleted() {
    DocumentStatusGuard.requireCompleted("D1", "CO", "%s is %s");
  }

  @Test
  public void requireCompleted_refusesEveryOtherStatusNamingNumberAndStatus() {
    for (String status : new String[] {"DR", "CL", "VO", "RE", "IP", null}) {
      try {
        DocumentStatusGuard.requireCompleted("D1", status, "%s is %s");
        fail("status " + status + " must be refused");
      } catch (OBException expected) {
        assertEquals("D1 is " + status, expected.getMessage());
      }
    }
  }

  @Test
  public void requireCompleted_refusesAClosedDocumentNamingNumberAndClCode() {
    try {
      DocumentStatusGuard.requireCompleted("PO-CL-1", "CL", "%s is %s");
      fail("a closed (CL) document must be refused");
    } catch (OBException expected) {
      assertEquals("PO-CL-1 is CL", expected.getMessage());
    }
  }

  @Test
  public void requireAnyStatus_refusesAClosedDocumentWhenOnlyUeAndCoPass() {
    try {
      DocumentStatusGuard.requireAnyStatus("Q-1", "CL", "%s is %s", "UE", "CO");
      fail("a closed (CL) document must be refused");
    } catch (OBException expected) {
      assertEquals("Q-1 is CL", expected.getMessage());
    }
  }

  @Test
  public void requireStatus_acceptsOnlyTheRequiredStatusNamingNumberAndStatus() {
    DocumentStatusGuard.requireStatus("D1", "UE", "UE", "%s is %s");
    for (String status : new String[] {"DR", "CO", "RJ", "ETGO_CI", "CA", null}) {
      try {
        DocumentStatusGuard.requireStatus("D1", status, "UE", "%s is %s");
        fail("status " + status + " must be refused");
      } catch (OBException expected) {
        assertEquals("D1 is " + status, expected.getMessage());
      }
    }
  }

  @Test
  public void requireAnyStatus_acceptsAnyListedStatusAndRefusesTheRest() {
    DocumentStatusGuard.requireAnyStatus("D1", "UE", "%s is %s", "UE", "CO");
    DocumentStatusGuard.requireAnyStatus("D1", "CO", "%s is %s", "UE", "CO");
    for (String status : new String[] {"DR", "RJ", "ETGO_CI", null}) {
      try {
        DocumentStatusGuard.requireAnyStatus("D1", status, "%s is %s", "UE", "CO");
        fail("status " + status + " must be refused");
      } catch (OBException expected) {
        assertEquals("D1 is " + status, expected.getMessage());
      }
    }
  }
}
