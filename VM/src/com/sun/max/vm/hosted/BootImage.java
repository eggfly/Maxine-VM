/*
 * Copyright (c) 2007 Sun Microsystems, Inc.  All rights reserved.
 *
 * Sun Microsystems, Inc. has intellectual property rights relating to technology embodied in the product
 * that is described in this document. In particular, and without limitation, these intellectual property
 * rights may include one or more of the U.S. patents listed at http://www.sun.com/patents and one or
 * more additional patents or pending patent applications in the U.S. and in other countries.
 *
 * U.S. Government Rights - Commercial software. Government users are subject to the Sun
 * Microsystems, Inc. standard license agreement and applicable provisions of the FAR and its
 * supplements.
 *
 * Use is subject to license terms. Sun, Sun Microsystems, the Sun logo, Java and Solaris are trademarks or
 * registered trademarks of Sun Microsystems, Inc. in the U.S. and other countries. All SPARC trademarks
 * are used under license and are trademarks or registered trademarks of SPARC International, Inc. in the
 * U.S. and other countries.
 *
 * UNIX is a registered trademark in the U.S. and other countries, exclusively licensed through X/Open
 * Company, Ltd.
 */
package com.sun.max.vm.hosted;

import static com.sun.max.platform.Platform.*;
import static com.sun.max.vm.VMConfiguration.*;

import java.io.*;
import java.lang.reflect.*;
import java.nio.*;
import java.nio.channels.FileChannel.*;
import java.util.*;

import com.sun.max.*;
import com.sun.max.asm.*;
import com.sun.max.collect.*;
import com.sun.max.lang.*;
import com.sun.max.platform.*;
import com.sun.max.program.*;
import com.sun.max.unsafe.*;
import com.sun.max.util.*;
import com.sun.max.vm.*;
import com.sun.max.vm.actor.*;
import com.sun.max.vm.actor.holder.*;
import com.sun.max.vm.actor.member.*;
import com.sun.max.vm.classfile.constant.*;
import com.sun.max.vm.compiler.*;
import com.sun.max.vm.compiler.CompilationScheme.*;
import com.sun.max.vm.heap.*;
import com.sun.max.vm.tele.*;
import com.sun.max.vm.thread.*;
import com.sun.max.vm.type.*;

/**
 * The <i>boot image</i> contains the heap objects that represent a Maxine VM, including compiled code.
 * This class is a utility for both writing and reading a boot image.
 *
 * The layout of the boot image as a pseudo-C struct is:
 * <pre>
 *
 *     struct BootImage {
 *          struct Header header;            // see declaration of image_Header in Native/substrate/image.h
 *          struct StringInfo string_info;   // see declaration of image_StringInfo in Native/substrate/image.h
 *          byte[header.relocationDataSize] relocation_data;
 *          byte[] pad;                      // padding such that next field will be aligned on an OS page-size address
 *          byte[header.heapSize];           // header.heapSize is a multiple of page-size
 *          byte[header.codeSize];           // header.codeSize is a multiple of page-size
 *          struct Trailer trailer;          // see declaration of image_Trailer in Native/substrate/image.h
 *     }
 *
 * </pre>
 *
 * The 'pad' member in the image exists so that in-memory images can be supported. That is, if the
 * VM obtains the image from a memory location as opposed to loading it from a file, then the
 * page-alignment requirement for the heap and code sections in the image will be satisifed if the
 * image itself starts at a page-aligned address.
 *
 * @author Bernd Mathiske
 * @author Doug Simon
 */
public class BootImage {

    /**
     * A special identifier for Maxine boot image files. {@code 0xCAFE4DAD}
     */
    public static final int IDENTIFICATION = 0xcafe4dad;

    /**
     * A version number of Maxine.
     */
    public static final int VERSION = 1;

    /**
     * A field section in a boot image is described by the {@code public final} and {@code final}
     * fields in a {@code FieldSection} subclass. That is, every {@code public final} field of the
     * specified {@linkplain #fieldType() type} declared in a subclass of {@code FieldSection}
     * describes a section entry.
     */
    public abstract static class FieldSection {

        private final int offset;

        protected FieldSection(int offset) {
            this.offset = offset;
        }

