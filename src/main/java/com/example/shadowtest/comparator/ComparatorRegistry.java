package com.example.shadowtest.comparator;

import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class ComparatorRegistry {

    private final Map<String, ResponseComparator> byProduct = new HashMap<>();
    private final ResponseComparator defaultComparator;

    public ComparatorRegistry(List<ResponseComparator> all, HashResponseComparator defaultComparator) {
        this.defaultComparator = defaultComparator;
        for (ResponseComparator c : all) {
            if (c == defaultComparator) {
                continue;
            }
            if (ResponseComparator.DEFAULT_PRODUCT.equals(c.product())) {
                continue;
            }
            byProduct.put(c.product(), c);
        }
    }

    public ResponseComparator forProduct(String product) {
        return byProduct.getOrDefault(product, defaultComparator);
    }
}
