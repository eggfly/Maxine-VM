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
package test.com.sun.max.vm.compiler.bytecode;

import test.com.sun.max.vm.compiler.*;

import com.sun.max.unsafe.*;
import com.sun.max.vm.compiler.ir.*;
import com.sun.max.vm.type.*;
import com.sun.max.vm.value.*;

/**
 * The tests are divided in two cases for each type of field: whether the field is resolved or not.
 * To test the case of unresolved field, we arrange for the method whose compilation is being tested to be passed
 * an argument that is an instance of the UnresolvedClassUnderTest such that a field of the argument is read,
 * and the UnresolvedClassUnderTest is not loaded in the prototype VM. So there are two test cases per
 * type of field: test_getfield_XXX and test_unresolved_getfield_XXX.
 */
public abstract class BytecodeTest_getfield<Method_Type extends IrMethod> extends CompilerTestCase<Method_Type> {

    protected BytecodeTest_getfield(String name) {
        super(name);
    }

    private byte _byteField = 111;

    /**
     * Testing for a byte field when the field is resolved at compile-time
     * (so the compiler will not issue a guarded field load).
     */
    private byte perform_getfield_byte() {
        return _byteField;
    }

    /**
     * Testing for a byte field when the field is resolved at compile-time
     * (so the compiler will issue a guarded field load).
     */
    private byte perform_getfield_byte(UnresolvedClassUnderTest u) {
        return u._byteField;
    }

    private void do_getfield_byte(Method_Type method, UnresolvedClassUnderTest u) {
        new BytecodeConfirmation(method.classMethodActor()) {
            @Override
            public void getfield(int index) {
                confirmPresence();
            }
        };
        Value result = null;
        if (u == null) {
            result = executeWithReceiver(method);
            assertTrue(result.asByte() == _byteField);
        } else {
            result = executeWithReceiver(method, ReferenceValue.from(u));
            assertTrue(result.asByte() == u._byteField);
        }
        assertTrue(result.asByte() == 111);
    }
    public void test_resolved_getfield_byte() {
        final Method_Type method = compileMethod("perform_getfield_byte", SignatureDescriptor.create(byte.class));
        do_getfield_byte(method, null);
    }

    public void test_unresolved_getfield_byte() {
        // NOTE: using UnresolvedClassUnderTest.class here cause the loading of the UnresolvedClassUnderTest in the host VM, not in the Target.
        // So we do have the desired effect with respect to the test: testing the compilation of a "unresolved" field.
        final Method_Type method = compileMethod("perform_getfield_byte", SignatureDescriptor.create(byte.class, UnresolvedClassUnderTest.class));
        do_getfield_byte(method, new UnresolvedClassUnderTest());
    }

    private boolean _booleanField = true;

    private boolean perform_getfield_boolean() {
        return _booleanField;
    }
    private boolean perform_getfield_boolean(UnresolvedClassUnderTest u) {
        return u._booleanField;
    }

    public void test_resolved_getfield_boolean() {
        final Method_Type method = compileMethod("perform_getfield_boolean", SignatureDescriptor.create(boolean.class));
        do_getfield_boolean(method, null);
    }

    public void test_unresolved_getfield_boolean() {
        final Method_Type method = compileMethod("perform_getfield_boolean", SignatureDescriptor.create(boolean.class, UnresolvedClassUnderTest.class));
        do_getfield_boolean(method, new UnresolvedClassUnderTest());
    }

    private void do_getfield_boolean(Method_Type method, UnresolvedClassUnderTest u) {
        new BytecodeConfirmation(method.classMethodActor()) {
            @Override
            public void getfield(int index) {
                confirmPresence();
            }
        };
        Value result = null;
        if (u == null) {
            result = executeWithReceiver(method);
            assertTrue(result.asBoolean() == _booleanField);
        } else {
            result = executeWithReceiver(method, ReferenceValue.from(u));
            assertTrue(result.asBoolean() == u._booleanField);
        }
        assertTrue(result.asBoolean());
    }


    private short _shortField = 333;

    private short perform_getfield_short() {
        return _shortField;
    }

    private short perform_getfield_short(UnresolvedClassUnderTest u) {
        return u._shortField;
    }

    public void test_resolved_getfield_short() {
        final Method_Type method = compileMethod("perform_getfield_short", SignatureDescriptor.create(short.class));
        do_getfield_short(method, null);
    }

    public void test_unresolved_getfield_short() {
        final Method_Type method = compileMethod("perform_getfield_short", SignatureDescriptor.create(short.class, UnresolvedClassUnderTest.class));
        do_getfield_short(method, new UnresolvedClassUnderTest());
    }

