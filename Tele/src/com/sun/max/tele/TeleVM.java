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
package com.sun.max.tele;

import java.io.*;
import java.util.*;
import java.util.logging.*;

import com.sun.max.*;
import com.sun.max.collect.*;
import com.sun.max.ide.*;
import com.sun.max.jdwp.vm.core.*;
import com.sun.max.jdwp.vm.proxy.*;
import com.sun.max.jdwp.vm.proxy.VMValue.*;
import com.sun.max.memory.*;
import com.sun.max.program.*;
import com.sun.max.program.Classpath.*;
import com.sun.max.program.option.*;
import com.sun.max.tele.debug.*;
import com.sun.max.tele.debug.TeleProcess.*;
import com.sun.max.tele.debug.darwin.*;
import com.sun.max.tele.debug.guestvm.xen.*;
import com.sun.max.tele.debug.linux.*;
import com.sun.max.tele.debug.no.*;
import com.sun.max.tele.debug.solaris.*;
import com.sun.max.tele.field.*;
import com.sun.max.tele.grip.*;
import com.sun.max.tele.jdwputil.*;
import com.sun.max.tele.method.*;
import com.sun.max.tele.object.*;
import com.sun.max.tele.reference.*;
import com.sun.max.tele.type.*;
import com.sun.max.tele.value.*;
import com.sun.max.unsafe.*;
import com.sun.max.vm.*;
import com.sun.max.vm.actor.holder.*;
import com.sun.max.vm.code.*;
import com.sun.max.vm.compiler.*;
import com.sun.max.vm.debug.*;
import com.sun.max.vm.grip.*;
import com.sun.max.vm.layout.*;
import com.sun.max.vm.monitor.*;
import com.sun.max.vm.object.host.*;
import com.sun.max.vm.prototype.*;
import com.sun.max.vm.reference.*;
import com.sun.max.vm.reference.prototype.*;
import com.sun.max.vm.type.*;
import com.sun.max.vm.value.*;

/**
 * @author Bernd Mathiske
 * @author Athul Acharya
 * @author Michael Van De Vanter
 * @author Doug Simon
 * @author Thomas Wuerthinger
 */
public abstract class TeleVM implements VMAccess {

    private static final int TRACE_VALUE = 2;

    private static final String _TELE_LIBRARY_NAME = "tele";

    /**
     * The options controlling how a tele VM instance is {@linkplain #create(String...) created}.
     */
    public static class Options extends OptionSet {
        public final Option<Boolean> _debugOption = newBooleanOption("d", false,
            "Makes the inspector create a Maxine VM process as the target of inspection. If omitted or 'false', then the boot image is inspected.");
        public final Option<File> _bootImageFileOption = newFileOption("i", BinaryImageGenerator.getDefaultBootImageFilePath(),
            "Path to boot image file.");
        public final Option<File> _bootJarOption = newFileOption("j", BinaryImageGenerator.getDefaultBootImageJarFilePath(),
            "Boot jar file path.");
        public final Option<List<String>> _classpathOption = newStringListOption("cp", null, File.pathSeparatorChar,
            "Additional locations to use when searching for Java class files. These locations are searched after the jar file containing the " +
            "boot image classes but before the locations corresponding to the class path of this JVM process.");
        public final Option<List<String>> _sourcepathOption = newStringListOption("sourcepath", null, File.pathSeparatorChar,
            "Additional locations to use when searching for Java source files. These locations are searched before the default locations.");
        public final Option<Boolean> _relocateOption = newBooleanOption("b", false,
            "Specifies if the heap and code in the boot image is to be relocated. Ignored if -" + _debugOption.getName() + " is specified.");
        public final Option<String> _vmArguments = newStringOption("a", "",
            "Specifies the arguments to the target VM.");
        public final Option<File> _commandFileOption = newFileOption("c", "",
            "Executes the commands in a file on startup.");
        public final Option<Integer> _debuggeeIdOption = newIntegerOption("id", -1,
            "Id of debuggee to connect to.");
        public final Option<String> _logLevelOption = newStringOption("logLevel", Level.SEVERE.getName(),
            "Level to set for java.util.logging root logger.");
    }

    protected String  tracePrefix() {
        return "[TeleVM: " + Thread.currentThread().getName() + "] ";
    }

    /**
     * Creates a new {@code TeleVM} instance based on a given set of options.
     *
     * @param options the options controlling specifics of the TeleVM instance to be created
     * @return a new TeleVM instance
     */
    public static TeleVM create(Options options) throws BootImageException {
        HostObjectAccess.setMainThread(Thread.currentThread());

        final String logLevel = options._logLevelOption.getValue();
        try {
            LogManager.getLogManager().getLogger("").setLevel(Level.parse(logLevel));
        } catch (IllegalArgumentException e) {
            ProgramWarning.message("Invalid level specified for java.util.logging root logger: " + logLevel + " [using " + Level.SEVERE + "]");
            LogManager.getLogManager().getLogger("").setLevel(Level.SEVERE);
        }

        TeleVM teleVM = null;

        // Configure the prototype class loader gets the class files used to build the image
        Classpath classpathPrefix = Classpath.EMPTY;
        final List<String> classpathList = options._classpathOption.getValue();
        if (classpathList != null) {
            final Classpath extraClasspath = new Classpath(classpathList.toArray(new String[classpathList.size()]));
            classpathPrefix = classpathPrefix.prepend(extraClasspath);
        }
        classpathPrefix = classpathPrefix.prepend(options._bootJarOption.getValue().getAbsolutePath());
        checkClasspath(classpathPrefix);
        final Classpath classpath = Classpath.fromSystem().prepend(classpathPrefix);
        PrototypeClassLoader.setClasspath(classpath);

        Prototype.loadLibrary(_TELE_LIBRARY_NAME);
        final File bootImageFile = options._bootImageFileOption.getValue();

        Classpath sourcepath = JavaProject.getSourcePath(true);
        final List<String> sourcepathList = options._sourcepathOption.getValue();
        if (sourcepathList != null) {
            sourcepath = sourcepath.prepend(new Classpath(sourcepathList.toArray(new String[sourcepathList.size()])));
        }
        checkClasspath(sourcepath);

        final String value = options._vmArguments.getValue();
        final String[] commandLineArguments = "".equals(value) ? new String[0] : value.split(" ");

        if (options._debugOption.getValue()) {
            teleVM = create(bootImageFile, sourcepath, commandLineArguments, options._debuggeeIdOption.getValue());
            try {
                teleVM.advanceToJavaEntryPoint();
            } catch (IOException ioException) {
                throw new BootImageException(ioException);
            }

            final File commandFile = options._commandFileOption.getValue();
            if (commandFile != null && !commandFile.equals("")) {
                FileCommands.executeCommandsFromFile(teleVM, commandFile.getPath());
            }

        } else {
            teleVM = createReadOnly(bootImageFile, sourcepath, !options._relocateOption.getValue());
        }

        return teleVM;
    }

    private static void checkClasspath(Classpath classpath) {
        for (Entry classpathEntry : classpath.entries()) {
            if (classpathEntry.isPlainFile()) {
                ProgramWarning.message("Class path entry is neither a directory nor a JAR file: " + classpathEntry);
            }
        }
    }

