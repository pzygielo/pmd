/*
 * BSD-style license; for more info see http://pmd.sourceforge.net/license.html
 */

package net.sourceforge.pmd.lang.java.symbols.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import net.sourceforge.pmd.lang.java.JavaParsingHelper;
import net.sourceforge.pmd.lang.java.ast.ASTCompilationUnit;
import net.sourceforge.pmd.lang.java.ast.ASTTypeDeclaration;
import net.sourceforge.pmd.lang.java.symbols.JClassSymbol;
import net.sourceforge.pmd.lang.java.symbols.JFieldSymbol;
import net.sourceforge.pmd.lang.java.symbols.JFormalParamSymbol;
import net.sourceforge.pmd.lang.java.symbols.JMethodSymbol;
import net.sourceforge.pmd.lang.java.symbols.SymbolicValue;
import net.sourceforge.pmd.lang.java.types.JClassType;
import net.sourceforge.pmd.lang.java.types.JTypeMirror;
import net.sourceforge.pmd.lang.java.types.Substitution;
import net.sourceforge.pmd.lang.java.types.TypeSystem;

/**
 * Abstracts over which symbol implementation to use. Allows running
 * the same tests with different implementations of the symbol.
 * Use with {@link ParameterizedTest} and {@link EnumSource}.
 */
public enum SymImplementation {
    ASM {
        @Override
        public Fixture findClass(String binaryName) {
            int idx = binaryName.lastIndexOf('.');
            String packageName = idx == -1 ? "" : binaryName.substring(0, idx);
            return new Fixture(packageName) {
                @Override
                public @NotNull JClassSymbol getByBinaryName(String binaryName) {
                    JClassSymbol sym = JavaParsingHelper.TEST_TYPE_SYSTEM.getClassSymbol(binaryName);
                    return Objects.requireNonNull(sym, binaryName);
                }
            };
        }
    },
    AST {
        @Override
        public Fixture findClass(String binaryName) {
            ASTCompilationUnit ast = JavaParsingHelper.DEFAULT.parseClass(binaryName);
            return new Fixture(ast.getPackageName()) {
                @Override
                public @NotNull JClassSymbol getByBinaryName(String binaryName) {
                    return ast.descendants(ASTTypeDeclaration.class).crossFindBoundaries()
                              .filter(it -> it.getBinaryName().equals(binaryName))
                              .firstOrThrow().getSymbol();
                }

            };
        }

        @Override
        public boolean supportsDebugSymbols() {
            return true;
        }
    };

    public boolean supportsDebugSymbols() {
        return false;
    }

    /**
     * Find the source file identified by the given binary name and parse it into the fixture.
     */
    public abstract Fixture findClass(String binaryName);

    public @NonNull JClassSymbol getSymbol(Class<?> aClass) {
        return findClass(aClass.getName()).getByBinaryName(aClass.getName());
    }

    public JClassType getDeclaration(Class<?> aClass) {
        return findClass(aClass.getName()).getDeclaration(aClass);
    }


    public void assertAllFieldsMatch(Class<?> actualClass, JClassSymbol sym) {
        List<JFieldSymbol> fs = sym.getDeclaredFields();
        Set<Field> actualFields = Arrays.stream(actualClass.getDeclaredFields()).filter(f -> !f.isSynthetic()).collect(Collectors.toSet());
        assertEquals(actualFields.size(), fs.size());

        for (final Field f : actualFields) {
            JFieldSymbol fSym = fs.stream().filter(it -> it.getSimpleName().equals(f.getName()))
                                  .findFirst().orElseThrow(AssertionError::new);

            // Type matches
            final JTypeMirror expectedType = typeMirrorOf(sym.getTypeSystem(), f.getType());
            assertEquals(expectedType, fSym.getTypeMirror(Substitution.EMPTY));

            assertEquals(f.getModifiers(), fSym.getModifiers());
        }
    }

    private static JTypeMirror typeMirrorOf(TypeSystem ts, Class<?> type) {
        return ts.declaration(ts.getClassSymbol(type));
    }

    public void assertAllMethodsMatch(Class<?> actualClass, JClassSymbol sym) {
        List<JMethodSymbol> ms = sym.getDeclaredMethods();
        Set<Method> actualMethods = Arrays.stream(actualClass.getDeclaredMethods()).filter(m -> !m.isSynthetic()).collect(Collectors.toSet());
        assertEquals(actualMethods.size(), ms.size());

        for (final Method m : actualMethods) {
            JMethodSymbol mSym = ms.stream().filter(it -> it.getSimpleName().equals(m.getName()))
                                   .findFirst().orElseThrow(AssertionError::new);

            assertMethodMatch(m, mSym);
        }
    }

    public void assertMethodMatch(Method m, JMethodSymbol mSym) {
        assertEquals(m.getParameterCount(), mSym.getArity());

        final Parameter[] parameters = m.getParameters();
        List<JFormalParamSymbol> formals = mSym.getFormalParameters();

        for (int i = 0; i < m.getParameterCount(); i++) {
            assertParameterMatch(parameters[i], formals.get(i));
        }

        // Defaults should match too (even if not an annotation method, both should be null)
        assertEquals(SymbolicValue.of(mSym.getTypeSystem(), m.getDefaultValue()), mSym.getDefaultAnnotationValue());
    }

    private void assertParameterMatch(Parameter p, JFormalParamSymbol pSym) {
        if (supportsDebugSymbols()) {
            if (p.isNamePresent()) {
                assertEquals(p.getName(), pSym.getSimpleName());
                assertEquals(Modifier.isFinal(p.getModifiers()), pSym.isFinal());
            } else {
                System.out.println("WARN: test classes were not compiled with -parameters, parameters not fully checked");
            }
        } else {
            // note that this asserts, that the param names are unavailable
            assertEquals("", pSym.getSimpleName());
            assertFalse(pSym.isFinal());
        }

        // Ensure type matches
        final JTypeMirror expectedType = typeMirrorOf(pSym.getTypeSystem(), p.getType());

        assertEquals(expectedType, pSym.getTypeMirror(Substitution.EMPTY));
    }

    /**
     * In order to test simultaneously types defined in the same compilation unit,
     * we must store them somewhere. The fixture stores the parsed AST and allows
     * convenient access to the parsed types. This makes sure that we don't parse
     * the file once per lookup.
     */
    public abstract static class Fixture {
        private final String packageName;

        Fixture(String packageName) {
            this.packageName = packageName;
        }

        /**
         * Return a symbol found in the package with the given simple name.
         */
        public @NonNull JClassSymbol getSymbol(String simpleName) {
            return getByBinaryName(packageName + "." + simpleName);
        }

        /**
         * Return a symbol found in the package with the given binary name.
         */
        public abstract @NonNull JClassSymbol getByBinaryName(String binaryName);

        public @NonNull JClassSymbol getByBinaryName(Class<?> klass) {
            return getByBinaryName(klass.getName());
        }

        public JClassType getDeclaration(Class<?> aClass) {
            JClassSymbol symbol = getByBinaryName(aClass);
            return (JClassType) symbol.getTypeSystem().declaration(symbol);
        }
    }


}
