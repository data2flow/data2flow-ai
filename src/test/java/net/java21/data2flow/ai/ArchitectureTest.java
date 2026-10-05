package net.java21.data2flow.ai;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import net.java21.data2flow.contracts.test.arch.Data2flowArchRules;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/** 공통 ArchUnit 규칙(testing/backend.md §6) + AI 경로에서 제어 명령·배포를 직접 부르지 않는다(BR-AIA-07) */
@AnalyzeClasses(packages = "net.java21.data2flow.ai", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule organizationScoped = Data2flowArchRules.REPOSITORY_QUERIES_ARE_ORGANIZATION_SCOPED;
    @ArchTest
    static final ArchRule noUnscopedCrud = Data2flowArchRules.UNSCOPED_CRUD_LOOKUPS_ARE_NOT_CALLED;
    @ArchTest
    static final ArchRule noSleep = Data2flowArchRules.NO_THREAD_SLEEP;
    @ArchTest
    static final ArchRule noSystemClock = Data2flowArchRules.NO_SYSTEM_CLOCK;

    /** BR-AIA-07: ai는 action(제어 창구)을 부르지 않는다. 제어 명령 계약 타입도 쓰지 않는다 */
    @ArchTest
    static final ArchRule noControlPath = noClasses().that().resideInAPackage("net.java21.data2flow.ai..")
            .should().dependOnClassesThat().resideInAnyPackage("net.java21.data2flow.contracts.command..", "net.java21.data2flow.action..")
            .because("AI 경로에서 제어 명령을 직접 실행하는 길은 없다(BR-AIA-07)");
}
