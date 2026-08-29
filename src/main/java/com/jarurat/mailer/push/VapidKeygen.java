package com.jarurat.mailer.push;

import java.security.KeyPair;
import java.security.interfaces.ECPublicKey;
import java.util.Base64;

/**
 * Prints one VAPID key pair, in the three encodings the deployment needs.
 *
 * Run once, ever:
 *
 *   java -cp target/classes com.jarurat.mailer.push.VapidKeygen
 *
 * It is a main and not an endpoint on purpose. A running server that will mint a
 * signing key on request is a running server that will mint one for somebody who asks
 * politely, and there is no version of this that needs to happen more than once in the
 * life of the deployment. Read VapidKeys before running it a second time: a new pair
 * silently invalidates every subscription the fleet already holds, and nothing
 * anywhere reports that it has happened.
 *
 * The PKCS#8 PEM is printed because Stalwart's jmap.webPushKey wants that exact
 * format, and Stalwart has to sign with the same key this application does. One
 * browser subscription carries one application server key and the push service refuses
 * anything signed by a different one, so if the two ever diverge, whichever half did
 * not sign the subscription is answered with 403 for the rest of that subscription's
 * life and nobody is told.
 */
public final class VapidKeygen {

    private VapidKeygen() {}

    public static void main(String[] args) {
        KeyPair pair = VapidKeys.generate();
        byte[] point = VapidKeys.bytesOf((ECPublicKey) pair.getPublic());
        byte[] pkcs8 = pair.getPrivate().getEncoded();

        System.out.println("# Put these two in the environment file, beside OTP_PEPPER.");
        System.out.println("# Back them up with the database. Losing them is a migration, not a restart.");
        System.out.println("PUSH_VAPID_PUBLIC_KEY=" + VapidKeys.base64Url(point));
        System.out.println("PUSH_VAPID_PRIVATE_KEY=" + VapidKeys.base64Url(pkcs8));
        System.out.println("PUSH_VAPID_SUBJECT=mailto:postmaster@jarurat.care");
        System.out.println();
        System.out.println("# The same private key, for Stalwart's jmap.webPushKey setting. Both");
        System.out.println("# have to sign with this one pair or one of them is refused by the");
        System.out.println("# push service with no error anywhere on this side.");
        System.out.println(pem(pkcs8));
        System.out.println("# And set jmap.webPushContact to the same mailto as PUSH_VAPID_SUBJECT.");
        System.out.println("# Apple answers 403 BadJwtToken when it is left to default to the");
        System.out.println("# machine's local hostname, and it fails only on iPhones.");

        // The raw scalar is not printed. It is accepted on input because other tooling
        // emits it, but there is no reason to put a second copy of a private key on a
        // terminal that is very likely being screen shared.
    }

    private static String pem(byte[] pkcs8) {
        String body = Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(pkcs8);
        return "-----BEGIN PRIVATE KEY-----\n" + body + "\n-----END PRIVATE KEY-----";
    }
}
