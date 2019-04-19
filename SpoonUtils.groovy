import java.lang.annotation.Annotation
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

import spoon.MavenLauncher
import spoon.reflect.code.CtComment
import spoon.reflect.code.CtExpression
import spoon.reflect.code.CtInvocation
import spoon.reflect.declaration.CtClass
import spoon.reflect.declaration.CtElement
import spoon.reflect.declaration.CtField
import spoon.reflect.factory.Factory
import spoon.reflect.reference.CtExecutableReference
import spoon.reflect.visitor.DefaultJavaPrettyPrinter
import spoon.support.sniper.SniperJavaPrettyPrinter

import org.junit.jupiter.api.Assertions

class SpoonUtils {
    static MavenLauncher getMavenLauncher(Path projectDir, MavenLauncher.SOURCE_TYPE type, boolean sniper) {
        MavenLauncher launcher = new MavenLauncher(projectDir.toString(),
                type == MavenLauncher.SOURCE_TYPE.TEST_SOURCE ? MavenLauncher.SOURCE_TYPE.ALL_SOURCE : type)

        launcher.environment.autoImports = true

        launcher.environment.prettyPrinterCreator = {
            def printer = sniper ? new SniperJavaPrettyPrinter(launcher.environment)
                                 : new DefaultJavaPrettyPrinter(launcher.environment)

            def elementPrinter = new CustomPrinterHelper(printer.printerTokenWriter, printer, printer.env)
            DefaultJavaPrettyPrinter.metaClass.setProperty(printer, 'elementPrinterHelper', elementPrinter)
            return printer
        }
        def srcType = type == MavenLauncher.SOURCE_TYPE.TEST_SOURCE ? 'test' : 'main'
        launcher.sourceOutputDirectory = projectDir.resolve("target/spoon/${srcType}/java").toFile()
        return launcher
    }

    static void copyChangedFiles(Path projectDir, MavenLauncher launcher) {
        Set<File> changedFiles = launcher.getProcessors()
                .collect { it instanceof AbstractModificationTrackingProcessor ? it.changedFiles : [] }
                .flatten() as Set

        println "--- changed files ---"
        changedFiles.each { println it }

        def spoonDir = projectDir.resolve('target/spoon')
        if (!Files.exists(spoonDir)) {
            println "spoon directory doesn't exist!"
            return
        }
        Files.walk(spoonDir).forEach { f ->
            if (!Files.isRegularFile(f) || f.text.isBlank()) {
                return
            }

            def originalFile = projectDir.resolve('src').resolve(spoonDir.relativize(f))
            if (changedFiles.contains(originalFile.toFile().getAbsoluteFile().getCanonicalFile())) {
                println "copying $f to $originalFile"
                originalFile.text = fixAssertThrows(fixWhitespace(f.text))
            }
            Files.delete(f)
        }
    }

    /*
    we had an issue where the SniperJavaPrettyPrinter decided to leave off the start of the lambda declaration for some
    reason so we're using a regex to try to fix it after the fact
    */
    static String fixAssertThrows(List<String> lines) {
        return lines.collect {
            def m = it =~ /\s*assertThrows\s*\(\s*\w+\.class,(\s*\(\s*\)\s*->\s*\{)?\s*/
            if (m && m.group(1) == null) {
                return it + ' () -> {'
            }
            return it
        }.join('\n')
    }

    /*
    fixes an issue with the SniperJavaPrettyPrinter where it wasn't properly indenting added annotations on methods
    */
    static List<String> fixWhitespace(String file) {
        def resultLines = []
        file.readLines().each {
            def m = it =~ /\{(\S+)/
            if (m && !it.contains('"')) { // not in quotes... probably..
                def indent = it.takeWhile { Character.isWhitespace(it as char) }
                if (!it.contains('class')) {
                    indent *= 2
                } else {
                    indent = '    '
                }

                resultLines << it[0..m.start(1)-1]
                resultLines << indent + m.group(1)
            } else {
                resultLines << it
            }
        }
        return resultLines
    }

/*
some helpers to make it easier to add elements to classes
*/

    static void todo(CtElement element, String message) {
        element.addComment(element.getFactory().createComment("TODO: $message", CtComment.CommentType.INLINE))
    }

    static CtInvocation createInvocation(Factory factory, boolean isStatic, Class declaringClass, String name,
                                         List<CtExpression> args = []) {

        def methods = factory.Type().get(declaringClass).getMethodsByName(name)
        CtExecutableReference method = methods.find { it.parameters.size() == args.size() }
                .getReference()
                .setStatic(isStatic)

        def typeAccess = factory.createTypeAccess(factory.createCtTypeReference(declaringClass))
        return factory.createInvocation(typeAccess, method, args)
    }

    static CtField<?> getOrAddField(CtClass declaringClass, Class<? extends Annotation> annotation,
                            Class type, String name, Set modifiers = []) {

        def existing = declaringClass.declaredFields.find { it.simpleName == name && it.type.qualifiedName == type.name}
        return existing?.getDeclaration() ?: addField(declaringClass, annotation, type, name, modifiers)
    }

    static CtField addField(CtClass declaringClass, Class<? extends Annotation> annotation,
                            Class type, String name, Set modifiers = []) {

        def factory = declaringClass.factory

        def field = factory.Core().createField()
        field.setModifiers(modifiers)
        field.setType(factory.Type().createReference(type))
        field.setSimpleName(name)

        if (annotation) {
            factory.Annotation().annotate(field, annotation)
        }

        // TODO: try to match annotations && (annotation == null || it.getAnnotation(annotation) != null)
        int matchingTypeIdx = declaringClass.declaredFields.findIndexOf { it.modifiers == modifiers }

        declaringClass.addField(matchingTypeIdx >= 0 ? matchingTypeIdx+1 : 0, field)
        return field
    }
}
