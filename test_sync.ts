import kdbxweb from 'kdbxweb';
import argon2 from 'argon2';
import crypto from 'crypto';

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

const run = async () => {
    try {
        const credentials = new kdbxweb.Credentials(kdbxweb.ProtectedValue.fromString('testpassword123'));
        const db = kdbxweb.Kdbx.create(credentials, 'TestDB');
        const data = await db.save(); // KDBX4 defaults to Argon2
        const buffer = Buffer.from(data);

        const formData = new FormData();
        const blob = new Blob([buffer], { type: 'application/octet-stream' });
        formData.append('dbFile', blob, 'test-db.kdbx');
        formData.append('password', 'testpassword123');
        formData.append('masterName', 'master-argon');

        console.log('Sending sync request...');
        const res = await fetch('http://localhost:3000/api/sync', {
            method: 'POST',
            body: formData
        });

        const text = await res.text();
        console.log('Response Status:', res.status);
        console.log('Response Body:', text);
    } catch (e) {
        console.error(e);
    }
}
run();
