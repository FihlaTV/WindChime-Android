package pro.dbro.airshare.crypto;

import androidx.annotation.NonNull;

import org.abstractj.kalium.NaCl;
import org.abstractj.kalium.Sodium;

/**
 * Wrapper around libsodium functions.
 *
 * Created by davidbrodsky on 10/13/14.
 */
@SuppressWarnings({"WeakerAccess", "unused"})
public class SodiumShaker {

    public static final int crypto_sign_PUBLICKEYBYTES = 32;
    private static final int crypto_sign_SECRETKEYBYTES = 64;
    public static final int crypto_sign_BYTES = 64;

    static {
        // Load native libraries
        //noinspection ResultOfMethodCallIgnored
        NaCl.sodium();
        // Initialize libsodium
        if (Sodium.sodium_init() == -1) {
            throw new IllegalStateException("sodiun_init failed!");
        }
    }

    public static KeyPair generateKeyPair() {
        byte[] pk = new byte[crypto_sign_PUBLICKEYBYTES];
        byte[] sk = new byte[crypto_sign_SECRETKEYBYTES];

        Sodium.crypto_sign_ed25519_keypair(pk, sk);
        return new KeyPair(pk, sk);
    }

    public static byte[] generateSignatureForMessage(@NonNull byte[] secret_key, @NonNull byte[] message, int message_len) {
        if (secret_key.length != crypto_sign_SECRETKEYBYTES) throw new IllegalArgumentException("secret_key is incorrect length");
        byte[] signature = new byte[crypto_sign_BYTES];
        int[] signature_len = new int[0];

        Sodium.crypto_sign_ed25519_detached(signature, signature_len, message, message_len, secret_key);

        return signature;
    }

    /**
     * Very that signature and public_key verify message
     *
     * @param public_key the public key corresponding to signature
     * @param signature the signature of message decipherable with public_key
     * @param message the data with signature
     */
    public static boolean verifySignature(@NonNull byte[] public_key, @NonNull byte[] signature, @NonNull byte[] message) {
        // Verify signature

        /* Incorrect signature! */
        return Sodium.crypto_sign_ed25519_verify_detached(signature, message, message.length, public_key) == 0;
    }
}
