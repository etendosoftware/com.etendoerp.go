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
package com.etendoerp.go.onboarding;

import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The GOClient master-data rows that are demo content: they belong to the sample client seeded by
 * {@code install.source}, but must never reach a freshly onboarded tenant.
 *
 * <p>Named ids rather than a rule, because these are constants of a curated dataset — there is no
 * column that tells demo master data apart from the rows a tenant genuinely needs. The internal
 * {@code ETGO_DTO} discount product and its {@code Discounts} category, for instance, live in the
 * same two files and must be kept: {@code TotalDiscountService} resolves them at runtime.
 *
 * <p><b>Why this exists at all.</b> The bundled dataset has two consumers with opposite needs, and
 * a single source:
 *
 * <ul>
 *   <li>{@code install.source} → {@code import.sample.data} reads all 121 files and creates the
 *       GOClient sample client, which is expected to have sample products, financial accounts and
 *       warehouses to demo with;</li>
 *   <li>onboarding reads a 40-table subset of the same files through the classpath to seed a new
 *       tenant, which must be born without any of it.</li>
 * </ul>
 *
 * <p>ETP-5079 first served the second consumer by deleting these rows from the source. That broke
 * the first one: the transactional chain that references them (orders, invoices, shipments,
 * inventory, costing, payments, {@code FACT_ACCT}, {@code AD_TREENODE}) stayed in the dataset, so
 * {@code ImportSampledata} re-enabled the foreign keys over ~394 dangling references and
 * {@code ./gradlew install} died in {@code enableAllFK} on {@code C_BPARTNER_FIN_FINACC}. None of
 * that transactional chain reaches a tenant — every one of those tables is either absent from
 * {@link OnboardingDatasetDefinition#getIncludedTables()} or listed in its excluded set — so the
 * orphan problem was exclusively install's. Hence the split enforced here: the source dataset stays
 * complete, and the onboarding consumer drops these rows at import time.
 *
 * @see OnboardingDatasetNormalizer
 */
final class OnboardingDemoMasterData {

  /** "Caja", "Tarjeta" and "Cuenta de Banco" — a tenant creates its own financial accounts. */
  static final Set<String> FINANCIAL_ACCOUNT_IDS = Set.of(
      "06DB0ED44F25406C9214064C1399E446",
      "16A5ED3050764FFCBB8C5058CFE707D4",
      "5521767A6D3C47E1957AF82D1334BFE4"
  );

  /**
   * The four sample products (SK-001 Agua, SK-002 Cerveza, SK-003 Fernet, SK-004 Queso Sardo). The
   * fifth product the file ships, {@code ETGO_DTO}, is deliberately absent from this set: it is
   * internal infrastructure for inline discounts, not sample data.
   */
  static final Set<String> PRODUCT_IDS = Set.of(
      "EC67CB536A504743B489946CD7E469B1",
      "88AD998AD10841F99D4F605014113EE3",
      "D627916D3A9141438A7B383364452E12",
      "97290BE7328B48769A365DFF1002FA55"
  );

  /** "AS" / "Almacén Secundario" — a tenant is born with a single warehouse. */
  static final Set<String> WAREHOUSE_IDS = Set.of("081A28467A2948529BB65C902289AFDF");

  /** {@code AS-0-0-0}, the secondary warehouse's only locator. */
  static final Set<String> LOCATOR_IDS = Set.of("1D7531261BCD44969FFB479692A26D70");

  /**
   * "Beverages" (es_ES "Bebidas"), the category the three drink samples are filed under. The
   * starter category ({@code EBAE46FD…}, "Generic") and the system-flagged {@code Discounts}
   * category are kept.
   */
  static final Set<String> PRODUCT_CATEGORY_IDS = Set.of("0953563B605F4CD29A65A87F6F7F48B7");

  /** Every demo master-data id, for tests that assert the source dataset still defines them all. */
  static final Set<String> ALL = Stream
      .of(FINANCIAL_ACCOUNT_IDS, PRODUCT_IDS, WAREHOUSE_IDS, LOCATOR_IDS, PRODUCT_CATEGORY_IDS)
      .flatMap(Set::stream)
      .collect(Collectors.toUnmodifiableSet());

  private OnboardingDemoMasterData() {
  }
}