    /**
     * Creates a tele VM instance that is read-only and is only useful for inspecting a boot image.
     *
     * @param bootImageFile the file containing the boot image
     * @param sourcepath the source code path to search for class or interface definitions
     * @param relocate specifies if the heap and code sections in the boot image are to be relocated
     * @return
     * @throws BootImageException
     * @throws IOException
     */
    private static TeleVM createReadOnly(File bootImageFile, Classpath sourcepath, boolean relocate) throws BootImageException {
        final BootImage bootImage = new BootImage(bootImageFile);
        return new ReadOnlyTeleVM(bootImageFile, bootImage, sourcepath, relocate);
    }

    private static final Logger LOGGER = Logger.getLogger(TeleVM.class.getName());

    private TeleNativeThread _registeredSingleStepThread;
    private TeleNativeThread _registeredStepOutThread;

    // Factory for creating fake object providers that represent Java objects
    // living in the JDWP server.
    private JavaProviderFactory _javaProviderFactory;

    private static VMPackage getInspectorGripPackage(VMPackage gripPackage) {
        final MaxPackage vmGripRootPackage = new com.sun.max.vm.grip.Package();
        final String suffix = gripPackage.name().substring(
                vmGripRootPackage.name().length());
        final MaxPackage inspectorGripRootPackage = new com.sun.max.tele.grip.Package();
        return (VMPackage) MaxPackage.fromName(inspectorGripRootPackage.name()
                + suffix);
    }

    private static MaxineVM createVM(BootImage bootImage) {
        final VMConfiguration b = bootImage.vmConfiguration();
        final VMConfiguration vmConfiguration = new VMConfiguration(
                b.buildLevel(),
                b.platform(),
                getInspectorGripPackage(b.gripPackage()),
                new com.sun.max.tele.reference.plain.Package(),
                    b.layoutPackage(), b.heapPackage(), b.monitorPackage(),
                b.compilerPackage(), b.jitPackage(), b.trampolinePackage(),
                    b.targetABIsPackage(), b.runPackage());
        vmConfiguration.loadAndInstantiateSchemes();

        final MaxineVM vm = new MaxineVM(vmConfiguration);
        MaxineVM.setTarget(vm);
        MaxineVM.setGlobalHostOrTarget(vm);
        HackJDK.checkVMFlags();
        new JavaPrototype(vm.configuration(), false);
        return vm;
    }

    private final MaxineVM _vm;

    public final MaxineVM maxineVM() {
        return _vm;
    }

    private final BootImage _bootImage;

    public final BootImage bootImage() {
        return _bootImage;
    }

    private final File _bootImageFile;

    public final File bootImageFile() {
        return _bootImageFile;
    }

    final File _programFile;

    public final File programFile() {
        return _programFile;
    }

    private final TeleProcess _teleProcess;

    public TeleProcess teleProcess() {
        return _teleProcess;
    }

    protected abstract TeleProcess createTeleProcess(String[] commandLineArguments, int id);

    /**
     * Gets a pointer to the boot image in the remote VM. This may require executing the remote VM up to the
     * point where it loads or memory maps the boot image.
     *
     * @throws BootImageException if the address of the boot image could not be obtained
     */
    protected abstract Pointer loadBootImage() throws BootImageException;

    /**
     * Determines if this VM is read-only. The operations that try to write to the memory of a read-only VM
     * or change its execution state will result in a {@link TeleVMCannotBeModifiedError}.
     */
    public boolean isReadOnly() {
        return false;
    }

    /**
     * Determines if the heap and code sections in the boot image have been relocated.
     */
    public boolean isBootImageRelocated() {
        return true;
    }

    private final Pointer _bootImageStart;

    public final Pointer bootImageStart() {
        return _bootImageStart;
    }

    private static final String _PROGRAM_NAME = "maxvm";

    private TeleFields _fields;

    public final TeleFields fields() {
        return _fields;
    }

    private TeleMethods _methods;

    public final TeleMethods methods() {
        return _methods;
    }

    /**
     * Gets the classpath used when searching for class files.
     */
    public Classpath classpath() {
        return PrototypeClassLoader.PROTOTYPE_CLASS_LOADER.classpath();
    }

    private final Classpath _sourcepath;

    public Classpath sourcepath() {
        return _sourcepath;
    }

    private int _interpreterUseLevel = 0;

    protected TeleVM(File bootImageFile, BootImage bootImage, Classpath sourcepath, String[] commandlineArguments, int id) throws BootImageException {
        _bootImageFile = bootImageFile;
        _bootImage = bootImage;
        _sourcepath = sourcepath;
        _vm = createVM(_bootImage);
        _programFile = new File(bootImageFile.getParent(), _PROGRAM_NAME);
        _teleProcess = createTeleProcess(commandlineArguments, id);
        _bootImageStart = loadBootImage();
        _fields = new TeleFields(this);
        _methods = new TeleMethods(this);

        _teleHeapManager = TeleHeapManager.make(this);

        _teleProcess.addStateListener(_model);
        _javaProviderFactory = new JavaProviderFactory(this, null);

        final TeleGripScheme teleGripScheme = (TeleGripScheme) _vm.configuration().gripScheme();
        teleGripScheme.setTeleVM(this);
    }

    private static TeleVM create(File bootImageFile, Classpath sourcepath, String[] commandlineArguments, int id) throws BootImageException {
        final BootImage bootImage = new BootImage(bootImageFile);
        TeleVM teleVM = null;
        switch (bootImage.vmConfiguration().platform().operatingSystem()) {
            case DARWIN:
                teleVM = new DarwinTeleVM(bootImageFile, bootImage, sourcepath, commandlineArguments, id);
                break;
            case LINUX:
                teleVM = new LinuxTeleVM(bootImageFile, bootImage, sourcepath, commandlineArguments, id);
                break;
            case SOLARIS:
                teleVM = new SolarisTeleVM(bootImageFile, bootImage, sourcepath, commandlineArguments, id);
                break;
            case GUESTVM:
                teleVM = new GuestVMXenTeleVM(bootImageFile, bootImage, sourcepath, commandlineArguments, id);
                break;
            default:
                Problem.unimplemented();
        }
        return teleVM;
    }

    public int interpreterUseLevel() {
        return _interpreterUseLevel;
    }

    public void setInterpreterUseLevel(int interpreterUseLevel) {
        _interpreterUseLevel = interpreterUseLevel;
    }

    public VMConfiguration vmConfiguration() {
        return maxineVM().configuration();
    }

    public TeleGripScheme gripScheme() {
        return (TeleGripScheme) vmConfiguration().gripScheme();
    }

    public ReferenceScheme referenceScheme() {
        return vmConfiguration().referenceScheme();
    }

    public LayoutScheme layoutScheme() {
        return vmConfiguration().layoutScheme();
    }

    public CompilerScheme compilerScheme() {
        return vmConfiguration().compilerScheme();
    }

    public MonitorScheme monitorScheme() {
        return vmConfiguration().monitorScheme();
    }

