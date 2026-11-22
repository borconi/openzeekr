package io.aawireless.wirelessaamanipulator.helpers.ssl;



import android.content.Context;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.security.KeyManagementException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;



/**
 * Created by Emil on 25/03/2018.
 */

public class sslEngineBuilder {

    public static SSLEngine Builder(Context context, String key) throws KeyStoreException, CertificateException, NoSuchAlgorithmException, IOException, UnrecoverableKeyException, KeyManagementException {




        InputStream openRawResource = context.getResources().openRawResource(context.getResources().getIdentifier(key, "raw", context.getPackageName()));
        KeyStore instance = KeyStore.getInstance("PKCS12");
        instance.load(openRawResource, "aa".toCharArray());
        return Builder(instance, "aa".toCharArray());
    }

    /**
     * Build the phone-side SSLEngine from an already-loaded KeyStore (e.g. the AA
     * cert extracted at runtime by {@link CertProvider}). Key password defaults to "aa".
     */
    public static SSLEngine Builder(Context context, KeyStore keyStore) throws KeyStoreException, NoSuchAlgorithmException, UnrecoverableKeyException, KeyManagementException {
        return Builder(keyStore, "aa".toCharArray());
    }

    /**
     * TLS context with the phone-side AA identity, for stream sockets (the WPP-over-TCP
     * control channel is plain TLS on the socket, not wrapped in AA frames).
     */
    public static SSLContext context(KeyStore keyStore) throws KeyStoreException, NoSuchAlgorithmException, UnrecoverableKeyException, KeyManagementException {
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keyStore, "aa".toCharArray());
        SSLContext ctx = SSLContext.getInstance("TLSv1.2");
        ctx.init(kmf.getKeyManagers(), new TrustManager[]{new adb()}, new SecureRandom());
        return ctx;
    }

    public static SSLEngine Builder(KeyStore instance, char[] keyPassword) throws KeyStoreException, NoSuchAlgorithmException, UnrecoverableKeyException, KeyManagementException {
        KeyManagerFactory instance2 = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        instance2.init(instance, keyPassword);
        Log.d("SSL", "Before creating ssl context");

        SSLContext instance3 = SSLContext.getInstance("TLSv1.2");
        Log.d("SSL", "Alfter creating ssl context");
        instance3.init(instance2.getKeyManagers(), new TrustManager[]{new adb()}, new SecureRandom());
        Log.d("SSL", "After init");
        SSLEngine createSSLEngine = instance3.createSSLEngine();
        Log.d("SSL", "After create engine");
        createSSLEngine.setEnabledProtocols(new String[]{"TLSv1.2", "TLSv1.1"});
        Log.d("SSL", "After enable protocol");
        createSSLEngine.setNeedClientAuth(true);
        return createSSLEngine;

    }
}
