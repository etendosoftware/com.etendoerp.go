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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;

import javax.servlet.http.HttpServletResponse;

import org.junit.Test;
import org.mockito.MockedStatic;

import com.etendoerp.go.schemaforge.data.SFSpec;
import com.etendoerp.go.schemaforge.util.NeoDiscoveryHelper;

/** Tests for {@link NeoDiscoveryHandler}. */
public class NeoDiscoveryHandlerTest {

  @Test
  public void handleDiscoveryDelegatesToHelper() throws Exception {
    NeoServlet servlet = mock(NeoServlet.class);
    NeoDiscoveryHandler handler = new NeoDiscoveryHandler(servlet);
    HttpServletResponse response = mock(HttpServletResponse.class);
    NeoResponse expected = new NeoResponse(200, null);

    try (MockedStatic<NeoDiscoveryHelper> helperMock = mockStatic(NeoDiscoveryHelper.class)) {
      helperMock.when(NeoDiscoveryHelper::handleDiscovery).thenReturn(expected);

      handler.handleDiscovery(response);

      verify(servlet).writeResponse(response, expected);
    }
  }

  @Test
  public void handleSpecDescribeDelegatesToHelper() throws Exception {
    NeoServlet servlet = mock(NeoServlet.class);
    NeoDiscoveryHandler handler = new NeoDiscoveryHandler(servlet);
    HttpServletResponse response = mock(HttpServletResponse.class);
    SFSpec spec = mock(SFSpec.class);
    NeoResponse expected = new NeoResponse(200, null);

    try (MockedStatic<NeoDiscoveryHelper> helperMock = mockStatic(NeoDiscoveryHelper.class)) {
      helperMock.when(() -> NeoDiscoveryHelper.handleSpecDescribe(spec)).thenReturn(expected);

      handler.handleSpecDescribe(response, spec);

      verify(servlet).writeResponse(response, expected);
    }
  }
}