        static Field[] fields(Class holder, final Class fieldType) {
            Field[] declaredFields = holder.getDeclaredFields();
            ArrayList<Field> result = new ArrayList<Field>(declaredFields.length);
            for (Field declaredField : declaredFields) {
                final int flags = Actor.ACC_FINAL | Actor.ACC_PUBLIC;
                if ((declaredField.getModifiers() & flags) == flags && declaredField.getType().equals(fieldType)) {
                    result.add(declaredField);
                }
            }
            return result.toArray(new Field[result.size()]);
        }

        /**
         * Gets the fields representing the entries of this section.
         */
        public Field[] fields() {
            return fields(getClass(), fieldType());
        }

        /**
         * Gets the type of the fields in this class that describe section entries.
         */
        public abstract Class<?> fieldType();

        /**
         * Gets the size (in bytes) of this section within the boot image.
         */
        public abstract int size();

        /**
         * Gets the offset (in bytes) of this section within the boot image.
         */
        public final int offset() {
            return offset;
        }

        /**
         * Writes the data in this section to a given stream.
         */
        public abstract void write(OutputStream outputStream) throws IOException;
    }

    /**
     * A section composed of {@code int} entries.
     */
    public abstract static class IntSection extends FieldSection {
        private final Endianness endianness;

        public Endianness endianness() {
            return endianness;
        }

        protected IntSection(Endianness endianness, int offset) {
            super(offset);
            this.endianness = endianness;
        }

        @Override
        public int size() {
            return fields().length * Ints.SIZE;
        }

        @Override
        public void write(OutputStream outputStream) throws IOException {
            for (Field field : fields()) {
                try {
                    endianness.writeInt(outputStream, field.getInt(this));
                } catch (IllegalAccessException illegalAccessException) {
                    ProgramError.unexpected();
                }
            }
        }

        @Override
        public Class<?> fieldType() {
            return int.class;
        }
    }

    /**
     * The header section in a boot image.
     *
     * <b>ATTENTION: this must match 'image_Header' in "Native/substrate/image.h".</b>
     */
    public static final class Header extends IntSection {
        public final int isBigEndian;

        public final int identification;
        public final int version;
        public final int randomID;

        public final int wordSize;
        public final int cacheAlignment;

        public final int pageSize;

        public final int vmRunMethodOffset;
        public final int vmThreadAddMethodOffset;
        public final int vmThreadRunMethodOffset;
        public final int vmThreadAttachMethodOffset;
        public final int vmThreadDetachMethodOffset;

        public final int classRegistryOffset;

        public final int stringInfoSize;
        public final int relocationDataSize;

        public final int heapSize;
        public final int codeSize;

        public final int dynamicHeapRegionsArrayFieldOffset;

        /**
         * Instruct the boot image loader to reserve a range of contiguous virtual space of specified size.
         */
        public final int reservedVirtualSpaceSize;

        /**
         * Offset to the variable that will hold the address of the virtual space reserved by the boot image loader at boot-load time.
         *
         * @see Heap#reservedVirtualSpace
         */
        public final int reservedVirtualSpaceFieldOffset;

        /**
         * Instruct the boot image loader to memory map the boot heap region at a specific  location:
         * if 0, just map it anywhere; if 1 (respectively, 2), map it at the beginning (respectively, end),of the reserved virtual space.
         */
        public final int bootRegionMappingConstraint;

        /**
         * @see VmThreadMap#ACTIVE
         */
        public final int threadLocalsListHeadOffset;

        /**
         * @see MaxineVM#primordialVmThreadLocals()
         */
        public final int primordialThreadLocalsOffset;

        /**
         * The storage size of one set of VM thread locals.
         */
        public final int threadLocalsAreaSize;

        /**
         * The indexes of the VM thread locals accessed directly by C code.
         */
        public final int SAFEPOINT_LATCH;
        public final int SAFEPOINTS_ENABLED_THREAD_LOCALS;
        public final int SAFEPOINTS_DISABLED_THREAD_LOCALS;
        public final int SAFEPOINTS_TRIGGERED_THREAD_LOCALS;
        public final int NATIVE_THREAD_LOCALS;
        public final int FORWARD_LINK;
        public final int BACKWARD_LINK;
        public final int ID;
        public final int JNI_ENV;
        public final int LAST_JAVA_FRAME_ANCHOR;
        public final int TRAP_NUMBER;
        public final int TRAP_INSTRUCTION_POINTER;
        public final int TRAP_FAULT_ADDRESS;
        public final int TRAP_LATCH_REGISTER;
        public final int STACK_REFERENCE_MAP;
        public final int STACK_REFERENCE_MAP_SIZE;

