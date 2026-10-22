package vn.flashsale.order;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
class ArchitectureTest {
    @Test void innerLayersDoNotDependOnFrameworksOrAdapters() {
        var classes = new ClassFileImporter().importPackages("vn.flashsale.order");
        noClasses().that().resideInAnyPackage("..domain..", "..application..")
            .should().dependOnClassesThat().resideInAnyPackage("org.springframework..", "java.sql..", "jakarta..", "..adapter..", "..config..", "vn.flashsale.common..")
            .check(classes);
    }
}