    /**
     * @param address a memory location in the {@link TeleVM}.
     *
     * @return whether the location is either in the object heap or in the code  regions of the {@link TeleVM}..
     */
    private boolean heapOrCodeContains(Address address) {
        if (address.isZero()) {
            return false;
        }
        return _teleHeapManager.contains(address) || _teleCodeManager.contains(address);
    }

    /**
     * @param address a memory location in the {@link TeleVM}
     * @return the allocated {@link MemoryRegion} containing the address, null if not in any known region.
     */
    public MemoryRegion memoryRegionContaining(Address address) {
        MemoryRegion memoryRegion = _teleHeapManager.regionContaining(address);
        if (memoryRegion == null) {
            memoryRegion = _teleCodeManager.regionContaining(address);
            if (memoryRegion == null) {
                final TeleNativeThread thread = teleProcess().threadContaining(address);
                if (thread != null) {
                    memoryRegion = thread.stack();
                }
            }
        }
        return memoryRegion;
    }

    /**
     * @param address a memory location in the {@link TeleVM}.
     * @return whether the location is either in the object heap, the code
     *         regions, or a stack region of the VM.
     */
    public boolean heapOrCodeOrStackContains(Address address) {
        return heapOrCodeContains(address) || teleProcess().threadContaining(address) != null;
    }

    private RemoteTeleGrip createTemporaryRemoteTeleGrip(Word rawGrip) {
        return gripScheme().createTemporaryRemoteTeleGrip(rawGrip.asAddress());
    }

    public RemoteTeleGrip temporaryRemoteTeleGripFromOrigin(Word origin) {
        return gripScheme().temporaryRemoteTeleGripFromOrigin(origin);
    }

    /**
     * Determines if a given pointer is a valid heap object origin in the
     * {@link TeleVM}.
     */
    public boolean isValidOrigin(Pointer origin) {
        if (origin.isZero()) {
            return false;
        }
        final Pointer cell = layoutScheme().generalLayout().originToCell(origin);
        Pointer p = cell;
        if (_bootImage.vmConfiguration().buildLevel() == BuildLevel.DEBUG) {
            p = p.minus(Word.size()); // can the tag be accessed?
        }
        if (!heapOrCodeContains(p)) {
            return false;
        }
        if (!areReferencesValid()) {
            if (_teleHeapManager.dynamicHeapContains(origin)) {
                return false;
            }
        }
        if (_bootImage.vmConfiguration().buildLevel() == BuildLevel.DEBUG) {
            try {
                final Word tag = teleProcess().dataAccess().getWord(cell, 0, -1);
                return DebugHeap.isValidCellTag(tag);
            } catch (DataIOError dataAccessError) {
                return false;
            }
        }

        // Keep following hub pointers until the same hub is traversed twice or
        // an address outside of heap or code
        // region(s) is encountered.
        Word hubWord = layoutScheme().generalLayout().readHubReferenceAsWord(
                temporaryRemoteTeleGripFromOrigin(origin));
        for (int i = 0; i < 3; i++) { // longest expected chain: staticTuple
                                        // -> staticHub -> dynamicHub ->
                                        // dynamicHub
            final RemoteTeleGrip hubGrip = createTemporaryRemoteTeleGrip(hubWord);
            if (!heapOrCodeContains(hubGrip.toOrigin())) {
                return false;
            }
            final Word nextHubWord = layoutScheme().generalLayout().readHubReferenceAsWord(hubGrip);
            if (nextHubWord.equals(hubWord)) {
                return true;
            }
            hubWord = nextHubWord;
        }
        return false;
    }

    public boolean isValidGrip(Grip grip) {
        if (!areReferencesValid()) {
            final TeleGrip teleGrip = (TeleGrip) grip;
            if (teleGrip instanceof MutableTeleGrip) {
                return false;
            }
        }
        if (grip instanceof LocalTeleGrip) {
            return true;
        }
        try {
            return isValidOrigin(grip.toOrigin());
        } catch (TeleError teleError) {
            return false;
        }
    }

    public boolean isValidReference(Reference reference) {
        return isValidGrip(reference.toGrip());
    }

    public void checkGrip(Grip grip) {
        final Pointer origin = grip.toOrigin();
        if (!isValidOrigin(origin)) {
            throw new TeleError("not a valid origin: " + origin);
        }
    }

    private void checkReference(Reference reference) {
        checkGrip(reference.toGrip());
    }

    public Reference wordToReference(Word word) {
        return referenceScheme().fromGrip(gripScheme().fromWord(word));
    }

    public Reference readReference(Address pointer) {
        return wordToReference(teleProcess().dataAccess().readWord(pointer));
    }

    public Reference readReference(Reference reference, int offset) {
        checkReference(reference);
        return wordToReference(reference.readWord(offset));
    }

    public Reference getReference(Address pointer, int index) {
        return wordToReference(teleProcess().dataAccess().getWord(pointer, 0,
                index));
    }

    public Reference getReference(Reference reference, int index) {
        checkReference(reference);
        return wordToReference(layoutScheme().wordArrayLayout().getWord(
                reference, index));
    }

    /**
     * @param stringReference A {@link String} object in the {@link TeleVM}.
     * @return A local {@link String} representing the object's contents.
     */
    public String getString(Reference stringReference) {
        final Reference valueReference = fields().String_value.readReference(stringReference);
        checkReference(valueReference);
        int offset = fields().String_offset.readInt(stringReference);
        final int count = fields().String_count.readInt(stringReference);
        final char[] chars = new char[count];
        final CharArrayLayout charArrayLayout = layoutScheme().charArrayLayout();
        for (int i = 0; i < count; i++) {
            chars[i] = charArrayLayout.getChar(valueReference, offset);
            offset++;
        }
        return new String(chars);
    }

    /**
     * Gets a canonical local {@link ClassActor} for the named class, creating one if needed by loading the class from
     * the classpath using the {@link PrototypeClassLoader#PROTOTYPE_CLASS_LOADER}.
     *
     * @param name the name of a class
     * @return Local {@link ClassActor} corresponding to the class, possibly created by loading it from classpath.
     * @throws ClassNotFoundException if not already loaded and unavailable on the classpath.
     */
    private ClassActor makeClassActor(String name) throws ClassNotFoundException {
        // The VM registry includes all ClassActors for classes loaded locally
        // using the prototype class loader
        ClassActor classActor = ClassRegistry.vmClassRegistry().get(
                JavaTypeDescriptor.getDescriptorForJavaString(name));
        if (classActor == null) {
            // Try to load the class from the local classpath.
            if (name.endsWith("[]")) {
                classActor = ClassActorFactory.createArrayClassActor(makeClassActor(name.substring(0,
                                name.length() - 2)));
            } else {
                classActor = PrototypeClassLoader.PROTOTYPE_CLASS_LOADER.makeClassActor(
                                JavaTypeDescriptor.getDescriptorForWellFormedTupleName(name));
            }
        }
        return classActor;
    }

