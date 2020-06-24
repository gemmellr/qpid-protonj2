/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.messaginghub.amqperative.test;

import java.util.HashMap;
import java.util.Map;

import org.apache.qpid.proton4j.buffer.ProtonBuffer;
import org.apache.qpid.proton4j.buffer.ProtonByteBufferAllocator;
import org.apache.qpid.proton4j.codec.CodecFactory;
import org.apache.qpid.proton4j.codec.Encoder;
import org.apache.qpid.proton4j.types.messaging.Section;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.rules.TestName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AMQPerativeTestCase {

    public static final boolean IS_WINDOWS = System.getProperty("os.name", "unknown").toLowerCase().contains("windows");

    private final Logger LOG = LoggerFactory.getLogger(getClass());

    private final Map<String, String> propertiesSetForTest = new HashMap<String, String>();

    @Rule
    public TestName testName = new TestName();

    /**
     * Set a System property for duration of this test only. The tearDown will guarantee to reset the property to its
     * previous value after the test completes.
     *
     * @param property
     *            The property to set
     * @param value
     *            the value to set it to, if null, the property will be cleared
     */
    protected void setTestSystemProperty(final String property, final String value) {
        if (!propertiesSetForTest.containsKey(property)) {
            // Record the current value so we can revert it later.
            propertiesSetForTest.put(property, System.getProperty(property));
        }

        if (value == null) {
            System.clearProperty(property);
            LOG.info("Set system property '" + property + "' to be cleared");
        } else {
            System.setProperty(property, value);
            LOG.info("Set system property '" + property + "' to: '" + value + "'");
        }

    }

    /**
     * Restore the System property values that were set by this test run.
     */
    protected void revertTestSystemProperties() {
        if (!propertiesSetForTest.isEmpty()) {
            for (String key : propertiesSetForTest.keySet()) {
                String value = propertiesSetForTest.get(key);
                if (value != null) {
                    System.setProperty(key, value);
                    LOG.info("Reverted system property '" + key + "' to: '" + value + "'");
                } else {
                    System.clearProperty(key);
                    LOG.info("Reverted system property '" + key + "' to be cleared");
                }
            }

            propertiesSetForTest.clear();
        }
    }

    @After
    public void tearDown() throws java.lang.Exception {
        LOG.info("========== tearDown " + getTestName() + " ==========");
        revertTestSystemProperties();
    }

    @Before
    public void setUp() throws Exception {
        LOG.info("========== start " + getTestName() + " ==========");
    }

    protected String getTestName() {
        return getClass().getSimpleName() + "." + testName.getMethodName();
    }

    protected byte[] createEncodedMessage(Section body) {
        Encoder encoder = CodecFactory.getEncoder();
        ProtonBuffer buffer = new ProtonByteBufferAllocator().allocate();
        encoder.writeObject(buffer, encoder.newEncoderState(), body);
        byte[] result = new byte[buffer.getReadableBytes()];
        buffer.readBytes(result);
        return result;
    }
}
