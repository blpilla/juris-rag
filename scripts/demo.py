#!/usr/bin/env python3
"""Executa a demonstração autenticada; falha com o nome da etapa que não passou."""

from datetime import datetime, timezone
from pathlib import Path
import json
import os
import sys
import time
import urllib.error
import urllib.request
import uuid


ROOT = Path(__file__).resolve().parents[1]
BASE_URL = os.environ.get("JURIS_RAG_URL", "http://localhost:8080").rstrip("/")
TIMEOUT_SECONDS = int(os.environ.get("DEMO_TIMEOUT_SECONDS", "180"))
UPLOAD_RETRY_SECONDS = int(os.environ.get("UPLOAD_RETRY_SECONDS", "30"))


def fail(step: str, detail: str) -> None:
    raise RuntimeError(f"[{step}] {detail}")


def read_token(tenant: str) -> str:
    path = ROOT / ".local" / f"token-{tenant}.txt"
    if not path.exists():
        fail("autenticação", f"token ausente: {path.relative_to(ROOT)}; execute scripts/dev-setup.sh")
    return path.read_text(encoding="ascii").strip()


def request(method: str, path: str, token: str | None = None, data: bytes | None = None,
            content_type: str | None = None, timeout: int = 30) -> tuple[int, object, float]:
    headers = {"Accept": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    if content_type:
        headers["Content-Type"] = content_type
    req = urllib.request.Request(BASE_URL + path, data=data, headers=headers, method=method)
    start = time.perf_counter()
    try:
        with urllib.request.urlopen(req, timeout=timeout) as response:
            body = response.read()
            elapsed = (time.perf_counter() - start) * 1000
            return response.status, json.loads(body) if body else {}, elapsed
    except urllib.error.HTTPError as error:
        body = error.read()
        try:
            parsed = json.loads(body)
        except Exception:
            parsed = body.decode("utf-8", errors="replace")
        return error.code, parsed, (time.perf_counter() - start) * 1000
    except urllib.error.URLError as error:
        return 0, {"connectionError": str(error.reason)}, (time.perf_counter() - start) * 1000


def wait_api() -> None:
    deadline = time.monotonic() + TIMEOUT_SECONDS
    latest = None
    while time.monotonic() < deadline:
        status, body, _ = request("GET", "/actuator/health", timeout=5)
        latest = (status, body)
        if status == 200 and isinstance(body, dict) and body.get("status") == "UP":
            return
        time.sleep(1)
    fail("API", f"timeout esperando /actuator/health: {latest}")


def upload(token: str, file_name: str, title: str) -> str:
    path = ROOT / "fixtures" / "demo" / file_name
    if not path.exists():
        fail("fixtures", f"PDF ausente: {path.relative_to(ROOT)}; execute scripts/generate_fixtures.py")
    boundary = "----JurisRag" + uuid.uuid4().hex
    content = path.read_bytes()
    parts = [
        f"--{boundary}\r\nContent-Disposition: form-data; name=\"title\"\r\n\r\n{title}\r\n".encode(),
        f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"{file_name}\"\r\nContent-Type: application/pdf\r\n\r\n".encode(),
        content,
        f"\r\n--{boundary}--\r\n".encode(),
    ]
    deadline = time.monotonic() + UPLOAD_RETRY_SECONDS
    latest = None
    while time.monotonic() <= deadline:
        status, body, _ = request("POST", "/v1/documents", token, b"".join(parts),
                                  f"multipart/form-data; boundary={boundary}")
        if status == 202:
            return body["documentId"]
        latest = (status, body)
        if status != 503:
            fail("upload", f"{file_name}: HTTP {status}: {body}")
        time.sleep(1)
    fail("upload", f"{file_name}: SQS indisponível após reenvios idempotentes: {latest}")


def wait_ready(token: str, document_id: str) -> dict:
    deadline = time.monotonic() + TIMEOUT_SECONDS
    latest = None
    while time.monotonic() < deadline:
        status, body, _ = request("GET", f"/v1/documents/{document_id}", token, timeout=10)
        if status != 200:
            fail("ingestão", f"status do documento {document_id}: HTTP {status}: {body}")
        latest = body
        if body.get("status") == "READY":
            return body
        if body.get("status") == "FAILED":
            fail("ingestão", f"documento {document_id} ficou FAILED")
        time.sleep(1)
    fail("ingestão", f"timeout esperando READY para {document_id}; último estado={latest}")


def query(token: str, question: str) -> tuple[dict, float]:
    status, body, elapsed = request("POST", "/v1/queries", token,
                                    json.dumps({"question": question}, ensure_ascii=False).encode(),
                                    "application/json", timeout=240)
    if status != 200:
        fail("consulta", f"HTTP {status}: {body}")
    return body, elapsed


def metric(name: str, token: str) -> object:
    status, body, _ = request("GET", f"/actuator/metrics/{name}", token)
    return body if status == 200 else {"unavailable": status}


def active_mode() -> str:
    if os.environ.get("AI_PROFILE"):
        return os.environ["AI_PROFILE"]
    env_file = ROOT / ".env"
    if env_file.exists():
        for line in env_file.read_text(encoding="utf-8").splitlines():
            if line.strip().startswith("AI_PROFILE="):
                return line.split("=", 1)[1].strip().strip("\"'")
    return "deterministic"


def main() -> int:
    token_a = read_token("energia-demo")
    token_b = read_token("energia-outro")
    wait_api()
    ids = {
        "contract": upload(token_a, "contrato-001.pdf", "Contrato 001 — Fornecimento"),
        "amendment": upload(token_a, "aditivo-001.pdf", "Aditivo 01 — Contrato 001"),
        "otherTenant": upload(token_b, "contrato-001-outro-tenant.pdf", "Contrato 001 — Fornecimento (tenant B)"),
    }
    states = {key: wait_ready(token_a if key != "otherTenant" else token_b, value) for key, value in ids.items()}

    cases = []
    direct, direct_ms = query(token_a, "Qual o prazo de pagamento da fatura do Contrato 001?")
    direct_citations = direct.get("citations", [])
    if direct.get("status") != "ANSWERED" or direct.get("attempts") != 1:
        fail("resposta direta", f"status/tentativas inesperados: {direct}")
    if not any(item.get("documentId") == ids["contract"] and item.get("page") == 1 for item in direct_citations):
        fail("resposta direta", f"citação do Contrato 001 na página 1 ausente: {direct_citations}")
    if not any("30 dias corridos" in item.get("excerpt", "") for item in direct_citations):
        fail("resposta direta", f"trecho literal com 30 dias corridos ausente: {direct_citations}")
    if "30 dias corridos" not in direct.get("answer", ""):
        fail("resposta direta", f"prazo esperado ausente: {direct.get('answer')}")
    cases.append({"name": "resposta-direta", "expectedDocuments": [ids["contract"]], "response": direct, "clientLatencyMs": round(direct_ms, 2)})

    termination, termination_ms = query(token_a, "Com quanta antecedência precisamos avisar para encerrar o Contrato 001 sem motivo?")
    termination_citations = termination.get("citations", [])
    cited_documents = {item.get("documentId") for item in termination_citations}
    if termination.get("status") != "ANSWERED" or termination.get("attempts") != 2 or not termination.get("rewritten"):
        fail("reformulação", f"status, buscas ou reformulação inesperados: {termination}")
    if not {ids["contract"], ids["amendment"]}.issubset(cited_documents):
        fail("reformulação", f"contrato e aditivo precisam ser citados: {termination_citations}")
    if not any(item.get("documentId") == ids["contract"] and item.get("page") == 2 for item in termination_citations):
        fail("reformulação", f"citação do contrato na página 2 ausente: {termination_citations}")
    if not any(item.get("documentId") == ids["amendment"] and item.get("page") == 1 for item in termination_citations):
        fail("reformulação", f"citação do aditivo na página 1 ausente: {termination_citations}")
    if not all(token in termination.get("answer", "") for token in ("60 dias", "90 dias")):
        fail("reformulação", f"prazos vigente e anterior ausentes: {termination.get('answer')}")
    if any(item.get("documentId") == ids["otherTenant"] for item in termination_citations):
        fail("isolamento", "a resposta citou documento de outro tenant")
    cases.append({"name": "reformulacao", "expectedDocuments": [ids["contract"], ids["amendment"]], "response": termination, "clientLatencyMs": round(termination_ms, 2)})

    penalty, penalty_ms = query(token_a, "Qual a multa por vazamento de dados no Contrato 001?")
    if penalty.get("status") != "INSUFFICIENT_EVIDENCE" or penalty.get("citations"):
        fail("abstenção", f"deveria abster sem citações: {penalty}")
    cases.append({"name": "abstencao", "expectedDocuments": [], "response": penalty, "clientLatencyMs": round(penalty_ms, 2)})

    tenant_b_payment, tenant_b_ms = query(token_b, "Qual o prazo de pagamento da fatura do Contrato 001?")
    if tenant_b_payment.get("status") != "ANSWERED" or "7 dias úteis" not in tenant_b_payment.get("answer", ""):
        fail("isolamento", f"o tenant B deveria ver seu prazo de 7 dias úteis: {tenant_b_payment}")
    if any(item.get("documentId") != ids["otherTenant"] for item in tenant_b_payment.get("citations", [])):
        fail("isolamento", f"resposta do tenant B citou documento alheio: {tenant_b_payment}")
    cross_tenant_status, cross_tenant_body, _ = request(
        "GET", f"/v1/documents/{ids['otherTenant']}", token_a, timeout=10)
    if cross_tenant_status != 404:
        fail("isolamento", f"consulta cruzada de documento deveria retornar 404, recebeu {cross_tenant_status}: {cross_tenant_body}")

    query_latencies = [case["clientLatencyMs"] for case in cases]
    report = {
        "executedAt": datetime.now(timezone.utc).isoformat(),
        "mode": active_mode(),
        "sampleSize": len(cases),
        "documentStates": states,
        "cases": cases,
        "clientQueryLatencyP95Ms": sorted(query_latencies)[-1],
        "observedSearchCount": sum(case["response"].get("attempts", 0) for case in cases),
        "observedReformulations": sum(1 for case in cases if case["response"].get("rewritten")),
        "observedAbstentions": sum(1 for case in cases if case["response"].get("status") == "INSUFFICIENT_EVIDENCE"),
        "tenantIsolationCheck": {
            "tenantBQuery": tenant_b_payment,
            "tenantBClientLatencyMs": round(tenant_b_ms, 2),
            "crossTenantDocumentStatus": cross_tenant_status,
        },
        "micrometer": {
            "retrievalLatency": metric("jurisrag.retrieval.latency", token_a),
            "generationLatency": metric("jurisrag.generation.latency", token_a),
            "attempts": metric("jurisrag.agent.attempts", token_a),
            "reformulations": metric("jurisrag.agent.reformulations", token_a),
            "abstentions": metric("jurisrag.agent.abstentions", token_a),
        },
    }
    output_dir = ROOT / "portfolio-publico" / "resultados"
    output_dir.mkdir(parents=True, exist_ok=True)
    output_path = output_dir / "ultima-execucao.json"
    output_path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"result": "PASS", "sampleSize": report["sampleSize"], "output": str(output_path.relative_to(ROOT))}, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as error:
        print(str(error), file=sys.stderr)
        raise SystemExit(1)
