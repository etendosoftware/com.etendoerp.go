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

package com.etendoerp.go.usage;

import java.util.LinkedHashMap;
import java.util.Map;

/** What one aggregation run did, for the process result message and the log. */
public final class UsageAggregationResult {

  private int daysProcessed;
  private int resourcesProcessed;
  private int rowsWritten;
  private int resourcesFailed;
  /**
   * Search key of every resource that failed, against the first reason it gave. Ordered and
   * de-duplicated: a resource failing on four days of a six-day window is one entry, not four,
   * because the operator has one thing to fix, and the first failure is the one that explains
   * it. Counting alone was not enough -- "3 resource-day(s) failed" tells nobody which.
   */
  private final Map<String, String> failures = new LinkedHashMap<>();

  /** Records that one more day was processed. */
  public void addDay() {
    daysProcessed++;
  }

  /** Records that one more resource-day was attempted. */
  public void addResource() {
    resourcesProcessed++;
  }

  /**
   * Adds the rows written by one resource-day to the run total.
   *
   * @param rows how many usage rows were written
   */
  public void addRows(int rows) {
    rowsWritten += rows;
  }

  /**
   * Records a failed resource-day, keeping only the first reason per resource.
   *
   * @param searchKey search key of the failing resource; may be null
   * @param reason the failure message; may be null
   */
  public void addFailure(String searchKey, String reason) {
    resourcesFailed++;
    failures.putIfAbsent(searchKey == null ? "(unknown resource)" : searchKey,
        reason == null ? "no reason reported" : reason);
  }

  /** Search key to first reason, for every resource that failed. Empty on a clean run. */
  public Map<String, String> getFailures() {
    return failures;
  }

  /** The failed resources by name, for a message an operator can act on. */
  public String getFailedResourceNames() {
    return String.join(", ", failures.keySet());
  }

  public int getDaysProcessed() {
    return daysProcessed;
  }

  public int getResourcesProcessed() {
    return resourcesProcessed;
  }

  public int getRowsWritten() {
    return rowsWritten;
  }

  public int getResourcesFailed() {
    return resourcesFailed;
  }

  /**
   * Resource-days that completed and committed. On a partial run this is the number that
   * matters most: it says how much of the range is already written, and therefore how much a
   * re-run has left to do.
   */
  public int getResourcesSucceeded() {
    return resourcesProcessed - resourcesFailed;
  }

  @Override
  public String toString() {
    return "Processed " + daysProcessed + " day(s) over " + resourcesProcessed
        + " resource-day(s): " + getResourcesSucceeded() + " succeeded"
        + (resourcesFailed > 0 ? ", " + resourcesFailed + " failed" : "")
        + ", wrote " + rowsWritten + " usage row(s)";
  }
}
