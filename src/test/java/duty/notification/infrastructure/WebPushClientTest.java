package duty.notification.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Push is off without keys, and a real P-256 key pair in the format the runbook generates is accepted. */
class WebPushClientTest {

    @Test
    @DisplayName("without VAPID keys push is off")
    void withoutKeysPushIsOff() throws Exception {
        assertThat(new WebPushClient("", "", "").enabled()).isFalse();
    }

    @Test
    @DisplayName("a base64url P-256 key pair turns push on")
    void aKeyPairTurnsPushOn() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair pair = generator.generateKeyPair();
        ECPublicKey publicKey = (ECPublicKey) pair.getPublic();

        // The uncompressed point 0x04 || X || Y, and the private scalar, as `web-push` prints them.
        byte[] point = new byte[65];
        point[0] = 4;
        System.arraycopy(fixed(publicKey.getW().getAffineX()), 0, point, 1, 32);
        System.arraycopy(fixed(publicKey.getW().getAffineY()), 0, point, 33, 32);
        String encodedPublic = Base64.getUrlEncoder().withoutPadding().encodeToString(point);
        String encodedPrivate =
                Base64.getUrlEncoder().withoutPadding().encodeToString(fixed(((ECPrivateKey) pair.getPrivate()).getS()));

        WebPushClient client = new WebPushClient(encodedPublic, encodedPrivate, "mailto:manager@localhost");

        assertThat(client.enabled()).isTrue();
        assertThat(client.publicKey()).isEqualTo(encodedPublic);
    }

    /** A coordinate as exactly 32 bytes, as the encoding requires. */
    private static byte[] fixed(BigInteger value) {
        byte[] raw = value.toByteArray();
        byte[] out = new byte[32];
        int length = Math.min(raw.length, 32);
        System.arraycopy(raw, raw.length - length, out, 32 - length, length);
        return out;
    }
}
