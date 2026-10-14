/*
 *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"), you may not use this file except in compliance
 * with the License.
 * You may obtain a copy of the License at
 * https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * Software distributed under the License is distributed on an
 * "AS IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing rights
 * and limitations under the License.
 * All portions are Copyright (C) 2021-2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 *************************************************************************
 */
package com.etendoerp.go.schemaforge;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.openbravo.base.exception.OBException;
import org.openbravo.model.financialmgmt.accounting.coa.AcctSchema;
import org.openbravo.model.financialmgmt.calendar.Period;
import org.openbravo.module.taxreportlauncher.erpCommon.ad_reports.OBTL_TaxReport_I;

/**
 * {@code GET /fiscal390/generate} (ETP-5716): the AEAT Modelo 390 TXT file, produced by the
 * classic report class of the resolved {@code TaxReport} — the same reflective
 * {@code OBTL_TaxReport_I#generateElectronicFile} call {@link Fiscal303SubmissionSupport} makes
 * for Modelo 303.
 *
 * <p>Unlike 303/349, the classic 390 classes do not return the content under {@code "file"} but
 * a set of TEMPORARY files under {@code "files"}: the TXT, an informative CSV and, when the
 * report's {@code GenerateXML} is {@code Y}, an XML. This class picks the {@code .txt}, reads it
 * (classic writes it in UTF-8), deletes every returned file, and serves the content through the
 * shared {@link AbstractFiscalHandler#writeGeneratedFile}, which encodes it in ISO-8859-1 like
 * every other AEAT flat file.
 *
 * <p>The download name is built here ({@code 390_0A_<exercise>.txt}) — never from the
 * {@code TaxReport} name, which carries the LAYOUT year and would read e.g. "v2025" on a 2026
 * declaration generated with the fallback report. The resolved report is reported in the
 * {@code X-Fiscal-Tax-Report} / {@code X-Fiscal-Layout-Year} response headers.
 */
class Fiscal390GenerateSupport {

  static final String HEADER_TAX_REPORT = "X-Fiscal-Tax-Report";
  static final String HEADER_LAYOUT_YEAR = "X-Fiscal-Layout-Year";

  /** Query params this endpoint consumes itself — never forwarded as AEAT390 inputs. */
  private static final Set<String> STRUCTURAL_PARAMS = Set.of("year",
      AbstractFiscalHandler.PERIOD_KEY, AbstractFiscalHandler.SINCE_KEY, "id", "FileName");

  private final Fiscal390BoxesHandler owner;

  Fiscal390GenerateSupport(Fiscal390BoxesHandler owner) {
    this.owner = owner;
  }

  /** The download base name: model, annual period and EXERCISE year. */
  static String fileName(int year) {
    return Fiscal390BoxesHandler.MODEL + "_" + AbstractFiscalHandler.ANNUAL_PERIOD + "_" + year;
  }

  void handleGenerate(String orgId, int year, Map<String, String> inputs,
      HttpServletResponse response) throws Exception {
    AbstractFiscalHandler.ResolvedTaxReport taxReport = owner.resolveTaxReport390(orgId, year);
    AcctSchema acctSchema = owner.resolveAcctSchema();
    List<Period> periods = owner.resolvePeriods(orgId, year, AbstractFiscalHandler.ANNUAL_PERIOD);
    String fileName = fileName(year);

    Map<String, String> inputParams = new HashMap<>(inputs);
    inputParams.put("FileName", fileName);

    OBTL_TaxReport_I report = newReport(taxReport.report.getJavaClassName());
    Map<String, Object> result = report.generateElectronicFile(orgId, taxReport.report.getId(),
        acctSchema.getId(), periods.get(0).getYear().getId(),
        periods.stream().map(Period::getId).collect(Collectors.joining(",")),
        inputParams);
    String content;
    try {
      content = readTxtContent(result);
    } finally {
      deleteReturnedFiles(result);
    }

    response.setHeader(HEADER_TAX_REPORT, taxReport.searchKey);
    response.setHeader(HEADER_LAYOUT_YEAR, String.valueOf(taxReport.layoutYear));
    HashMap<String, Object> single = new HashMap<>();
    single.put("file", content);
    owner.writeGeneratedFile(single, fileName + ".txt", response);
  }

  /** Instantiates the classic report class. Package-private so tests can replace it. */
  OBTL_TaxReport_I newReport(String className) throws ReflectiveOperationException {
    return (OBTL_TaxReport_I) Class.forName(className).getDeclaredConstructor().newInstance();
  }

  /**
   * The TXT content of a classic 390 result: the {@code .txt} of {@code "files"} read as UTF-8,
   * or a plain {@code "file"} string should a report class ever return the 303 shape.
   *
   * @throws OBException when the result carries no TXT
   */
  static String readTxtContent(Map<String, Object> result) throws IOException {
    Object inline = result.get("file");
    if (inline != null) {
      return inline.toString();
    }
    for (File f : returnedFiles(result)) {
      if (f.getName().toLowerCase(Locale.ROOT).endsWith(".txt")) {
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
      }
    }
    throw new OBException("The Modelo 390 report returned no TXT file");
  }

  /** Deletes every temporary file of the result; a failure is logged, never thrown. */
  static void deleteReturnedFiles(Map<String, Object> result) {
    for (File f : returnedFiles(result)) {
      try {
        Files.deleteIfExists(f.toPath());
      } catch (IOException | SecurityException e) {
        AbstractFiscalHandler.log.warn("Could not delete Modelo 390 temporary file " + f, e);
      }
    }
  }

  private static List<File> returnedFiles(Map<String, Object> result) {
    Object files = result.get("files");
    if (!(files instanceof Collection)) {
      return List.of();
    }
    return ((Collection<?>) files).stream()
        .filter(File.class::isInstance)
        .map(File.class::cast)
        .collect(Collectors.toList());
  }

  /**
   * The request's AEAT390 inputs: every non-structural, non-blank query parameter, verbatim
   * (first value). Like 303's generate, the parameter names are the classic launcher's search
   * keys, owned by the frontend — no list of them is duplicated here.
   */
  static Map<String, String> requestInputs(HttpServletRequest request) {
    Map<String, String> inputs = new LinkedHashMap<>();
    Map<String, String[]> params = request.getParameterMap();
    if (params == null) {
      return inputs;
    }
    for (Map.Entry<String, String[]> e : params.entrySet()) {
      String[] values = e.getValue();
      String value = values != null && values.length > 0 ? values[0] : null;
      if (!STRUCTURAL_PARAMS.contains(e.getKey()) && StringUtils.isNotBlank(value)) {
        inputs.put(e.getKey(), value);
      }
    }
    return inputs;
  }
}
