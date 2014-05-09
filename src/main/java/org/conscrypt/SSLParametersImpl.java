/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.conscrypt;

import org.conscrypt.util.EmptyArray;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.security.InvalidKeyException;
import java.security.KeyManagementException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import javax.crypto.SecretKey;
import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedKeyManager;
import javax.net.ssl.X509KeyManager;
import javax.net.ssl.X509TrustManager;
import javax.security.auth.x500.X500Principal;

/**
 * The instances of this class encapsulate all the info
 * about enabled cipher suites and protocols,
 * as well as the information about client/server mode of
 * ssl socket, whether it require/want client authentication or not,
 * and controls whether new SSL sessions may be established by this
 * socket or not.
 */
public class SSLParametersImpl implements Cloneable {

    // default source of X.509 certificate based authentication keys
    private static volatile X509KeyManager defaultX509KeyManager;
    // default source of X.509 certificate based authentication trust decisions
    private static volatile X509TrustManager defaultX509TrustManager;
    // default source of random numbers
    private static volatile SecureRandom defaultSecureRandom;
    // default SSL parameters
    private static volatile SSLParametersImpl defaultParameters;

    // client session context contains the set of reusable
    // client-side SSL sessions
    private final ClientSessionContext clientSessionContext;
    // server session context contains the set of reusable
    // server-side SSL sessions
    private final ServerSessionContext serverSessionContext;
    // source of X.509 certificate based authentication keys or null if not provided
    private final X509KeyManager x509KeyManager;
    // source of Pre-Shared Key (PSK) authentication keys or null if not provided.
    private final PSKKeyManager pskKeyManager;
    // source of X.509 certificate based authentication trust decisions or null if not provided
    private final X509TrustManager x509TrustManager;
    // source of random numbers
    private SecureRandom secureRandom;

    // protocols available for SSL connection
    private String[] enabledProtocols = NativeCrypto.getDefaultProtocols();

    // if the peer with this parameters tuned to work in client mode
    private boolean client_mode = true;
    // if the peer with this parameters tuned to require client authentication
    private boolean need_client_auth = false;
    // if the peer with this parameters tuned to request client authentication
    private boolean want_client_auth = false;
    // if the peer with this parameters allowed to cteate new SSL session
    private boolean enable_session_creation = true;
    private String endpointIdentificationAlgorithm;

    String[] openSslEnabledProtocols = NativeCrypto.getDefaultProtocols();
    String[] openSslEnabledCipherSuites = NativeCrypto.getDefaultCipherSuites();

    byte[] npnProtocols;
    byte[] alpnProtocols;
    boolean useSessionTickets;
    boolean useSni;

    /**
     * Whether the TLS Channel ID extension is enabled. This field is
     * server-side only.
     */
    boolean channelIdEnabled;

    /**
     * Initializes the parameters. Naturally this constructor is used
     * in SSLContextImpl.engineInit method which directly passes its
     * parameters. In other words this constructor holds all
     * the functionality provided by SSLContext.init method.
     * See {@link javax.net.ssl.SSLContext#init(KeyManager[],TrustManager[],
     * SecureRandom)} for more information
     */
    protected SSLParametersImpl(KeyManager[] kms, TrustManager[] tms,
            SecureRandom sr, ClientSessionContext clientSessionContext,
            ServerSessionContext serverSessionContext)
            throws KeyManagementException {
        this.serverSessionContext = serverSessionContext;
        this.clientSessionContext = clientSessionContext;

        // initialize key managers
        if (kms == null) {
            x509KeyManager = getDefaultX509KeyManager();
            // There's no default PSK key manager
            pskKeyManager = null;
        } else {
            x509KeyManager = findFirstX509KeyManager(kms);
            pskKeyManager = findFirstPSKKeyManager(kms);
        }

        // initialize x509TrustManager
        if (tms == null) {
            x509TrustManager = getDefaultX509TrustManager();
        } else {
            x509TrustManager = findFirstX509TrustManager(tms);
        }
        // initialize secure random
        // BEGIN android-removed
        // if (sr == null) {
        //     if (defaultSecureRandom == null) {
        //         defaultSecureRandom = new SecureRandom();
        //     }
        //     secureRandom = defaultSecureRandom;
        // } else {
        //     secureRandom = sr;
        // }
        // END android-removed
        // BEGIN android-added
        // We simply use the SecureRandom passed in by the caller. If it's
        // null, we don't replace it by a new instance. The native code below
        // then directly accesses /dev/urandom. Not the most elegant solution,
        // but faster than going through the SecureRandom object.
        secureRandom = sr;
        // END android-added
    }

