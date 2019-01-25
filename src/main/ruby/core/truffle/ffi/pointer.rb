# frozen_string_literal: true

# Copyright (c) 2016, 2018 Oracle and/or its affiliates. All rights reserved. This
# code is released under a tri EPL/GPL/LGPL license. You can use it,
# redistribute it and/or modify it under the terms of the:
#
# Eclipse Public License version 1.0, or
# GNU General Public License version 2, or
# GNU Lesser General Public License version 2.1.

# Copyright (c) 2007-2015, Evan Phoenix and contributors
# All rights reserved.
#
# Redistribution and use in source and binary forms, with or without
# modification, are permitted provided that the following conditions are met:
#
# * Redistributions of source code must retain the above copyright notice, this
#   list of conditions and the following disclaimer.
# * Redistributions in binary form must reproduce the above copyright notice
#   this list of conditions and the following disclaimer in the documentation
#   and/or other materials provided with the distribution.
# * Neither the name of Rubinius nor the names of its contributors
#   may be used to endorse or promote products derived from this software
#   without specific prior written permission.
#
# THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
# AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
# IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
# DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE
# FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
# DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
# SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
# CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
# OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
# OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

module Truffle::FFI
  class Pointer
    def self.find_type_size(type)
      if defined?(::FFI) # Full FFI loaded
        ::FFI.type_size(::FFI.find_type(type))
      else
        Truffle.invoke_primitive :pointer_find_type_size, type
      end
    end

    def initialize(a1, a2=undefined)
      if undefined.equal? a2
        if Truffle::Interop.pointer?(a1)
          a1 = Truffle::Interop.as_pointer(a1)
        end
        self.address = a1
      else
        @type = a1
        self.address = a2
      end
    end

    def inspect
      # Don't have this print the data at the location. It can crash everything.
      addr = address()

      if addr < 0
        sign = '-'
        addr = -addr
      else
        sign = ''
      end

      "#<#{self.class.name} address=#{sign}0x#{addr.to_s(16)}>"
    end

    def pointer?
      true
    end

    # Every IS_POINTER object should also have TO_NATIVE
    def to_native
      self
    end

    def null?
      address == 0x0
    end

    def +(offset)
      Pointer.new(address + offset)
    end

    def slice(offset, length)
      Pointer.new(address + offset) # TODO: track length in Pointer
    end

    def ==(other)
      return false unless other.kind_of? Pointer
      address == other.address
    end

    def network_order(start, size)
      raise 'FFI::Pointer#network_order not yet implemented'
    end

    # Read +len+ bytes from the memory pointed to and return them as
    # a String
    def read_string_length(len)
      Truffle.primitive :pointer_read_string
      raise PrimitiveFailure, 'FFI::Pointer#read_string_length primitive failed'
    end
    alias :read_bytes :read_string_length

    # Read bytes from the memory pointed to until a NULL is seen, return
    # the bytes as a String
    def read_string_to_null
      Truffle.primitive :pointer_read_string_to_null
      raise PrimitiveFailure, 'FFI::Pointer#read_string_to_null primitive failed'
    end

    # Read bytes as a String from the memory pointed to
    def read_string(len=nil)
      if len
        read_string_length(len)
      else
        read_string_to_null
      end
    end

    # FFI compat methods
    def get_bytes(offset, length)
      (self + offset).read_string_length(length)
    end

    # Write String +str+ as bytes into the memory pointed to. Only
    # write up to +len+ bytes.
    def write_string_length(str, len)
      Truffle.primitive :pointer_write_string
      raise PrimitiveFailure, 'FFI::Pointer#write_string_length primitive failed'
    end

    # Write a String +str+ as bytes to the memory pointed to.
    def write_string(str, len=nil)
      len = str.bytesize unless len

      write_string_length(str, len)
    end

    def __copy_from__(pointer, size)
      put_bytes(0, pointer.get_bytes(0, size))
    end

    # Read bytes from +offset+ from the memory pointed to as type +type+
    def get_at_offset(offset, type)
      Truffle.primitive :pointer_get_at_offset
      raise PrimitiveFailure, 'FFI::Pointer#get_at_offset primitive failed'
    end

    # Write +val+ as type +type+ to bytes from +offset+
    def set_at_offset(offset, type, val)
      Truffle.primitive :pointer_set_at_offset
      raise PrimitiveFailure, 'FFI::Pointer#set_at_offset primitive failed'
    end

    # Number of bytes taken up by a pointer.
    def self.size
      8
    end

    NULL = Pointer.new(0x0)
  end

  class MemoryPointer < Pointer

    # Indicates how many bytes the chunk of memory that is pointed to takes up.
    attr_accessor :total

    # Indicates how many bytes the type that the pointer is cast as uses.
    attr_accessor :type_size

    # call-seq:
    #   MemoryPointer.new(num) => MemoryPointer instance of <i>num</i> bytes
    #   MemoryPointer.new(sym) => MemoryPointer instance with number
    #                             of bytes need by FFI type <i>sym</i>
    #   MemoryPointer.new(obj) => MemoryPointer instance with number
    #                             of <i>obj.size</i> bytes
    #   MemoryPointer.new(sym, count) => MemoryPointer instance with number
    #                             of bytes need by length-<i>count</i> array
    #                             of FFI type <i>sym</i>
    #   MemoryPointer.new(obj, count) => MemoryPointer instance with number
    #                             of bytes need by length-<i>count</i> array
    #                             of <i>obj.size</i> bytes
    #   MemoryPointer.new(arg) { |p| ... }
    #
    # Both forms create a MemoryPointer instance. The number of bytes to
    # allocate is either specified directly or by passing an FFI type, which
    # specifies the number of bytes needed for that type.
    #
    # The form without a block returns the MemoryPointer instance. The form
    # with a block yields the MemoryPointer instance and frees the memory
    # when the block returns. The value returned is the value of the block.
    #
    def self.new(type, count=nil, clear=true)
      if type.kind_of? Integer
        size = type
      elsif type.kind_of? Symbol
        size = Pointer.find_type_size(type)
      else
        size = type.size
      end

      if count
        total = size * count
      else
        total = size
      end

      ptr = Truffle.invoke_primitive :pointer_malloc, self, total
      ptr.total = total
      ptr.type_size = size
      Truffle.invoke_primitive :pointer_clear, ptr, total if clear

      if block_given?
        begin
          yield ptr
        ensure
          ptr.free
        end
      else
        ptr.autorelease = true
        ptr
      end
    end

    def self.from_string(str)
      ptr = new str.bytesize + 1
      ptr.write_string str + "\0"

      ptr
    end

    def copy
      other = Truffle.invoke_primitive :pointer_malloc, self, total
      other.total = total
      other.type_size = type_size
      Truffle::POSIX.memcpy other, self, total

      Truffle.privately do
        other.initialize_copy self
      end

      other
    end

    # Access the MemoryPointer like a C array, accessing the +which+ number
    # element in memory. The position of the element is calculate from
    # +@type_size+ and +which+. A new MemoryPointer object is returned, which
    # points to the address of the element.
    #
    # Example:
    #   ptr = MemoryPointer.new(:int, 20)
    #   new_ptr = ptr[9]
    #
    # c-equiv:
    #   int *ptr = (int*)malloc(sizeof(int) * 20);
    #   int *new_ptr;
    #   new_ptr = &ptr[9];
    #
    def [](which)
      raise ArgumentError, 'unknown type size' unless @type_size
      self + (which * @type_size)
    end
  end
end