    /**
     * Gets a canonical local {@link ClassActor} corresponding to a
     * {@link ClassActor} in the {@link TeleVM}, creating one if needed by
     * loading the class using the
     * {@link PrototypeClassLoader#PROTOTYPE_CLASS_LOADER} from either the
     * classpath, or if not found on the classpath, by copying the classfile
     * from the {@link TeleVM}.
     *
     * @param classActorReference
     *            A {@link ClassActor} in the {@link TeleVM}.
     * @return Local, equivalent {@link ClassActor}, possibly created by
     *         loading from the classpath, or if not found, by copying and
     *         loading the classfile from the {@link TeleVM}
     */
    public ClassActor makeClassActor(Reference classActorReference) {
        final Reference utf8ConstantReference = fields().Actor_name.readReference(classActorReference);
        final Reference stringReference = fields().Utf8Constant_string.readReference(utf8ConstantReference);
        final String name = getString(stringReference);
        try {
            return makeClassActor(name);
        } catch (ClassNotFoundException classNotFoundException) {
            // Not loaded and not available on local classpath; load by copying
            // classfile from the {@link TeleVM}.
            final Reference byteArrayReference = fields().ClassActor_classfile.readReference(classActorReference);
            final TeleArrayObject teleByteArrayObject = (TeleArrayObject) TeleObject.make(this, byteArrayReference);
            TeleError.check(teleByteArrayObject != null,
                    "could not find class actor: " + name);
            final byte[] classfile = (byte[]) teleByteArrayObject.shallowCopy();
            return PrototypeClassLoader.PROTOTYPE_CLASS_LOADER.makeClassActor(
                    name, classfile);
        }
    }

    /**
     * Gets a canonical local {@classActor} corresponding to the type of a heap object in the targetVM, creating one if
     * needed by loading the class using the {@link PrototypeClassLoader#PROTOTYPE_CLASS_LOADER} from either the
     * classpath, or if not found on the classpath, by copying the classfile from the {@link TeleVM}.
     *
     * @param objectReference An {@link Object} in the {@link TeleVM}.
     * @return Local {@link ClassActor} representing the type of the object.
     */
    public ClassActor makeClassActorForTypeOf(Reference objectReference) {
        checkReference(objectReference);
        final Reference hubReference = wordToReference(layoutScheme().generalLayout().readHubReferenceAsWord(objectReference));
        final Reference classActorReference = fields().Hub_classActor.readReference(hubReference);
        return makeClassActor(classActorReference);
    }

    /**
     * @param objectReference
     *            An {@link Object} in the {@link TeleVM}.
     * @return Local {@link Hub}, equivalent to the hub of the object.
     */
    public Hub makeLocalHubForObject(Reference objectReference) {
        checkReference(objectReference);
        final Reference hubReference = wordToReference(layoutScheme().
                generalLayout().readHubReferenceAsWord(objectReference));
        final Reference classActorReference = fields().Hub_classActor.
                readReference(hubReference);
        final ClassActor objectClassActor = makeClassActor(classActorReference);
        final ClassActor hubClassActor = makeClassActorForTypeOf(hubReference);
        return (StaticHub.class.isAssignableFrom(hubClassActor.toJava())) ? objectClassActor.staticHub()
                : objectClassActor.dynamicHub();
    }

    public Value getElementValue(Kind kind, Reference reference, int index) {
        switch (kind.asEnum()) {
            case BYTE:
                return ByteValue.from(layoutScheme().byteArrayLayout().getByte(reference, index));
            case BOOLEAN:
                return BooleanValue.from(layoutScheme().booleanArrayLayout().getBoolean(reference, index));
            case SHORT:
                return ShortValue.from(layoutScheme().shortArrayLayout().getShort(reference, index));
            case CHAR:
                return CharValue.from(layoutScheme().charArrayLayout().getChar(reference, index));
            case INT:
                return IntValue.from(layoutScheme().intArrayLayout().getInt(reference, index));
            case FLOAT:
                return FloatValue.from(layoutScheme().floatArrayLayout().getFloat(reference, index));
            case LONG:
                return LongValue.from(layoutScheme().longArrayLayout().getLong(reference, index));
            case DOUBLE:
                return DoubleValue.from(layoutScheme().doubleArrayLayout().getDouble(reference, index));
            case WORD:
                return new WordValue(layoutScheme().wordArrayLayout().getWord(reference, index));
            case REFERENCE:
                checkReference(reference);
                return TeleReferenceValue.from(this, wordToReference(layoutScheme().
                                wordArrayLayout().getWord(reference, index)));
            default:
                throw ProgramError.unexpected("unknown array kind");
        }
    }

    private TeleCodeManager _teleCodeManager;

    /**
     * @return surrogate for the singleton {@link CodeManager} in the
     *         {@link TeleVM}, access to code state.
     */
    public TeleCodeManager teleCodeManager() {
        if (_teleCodeManager == null) {
            _teleCodeManager = TeleCodeManager.make(this);
        }
        return _teleCodeManager;
    }

    private final TeleHeapManager _teleHeapManager;

    public TeleHeapManager teleHeapManager() {
        return _teleHeapManager;
    }

    private TeleClassRegistry _teleClassRegistry;

    /**
     * @return a registry that identifies all classes known to have been loaded
     *         in the {@link TeleVM}, loaded with key data that doesn't require
     *         loading the class here until needed.
     */
    public synchronized TeleClassRegistry teleClassRegistry() {
        assert _teleClassRegistry != null;
        return _teleClassRegistry;
    }

    private TeleCodeRegistry _teleCodeRegistry;

    public synchronized TeleCodeRegistry teleCodeRegistry() {
        if (_teleCodeRegistry == null) {
            _teleCodeRegistry = new TeleCodeRegistry(this);
        }
        return _teleCodeRegistry;
    }

    /**
     * @return an ordered set of {@link TypeDescriptor}s for classes loaded in
     *         the {@link TeleVM}, plus classes found on the class path.
     */
    public synchronized Iterable<TypeDescriptor> loadableTypeDescriptors() {
        final SortedSet<TypeDescriptor> typeDescriptors = new TreeSet<TypeDescriptor>();
        for (TypeDescriptor typeDescriptor : _teleClassRegistry.typeDescriptors()) {
            typeDescriptors.add(typeDescriptor);
        }
        if (_typesOnClasspath == null) {
            updateLoadableTypeDescriptorsFromClasspath();
        }
        typeDescriptors.addAll(_typesOnClasspath);
        return typeDescriptors;
    }

    private Set<TypeDescriptor> _typesOnClasspath;

    /**
     * Updates the set of types that are available on the
     * {@linkplain #classpath() class path} by scanning the class path. This
     * scan will be performed automatically the first time
     * {@link #loadableTypeDescriptors()} is called. However, it should also be
     * performed any time the set of classes available on the class path may
     * have changed.
     */
    public void updateLoadableTypeDescriptorsFromClasspath() {
        final Set<TypeDescriptor> typesOnClasspath = new TreeSet<TypeDescriptor>();
        Trace.begin(TRACE_VALUE, tracePrefix() + "searching classpath for class files");
        new ClassSearch() {
            @Override
            protected boolean visitClass(String className) {
                if (!className.endsWith("package-info")) {
                    final String typeDescriptorString = "L"
                            + className.replace('.', '/') + ";";
                    typesOnClasspath.add(JavaTypeDescriptor.parseTypeDescriptor(typeDescriptorString));
                }
                return true;
            }
        }.run(PrototypeClassLoader.PROTOTYPE_CLASS_LOADER.classpath());
        Trace.end(TRACE_VALUE, tracePrefix() + "searching classpath for class files ["
                + typesOnClasspath.size() + " types found]");
        _typesOnClasspath = typesOnClasspath;
    }

