import java.lang.annotation.Annotation

import spoon.processing.AbstractProcessor
import spoon.reflect.declaration.CtAnnotation
import spoon.reflect.declaration.CtClass
import spoon.reflect.declaration.CtMethod
import spoon.reflect.reference.CtTypeReference

/*
This is a helper that makes it easier to write a processor that replaces one annotation with a different one
and includes an optional callback to further process methods that were annotated
*/
class MethodAnnotationReplacementProcessor extends AbstractModificationTrackingProcessor<CtMethod> {
    private Class originalClass
    private String originalClassName
    private Class replacementClass
    private CtTypeReference original
    private CtTypeReference replacement

    MethodAnnotationReplacementProcessor(Class<? extends Annotation> original, Class<? extends Annotation> replacement) {
        originalClass = original
        replacementClass = replacement
    }

    MethodAnnotationReplacementProcessor(String originalClassName, Class<? extends Annotation> replacement) {
        this.originalClassName = originalClassName
        replacementClass = replacement
    }

    @Override
    void init() {
        this.original = originalClass ? factory.Type().createReference(originalClass)
                : factory.Type().createReference(originalClassName)

        this.replacement = factory.Type().createReference(replacementClass)
    }

    @Override
    void process(CtMethod method) {
        def annotation = method.getAnnotation(original)
        if (annotation) {
            println "replacing ${original} annotation in ${method.getParent(CtClass.class).simpleName}.${method.simpleName}"
            method.removeAnnotation(annotation)
            def addedAnnotation = getFactory().Annotation().annotate(method, replacement)
            markChanged(method)
            onMatch(method, annotation, addedAnnotation)
        }
    }

    void onMatch(CtMethod method, CtAnnotation originalAnnotation, CtAnnotation replacementAnnotation) {}
}