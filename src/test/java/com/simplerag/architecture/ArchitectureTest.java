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
    void swingDoesNotDependOnRetrievalInternals() {
        noClasses().that().resideInAPackage("..adapter.in.swing..")
                .should().dependOnClassesThat().haveFullyQualifiedName("com.simplerag.model.DocumentChunk")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("com.simplerag.search.SemanticSearchEngine")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("com.simplerag.search.IndexHandle")
                .check(classes);
    }

    @Test
    void inputPortsDoNotExposeSearchImplementationTypes() {
        noClasses().that().resideInAPackage("..application.port.in..")
                .should().dependOnClassesThat().resideInAPackage("..search..")
                .check(classes);
    }

    @Test
    void onlyBackgroundTaskCoordinatorCreatesSwingWorkers() {
        noClasses().that().resideOutsideOfPackage("..adapter.in.swing..")
                .should().dependOnClassesThat().haveFullyQualifiedName("javax.swing.SwingWorker")
                .check(classes);
        noClasses().that().haveSimpleName("MainFrame")
                .should().dependOnClassesThat().haveFullyQualifiedName("javax.swing.SwingWorker")
                .check(classes);
    }

    @Test
    void retrievalPipelineDoesNotDependOnUiDatabaseOrRemoteChat() {
        noClasses().that().resideInAPackage("..search..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "..adapter.in.swing..", "..adapter.out.sqlite..", "..adapter.out.openai..")
                .check(classes);
    }

    @Test
    void useCaseImplementationsDoNotCallOtherUseCaseImplementations() {
        noClasses().that().haveSimpleNameEndingWith("UseCase")
                .should().dependOnClassesThat().haveSimpleNameEndingWith("UseCase")
                .check(classes);
    }

    @Test
    void mainFrameOnlyComposesNavigatesAndClosesTheWindow() {
        noClasses().that().haveSimpleName("MainFrame")
                .should().dependOnClassesThat().resideInAPackage("..application.dto..")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("javax.swing.SwingWorker")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("javax.swing.JFileChooser")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("javax.swing.JOptionPane")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("javax.swing.Timer")
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
