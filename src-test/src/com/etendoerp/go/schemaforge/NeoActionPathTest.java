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

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Specs for {@link NeoActionPath}. The scope check is only sound while it classifies a path as an
 * action exactly when {@code NeoServlet} dispatches it as one, so every case asserts equality with
 * {@link NeoServletSupport#parsePath} and pins the expected answer for the edge shapes.
 *
 * @covers com.etendoerp.go.schemaforge.NeoActionPath
 */
class NeoActionPathTest {

  @ParameterizedTest(name = "[{0}] is an action route")
  @ValueSource(strings = {
      "/sales-order/header/ID1/action/documentAction",
      "sales-order/header/ID1/action/documentAction",
      "/sales-order/header/ID1/action/documentAction/",
      "/sales-order/header/ID1/action/documentAction/extra/segments",
      "/sales-order//ID1/action/documentAction",
      "/sales-order/header/ID1/action"
  })
  void actionShapesAreActions(String path) {
    assertEquals(true, NeoActionPath.isActionPath(path));
    assertEquals(NeoServletSupport.parsePath(path).isAction, NeoActionPath.isActionPath(path));
  }

  @ParameterizedTest(name = "[{0}] is not an action route")
  @NullAndEmptySource
  @ValueSource(strings = {
      "/",
      "//sales-order/header/ID1/action/documentAction",
      "/sales-order/header/ID1/ACTION/documentAction",
      "/sales-order/header/ID1/Action/documentAction",
      "/sales-order/header/selectors/action",
      "/sales-order/header/callout/action/x",
      "/sales-order/header/defaults/action/x",
      "/sales-order/header/ID1",
      "/sales-order/header",
      "/sales-order",
      "/batch"
  })
  void everythingElseIsNot(String path) {
    assertEquals(false, NeoActionPath.isActionPath(path));
    assertEquals(NeoServletSupport.parsePath(path).isAction, NeoActionPath.isActionPath(path));
  }
}