    protected static SSLParametersImpl getDefault() throws KeyManagementException {
        SSLParametersImpl result = defaultParameters;
        if (result == null) {
            // single-check idiom
            defaultParameters = result = new SSLParametersImpl(null,
                                                               null,
                                                               null,
                                                               new ClientSessionContext(),
                                                               new ServerSessionContext());
        }
        return (SSLParametersImpl) result.clone();
    }

    /**
     * Returns the appropriate session context.
     */
    public AbstractSessionContext getSessionContext() {
        return client_mode ? clientSessionContext : serverSessionContext;
    }

    /**
     * @return server session context
     */
    protected ServerSessionContext getServerSessionContext() {
        return serverSessionContext;
    }

    /**
     * @return client session context
     */
    protected ClientSessionContext getClientSessionContext() {
        return clientSessionContext;
    }

    /**
     * @return X.509 key manager or {@code null} for none.
     */
    protected X509KeyManager getX509KeyManager() {
        return x509KeyManager;
    }

    /**
     * @return Pre-Shared Key (PSK) key manager or {@code null} for none.
     */
    protected PSKKeyManager getPSKKeyManager() {
        return pskKeyManager;
    }

    /**
     * @return X.509 trust manager or {@code null} for none.
     */
    protected X509TrustManager getX509TrustManager() {
        return x509TrustManager;
    }

    /**
     * @return secure random
     */
    protected SecureRandom getSecureRandom() {
        if (secureRandom != null) {
            return secureRandom;
        }
        SecureRandom result = defaultSecureRandom;
        if (result == null) {
            // single-check idiom
            defaultSecureRandom = result = new SecureRandom();
        }
        secureRandom = result;
        return secureRandom;
    }

    /**
     * @return the secure random member reference, even it is null
     */
    protected SecureRandom getSecureRandomMember() {
        return secureRandom;
    }

    /**
     * @return the names of enabled cipher suites
     */
    protected String[] getEnabledCipherSuites() {
        return openSslEnabledCipherSuites.clone();
    }

    /**
     * Sets the enabled cipher suites after filtering through OpenSSL.
     */
    protected void setEnabledCipherSuites(String[] cipherSuites) {
        openSslEnabledCipherSuites = NativeCrypto.checkEnabledCipherSuites(cipherSuites);
    }

    /**
     * @return the set of enabled protocols
     */
    protected String[] getEnabledProtocols() {
        return enabledProtocols.clone();
    }

    /**
     * Sets the set of available protocols for use in SSL connection.
     * @param protocols String[]
     */
    protected void setEnabledProtocols(String[] protocols) {
        enabledProtocols = NativeCrypto.checkEnabledProtocols(protocols);
    }

    /**
     * Tunes the peer holding this parameters to work in client mode.
     * @param   mode if the peer is configured to work in client mode
     */
    protected void setUseClientMode(boolean mode) {
        client_mode = mode;
    }

    /**
     * Returns the value indicating if the parameters configured to work
     * in client mode.
     */
    protected boolean getUseClientMode() {
        return client_mode;
    }

    /**
     * Tunes the peer holding this parameters to require client authentication
     */
    protected void setNeedClientAuth(boolean need) {
        need_client_auth = need;
        // reset the want_client_auth setting
        want_client_auth = false;
    }

    /**
     * Returns the value indicating if the peer with this parameters tuned
     * to require client authentication
     */
    protected boolean getNeedClientAuth() {
        return need_client_auth;
    }

    /**
     * Tunes the peer holding this parameters to request client authentication
     */
    protected void setWantClientAuth(boolean want) {
        want_client_auth = want;
        // reset the need_client_auth setting
        need_client_auth = false;
    }

    /**
     * Returns the value indicating if the peer with this parameters
     * tuned to request client authentication
     */
    protected boolean getWantClientAuth() {
        return want_client_auth;
    }