        public WordWidth wordWidth() {
            return WordWidth.fromInt(wordSize * 8);
        }

        private Header(DataInputStream dataInputStream) throws IOException, BootImageException {
            super(dataInputStream.readInt() == 0 ? Endianness.LITTLE : Endianness.BIG, 0);
            final Endianness endian = endianness();
            isBigEndian = endian.ordinal();

            identification = endian.readInt(dataInputStream);
            version = endian.readInt(dataInputStream);
            randomID = endian.readInt(dataInputStream);

            wordSize = endian.readInt(dataInputStream);
            cacheAlignment = endian.readInt(dataInputStream);

            pageSize = endian.readInt(dataInputStream);

            vmRunMethodOffset = endian.readInt(dataInputStream);
            vmThreadAddMethodOffset = endian.readInt(dataInputStream);
            vmThreadRunMethodOffset = endian.readInt(dataInputStream);
            vmThreadAttachMethodOffset = endian.readInt(dataInputStream);
            vmThreadDetachMethodOffset = endian.readInt(dataInputStream);
            classRegistryOffset = endian.readInt(dataInputStream);

            stringInfoSize = endian.readInt(dataInputStream);
            relocationDataSize = endian.readInt(dataInputStream);

            heapSize = endian.readInt(dataInputStream);
            codeSize = endian.readInt(dataInputStream);

            dynamicHeapRegionsArrayFieldOffset = endian.readInt(dataInputStream);
            reservedVirtualSpaceSize = endian.readInt(dataInputStream);
            reservedVirtualSpaceFieldOffset = endian.readInt(dataInputStream);
            bootRegionMappingConstraint = endian.readInt(dataInputStream);
            threadLocalsListHeadOffset = endian.readInt(dataInputStream);
            primordialThreadLocalsOffset = endian.readInt(dataInputStream);

            threadLocalsAreaSize = endian.readInt(dataInputStream);

            SAFEPOINT_LATCH = endian.readInt(dataInputStream);
            SAFEPOINTS_ENABLED_THREAD_LOCALS = endian.readInt(dataInputStream);
            SAFEPOINTS_DISABLED_THREAD_LOCALS = endian.readInt(dataInputStream);
            SAFEPOINTS_TRIGGERED_THREAD_LOCALS = endian.readInt(dataInputStream);
            NATIVE_THREAD_LOCALS = endian.readInt(dataInputStream);
            FORWARD_LINK = endian.readInt(dataInputStream);
            BACKWARD_LINK = endian.readInt(dataInputStream);
            ID = endian.readInt(dataInputStream);
            JNI_ENV = endian.readInt(dataInputStream);
            LAST_JAVA_FRAME_ANCHOR = endian.readInt(dataInputStream);
            TRAP_NUMBER = endian.readInt(dataInputStream);
            TRAP_INSTRUCTION_POINTER = endian.readInt(dataInputStream);
            TRAP_FAULT_ADDRESS = endian.readInt(dataInputStream);
            TRAP_LATCH_REGISTER = endian.readInt(dataInputStream);
            STACK_REFERENCE_MAP = endian.readInt(dataInputStream);
            STACK_REFERENCE_MAP_SIZE = endian.readInt(dataInputStream);
        }

        private int staticFieldPointerOffset(DataPrototype dataPrototype, Class javaClass, String staticFieldName) {
            final Pointer staticTupleOrigin = dataPrototype.objectToOrigin(ClassActor.fromJava(javaClass).staticTuple());
            final FieldActor fieldActor = ClassActor.fromJava(javaClass).findLocalStaticFieldActor(SymbolTable.makeSymbol(staticFieldName));
            return staticTupleOrigin.toInt() + fieldActor.offset();
        }

