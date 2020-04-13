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
package org.apache.qpid.proton4j.engine.sasl.client;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import javax.security.sasl.SaslException;

import org.apache.qpid.proton4j.buffer.ProtonBuffer;
import org.junit.Test;

public class ExternalMechanismTest extends MechanismTestBase {

    @Test
    public void testGetInitialResponseWithNullUserAndPassword() throws SaslException {
        ExternalMechanism mech = new ExternalMechanism();

        ProtonBuffer response = mech.getInitialResponse(credentials());
        assertNotNull(response);
        assertTrue(response.getReadableBytes() == 0);
    }

    @Test
    public void testGetChallengeResponse() throws SaslException {
        ExternalMechanism mech = new ExternalMechanism();

        ProtonBuffer response = mech.getChallengeResponse(credentials(), TEST_BUFFER);
        assertNotNull(response);
        assertTrue(response.getReadableBytes() == 0);
    }

    @Test
    public void testIsNotApplicableWithUserAndPasswordButNoPrincipal() {
        assertFalse("Should not be applicable with user and password but no principal",
            SaslMechanisms.EXTERNAL.createMechanism().isApplicable(credentials("user", "password", false)));
    }

    @Test
    public void testIsApplicableWithUserAndPasswordAndPrincipal() {
        assertTrue("Should be applicable with user and password and principal",
            SaslMechanisms.EXTERNAL.createMechanism().isApplicable(credentials("user", "password", true)));
    }

    @Test
    public void testIsApplicableWithPrincipalOnly() {
        assertTrue("Should be applicable with principal only",
            SaslMechanisms.EXTERNAL.createMechanism().isApplicable(credentials(null, null, true)));
    }
}