    /**
     * Allows/disallows the peer holding this parameters to
     * create new SSL session
     */
    protected void setEnableSessionCreation(boolean flag) {
        enable_session_creation = flag;
    }

    /**
     * Returns the value indicating if the peer with this parameters
     * allowed to cteate new SSL session
     */
    protected boolean getEnableSessionCreation() {
        return enable_session_creation;
    }

    /**
     * Whether connections using this SSL connection should use the TLS
     * extension Server Name Indication (SNI).
     */
    protected void setUseSni(boolean flag) {
        useSni = flag;
    }

    /**
     * Returns whether connections using this SSL connection should use the TLS
     * extension Server Name Indication (SNI).
     */
    protected boolean getUseSni() {
        return useSni;
    }

    static byte[][] encodeIssuerX509Principals(X509Certificate[] certificates)
            throws CertificateEncodingException {
        byte[][] principalBytes = new byte[certificates.length][];
        for (int i = 0; i < certificates.length; i++) {
            principalBytes[i] = certificates[i].getIssuerX500Principal().getEncoded();
        }
        return principalBytes;
    }

    /**
     * Return a possibly null array of X509Certificates given the possibly null
     * array of DER encoded bytes.
     */
    private static OpenSSLX509Certificate[] createCertChain(long[] certificateRefs)
            throws IOException {
        if (certificateRefs == null) {
            return null;
        }
        OpenSSLX509Certificate[] certificates = new OpenSSLX509Certificate[certificateRefs.length];
        for (int i = 0; i < certificateRefs.length; i++) {
            certificates[i] = new OpenSSLX509Certificate(certificateRefs[i]);
        }
        return certificates;
    }

    OpenSSLSessionImpl getSessionToReuse(long sslNativePointer, String hostname, int port)
            throws SSLException {
        final OpenSSLSessionImpl sessionToReuse;
        if (client_mode) {
            // look for client session to reuse
            sessionToReuse = getCachedClientSession(clientSessionContext, hostname, port);
            if (sessionToReuse != null) {
                NativeCrypto.SSL_set_session(sslNativePointer,
                        sessionToReuse.sslSessionNativePointer);
            }
        } else {
            sessionToReuse = null;
        }
        return sessionToReuse;
    }

    void setTlsChannelId(long sslNativePointer, OpenSSLKey channelIdPrivateKey)
            throws SSLHandshakeException, SSLException {
        // TLS Channel ID
        if (channelIdEnabled) {
            if (client_mode) {
                // Client-side TLS Channel ID
                if (channelIdPrivateKey == null) {
                    throw new SSLHandshakeException("Invalid TLS channel ID key specified");
                }
                NativeCrypto.SSL_set1_tls_channel_id(sslNativePointer,
                        channelIdPrivateKey.getPkeyContext());
            } else {
                // Server-side TLS Channel ID
                NativeCrypto.SSL_enable_tls_channel_id(sslNativePointer);
            }
        }
    }

    void setCertificate(long sslNativePointer, String alias) throws CertificateEncodingException,
            SSLException {
        if (alias == null) {
            return;
        }
        X509KeyManager keyManager = getX509KeyManager();
        if (keyManager == null) {
            return;
        }
        PrivateKey privateKey = keyManager.getPrivateKey(alias);
        if (privateKey == null) {
            return;
        }
        X509Certificate[] certificates = keyManager.getCertificateChain(alias);
        if (certificates == null) {
            return;
        }

        /*
         * Make sure we keep a reference to the OpenSSLX509Certificate by using
         * this array. Otherwise, if they're not OpenSSLX509Certificate
         * instances originally, they may be garbage collected before we
         * complete our JNI calls.
         */
        OpenSSLX509Certificate[] openSslCerts = new OpenSSLX509Certificate[certificates.length];
        long[] x509refs = new long[certificates.length];
        for (int i = 0; i < certificates.length; i++) {
            OpenSSLX509Certificate openSslCert = OpenSSLX509Certificate
                    .fromCertificate(certificates[i]);
            openSslCerts[i] = openSslCert;
            x509refs[i] = openSslCert.getContext();
        }

        // Note that OpenSSL says to use SSL_use_certificate before
        // SSL_use_PrivateKey.
        NativeCrypto.SSL_use_certificate(sslNativePointer, x509refs);

        try {
            final OpenSSLKey key = OpenSSLKey.fromPrivateKey(privateKey);
            NativeCrypto.SSL_use_PrivateKey(sslNativePointer, key.getPkeyContext());
        } catch (InvalidKeyException e) {
            throw new SSLException(e);
        }

        // checks the last installed private key and certificate,
        // so need to do this once per loop iteration
        NativeCrypto.SSL_check_private_key(sslNativePointer);
    }