        private Header(DataPrototype dataPrototype, int stringInfoSize) {
            super(platform().endianness(), 0);
            final VMConfiguration vmConfiguration = vmConfig();
            isBigEndian = endianness() == Endianness.LITTLE ? 0 : 0xffffffff;
            identification = IDENTIFICATION;
            version = VERSION;
            randomID = UUID.randomUUID().hashCode();
            wordSize = platform().wordWidth().numberOfBytes;
            cacheAlignment = platform().dataModel().cacheAlignment;
            pageSize = platform().pageSize;
            vmRunMethodOffset = Static.getCriticalEntryPoint((ClassMethodActor) ClassRegistry.MaxineVM_run, CallEntryPoint.C_ENTRY_POINT).toInt();
            vmThreadAddMethodOffset = Static.getCriticalEntryPoint((ClassMethodActor) ClassRegistry.VmThread_add, CallEntryPoint.C_ENTRY_POINT).toInt();
            vmThreadRunMethodOffset = Static.getCriticalEntryPoint((ClassMethodActor) ClassRegistry.VmThread_run, CallEntryPoint.C_ENTRY_POINT).toInt();
            vmThreadAttachMethodOffset = Static.getCriticalEntryPoint((ClassMethodActor) ClassRegistry.VmThread_attach, CallEntryPoint.C_ENTRY_POINT).toInt();
            vmThreadDetachMethodOffset = Static.getCriticalEntryPoint((ClassMethodActor) ClassRegistry.VmThread_detach, CallEntryPoint.C_ENTRY_POINT).toInt();
            classRegistryOffset = dataPrototype.objectToOrigin(ClassRegistry.BOOT_CLASS_REGISTRY).toInt();
            this.stringInfoSize = stringInfoSize;
            relocationDataSize = dataPrototype.relocationData().length;
            heapSize = dataPrototype.heapData().length;
            codeSize = dataPrototype.codeData().length;

            dynamicHeapRegionsArrayFieldOffset = staticFieldPointerOffset(dataPrototype, InspectableHeapInfo.class, "dynamicHeapMemoryRegions");

            reservedVirtualSpaceSize = vmConfiguration.heapScheme().reservedVirtualSpaceSize();
            reservedVirtualSpaceFieldOffset = staticFieldPointerOffset(dataPrototype, Heap.class, "reservedVirtualSpace");
            bootRegionMappingConstraint = vmConfiguration.heapScheme().bootRegionMappingConstraint().ordinal();
            threadLocalsListHeadOffset = dataPrototype.objectToOrigin(VmThreadMap.ACTIVE).toInt() + ClassActor.fromJava(VmThreadMap.class).findLocalInstanceFieldActor("threadLocalsListHead").offset();
            primordialThreadLocalsOffset = staticFieldPointerOffset(dataPrototype, MaxineVM.class, "primordialThreadLocals");

            threadLocalsAreaSize = VmThreadLocal.threadLocalsAreaSize().toInt();

            SAFEPOINT_LATCH = VmThreadLocal.SAFEPOINT_LATCH.index;
            SAFEPOINTS_ENABLED_THREAD_LOCALS = VmThreadLocal.SAFEPOINTS_ENABLED_THREAD_LOCALS.index;
            SAFEPOINTS_DISABLED_THREAD_LOCALS = VmThreadLocal.SAFEPOINTS_DISABLED_THREAD_LOCALS.index;
            SAFEPOINTS_TRIGGERED_THREAD_LOCALS = VmThreadLocal.SAFEPOINTS_TRIGGERED_THREAD_LOCALS.index;
            NATIVE_THREAD_LOCALS = VmThreadLocal.NATIVE_THREAD_LOCALS.index;
            FORWARD_LINK = VmThreadLocal.FORWARD_LINK.index;
            BACKWARD_LINK = VmThreadLocal.BACKWARD_LINK.index;
            ID = VmThreadLocal.ID.index;
            JNI_ENV = VmThreadLocal.JNI_ENV.index;
            LAST_JAVA_FRAME_ANCHOR = VmThreadLocal.LAST_JAVA_FRAME_ANCHOR.index;
            TRAP_NUMBER = VmThreadLocal.TRAP_NUMBER.index;
            TRAP_INSTRUCTION_POINTER = VmThreadLocal.TRAP_INSTRUCTION_POINTER.index;
            TRAP_FAULT_ADDRESS = VmThreadLocal.TRAP_FAULT_ADDRESS.index;
            TRAP_LATCH_REGISTER = VmThreadLocal.TRAP_LATCH_REGISTER.index;
            STACK_REFERENCE_MAP = VmThreadLocal.STACK_REFERENCE_MAP.index;
            STACK_REFERENCE_MAP_SIZE = VmThreadLocal.STACK_REFERENCE_MAP_SIZE.index;
        }

