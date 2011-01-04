/*
 * Copyright (c) 2010, 2010, Oracle and/or its affiliates. All rights reserved.
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
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.sun.c1x.opt;

import com.sun.c1x.graph.*;
import com.sun.c1x.ir.*;
import com.sun.c1x.value.*;

/**
 * A mechanism to remove {@linkplain UnsafeCast#redundant redundant} unsafe casts.
 *
 * @author Doug Simon
 */
public class UnsafeCastEliminator implements ValueClosure, BlockClosure {

    final IR ir;

    /**
     * Eliminates redundant unsafe casts from a given IR.
     */
    public UnsafeCastEliminator(IR ir) {
        this.ir = ir;
        ir.startBlock.iterateAnyOrder(this, false);
    }

    public void apply(BlockBegin block) {
        if (block.isExceptionEntry()) {
            for (FrameState ehState : block.exceptionHandlerStates()) {
                ehState.valuesDo(this);
            }
        }

        Instruction i = block;
        while (i != null) {
            FrameState stateBefore = i.stateBefore();
            if (stateBefore != null) {
                stateBefore.valuesDo(this);
            }
            i.inputValuesDo(this);
            if (i instanceof BlockEnd) {
                // Remove redundant unsafe casts in the state at the end of a block
                i.stateAfter().valuesDo(this);
            }
            i = i.next();
        }
    }

    public Value apply(Value i) {
        if (i instanceof UnsafeCast) {
            Value y = ((UnsafeCast) i).nonRedundantReplacement();
            assert y != null;
            return y;
        }
        return i;
    }
}