    void setSSLParameters(long sslCtxNativePointer, long sslNativePointer, AliasChooser chooser,
            PSKCallbacks pskCallbacks, String hostname) throws SSLException, IOException {
        if (npnProtocols != null) {
            NativeCrypto.SSL_CTX_enable_npn(sslCtxNativePointer);
        }

        if (client_mode && alpnProtocols != null) {
            NativeCrypto.SSL_set_alpn_protos(sslNativePointer, alpnProtocols);
        }

        String[] enabledCipherSuites = openSslEnabledCipherSuites;
        NativeCrypto.setEnabledProtocols(sslNativePointer, openSslEnabledProtocols);
        NativeCrypto.setEnabledCipherSuites(sslNativePointer, enabledCipherSuites);

        // setup server certificates and private keys.
        // clients will receive a call back to request certificates.
        if (!client_mode) {
            Set<String> keyTypes = new HashSet<String>();
            for (long sslCipherNativePointer : NativeCrypto.SSL_get_ciphers(sslNativePointer)) {
                String keyType = getServerX509KeyType(sslCipherNativePointer);
                if (keyType != null) {
                    keyTypes.add(keyType);
                }
            }
            X509KeyManager keyManager = getX509KeyManager();
            if (keyManager != null) {
                for (String keyType : keyTypes) {
                    try {
                        setCertificate(sslNativePointer,
                                chooser.chooseServerAlias(x509KeyManager, keyType));
                    } catch (CertificateEncodingException e) {
                        throw new IOException(e);
                    }
                }
            }
        }

        // Enable Pre-Shared Key (PSK) key exchange if requested
        PSKKeyManager pskKeyManager = getPSKKeyManager();
        if (pskKeyManager != null) {
            boolean pskEnabled = false;
            for (String enabledCipherSuite : enabledCipherSuites) {
                if ((enabledCipherSuite != null) && (enabledCipherSuite.contains("PSK"))) {
                    pskEnabled = true;
                    break;
                }
            }
            if (pskEnabled) {
                if (client_mode) {
                    NativeCrypto.set_SSL_psk_client_callback_enabled(sslNativePointer, true);
                } else {
                    NativeCrypto.set_SSL_psk_server_callback_enabled(sslNativePointer, true);
                    String identityHint = pskCallbacks.chooseServerPSKIdentityHint(pskKeyManager);
                    NativeCrypto.SSL_use_psk_identity_hint(sslNativePointer, identityHint);
                }
            }
        }

        if (useSessionTickets) {
            NativeCrypto.SSL_clear_options(sslNativePointer, NativeCrypto.SSL_OP_NO_TICKET);
        }
        if (useSni) {
            NativeCrypto.SSL_set_tlsext_host_name(sslNativePointer, hostname);
        }

        // BEAST attack mitigation (1/n-1 record splitting for CBC cipher suites
        // with TLSv1 and SSLv3).
        NativeCrypto.SSL_set_mode(sslNativePointer, NativeCrypto.SSL_MODE_CBC_RECORD_SPLITTING);

        boolean enableSessionCreation = getEnableSessionCreation();
        if (!enableSessionCreation) {
            NativeCrypto.SSL_set_session_creation_enabled(sslNativePointer, enableSessionCreation);
        }
    }