    public Reference bootClassRegistryReference() {
        return originToReference(_bootImageStart.plus(_bootImage.header()._classRegistryOffset));
    }

    public Reference originToReference(final Pointer origin) {
        return referenceScheme().fromGrip(gripScheme().fromOrigin(origin));
    }

    public Pointer referenceToCell(Reference reference) {
        return layoutScheme().generalLayout().originToCell(reference.toOrigin());
    }

    public Reference cellToReference(Pointer cell) {
        return originToReference(layoutScheme().generalLayout().cellToOrigin(cell));
    }

    public void advanceToJavaEntryPoint() throws IOException {
        _messenger.enable();
        final Address startEntryPoint = bootImageStart().plus(bootImage().header()._vmRunMethodOffset);
        try {
            teleProcess().controller().runToInstruction(startEntryPoint, true, false);
        } catch (Exception exception) {
            throw new IOException(exception);
        }
    }

    /**
     * @return a collection of all current threads in the targetVM, ordered by
     *         threadID.
     */
    public IterableWithLength<TeleNativeThread> allThreads() {
        return _teleProcess.threads();
    }

    /**
     * @return Whether the thread is a known thread.
     */
    public boolean isValidThread(TeleNativeThread thread) {
        for (TeleNativeThread teleNativeThread : _teleProcess.threads()) {
            if (thread == teleNativeThread) {
                return true;
            }
        }
        return false;
    }

    private boolean _isInGC = false;

    /**
     * @return whether a GC is underway in the {@link TeleVM}.
     */
    public boolean isInGC() {
        return _isInGC;
    }

    /**
     * @return whether {@link Reference}s to memory in the {@link TeleVM} are reliable.
     */
    private boolean areReferencesValid() {
        return !_isInGC;
    }

    /**
     * Identifies the most recent GC for which the local copy of the tele root
     * table in the {@link TeleVM} is valid.
     */
    private long _cachedCollectionEpoch;

    private Tracer _refreshReferencesTracer = new Tracer("refresh references");

    /**
     * Refreshes the values that describe {@link TeleVM} state such as the
     * current GC epoch.
     */
    private void refreshReferences() {
        Trace.begin(TRACE_VALUE, _refreshReferencesTracer);
        final long startTimeMillis = System.currentTimeMillis();
        final long teleRootEpoch = fields().TeleHeapInfo_rootEpoch.readLong(this);
        final long teleCollectionEpoch = fields().TeleHeapInfo_collectionEpoch.readLong(this);
        if (teleCollectionEpoch != teleRootEpoch) {
            // A GC is in progress, local cache is out of date by definition but can't update yet
            assert teleCollectionEpoch != _cachedCollectionEpoch;
            _isInGC = true;
        } else if (teleCollectionEpoch == _cachedCollectionEpoch) {
            // GC not in progress, local cache is up to date
            assert !_isInGC;
        } else {
            // GC not in progress, local cache is out of date
            gripScheme().refresh();
            _cachedCollectionEpoch = teleCollectionEpoch;
            _isInGC = false;
        }
        Trace.end(TRACE_VALUE, _refreshReferencesTracer, startTimeMillis);
    }

    public void fireThreadEvents() {
        for (TeleNativeThread thread : _teleProcess.deadThreads()) {
            fireThreadDiedEvent(thread);
        }
        for (TeleNativeThread thread : _teleProcess.startedThreads()) {
            fireThreadStartedEvent(thread);
        }
    }

    private final Tracer _refreshTracer = new Tracer("refresh");

    /**
     * Updates all cached information about the state of the running VM.
     */
    public synchronized void refresh() {
        Trace.begin(TRACE_VALUE, _refreshTracer);
        final long startTimeMillis = System.currentTimeMillis();
        if (_teleClassRegistry == null) {
            // Must delay creation/initialization of the {@link TeleClassRegistry} until after
            // we hit the first execution breakpoint; otherwise addresses won't have been relocated.
            // This depends on the {@TeleHeapManager} already existing.
            _teleClassRegistry = new TeleClassRegistry(this);
            // Can only fully initialize the {@link TeleHeapManager} once
            // the {@TeleClassRegistry} is fully initialized, otherwise there's a cycle.
            _teleHeapManager.initialize();
        }
        refreshReferences();
        if (areReferencesValid()) {
            _teleHeapManager.refresh();
            _teleClassRegistry.refresh();
        }
        Trace.end(TRACE_VALUE, _refreshTracer, startTimeMillis);
    }

    public ReferenceValue createReferenceValue(Reference value) {
        if (value instanceof TeleReference) {
            return TeleReferenceValue.from(this, value);
        } else if (value instanceof PrototypeReference) {
            return TeleReferenceValue.from(this, Reference.fromJava(value.toJava()));
        }
        throw ProgramError.unexpected("Got a non-Prototype, non-Tele reference in createReferenceValue");
    }

    /**
     * Uses the configured {@linkplain #sourcepath() source path} to search for a source file corresponding to a given
     * class actor.
     *
     * @param classActor
     *            the class for which a source file is to be found
     * @return the source file corresponding to {@code classActor} or null if so such source file can be found
     */
    public File findJavaSourceFile(ClassActor classActor) {
        final String sourceFilePath = classActor.sourceFilePath();
        return _sourcepath.findFile(sourceFilePath);
    }

    private TeleMessenger _messenger = new VMTeleMessenger(this);

    public TeleMessenger messenger() {
        return _messenger;
    }

    private final TeleBytecodeBreakpoint.Factory _bytecodeBreakpointFactory = new TeleBytecodeBreakpoint.Factory(
            this);

    public TeleBytecodeBreakpoint.Factory bytecodeBreakpointFactory() {
        return _bytecodeBreakpointFactory;
    }

    @Override
    public StringProvider createString(String s) {
        final VMValue value = createJavaObjectValue(s, String.class);
        assert value.asProvider() != null : "Must be a provider value object";
        assert value.asProvider() instanceof StringProvider : "Must be a String provider object";
        return (StringProvider) value.asProvider();
    }

    @Override
    public void dispose() {
        // TODO: Consider implementing disposal of the VM when told so by a JDWP
        // command.
        LOGGER.warning("Asked to DISPOSE VM, doing nothing");
    }

    @Override
    public void exit(int code) {
        // TODO: Consider implementing exiting the VM when told so by a JDWP
        // command.
        LOGGER.warning("Asked to EXIT VM, doing nothing");
    }

    /**
     * Tries to find a JDWP ObjectProvider that represents the object that is
     * referenced by the parameter.
     *
     * @param reference
     *            a reference to the object that should be represented as a JDWP
     *            ObjectProvider
     * @return a JDWP ObjectProvider object or null, if no object is found at
     *         the address specified by the reference
     */
    private ObjectProvider findObject(Reference reference) {
        if (isValidOrigin(reference.toOrigin())) {
            return TeleObject.make(this, reference);
        }
        return null;
    }

    @Override
    public ReferenceTypeProvider[] getAllReferenceTypes() {
        return _teleClassRegistry.teleClassActors();
    }

