def junit5UpgradeProcessors = [
            new TestMethodConverter(),
            new MethodAnnotationReplacementProcessor(Before.class, BeforeEach.class),
            new MethodAnnotationReplacementProcessor(After.class, AfterEach.class),
            new MethodAnnotationReplacementProcessor(BeforeClass.class, BeforeAll.class),
            new MethodAnnotationReplacementProcessor(AfterClass.class, AfterAll.class),
            new MethodAnnotationReplacementProcessor(Ignore.class, Disabled.class),
            new RuleProcessor(),
            new RunWithProcessor(),
]

class TestMethodConverter extends MethodAnnotationReplacementProcessor {
    CtTypeReference junitAssert
    CtTypeReference frameworkAssert

    TestMethodConverter() {
        super(Test.class, org.junit.jupiter.api.Test.class)
    }

    @Override
    void init() {
        super.init()
        junitAssert = factory.createCtTypeReference(org.junit.Assert.class)
        frameworkAssert = factory.createCtTypeReference(junit.framework.Assert.class)
    }

    @Override
    void onMatch(CtMethod method, CtAnnotation originalAnnotation, CtAnnotation replacementAnnotation) {
        def wildcardImport = factory.createTypeMemberWildcardImportReference(factory.createCtTypeReference(Assertions.class))
        factory.CompilationUnit().getOrCreate(method.getDeclaringType()).getImports().add(factory.createImport(wildcardImport))
        def expectedException = originalAnnotation.getValue("expected")
        if (expectedException.toString() != 'Test.None.class') {
            def newBody = factory.createBlock()
            newBody.insertBegin(createAssert('assertThrows', method.getFactory(),
                                             [expectedException, factory.createLambda().setBody(method.body)]))
            method.setBody(newBody)
            println "  wrapping in assertThrows(${expectedException}, () -> ...)"
        }

        method.getElements(new TypeFilter(CtInvocation.class)).stream()
              .filter { isAssert(it) }
              .forEach { CtInvocation assertInvocation ->
            println "  converting $assertInvocation to junit5"

            def args = assertInvocation.arguments
            def assertName = assertInvocation.executable.simpleName
            // TODO: swap arg order for other asserts with a message
            if (assertName ==~ /assert(?:Not)?(?:Equals|Same)/ && args.size() == 3 && args[0].type.simpleName == 'String') {
                args.add(args[0])
                args.removeAt(0)
            }

            if (assertName == 'assertThat') {
                def replacementAssert = SpoonUtils.createInvocation(factory, true, MatcherAssert.class, assertName, args)
                assertInvocation.setExecutable(replacementAssert.executable)
                assertInvocation.setTarget(replacementAssert.target)
            } else if(args == null || args.isEmpty()) {
                def replacementAssert = createAssert(assertName, assertInvocation.getFactory())
                assertInvocation.setExecutable(replacementAssert.executable)
                assertInvocation.setTarget(replacementAssert.target)
            } else {
                def replacementAssert = createAssert(assertName, assertInvocation.getFactory(), args)
                assertInvocation.setExecutable(replacementAssert.executable)
                assertInvocation.setTarget(replacementAssert.target)
                assertInvocation.setArguments(replacementAssert.arguments)
            }
        }
    }

    static CtInvocation createAssert(String name, Factory factory, List<CtExpression> parameters = []) {
        return SpoonUtils.createInvocation(factory, true, Assertions.class, name, parameters)
    }

    protected boolean isAssert(CtInvocation invocation) {
        try {
            def type = invocation.getExecutable().getDeclaringType()
            return type == junitAssert || type == frameworkAssert
        } catch (Exception e) {
            return false
        }

    }
}


class RuleProcessor extends AbstractModificationTrackingProcessor<CtField> {
    @Override
    void process(CtField element) {
        if (element.getAnnotation(Rule.class) != null || element.getAnnotation(ClassRule.class) != null) {
            factory.Annotation().annotate(element.parent, EnableRuleMigrationSupport.class)
            markChanged(element)
        }
    }
}


class RunWithProcessor extends AbstractModificationTrackingProcessor<CtClass> {
    static def SPRING_TEST_ANNOTATIONS = [
            'org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest',
            'org.springframework.boot.test.context.SpringBootTest',
            'org.springframework.boot.test.autoconfigure.json.JsonTest',
            'org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest',
            'org.springframework.boot.test.autoconfigure.jdbc.JdbcTest',
            'org.springframework.boot.test.autoconfigure.data.mongo.DataMongoTest',
            'org.springframework.boot.test.autoconfigure.data.redis.DataRedisTest'
    ]

    static def SPRING_EXTENSION = 'org.springframework.test.context.junit.jupiter.SpringExtension'

    @Override
    void process(CtClass element) {
        def runWith = element.getAnnotation(factory.createCtTypeReference(RunWith.class))
        if (runWith != null) {
            element.removeAnnotation(runWith)
            markChanged(element)
            println "removing @RunWith annotation from $element.simpleName"
            if (!SPRING_TEST_ANNOTATIONS.any { element.getAnnotation(factory.Type().createReference(it)) != null }) {
                // TODO add ExtendWith
                if (runWith.getValue('value').toString().contains('SpringRunner')) {
                    println "  adding @ExtendWith(SpringExtension.class) annotation to $element.simpleName"
                    factory.Annotation().annotate(element, ExtendWith.class,
                            'value', factory.Code().createTypeAccess(factory.Type().createReference(SPRING_EXTENSION)))
                }
            }

        }
    }
}