        public void check() throws BootImageException {
            BootImageException.check(identification == IDENTIFICATION, "not a MaxineVM VM boot image file, wrong identification: " + identification);
            BootImageException.check(version == VERSION, "wrong version: " + version);
            BootImageException.check(wordSize == 4 || wordSize == 8, "illegal word size: " + wordSize);
            BootImageException.check(cacheAlignment > 4 && Ints.isPowerOfTwoOrZero(cacheAlignment), "implausible alignment size: " + cacheAlignment);
            BootImageException.check(pageSize >= Longs.K && pageSize % Longs.K == 0, "implausible page size: " + pageSize);
            BootImageException.check(!(bootRegionMappingConstraint > 0 && reservedVirtualSpaceSize == 0), "invalid boot region mapping constraint");
        }

        @Override
        public int size() {
            return fields().length * Ints.SIZE;
        }

        @Override
        public void write(OutputStream outputStream) throws IOException {
            for (Field field : fields()) {
                try {
                    endianness().writeInt(outputStream, field.getInt(this));
                } catch (IllegalAccessException illegalAccessException) {
                    ProgramError.unexpected();
                }
            }
        }
    }

    /**
     * The string info section in a boot image.
     *
     * <b>ATTENTION: this must match 'image_StringInfo' in "Native/substrate/image.h".</b>
     */
    public static final class StringInfo extends FieldSection {
        public final String buildLevelName;
        public final String processorModelName;
        public final String instructionSetName;
        public final String osName;

        public final String referencePackageName;
        public final String layoutPackageName;
        public final String heapPackageName;
        public final String monitorPackageName;
        public final String compilerPackageName;
        public final String compilationPackageName;
        public final String jitPackageName;
        public final String trampolinePackageName;
        public final String targetABIsPackageName;
        public final String runPackageName;

        public BuildLevel buildLevel() {
            return Enums.fromString(BuildLevel.class, buildLevelName);
        }

        public ProcessorModel processorModel() {
            return Enums.fromString(ProcessorModel.class, processorModelName);
        }

        public InstructionSet instructionSet() {
            return Enums.fromString(InstructionSet.class, instructionSetName);
        }

        public OS os() {
            return Enums.fromString(OS.class, osName);
        }

        public VMPackage referencePackage() {
            return (VMPackage) MaxPackage.fromName(referencePackageName);
        }

        public VMPackage layoutPackage() {
            return (VMPackage) MaxPackage.fromName(layoutPackageName);
        }

        public VMPackage heapPackage() {
            return (VMPackage) MaxPackage.fromName(heapPackageName);
        }

        public VMPackage monitorPackage() {
            return (VMPackage) MaxPackage.fromName(monitorPackageName);
        }

        public VMPackage compilerPackage() {
            return (VMPackage) MaxPackage.fromName(compilerPackageName);
        }

        public VMPackage compilationPackage() {
            return (VMPackage) MaxPackage.fromName(compilationPackageName);
        }

        public VMPackage jitPackage() {
            if (jitPackageName == null) {
                return null;
            }
            return (VMPackage) MaxPackage.fromName(jitPackageName);
        }

        public VMPackage trampolinePackage() {
            return (VMPackage) MaxPackage.fromName(trampolinePackageName);
        }

        public VMPackage targetABIsPackage() {
            return (VMPackage) MaxPackage.fromName(targetABIsPackageName);
        }

        public VMPackage runPackage() {
            return (VMPackage) MaxPackage.fromName(runPackageName);
        }

