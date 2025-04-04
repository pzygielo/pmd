/*
 * BSD-style license; for more info see http://pmd.sourceforge.net/license.html
 */

package net.sourceforge.pmd.lang.java.symbols.internal.asm;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.pcollections.HashTreePSet;
import org.pcollections.PSet;

import net.sourceforge.pmd.lang.java.symbols.JClassSymbol;
import net.sourceforge.pmd.lang.java.symbols.JConstructorSymbol;
import net.sourceforge.pmd.lang.java.symbols.JElementSymbol;
import net.sourceforge.pmd.lang.java.symbols.JExecutableSymbol;
import net.sourceforge.pmd.lang.java.symbols.JFieldSymbol;
import net.sourceforge.pmd.lang.java.symbols.JMethodSymbol;
import net.sourceforge.pmd.lang.java.symbols.JRecordComponentSymbol;
import net.sourceforge.pmd.lang.java.symbols.JTypeDeclSymbol;
import net.sourceforge.pmd.lang.java.symbols.JTypeParameterOwnerSymbol;
import net.sourceforge.pmd.lang.java.symbols.SymbolicValue;
import net.sourceforge.pmd.lang.java.symbols.SymbolicValue.SymAnnot;
import net.sourceforge.pmd.lang.java.symbols.internal.SymbolEquality;
import net.sourceforge.pmd.lang.java.symbols.internal.asm.ExecutableStub.CtorStub;
import net.sourceforge.pmd.lang.java.symbols.internal.asm.ExecutableStub.MethodStub;
import net.sourceforge.pmd.lang.java.symbols.internal.asm.GenericSigBase.LazyClassSignature;
import net.sourceforge.pmd.lang.java.symbols.internal.asm.ParseLock.CheckedParseLock;
import net.sourceforge.pmd.lang.java.types.JClassType;
import net.sourceforge.pmd.lang.java.types.JTypeVar;
import net.sourceforge.pmd.lang.java.types.LexicalScope;
import net.sourceforge.pmd.lang.java.types.Substitution;
import net.sourceforge.pmd.lang.java.types.TypeSystem;
import net.sourceforge.pmd.util.CollectionUtil;


final class ClassStub implements JClassSymbol, AsmStub, AnnotationOwner {

    static final int UNKNOWN_ARITY = 0;

    private final AsmSymbolResolver resolver;

    private final Names names;

    // all the following are lazy and depend on the parse lock

    private int accessFlags;

    private EnclosingInfo enclosingInfo;
    private LazyClassSignature signature;
    private LexicalScope scope;

    private List<JFieldSymbol> fields = new ArrayList<>();
    private List<JClassSymbol> memberClasses = new ArrayList<>();
    private List<JMethodSymbol> methods = new ArrayList<>();
    private List<JConstructorSymbol> ctors = new ArrayList<>();
    private List<JRecordComponentSymbol> recordComponents = null;
    private List<JFieldSymbol> enumConstants = null;
    private List<JClassSymbol> permittedSubclasses = null;

    private PSet<SymAnnot> annotations = HashTreePSet.empty();

    private PSet<String> annotAttributes;

    private final ParseLock parseLock;

    /** Note that '.' is forbidden because in internal names they're replaced by slashes '/'. */
    private static final Pattern INTERNAL_NAME_FORBIDDEN_CHARS = Pattern.compile("[;<>\\[.]");

    private static boolean isValidInternalName(String internalName) {
        return !internalName.isEmpty() && !INTERNAL_NAME_FORBIDDEN_CHARS.matcher(internalName).find();
    }

