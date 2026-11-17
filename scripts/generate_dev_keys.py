#!/usr/bin/env python3
"""Cria uma chave RS256 descartável e tokens locais para dois tenants sintéticos."""

from pathlib import Path
import base64
import json
import subprocess
import time


ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / ".local"


def b64url(value: bytes) -> str:
    return base64.urlsafe_b64encode(value).decode("ascii").rstrip("=")


def main() -> None:
    OUT.mkdir(mode=0o700, parents=True, exist_ok=True)
    private_key = OUT / "jwt-private.pem"
    public_key = OUT / "jwt-public.pem"
    subprocess.run(["openssl", "genpkey", "-algorithm", "RSA", "-pkeyopt", "rsa_keygen_bits:2048", "-out", str(private_key)], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    subprocess.run(["openssl", "pkey", "-in", str(private_key), "-pubout", "-out", str(public_key)], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    private_key.chmod(0o600)
    public_key.chmod(0o644)

    now = int(time.time())
    header = {"alg": "RS256", "typ": "JWT"}
    for tenant in ("energia-demo", "energia-outro"):
        claims = {
            "sub": f"analista-{tenant}",
            "tenant_id": tenant,
            "iss": "juris-rag-local",
            "aud": ["juris-rag-api"],
            "iat": now,
            "nbf": now - 5,
            "exp": now + 86400,
        }
        encoded_header = b64url(json.dumps(header, separators=(",", ":")).encode())
        encoded_claims = b64url(json.dumps(claims, separators=(",", ":")).encode())
        signing_input = f"{encoded_header}.{encoded_claims}".encode()
        signature = subprocess.run(
            ["openssl", "dgst", "-sha256", "-sign", str(private_key)],
            input=signing_input, check=True, capture_output=True,
        ).stdout
        (OUT / f"token-{tenant}.txt").write_text(
            f"{signing_input.decode()}.{b64url(signature)}\n", encoding="ascii"
        )
        (OUT / f"token-{tenant}.txt").chmod(0o600)
    print("Chave JWT e tokens locais criados em .local/; esses arquivos são ignorados pelo Git.")


if __name__ == "__main__":
    main()