    private void do_getfield_short(Method_Type method, UnresolvedClassUnderTest u) {
        new BytecodeConfirmation(method.classMethodActor()) {
            @Override
            public void getfield(int index) {
                confirmPresence();
            }
        };
        Value result = null;
        if (u == null) {
            result = executeWithReceiver(method);
            assertTrue(result.asShort() == _shortField);
        } else {
            result = executeWithReceiver(method, ReferenceValue.from(u));
            assertTrue(result.asShort() == u._shortField);
        }
        assertTrue(result.asShort() == 333);
    }

    private char _charField = 444;

    private char perform_getfield_char() {
        return _charField;
    }

    private char perform_getfield_char(UnresolvedClassUnderTest u) {
        return u._charField;
    }

    public void test_resolved_getfield_char() {
        final Method_Type method = compileMethod("perform_getfield_char", SignatureDescriptor.create(char.class));
        do_getfield_char(method, null);
    }
    public void test_getfield_char() {
        final Method_Type method = compileMethod("perform_getfield_char", SignatureDescriptor.create(char.class, UnresolvedClassUnderTest.class));
        do_getfield_char(method, new UnresolvedClassUnderTest());
    }
    private void do_getfield_char(Method_Type method, UnresolvedClassUnderTest u) {
        new BytecodeConfirmation(method.classMethodActor()) {

            @Override
            public void getfield(int index) {
                confirmPresence();
            }
        };
        Value result = null;
        if (u == null) {
            result = executeWithReceiver(method);
            assertTrue(result.asChar() == _charField);
        } else {
            result = executeWithReceiver(method, ReferenceValue.from(u));
            assertTrue(result.asChar() == _charField);
        }
        assertTrue(result.asChar() == 444);
    }

    private int _intField = 55;

    private int perform_getfield_int() {
        return _intField;
    }
    private int perform_getfield_int(UnresolvedClassUnderTest u) {
        return u._intField;
    }

    public void test_resolved_getfield_int() {
        final Method_Type method = compileMethod("perform_getfield_int", SignatureDescriptor.create(int.class));
        do_getfield_int(method, null);
    }
    public void test_getfield_int() {
        final Method_Type method = compileMethod("perform_getfield_int", SignatureDescriptor.create(int.class, UnresolvedClassUnderTest.class));
        do_getfield_int(method, new UnresolvedClassUnderTest());
    }
    private void do_getfield_int(Method_Type method, UnresolvedClassUnderTest u) {

        new BytecodeConfirmation(method.classMethodActor()) {

            @Override
            public void getfield(int index) {
                confirmPresence();
            }
        };
        Value result = null;
        if (u == null) {
            result = executeWithReceiver(method);
            assertTrue(result.asInt() == _intField);
        } else {
            result = executeWithReceiver(method, ReferenceValue.from(u));
            assertTrue(result.asInt() == u._intField);
        }
        assertTrue(result.asInt() == 55);
    }

    private float _floatField = 6.6F;

    private float perform_getfield_float() {
        return _floatField;
    }
    private float perform_getfield_float(UnresolvedClassUnderTest u) {
        return u._floatField;
    }
    public void test_resolved_getfield_float() {
        final Method_Type method = compileMethod("perform_getfield_float", SignatureDescriptor.create(float.class));
        do_getfield_float(method, null);
    }
    public void test_unresolved_getfield_float() {
        final Method_Type method = compileMethod("perform_getfield_float", SignatureDescriptor.create(float.class, UnresolvedClassUnderTest.class));
        do_getfield_float(method, new UnresolvedClassUnderTest());
    }
    private void do_getfield_float(Method_Type method, UnresolvedClassUnderTest u) {
        new BytecodeConfirmation(method.classMethodActor()) {

            @Override
            public void getfield(int index) {
                confirmPresence();
            }
        };
        Value result = null;
        if (u == null) {
            result = executeWithReceiver(method);
            assertTrue(result.asFloat() == _floatField);
        } else {
            result = executeWithReceiver(method, ReferenceValue.from(u));
            assertTrue(result.asFloat() == u._floatField);
        }
        assertTrue(result.asFloat() == 6.6F);
    }

    private long _longField = 77L;

    private long perform_getfield_long() {
        return _longField;
    }
    private long perform_getfield_long(UnresolvedClassUnderTest u) {
        return u._longField;
    }
    public void test_resolved_getfield_long() {
        final Method_Type method = compileMethod("perform_getfield_long", SignatureDescriptor.create(long.class));
        do_getfield_long(method, null);
    }
    public void test_unresolved_getfield_long() {
        final Method_Type method = compileMethod("perform_getfield_long", SignatureDescriptor.create(long.class, UnresolvedClassUnderTest.class));
        do_getfield_long(method, new UnresolvedClassUnderTest());
    }