    @Override
    public ThreadProvider[] getAllThreads() {
        final ThreadProvider[] threadProviders = new ThreadProvider[allThreads().length()];
        return Iterables.toCollection(allThreads()).toArray(threadProviders);
    }

    @Override
    public ReferenceTypeProvider[] getReferenceTypesBySignature(String signature) {

        // Always fake the Object type. This means that calls to all methods of
        // the Object class will be reflectively delegated to the Object class
        // that lives
        // on the Tele side not to the Object class in the VM.
        if (signature.equals("Ljava/lang/Object;")) {
            return new ReferenceTypeProvider[] {getReferenceType(Object.class)};
        }

        // Try to find a matching class actor that lives within the VM based on
        // the signature.
        final AppendableSequence<ReferenceTypeProvider> result = new LinkSequence<ReferenceTypeProvider>();
        for (TypeDescriptor td : _teleClassRegistry.typeDescriptors()) {
            if (td.toString().equals(signature)) {
                final TeleClassActor tca = _teleClassRegistry.findTeleClassActorByType(td);

                // Do not include array types, there should always be faked in
                // order to be able to call newInstance on them. Arrays that are
                // created this way then do
                // not really live within the VM, but on the JDWP server side.
                if (!(tca instanceof TeleArrayClassActor)) {
                    result.append(tca);
                }
            }
        }

        // If no class living in the VM was found, try to lookup Java class
        // known to the JDWP server. If such a class is found, then a JDWP
        // reference type is faked for it.
        if (result.length() == 0) {
            try {
                final Class klass = JavaTypeDescriptor.resolveToJavaClass(
                        JavaTypeDescriptor.parseTypeDescriptor(signature), getClass().getClassLoader());
                result.append(_javaProviderFactory.getReferenceTypeProvider(klass));
            } catch (NoClassDefFoundError e) {
                LOGGER.log(Level.SEVERE,
                        "Error while looking up class based on signature", e);
            }
        }

        return Sequence.Static.toArray(result, ReferenceTypeProvider.class);
    }

    @Override
    public void resume() {

        if (teleProcess().state() == TeleProcess.State.STOPPED) {

            if (_registeredSingleStepThread != null) {

                // There has been a thread registered for performing a single
                // step => perform single step instead of resume.
                try {
                    LOGGER.info("Doing single step instead of resume!");
                    teleProcess().controller().singleStep(
                            _registeredSingleStepThread, false);
                } catch (OSExecutionRequestException e) {
                    LOGGER.log(
                                    Level.SEVERE,
                                    "Unexpected error while performing a single step in the VM",
                                    e);
                } catch (InvalidProcessRequestException e) {
                    LOGGER.log(
                                    Level.SEVERE,
                                    "Unexpected error while performing a single step in the VM",
                                    e);
                }

                _registeredSingleStepThread = null;

            } else if (_registeredStepOutThread != null
                    && _registeredStepOutThread.getReturnAddress() != null) {

                // There has been a thread registered for performing a step out
                // => perform a step out instead of resume.
                final Address returnAddress = _registeredStepOutThread.getReturnAddress();
                assert returnAddress != null;
                try {
                    teleProcess().controller().runToInstruction(returnAddress,
                            false, true);
                } catch (OSExecutionRequestException e) {
                    LOGGER.log(
                                    Level.SEVERE,
                                    "Unexpected error while performing a run-to-instruction in the VM",
                                    e);
                } catch (InvalidProcessRequestException e) {
                    LOGGER.log(
                                    Level.SEVERE,
                                    "Unexpected error while performing a run-to-instruction in the VM",
                                    e);
                }

                _registeredStepOutThread = null;

            } else {

                // Nobody registered for special commands => resume the Vm.
                try {
                    LOGGER.info("Client tried to resume the VM!");
                    teleProcess().controller().resume(false, false);
                } catch (OSExecutionRequestException e) {
                    LOGGER.log(Level.SEVERE,
                            "Unexpected error while resuming the VM", e);
                } catch (InvalidProcessRequestException e) {
                    LOGGER.log(Level.SEVERE,
                            "Unexpected error while resuming the VM", e);
                }
            }
        } else {
            LOGGER.severe("Client tried to resume the VM, but tele process is not in stopped state!");
        }
    }

    @Override
    public void suspend() {

        if (teleProcess().state() == TeleProcess.State.RUNNING) {
            LOGGER.info("Pausing VM...");
            try {
                teleProcess().controller().pause();
            } catch (OSExecutionRequestException e) {
                LOGGER.log(Level.SEVERE,
                        "Unexpected error while pausing the VM", e);
            } catch (InvalidProcessRequestException e) {
                LOGGER.log(Level.SEVERE,
                        "Unexpected error while pausing the VM", e);
            }
        } else {
            LOGGER.warning("Suspend called while VM not running!");
        }
    }

    private Set<CodeLocation> _locations = new HashSet<CodeLocation>();

    private StateTransitionListener _model = new StateTransitionListener() {

        public void handleStateTransition(StateTransitionEvent event) {
            Trace.begin(TRACE_VALUE, tracePrefix() + "handling " + event);
            switch(event.newState()) {
                case TERMINATED:
                    fireVMDiedEvent();
                    break;
                case STOPPED:
                    if (!_listeners.isEmpty()) {
                        final Sequence<TeleNativeThread> breakpointThreads = event.breakpointThreads();
                        for (TeleNativeThread t : breakpointThreads) {
                            fireBreakpointEvent(t, t.getFrames()[0].getLocation());
                        }
                        if (event.singleStepThread() != null) {
                            fireSingleStepEvent(event.singleStepThread(), event.singleStepThread().getFrames()[0].getLocation());
                        }
                    }
                    break;
                case RUNNING:
                    LOGGER.info("VM continued to RUN!");
                    break;
            }
            Trace.end(TRACE_VALUE, tracePrefix() + "handling " + event);
        }
    };

    private VariableSequence<VMListener> _listeners = new ArrayListSequence<VMListener>();

    /**
     * Informs all listeners that the VM died.
     */
    private void fireVMDiedEvent() {
        LOGGER.info("VM EVENT: VM died");
        for (VMListener l : _listeners) {
            l.vmDied();
        }
    }

    /**
     * Informs all listeners that a single step has been completed.
     *
     * @param thread the thread that did the single step
     * @param location the code location onto which the thread just stepped
     */
    private void fireSingleStepEvent(ThreadProvider thread,
            CodeLocation location) {
        LOGGER.info("VM EVENT: Single step was made at thread " + thread
                + " to location " + location);
        for (VMListener l : _listeners) {
            l.singleStepMade(thread, location);
        }
    }

    /**
     * Informs all listeners that a breakpoint has been hit.
     *
     * @param thread the thread that hit the breakpoint
     * @param location the code location at which the breakpoint was hit
     */
    private void fireBreakpointEvent(ThreadProvider thread,
            CodeLocation location) {
        LOGGER.info("VM EVENT: Breakpoint hit at thread " + thread
                + " at location " + location);
        for (VMListener l : _listeners) {
            l.breakpointHit(thread, location);
        }
    }

