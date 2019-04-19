import spoon.compiler.Environment
import spoon.reflect.declaration.CtImport
import spoon.reflect.declaration.CtImportKind
import spoon.reflect.declaration.CtType
import spoon.reflect.reference.CtExecutableReference
import spoon.reflect.reference.CtFieldReference
import spoon.reflect.reference.CtPackageReference
import spoon.reflect.reference.CtTypeMemberWildcardImportReference
import spoon.reflect.reference.CtTypeReference
import spoon.reflect.visitor.ElementPrinterHelper
import spoon.reflect.visitor.TokenWriter

/*
there's no built in way to customize the way imports are written out so this overrides the default writer which
unfortunately requires copying a bunch of code from the default one 
*/
class CustomPrinterHelper extends ElementPrinterHelper {
    TokenWriter printer

    CustomPrinterHelper(TokenWriter printerTokenWriter, def prettyPrinter, Environment env) {
        super(printerTokenWriter, prettyPrinter, env)
        this.printer = printerTokenWriter
    }

    /** writes the imports in a specific order (eg all static imports together */
    void writeImports(Collection<CtImport> imports) {
        Set<String> setImports = new HashSet<>();
        Set<String> setStaticImports = new HashSet<>();
        for (CtImport ctImport : imports) {
            String importTypeStr;
            switch (ctImport.getImportKind()) {
                case CtImportKind.TYPE:
                    CtTypeReference typeRef = (CtTypeReference) ctImport.getReference()
                    importTypeStr = typeRef.getQualifiedName()

                    // local enum reference spoon screws up
                    if (!isJavaLangClasses(importTypeStr) && !(importTypeStr ==~ /\w+\.[A-Z_]+/)) {
                        setImports.add(removeInnerTypeSeparator(importTypeStr))
                    }
                    break;

                case CtImportKind.ALL_TYPES:
                    CtPackageReference packageRef = (CtPackageReference) ctImport.getReference()
                    importTypeStr = packageRef.getQualifiedName() + ".*"
                    if (!isJavaLangClasses(importTypeStr)) {
                        setImports.add(removeInnerTypeSeparator(importTypeStr))
                    }
                    break;

                case CtImportKind.METHOD:
                    CtExecutableReference execRef = (CtExecutableReference) ctImport.getReference()
                    if (execRef.getDeclaringType() != null) {
                        setStaticImports.add(removeInnerTypeSeparator(execRef.getDeclaringType().getQualifiedName()) + "." + execRef.getSimpleName());
                    }
                    break;

                case CtImportKind.FIELD:
                    CtFieldReference fieldRef = (CtFieldReference) ctImport.getReference()
                    setStaticImports.add(removeInnerTypeSeparator(fieldRef.getDeclaringType().getQualifiedName()) + "." + fieldRef.getSimpleName());
                    break;

                case CtImportKind.ALL_STATIC_MEMBERS:
                    CtTypeMemberWildcardImportReference typeStarRef = (CtTypeMemberWildcardImportReference) ctImport.getReference();
                    importTypeStr = typeStarRef.getTypeReference().getQualifiedName();
                    if (!isJavaLangClasses(importTypeStr)) {
                        setStaticImports.add(this.removeInnerTypeSeparator(importTypeStr) + ".*");
                    }
                    break;
            }
        }

        boolean isFirst = true

        if (!setStaticImports.isEmpty()) {
            if (isFirst) {
                printer.writeln();
            }
            printer.writeln();
            List<String> sortedStaticImports = new ArrayList<>(setStaticImports)
            Collections.sort(sortedStaticImports)
            for (String importLine : sortedStaticImports) {
                printer.writeKeyword("import").writeSpace().writeKeyword("static").writeSpace()
                writeQualifiedName(importLine).writeSeparator(";").writeln()
            }
        }

        List<String> sortedImports = new ArrayList<>(setImports).sort()

        for (String importLine : sortedImports) {
            if (isFirst) {
                printer.writeln()
                isFirst = false
            }
            printer.writeKeyword("import").writeSpace()
            writeQualifiedName(importLine).writeSeparator(";").writeln()
        }

    }

    private boolean isJavaLangClasses(String importType) {
        return importType.matches('^(java\\.lang\\.)[^.]*$')
    }

    private String removeInnerTypeSeparator(String fqn) {
        return fqn.replace(CtType.INNERTTYPE_SEPARATOR, ".")
    }
}