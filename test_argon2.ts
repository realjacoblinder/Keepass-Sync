import kdbxweb from 'kdbxweb';
import argon2 from 'argon2';

const run = async () => {
    kdbxweb.CryptoEngine.setArgon2Impl(async (password, salt, memory, iterations, length, parallelism, type, version) => {
        console.log("kdbxweb passes memory:", memory);
        return new Uint8Array(32).buffer;
    });

    const credentials = new kdbxweb.Credentials(kdbxweb.ProtectedValue.fromString('test'));
    const kdf = kdbxweb.KdfParameters.createDefault();
    // Default argon2 memory might be 1MB
    console.log("Default KDF memory:", kdf.get('S'));
    
    // Simulate KeePassXC setting 64MB: Wait, kdf.set('S', kdbxweb.Int64?) 
    
    const db = kdbxweb.Kdbx.create(credentials, 'Test');
    await db.save(); 
}
run();