        private StringInfo(InputStream inputStream, int offset) throws IOException, Utf8Exception {
            super(offset);
            buildLevelName = Utf8.readString(inputStream);
            processorModelName = Utf8.readString(inputStream);
            instructionSetName = Utf8.readString(inputStream);
            osName = Utf8.readString(inputStream);

            referencePackageName = Utf8.readString(inputStream);
            layoutPackageName = Utf8.readString(inputStream);
            heapPackageName = Utf8.readString(inputStream);
            monitorPackageName = Utf8.readString(inputStream);
            compilerPackageName = Utf8.readString(inputStream);
            compilationPackageName = Utf8.readString(inputStream);
            jitPackageName =  Utf8.readString(inputStream);
            trampolinePackageName = Utf8.readString(inputStream);
            targetABIsPackageName = Utf8.readString(inputStream);
            runPackageName = Utf8.readString(inputStream);
        }

        private StringInfo(VMConfiguration vmConfiguration, int offset) {
            super(offset);
            buildLevelName = vmConfiguration.buildLevel.name();
            processorModelName = platform().processorModel().name();
            instructionSetName = platform().instructionSet().name();
            osName = platform().os.name();

            referencePackageName = vmConfiguration.referencePackage.name();
            layoutPackageName = vmConfiguration.layoutPackage.name();
            heapPackageName = vmConfiguration.heapPackage.name();
            monitorPackageName = vmConfiguration.monitorPackage.name();
            compilerPackageName = vmConfiguration.bootCompilerPackage.name();
            compilationPackageName = vmConfiguration.compilationPackage.name();
            // Jit Package is optional and may be null. In which case, fall back to the default compiler.
            if (vmConfiguration.jitCompilerPackage == null) {
                jitPackageName = compilerPackageName;
            } else {
                jitPackageName = vmConfiguration.jitCompilerPackage.name();
            }

            trampolinePackageName = vmConfiguration.trampolinePackage.name();
            targetABIsPackageName = vmConfiguration.targetABIsPackage.name();
            runPackageName = vmConfiguration.runPackage.name();
        }

        private void checkPackage(String packageName) throws BootImageException {
            BootImageException.check(MaxPackage.fromName(packageName) instanceof VMPackage, "not a VM package: " + packageName);
        }

        public void check() throws BootImageException {
            BootImageException.check(buildLevel() != null, "unknown build level: " + buildLevelName);
            BootImageException.check(processorModel() != null, "unknown processor model: " + processorModelName);
            BootImageException.check(instructionSet() != null, "unknown instruction set: " + instructionSetName);
            BootImageException.check(os() != null, "unknown operating system: " + osName);

            checkPackage(referencePackageName);
            checkPackage(layoutPackageName);
            checkPackage(heapPackageName);
            checkPackage(monitorPackageName);
            checkPackage(compilerPackageName);
            checkPackage(jitPackageName);
            checkPackage(trampolinePackageName);
            checkPackage(targetABIsPackageName);
            checkPackage(runPackageName);
        }

        @Override
        public int size() {
            int size = 0;
            for (Field field : fields()) {
                try {
                    final String string = (String) field.get(this);
                    size += Utf8.utf8Length(string) + 1;
                } catch (IllegalAccessException illegalAccessException) {
                    ProgramError.unexpected();
                }
            }
            return size;
        }

        @Override
        public void write(OutputStream outputStream) throws IOException {
            for (Field field : fields()) {
                try {
                    final String string = (String) field.get(this);
                    Utf8.writeString(outputStream, string);
                } catch (IllegalAccessException illegalAccessException) {
                    ProgramError.unexpected();
                }
            }
        }
        @Override
        public Class<?> fieldType() {
            return String.class;
        }
    }

    /**
     * The trailer section in a boot image.
     *
     * <b>ATTENTION: this must match 'image_Trailer' in "Native/substrate/image.h".</b>
     */
    public static final class Trailer extends IntSection {
        public final int randomID;
        public final int version;
        public final int identification;

        private Trailer(Header header, InputStream inputStream, int offset) throws IOException {
            super(header.endianness(), offset);
            randomID = endianness().readInt(inputStream);
            version = endianness().readInt(inputStream);
            identification = endianness().readInt(inputStream);
        }

        private Trailer(Header header, int offset) {
            super(header.endianness(), offset);
            randomID = header.randomID;
            version = header.version;
            identification = header.identification;
        }

