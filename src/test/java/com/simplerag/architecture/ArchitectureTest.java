package com.simplerag.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

class ArchitectureTest {
    private final JavaClasses classes = new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .importPackages("com.simplerag");

    @Test
    void applicationDoesNotDependOnConcreteAdaptersOrSwing() {
        noClasses().that().resideInAPackage("..application..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "..adapter..", "..repository..", "..embedding..", "..ui..")
                .check(classes);
    }

    @Test
    void swingDoesNotReachIntoSqliteIndexStoreOrOnnx() {
        noClasses().that().resideInAnyPackage("..ui..", "..adapter.in.swing..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "..repository..", "..embedding..", "..adapter.out..")
                .check(classes);
    }

    @Test
    void infrastructureIsOnlyAssembledByBootstrap() {
        noClasses().that().resideOutsideOfPackages("..bootstrap..", "..adapter.out..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "..adapter.out.sqlite..", "..adapter.out.onnx..", "..adapter.out.openai..",
                        "..adapter.out.filesystem..", "..adapter.out.security..")
                .check(classes);
    }
}
