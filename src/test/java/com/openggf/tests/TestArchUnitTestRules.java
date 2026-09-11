package com.openggf.tests;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.junit.CacheMode;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Bytecode-level architectural invariants for test classes.
 */
@AnalyzeClasses(
        packages = "com.openggf",
        cacheMode = CacheMode.FOREVER,
        importOptions = ImportOption.OnlyIncludeTests.class)
class TestArchUnitTestRules {

    private static final String JUNIT4_ROOT = "org." + "junit";

    @ArchTest
    static final ArchRule tests_do_not_reference_junit4_apis =
            noClasses().that().resideInAPackage("com.openggf..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            JUNIT4_ROOT,
                            JUNIT4_ROOT + ".experimental..",
                            JUNIT4_ROOT + ".rules..",
                            JUNIT4_ROOT + ".runner..",
                            JUNIT4_ROOT + ".runners..");
}
