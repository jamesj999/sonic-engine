package com.openggf.mods;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

public final class ModApiVersion {
    /**
     * Current unpublished compiled-mod API candidate, including the 0.7
     * widescreen presentation contracts and deferred SMPS header construction.
     */
    public static final SemanticVersion CURRENT = SemanticVersion.parse("0.7.0");
    public static final List<SemanticVersion> SUPPORTED_CONTRACTS = List.of(CURRENT);

    public static boolean supports(VersionRange range) {
        return supports(range, SUPPORTED_CONTRACTS);
    }

    public static String supportedContractsDiagnostic() {
        return SUPPORTED_CONTRACTS.toString();
    }

    static boolean supports(VersionRange range, Collection<SemanticVersion> contracts) {
        Objects.requireNonNull(range, "range");
        return normalizedContracts(contracts).stream().anyMatch(range::contains);
    }

    static String supportedContractsDiagnostic(Collection<SemanticVersion> contracts) {
        return normalizedContracts(contracts).toString();
    }

    private static List<SemanticVersion> normalizedContracts(Collection<SemanticVersion> contracts) {
        ArrayList<SemanticVersion> normalized = new ArrayList<>(
                Objects.requireNonNull(contracts, "contracts"));
        if (normalized.stream().anyMatch(Objects::isNull)) {
            throw new NullPointerException("contracts contains null");
        }
        normalized.sort(SemanticVersion::compareTo);
        return normalized.stream().distinct().toList();
    }

    private ModApiVersion() {
    }
}
