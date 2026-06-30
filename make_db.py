import pexpect

child = pexpect.spawn('keepassxc-cli db-create test-data/real-db.kdbx')
child.expect('Enter password to encrypt database')
child.sendline('testpassword123')
child.expect('Repeat password')
child.sendline('testpassword123')
child.expect(pexpect.EOF)
print("Done creating DB!")