    void setCertificateValidation(long sslNativePointer) throws IOException {
        // setup peer certificate verification
        if (!client_mode) {
            // needing client auth takes priority...
            boolean certRequested;
            if (getNeedClientAuth()) {
                NativeCrypto.SSL_set_verify(sslNativePointer,
                                            NativeCrypto.SSL_VERIFY_PEER
                                            | NativeCrypto.SSL_VERIFY_FAIL_IF_NO_PEER_CERT);
                certRequested = true;
            // ... over just wanting it...
            } else if (getWantClientAuth()) {
                NativeCrypto.SSL_set_verify(sslNativePointer, NativeCrypto.SSL_VERIFY_PEER);
                certRequested = true;
            // ... and we must disable verification if we don't want client auth.
            } else {
                NativeCrypto.SSL_set_verify(sslNativePointer, NativeCrypto.SSL_VERIFY_NONE);
                certRequested = false;
            }

            if (certRequested) {
                X509TrustManager trustManager = getX509TrustManager();
                X509Certificate[] issuers = trustManager.getAcceptedIssuers();
                if (issuers != null && issuers.length != 0) {
                    byte[][] issuersBytes;
                    try {
                        issuersBytes = encodeIssuerX509Principals(issuers);
                    } catch (CertificateEncodingException e) {
                        throw new IOException("Problem encoding principals", e);
                    }
                    NativeCrypto.SSL_set_client_CA_list(sslNativePointer, issuersBytes);
                }
            }
        }
    }

    OpenSSLSessionImpl setupSession(long sslSessionNativePointer, long sslNativePointer,
            final OpenSSLSessionImpl sessionToReuse, String hostname, int port,
            boolean handshakeCompleted) throws IOException {
        OpenSSLSessionImpl sslSession = null;
        byte[] sessionId = NativeCrypto.SSL_SESSION_session_id(sslSessionNativePointer);
        if (sessionToReuse != null && Arrays.equals(sessionToReuse.getId(), sessionId)) {
            sslSession = sessionToReuse;
            sslSession.lastAccessedTime = System.currentTimeMillis();
            NativeCrypto.SSL_SESSION_free(sslSessionNativePointer);
        } else {
            if (!getEnableSessionCreation()) {
                // Should have been prevented by
                // NativeCrypto.SSL_set_session_creation_enabled
                throw new IllegalStateException("SSL Session may not be created");
            }
            X509Certificate[] localCertificates = createCertChain(NativeCrypto
                    .SSL_get_certificate(sslNativePointer));
            X509Certificate[] peerCertificates = createCertChain(NativeCrypto
                    .SSL_get_peer_cert_chain(sslNativePointer));
            sslSession = new OpenSSLSessionImpl(sslSessionNativePointer, localCertificates,
                    peerCertificates, hostname, port, getSessionContext());
            // if not, putSession later in handshakeCompleted() callback
            if (handshakeCompleted) {
                getSessionContext().putSession(sslSession);
            }
        }
        return sslSession;
    }

    void chooseClientCertificate(byte[] keyTypeBytes, byte[][] asn1DerEncodedPrincipals,
            long sslNativePointer, AliasChooser chooser) throws SSLException,
            CertificateEncodingException {
        String[] keyTypes = new String[keyTypeBytes.length];
        for (int i = 0; i < keyTypeBytes.length; i++) {
            keyTypes[i] = getClientKeyType(keyTypeBytes[i]);
        }

        X500Principal[] issuers;
        if (asn1DerEncodedPrincipals == null) {
            issuers = null;
        } else {
            issuers = new X500Principal[asn1DerEncodedPrincipals.length];
            for (int i = 0; i < asn1DerEncodedPrincipals.length; i++) {
                issuers[i] = new X500Principal(asn1DerEncodedPrincipals[i]);
            }
        }
        X509KeyManager keyManager = getX509KeyManager();
        String alias = (keyManager != null) ? chooser.chooseClientAlias(keyManager, issuers,
                keyTypes) : null;
        setCertificate(sslNativePointer, alias);
    }

