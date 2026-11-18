import com.android.apksig.ApkSigner;
import java.io.File;
import java.io.FileInputStream;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.Collections;

/** Signiert ein APK mit dem Debug-Schluessel (gleiches Zertifikat wie alle bisherigen ZFix-Launcher-Versionen). */
public class Sign {
    public static void main(String[] a) throws Exception {
        KeyStore ks = KeyStore.getInstance("JKS");
        try (FileInputStream in = new FileInputStream(a[0])) { ks.load(in, "android".toCharArray()); }
        String alias = ks.aliases().nextElement();
        PrivateKey key = (PrivateKey) ks.getKey(alias, "android".toCharArray());
        X509Certificate cert = (X509Certificate) ks.getCertificate(alias);
        ApkSigner.SignerConfig sc = new ApkSigner.SignerConfig.Builder("CERT", key, Collections.singletonList(cert)).build();
        new ApkSigner.Builder(Collections.singletonList(sc)).setInputApk(new File(a[1])).setOutputApk(new File(a[2]))
                .setV1SigningEnabled(false).setV2SigningEnabled(true).setMinSdkVersion(26).build().sign();
    }
}