    private void do_getfield_long(Method_Type method, UnresolvedClassUnderTest u) {
        new BytecodeConfirmation(method.classMethodActor()) {

            @Override
            public void getfield(int index) {
                confirmPresence();
            }
        };
        Value result = null;
        if (u == null) {
            result = executeWithReceiver(method);
            assertTrue(result.asLong() == _longField);
        } else {
            result = executeWithReceiver(method, ReferenceValue.from(u));
            assertTrue(result.asLong() == u._longField);
        }
        assertTrue(result.asLong() == 77L);
    }

    private double _doubleField = 8.8;

    private double perform_getfield_double() {
        return _doubleField;
    }
    private double perform_getfield_double(UnresolvedClassUnderTest u) {
        return _doubleField;
    }

    public void test_resolved_getfield_double() {
        final Method_Type method = compileMethod("perform_getfield_double", SignatureDescriptor.create(double.class));
        do_getfield_double(method, null);
    }
    public void test_unresolved_getfield_double() {
        final Method_Type method = compileMethod("perform_getfield_double", SignatureDescriptor.create(double.class, UnresolvedClassUnderTest.class));
        do_getfield_double(method, new UnresolvedClassUnderTest());
    }
    private void do_getfield_double(Method_Type method, UnresolvedClassUnderTest u) {
        new BytecodeConfirmation(method.classMethodActor()) {

            @Override
            public void getfield(int index) {
                confirmPresence();
            }
        };
        Value result = null;
        if (u == null) {
            result = executeWithReceiver(method);
            assertTrue(result.asDouble() == _doubleField);
        } else {
            result = executeWithReceiver(method, ReferenceValue.from(u));
            assertTrue(result.asDouble() == u._doubleField);
        }
        assertTrue(result.asDouble() == 8.8);
    }

    private Word _wordField;

    private Word perform_getfield_word() {
        return _wordField;
    }

    private Word perform_getfield_word(UnresolvedClassUnderTest u) {
        return u._wordField;
    }
    public void test_unresolved_getfield_word() {
        final Method_Type method = compileMethod("perform_getfield_word", SignatureDescriptor.create(Word.class));
        do_getfield_word(method, null);
    }
    public void test_resolved_getfield_word() {
        final Method_Type method = compileMethod("perform_getfield_word", SignatureDescriptor.create(Word.class, UnresolvedClassUnderTest.class));
        final UnresolvedClassUnderTest u = new UnresolvedClassUnderTest();
        u._wordField = Offset.fromInt(88);
        do_getfield_word(method, u);
    }

    private void do_getfield_word(Method_Type method, UnresolvedClassUnderTest u) {
        new BytecodeConfirmation(method.classMethodActor()) {

            @Override
            public void getfield(int index) {
                confirmPresence();
            }
        };
        _wordField = Offset.fromInt(88);
        Value result = null;
        if (u == null) {
            result = executeWithReceiver(method);
            assertTrue(result.asWord().equals(_wordField));
        } else {
            result = executeWithReceiver(method, ReferenceValue.from(u));
            assertTrue(result.asWord().equals(u._wordField));
        }
        assertTrue(result.asWord().asOffset().toInt() == 88);
    }

    private Object _referenceField = this;

    private Object perform_getfield_reference() {
        return _referenceField;
    }

    private Object perform_getfield_reference(UnresolvedClassUnderTest u) {
        return u._referenceField;
    }

    public void test_resolved_getfield_reference() {
        final Method_Type method = compileMethod("perform_getfield_reference", SignatureDescriptor.create(Object.class));
        do_getfield_reference(method, null);
    }
    public void test_unresolved_getfield_reference() {
        final Method_Type method = compileMethod("perform_getfield_reference", SignatureDescriptor.create(Object.class, UnresolvedClassUnderTest.class));
        do_getfield_reference(method, new UnresolvedClassUnderTest());
    }
    private void do_getfield_reference(Method_Type method, UnresolvedClassUnderTest u) {
        new BytecodeConfirmation(method.classMethodActor()) {

            @Override
            public void getfield(int index) {
                confirmPresence();
            }
        };
        Value result = null;
        if (u == null) {
            result = executeWithReceiver(method);
            assertTrue(result.asObject() == _referenceField);
            assertTrue(result.asObject() == this);
        } else {
            result = executeWithReceiver(method, ReferenceValue.from(u));
            assertTrue(result.asObject() == u._referenceField);
            assertTrue(result.asObject() == u);
        }
    }
}


