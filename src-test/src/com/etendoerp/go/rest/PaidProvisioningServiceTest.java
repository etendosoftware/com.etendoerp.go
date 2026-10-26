/*
 * *************************************************************************
 * Etendo License. See https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * *************************************************************************
 */
package com.etendoerp.go.rest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;

import com.etendoerp.go.onboarding.OnboardingProgressSink;
import com.etendoerp.go.rest.PaidProvisioningService.Outcome;
import com.etendoerp.go.rest.PaidProvisioningService.PaidInput;
import com.etendoerp.go.rest.PaidProvisioningService.Status;
import com.etendoerp.go.schemaforge.data.Account;
import com.etendoerp.go.schemaforge.data.CheckoutRequest;

/**
 * Specs for {@link PaidProvisioningService}: how the onboarding inputs of a paid purchase are
 * resolved without the browser that would have sent them — the checkout column, then the
 * account's {@code ONBOARDING_DRAFT}, then {@code en_US} / {@code EUR} / {@code ES}; the company
 * address and fiscal identifier from the draft — and that the
 * run is handed to the servlet's shared onboarding with the caller's context restored after.
 *
 * <p>The fenced claim and the run itself are {@link EtendoGoJwtServlet#provisionPaidPurchase}'s;
 * see {@code EtendoGoJwtServletCoverageTest}.
 *
 * @covers com.etendoerp.go.rest.PaidProvisioningService
 */
public class PaidProvisioningServiceTest {

  private static final String DRAFT = "{\"step\":2,\"form\":{\"language\":\"fr_FR\","
      + "\"currency\":\"GBP\",\"countryCode\":\"FR\",\"fullName\":\"Ana Pérez\"}}";

  private static CheckoutRequest request(String language, String currency, String country,
      String draft) {
    Account account = mock(Account.class);
    when(account.getId()).thenReturn("ACCOUNT-1");
    when(account.getName()).thenReturn("Ana");
    when(account.getOnboardingDraft()).thenReturn(draft);
    CheckoutRequest request = mock(CheckoutRequest.class);
    when(request.getRequest()).thenReturn("req-1");
    when(request.getEtendoGoAccount()).thenReturn(account);
    when(request.getAccountEmail()).thenReturn("ana@example.test");
    when(request.getClientName()).thenReturn("Acme");
    when(request.getLanguage()).thenReturn(language);
    when(request.getCurrency()).thenReturn(currency);
    when(request.getCountry()).thenReturn(country);
    return request;
  }

  @Test
  public void theRecordedColumnsWinOverTheDraft() {
    PaidInput input = PaidProvisioningService.inputOf(request("es_ES", "USD", "AR", DRAFT));

    assertEquals("es_ES", input.language());
    assertEquals("USD", input.currencyIso());
    assertEquals("AR", input.countryCode());
    assertEquals("req-1", input.requestId());
    assertEquals("ACCOUNT-1", input.accountId());
    assertEquals("ana@example.test", input.accountEmail());
    assertEquals("Acme", input.clientName());
    assertEquals("Ana Pérez", input.fullName());
  }

  @Test
  public void anEmptyColumnFallsBackToTheAccountDraft() {
    PaidInput input = PaidProvisioningService.inputOf(request(null, " ", null, DRAFT));

    assertEquals("fr_FR", input.language());
    assertEquals("GBP", input.currencyIso());
    assertEquals("FR", input.countryCode());
  }

  @Test
  public void withoutAColumnOrADraftTheOnboardingDefaultsApply() {
    PaidInput input = PaidProvisioningService.inputOf(request(null, null, null, null));

    assertEquals("en_US", input.language());
    assertEquals("EUR", input.currencyIso());
    assertEquals("ES", input.countryCode());
    assertNull(input.fullName());
  }

