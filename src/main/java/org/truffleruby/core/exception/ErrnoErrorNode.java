/*
 * Copyright (c) 2020 Oracle and/or its affiliates. All rights reserved. This
 * code is released under a tri EPL/GPL/LGPL license. You can use it,
 * redistribute it and/or modify it under the terms of the:
 *
 * Eclipse Public License version 2.0, or
 * GNU General Public License version 2, or
 * GNU Lesser General Public License version 2.1.
 */
package org.truffleruby.core.exception;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.library.CachedLibrary;
import org.jcodings.specific.UTF8Encoding;
import org.truffleruby.core.klass.RubyClass;
import org.truffleruby.core.string.RubyString;
import org.truffleruby.core.string.StringOperations;
import org.truffleruby.language.ImmutableRubyString;
import org.truffleruby.language.RubyContextNode;
import org.truffleruby.language.backtrace.Backtrace;
import org.truffleruby.language.dispatch.DispatchNode;
import org.truffleruby.language.library.RubyStringLibrary;
import org.truffleruby.platform.ErrnoDescriptions;

public abstract class ErrnoErrorNode extends RubyContextNode {

    public static ErrnoErrorNode create() {
        return ErrnoErrorNodeGen.create();
    }

    @Child private DispatchNode formatMessageNode;

    public abstract RubySystemCallError execute(int errno, Object extraMessage, Backtrace backtrace);

    @Specialization(guards = "strings.isRubyString(extraMessage)")
    protected RubySystemCallError errnoError(int errno, Object extraMessage, Backtrace backtrace,
            @CachedLibrary(limit = "2") RubyStringLibrary strings) {
        final String errnoName = getContext().getCoreLibrary().getErrnoName(errno);

        final Object errnoDescription;
        final RubyClass errnoClass;
        if (errnoName == null) {
            errnoClass = getContext().getCoreLibrary().systemCallErrorClass;
            errnoDescription = nil;
        } else {
            errnoClass = getContext().getCoreLibrary().getErrnoClass(errnoName);
            errnoDescription = StringOperations.createString(
                    getContext(),
                    getLanguage(),
                    StringOperations.encodeRope(ErrnoDescriptions.getDescription(errnoName), UTF8Encoding.INSTANCE));
        }


        final RubyString errorMessage = formatMessage(errnoDescription, errno, extraMessage);

        return ExceptionOperations
                .createSystemCallError(getContext(), errnoClass, errorMessage, errno, backtrace);
    }

    private RubyString formatMessage(Object errnoDescription, int errno, Object extraMessage) {
        assert extraMessage instanceof RubyString || extraMessage instanceof ImmutableRubyString;
        if (formatMessageNode == null) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            formatMessageNode = insert(DispatchNode.create());
        }
        return (RubyString) formatMessageNode.call(
                getContext().getCoreLibrary().truffleExceptionOperationsModule,
                "format_errno_error_message",
                errnoDescription,
                errno,
                extraMessage);
    }

}
