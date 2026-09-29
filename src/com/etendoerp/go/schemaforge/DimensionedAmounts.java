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

import java.math.BigDecimal;

/**
 * The debit/credit pair plus the four accounting-dimension names that every {@code fact_acct}
 * line-grain carrier of {@link GeneralLedgerGrouping} and {@link JournalEntriesGrouping} holds
 * ({@code Row}, {@code AccountTotal}, {@code Line}). Pure data — no DB, no OBContext, no JSON —
 * like the grouping classes themselves.
 *
 * <p>Two ways in, each preserving the defaulting rule its callers always had: from a {@link
 * FieldsBuilder} a {@code null} amount becomes {@link BigDecimal#ZERO} (every builder-built input
 * row did this), while the copy constructor takes the source's values as-is (every output line was
 * copied field by field from an already-defaulted input row).
 */
class DimensionedAmounts {

  final BigDecimal amtacctdr;
  final BigDecimal amtacctcr;
  final String bpname;
  final String productname;
  final String projectname;
  final String costcentername;

  DimensionedAmounts(FieldsBuilder<?> b) {
    this.amtacctdr = b.amtacctdr == null ? BigDecimal.ZERO : b.amtacctdr;
    this.amtacctcr = b.amtacctcr == null ? BigDecimal.ZERO : b.amtacctcr;
    this.bpname = b.bpname;
    this.productname = b.productname;
    this.projectname = b.projectname;
    this.costcentername = b.costcentername;
  }

  DimensionedAmounts(DimensionedAmounts source) {
    this.amtacctdr = source.amtacctdr;
    this.amtacctcr = source.amtacctcr;
    this.bpname = source.bpname;
    this.productname = source.productname;
    this.projectname = source.projectname;
    this.costcentername = source.costcentername;
  }

  /**
   * Fluent setters for the shared fields; each concrete builder extends this with its own fields
   * (self-typed, so a chain keeps returning the concrete builder).
   *
   * @param <B> the concrete builder type
   */
  abstract static class FieldsBuilder<B extends FieldsBuilder<B>> {
    private BigDecimal amtacctdr;
    private BigDecimal amtacctcr;
    private String bpname;
    private String productname;
    private String projectname;
    private String costcentername;

    /** @return {@code this}, typed as the concrete builder */
    abstract B self();

    B amtacctdr(BigDecimal v) {
      this.amtacctdr = v;
      return self();
    }

    B amtacctcr(BigDecimal v) {
      this.amtacctcr = v;
      return self();
    }

    B bpname(String v) {
      this.bpname = v;
      return self();
    }

    B productname(String v) {
      this.productname = v;
      return self();
    }

    B projectname(String v) {
      this.projectname = v;
      return self();
    }

    B costcentername(String v) {
      this.costcentername = v;
      return self();
    }
  }
}