  @Test
  public void anUnreadableOrPartialDraftFallsBackFieldByField() {
    assertEquals("en_US", PaidProvisioningService.inputOf(request(null, null, null,
        "not json")).language());

    PaidInput partial = PaidProvisioningService.inputOf(request(null, null, "PT",
        "{\"form\":{\"language\":7,\"currency\":null,\"countryCode\":\"FR\"}}"));
    assertEquals("A non-text draft value is ignored", "en_US", partial.language());
    assertEquals("EUR", partial.currencyIso());
    assertEquals("The column still wins", "PT", partial.countryCode());
  }

  /** S4 — the company data the browser would have sent comes from the draft, as given. */
  @Test
  public void theDraftAddressAndFiscalIdAreCarriedOver() {
    PaidInput input = PaidProvisioningService.inputOf(request("es_ES", "EUR", "ES",
        "{\"form\":{\"address\":\" Calle Mayor 1 \",\"fiscalIdValue\":\"12345678Z\"}}"));

    assertEquals("Calle Mayor 1", input.address());
    assertEquals("12345678Z", input.taxId());
  }

  @Test
  public void withoutADraftThereIsNoAddressOrFiscalId() {
    PaidInput input = PaidProvisioningService.inputOf(request("es_ES", "EUR", "ES", null));

    assertNull(input.address());
    assertNull(input.taxId());
  }

  @Test
  public void aRowWithoutAnAccountHasNoInputs() {
    CheckoutRequest request = mock(CheckoutRequest.class);

    assertNull(PaidProvisioningService.inputOf(request));
  }

  // ── provision() ─────────────────────────────────────────────────────────────────────────

  private static Outcome provision(CheckoutRequest found, EtendoGoJwtServlet runner,
      AtomicInteger runnersMade, OBContext previous) {
    @SuppressWarnings("unchecked")
    OBCriteria<CheckoutRequest> criteria = mock(OBCriteria.class, RETURNS_SELF);
    when(criteria.uniqueResult()).thenReturn(found);
    OBDal dal = mock(OBDal.class);
    when(dal.createCriteria(CheckoutRequest.class)).thenReturn(criteria);
    try (MockedStatic<OBDal> dalStatic = mockStatic(OBDal.class);
        MockedStatic<OBContext> context = mockStatic(OBContext.class)) {
      dalStatic.when(OBDal::getInstance).thenReturn(dal);
      context.when(OBContext::getOBContext).thenReturn(previous);
      Outcome outcome = new PaidProvisioningService(() -> {
        runnersMade.incrementAndGet();
        return runner;
      }).provision("req-1", mock(OnboardingProgressSink.class));
      context.verify(() -> OBContext.setOBContext(previous), org.mockito.Mockito.atLeastOnce());
      return outcome;
    }
  }

  @Test
  public void anUnknownRequestNeedsReviewAndRunsNothing() {
    EtendoGoJwtServlet runner = mock(EtendoGoJwtServlet.class);
    AtomicInteger runnersMade = new AtomicInteger();

    Outcome outcome = provision(null, runner, runnersMade, mock(OBContext.class));

    assertEquals(Status.NEEDS_REVIEW, outcome.status());
    assertEquals("ACCOUNT_MISSING", outcome.code());
    assertNull(outcome.attempt());
    assertEquals(0, runnersMade.get());
    verify(runner, never()).provisionPaidPurchase(any(), any());
  }

  @Test
  public void aKnownRequestIsHandedToTheSharedOnboardingAndItsOutcomeReturned() {
    EtendoGoJwtServlet runner = mock(EtendoGoJwtServlet.class);
    Outcome provisioned = new Outcome(Status.PROVISIONED, null, "Environment created", 2L);
    when(runner.provisionPaidPurchase(any(), any())).thenReturn(provisioned);

    Outcome outcome = provision(request("es_ES", "EUR", "ES", null), runner, new AtomicInteger(),
        mock(OBContext.class));

    assertSame(provisioned, outcome);
    ArgumentCaptor<PaidInput> input = ArgumentCaptor.forClass(PaidInput.class);
    verify(runner).provisionPaidPurchase(input.capture(), any());
    assertEquals("req-1", input.getValue().requestId());
    assertEquals("es_ES", input.getValue().language());
  }
}
