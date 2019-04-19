import spoon.processing.AbstractProcessor
import spoon.reflect.declaration.CtElement

abstract class AbstractModificationTrackingProcessor<T extends CtElement> extends AbstractProcessor<T> {
    private List<CtElement> changed = []

    void markChanged(CtElement element) {
        changed.add(element)
    }

    Set<File> getChangedFiles() {
       return changed.collect {
           it.getPosition().file.absoluteFile
       } as Set
    }
}
