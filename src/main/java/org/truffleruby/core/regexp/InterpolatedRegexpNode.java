/*
 * Copyright (c) 2014, 2020 Oracle and/or its affiliates. All rights reserved. This
 * code is released under a tri EPL/GPL/LGPL license. You can use it,
 * redistribute it and/or modify it under the terms of the:
 *
 * Eclipse Public License version 2.0, or
 * GNU General Public License version 2, or
 * GNU Lesser General Public License version 2.1.
 */
package org.truffleruby.core.regexp;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import org.jcodings.specific.ASCIIEncoding;
import org.jcodings.specific.USASCIIEncoding;
import org.joni.Regex;
import org.truffleruby.core.cast.ToSNode;
import org.truffleruby.core.regexp.InterpolatedRegexpNodeFactory.RegexpBuilderNodeGen;
import org.truffleruby.core.rope.Rope;
import org.truffleruby.core.rope.RopeNodes;
import org.truffleruby.core.rope.RopeOperations;
import org.truffleruby.core.rope.RopeWithEncoding;
import org.truffleruby.language.NotOptimizedWarningNode;
import org.truffleruby.language.RubyContextNode;
import org.truffleruby.language.RubyContextSourceNode;
import org.truffleruby.language.control.DeferredRaiseException;
import org.truffleruby.language.dispatch.DispatchNode;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import org.truffleruby.language.library.RubyStringLibrary;

public class InterpolatedRegexpNode extends RubyContextSourceNode {

    @Children private final ToSNode[] children;
    @Child private RegexpBuilderNode builderNode;
    @Child private RubyStringLibrary rubyStringLibrary;

    public InterpolatedRegexpNode(ToSNode[] children, RegexpOptions options) {
        this.children = children;
        builderNode = RegexpBuilderNode.create(options);
        rubyStringLibrary = RubyStringLibrary.getFactory().createDispatched(2);
    }

    @Override
    public Object execute(VirtualFrame frame) {
        return builderNode.execute(executeChildren(frame));
    }

    @ExplodeLoop
    protected RopeWithEncoding[] executeChildren(VirtualFrame frame) {
        RopeWithEncoding[] values = new RopeWithEncoding[children.length];
        for (int i = 0; i < children.length; i++) {
            final Object value = children[i].execute(frame);
            values[i] = new RopeWithEncoding(rubyStringLibrary.getRope(value), rubyStringLibrary.getEncoding(value));
        }
        return values;
    }

    public abstract static class RegexpBuilderNode extends RubyContextNode {

        @Child private RopeNodes.EqualNode ropesEqualNode = RopeNodes.EqualNode.create();
        @Child private DispatchNode copyNode = DispatchNode.create();
        private final RegexpOptions options;

        public static RegexpBuilderNode create(RegexpOptions options) {
            return RegexpBuilderNodeGen.create(options);
        }

        public RegexpBuilderNode(RegexpOptions options) {
            this.options = options;
        }

        public abstract Object execute(RopeWithEncoding[] parts);

        @Specialization(guards = "ropesWithEncodingsMatch(cachedParts, parts)", limit = "getDefaultCacheLimit()")
        protected Object executeFast(RopeWithEncoding[] parts,
                @Cached(value = "parts", dimensions = 1) RopeWithEncoding[] cachedParts,
                @Cached("createRegexp(cachedParts)") RubyRegexp regexp) {
            final Object clone = copyNode.call(regexp, "clone");
            return clone;
        }

        @Specialization(replaces = "executeFast")
        protected Object executeSlow(RopeWithEncoding[] parts,
                @Cached NotOptimizedWarningNode notOptimizedWarningNode) {
            notOptimizedWarningNode.warn("unstable interpolated regexps are not optimized");
            return createRegexp(parts);
        }

        @ExplodeLoop
        protected boolean ropesWithEncodingsMatch(RopeWithEncoding[] a, RopeWithEncoding[] b) {
            for (int i = 0; i < a.length; i++) {
                if (!ropesEqualNode.execute(a[i].getRope(), b[i].getRope())) {
                    return false;
                }
                if (a[i].getEncoding() != b[i].getEncoding()) {
                    return false;
                }
            }
            return true;
        }

        @TruffleBoundary
        protected RubyRegexp createRegexp(RopeWithEncoding[] strings) {
            final RegexpOptions options = (RegexpOptions) this.options.clone();
            final RopeWithEncoding preprocessed;
            final Regex regexp1;
            try {
                preprocessed = ClassicRegexp.preprocessDRegexp(getContext(), strings, options);
                regexp1 = TruffleRegexpNodes
                        .compile(
                                getLanguage(),
                                null,
                                preprocessed,
                                options,
                                this);
            } catch (DeferredRaiseException dre) {
                throw dre.getException(getContext());
            }

            // The RegexpNodes.compile operation may modify the encoding of the source rope. This modified copy is stored
            // in the Regex object as the "user object". Since ropes are immutable, we need to take this updated copy when
            // constructing the final regexp.
            final RopeWithEncoding ropeWithEncoding = (RopeWithEncoding) regexp1.getUserObject();

            Rope source = ropeWithEncoding.getRope();
            if (options.isEncodingNone()) {
                if (!all7Bit(preprocessed.getRope().getBytes())) {
                    source = RopeOperations.withEncoding(source, ASCIIEncoding.INSTANCE);
                } else {
                    source = RopeOperations.withEncoding(source, USASCIIEncoding.INSTANCE);
                }
            }

            return new RubyRegexp(
                    regexp1,
                    source,
                    ropeWithEncoding.getEncoding(),
                    options);
        }

        private static boolean all7Bit(byte[] bytes) {
            for (int n = 0; n < bytes.length; n++) {
                if (bytes[n] < 0) {
                    return false;
                }

                if (bytes[n] == '\\' && n + 1 < bytes.length && bytes[n + 1] == 'x') {
                    final String num;
                    final boolean isSecondHex = n + 3 < bytes.length && Character.digit(bytes[n + 3], 16) != -1;
                    if (isSecondHex) {
                        num = new String(Arrays.copyOfRange(bytes, n + 2, n + 4), StandardCharsets.UTF_8);
                    } else {
                        num = new String(Arrays.copyOfRange(bytes, n + 2, n + 3), StandardCharsets.UTF_8);
                    }

                    int b = Integer.parseInt(num, 16);

                    if (b > 0x7F) {
                        return false;
                    }

                    if (isSecondHex) {
                        n += 3;
                    } else {
                        n += 2;
                    }

                }
            }

            return true;
        }
    }
}
