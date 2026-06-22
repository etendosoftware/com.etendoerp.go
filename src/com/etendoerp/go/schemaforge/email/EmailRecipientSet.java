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

package com.etendoerp.go.schemaforge.email;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

import org.apache.commons.lang3.StringUtils;
import org.openbravo.base.exception.OBException;

/**
 * Immutable per-channel recipient set (to/cc) with normalization, cross-channel
 * dedup (to wins over cc) and a stable channel-aware SHA-256 hash.
 */
public final class EmailRecipientSet {

  public static final String CHANNEL_TO = "to";
  public static final String CHANNEL_CC = "cc";

  private final List<String> to;
  private final List<String> cc;

  private EmailRecipientSet(List<String> to, List<String> cc) {
    this.to = Collections.unmodifiableList(to);
    this.cc = Collections.unmodifiableList(cc);
  }

  /**
   * Creates a recipient set from raw per-channel address lists.
   *
   * @param to to-channel addresses
   * @param cc cc-channel addresses
   * @return normalized, cross-channel deduplicated recipient set
   */
  public static EmailRecipientSet of(List<String> to, List<String> cc) {
    List<String> normalizedTo = normalizeChannel(to);
    List<String> normalizedCc = normalizeChannel(cc);
    Set<String> toKeys = comparableKeys(normalizedTo);
    List<String> dedupedCc = new ArrayList<>();
    for (String address : normalizedCc) {
      if (!toKeys.contains(comparableKey(address))) {
        dedupedCc.add(address);
      }
    }
    return new EmailRecipientSet(normalizedTo, dedupedCc);
  }

  /**
   * Creates a single to-recipient set for single-recipient compatibility paths.
   *
   * @param recipient single to address
   * @return recipient set with one to address and no cc
   */
  public static EmailRecipientSet singleTo(String recipient) {
    return of(Collections.singletonList(recipient), Collections.emptyList());
  }

  /**
   * Returns the normalized to-channel addresses.
   *
   * @return immutable to address list
   */
  public List<String> getTo() {
    return to;
  }

  /**
   * Returns the normalized cc-channel addresses.
   *
   * @return immutable cc address list
   */
  public List<String> getCc() {
    return cc;
  }

  /**
   * Returns the total recipient count across both channels.
   *
   * @return number of to + cc recipients
   */
  public int totalCount() {
    return to.size() + cc.size();
  }

  /**
   * Indicates whether the to channel is empty.
   *
   * @return {@code true} when there is no to recipient
   */
  public boolean isToEmpty() {
    return to.isEmpty();
  }

  /**
   * Stable SHA-256 hex hash over sorted, normalized {@code channel:address} tuples.
   *
   * @return channel-aware recipient set hash
   */
  public String recipientSetHash() {
    Set<String> tuples = new TreeSet<>();
    for (String address : to) {
      tuples.add(CHANNEL_TO + ":" + comparableKey(address));
    }
    for (String address : cc) {
      tuples.add(CHANNEL_CC + ":" + comparableKey(address));
    }
    return sha256(String.join("|", tuples));
  }

  /** Normalization: trim, lower-case domain, drop blanks, dedup within channel. */
  private static List<String> normalizeChannel(List<String> values) {
    Set<String> seen = new LinkedHashSet<>();
    List<String> result = new ArrayList<>();
    if (values == null) {
      return result;
    }
    for (String value : values) {
      String normalized = normalizeAddress(value);
      if (normalized != null && seen.add(comparableKey(normalized))) {
        result.add(normalized);
      }
    }
    return result;
  }

  /**
   * Trims and lower-cases the domain part; local part case is preserved.
   *
   * @param value raw address
   * @return normalized address or {@code null} when blank
   */
  public static String normalizeAddress(String value) {
    String trimmed = StringUtils.trimToNull(value);
    if (trimmed == null) {
      return null;
    }
    int at = trimmed.lastIndexOf('@');
    if (at < 0) {
      return trimmed;
    }
    return trimmed.substring(0, at) + "@"
        + trimmed.substring(at + 1).toLowerCase(Locale.ROOT);
  }

  private static String comparableKey(String address) {
    return address.toLowerCase(Locale.ROOT);
  }

  private static Set<String> comparableKeys(List<String> addresses) {
    Set<String> keys = new LinkedHashSet<>();
    for (String address : addresses) {
      keys.add(comparableKey(address));
    }
    return keys;
  }

  static String sha256(String value) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
      StringBuilder hex = new StringBuilder(hash.length * 2);
      for (byte b : hash) {
        hex.append(String.format("%02x", b));
      }
      return hex.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new OBException("SHA-256 unavailable", e);
    }
  }
}