    /**
     * Informs all listeners that a thread has started.
     *
     * @param thread the thread that has started
     */
    private void fireThreadStartedEvent(ThreadProvider thread) {
        LOGGER.info("VM EVENT: Thread started: " + thread);
        for (VMListener l : _listeners) {
            l.threadStarted(thread);
        }
    }

    /**
     * Informs all listeners that a thread has died.
     *
     * @param thread the thread that has died
     */
    private void fireThreadDiedEvent(ThreadProvider thread) {
        LOGGER.info("VM EVENT: Thread died: " + thread);
        for (VMListener l : _listeners) {
            l.threadDied(thread);
        }

    }

    @Override
    public void addListener(VMListener l) {
        _listeners.append(l);
    }

    @Override
    public void removeListener(VMListener l) {
        _listeners.remove(Sequence.Static.indexOfIdentical(_listeners, l));
    }

    /**
     * Sets a breakpoint at the specified code location. This function currently has the following severe limitations:
     * Always sets the breakpoint at the call entry point of a method. Does ignore the suspendAll parameter, there will
     * always be all threads suspended when the breakpoint is hit.
     *
     * TODO: Fix the limitations for breakpoints.
     *
     * @param codeLocation specifies the code location at which the breakpoint should be set
     * @param suspendAll if true, all threads should be suspended when the breakpoint is hit
     */
    @Override
    public void addBreakpoint(CodeLocation codeLocation, boolean suspendAll) {

        // For now ignore duplicates
        if (_locations.contains(codeLocation)) {
            return;
        }

        assert codeLocation.method() instanceof TeleClassMethodActor : "Only tele method actors allowed here";

        assert !_locations.contains(codeLocation);
        _locations.add(codeLocation);
        assert _locations.contains(codeLocation);
        final TeleClassMethodActor tma = (TeleClassMethodActor) codeLocation.method();
        teleProcess().targetBreakpointFactory().makeBreakpoint(
                tma.getCurrentJavaTargetMethod().callEntryPoint(), false);
        Trace.line(TRACE_VALUE, tracePrefix() + "Breakpoint set at: " + tma.getCurrentJavaTargetMethod().callEntryPoint());
    }

    @Override
    public void removeBreakpoint(CodeLocation l) {
        final TeleClassMethodActor tma = (TeleClassMethodActor) l.method();
        teleProcess().targetBreakpointFactory().removeBreakpointAt(
                tma.getCurrentJavaTargetMethod().callEntryPoint());
        assert _locations.contains(l);
        _locations.remove(l);
        assert !_locations.contains(l);
    }

    private final ThreadGroupProvider _javaThreads = new ThreadGroupProviderImpl(
            true);
    private final ThreadGroupProvider _nativeThreads = new ThreadGroupProviderImpl(
            false);

    /**
     * Class representing a thread group used for logical grouping in the JDWP
     * protocol. Currently we only distinguish between Java and native threads.
     */
    private class ThreadGroupProviderImpl implements ThreadGroupProvider {

        private final boolean _containsJavaThreads;

        public ThreadGroupProviderImpl(boolean b) {
            _containsJavaThreads = b;
        }

        @Override
        public String getName() {
            return _containsJavaThreads ? "Java Threads" : "Native Threads";
        }

        @Override
        public ThreadGroupProvider getParent() {
            return null;
        }

        @Override
        public ThreadProvider[] getThreadChildren() {
            final AppendableSequence<ThreadProvider> result = new LinkSequence<ThreadProvider>();
            for (TeleNativeThread t : allThreads()) {
                if (t.isJava() == _containsJavaThreads) {
                    result.append(t);
                }
            }

            return Sequence.Static.toArray(result, ThreadProvider.class);
        }

        @Override
        public ThreadGroupProvider[] getThreadGroupChildren() {
            return new ThreadGroupProvider[0];
        }

        @Override
        public ReferenceTypeProvider getReferenceType() {
            assert false : "No reference type for thread groups available!";
            return null;
        }

    };

    /**
     * @return Thread group that should be used to logically group Java threads.
     */
    public ThreadGroupProvider javaThreads() {
        return _javaThreads;
    }

    /**
     * @return Thread group that should be used to logically group native
     *         threads.
     */
    public ThreadGroupProvider nativeThreads() {
        return _nativeThreads;
    }

    @Override
    public ThreadGroupProvider[] getThreadGroups() {
        return new ThreadGroupProvider[] {_javaThreads, _nativeThreads};
    }

    /**
     * Reads a value of a certain kind from the Maxine VM process.
     *
     * @param kind the type of the value that should be read
     * @param pointer pointer to the memory location where the value should be read
     * @param offset offset that should be added to the pointer before reading the value
     * @return the value read from the Maxine VM process
     */
    public Value readValue(Kind kind, Pointer pointer, int offset) {

        final Reference reference = teleProcess().teleVM().originToReference(
                pointer);

        if (kind == Kind.REFERENCE) {
            final Word word = teleProcess().dataAccess().readWord(pointer,
                    offset);
            return TeleReferenceValue.from(teleProcess().teleVM(),
                    teleProcess().teleVM().wordToReference(word));
        }

        final Value result = kind.readValue(reference, offset);

        if (result.kind() == Kind.WORD) {
            LOGGER.info("Creating WORD reference! " + result.asWord());
            return LongValue.from(result.asWord().asAddress().toLong());
        }

        if (result.kind() == Kind.REFERENCE
                && !isValidOrigin(result.asReference().toOrigin())) {
            LOGGER.severe("Wrong reference encountered ("
                    + result.asReference() + "), returning null reference!");
            return ReferenceValue.fromReference(Reference.fromOrigin(Pointer.zero()));
        }

        return result;
    }

    /**
     * Converts a value as seen by the Maxine VM to a value as seen by the JDWP
     * server.
     *
     * @param value
     *            the value as seen by the Maxine VM
     * @return the value as seen by the JDWP server
     */
    public VMValue convertToVirtualMachineValue(Value value) {
        switch (value.kind().asEnum()) {
            case BOOLEAN:
                return createBooleanValue(value.asBoolean());
            case BYTE:
                return createByteValue(value.asByte());
            case CHAR:
                return createCharValue(value.asChar());
            case DOUBLE:
                return createDoubleValue(value.asDouble());
            case FLOAT:
                return createFloatValue(value.asFloat());
            case INT:
                return createIntValue(value.asInt());
            case LONG:
                return createLongValue(value.asLong());
            case REFERENCE:
                return createObjectProviderValue(findObject(value.asReference()));
            case SHORT:
                return createShortValue(value.asShort());
            case VOID:
                return getVoidValue();
            case WORD:
                final Word word = value.asWord();
                LOGGER.warning("Tried to convert a word, this is not implemented yet! (word="
                            + word + ")");
                return getVoidValue();
        }

        throw new IllegalArgumentException("Unkown kind: " + value.kind());
    }

