package com.openggf.mods;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.Set;

/** Mutable manager-session editor for pending state only. */
public final class PendingModStateEditor {
    private final ModState startup;
    private final Set<String> editableIds;
    private final Map<String, ModDescriptor> descriptorsById;
    private final ModStateStore store;
    private ModState pending;
    private ModState saved;

    public PendingModStateEditor(ModState startup, List<? extends ModCatalogEntry> scanned,
                                 ModStateStore store) {
        Objects.requireNonNull(startup, "startup");
        Objects.requireNonNull(scanned, "scanned");
        this.store = Objects.requireNonNull(store, "store");
        this.startup = startup.normalize(scanned);
        this.pending = this.startup;
        this.saved = this.startup;
        Set<String> ids = new HashSet<>();
        Map<String, ModDescriptor> descriptors = new LinkedHashMap<>();
        for (ModCatalogEntry entry : scanned) {
            if (entry instanceof ModDescriptor descriptor) {
                ids.add(descriptor.manifest().id());
                descriptors.putIfAbsent(descriptor.manifest().id(), descriptor);
            }
        }
        editableIds = Set.copyOf(ids);
        descriptorsById = Map.copyOf(descriptors);
    }

    public ModState pendingState() {
        return pending;
    }

    public boolean restartRequired() {
        return !pending.equals(startup);
    }

    public void enable(String id) {
        setEnabled(id, true);
    }

    public void disable(String id) {
        setEnabled(id, false);
    }

    public void setEnabled(String id, boolean enabled) {
        requireEditable(id);
        replaceEnabled(Set.of(id), enabled);
    }

    public void setEnabledCascade(Collection<String> ids, boolean enabled) {
        Set<String> requested = new HashSet<>(Objects.requireNonNull(ids, "ids"));
        requested.forEach(this::requireEditable);
        replaceEnabled(requested, enabled);
    }

    public void trust(String id) {
        requireEditable(id);
        ModDescriptor descriptor = descriptorsById.get(id);
        if (descriptor == null || !descriptor.containsCode()) {
            throw new IllegalArgumentException("Trust grants require a scanned code mod: " + id);
        }
        List<ModState.Entry> entries = new ArrayList<>(pending.entries().size());
        for (ModState.Entry entry : pending.entries()) {
            entries.add(entry.id().equals(id)
                    ? new ModState.Entry(entry.id(), entry.enabled(), entry.order(), true,
                    descriptor.sha256()) : entry);
        }
        pending = new ModState(ModState.CURRENT_FORMAT_VERSION, entries);
    }

    public void move(String id, int targetIndex) {
        requireEditable(id);
        List<ModState.Entry> entries = new ArrayList<>(pending.entries());
        if (targetIndex < 0 || targetIndex >= entries.size()) {
            throw new IllegalArgumentException("Target order is outside pending state: " + targetIndex);
        }
        ModState.Entry moved = entries.stream().filter(entry -> entry.id().equals(id))
                .findFirst().orElseThrow();
        entries.remove(moved);
        entries.add(targetIndex, moved);
        pending = withContiguousOrder(entries);
    }

    public void resetToStartup() {
        pending = startup;
    }

    public boolean dirty() { return !pending.equals(saved); }

    public void discardDraft() { pending = saved; }

    public ModStateSaveResult save() {
        ModStateSaveResult result = store.save(pending);
        if (result instanceof ModStateSaveResult.Saved) saved = pending;
        return result;
    }

    private void replaceEnabled(Set<String> ids, boolean enabled) {
        List<ModState.Entry> entries = new ArrayList<>(pending.entries().size());
        for (ModState.Entry entry : pending.entries()) {
            entries.add(ids.contains(entry.id())
                    ? new ModState.Entry(entry.id(), enabled, entry.order(),
                    entry.trusted(), entry.trustedJarSha256()) : entry);
        }
        pending = new ModState(ModState.CURRENT_FORMAT_VERSION, entries);
    }

    private static ModState withContiguousOrder(List<ModState.Entry> entries) {
        List<ModState.Entry> ordered = new ArrayList<>(entries.size());
        for (int index = 0; index < entries.size(); index++) {
            ModState.Entry entry = entries.get(index);
            ordered.add(new ModState.Entry(entry.id(), entry.enabled(), index,
                    entry.trusted(), entry.trustedJarSha256()));
        }
        return new ModState(ModState.CURRENT_FORMAT_VERSION, ordered);
    }

    private void requireEditable(String id) {
        Objects.requireNonNull(id, "id");
        if (!editableIds.contains(id)) {
            throw new IllegalArgumentException("Unknown scanned mod id: " + id);
        }
    }
}
