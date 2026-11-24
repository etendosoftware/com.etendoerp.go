/*
 * *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"), you may not use this file except in compliance with
 * the License.
 * You may obtain a copy of the License at
 * https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * Software distributed under the License  is  distributed  on  an  "AS IS"
 * basis, WITHOUT WARRANTY OF ANY KIND, either express or implied. See the
 * License for the specific language governing rights and limitations under
 * the License.
 * All portions are Copyright © 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;
import org.openbravo.base.secureApp.VariablesSecureApp;
import org.openbravo.client.kernel.RequestContext;
import org.openbravo.dal.core.OBContext;

import com.etendoerp.go.schemaforge.data.SFEntity;
import com.etendoerp.go.schemaforge.data.SFSpec;

/**
 * Unit tests for {@link NeoCustomizedSelectorCheck} (ETP-6377): the write check asks the entity's
 * own SELECTOR customization, through the same pre/post hook chain the REST selector runs, and
 * leaves the caller's request context as it found it. ETP-6365 — {@code query}: the same chain
 * answers the customization's own list for a field, searched by {@code q}, for the MCP name
 * resolution.
 *
 * @covers com.etendoerp.go.schemaforge.NeoCustomizedSelectorCheck
 */
@RunWith(MockitoJUnitRunner.Silent.class)
public class NeoCustomizedSelectorCheckTest {

  private static final String FIELD = "paymentMethod";
  private static final String VALUE = "PM-1";
  private static final Map<String, String> CONTEXT = Map.of("IsSOTrx", "Y");

  private MockedStatic<RequestContext> requestContextStatic;
  private MockedStatic<OBContext> obContextStatic;
  private MockedStatic<NeoExtensionDispatcher> dispatcherStatic;
  private RequestContext requestContext;
  private HttpServletRequest originalRequest;
  private VariablesSecureApp originalVars;
  private SFEntity entity;
  private NeoHandler customization;
  /** Pre-hook answers, one per page; the post hook answers null (keeps the pre answer). */
  private Deque<NeoResponse> preAnswers;
  private NeoResponse postAnswer;
  private final List<NeoExtensionRequest> dispatched = new ArrayList<>();

  @Before
  public void setUp() {
    requestContextStatic = mockStatic(RequestContext.class);
    obContextStatic = mockStatic(OBContext.class);
    dispatcherStatic = mockStatic(NeoExtensionDispatcher.class);

    requestContext = mock(RequestContext.class);
    originalRequest = mock(HttpServletRequest.class);
    originalVars = mock(VariablesSecureApp.class);
    requestContextStatic.when(RequestContext::get).thenReturn(requestContext);
    when(requestContext.getRequest()).thenReturn(originalRequest);
    when(requestContext.getVariablesSecureApp()).thenReturn(originalVars);
    obContextStatic.when(OBContext::getOBContext).thenReturn(mock(OBContext.class));

    SFSpec spec = mock(SFSpec.class);
    when(spec.getName()).thenReturn("sales-quotation");
    entity = mock(SFEntity.class);
    when(entity.getETGOSFSpec()).thenReturn(spec);
    when(entity.getName()).thenReturn("quotation");

    customization = mock(NeoHandler.class);
    preAnswers = new java.util.ArrayDeque<>();
    postAnswer = null;
    dispatcherStatic.when(() -> NeoExtensionDispatcher.dispatch(any())).thenAnswer(inv -> {
      NeoExtensionRequest request = inv.getArgument(0);
      dispatched.add(request);
      if (request.phase() == NeoExtensionRequest.Phase.POST) {
        return new NeoExtensionResult(customization, postAnswer, null);
      }
      NeoResponse answer = preAnswers.isEmpty() ? null : preAnswers.poll();
      return new NeoExtensionResult(answer == null ? null : customization, answer, null);
    });
  }

  @After
  public void tearDown() {
    requestContextStatic.close();
    obContextStatic.close();
    dispatcherStatic.close();
    Mockito.framework().clearInlineMocks();
  }

  /** Without an entity there is no customization to ask, and the request context is untouched. */
  @Test
  public void withoutAnEntityTheGenericSelectorDecides() {
    assertEquals(NeoCustomizedSelectorCheck.Answer.NOT_CUSTOMIZED,
        NeoCustomizedSelectorCheck.offers(null, FIELD, VALUE, CONTEXT));
    verify(requestContext, never()).setRequest(any());
  }

  /** A customization that declines the field leaves the decision to the generic selector. */
  @Test
  public void aCustomizationThatDeclinesTheFieldLeavesItToTheGenericSelector() {
    assertEquals(NeoCustomizedSelectorCheck.Answer.NOT_CUSTOMIZED, offers());
    assertRequestContextRestored();
  }

  /** The value is offered when the customization lists it; the post hook still runs. */
  @Test
  public void aListedValueIsOfferedAndThePostHookRuns() throws Exception {
    preAnswers.add(page(false, VALUE, "PM-2"));

    assertEquals(NeoCustomizedSelectorCheck.Answer.OFFERED, offers());
    assertEquals(NeoExtensionRequest.Phase.POST, dispatched.get(1).phase());
    assertEquals(NeoExtensionSurface.SELECTOR, dispatched.get(0).surface());
    assertRequestContextRestored();
  }