        public void check(Header header) throws BootImageException {
            BootImageException.check(identification == header.identification, "inconsistent trailer identififcation");
            BootImageException.check(version == header.version, "inconsistent trailer version");
            BootImageException.check(randomID == header.randomID, "inconsistent trailer random ID");
        }
    }

    /**
     * Gets the class method actor for the first method with the specified name found
     * while traversing all the class method actors declared by a given class and
     * its super classes.
     * @param name name the method name
     * @param javaClass the class in which to start the search for a method named "run"
     *
     * @return the found method or null
     */
    public static ClassMethodActor getMethodActorFor(String n, Class<?> javaClass) {
        Utf8Constant name = SymbolTable.makeSymbol(n);
        final ClassMethodActor runMethodActor = ClassActor.fromJava(javaClass).findLocalClassMethodActor(name, null);
        if (runMethodActor != null) {
            return runMethodActor;
        }
        return getMethodActorFor(n, javaClass.getSuperclass());
    }

    public final Header header;
    public final StringInfo stringInfo;
    public final byte[] relocationData;
    public final byte[] padding;
    public final Trailer trailer;
    public final VMConfiguration vmConfiguration;

    private ByteBuffer heap;
    private ByteBuffer code;
    private ByteBuffer heapAndCode;
    private final File imageFile;

    /**
     * Creates a BootImage object representing the information in a given boot image file.
     */
    public BootImage(File file) throws BootImageException {
        this.imageFile = file;
        try {
            final FileInputStream fileInputStream = new FileInputStream(file);
            try {
                header = new Header(new DataInputStream(fileInputStream));
                header.check();
                stringInfo = new StringInfo(fileInputStream, header.size());
                stringInfo.check();
                BootImageException.check(header.stringInfoSize == stringInfo.size(), "inconsistent string area size");
                relocationData = new byte[header.relocationDataSize];
                fileInputStream.read(relocationData);
                this.padding = new byte[deltaToPageAlign(header.size() + stringInfo.size() + relocationData.length)];
                fileInputStream.read(padding);

                for (int i = 0; i < padding.length; ++i) {
                    BootImageException.check(padding[i] == 0, "all padding bytes should be 0");
                }
                BootImageException.check((heapOffset() % header.pageSize) == 0, "heap offset is not page-size aligned");
                BootImageException.check((codeOffset() % header.pageSize) == 0, "code offset is not page-size aligned");

                final DataModel dataModel = new DataModel(header.wordWidth(), header.endianness(), header.cacheAlignment);
                final ProcessorKind processorKind = new ProcessorKind(stringInfo.processorModel(), stringInfo.instructionSet(), dataModel);
                final Platform platform = new Platform(processorKind, stringInfo.os(), header.pageSize);
                Platform.set(platform);
                vmConfiguration = new VMConfiguration(stringInfo.buildLevel(),
                                                      platform,
                                                      stringInfo.referencePackage(),
                                                      stringInfo.layoutPackage(),
                                                      stringInfo.heapPackage(),
                                                      stringInfo.monitorPackage(),
                                                      stringInfo.compilerPackage(),
                                                      stringInfo.jitPackage(),
                                                      null,
                                                      stringInfo.compilationPackage(),
                                                      stringInfo.trampolinePackage(),
                                                      stringInfo.targetABIsPackage(), stringInfo.runPackage());

                fileInputStream.skip(header.heapSize + header.codeSize);
                int trailerOffset = codeOffset() + header.codeSize;
                trailer = new Trailer(header, fileInputStream, trailerOffset);
                trailer.check(header);
            } catch (Utf8Exception utf8Exception) {
                throw new BootImageException(utf8Exception);
            } finally {
                fileInputStream.close();
            }
        } catch (IOException ioException) {
            throw new BootImageException(ioException);
        }
    }

