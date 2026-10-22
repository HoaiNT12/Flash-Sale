package vn.flashsale.auth.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

class CleanArchitectureTest {
    @Test
    void domainAndApplicationDoNotDependOnFrameworksTransportOrOuterLayers() {
        var classes = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("vn.flashsale.auth");
        noClasses().that().resideInAnyPackage("..domain..", "..application..")
                .should().dependOnClassesThat().resideInAnyPackage("org.springframework..", "java.sql..", "javax.sql..",
                        "java.net.http..", "jakarta.servlet..", "jakarta.validation..", "com.fasterxml.jackson..",
                        "org.apache.http..", "org.apache.hc..", "vn.flashsale.common..", "..infrastructure..", "..adapter..")
                .because("authentication policy is independent of persistence, Spring Security and HTTP adapters")
                .check(classes);
    }
}
