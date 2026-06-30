import kdbxweb from 'kdbxweb';
import argon2 from 'argon2';

kdbxweb.CryptoEngine.setArgon2Impl(async (password, salt, memory, iterations, length, parallelism, type, version) => {
    console.log("Argon2Impl args:", {
        memory, iterations, length, parallelism, type, version,
        passwordType: password.constructor.name,
        saltType: salt.constructor.name
    });
    
    // Test if we can hash it
    try {
        const hash = await argon2.hash(Buffer.from(password), {
            timeCost: iterations,
            memoryCost: memory,
            parallelism: parallelism,
            type: type,
            version: version,
            salt: Buffer.from(salt),
            raw: true,
            hashLength: length
        });
        console.log("Argon hash succeeded!");
        return new Uint8Array(hash).buffer;
    } catch (e) {
        console.error("Argon hash failed:", e);
        throw e;
    }
});

const run = async () => {
    const credentials = new kdbxweb.Credentials(kdbxweb.ProtectedValue.fromString('testpassword123'));
    const db = kdbxweb.Kdbx.create(credentials, 'TestDB');
    await db.save();
}
run();
