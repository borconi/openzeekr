#!/usr/bin/env python3
"""
Decrypt an OpenZeekr encrypted log blob (from Settings -> "Copy (encrypted)").

The app encrypts a copied log with the OpenZeekr RSA-4096 PUBLIC key; only the holder of
the matching PRIVATE key (the developers) can read it. This tool does that decryption.

Blob layout (base64, NO_WRAP), matching core/.../util/LogCrypto.kt:
    magic "OZ" (2) | version (1) | wrappedKeyLen (2, big-endian) | wrappedKey | IV (12) | GCM(ct+tag)
  - wrappedKey : AES-256 key, RSA-OAEP(SHA-256, MGF1-SHA-256) wrapped
  - GCM(ct+tag): AES-256-GCM ciphertext with the 16-byte tag appended (JCA layout)

Usage:
    python3 decrypt_log.py --key tools/log-decrypt/private_key.pem --in blob.txt
    pbpaste | python3 decrypt_log.py --key tools/log-decrypt/private_key.pem        # blob on stdin
    python3 decrypt_log.py --key private_key.pem "<base64 blob string>"

Requires the 'cryptography' package (pip install cryptography). RSA-OAEP + AES-GCM are not
available from the Python stdlib alone.
"""

import argparse
import base64
import sys

try:
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import padding
    from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM
except ImportError:
    sys.exit("This tool needs the 'cryptography' package:  pip install cryptography")

MAGIC = b"OZ"
VERSION = 1
IV_LEN = 12


def _unwrap_aes_key(wrapped: bytes, private_key_pem: bytes) -> bytes:
    private_key = serialization.load_pem_private_key(private_key_pem, password=None)
    return private_key.decrypt(
        wrapped,
        padding.OAEP(
            mgf=padding.MGF1(algorithm=hashes.SHA256()),
            algorithm=hashes.SHA256(),
            label=None,
        ),
    )


def decrypt(
    blob_b64: str, private_key_pem: bytes, allow_truncated: bool = False
) -> str:
    raw = base64.b64decode(blob_b64.strip())
    if raw[:2] != MAGIC:
        raise ValueError("bad magic - not an OpenZeekr log blob")
    version = raw[2]
    if version != VERSION:
        raise ValueError(
            f"unsupported blob version {version} (this tool handles v{VERSION})"
        )
    wrapped_len = (raw[3] << 8) | raw[4]
    off = 5
    wrapped = raw[off : off + wrapped_len]
    off += wrapped_len
    iv = raw[off : off + IV_LEN]
    off += IV_LEN
    ct = raw[off:]

    aes_key = _unwrap_aes_key(wrapped, private_key_pem)
    try:
        plaintext = AESGCM(aes_key).decrypt(iv, ct, None)
        return plaintext.decode("utf-8", errors="replace")
    except Exception:
        if not allow_truncated:
            raise
        # The blob was truncated (a clipboard/field char cap is the usual cause), so the GCM tag is
        # missing/wrong and authenticated decryption fails. GCM's confidentiality layer is just
        # AES-CTR, so we can still recover the ciphertext we DO have - unauthenticated - by running
        # CTR from the GCM start counter (for a 96-bit IV, J0 = IV||0x00000001, and the ciphertext
        # keystream starts at J0+1 = IV||0x00000002). Only the lost tail is missing; the rest is real.
        init_ctr = iv + b"\x00\x00\x00\x02"
        dec = Cipher(algorithms.AES(aes_key), modes.CTR(init_ctr)).decryptor()
        pt = dec.update(ct) + dec.finalize()
        sys.stderr.write(
            "WARNING: blob is truncated - GCM tag failed; recovered "
            f"{len(pt)} bytes UNAUTHENTICATED via CTR. The tail is lost and the last line may be "
            "partial. Get the full log as a FILE (not a paste) to avoid the char cap.\n"
        )
        return pt.decode("utf-8", errors="replace")


def main() -> None:
    ap = argparse.ArgumentParser(description="Decrypt an OpenZeekr encrypted log blob.")
    ap.add_argument(
        "--key",
        required=True,
        help="path to the RSA private key PEM (dev-held, offline)",
    )
    ap.add_argument(
        "--in", dest="infile", help="file holding the base64 blob (default: stdin)"
    )
    ap.add_argument(
        "--allow-truncated",
        action="store_true",
        help="if the GCM tag fails (truncated blob), recover the ciphertext we have "
        "UNAUTHENTICATED via AES-CTR (partial log; tail lost)",
    )
    ap.add_argument(
        "blob",
        nargs="?",
        help="the base64 blob as an argument (alternative to --in/stdin)",
    )
    args = ap.parse_args()

    if args.blob:
        blob = args.blob
    elif args.infile:
        with open(args.infile, "r", encoding="utf-8") as f:
            blob = f.read()
    else:
        blob = sys.stdin.read()

    with open(args.key, "rb") as f:
        private_key_pem = f.read()

    sys.stdout.write(
        decrypt(blob, private_key_pem, allow_truncated=args.allow_truncated)
    )


if __name__ == "__main__":
    main()