    /**
     * @see NativeCrypto.SSLHandshakeCallbacks#clientPSKKeyRequested(String, byte[], byte[])
     */
    int clientPSKKeyRequested(
            String identityHint, byte[] identityBytesOut, byte[] key, PSKCallbacks pskCallbacks) {
        PSKKeyManager pskKeyManager = getPSKKeyManager();
        if (pskKeyManager == null) {
            return 0;
        }

        String identity = pskCallbacks.chooseClientPSKIdentity(pskKeyManager, identityHint);
        // Store identity in NULL-terminated modified UTF-8 representation into ientityBytesOut
        byte[] identityBytes;
        if (identity == null) {
            identity = "";
            identityBytes = EmptyArray.BYTE;
        } else if (identity.isEmpty()) {
            identityBytes = EmptyArray.BYTE;
        } else {
            try {
                identityBytes = identity.getBytes("UTF-8");
            } catch (UnsupportedEncodingException e) {
                throw new RuntimeException("UTF-8 encoding not supported", e);
            }
        }
        if (identityBytes.length + 1 > identityBytesOut.length) {
            // Insufficient space in the output buffer
            return 0;
        }
        if (identityBytes.length > 0) {
            System.arraycopy(identityBytes, 0, identityBytesOut, 0, identityBytes.length);
        }
        identityBytesOut[identityBytes.length] = 0;

        SecretKey secretKey = pskCallbacks.getPSKKey(pskKeyManager, identityHint, identity);
        byte[] secretKeyBytes = secretKey.getEncoded();
        if (secretKeyBytes == null) {
            return 0;
        } else if (secretKeyBytes.length > key.length) {
            // Insufficient space in the output buffer
            return 0;
        }
        System.arraycopy(secretKeyBytes, 0, key, 0, secretKeyBytes.length);
        return secretKeyBytes.length;
    }

    /**
     * @see NativeCrypto.SSLHandshakeCallbacks#serverPSKKeyRequested(String, String, byte[])
     */
    int serverPSKKeyRequested(
            String identityHint, String identity, byte[] key, PSKCallbacks pskCallbacks) {
        PSKKeyManager pskKeyManager = getPSKKeyManager();
        if (pskKeyManager == null) {
            return 0;
        }
        SecretKey secretKey = pskCallbacks.getPSKKey(pskKeyManager, identityHint, identity);
        byte[] secretKeyBytes = secretKey.getEncoded();
        if (secretKeyBytes == null) {
            return 0;
        } else if (secretKeyBytes.length > key.length) {
            return 0;
        }
        System.arraycopy(secretKeyBytes, 0, key, 0, secretKeyBytes.length);
        return secretKeyBytes.length;
    }

    /**
     * Gets the suitable session reference from the session cache container.
     */
    OpenSSLSessionImpl getCachedClientSession(ClientSessionContext sessionContext, String hostName,
            int port) {
        if (hostName == null) {
            return null;
        }
        OpenSSLSessionImpl session = (OpenSSLSessionImpl) sessionContext.getSession(hostName, port);
        if (session == null) {
            return null;
        }

        String protocol = session.getProtocol();
        boolean protocolFound = false;
        for (String enabledProtocol : openSslEnabledProtocols) {
            if (protocol.equals(enabledProtocol)) {
                protocolFound = true;
                break;
            }
        }
        if (!protocolFound) {
            return null;
        }

        String cipherSuite = session.getCipherSuite();
        boolean cipherSuiteFound = false;
        for (String enabledCipherSuite : openSslEnabledCipherSuites) {
            if (cipherSuite.equals(enabledCipherSuite)) {
                cipherSuiteFound = true;
                break;
            }
        }
        if (!cipherSuiteFound) {
            return null;
        }

        return session;
    }

    /**
     * For abstracting the X509KeyManager calls between
     * {@link X509KeyManager#chooseClientAlias(String[], java.security.Principal[], java.net.Socket)}
     * and
     * {@link X509ExtendedKeyManager#chooseEngineClientAlias(String[], java.security.Principal[], javax.net.ssl.SSLEngine)}
     */
    public interface AliasChooser {
        String chooseClientAlias(X509KeyManager keyManager, X500Principal[] issuers,
                String[] keyTypes);

        String chooseServerAlias(X509KeyManager keyManager, String keyType);
    }

    /**
     * For abstracting the {@code PSKKeyManager} calls between those taking an {@code SSLSocket} and
     * those taking an {@code SSLEngine}.
     */
    public interface PSKCallbacks {
        String chooseServerPSKIdentityHint(PSKKeyManager keyManager);
        String chooseClientPSKIdentity(PSKKeyManager keyManager, String identityHint);
        SecretKey getPSKKey(PSKKeyManager keyManager, String identityHint, String identity);
    }

