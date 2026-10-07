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
package com.etendoerp.go.schemaforge.selector.meta;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * One suggestion-box search fragment of a selector, together with where it came from.
 *
 * <p>The origin decides how the fragment is qualified with the query alias. A fragment taken from
 * {@code SelectorField.property} (or synthesised by the searchable fallback) is a DAL path relative
 * to the queried entity, such as {@code product.name}: it must always be prefixed with the alias.
 * A fragment taken from {@code clause_left_part} is raw HQL that may carry its own alias, such as
 * {@code bp.name}. Telling them apart by looking for a dot is not possible, since both may contain
 * one (ETP-5670).
 */
public final class SearchableFragment {
  /** The fragment text, as stored in the selector metadata. */
  public final String expression;
  /** {@code true} when the fragment is a DAL path relative to the queried entity. */
  public final boolean relativePath;

  private SearchableFragment(String expression, boolean relativePath) {
    this.expression = expression;
    this.relativePath = relativePath;
  }

  /**
   * Create a fragment for a DAL path relative to the queried entity.
   *
   * @param path DAL property path, e.g. {@code product.name}
   * @return the fragment
   */
  public static SearchableFragment relativePath(String path) {
    return new SearchableFragment(path, true);
  }

  /**
   * Create a fragment for a raw HQL {@code clause_left_part}, kept as written.
   *
   * @param hql HQL fragment, e.g. {@code bp.name}
   * @return the fragment
   */
  public static SearchableFragment clauseLeftPart(String hql) {
    return new SearchableFragment(hql, false);
  }

  /**
   * Extract the fragment texts, in order.
   *
   * @param fragments searchable fragments
   * @return their expressions
   */
  public static List<String> expressions(List<SearchableFragment> fragments) {
    List<String> result = new ArrayList<>();
    for (SearchableFragment fragment : fragments) {
      result.add(fragment.expression);
    }
    return result;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof SearchableFragment)) {
      return false;
    }
    SearchableFragment other = (SearchableFragment) o;
    return relativePath == other.relativePath && Objects.equals(expression, other.expression);
  }

  @Override
  public int hashCode() {
    return Objects.hash(expression, relativePath);
  }

  @Override
  public String toString() {
    return (relativePath ? "path:" : "clause:") + expression;
  }
}
