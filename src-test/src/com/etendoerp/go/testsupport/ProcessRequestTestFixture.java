/* Etendo License. */
package com.etendoerp.go.testsupport;

import static org.mockito.Mockito.RETURNS_DEFAULTS;
import static org.mockito.Mockito.mock;

import java.util.HashMap;
import java.util.Map;

import org.openbravo.model.ad.ui.ProcessRequest;

/** Constructor-free scheduling records for unit tests of scheduler orchestration. */
public final class ProcessRequestTestFixture {

  private ProcessRequestTestFixture() {
  }

  /** Retains the generated scheduling accessors while generic property access stays in memory. */
  public static ProcessRequest propertyBackedRequest() {
    Map<String, Object> values = new HashMap<>();
    return mock(ProcessRequest.class, invocation -> {
      String method = invocation.getMethod().getName();
      if ("get".equals(method) && invocation.getArguments().length == 1) {
        return values.get(invocation.getArgument(0));
      }
      if ("set".equals(method) && invocation.getArguments().length == 2) {
        values.put(invocation.getArgument(0), invocation.getArgument(1));
        return null;
      }
      if (invocation.getMethod().getDeclaringClass() == ProcessRequest.class) {
        return invocation.callRealMethod();
      }
      return RETURNS_DEFAULTS.answer(invocation);
    });
  }
}
