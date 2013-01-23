package com.strobel.assembler.ir;

import com.strobel.assembler.ir.attributes.*;
import com.strobel.assembler.metadata.*;
import com.strobel.core.StringUtilities;
import com.strobel.core.VerifyArgument;
import com.strobel.util.EmptyArrayCache;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * @author Mike Strobel
 */
public final class ClassFileReader extends MetadataReader implements ClassReader<MutableTypeDefinition> {
    final static long MAGIC = 0xCAFEBABEL;

    final IMetadataResolver resolver;
    final long magic;
    final int majorVersion;
    final int minorVersion;
    final Buffer buffer;
    final ConstantPool constantPool;
    final int accessFlags;
    final ConstantPool.TypeInfoEntry thisClassEntry;
    final ConstantPool.TypeInfoEntry baseClassEntry;
    final ConstantPool.TypeInfoEntry[] interfaceEntries;
    final List<FieldInfo> fields;
    final List<MethodInfo> methods;
    final List<SourceAttribute> attributes;
    final String name;
    final String packageName;

    private final ResolverFrame _resolverFrame;
    private final AtomicBoolean _completed;
    private final Scope _scope;

    private TypeDefinition _thisType;

    private ClassFileReader(
        final IMetadataResolver resolver,
        final long magic,
        final int majorVersion,
        final int minorVersion,
        final Buffer buffer,
        final ConstantPool constantPool,
        final int accessFlags,
        final ConstantPool.TypeInfoEntry thisClassEntry,
        final ConstantPool.TypeInfoEntry baseClassEntry,
        final ConstantPool.TypeInfoEntry[] interfaceEntries) {

        super(resolver);

        _resolverFrame = new ResolverFrame();

        this.resolver = resolver;

        _scope = new Scope(this.resolver);

//        this.resolver = resolver;
        this.magic = magic;
        this.majorVersion = majorVersion;
        this.minorVersion = minorVersion;
        this.buffer = buffer;
        this.constantPool = constantPool;
        this.accessFlags = accessFlags;
        this.thisClassEntry = VerifyArgument.notNull(thisClassEntry, "thisClassEntry");
        this.baseClassEntry = baseClassEntry;
        this.interfaceEntries = VerifyArgument.notNull(interfaceEntries, "interfaceEntries");
        this.attributes = new ArrayList<>();
        this.fields = new ArrayList<>();
        this.methods = new ArrayList<>();

        final String internalName = thisClassEntry.getName();

        final int delimiter = internalName.lastIndexOf('/');

        if (delimiter < 0) {
            this.packageName = StringUtilities.EMPTY;
            this.name = internalName;
        }
        else {
            this.packageName = internalName.substring(0, delimiter).replace('/', '.');
            this.name = internalName.substring(delimiter + 1);
        }

        _completed = new AtomicBoolean();
    }

    @Override
    protected IMetadataScope getScope() {
        return _scope;
    }

    final synchronized void complete(final TypeDefinition thisType) {
        if (_completed.getAndSet(true)) {
            return;
        }

        resolver.pushFrame(_resolverFrame);

        try {
            _thisType = thisType;

            final int fieldCount = buffer.readUnsignedShort();

            for (int i = 0; i < fieldCount; i++) {
                final int accessFlags = buffer.readUnsignedShort();

                final String name = constantPool.lookupUtf8Constant(buffer.readUnsignedShort());
                final String descriptor = constantPool.lookupUtf8Constant(buffer.readUnsignedShort());

                final SourceAttribute[] attributes;
                final int attributeCount = buffer.readUnsignedShort();

                if (attributeCount > 0) {
                    attributes = new SourceAttribute[attributeCount];
                    readAttributesPhaseOne(buffer, attributes);
                }
                else {
                    attributes = EmptyArrayCache.fromElementType(SourceAttribute.class);
                }

                final FieldInfo field = new FieldInfo(accessFlags, name, descriptor, attributes);

                fields.add(field);
            }

            final int methodCount = buffer.readUnsignedShort();

            for (int i = 0; i < methodCount; i++) {
                final int accessFlags = buffer.readUnsignedShort();

                final String name = constantPool.lookupUtf8Constant(buffer.readUnsignedShort());
                final String descriptor = constantPool.lookupUtf8Constant(buffer.readUnsignedShort());

                final SourceAttribute[] attributes;
                final int attributeCount = buffer.readUnsignedShort();

                if (attributeCount > 0) {
                    attributes = new SourceAttribute[attributeCount];
                    readAttributesPhaseOne(buffer, attributes);
                }
                else {
                    attributes = EmptyArrayCache.fromElementType(SourceAttribute.class);
                }

                final MethodInfo field = new MethodInfo(accessFlags, name, descriptor, attributes);

                methods.add(field);
            }

            final int typeAttributeCount = buffer.readUnsignedShort();

            if (typeAttributeCount > 0) {
                final SourceAttribute[] typeAttributes = new SourceAttribute[typeAttributeCount];

                readAttributesPhaseOne(buffer, typeAttributes);

                for (final SourceAttribute typeAttribute : typeAttributes) {
                    this.attributes.add(typeAttribute);
                }
            }

            final MutableTypeDefinition type = new MutableTypeDefinition();

            accept(type, new TypeDefinitionBuilder(resolver));

            buffer.reset(0);
        }
        finally {
            resolver.popFrame();
        }
    }

