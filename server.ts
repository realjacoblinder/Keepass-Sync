import express from 'express';
import { createServer as createViteServer } from 'vite';
import multer from 'multer';
import fs from 'fs';
import path from 'path';
import kdbxweb from 'kdbxweb';

// Ensure data directory exists
const DATA_DIR = path.join(process.cwd(), 'data');
if (!fs.existsSync(DATA_DIR)) {
  fs.mkdirSync(DATA_DIR, { recursive: true });
}

const sanitizeName = (name: string) => name.replace(/[^a-zA-Z0-9_-]/g, '');

async function startServer() {
  const app = express();
  const PORT = 3000;

  // Use memory storage for multer
  const upload = multer({ storage: multer.memoryStorage() });

  app.use(express.json());

  // API routes FIRST
  app.post('/api/sync', upload.single('dbFile'), async (req, res) => {
    try {
      const password = req.body.password;
      const file = req.file;
      const masterNameRaw = req.body.masterName || 'master';
      const masterName = sanitizeName(masterNameRaw);

      if (!file || !password) {
        res.status(400).json({ error: 'File and password are required' });
        return;
      }
      if (!masterName) {
        res.status(400).json({ error: 'Invalid master name' });
        return;
      }

      const masterPath = path.join(DATA_DIR, `${masterName}.kdbx`);

      // Convert buffer to ArrayBuffer
      const uploadedBuffer = new Uint8Array(file.buffer).buffer;
      const credentials = new kdbxweb.Credentials(kdbxweb.ProtectedValue.fromString(password));

      // Load uploaded DB
      let uploadedDb;
      try {
        uploadedDb = await kdbxweb.Kdbx.load(uploadedBuffer, credentials);
      } catch (err) {
        res.status(401).json({ error: 'Failed to decrypt uploaded database. Incorrect password?' });
        return;
      }

      // Check if master DB exists
      if (fs.existsSync(masterPath)) {
        const masterBuffer = new Uint8Array(fs.readFileSync(masterPath)).buffer;
        let masterDb;
        try {
          masterDb = await kdbxweb.Kdbx.load(masterBuffer, credentials);
        } catch (err) {
          res.status(500).json({ error: 'Failed to decrypt master database. Password mismatch?' });
          return;
        }

        // Merge uploaded into master
        masterDb.merge(uploadedDb);

        // Save merged DB
        const savedBuffer = await masterDb.save();
        fs.writeFileSync(masterPath, Buffer.from(savedBuffer));
        
        res.json({ message: `Database merged successfully` });
      } else {
        // No master DB yet, save uploaded as master
        fs.writeFileSync(masterPath, file.buffer);
        res.json({ message: `Master database created successfully` });
      }
    } catch (error) {
      // Omit error details to prevent leaking sensitive data in logs
      console.error('[Secure] Sync error occurred. Details omitted to protect sensitive data.');
      res.status(500).json({ error: 'Internal server error during sync' });
    }
  });

  app.post('/api/download', (req, res) => {
    const masterName = sanitizeName(req.body.masterName || 'master');
    if (!masterName) return res.status(400).json({ error: 'Invalid master name' });
    
    const masterPath = path.join(DATA_DIR, `${masterName}.kdbx`);
    if (!fs.existsSync(masterPath)) {
      res.status(404).json({ error: 'Master database not found' });
      return;
    }
    res.download(masterPath, `${masterName}.kdbx`);
  });

  app.post('/api/status', (req, res) => {
    const masterName = sanitizeName(req.body.masterName || 'master');
    if (!masterName) return res.status(400).json({ error: 'Invalid master name' });

    const masterPath = path.join(DATA_DIR, `${masterName}.kdbx`);
    if (fs.existsSync(masterPath)) {
      const stats = fs.statSync(masterPath);
      res.json({ lastUpdated: stats.mtime });
    } else {
      res.json({ lastUpdated: null });
    }
  });

  // Vite middleware for development
  if (process.env.NODE_ENV !== 'production') {
    const vite = await createViteServer({
      server: { middlewareMode: true },
      appType: 'spa',
    });
    app.use(vite.middlewares);
  } else {
    const distPath = path.join(process.cwd(), 'dist');
    app.use(express.static(distPath));
    app.get('*', (req, res) => {
      res.sendFile(path.join(distPath, 'index.html'));
    });
  }

  app.listen(PORT, '0.0.0.0', () => {
    console.log(`Server running on http://localhost:${PORT}`);
  });
}

startServer();
