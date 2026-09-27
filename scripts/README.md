# scripts

## decrypt_log.py - read an encrypted OpenZeekr log

When a tester uses **Settings -> App -> Copy (encrypted)**, the copied text is NOT the
plaintext log. It is a base64 blob that only the OpenZeekr developers can decrypt, so a log
pasted into a GitHub issue / chat leaks no tokens, VIN, or DK/BLE key material.

The app ships only the RSA-4096 **public** key (embedded in `core/.../util/LogCrypto.kt`). The
matching **private** key is what decrypts a blob. It is **not** in the app and **not** in git.

### One-time setup

Install the dependency:

```bash
pip install cryptography
```

Obtain the private key. It lives at `tools/log-decrypt/private_key.pem`, which is **gitignored** -
each developer must keep their own copy **offline and secure** (password manager / encrypted
vault). If the key is lost, no previously copied log can ever be decrypted; if it leaks, anyone
can read every copied log, so treat it like a signing key.

### Decrypt a blob

```bash
# from a file holding the pasted blob
python3 scripts/decrypt_log.py --key tools/log-decrypt/private_key.pem --in blob.txt

# from the clipboard (macOS)
pbpaste | python3 scripts/decrypt_log.py --key tools/log-decrypt/private_key.pem

# as an argument
python3 scripts/decrypt_log.py --key tools/log-decrypt/private_key.pem "OZ...base64..."
```

The decrypted plaintext log is written to stdout.

### Rotating the keypair

```bash
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:4096 -out tools/log-decrypt/private_key.pem
openssl rsa -in tools/log-decrypt/private_key.pem -pubout -out tools/log-decrypt/public_key.pem
openssl rsa -in tools/log-decrypt/private_key.pem -pubout -outform DER | base64 -w0
```

Paste the last command's output into `PUBLIC_KEY_B64` in
`core/src/main/java/com/openzeekr/app/util/LogCrypto.kt`. Blobs made with the old public key can
still be read with the old private key; keep old private keys until no old blobs remain.
