#!/usr/bin/env python3
"""Gera PDFs pequenos, reproduzíveis e sem dados reais para a demonstração."""

from pathlib import Path
import json
import textwrap


ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "fixtures" / "demo"


def pdf_bytes(pages: list[list[str]]) -> bytes:
    objects: list[bytes] = []
    page_ids = [4 + page_index * 2 for page_index in range(len(pages))]
    objects.append(b"<< /Type /Catalog /Pages 2 0 R >>")
    kids = " ".join(f"{page_id} 0 R" for page_id in page_ids)
    objects.append(f"<< /Type /Pages /Kids [{kids}] /Count {len(pages)} >>".encode())
    objects.append(b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>")

    for index, lines in enumerate(pages):
        page_id = page_ids[index]
        content_id = page_id + 1
        content = bytearray(b"BT\n/F1 12 Tf\n72 740 Td\n16 TL\n")
        for line in lines:
            for visual_line in textwrap.wrap(line, width=65, break_long_words=False,
                                             break_on_hyphens=False) or [""]:
                encoded = visual_line.encode("cp1252")
                encoded = encoded.replace(b"\\", b"\\\\").replace(b"(", b"\\(").replace(b")", b"\\)")
                content.extend(b"(" + encoded + b") Tj\nT*\n")
        content.extend(b"ET\n")
        page = (
            f"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] "
            f"/Resources << /Font << /F1 3 0 R >> >> /Contents {content_id} 0 R >>"
        ).encode()
        stream = b"<< /Length " + str(len(content)).encode() + b" >>\nstream\n" + bytes(content) + b"endstream"
        while len(objects) < page_id - 1:
            objects.append(b"")
        objects.extend([page, stream])

    body = bytearray(b"%PDF-1.4\n%\xe2\xe3\xcf\xd3\n")
    offsets = [0]
    for object_id, obj in enumerate(objects, start=1):
        offsets.append(len(body))
        body.extend(f"{object_id} 0 obj\n".encode() + obj + b"\nendobj\n")
    xref_offset = len(body)
    body.extend(f"xref\n0 {len(offsets)}\n".encode())
    body.extend(b"0000000000 65535 f \n")
    for offset in offsets[1:]:
        body.extend(f"{offset:010d} 00000 n \n".encode())
    body.extend(f"trailer\n<< /Size {len(offsets)} /Root 1 0 R >>\nstartxref\n{xref_offset}\n%%EOF\n".encode())
    return bytes(body)


def main() -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    fixtures = [
        {
            "file": "contrato-001.pdf",
            "title": "Contrato 001 — Fornecimento",
            "tenant": "energia-demo",
            "pages": [
                [
                    "Contrato 001 — Fornecimento de energia",
                    "Cláusula 4.2 — Pagamento da fatura",
                    "O prazo para pagamento da fatura será de 30 dias corridos após o recebimento da fatura.",
                ],
                [
                    "Contrato 001 — Condições de duração",
                    "Cláusula 12.1 — Resilição unilateral sem motivação",
                    "Qualquer das partes poderá resilir unilateralmente sem justa causa, mediante notificação prévia com prazo mínimo de 90 dias contados do recebimento.",
                ],
            ],
        },
        {
            "file": "aditivo-001.pdf",
            "title": "Aditivo 01 — Contrato 001",
            "tenant": "energia-demo",
            "pages": [
                [
                    "Aditivo 01 ao Contrato 001",
                    "Cláusula 1.1 — Alteração da cláusula de resilição",
                    "Este aditivo altera a Cláusula 12.1 do Contrato 001. O prazo de notificação passa de 90 para 60 dias corridos.",
                    "Vigência a partir de 01/10/2026.",
                ],
            ],
        },
        {
            "file": "contrato-001-outro-tenant.pdf",
            "title": "Contrato 001 — Fornecimento (tenant B)",
            "tenant": "energia-outro",
            "pages": [
                [
                    "Contrato 001 — Fornecimento de energia — tenant B",
                    "Cláusula 4.2 — Pagamento da fatura",
                    "O prazo para pagamento da fatura será de 7 dias úteis após o recebimento da fatura.",
                    "Cláusula 12.1 — Resilição unilateral",
                    "O prazo de notificação é de 15 dias corridos.",
                ],
            ],
        },
    ]
    for fixture in fixtures:
        (OUT / fixture["file"]).write_bytes(pdf_bytes(fixture["pages"]))
    (OUT / "manifest.json").write_text(json.dumps(fixtures, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"PDFs sintéticos gerados em {OUT.relative_to(ROOT)} ({len(fixtures)} documentos).")


if __name__ == "__main__":
    main()
