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
package com.sun.max.tele.debug.darwin;

import java.io.*;

import com.sun.max.program.*;
import com.sun.max.tele.*;
import com.sun.max.unsafe.*;
import com.sun.max.vm.prototype.*;

/**
 * @author Bernd Mathiske
 */
public final class DarwinTeleVM extends TeleVM {

    @Override
    protected DarwinTeleProcess createTeleProcess(String[] commandLineArguments, int id) {
        return new DarwinTeleProcess(this, bootImage().vmConfiguration().platform(), programFile(), commandLineArguments, id);
    }

    private static native long nativeLoadBootHeap(long childPID, long task, long mappingSize);

    @Override
    protected Pointer loadBootImage() throws BootImageException {
        final DarwinTeleProcess darwinTeleProcess = (DarwinTeleProcess) teleProcess();
        final long heap = nativeLoadBootHeap(darwinTeleProcess.pid(), darwinTeleProcess.task(), bootImage().header()._bootHeapSize + bootImage().header()._bootCodeSize);
        if (heap == 0L) {
            throw new BootImageException("Could not trace remote process up to image mapping.");
        }
        return Pointer.fromLong(heap);
    }

    public DarwinTeleVM(File bootImageFile, BootImage bootImage, Classpath sourcepath, String[] commandLineArguments, int id) throws BootImageException {
        super(bootImageFile, bootImage, sourcepath, commandLineArguments, id);
    }

}