    /**
     * Returns the clone of this object.
     * @return the clone.
     */
    @Override
    protected Object clone() {
        try {
            return super.clone();
        } catch (CloneNotSupportedException e) {
            throw new AssertionError(e);
        }
    }

    private static X509KeyManager getDefaultX509KeyManager() throws KeyManagementException {
        X509KeyManager result = defaultX509KeyManager;
        if (result == null) {
            // single-check idiom
            defaultX509KeyManager = result = createDefaultX509KeyManager();
        }
        return result;
    }
    private static X509KeyManager createDefaultX509KeyManager() throws KeyManagementException {
        try {
            String algorithm = KeyManagerFactory.getDefaultAlgorithm();
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(algorithm);
            kmf.init(null, null);
            KeyManager[] kms = kmf.getKeyManagers();
            X509KeyManager result = findFirstX509KeyManager(kms);
            if (result == null) {
                throw new KeyManagementException("No X509KeyManager among default KeyManagers: "
                        + Arrays.toString(kms));
            }
            return result;
        } catch (NoSuchAlgorithmException e) {
            throw new KeyManagementException(e);
        } catch (KeyStoreException e) {
            throw new KeyManagementException(e);
        } catch (UnrecoverableKeyException e) {
            throw new KeyManagementException(e);
        }
    }

    /**
     * Finds the first {@link X509KeyManager} element in the provided array.
     *
     * @return the first {@code X509KeyManager} or {@code null} if not found.
     */
    private static X509KeyManager findFirstX509KeyManager(KeyManager[] kms) {
        for (KeyManager km : kms) {
            if (km instanceof X509KeyManager) {
                return (X509KeyManager)km;
            }
        }
        return null;
    }

    /**
     * Finds the first {@link PSKKeyManager} element in the provided array.
     *
     * @return the first {@code PSKKeyManager} or {@code null} if not found.
     */
    private static PSKKeyManager findFirstPSKKeyManager(KeyManager[] kms) {
        for (KeyManager km : kms) {
            if (km instanceof PSKKeyManager) {
                return (PSKKeyManager)km;
            }
        }
        return null;
    }

    /**
     * Gets the default X.509 trust manager.
     *
     * TODO: Move this to a published API under dalvik.system.
     */
    public static X509TrustManager getDefaultX509TrustManager()
            throws KeyManagementException {
        X509TrustManager result = defaultX509TrustManager;
        if (result == null) {
            // single-check idiom
            defaultX509TrustManager = result = createDefaultX509TrustManager();
        }
        return result;
    }

    private static X509TrustManager createDefaultX509TrustManager()
            throws KeyManagementException {
        try {
            String algorithm = TrustManagerFactory.getDefaultAlgorithm();
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(algorithm);
            tmf.init((KeyStore) null);
            TrustManager[] tms = tmf.getTrustManagers();
            X509TrustManager trustManager = findFirstX509TrustManager(tms);
            if (trustManager == null) {
                throw new KeyManagementException(
                        "No X509TrustManager in among default TrustManagers: "
                                + Arrays.toString(tms));
            }
            return trustManager;
        } catch (NoSuchAlgorithmException e) {
            throw new KeyManagementException(e);
        } catch (KeyStoreException e) {
            throw new KeyManagementException(e);
        }
    }

    /**
     * Finds the first {@link X509ExtendedTrustManager} or
     * {@link X509TrustManager} element in the provided array.
     *
     * @return the first {@code X509ExtendedTrustManager} or
     *         {@code X509TrustManager} or {@code null} if not found.
     */
    private static X509TrustManager findFirstX509TrustManager(TrustManager[] tms) {
        for (TrustManager tm : tms) {
            if (tm instanceof X509TrustManager) {
                return (X509TrustManager) tm;
            }
        }
        return null;
    }

    public String getEndpointIdentificationAlgorithm() {
        return endpointIdentificationAlgorithm;
    }

    public void setEndpointIdentificationAlgorithm(String endpointIdentificationAlgorithm) {
        this.endpointIdentificationAlgorithm = endpointIdentificationAlgorithm;
    }

    /** Key type: RSA. */
    private static final String KEY_TYPE_RSA = "RSA";

    /** Key type: DSA. */
    private static final String KEY_TYPE_DSA = "DSA";

