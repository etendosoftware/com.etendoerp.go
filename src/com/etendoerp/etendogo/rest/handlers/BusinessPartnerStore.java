package com.etendoerp.etendogo.rest.handlers;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.etendoerp.etendogo.rest.contract.model.BusinessPartner;

public class BusinessPartnerStore {
  private static final BusinessPartnerStore INSTANCE = new BusinessPartnerStore();

  private final Map<String, BusinessPartner> store = new LinkedHashMap<>();
  private int sequence = 0;

  private BusinessPartnerStore() {
  }

  public static BusinessPartnerStore getInstance() {
    return INSTANCE;
  }

  public synchronized String nextId() {
    return String.valueOf(++sequence);
  }

  public synchronized void put(String id, BusinessPartner bp) {
    store.put(id, bp);
  }

  public synchronized Optional<BusinessPartner> get(String id) {
    return Optional.ofNullable(store.get(id));
  }

  public synchronized List<BusinessPartner> getAll() {
    return new ArrayList<>(store.values());
  }

  public synchronized boolean remove(String id) {
    return store.remove(id) != null;
  }

  public synchronized boolean contains(String id) {
    return store.containsKey(id);
  }
}
