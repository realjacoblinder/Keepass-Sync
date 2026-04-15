import React, { useState, useEffect } from 'react';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Button } from '@/components/ui/button';
import { Upload, Download, RefreshCw, KeyRound, CheckCircle2, AlertCircle, Clock, Database } from 'lucide-react';

export default function App() {
  const [file, setFile] = useState<File | null>(null);
  const [password, setPassword] = useState('');
  const [masterName, setMasterName] = useState('master');
  const [isSyncing, setIsSyncing] = useState(false);
  const [lastUpdated, setLastUpdated] = useState<Date | null>(null);
  const [status, setStatus] = useState<{ type: 'success' | 'error' | null; message: string }>({
    type: null,
    message: '',
  });

  const fetchStatus = async (name: string) => {
    if (!name) {
      setLastUpdated(null);
      return;
    }
    try {
      const response = await fetch('/api/status', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ masterName: name })
      });
      const data = await response.json();
      if (data.lastUpdated) {
        setLastUpdated(new Date(data.lastUpdated));
      } else {
        setLastUpdated(null);
      }
    } catch (error) {
      // Omit error details for security
      setLastUpdated(null);
    }
  };

  useEffect(() => {
    const timeoutId = setTimeout(() => {
      if (masterName && /^[a-zA-Z0-9-]+$/.test(masterName)) {
        fetchStatus(masterName);
      } else {
        setLastUpdated(null);
      }
    }, 300);
    return () => clearTimeout(timeoutId);
  }, [masterName]);

  const handleFileChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    if (e.target.files && e.target.files.length > 0) {
      setFile(e.target.files[0]);
    }
  };

  const handleSync = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!file || !password || !masterName) {
      setStatus({ type: 'error', message: 'Please provide a file, password, and master name.' });
      return;
    }
    if (!/^[a-zA-Z0-9-]+$/.test(masterName)) {
      setStatus({ type: 'error', message: 'Master name can only contain alphanumeric characters and hyphens.' });
      return;
    }

    setIsSyncing(true);
    setStatus({ type: null, message: '' });

    const formData = new FormData();
    formData.append('dbFile', file);
    formData.append('password', password);
    formData.append('masterName', masterName);

    try {
      const response = await fetch('/api/sync', {
        method: 'POST',
        body: formData,
      });

      const data = await response.json();

      if (!response.ok) {
        throw new Error(data.error || 'Failed to sync database');
      }

      setStatus({ type: 'success', message: data.message || 'Successfully synchronized database!' });
      setFile(null);
      setPassword('');
      // Reset file input
      const fileInput = document.getElementById('file-upload') as HTMLInputElement;
      if (fileInput) fileInput.value = '';
      
      // Update the last updated timestamp
      await fetchStatus(masterName);
    } catch (error) {
      setStatus({ type: 'error', message: error instanceof Error ? error.message : 'An unknown error occurred' });
    } finally {
      setIsSyncing(false);
    }
  };

  const handleDownload = async () => {
    if (!masterName) return;
    if (!/^[a-zA-Z0-9-]+$/.test(masterName)) {
      setStatus({ type: 'error', message: 'Master name can only contain alphanumeric characters and hyphens.' });
      return;
    }
    try {
      const response = await fetch('/api/download', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ masterName })
      });

      if (!response.ok) {
        throw new Error('Download failed');
      }

      const blob = await response.blob();
      const url = window.URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = `${masterName}.kdbx`;
      document.body.appendChild(a);
      a.click();
      window.URL.revokeObjectURL(url);
      document.body.removeChild(a);
    } catch (error) {
      setStatus({ type: 'error', message: 'Failed to download database.' });
    }
  };

  return (
    <div className="min-h-screen bg-zinc-50 flex items-center justify-center p-4">
      <div className="w-full max-w-md space-y-6">
        <div className="text-center space-y-2">
          <h1 className="text-3xl font-bold tracking-tight text-zinc-900">KeePass Sync</h1>
          <p className="text-zinc-500">Synchronize your .kdbx files securely</p>
        </div>

        <Card>
          <CardHeader>
            <CardTitle>Upload & Sync</CardTitle>
            <CardDescription>
              Upload your local database to merge changes with the master copy.
            </CardDescription>
          </CardHeader>
          <CardContent>
            <form onSubmit={handleSync} className="space-y-4">
              <div className="space-y-2">
                <Label htmlFor="master-name">Master Database Name</Label>
                <div className="relative">
                  <Database className="absolute left-3 top-2.5 h-4 w-4 text-zinc-500" />
                  <Input
                    id="master-name"
                    type="text"
                    placeholder="e.g., personal, work"
                    value={masterName}
                    onChange={(e) => {
                      setMasterName(e.target.value);
                      if (status.type === 'error') setStatus({ type: null, message: '' });
                    }}
                    disabled={isSyncing}
                    className={`pl-9 ${masterName && !/^[a-zA-Z0-9-]+$/.test(masterName) ? 'border-red-500 focus-visible:ring-red-500' : ''}`}
                  />
                </div>
                {masterName && !/^[a-zA-Z0-9-]+$/.test(masterName) && (
                  <p className="text-xs text-red-500">Only alphanumeric characters and hyphens are allowed.</p>
                )}
              </div>

              <div className="space-y-2">
                <Label htmlFor="file-upload">Database File (.kdbx)</Label>
                <div className="flex items-center gap-2">
                  <Input
                    id="file-upload"
                    type="file"
                    accept=".kdbx"
                    onChange={handleFileChange}
                    disabled={isSyncing}
                    className="cursor-pointer"
                  />
                </div>
              </div>

              <div className="space-y-2">
                <Label htmlFor="password">Master Password</Label>
                <div className="relative">
                  <KeyRound className="absolute left-3 top-2.5 h-4 w-4 text-zinc-500" />
                  <Input
                    id="password"
                    type="password"
                    placeholder="Enter master password"
                    value={password}
                    onChange={(e) => setPassword(e.target.value)}
                    disabled={isSyncing}
                    className="pl-9"
                  />
                </div>
              </div>

              {status.type && (
                <div
                  className={`p-3 rounded-md flex items-start gap-2 text-sm ${
                    status.type === 'success'
                      ? 'bg-green-50 text-green-700 border border-green-200'
                      : 'bg-red-50 text-red-700 border border-red-200'
                  }`}
                >
                  {status.type === 'success' ? (
                    <CheckCircle2 className="h-5 w-5 shrink-0" />
                  ) : (
                    <AlertCircle className="h-5 w-5 shrink-0" />
                  )}
                  <p>{status.message}</p>
                </div>
              )}

              <Button type="submit" className="w-full" disabled={isSyncing || !file || !password || !masterName}>
                {isSyncing ? (
                  <>
                    <RefreshCw className="mr-2 h-4 w-4 animate-spin" />
                    Syncing...
                  </>
                ) : (
                  <>
                    <Upload className="mr-2 h-4 w-4" />
                    Sync Database
                  </>
                )}
              </Button>
            </form>
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle>Download Master</CardTitle>
            <CardDescription>
              Download the latest merged master database to your device.
            </CardDescription>
          </CardHeader>
          <CardContent className="space-y-4">
            {lastUpdated ? (
              <div className="flex items-center gap-2 text-sm text-zinc-600 bg-zinc-100 p-3 rounded-md">
                <Clock className="h-4 w-4 text-zinc-500" />
                <span>
                  Last updated: <strong>{lastUpdated.toLocaleString()}</strong>
                </span>
              </div>
            ) : (
              <div className="flex items-center gap-2 text-sm text-zinc-500 bg-zinc-50 p-3 rounded-md border border-dashed border-zinc-200">
                <AlertCircle className="h-4 w-4" />
                <span>No master database found for '{masterName}'.</span>
              </div>
            )}
            <Button
              variant="outline"
              className="w-full"
              disabled={!lastUpdated}
              onClick={handleDownload}
            >
              <Download className="mr-2 h-4 w-4" />
              Download '{masterName}' DB
            </Button>
          </CardContent>
        </Card>
      </div>
    </div>
  );
}