    /**
     * Used when constructing a boot image to be written to a file.
     */
    public BootImage(DataPrototype dataPrototype) throws BootImageException {
        this.vmConfiguration = vmConfig();
        this.stringInfo = new StringInfo(vmConfiguration, new Header(dataPrototype, 0).size());
        this.stringInfo.check();
        this.header = new Header(dataPrototype, stringInfo.size());
        this.header.check();
        this.relocationData = dataPrototype.relocationData();
        this.padding = new byte[deltaToPageAlign(header.size() + stringInfo.size() + relocationData.length)];
        this.heap = ByteBuffer.wrap(dataPrototype.heapData());
        this.code = ByteBuffer.wrap(dataPrototype.codeData());
        int trailerOffset = codeOffset() + header.codeSize;
        this.trailer = new Trailer(header, trailerOffset);
        this.imageFile = null;
    }

    public int relocationDataOffset() {
        return header.size() + stringInfo.size();
    }

    public int heapOffset() {
        return paddingOffset() + paddingSize();
    }

    public int codeOffset() {
        return heapOffset() + header.heapSize;
    }

    public int paddingOffset() {
        return relocationDataOffset() + header.relocationDataSize;
    }

    public int paddingSize() {
        return padding.length;
    }

    public synchronized ByteBuffer heap() {
        if (heap == null) {
            heap = mapSection(heapOffset(), header.heapSize);
        }
        ByteBuffer duplicate = heap.duplicate();
        duplicate.order(heap.order());
        return duplicate;
    }

    public synchronized ByteBuffer code() {
        if (code == null) {
            code = mapSection(codeOffset(), header.codeSize);
        }
        ByteBuffer duplicate = code.duplicate();
        duplicate.order(code.order());
        return duplicate;
    }

    public synchronized ByteBuffer heapAndCode() {
        if (heapAndCode == null) {
            heapAndCode = mapSection(heapOffset(), header.heapSize + header.codeSize);
        }
        ByteBuffer duplicate = heapAndCode.duplicate();
        duplicate.order(heapAndCode.order());
        return duplicate;
    }

    /**
     * Writes this image to a given stream.
     *
     * @param outputStream
     * @throws IOException
     */
    public void write(OutputStream outputStream) throws IOException {
        header.write(outputStream);
        stringInfo.write(outputStream);
        outputStream.write(relocationData);
        outputStream.write(padding);
        write(heap(), outputStream);
        write(code(), outputStream);
        trailer.write(outputStream);
    }

    private void write(ByteBuffer buffer, OutputStream outputStream) throws IOException {
        if (buffer.hasArray()) {
            outputStream.write(buffer.array(), buffer.arrayOffset(), buffer.limit());
        } else {
            byte[] array = new byte[buffer.limit()];
            buffer.get(array);
            outputStream.write(array);
        }
    }

    /**
     * Computes the amount to add to a given size to bump it up to the next page-aligned size.
     */
    private int deltaToPageAlign(int size) {
        final int pageSize = header.pageSize;
        final int rest = size % pageSize;
        if (rest == 0) {
            return 0;
        }
        return pageSize - rest;
    }

    /**
     * Maps a section of an image file to memory.
     *
     * @param offset the offset within the image file to map
     * @param size the size of the image file section to map
     * @return a buffer representing the mapped section
     */
    private ByteBuffer mapSection(int offset, int size) {
        assert imageFile != null : "Cannot map section of image for which underlying file is not available";
        try {
            final RandomAccessFile raf = new RandomAccessFile(imageFile, "r");
            final MappedByteBuffer buffer = raf.getChannel().map(MapMode.READ_ONLY, offset, size);
            raf.close();
            ByteOrder byteOrder = platform().endianness().asByteOrder();
            buffer.order(byteOrder);
            raf.close();
            return buffer;
        } catch (IOException e) {
            throw new InternalError("Error trying to map section of image file: " + e);
        }
    }

    private static native void nativeRelocate(long heap, long relocatedHeap, byte[] relocationDataPointer, int relocationDataSize, int isBigEndian, int wordSize);

    /**
     * Relocates the pointers in the heap and code. All the pointers are assumed to be
     * canonicalized; their current values assume that the heap and code start address 0.
     *
     * @param heap the physical address at which the (contiguous) heap and code reside
     * @param relocatedHeap the logical address to which the heap and code is being relocated
     */
    public void relocate(long heap, Address relocatedHeap) {
        nativeRelocate(heap, relocatedHeap.toLong(), relocationData, relocationData.length, header.isBigEndian, header.wordSize);
    }
}