    private void readAttributesPhaseOne(final Buffer buffer, final SourceAttribute[] attributes) {
        for (int i = 0; i < attributes.length; i++) {
            final int nameIndex = buffer.readUnsignedShort();
            final int length = buffer.readInt();
            final IMetadataScope scope = getScope();
            final String name = scope.lookupConstant(nameIndex);

            switch (name) {
                case AttributeNames.SourceFile: {
                    final int token = buffer.readUnsignedShort();
                    final String sourceFile = scope.lookupConstant(token);
                    attributes[i] = new SourceFileAttribute(sourceFile);
                    continue;
                }

                case AttributeNames.ConstantValue: {
                    final int token = buffer.readUnsignedShort();
                    final Object constantValue = scope.lookupConstant(token);
                    attributes[i] = new ConstantValueAttribute(constantValue);
                    continue;
                }

                case AttributeNames.LineNumberTable: {
                    final int entryCount = buffer.readUnsignedShort();
                    final LineNumberTableEntry[] entries = new LineNumberTableEntry[entryCount];

                    for (int j = 0; j < entries.length; j++) {
                        entries[j] = new LineNumberTableEntry(
                            buffer.readUnsignedShort(),
                            buffer.readUnsignedShort()
                        );
                    }

                    attributes[i] = new LineNumberTableAttribute(entries);
                    continue;
                }

                case AttributeNames.Signature: {
                    final int token = buffer.readUnsignedShort();
                    final String signature = scope.lookupConstant(token);
                    attributes[i] = new SignatureAttribute(signature);
                    continue;
                }

                default: {
                    final byte[] blob = new byte[length];
                    buffer.read(blob, 0, blob.length);
                    attributes[i] = new BlobAttribute(name, blob);
                    continue;
                }
            }
        }
    }

    public static ClassFileReader readClass(final IMetadataResolver resolver, final Buffer b) {
        final long magic = b.readInt() & 0xFFFFFFFFL;

        if (magic != MAGIC) {
            throw new IllegalStateException("Wrong magic number: " + magic);
        }

        final int minorVersion = b.readUnsignedShort();
        final int majorVersion = b.readUnsignedShort();

        final ConstantPool constantPool = ConstantPool.read(b);

        final int accessFlags = b.readUnsignedShort();

        final ConstantPool.TypeInfoEntry thisClass = (ConstantPool.TypeInfoEntry) constantPool.get(b.readUnsignedShort(), ConstantPool.Tag.TypeInfo);
        final ConstantPool.TypeInfoEntry baseClass;

        final int baseClassToken = b.readUnsignedShort();

        if (baseClassToken == 0) {
            baseClass = null;
        }
        else {
            baseClass = constantPool.getEntry(baseClassToken);
        }

        final ConstantPool.TypeInfoEntry interfaces[] = new ConstantPool.TypeInfoEntry[b.readUnsignedShort()];

        for (int i = 0; i < interfaces.length; i++) {
            interfaces[i] = (ConstantPool.TypeInfoEntry) constantPool.get(b.readUnsignedShort(), ConstantPool.Tag.TypeInfo);
        }

        return new ClassFileReader(
            resolver,
            magic,
            majorVersion,
            minorVersion,
            b,
            constantPool,
            accessFlags,
            thisClass,
            baseClass,
            interfaces
        );
    }