    /**
     * Converts a JDWP value object to a Maxine value object.
     *
     * @param vmValue
     *            the value as seen by the JDWP server
     * @return a newly created value as seen by the Maxine VM
     */
    public Value convertToValue(VMValue vmValue) {
        if (vmValue.isVoid()) {
            return VoidValue.VOID;
        } else if (vmValue.asBoolean() != null) {
            return BooleanValue.from(vmValue.asBoolean());
        } else if (vmValue.asByte() != null) {
            return ByteValue.from(vmValue.asByte());
        } else if (vmValue.asChar() != null) {
            return CharValue.from(vmValue.asChar());
        } else if (vmValue.asDouble() != null) {
            return DoubleValue.from(vmValue.asDouble());
        } else if (vmValue.asFloat() != null) {
            return FloatValue.from(vmValue.asFloat());
        } else if (vmValue.asInt() != null) {
            return IntValue.from(vmValue.asInt());
        } else if (vmValue.asLong() != null) {
            return LongValue.from(vmValue.asLong());
        } else if (vmValue.asShort() != null) {
            return ShortValue.from(vmValue.asShort());
        } else if (vmValue.asProvider() != null) {
            final Provider p = vmValue.asProvider();
            if (p instanceof TeleObject) {
                return TeleReferenceValue.from(this, ((TeleObject) p).getReference());
            }
            throw new IllegalArgumentException(
                    "Could not convert the provider object " + p
                            + " to a reference!");

        }
        throw new IllegalArgumentException("Unknown VirtualMachineValue type!");
    }

    public void registerSingleStepThread(TeleNativeThread teleNativeThread) {

        if (_registeredSingleStepThread != null) {
            LOGGER.warning("Overwriting registered single step thread! "
                    + _registeredSingleStepThread);
        }
        _registeredSingleStepThread = teleNativeThread;

    }

    public void registerStepOutThread(TeleNativeThread teleNativeThread) {
        if (_registeredStepOutThread != null) {
            LOGGER.warning("Overwriting registered step out thread! "
                    + _registeredStepOutThread);
        }
        _registeredStepOutThread = teleNativeThread;
    }

    /**
     * Looks up a JDWP reference type object based on a Java class object.
     *
     * @param klass
     *            the class object whose JDWP reference type should be looked up
     * @return a JDWP reference type representing the Java class
     */
    public ReferenceTypeProvider getReferenceType(Class klass) {
        ReferenceTypeProvider referenceTypeProvider = null;

        // Always fake the Object class, otherwise try to find a class in the
        // Maxine VM that matches the signature.
        if (!klass.equals(Object.class)) {
            referenceTypeProvider = _teleClassRegistry.findTeleClassActorByClass(klass);
        }

        // If no class was found within the Maxine VM, create a faked reference
        // type object.
        if (referenceTypeProvider == null) {
            LOGGER.info("Creating Java provider for class " + klass);
            referenceTypeProvider = _javaProviderFactory.getReferenceTypeProvider(klass);
        }
        return referenceTypeProvider;
    }

    /**
     * Converts a value kind as seen by the Maxine world to a VMValue type as
     * seen by the VM interface used by the JDWP server.
     *
     * @param kind
     *            the Maxine kind value
     * @return the type as seen by the JDWP server
     */
    public static Type toVirtualMachineType(Kind kind) {

        final KindEnum e = kind.asEnum();
        switch (e) {
            case BOOLEAN:
                return VMValue.Type.BOOLEAN;
            case BYTE:
                return VMValue.Type.BYTE;
            case CHAR:
                return VMValue.Type.CHAR;
            case DOUBLE:
                return VMValue.Type.DOUBLE;
            case FLOAT:
                return VMValue.Type.FLOAT;
            case INT:
                return VMValue.Type.INT;
            case LONG:
                return VMValue.Type.LONG;
            case REFERENCE:
                return VMValue.Type.PROVIDER;
            case SHORT:
                return VMValue.Type.SHORT;
            case VOID:
                return VMValue.Type.VOID;
            case WORD:
                break;
        }

        throw new IllegalArgumentException("Typeype " + kind
                + " cannot be resolved to a virtual machine value type");
    }

    @Override
    public VMValue createBooleanValue(boolean b) {
        return createJavaObjectValue(b, Boolean.TYPE);
    }

    @Override
    public VMValue createByteValue(byte b) {
        return createJavaObjectValue(b, Byte.TYPE);
    }

    @Override
    public VMValue createCharValue(char c) {
        return createJavaObjectValue(c, Character.TYPE);
    }

    @Override
    public VMValue createDoubleValue(double d) {
        return createJavaObjectValue(d, Double.TYPE);
    }

    @Override
    public VMValue createFloatValue(float f) {
        return createJavaObjectValue(f, Float.TYPE);
    }

    @Override
    public VMValue createIntValue(int i) {
        return createJavaObjectValue(i, Integer.TYPE);
    }

    @Override
    public VMValue createJavaObjectValue(Object o, Class expectedClass) {
        return VMValueImpl.fromJavaObject(o, this, expectedClass);
    }

    @Override
    public VMValue createLongValue(long l) {
        return VMValueImpl.fromJavaObject(l, this, Long.TYPE);
    }

    @Override
    public VMValue createObjectProviderValue(ObjectProvider p) {
        return createJavaObjectValue(p, null);
    }

    @Override
    public VMValue createShortValue(short s) {
        return VMValueImpl.fromJavaObject(s, this, Short.TYPE);
    }

    @Override
    public VMValue getVoidValue() {
        return VMValueImpl.VOID_VALUE;
    }

    @Override
    public byte[] accessMemory(long start, int length) {
        final byte[] result = new byte[length];
        final Address address = Address.fromLong(start);
        teleProcess().dataAccess().read(address, result, 0, length);
        return result;
    }

    @Override
    public String[] getClassPath() {
        return classpath().toStringArray();
    }

    @Override
    public String[] getBootClassPath() {
        return Classpath.bootClassPath().toStringArray();
    }

    @Override
    public String getName() {
        return MaxineVM.name();
    }

    @Override
    public String getVersion() {
        return MaxineVM.version();
    }

    @Override
    public String getDescription() {
        return "VM description";
    }

    @Override
    public CodeLocation createCodeLocation(MethodProvider method,
            long position, boolean isMachineCode) {
        return new CodeLocationImpl(method, position, isMachineCode);
    }

    public TeleTargetRoutine findTeleTargetRoutine(Address address) {
        // No routine can start at 0
        if (address.isZero()) {
            return null;
        }

        TeleTargetRoutine result = teleCodeRegistry().get(
                TeleTargetRoutine.class, address);
        if (result == null) {
            LOGGER.info("No target method found for address " + address
                    + ", trying to create new one");
            result = TeleTargetMethod.make(this, address);
        }
        return result;
    }

    @Override
    public TargetMethodAccess[] findTargetMethods(long[] addresses) {
        final TargetMethodAccess[] result = new TargetMethodAccess[addresses.length];
        for (int i = 0; i < addresses.length; i++) {
            result[i] = findTeleTargetRoutine(Address.fromLong(addresses[i]));
        }
        return result;
    }

    /**
     * An object that delays evaluation of a trace message for controller actions.
     */
    private class Tracer {

        private final String _message;

        /**
         * An object that delays evaluation of a trace message.
         * @param message identifies what is being traced
         */
        public Tracer(String message) {
            _message = message;
        }

        @Override
        public String toString() {
            return tracePrefix() + _message;
        }
    }
}
