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
package org.apache.qpid.proton4j.codec.decoders.transport;

import java.io.IOException;

import org.apache.qpid.proton4j.amqp.Symbol;
import org.apache.qpid.proton4j.amqp.UnsignedLong;
import org.apache.qpid.proton4j.amqp.transport.Detach;
import org.apache.qpid.proton4j.amqp.transport.ErrorCondition;
import org.apache.qpid.proton4j.codec.DecoderState;
import org.apache.qpid.proton4j.codec.DescribedTypeDecoder;
import org.apache.qpid.proton4j.codec.TypeDecoder;
import org.apache.qpid.proton4j.codec.decoders.primitives.ListTypeDecoder;
import org.apache.qpid.proton4j.codec.decoders.primitives.ListTypeDecoder.ListEntryHandler;

import io.netty.buffer.ByteBuf;

/**
 * Decoder of AMQP Detach type values from a byte stream
 */
public class DetachTypeDecoder implements DescribedTypeDecoder<Detach>, ListEntryHandler<Detach> {

    @Override
    public Class<Detach> getTypeClass() {
        return Detach.class;
    }

    @Override
    public UnsignedLong getDescriptorCode() {
        return Detach.DESCRIPTOR_CODE;
    }

    @Override
    public Symbol getDescriptorSymbol() {
        return Detach.DESCRIPTOR_SYMBOL;
    }

    @Override
    public Detach readValue(ByteBuf buffer, DecoderState state) throws IOException {
        TypeDecoder<?> decoder = state.getDecoder().readNextTypeDecoder(buffer, state);

        if (!(decoder instanceof ListTypeDecoder)) {
            throw new IOException("Expected List type indicator but got decoder for type: " + decoder.getTypeClass().getName());
        }

        ListTypeDecoder listDecoder = (ListTypeDecoder) decoder;
        Detach detach = new Detach();

        listDecoder.readValue(buffer, state, this, detach);

        return detach;
    }

    @Override
    public void onListEntry(int index, Detach detach, ByteBuf buffer, DecoderState state) throws IOException {
        switch (index) {
            case 0:
                detach.setHandle(state.getDecoder().readUnsignedInteger(buffer, state));
                break;
            case 1:
                detach.setClosed(Boolean.TRUE.equals(state.getDecoder().readBoolean(buffer, state)));
                break;
            case 2:
                detach.setError(state.getDecoder().readObject(buffer, state, ErrorCondition.class));
                break;
            default:
                throw new IllegalStateException("To many entries in Detach encoding");
        }
    }

    @Override
    public void skipValue(ByteBuf buffer, DecoderState state) throws IOException {
        TypeDecoder<?> decoder = state.getDecoder().readNextTypeDecoder(buffer, state);

        if (!(decoder instanceof ListTypeDecoder)) {
            throw new IOException("Expected List type indicator but got decoder for type: " + decoder.getTypeClass().getName());
        }

        decoder.skipValue(buffer, state);
    }
}
