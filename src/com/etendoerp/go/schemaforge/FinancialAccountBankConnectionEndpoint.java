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

import com.etendoerp.go.schemaforge.data.SFSpec;
import com.etendoerp.go.schemaforge.util.NeoReportCallability;

/**
 * The SPA's {@code financial-account-bank-connection} endpoint, called from the account's bank
 * connection actions the way REST calls it (ETP-5804).
 *
 * <p>Same bridge as {@link FinancialAccountTransactionsEndpoint}: the spec's customization is
 * resolved by its {@code Java_Qualifier} and run through
 * {@link NeoServletSupport#handleWithDefaultStep}, so an {@code @NeoExtension} or a replaced bean
 * serves the agent exactly as it serves the SPA. The SPA's own request does not pass here.</p>
 */
final class FinancialAccountBankConnectionEndpoint {

  /** The report spec the SPA's treasury screens call. */
  static final String SPEC = "financial-account-bank-connection";

  static final String MSG_NOT_CONFIGURED =
      "The financial-account-bank-connection endpoint is not configured";

  private FinancialAccountBankConnectionEndpoint() {
  }

  /**
   * Run the endpoint for one request.
   *
   * @param context the request, as the SPA would send it; {@code mcpOrigin} picks the channel
   * @return the endpoint's answer; a 500 when the spec or its customization is missing, which is
   *         what REST answers as "not configured"
   */
  static NeoResponse call(NeoContext context) {
    SFSpec spec = NeoServletSupport.findSpec(SPEC);
    String qualifier = spec == null ? null
        : NeoReportCallability.resolveReportHandlerQualifier(spec);
    return NeoServletSupport.handleWithDefaultStep(qualifier, context,
        ctx -> NeoResponse.error(500, MSG_NOT_CONFIGURED),
        context.isMcpOrigin() ? NeoExtensionChannel.MCP : NeoExtensionChannel.REST_SINGLE);
  }
}
