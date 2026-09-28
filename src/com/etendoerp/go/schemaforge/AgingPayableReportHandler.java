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
 * All portions are Copyright © 2021–2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 * *************************************************************************
 */

package com.etendoerp.go.schemaforge;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import javax.inject.Named;

import com.etendoerp.go.schemaforge.util.NeoAccessHelper;
import com.etendoerp.go.schemaforge.util.NeoReportParam;

/**
 * Payables-only sibling of {@link AgingReportHandler} (ETP-5483).
 *
 * <p>Classic Etendo exposes "Aging Balance Process Definition for Receivables" and "...for
 * Payables" as two separate reports with two separate process grants (see
 * {@code AgingReportHandler#AGING_RECEIVABLE_PROCESS_ID}/{@code AGING_PAYABLE_PROCESS_ID}'s
 * javadoc for the confirmed {@code AD_Menu} FK chain). Before this handler, the MCP catalog only
 * mirrored the receivables side as its own tool ({@code generate_aging_receivable}) and served
 * payables through the same handler via a {@code recOrPay} body switch — asymmetric with Classic
 * and easy to call wrong (nothing stopped a caller of the payables tool from omitting {@code
 * recOrPay} and silently getting receivables data back).
 *
 * <p>This class fixes that for the new {@code generate_aging_payable} tool: it always serves the
 * payables side regardless of anything in the request body, and does not advertise {@code
 * recOrPay} at all — there is nothing to choose. It extends {@link AgingReportHandler} rather
 * than duplicating its query/formatting logic, overriding only the three things that legitimately
 * differ between the two reports: which side is served ({@link #forcedRecOrPay()}), which OBUIAPP
 * process gates access ({@link #isAccessibleForCurrentRole()}), and the declared parameter
 * contract/self-description ({@link #reportParameters()}, {@link #reportName()}, {@link
 * #reportDescription()}).
 *
 * <p>The existing {@code agingReportHandler} bean (backing {@code generate_aging_receivable})
 * keeps honoring {@code recOrPay} in its request body for backward compatibility: the Schema
 * Forge SPA's {@code artifacts/aging-payable/report-contract.json} may still be deployed pointing
 * at {@code /sws/neo/aging-receivable} with {@code recOrPay: "PAYABLES"} until both repos are
 * redeployed together, since this Java module and the Schema Forge SPA ship independently.
 *
 * <p>Registered under its own {@code @Named} qualifier deliberately — {@code @Named} is not
 * {@code @Inherited}, so a subclass that relied on the parent's annotation would not be
 * resolvable by {@code WeldUtils.getInstances(NeoHandler.class)}/{@code NeoHandlerLookup} at all,
 * and must NOT be {@code @ApplicationScoped} (or any other normal scope) for the same reason —
 * see {@code docs/neo-headless-extensibility.md} §2.2.
 */
@Named("agingPayableReportHandler")
public class AgingPayableReportHandler extends AgingReportHandler {

  /**
   * Always PAYABLES — this handler never serves receivables data, no matter what a caller puts
   * in the request body (there being no {@code recOrPay} parameter in its contract to put it in,
   * per {@link #reportParameters()}).
   *
   * @return {@link AgingReportHandler#REC_OR_PAY_PAYABLES}, always
   */
  @Override
  protected String forcedRecOrPay() {
    return REC_OR_PAY_PAYABLES;
  }

  /**
   * Gates on the payables OBUIAPP process only — a role granted just the receivables side must
   * not see this tool in the catalog, matching Classic's two-separate-grants model.
   *
   * @return {@code true} when the current role holds the payables aging-report grant
   */
  @Override
  public boolean isAccessibleForCurrentRole() {
    return NeoAccessHelper.hasObuiappProcessAccess(AGING_PAYABLE_PROCESS_ID);
  }

  /**
   * Same contract as {@link AgingReportHandler#reportParameters()} minus {@code recOrPay} — this
   * report only ever serves one side, so there is nothing to select.
   *
   * @return the declared parameter list, without {@code recOrPay}
   */
  @Override
  public Optional<List<NeoReportParam>> reportParameters() {
    return super.reportParameters().map(params -> params.stream()
        .filter(p -> !PARAM_REC_OR_PAY.equals(p.getName()))
        .collect(Collectors.toList()));
  }

  @Override
  protected String reportName() {
    return "Aging of Payables";
  }

  @Override
  protected String reportDescription() {
    return "Aging schedule for payables, grouped by business partner";
  }
}
