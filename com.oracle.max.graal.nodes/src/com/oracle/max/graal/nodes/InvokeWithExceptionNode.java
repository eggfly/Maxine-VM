/*
 * Copyright (c) 2011, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.max.graal.nodes;

import com.oracle.max.graal.graph.*;
import com.oracle.max.graal.nodes.java.*;
import com.oracle.max.graal.nodes.spi.*;
import com.sun.cri.ri.*;

public class InvokeWithExceptionNode extends ControlSplitNode implements Node.IterableNodeType, Invoke{
    private static final int NORMAL_EDGE = 0;
    private static final int EXCEPTION_EDGE = 1;

    @Input private MethodCallTargetNode callTarget;
    @Input private FrameState stateBefore;
    private boolean canInline = true;

    /**
     * @param kind
     * @param blockSuccessors
     * @param branchProbability
     */
    public InvokeWithExceptionNode(MethodCallTargetNode callTarget, BeginNode exceptionEdge, FrameState stateBefore) {
        super(callTarget.returnKind().stackKind(), new BeginNode[]{null, exceptionEdge}, new double[]{1.0, 0.0});
        assert stateBefore != null && callTarget != null;
        this.stateBefore = stateBefore;
        this.callTarget = callTarget;
    }

    public boolean canInline() {
        return canInline;
    }

    public void setCanInline(boolean b) {
        canInline = b;
    }

    public BeginNode exceptionEdge() {
        return blockSuccessor(EXCEPTION_EDGE);
    }

    public void setExceptionEdge(BeginNode x) {
        setBlockSuccessor(EXCEPTION_EDGE, x);
    }

    public BeginNode next() {
        return blockSuccessor(NORMAL_EDGE);
    }

    public void setNext(BeginNode x) {
        setBlockSuccessor(NORMAL_EDGE, x);
    }

    public MethodCallTargetNode callTarget() {
        return callTarget;
    }

    @Override
    public RiResolvedType declaredType() {
        RiType returnType = callTarget().returnType();
        return (returnType instanceof RiResolvedType) ? ((RiResolvedType) returnType) : null;
    }

    @Override
    public void accept(ValueVisitor v) {
        v.visitInvoke(this);
    }

    @Override
    public String toString(Verbosity verbosity) {
        if (verbosity == Verbosity.Long) {
            return super.toString(Verbosity.Short) + "(bci=" + bci() + ")";
        } else {
            return super.toString(verbosity);
        }
    }

    public int bci() {
        return stateBefore().bci;
    }

    @Override
    public FixedNode node() {
        return this;
    }

    @Override
    public void setNext(FixedNode x) {
        if (x != null) {
            this.setNext(BeginNode.begin(x));
        } else {
            this.setNext(null);
        }
    }

    public FrameState stateAfter() {
        return next().stateAfter();
    }

    public FrameState stateDuring() {
        FrameState stateAfter = stateAfter();
        return stateAfter.duplicateModified(bci(), stateAfter.rethrowException(), this.kind);
    }

    public FrameState stateBefore() {
        return stateBefore;
    }
}