    /** Key type: Diffie-Hellman with RSA signature. */
    private static final String KEY_TYPE_DH_RSA = "DH_RSA";

    /** Key type: Diffie-Hellman with DSA signature. */
    private static final String KEY_TYPE_DH_DSA = "DH_DSA";

    /** Key type: Elliptic Curve. */
    private static final String KEY_TYPE_EC = "EC";

    /** Key type: Eliiptic Curve with ECDSA signature. */
    private static final String KEY_TYPE_EC_EC = "EC_EC";

    /** Key type: Eliiptic Curve with RSA signature. */
    private static final String KEY_TYPE_EC_RSA = "EC_RSA";

    /**
     * Returns key type constant suitable for calling X509KeyManager.chooseServerAlias or
     * X509ExtendedKeyManager.chooseEngineServerAlias. Returns {@code null} for key exchanges that
     * do not use X.509 for server authentication.
     */
    private static String getServerX509KeyType(long sslCipherNative) throws SSLException {
        int algorithm_mkey = NativeCrypto.get_SSL_CIPHER_algorithm_mkey(sslCipherNative);
        int algorithm_auth = NativeCrypto.get_SSL_CIPHER_algorithm_auth(sslCipherNative);
        switch (algorithm_mkey) {
            case NativeCrypto.SSL_kRSA:
                return KEY_TYPE_RSA;
            case NativeCrypto.SSL_kEDH:
                switch (algorithm_auth) {
                    case NativeCrypto.SSL_aDSS:
                        return KEY_TYPE_DSA;
                    case NativeCrypto.SSL_aRSA:
                        return KEY_TYPE_RSA;
                    case NativeCrypto.SSL_aNULL:
                        return null;
                }
                break;
            case NativeCrypto.SSL_kECDHr:
                return KEY_TYPE_EC_RSA;
            case NativeCrypto.SSL_kECDHe:
                return KEY_TYPE_EC_EC;
            case NativeCrypto.SSL_kEECDH:
                switch (algorithm_auth) {
                    case NativeCrypto.SSL_aECDSA:
                        return KEY_TYPE_EC_EC;
                    case NativeCrypto.SSL_aRSA:
                        return KEY_TYPE_RSA;
                    case NativeCrypto.SSL_aPSK:
                        return null;
                    case NativeCrypto.SSL_aNULL:
                        return null;
                }
                break;
            case NativeCrypto.SSL_kPSK:
                return null;
        }

        throw new SSLException("Unsupported key exchange. "
                + "mkey: 0x" + Long.toHexString(algorithm_mkey & 0xffffffffL)
                + ", auth: 0x" + Long.toHexString(algorithm_auth & 0xffffffffL));
    }

    /**
     * Similar to getServerKeyType, but returns value given TLS
     * ClientCertificateType byte values from a CertificateRequest
     * message for use with X509KeyManager.chooseClientAlias or
     * X509ExtendedKeyManager.chooseEngineClientAlias.
     */
    public static String getClientKeyType(byte keyType) {
        // See also http://www.ietf.org/assignments/tls-parameters/tls-parameters.xml
        switch (keyType) {
            case NativeCrypto.TLS_CT_RSA_SIGN:
                return KEY_TYPE_RSA; // RFC rsa_sign
            case NativeCrypto.TLS_CT_DSS_SIGN:
                return KEY_TYPE_DSA; // RFC dss_sign
            case NativeCrypto.TLS_CT_RSA_FIXED_DH:
                return KEY_TYPE_DH_RSA; // RFC rsa_fixed_dh
            case NativeCrypto.TLS_CT_DSS_FIXED_DH:
                return KEY_TYPE_DH_DSA; // RFC dss_fixed_dh
            case NativeCrypto.TLS_CT_ECDSA_SIGN:
                return KEY_TYPE_EC; // RFC ecdsa_sign
            case NativeCrypto.TLS_CT_RSA_FIXED_ECDH:
                return KEY_TYPE_EC_RSA; // RFC rsa_fixed_ecdh
            case NativeCrypto.TLS_CT_ECDSA_FIXED_ECDH:
                return KEY_TYPE_EC_EC; // RFC ecdsa_fixed_ecdh
            default:
                return null;
        }
    }
}