    // <editor-fold defaultstate="collapsed" desc="ClassReader Implementation">

    @Override
    public void accept(final MutableTypeDefinition type, final ClassVisitor<MutableTypeDefinition> visitor) {
        resolver.pushFrame(_resolverFrame);

        try {
            if (!_completed.getAndSet(true)) {
                _thisType = type;

                final int fieldCount = buffer.readUnsignedShort();

                for (int i = 0; i < fieldCount; i++) {
                    final int accessFlags = buffer.readUnsignedShort();

                    final String name = constantPool.lookupUtf8Constant(buffer.readUnsignedShort());
                    final String descriptor = constantPool.lookupUtf8Constant(buffer.readUnsignedShort());

                    final SourceAttribute[] attributes;
                    final int attributeCount = buffer.readUnsignedShort();

                    if (attributeCount > 0) {
                        attributes = new SourceAttribute[attributeCount];
                        readAttributesPhaseOne(buffer, attributes);
                    }
                    else {
                        attributes = EmptyArrayCache.fromElementType(SourceAttribute.class);
                    }

                    final FieldInfo field = new FieldInfo(accessFlags, name, descriptor, attributes);

                    fields.add(field);
                }

                final int methodCount = buffer.readUnsignedShort();

                for (int i = 0; i < methodCount; i++) {
                    final int accessFlags = buffer.readUnsignedShort();

                    final String name = constantPool.lookupUtf8Constant(buffer.readUnsignedShort());
                    final String descriptor = constantPool.lookupUtf8Constant(buffer.readUnsignedShort());

                    final SourceAttribute[] attributes;
                    final int attributeCount = buffer.readUnsignedShort();

                    if (attributeCount > 0) {
                        attributes = new SourceAttribute[attributeCount];
                        readAttributesPhaseOne(buffer, attributes);
                    }
                    else {
                        attributes = EmptyArrayCache.fromElementType(SourceAttribute.class);
                    }

                    final MethodInfo field = new MethodInfo(accessFlags, name, descriptor, attributes);

                    methods.add(field);
                }

                final int typeAttributeCount = buffer.readUnsignedShort();

                if (typeAttributeCount > 0) {
                    final SourceAttribute[] typeAttributes = new SourceAttribute[typeAttributeCount];

                    readAttributesPhaseOne(buffer, typeAttributes);

                    for (final SourceAttribute typeAttribute : typeAttributes) {
                        this.attributes.add(typeAttribute);
                    }
                }
            }

            type.setName(this.name);
            type.setPackageName(this.packageName);

            _resolverFrame.addType(type);

            try {
                visitHeader(type, visitor);
            }
            finally {
                _resolverFrame.removeType(type);
            }
        }
        finally {
            resolver.popFrame();
        }
    }
    private void visitHeader(final MutableTypeDefinition type, final ClassVisitor<MutableTypeDefinition> visitor) {
        final SignatureAttribute signature = SourceAttribute.find(AttributeNames.Signature, attributes);
        final String[] interfaceNames = new String[interfaceEntries.length];

        for (int i = 0; i < interfaceEntries.length; i++) {
            interfaceNames[i] = interfaceEntries[i].getName();
        }

        visitor.visit(
            type,
            majorVersion,
            minorVersion,
            accessFlags,
            thisClassEntry.getName(),
            signature != null ? signature.getSignature() : null,
            baseClassEntry != null ? baseClassEntry.getName() : null,
            interfaceNames
        );

        for (final MethodInfo method : methods) {
            final SignatureAttribute sigAttr = SourceAttribute.find(AttributeNames.Signature, method.attributes);
            if (sigAttr != null) {
                _scope._parser.pushGenericContext(type);
                try {
                    final IMethodSignature ms = _scope._parser.parseMethodSignature(sigAttr.getSignature());
                }
                finally {
                    _scope._parser.popGenericContext();
                }
            }
        }
    }

    // </editor-fold>

    // <editor-fold defaultstate="collapsed" desc="FieldInfo Class">

    final class FieldInfo {
        final int accessFlags;
        final String name;
        final String descriptor;
        final SourceAttribute[] attributes;

        FieldInfo(final int accessFlags, final String name, final String descriptor, final SourceAttribute[] attributes) {
            this.accessFlags = accessFlags;
            this.name = name;
            this.descriptor = descriptor;
            this.attributes = attributes;
        }
    }

