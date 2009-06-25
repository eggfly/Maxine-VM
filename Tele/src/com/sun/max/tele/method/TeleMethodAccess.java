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
package com.sun.max.tele.method;

import com.sun.max.program.*;
import com.sun.max.tele.*;
import com.sun.max.tele.interpreter.*;
import com.sun.max.vm.actor.holder.*;
import com.sun.max.vm.actor.member.*;
import com.sun.max.vm.classfile.constant.*;
import com.sun.max.vm.prototype.*;
import com.sun.max.vm.type.*;
import com.sun.max.vm.value.*;

/**
 * A {@code TeleMethodAccess} provides a mechanism for accessing a method in a tele VM.
 * It includes support for {@linkplain #interpret(Value...) invoking} such a method in the
 * context of the tele VM.
 *
 * @author Bernd Mathiske
 * @author Doug Simon
 */
public abstract class TeleMethodAccess extends AbstractTeleVMHolder {

    private static MethodActor findMethodActor(Class holder, String name, SignatureDescriptor signature) {
        final ClassActor classActor = PrototypeClassLoader.PROTOTYPE_CLASS_LOADER.mustMakeClassActor(JavaTypeDescriptor.forJavaClass(holder));
        return classActor.findMethodActor(SymbolTable.makeSymbol(name), signature);
    }

    private static MethodActor findMethodActor(Class holder, String name) {
        final ClassActor classActor = PrototypeClassLoader.PROTOTYPE_CLASS_LOADER.mustMakeClassActor(JavaTypeDescriptor.forJavaClass(holder));
        MethodActor uniqueMethodActor = null;
        for (MethodActor methodActor : classActor.getLocalMethodActors()) {
            if (methodActor.name.string.equals(name)) {
                if (uniqueMethodActor != null) {
                    ProgramError.unexpected("need to disambiguate method named '" + name + "' in " + classActor.name + " with a signature");
                }
                uniqueMethodActor = methodActor;
            }
        }
        return uniqueMethodActor;
    }

    private final MethodActor methodActor;

    protected final MethodActor methodActor() {
        return methodActor;
    }

    protected TeleMethodAccess(TeleVM teleVM, Class holder, String name, SignatureDescriptor signature) {
        super(teleVM);
        if (signature != null) {
            methodActor = findMethodActor(holder, name, signature);
            ProgramError.check(methodActor != null, "could not find method " + name + signature + " in " + holder);
        } else {
            methodActor = findMethodActor(holder, name);
            ProgramError.check(methodActor != null, "could not find method named '" + name + "' in " + holder);
        }
    }

    /**
     * Executes the method as if running in the VM.  Interprets (slowly) the bytecodes in an environment
     * where memory reads are remote to the VM, and in which the IDs of classes are those of the VM.
     * Local objects passed as arguments must be of types known as legitimate in the VM.
     *
     * @param arguments
     * @return return value from the method
     */
    public Value interpret(Value... arguments) {
        try {
            ProgramError.check(methodActor instanceof ClassMethodActor, "cannot interpret interface method");
            return TeleInterpreter.execute(teleVM(), (ClassMethodActor) methodActor, arguments);
        } catch (TeleInterpreterException teleInterpreterException) {
            ProgramError.unexpected("method interpretation failed", teleInterpreterException);
            return null;
        }
    }
}
