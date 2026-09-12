package com.openggf.game;

import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/** Host binding for effective game catalogs; does not publish a creator-facing API. */
public final class MasterTitleCatalog {
    private MasterTitleCatalog() { }

    /** Re-read the effective catalog when the mod manager returns; pending mods still require restart. */
    public static void bind(MasterTitleScreen title, Supplier<List<MasterTitleEntry>> source) {
        Objects.requireNonNull(title, "title").bindEntrySource(source);
    }
}