    // </editor-fold>

    // <editor-fold defaultstate="collapsed" desc="MethodInfo Class">

    final class MethodInfo {
        final int accessFlags;
        final String name;
        final String descriptor;
        final SourceAttribute[] attributes;

        SourceAttribute codeAttribute;

        MethodInfo(final int accessFlags, final String name, final String descriptor, final SourceAttribute[] attributes) {
            this.accessFlags = accessFlags;
            this.name = name;
            this.descriptor = descriptor;
            this.attributes = attributes;
            this.codeAttribute = SourceAttribute.find(AttributeNames.Code, attributes);
        }
    }

    // </editor-fold>

    // <editor-fold defaultstate="collapsed" desc="Metadata Scope">

    private class Scope implements IMetadataScope {
        private final MetadataParser _parser;

        Scope(final IMetadataResolver resolver) {
            _parser = new MetadataParser(VerifyArgument.notNull(resolver, "resolver"));
        }

        @Override
        public TypeReference lookupType(final int token) {
            final ConstantPool.Entry entry = constantPool.get(token);

            if (entry instanceof ConstantPool.TypeInfoEntry) {
                final ConstantPool.TypeInfoEntry typeInfo = (ConstantPool.TypeInfoEntry) entry;

                if (_thisType != null && thisClassEntry.getName().equals(typeInfo.getName())) {
                    return _thisType;
                }

                return _parser.parseTypeDescriptor(typeInfo.getName());
            }

            final String typeName = constantPool.lookupConstant(token);

            if (_thisType != null && thisClassEntry.getName().equals(typeName)) {
                return _thisType;
            }

            return _parser.parseTypeSignature(typeName);
        }

        @Override
        public FieldReference lookupField(final int token) {
            final ConstantPool.FieldReferenceEntry entry = constantPool.getEntry(token);
            return lookupField(entry.typeInfoIndex, entry.nameAndTypeDescriptorIndex);
        }

        @Override
        public MethodReference lookupMethod(final int token) {
            final ConstantPool.FieldReferenceEntry entry = constantPool.getEntry(token);
            return lookupMethod(entry.typeInfoIndex, entry.nameAndTypeDescriptorIndex);
        }

        @Override
        public FieldReference lookupField(final int typeToken, final int nameAndTypeToken) {
            final ConstantPool.NameAndTypeDescriptorEntry nameAndDescriptor = constantPool.getEntry(nameAndTypeToken);

            return _parser.parseField(
                lookupType(typeToken),
                nameAndDescriptor.getName(),
                nameAndDescriptor.getType()
            );
        }

        @Override
        public MethodReference lookupMethod(final int typeToken, final int nameAndTypeToken) {
            final ConstantPool.NameAndTypeDescriptorEntry nameAndDescriptor = constantPool.getEntry(nameAndTypeToken);

            return _parser.parseMethod(
                lookupType(typeToken),
                nameAndDescriptor.getName(),
                nameAndDescriptor.getType()
            );
        }

        @Override
        public <T> T lookupConstant(final int token) {
            return constantPool.lookupConstant(token);
        }
    }

    // </editor-fold>

    // <editor-fold defaultstate="collapsed" desc="ResolverFrame Class">

    private final class ResolverFrame implements IResolverFrame {
        final HashMap<String, TypeReference> types = new HashMap<>();
        final HashMap<String, GenericParameter> typeVariables = new HashMap<>();

        public void addType(final TypeReference type) {
            VerifyArgument.notNull(type, "type");
            types.put(type.getInternalName(), type);
        }

        public void addTypeVariable(final GenericParameter type) {
            VerifyArgument.notNull(type, "type");
            typeVariables.put(type.getName(), type);
        }

        public void removeType(final TypeReference type) {
            VerifyArgument.notNull(type, "type");
            types.remove(type.getInternalName());
        }

        public void removeTypeVariable(final GenericParameter type) {
            VerifyArgument.notNull(type, "type");
            typeVariables.remove(type.getName());
        }

        @Override
        public TypeReference findType(final String descriptor) {
            final TypeReference type = types.get(descriptor);

            if (type != null) {
                return type;
            }

            return null;
        }

        @Override
        public TypeReference findTypeVariable(final String name) {
            final GenericParameter type = typeVariables.get(name);

            if (type != null) {
                return type;
            }

            return null;
        }
    }

    // </editor-fold>
}
