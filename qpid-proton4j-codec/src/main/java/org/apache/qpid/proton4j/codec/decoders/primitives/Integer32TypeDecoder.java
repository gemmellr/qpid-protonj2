/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.qpid.proton4j.codec.decoders.primitives;

import java.io.IOException;

import org.apache.qpid.proton4j.codec.DecoderState;
import org.apache.qpid.proton4j.codec.EncodingCodes;
import org.apache.qpid.proton4j.codec.PrimitiveTypeDecoder;

import io.netty.buffer.ByteBuf;

/**
 * Decode AMQP Integer values from a byte stream
 */
public class Integer32TypeDecoder implements PrimitiveTypeDecoder<Integer> {

    @Override
    public boolean isJavaPrimitive() {
        return true;
    }

    @Override
    public Class<Integer> getTypeClass() {
        return Integer.class;
    }

    @Override
    public int getTypeCode() {
        return EncodingCodes.INT & 0xff;
    }

    @Override
    public Integer readValue(ByteBuf buffer, DecoderState state) {
        return buffer.readInt();
    }

    public int readPrimitiveValue(ByteBuf buffer, DecoderState state) {
        return buffer.readInt();
    }

    @Override
    public void skipValue(ByteBuf buffer, DecoderState state) throws IOException {
        buffer.skipBytes(Integer.BYTES);
    }
}
