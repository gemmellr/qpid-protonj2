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
package org.apache.qpid.proton4j.engine;

import org.apache.qpid.proton4j.buffer.ProtonBufferAllocator;

/**
 * Configuration options for the Engine
 */
public interface EngineConfiguration {

    /**
     * Sets the maximum frame size the engine will accept.
     * <p>
     * The configured value cannot be set lower than the AMQP default max frame size
     * value of 512 bytes.
     *
     * @param maxFrameSize
     *      The value to assign as the maximum frame size.
     */
    void setMaxFrameSize(int maxFrameSize);

    /**
     * @return the maximum frame size that the Engine will accept.
     */
    int getMaxFrameSize();

    /**
     * Sets the ProtonBufferAllocator used by this Engine.
     * <p>
     * When copying data, encoding types or otherwise needing to allocate memory
     * storage the Engine will use the assigned {@link ProtonBufferAllocator}.
     * If no allocator is assigned the Engine will use the default allocator.
     *
     * @param allocator
     *      The Allocator instance to use from this {@link Engine}.
     */
    void setBufferAllocator(ProtonBufferAllocator allocator);

    /**
     * @return the currently assigned {@link ProtonBufferAllocator}.
     */
    ProtonBufferAllocator getBufferAllocator();

}
