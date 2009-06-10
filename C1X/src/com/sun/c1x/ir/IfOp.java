/*
 * Copyright (c) 2009 Sun Microsystems, Inc.  All rights reserved.
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
package com.sun.c1x.ir;

import com.sun.c1x.util.InstructionVisitor;
import com.sun.c1x.util.InstructionClosure;
import com.sun.c1x.bytecode.Bytecodes;

/**
 * The <code>IfOp</code> class represents a comparison that yields one of two values.
 * Note that these nodes are not built directly from the bytecode but are introduced
 * by conditional expression elimination.
 *
 * @author Ben L. Titzer
 */
public class IfOp extends Op2 {

    Condition _cond;
    Instruction _trueVal;
    Instruction _falseVal;

    /**
     * Constructs a new IfOp.
     * @param x the instruction producing the first value to be compared
     * @param cond the condition of the comparison
     * @param y the instruction producing the second value to be compared
     * @param tval the value produced if the condition is true
     * @param fval the value produced if the condition is false
     */
    public IfOp(Instruction x, Condition cond, Instruction y, Instruction tval, Instruction fval) {
        super(tval.type().meet(fval.type()), Bytecodes.ILLEGAL, x, y); // TODO: return the bytecode IF_ICMPEQ, etc
        _cond = cond;
        _trueVal = tval;
        _falseVal = fval;
    }

    /**
     * Gets the condition of this if operation.
     * @return the condition
     */
    public Condition condition() {
        return _cond;
    }

    /**
     * Gets the instruction that produces the value if the comparison is true.
     * @return the instruction producing the value upon true
     */
    public Instruction trueValue() {
        return _trueVal;
    }

    /**
     * Gets the instruction that produces the value if the comparison is false.
     * @return the instruction producing the value upon false
     */
    public Instruction falseValue() {
        return _falseVal;
    }

    /**
     * Checks whether this comparison operator is commutative (i.e. it is either == or !=).
     * @return <code>true</code> if this comparison is commutative
     */
    public boolean isCommutative() {
        return _cond == Condition.eql || _cond == Condition.neq;
    }

    /**
     * Iterates over the input values to this instruction.
     * @param closure the closure to apply to each instruction
     */
    public void inputValuesDo(InstructionClosure closure) {
        super.inputValuesDo(closure);
        _trueVal = closure.apply(_trueVal);
        _trueVal = closure.apply(_falseVal);
    }

    /**
     * Implements this instruction's half of the visitor pattern.
     * @param v the visitor to accept
     */
    public void accept(InstructionVisitor v) {
        v.visitIfOp(this);
    }
}
