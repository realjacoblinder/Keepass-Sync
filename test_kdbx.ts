import kdbxweb from 'kdbxweb';
import crypto from 'crypto';
import argon2 from 'argon2';

kdbxweb.CryptoEngine.setArgon2Impl(async (password, salt, memory, iterations, length, parallelism, type, version) => {
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
    return new Uint8Array(hash).buffer;
});

console.log("Global crypto present:", typeof globalThis.crypto !== 'undefined');

const run = async () => {
    try {
        const credentials = new kdbxweb.Credentials(kdbxweb.ProtectedValue.fromString('testpassword123'));
        console.log("Credentials created");
        
        const db = kdbxweb.Kdbx.create(credentials, 'TestDB');
        console.log("DB Created");
        
        const data = await db.save();
        console.log("DB Saved, size:", data.byteLength);

        // Try to load it back
        const loaded = await kdbxweb.Kdbx.load(data, credentials);
        console.log("DB Loaded successfully");
    } catch (e) {
        console.error("Error during kdbxweb execution:", e);
    }
}
run();
