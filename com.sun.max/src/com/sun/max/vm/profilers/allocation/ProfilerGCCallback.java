/*
 * Copyright (c) 2018, APT Group, School of Computer Science,
 * The University of Manchester. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 */
package com.sun.max.vm.profilers.allocation;

import com.sun.max.vm.MaxineVM;
import com.sun.max.vm.heap.Heap;

public class ProfilerGCCallback implements Heap.GCCallback {

    static {
        Heap.registerGCCallback(new ProfilerGCCallback());
    }

    @Override
    public void gcCallback(Heap.GCCallbackPhase gcCallbackPhase) {
        if (gcCallbackPhase == Heap.GCCallbackPhase.BEFORE) {
            //TODO: move here pre-gc actions
        } else if (gcCallbackPhase == Heap.GCCallbackPhase.AFTER) {
            //TODO: move here pre-gc actions
        }
    }
}