  /** A complete list without the value refuses it. */
  @Test
  public void aCompleteListWithoutTheValueRefusesIt() throws Exception {
    preAnswers.add(page(false, "PM-2"));

    assertEquals(NeoCustomizedSelectorCheck.Answer.NOT_OFFERED, offers());
  }

  /** The post hook's answer replaces the pre hook's, as on the REST selector. */
  @Test
  public void thePostHookAnswerWins() throws Exception {
    preAnswers.add(page(false, VALUE));
    postAnswer = page(false, "PM-2");

    assertEquals(NeoCustomizedSelectorCheck.Answer.NOT_OFFERED, offers());
  }

  /** An error from the customization cannot vouch for the value. */
  @Test
  public void anErrorAnswerIsUnverifiable() {
    preAnswers.add(NeoResponse.error(422, "Unable to determine payment method direction"));

    assertEquals(NeoCustomizedSelectorCheck.Answer.UNVERIFIABLE, offers());
  }

  /** The list is read page by page; the synthetic request carries the record and the page. */
  @Test
  public void theNextPageIsReadWhenTheValueIsNotOnTheFirst() throws Exception {
    preAnswers.add(page(true, "PM-2"));
    preAnswers.add(page(false, VALUE));

    assertEquals(NeoCustomizedSelectorCheck.Answer.OFFERED, offers());

    ArgumentCaptor<HttpServletRequest> requests = ArgumentCaptor.forClass(HttpServletRequest.class);
    verify(requestContext, times(3)).setRequest(requests.capture());
    HttpServletRequest second = requests.getAllValues().get(1);
    assertEquals(String.valueOf(NeoCustomizedSelectorCheck.PAGE_SIZE),
        second.getParameter("offset"));
    assertEquals("Y", second.getParameter("IsSOTrx"));
    assertEquals(originalRequest, requests.getAllValues().get(2));
  }

  /** A customization that stops answering after the first page cannot vouch for the value. */
  @Test
  public void aLaterPageWithNoAnswerIsUnverifiable() throws Exception {
    preAnswers.add(page(true, "PM-2"));

    assertEquals(NeoCustomizedSelectorCheck.Answer.UNVERIFIABLE, offers());
  }

  /** A list that never ends within the page budget cannot vouch for the value. */
  @Test
  public void aListLongerThanThePageBudgetIsUnverifiable() throws Exception {
    for (int i = 0; i < NeoCustomizedSelectorCheck.MAX_PAGES; i++) {
      preAnswers.add(page(true, "PM-2"));
    }

    assertEquals(NeoCustomizedSelectorCheck.Answer.UNVERIFIABLE, offers());
  }

  // ── ETP-6365: query — the customization's own list, searched ──────────────

  /**
   * The customization's list for the field, searched by {@code q}, on one page of the requested
   * size, through the pre/post hook chain; the context travels as parameters and the caller's
   * request context is restored.
   */
  @Test
  public void queryAnswersTheCustomizationsListSearchedOnOnePage() throws Exception {
    NeoResponse listed = page(false, VALUE);
    preAnswers.add(listed);

    assertEquals(listed, NeoCustomizedSelectorCheck.query(entity, FIELD, "CHF", 25, CONTEXT));

    assertEquals("the pre and the post hook run", 2, dispatched.size());
    Map<String, String> params = dispatched.get(0).context().getQueryParams();
    assertEquals("CHF", params.get("q"));
    assertEquals("25", params.get("limit"));
    assertEquals("0", params.get("offset"));
    assertEquals("Y", params.get("IsSOTrx"));
    assertEquals(FIELD, dispatched.get(0).context().getFieldName());
    assertRequestContextRestored();
  }

  /** A blank search sends no {@code q}; no customization, or no entity, answers {@code null}. */
  @Test
  public void queryWithoutSearchOrCustomization() throws Exception {
    assertNull(NeoCustomizedSelectorCheck.query(entity, FIELD, "  ", 10, CONTEXT));
    assertNull(dispatched.get(0).context().getQueryParams().get("q"));
    assertRequestContextRestored();

    assertNull(NeoCustomizedSelectorCheck.query(null, FIELD, "CHF", 10, CONTEXT));
    assertNull(NeoCustomizedSelectorCheck.query(entity, " ", "CHF", 10, CONTEXT));
    assertEquals("no dispatch without an entity or a field", 1, dispatched.size());
  }

  private NeoCustomizedSelectorCheck.Answer offers() {
    return NeoCustomizedSelectorCheck.offers(entity, FIELD, VALUE, CONTEXT);
  }

  private void assertRequestContextRestored() {
    verify(requestContext).setRequest(originalRequest);
    verify(requestContext).setVariableSecureApp(originalVars);
  }

  private static NeoResponse page(boolean hasMore, String... ids) throws Exception {
    JSONArray items = new JSONArray();
    for (String id : ids) {
      items.put(new JSONObject().put("id", id));
    }
    return NeoResponse.ok(new JSONObject().put("items", items).put("hasMore", hasMore));
  }
}