    ClassStub(AsmSymbolResolver resolver, String internalName, @NonNull Loader loader, int observedArity) {
        assert isValidInternalName(internalName) : internalName;

        this.resolver = resolver;
        this.names = new Names(internalName);

        this.parseLock = new CheckedParseLock("ClassStub:" + internalName) {
            @Override
            protected boolean doParse() throws IOException {
                try (InputStream instream = loader.getInputStream()) {
                    if (instream != null) {
                        ClassReader classReader = new ClassReader(instream);
                        ClassStubBuilder builder = new ClassStubBuilder(ClassStub.this, resolver);
                        classReader.accept(builder, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                        return true;
                    } else {
                        return false;
                    }
                } catch (IOException e) {
                    // add a bit more info to the exception
                    throw new IOException("While loading class from " + loader, e);
                }
            }

            @Override
            protected void finishParse(boolean failed) {
                if (enclosingInfo == null) {
                    // this may be normal
                    enclosingInfo = EnclosingInfo.NO_ENCLOSING;
                }
                if (signature == null) {
                    assert failed : "No signature, but the parse hasn't failed? investigate " + names.internalName;
                    signature = LazyClassSignature.defaultWhenUnresolved(ClassStub.this, observedArity);
                }
                methods = Collections.unmodifiableList(methods);
                ctors = Collections.unmodifiableList(ctors);
                fields = Collections.unmodifiableList(fields);
                memberClasses = Collections.unmodifiableList(memberClasses);
                enumConstants = CollectionUtil.makeUnmodifiableAndNonNull(enumConstants);
                recordComponents = CollectionUtil.makeUnmodifiableAndNonNull(recordComponents);
                if ((accessFlags & Opcodes.ACC_ENUM) != 0) {
                    permittedSubclasses = Collections.emptyList();
                }
                permittedSubclasses = CollectionUtil.makeUnmodifiableAndNonNull(permittedSubclasses);

                if (enclosingInfo.getEnclosingClass() == null && names.simpleName == null) {
                    // Top-level classes don't get their simple-name populated during parsing.
                    // If the class simple name contains dollars, we can only know after parsing
                    // whether they are top-level or not.
                    names.finishOuterClass();
                }
            }

            @Override
            protected boolean postCondition() {
                return signature != null && enclosingInfo != null && names.simpleName != null;
            }
        };
    }

    @Override
    public AsmSymbolResolver getResolver() {
        return resolver;
    }

    // <editor-fold  defaultstate="collapsed" desc="Setters used during loading">

    void setHeader(@Nullable String signature,
                   @Nullable String superName,
                   String[] interfaces) {
        this.signature = new LazyClassSignature(this, signature, superName, interfaces);
    }

    /**
     * Called if this is an inner class (their simple name cannot be
     * derived from splitting the internal/binary name on dollars, as
     * the simple name may itself contain dollars).
     */
    void setSimpleName(String simpleName) {
        this.names.simpleName = simpleName;
    }

    void setModifiers(int accessFlags, boolean fromClassInfo) {
        /*
            A different set of modifiers is contained in the ClassInfo
            structure and the InnerClasses structure. See
            https://docs.oracle.com/javase/specs/jvms/se13/html/jvms-4.html#jvms-4.1-200-E.1
            https://docs.oracle.com/javase/specs/jvms/se13/html/jvms-4.html#jvms-4.7.6-300-D.1-D.1

            Here is the diff (+ lines (resp. - lines) are only available
            in InnerClasses (resp. ClassInfo), the rest are available in both)

            ACC_PUBLIC      0x0001  Declared public; may be accessed from outside its package.
         +  ACC_PRIVATE     0x0002  Marked private in source.
         +  ACC_PROTECTED   0x0004  Marked protected in source.
         +  ACC_STATIC      0x0008  Marked or implicitly static in source.
            ACC_FINAL       0x0010  Declared final; no subclasses allowed.
         -  ACC_SUPER       0x0020  Treat superclass methods specially when invoked by the invokespecial instruction.
            ACC_INTERFACE   0x0200  Is an interface, not a class.
            ACC_ABSTRACT    0x0400  Declared abstract; must not be instantiated.
            ACC_SYNTHETIC   0x1000  Declared synthetic; not present in the source code.
            ACC_ANNOTATION  0x2000  Declared as an annotation type.
            ACC_ENUM        0x4000  Declared as an enum type.
         -  ACC_MODULE      0x8000  Is a module, not a class or interface.

            If this stub is a nested class, then we don't have all its
            modifiers just with the ClassInfo, the actual source-declared
            visibility (if not public) is only in the InnerClasses, as
            well as its ACC_STATIC.

            Also ACC_SUPER conflicts with ACC_SYNCHRONIZED, which
            Modifier.toString would reflect.

            Since the differences are disjoint we can just OR the two
            sets of flags.
         */
        final int visibilityMask = Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED | Opcodes.ACC_PRIVATE;
        int myAccess = this.accessFlags;
        if (fromClassInfo) {
            // we don't care about ACC_SUPER and it conflicts
            // with ACC_SYNCHRONIZED
            accessFlags = accessFlags & ~Opcodes.ACC_SUPER;
        } else if ((myAccess & Opcodes.ACC_PUBLIC) != 0
            && (accessFlags & visibilityMask) != Opcodes.ACC_PUBLIC) {
            // ClassInfo mentions ACC_PUBLIC even if the real
            // visibility is protected or private
            // We remove the public to avoid a "public protected" or "public private" combination
            myAccess = myAccess & ~Opcodes.ACC_PUBLIC;
        }
        this.accessFlags = myAccess | accessFlags;

        // setModifiers is called multiple times: once from ClassFile structure (fromClassInfo==true)
        // and additionally from InnerClasses attribute (fromClassInfo==false)
        // The enum constants and record components should only be initialized once
        // to avoid losing the constants.
        if (fromClassInfo) {
            if ((accessFlags & Opcodes.ACC_ENUM) != 0) {
                this.enumConstants = new ArrayList<>();
            }
            if ((accessFlags & Opcodes.ACC_RECORD) != 0) {
                this.recordComponents = new ArrayList<>();
            }
        }
    }

    void setEnclosingInfo(ClassStub outer, boolean localOrAnon, @Nullable String methodName, @Nullable String methodDescriptor) {
        if (enclosingInfo == null) {
            if (outer == null) {
                assert methodName == null && methodDescriptor == null
                    : "Enclosing method requires enclosing class";
                this.enclosingInfo = EnclosingInfo.NO_ENCLOSING;
            } else {
                this.enclosingInfo = new EnclosingInfo(outer, localOrAnon, methodName, methodDescriptor);
            }
        }
    }

    void addField(FieldStub fieldStub) {
        fields.add(fieldStub);

        if (fieldStub.isEnumConstant() && enumConstants != null) {
            enumConstants.add(fieldStub);
        }
    }

    void addMemberClass(ClassStub classStub) {
        classStub.setEnclosingInfo(this, false, null, null);
        memberClasses.add(classStub);
    }

    void addMethod(MethodStub methodStub) {
        methods.add(methodStub);
    }

    void addCtor(CtorStub methodStub) {
        ctors.add(methodStub);
    }

    void addRecordComponent(RecordComponentStub recordComponentStub) {
        if (recordComponents == null) {
            recordComponents = new ArrayList<>();
        }
        recordComponents.add(recordComponentStub);
    }

    void addPermittedSubclass(ClassStub permittedSubclass) {
        if (this.permittedSubclasses == null) {
            this.permittedSubclasses = new ArrayList<>(2);
        }
        this.permittedSubclasses.add(permittedSubclass);
    }

    @Override
    public void addAnnotation(SymAnnot annot) {
        annotations = annotations.plus(annot);
    }


    // </editor-fold>


    @Override
    public @Nullable JClassSymbol getSuperclass() {
        parseLock.ensureParsed();
        return signature.getRawSuper();
    }

    @Override
    public List<JClassSymbol> getSuperInterfaces() {
        parseLock.ensureParsed();
        return signature.getRawItfs();
    }

    @Override
    public @Nullable JClassType getSuperclassType(Substitution substitution) {
        parseLock.ensureParsed();
        return signature.getSuperType(substitution);
    }

    @Override
    public List<JClassType> getSuperInterfaceTypes(Substitution substitution) {
        parseLock.ensureParsed();
        return signature.getSuperItfs(substitution);
    }

    @Override
    public List<JTypeVar> getTypeParameters() {
        parseLock.ensureParsed();
        return signature.getTypeParams();
    }

    @Override
    public int getTypeParameterCount() {
        parseLock.ensureParsed();
        return signature.getTypeParameterCount();
    }

    @Override
    public LexicalScope getLexicalScope() {
        if (scope == null) {
            scope = JClassSymbol.super.getLexicalScope();
        }
        return scope;
    }

    @Override
    public List<JFieldSymbol> getDeclaredFields() {
        parseLock.ensureParsed();
        return fields;
    }

    @Override
    public List<JMethodSymbol> getDeclaredMethods() {
        parseLock.ensureParsed();
        return methods;
    }

    @Override
    public List<JConstructorSymbol> getConstructors() {
        parseLock.ensureParsed();
        return ctors;
    }

    @Override
    public List<JClassSymbol> getDeclaredClasses() {
        parseLock.ensureParsed();
        return memberClasses;
    }

    @Override
    public PSet<SymAnnot> getDeclaredAnnotations() {
        parseLock.ensureParsed();
        return annotations;
    }

    @Override
    public PSet<String> getAnnotationAttributeNames() {
        if (annotAttributes == null) {
            parseLock.ensureParsed();
            annotAttributes = isAnnotation()
                              ? getDeclaredMethods().stream().filter(JMethodSymbol::isAnnotationAttribute)
                                                    .map(JElementSymbol::getSimpleName)
                                                    .collect(CollectionUtil.toPersistentSet())
                              : HashTreePSet.empty();
        }
        return annotAttributes;
    }

    @Override
    public @Nullable SymbolicValue getDefaultAnnotationAttributeValue(String attrName) {
        parseLock.ensureParsed();
        if (!getAnnotationAttributeNames().contains(attrName)) {
            // this is a shortcut, because the default impl checks each method
            return null;
        }
        return JClassSymbol.super.getDefaultAnnotationAttributeValue(attrName);
    }

    @Override
    public @Nullable JClassSymbol getEnclosingClass() {
        parseLock.ensureParsed();
        return enclosingInfo.getEnclosingClass();
    }

    @Override
    public @Nullable JExecutableSymbol getEnclosingMethod() {
        parseLock.ensureParsed();
        return enclosingInfo.getEnclosingMethod();
    }

    @Override
    public @NonNull List<JFieldSymbol> getEnumConstants() {
        parseLock.ensureParsed();
        return enumConstants;
    }


    @Override
    public @NonNull List<JRecordComponentSymbol> getRecordComponents() {
        parseLock.ensureParsed();
        return recordComponents;
    }


    @Override
    public List<JClassSymbol> getPermittedSubtypes() {
        parseLock.ensureParsed();
        return permittedSubclasses;
    }

    @Override
    public JTypeParameterOwnerSymbol getEnclosingTypeParameterOwner() {
        parseLock.ensureParsed();
        return enclosingInfo.getEnclosing();
    }

    @Override
    public String toString() {
        // do not use SymbolToString as it triggers the class parsing,
        // making tests undebuggable
        return getInternalName();
    }

    @Override
    public int hashCode() {
        return SymbolEquality.CLASS.hash(this);
    }

    @Override
    public boolean equals(Object obj) {
        return SymbolEquality.CLASS.equals(this, obj);
    }

    // <editor-fold  defaultstate="collapsed" desc="Names">

    public String getInternalName() {
        return getNames().internalName;
    }

    private Names getNames() {
        return names;
    }

    @Override
    public @NonNull String getBinaryName() {
        return getNames().binaryName;
    }

    /**
     * Simpler check than computing the canonical name.
     */
    boolean hasCanonicalName() {
        return getCanonicalName() != null;
    }

    @Override
    public @Nullable String getCanonicalName() {
        @Nullable String canoName = names.canonicalName;
        if (canoName == null) {
            canoName = computeCanonicalName();
            names.canonicalName = canoName;
        }

        if (Names.NO_CANONAME.equals(canoName)) {
            return null;
        }
        return canoName;
    }

    private @NonNull String computeCanonicalName() {
        parseLock.ensureParsed();
        if (names.canonicalName != null) {
            return names.canonicalName;
        } else if (enclosingInfo.isLocalOrAnon()) {
            return Names.NO_CANONAME;
        }
        assert names.simpleName != null && !names.simpleName.isEmpty() : "Anon class should not take this branch";

        JClassSymbol enclosing = enclosingInfo.getEnclosingClass();
        if (enclosing == null) {
            return names.binaryName;
        }
        String outerName = enclosing.getCanonicalName();
        if (outerName == null) {
            return Names.NO_CANONAME;
        }
        return outerName + '.' + names.simpleName;
    }

    @Override
    public @NonNull String getPackageName() {
        return getNames().packageName;
    }

    @Override
    public @NonNull String getSimpleName() {
        String mySimpleName = names.simpleName;
        if (mySimpleName == null) {
            parseLock.ensureParsed();
            return Objects.requireNonNull(names.simpleName, "Null simple name after parsing " + getInternalName());
        }
        return mySimpleName;
    }

    @Override
    public TypeSystem getTypeSystem() {
        return getResolver().getTypeSystem();
    }

    // </editor-fold>

    // <editor-fold  defaultstate="collapsed" desc="Modifier info">


    @Override
    public boolean isUnresolved() {
        return parseLock.isFailed();
    }

    @Override
    public boolean isArray() {
        return false;
    }

    @Override
    public boolean isPrimitive() {
        return false;
    }

    @Override
    public @Nullable JTypeDeclSymbol getArrayComponent() {
        return null;
    }

    @Override
    public int getModifiers() {
        parseLock.ensureParsed();
        return accessFlags;
    }

    @Override
    public boolean isAbstract() {
        return (getModifiers() & Opcodes.ACC_ABSTRACT) != 0;
    }

    @Override
    public boolean isEnum() {
        return (getModifiers() & Opcodes.ACC_ENUM) != 0;
    }

    @Override
    public boolean isAnnotation() {
        return (getModifiers() & Opcodes.ACC_ANNOTATION) != 0;
    }

    @Override
    public boolean isInterface() {
        return (getModifiers() & Opcodes.ACC_INTERFACE) != 0;
    }

    @Override
    public boolean isClass() {
        return (getModifiers() & (Opcodes.ACC_INTERFACE | Opcodes.ACC_ANNOTATION)) == 0;
    }

    @Override
    public boolean isRecord() {
        JClassSymbol sup = getSuperclass();
        return sup != null && "java.lang.Record".equals(sup.getBinaryName());
    }

    @Override
    public boolean isLocalClass() {
        parseLock.ensureParsed();
        return enclosingInfo.isLocalOrAnon() && !isAnonymousClass();
    }

    @Override
    public boolean isAnonymousClass() {
        return getSimpleName().isEmpty();
    }

    boolean isFailed() {
        return this.parseLock.isFailed();
    }

    boolean isNotParsed() {
        return this.parseLock.isNotParsed();
    }


    // </editor-fold>


    static class Names {
        /**
         * Placeholder to represent that the class has no canonical name.
         * This is not a valid canonical names so cannot class with an
         * actual canoname.
         */
        private static final String NO_CANONAME = "--NO-CANONICAL-NAME";

        final String binaryName;
        final String internalName;
        final String packageName;
        /** If null, the class requires parsing to find out the actual canonical name. */
        @Nullable String canonicalName;
        /** If null, the class requires parsing to find out the actual simple name. */
        @Nullable String simpleName;

        Names(String internalName) {
            assert isValidInternalName(internalName) : internalName;
            int packageEnd = internalName.lastIndexOf('/');

            this.internalName = internalName;
            this.binaryName = internalName.replace('/', '.');
            if (packageEnd == -1) {
                this.packageName = "";
            } else {
                this.packageName = binaryName.substring(0, packageEnd);
            }

            if (binaryName.indexOf('$', packageEnd + 1) >= 0) {
                // Contains a dollar in class name (after package)
                // Requires parsing to find out the actual simple name,
                // this might be an inner class, or simply a class with
                // a dollar in its name.

                // ASSUMPTION: all JVM languages use the $ convention
                // to separate inner classes. Java compilers do so but
                // not necessarily true of all compilers/languages.
                this.canonicalName = null;
                this.simpleName = null;
            } else {
                // fast path
                this.canonicalName = binaryName;
                this.simpleName = binaryName.substring(packageEnd + 1);
            }
        }

        public void finishOuterClass() {
            int packageEnd = internalName.lastIndexOf('/');
            this.simpleName = binaryName.substring(packageEnd + 1); // if -1, start from 0
            this.canonicalName = binaryName;
        }
    }

    static class EnclosingInfo {

        static final EnclosingInfo NO_ENCLOSING = new EnclosingInfo(null, false, null, null);

        private final @Nullable JClassSymbol stub;
        private final @Nullable String methodName;
        private final @Nullable String methodDescriptor;
        private final boolean isLocalOrAnon;

        EnclosingInfo(@Nullable JClassSymbol stub, boolean isLocalOrAnon, @Nullable String methodName, @Nullable String methodDescriptor) {
            this.stub = stub;
            this.isLocalOrAnon = isLocalOrAnon;
            this.methodName = methodName;
            this.methodDescriptor = methodDescriptor;
        }

        boolean isLocalOrAnon() {
            return isLocalOrAnon;
        }

        public @Nullable JClassSymbol getEnclosingClass() {
            return stub;
        }

        public @Nullable MethodStub getEnclosingMethod() {
            if (stub instanceof ClassStub && methodName != null) {
                ClassStub stub1 = (ClassStub) stub;
                stub1.parseLock.ensureParsed();
                for (JMethodSymbol m : stub1.methods) {
                    MethodStub ms = (MethodStub) m;
                    if (ms.matches(methodName, methodDescriptor)) {
                        return ms;
                    }
                }
            }
            return null;
        }


        JTypeParameterOwnerSymbol getEnclosing() {
            if (methodName != null) {
                return getEnclosingMethod();
            } else {
                return getEnclosingClass();
            }
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            EnclosingInfo that = (EnclosingInfo) o;
            return Objects.equals(stub, that.stub)
                && isLocalOrAnon == that.isLocalOrAnon
                && Objects.equals(methodName, that.methodName)
                && Objects.equals(methodDescriptor, that.methodDescriptor);
        }

        @Override
        public int hashCode() {
            return Objects.hash(stub, isLocalOrAnon, methodName, methodDescriptor);
        }
    }
}
