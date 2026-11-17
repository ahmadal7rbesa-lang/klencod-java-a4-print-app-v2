package com.klencod.a4editor;

import java.util.ArrayList;
import java.util.List;

class DocPage {
    final List<Item> items = new ArrayList<>();

    DocPage copy() {
        DocPage p = new DocPage();
        for (Item i : items) p.items.add(i.copy());
        return p;
    }
}
