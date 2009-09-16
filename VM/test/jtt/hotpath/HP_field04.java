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
// Checkstyle: stop
package jtt.hotpath;
/*
 * @Harness: java
 * @Runs: 40 = 4972; 1000 = 2019980;
 */
public class HP_field04 {
    public byte b;
    public char c;
    public short s;
    public int i;
    public long l;
    public float f;
    public double d;

    public static int test(int count) {
        return new HP_field04().run(count);
    }

    public int run(int count) {
        for (int x = 0; x <= count; x++) {
            b += x;
            c += x;
            s += x;
            i += x;
            l += x;
            f += x;
            d += x;
        }
        return (int) (b + c + s + i + l + f + d);
    }
}
