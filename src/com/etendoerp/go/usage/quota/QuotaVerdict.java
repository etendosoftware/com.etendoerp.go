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

package com.etendoerp.go.usage.quota;

/**
 * ETP-5051 — the answer of the quota evaluator for one quota. A quota that is not evaluated (mode
 * {@code off}, or a subscription with no current billing period) has no verdict at all, which
 * callers carry as {@code null}, never as {@link #ALLOW}.
 */
public enum QuotaVerdict {
  /** Under the quota and under its warning threshold. */
  ALLOW,
  /** At or over the warning threshold, or over a quota in mode {@code warn}. */
  WARN,
  /** At or over a quota in mode {@code block}. */
  DENY
}
